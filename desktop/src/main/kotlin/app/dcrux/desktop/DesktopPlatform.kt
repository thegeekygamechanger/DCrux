package app.dcrux.desktop

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import app.dcrux.AudioSink
import app.dcrux.PageTools
import app.dcrux.Platform
import app.dcrux.SoundControl
import app.dcrux.audio.DesktopSounds
import app.dcrux.audio.JavaAudioSink
import app.dcrux.data.TestResult
import app.dcrux.mains.DesktopPages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.net.URI

/** The computer's side of [Platform] (Linux and Windows). */
class DesktopPlatform(override val filesDir: File) : Platform {
    /** The app's window, once it exists: the parent for file choosers. */
    var frame: Frame? = null

    /** Read by the window: full-screen reading fills the display. */
    var fullScreen by mutableStateOf(false)
        private set

    /** Bumped to build the interface afresh after a reset. */
    var generation by mutableStateOf(0)
        private set

    override val isDesktop = true
    override val versionName: String = BuildInfo.VERSION
    override val versionCode: Int = BuildInfo.VERSION.split('.').fold(0) { n, p -> n * 100 + (p.toIntOrNull() ?: 0) }

    override fun openUrl(url: String) {
        runCatching { Desktop.getDesktop().browse(URI(url)) }.onFailure {
            // Some Linux desktops have no java.awt.Desktop support; xdg-open is the standard way there.
            runCatching { ProcessBuilder("xdg-open", url).start() }
        }
    }

    // A computer does not suspend an app that is merely in the background, and has no system job scheduler to hand a daily sync to.
    override fun aiWorkBegin() {}
    override fun aiWorkEnd() {}
    override fun scheduleDailySync(enabled: Boolean, wifiOnly: Boolean) {}
    override fun clearWebData() {}
    override fun restart() { generation++ }
    override fun setImmersive(on: Boolean) { fullScreen = on }
    override fun keepScreenOn(on: Boolean) {}

    override val sounds: SoundControl = DesktopSounds(::uptime)
    override val pages: PageTools = DesktopPages()

    override val canUseBiometrics = false
    override fun biometricPrompt(onSuccess: () -> Unit) {}
    override fun dynamicColors(dark: Boolean): ColorScheme? = null

    // A computer has no location service to ask; the city is typed in.
    override fun hasLocationPermission() = false
    override suspend fun position(): Pair<Double, Double>? = null
    override suspend fun cityName(lat: Double, lon: Double): Pair<String, String>? = null
    @Composable override fun rememberLocationAsk(onResult: (Boolean) -> Unit): () -> Unit = { onResult(false) }
    @Composable override fun rememberNotificationAsk(): () -> Unit = {}

    @Composable
    override fun rememberPagePicker(onPicked: (List<Any>) -> Unit): () -> Unit {
        val picked by rememberUpdatedState(onPicked)
        val scope = rememberCoroutineScope()
        return {
            scope.launch {
                // The chooser blocks until it is closed, so it runs off the interface's thread.
                val files = withContext(Dispatchers.IO) {
                    FileDialog(frame, "Choose your answer pages (pictures or PDF)", FileDialog.LOAD).run {
                        isMultipleMode = true
                        setFilenameFilter { _, name -> name.lowercase().let { n -> listOf(".jpg", ".jpeg", ".png", ".webp", ".bmp", ".pdf").any(n::endsWith) } }
                        isVisible = true
                        files?.toList().orEmpty()
                    }
                }
                picked(files)
            }
        }
    }

    @Composable override fun rememberCamera(onShot: () -> Unit): ((target: Any) -> Unit)? = null

    override fun clipboardText(): String? = runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
    }.getOrNull()

    override fun openInstallPermission() {}
    override fun sharePdf(testId: Long) {}
    override suspend fun pdfPages(result: TestResult): List<ImageBitmap> = emptyList()

    override fun audioSink(sampleRate: Int, frames: Int): AudioSink = JavaAudioSink(sampleRate, frames)
    override fun uptime(): Long = System.nanoTime() / 1_000_000
}

/** Where the app keeps its files: the usual per-user place on each system. */
fun appDataDir(): File {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val home = System.getProperty("user.home")
    val base = when {
        os.contains("win") -> System.getenv("APPDATA")?.let(::File) ?: File(home, "AppData/Roaming")
        os.contains("mac") -> File(home, "Library/Application Support")
        else -> System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let(::File) ?: File(home, ".local/share")
    }
    return File(base, "DCrux").apply { mkdirs() }
}
