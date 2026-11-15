package app.dcrux.desktop

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
        ImageLoader.Builder(ctx).components { add(OkHttpNetworkFetcherFactory(callFactory = { Http.client })) }.crossfade(true).build()
    }
    host.locked.value = runBlocking { graph.prefs.get().lockEnabled } && graph.secure.hasPin()
    graph.cloud.start()
    graph.scope.launch {
        val s = graph.prefs.get()
        launch { graph.updates.check() }
        // A computer has no overnight job: catch up on opening when the last sync is half a day old.
        if (s.dailySync && System.currentTimeMillis() - s.lastSync > 12 * 3_600_000L) graph.sync.run()
    }

    application {
        // A phone-shaped window by default: the app is the same single column on every screen.
        val state = rememberWindowState(size = DpSize(480.dp, 920.dp))
        LaunchedEffect(platform.fullScreen) { state.placement = if (platform.fullScreen) WindowPlacement.Fullscreen else WindowPlacement.Floating }
        Window(
            onCloseRequest = { graph.platform.sounds.stopAll(); exitApplication() },
            state = state,
            title = "DCrux",
            onPreviewKeyEvent = { e -> e.type == KeyEventType.KeyDown && e.key == Key.Escape && BackStack.back() },
        ) {
            LaunchedEffect(Unit) {
                platform.frame = window
                runCatching { object {}.javaClass.getResourceAsStream("/icon.png")?.use { window.iconImage = javax.imageio.ImageIO.read(it) } }
            }
            window.minimumSize = java.awt.Dimension(380, 640)
            // On a wide window the column stays phone-width and sits in the middle.
            Box(Modifier.fillMaxSize().background(Color(0xFF101014)), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = 620.dp).fillMaxHeight()) {
                    key(platform.generation) { DCruxRoot(host) }
                }
            }
        }
    }
}
