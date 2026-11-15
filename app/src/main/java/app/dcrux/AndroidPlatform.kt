package app.dcrux

import kotlinx.coroutines.launch
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.DecodeHintType
import com.google.zxing.BinaryBitmap
import com.google.zxing.BarcodeFormat
import androidx.core.content.FileProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import android.graphics.BitmapFactory
import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import app.dcrux.ai.AiWork
import app.dcrux.audio.AndroidAudioSink
import app.dcrux.audio.MindFlow
import app.dcrux.audio.SoundId
import app.dcrux.data.TestResult
import app.dcrux.lock.AndroidBiometrics
import app.dcrux.mains.AndroidPages
import app.dcrux.pdf.PdfShareProvider
import app.dcrux.pdf.ResultPdf
import app.dcrux.sync.scheduleDailySync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.lang.ref.WeakReference
import java.util.Locale
import kotlin.coroutines.resume

/** Android's side of [Platform]. */
class AndroidPlatform(private val app: Application) : Platform {
    /** The activity on screen, for the few things that need a window (full screen, biometrics, restart). */
    var activity = WeakReference<Activity>(null)

    override val isDesktop = false
    override val versionName: String = BuildConfig.VERSION_NAME
    override val versionCode: Int = BuildConfig.VERSION_CODE
    override val filesDir: File get() = app.filesDir

    override fun openUrl(url: String) {
        runCatching { app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    override fun aiWorkBegin() = AiWork.begin(app)
    override fun aiWorkEnd() = AiWork.end(app)
    override fun scheduleDailySync(enabled: Boolean, wifiOnly: Boolean) = scheduleDailySync(app, enabled, wifiOnly)

    override fun clearWebData() {
        CookieManager.getInstance().removeAllCookies(null)
        WebStorage.getInstance().deleteAllData()
    }

    override fun restart() { activity.get()?.recreate() }

    override fun setImmersive(on: Boolean) {
        val window = activity.get()?.window ?: return
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars.hide(WindowInsetsCompat.Type.systemBars())
        } else bars.show(WindowInsetsCompat.Type.systemBars())
    }

    override fun keepScreenOn(on: Boolean) {
        activity.get()?.window?.decorView?.keepScreenOn = on
    }

    override val sounds: SoundControl = object : SoundControl {
        override val levels: StateFlow<Map<SoundId, Float>> get() = MindFlow.levels
        override val paused: StateFlow<Boolean> get() = MindFlow.paused
        override val timerEnd: StateFlow<Long> get() = MindFlow.timerEnd
        override fun set(id: SoundId, level: Float) = MindFlow.set(app, id, level)
        override fun setMix(mix: Map<SoundId, Float>) = MindFlow.setMix(app, mix)
        override fun setTimer(minutes: Int) = MindFlow.setTimer(minutes)
        override fun pause() = MindFlow.pause(app)
        override fun resume() = MindFlow.resume(app)
        override fun stopAll() = MindFlow.stopAll(app)
    }

    override val pages: PageTools = AndroidPages(app)

    override val canUseBiometrics: Boolean get() = AndroidBiometrics.available(app)
    override fun biometricPrompt(onSuccess: () -> Unit) {
        (activity.get() as? FragmentActivity)?.let { AndroidBiometrics.prompt(it, onSuccess) }
    }

    override fun dynamicColors(dark: Boolean): ColorScheme? =
        if (Build.VERSION.SDK_INT >= 31) (if (dark) dynamicDarkColorScheme(app) else dynamicLightColorScheme(app)) else null

    override fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    override suspend fun position(): Pair<Double, Double>? {
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = runCatching { lm.getProviders(true) }.getOrDefault(emptyList())
        val last = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        // A fix from the last day is plenty for "which city".
        if (last != null && System.currentTimeMillis() - last.time < 24 * 3_600_000L) return last.latitude to last.longitude
        val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER).firstOrNull { it in providers }
            ?: return last?.let { it.latitude to it.longitude }
        val fresh = withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine<Location?> { cont ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        runCatching { lm.removeUpdates(this) }
                        if (cont.isActive) cont.resume(location)
                    }
                    @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }
                runCatching { lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper()) }
                    .onFailure { if (cont.isActive) cont.resume(null) }
                cont.invokeOnCancellation { runCatching { lm.removeUpdates(listener) } }
            }
        }
        return (fresh ?: last)?.let { it.latitude to it.longitude }
    }

    @Suppress("DEPRECATION")
    override suspend fun cityName(lat: Double, lon: Double): Pair<String, String>? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        runCatching {
            val a = Geocoder(app, Locale.ENGLISH).getFromLocation(lat, lon, 1)?.firstOrNull() ?: return@runCatching null
            val city = a.locality ?: a.subAdminArea ?: a.adminArea ?: return@runCatching null
            city to a.adminArea.orEmpty()
        }.getOrNull()
    }

    @Composable
    override fun rememberLocationAsk(onResult: (Boolean) -> Unit): () -> Unit {
        val result by rememberUpdatedState(onResult)
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result(it) }
        return { launcher.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }
    }

    /** Android 13+: the "now playing" notification needs permission; playback works either way. */
    @Composable
    override fun rememberNotificationAsk(): () -> Unit {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        return {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    @Composable
    override fun rememberPagePicker(onPicked: (List<Any>) -> Unit): () -> Unit {
        val picked by rememberUpdatedState(onPicked)
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> picked(uris) }
        return { launcher.launch(arrayOf("image/*", "application/pdf")) }
    }

    @Composable
    override fun rememberCamera(onShot: () -> Unit): ((target: Any) -> Unit)? {
        val shot by rememberUpdatedState(onShot)
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { _ -> shot() }
        return { target -> launcher.launch(target as Uri) }
    }

    /**
     * The phone's own camera app takes a picture of the code and the picture is read here, so DCrux needs
     * no camera permission and no scanning service. The picture is deleted as soon as it has been read.
     */
    @Composable
    override fun rememberQrScanner(onResult: (String?) -> Unit): (() -> Unit)? {
        val result by rememberUpdatedState(onResult)
        val scope = rememberCoroutineScope()
        val file = remember { File(File(app.filesDir, "scan").apply { mkdirs() }, "qr.jpg") }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { _ ->
            scope.launch {
                val text = withContext(Dispatchers.Default) { runCatching { readQr(file) }.getOrNull() }
                file.delete()
                result(text)
            }
        }
        return {
            runCatching {
                file.delete(); file.createNewFile()
                launcher.launch(FileProvider.getUriForFile(app, "${app.packageName}.capture", file))
            }.onFailure { result(null) }
        }
    }

    /** Finds a QR code in a photograph. A photo of a screen can be large and soft, so it is tried at a few sizes. */
    private fun readQr(photo: File): String? {
        if (photo.length() == 0L) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(photo.path, bounds)
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        if (longEdge <= 0) return null
        val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))
        for (target in listOf(1600, 1000, 2400, 640)) {
            var sample = 1
            while (longEdge / (sample * 2) >= target) sample *= 2
            val bmp = BitmapFactory.decodeFile(photo.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: continue
            val pixels = IntArray(bmp.width * bmp.height)
            bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            val source = RGBLuminanceSource(bmp.width, bmp.height, pixels)
            bmp.recycle()
            for (bitmap in listOf(BinaryBitmap(HybridBinarizer(source)), BinaryBitmap(GlobalHistogramBinarizer(source)))) {
                runCatching { return MultiFormatReader().decode(bitmap, hints).text }
            }
        }
        return null
    }

    override fun clipboardText(): String? {
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(app)?.toString()
    }

    override fun openInstallPermission() {
        runCatching {
            app.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override fun sharePdf(testId: Long) = PdfShareProvider.share(activity.get() ?: app, testId)

    override suspend fun pdfPages(result: TestResult): List<ImageBitmap> = withContext(Dispatchers.Default) {
        ResultPdf.bitmaps(app, result).map { it.asImageBitmap() }
    }

    override fun audioSink(sampleRate: Int, frames: Int): AudioSink = AndroidAudioSink(sampleRate, frames)
    override fun uptime(): Long = SystemClock.elapsedRealtime()
}

val Context.graph: Graph get() = (applicationContext as DCruxApp).graph
