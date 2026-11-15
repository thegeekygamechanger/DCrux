package app.dcrux

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import app.dcrux.audio.SoundId
import app.dcrux.data.TestResult
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Everything DCrux needs from the device it runs on. The screens and the app's logic are the
 * same on a phone and on a computer; this is the one place they differ. There is one
 * implementation for Android and one for the desktop (Linux and Windows).
 */
interface Platform {
    val isDesktop: Boolean
    val versionName: String
    val versionCode: Int

    /** A private folder for the app's own files. */
    val filesDir: File

    fun openUrl(url: String)

    /** Tells the system an AI request is in flight, so it is not cut off when the app goes to the background. */
    fun aiWorkBegin()
    fun aiWorkEnd()

    fun scheduleDailySync(enabled: Boolean, wifiOnly: Boolean)

    /** Forgets cookies and site data of the in-app browser. */
    fun clearWebData()

    /** Starts the interface afresh (after a reset). */
    fun restart()

    /** Hides the system's bars (phone) or fills the screen (computer) for full-screen reading. */
    fun setImmersive(on: Boolean)
    fun keepScreenOn(on: Boolean)

    val sounds: SoundControl
    val pages: PageTools

    val canUseBiometrics: Boolean
    fun biometricPrompt(onSuccess: () -> Unit)

    /** Colours taken from the system (Material You), or null where the system has none to offer. */
    fun dynamicColors(dark: Boolean): ColorScheme?

    fun hasLocationPermission(): Boolean

    /** The device's approximate position as latitude and longitude, or null when it cannot say. */
    suspend fun position(): Pair<Double, Double>?

    /** City and region for a position from the system's own place lookup, when it has one. */
    suspend fun cityName(lat: Double, lon: Double): Pair<String, String>?

    /** Returns a function that asks for the location permission; [onResult] hears the answer. */
    @Composable fun rememberLocationAsk(onResult: (Boolean) -> Unit): () -> Unit

    /** Returns a function that asks, where needed, for permission to show the "now playing" notification. */
    @Composable fun rememberNotificationAsk(): () -> Unit

    /** Returns a function that opens the file chooser for answer pages (pictures or PDFs). */
    @Composable fun rememberPagePicker(onPicked: (List<Any>) -> Unit): () -> Unit

    /** Returns a function that opens the camera to photograph a page into a capture target, or null where there is no camera to open. */
    @Composable fun rememberCamera(onShot: () -> Unit): ((target: Any) -> Unit)?

    /**
     * Returns a function that opens the camera to read a QR code and hands back what it says (null when
     * none was found), or null where there is no camera to open. Used to pair with another device.
     */
    @Composable fun rememberQrScanner(onResult: (String?) -> Unit): (() -> Unit)?

    /** Text on the clipboard, for the Paste button. */
    fun clipboardText(): String?

    /** The system's screen for letting the app install its own updates (Android only). */
    fun openInstallPermission()

    /** Hands a freshly made PDF of a result to another app; it is never stored by DCrux. */
    fun sharePdf(testId: Long)

    /** The pages of a result's PDF as pictures, made in memory for the in-app viewer. */
    suspend fun pdfPages(result: TestResult): List<ImageBitmap>

    /** Where generated sound goes. */
    fun audioSink(sampleRate: Int, frames: Int): AudioSink

    /** A clock that keeps running while the device sleeps, in milliseconds. */
    fun uptime(): Long
}

/** What the Mind Flow screen shows and changes. */
interface SoundControl {
    val levels: StateFlow<Map<SoundId, Float>>
    val paused: StateFlow<Boolean>

    /** [Platform.uptime] at which the sleep timer ends, or 0. */
    val timerEnd: StateFlow<Long>
    fun set(id: SoundId, level: Float)
    fun setMix(mix: Map<SoundId, Float>)
    fun setTimer(minutes: Int)
    fun pause()
    fun resume()
    fun stopAll()
}

/** Stereo 16-bit sound output. */
interface AudioSink {
    fun write(samples: ShortArray, count: Int)
    fun close()
}

/** Turning photographs, scans and PDFs into answer pages of a sensible size. */
interface PageTools {
    /** Where the camera should write a photo: something the platform's camera launcher understands. */
    fun captureTarget(file: File): Any

    /** Turns a full-size camera file into a page in [dir]; null when the camera wrote nothing. */
    fun adoptCapture(raw: File, dir: File): String?

    /** Adds one chosen picture or PDF as up to [room] pages in [dir]; returns their file names. */
    fun importPages(source: Any, dir: File, room: Int): List<String>
}

/** What hosts the interface: the activity on a phone, the window on a computer. */
interface Host {
    val graph: Graph
    val locked: MutableState<Boolean>

    /** Text shared into the app from another app (a YouTube link). */
    val sharedText: MutableState<String?>
    fun systemBars(dark: Boolean)
}

val LocalGraph = staticCompositionLocalOf<Graph> { error("No Graph") }
