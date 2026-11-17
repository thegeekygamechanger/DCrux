package app.dcrux.desktop

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.dcrux.BuildKeys
import app.dcrux.Graph
import app.dcrux.Host
import app.dcrux.data.DesktopSecureStore
import app.dcrux.data.Prefs
import app.dcrux.data.desktopSettings
import app.dcrux.data.openDesktopDb
import app.dcrux.net.Http
import app.dcrux.ui.BackStack
import app.dcrux.ui.DCruxRoot
import app.dcrux.update.DesktopUpdates
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Builds the app's long-lived objects for a computer. [dir] is where it keeps its files. */
fun desktopGraph(platform: DesktopPlatform): Graph {
    val dir = platform.filesDir
    return Graph(
        platform = platform,
        secure = DesktopSecureStore(dir),
        prefs = Prefs(desktopSettings(dir)),
        keys = BuildKeys("", "", BuildInfo.PX, BuildInfo.SA, BuildInfo.SB),
        openDb = { openDesktopDb(dir) },
        makeUpdates = { DesktopUpdates(it) },
    )
}

class DesktopHost(override val graph: Graph) : Host {
    override val locked: MutableState<Boolean> = mutableStateOf(false)
    override val sharedText: MutableState<String?> = mutableStateOf(null)
    override fun systemBars(dark: Boolean) {}
}

fun main() {
    val platform = DesktopPlatform(appDataDir())
    val graph = desktopGraph(platform)
    val host = DesktopHost(graph)
    // Pictures load through the app's own HTTP client, as on the phone.
    SingletonImageLoader.setSafe { ctx ->
        ImageLoader.Builder(ctx).components { add(OkHttpNetworkFetcherFactory(callFactory = { Http.client })) }.crossfade(true)
            // Decoded pictures kept ready in memory: enough for the lists on screen and a few behind them, and no more.
            // (Left to itself the cache would take a quarter of whatever the heap is allowed to grow to.)
            .memoryCache { coil3.memory.MemoryCache.Builder().maxSizeBytes(48L * 1024 * 1024).build() }
            .build()
    }
    val atStart = runBlocking { graph.prefs.get() }
    host.locked.value = atStart.lockEnabled && graph.secure.hasPin()
    graph.cloud.start()
    graph.cloud.setActive(true)
    // Makes sure a class archive exists for the next start (see StartArchive.kt); it waits before doing anything.
    StartArchive.keepFresh(graph.scope)
    graph.scope.launch {
        val s = graph.prefs.get()
        launch { graph.updates.check() }
        // A computer has no overnight job: catch up on opening when the last sync is half a day old.
        if (s.dailySync && System.currentTimeMillis() - s.lastSync > 12 * 3_600_000L) graph.sync.run()
    }

    application {
        // Opens filling the screen in the landscape layout; made narrow, it turns into the phone's single column.
        val state = rememberWindowState(placement = WindowPlacement.Maximized, size = DpSize(1280.dp, 800.dp))
        LaunchedEffect(platform.fullScreen) { state.placement = if (platform.fullScreen) WindowPlacement.Fullscreen else WindowPlacement.Maximized }
        // A smooth arrival instead of a blink: the launcher's start-up picture stays up while the app loads; the
        // window is only shown once its first screen has been put together, already in the app's own background
        // colour, and the screen then fades in over it.
        var ready by remember { mutableStateOf(false) }
        // Should putting the first screen together ever take long, the window is shown anyway.
        LaunchedEffect(Unit) { kotlinx.coroutines.delay(2500); ready = true }
        Window(
            onCloseRequest = { graph.platform.sounds.stopAll(); exitApplication() },
            state = state,
            visible = ready,
            title = "DCrux",
            onPreviewKeyEvent = { e -> e.type == KeyEventType.KeyDown && e.key == Key.Escape && BackStack.back() },
        ) {
            LaunchedEffect(Unit) {
                platform.frame = window
                runCatching { object {}.javaClass.getResourceAsStream("/icon.png")?.use { window.iconImage = javax.imageio.ImageIO.read(it) } }
            }
            // For measuring start-up: with DCRUX_TIMING set, says when the first frame was drawn; set to a number,
            // it also reports the memory in use that many seconds later and closes. Does nothing otherwise.
            LaunchedEffect(Unit) {
                val timing = System.getenv("DCRUX_TIMING") ?: return@LaunchedEffect
                androidx.compose.runtime.withFrameNanos { }
                val started = ProcessHandle.current().info().startInstant().orElse(null)
                println("DCRUX_TIMING first frame after " + (started?.let { java.time.Duration.between(it, java.time.Instant.now()).toMillis() } ?: -1) + " ms")
                // "12f" also draws a frame as often as the screen allows for those seconds and reports how even they were.
                val seconds = timing.removeSuffix("f").toLongOrNull() ?: return@LaunchedEffect
                if (timing.endsWith("f")) {
                    val gaps = ArrayList<Long>()
                    var last = androidx.compose.runtime.withFrameNanos { it }
                    val until = last + seconds * 1_000_000_000L
                    while (last < until) { val now = androidx.compose.runtime.withFrameNanos { it }; gaps += (now - last) / 1000; last = now }
                    // The first two seconds are start-up, when pictures and lists are still arriving; smoothness is judged after them.
                    var passed = 0L
                    val steady = gaps.dropWhile { g -> passed += g; passed < 2_000_000 }.sorted()
                    if (steady.isNotEmpty()) println(
                        "DCRUX_TIMING frames ${steady.size}, average ${steady.average().toInt() / 1000.0} ms, 95th ${steady[(steady.size * 95) / 100] / 1000.0} ms, " +
                            "99th ${steady[(steady.size * 99) / 100] / 1000.0} ms, worst ${steady.last() / 1000.0} ms, over 25 ms: ${steady.count { it > 25_000 }}",
                    )
                } else kotlinx.coroutines.delay(seconds * 1000)
                val status = runCatching { java.io.File("/proc/self/status").readLines().filter { it.startsWith("VmRSS") || it.startsWith("RssAnon") || it.startsWith("RssFile") } }.getOrDefault(emptyList())
                println("DCRUX_TIMING after $seconds s: " + status.joinToString(", ") { it.replace(Regex("\\s+"), " ") })
                exitApplication()
            }
            window.minimumSize = java.awt.Dimension(380, 640)
            val dark = app.dcrux.ui.theme.isDark(atStart.themeMode)
            window.background = if (dark) java.awt.Color(0x13, 0x12, 0x18) else java.awt.Color(0xFC, 0xF8, 0xFF)
            val appear by androidx.compose.animation.core.animateFloatAsState(if (ready) 1f else 0f, androidx.compose.animation.core.tween(320), label = "appear")
            // Runs once the first screen has been composed: only then is there something worth showing.
            LaunchedEffect(Unit) { ready = true }
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = appear }) { key(platform.generation) { DCruxRoot(host) } }
        }
    }
}
