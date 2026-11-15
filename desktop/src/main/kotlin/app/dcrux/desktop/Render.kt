package app.dcrux.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import app.dcrux.net.Http
import app.dcrux.ui.DCruxRoot
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Draws the app off-screen and saves pictures of it: a way to check the interface on a machine
 * with no display (and in automated builds). Usage: Render <data dir> <output dir> [sync].
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    runBlocking(Dispatchers.Default) {
    val data = File(args.getOrElse(0) { "build/render-data" }).apply { mkdirs() }
    val out = File(args.getOrElse(1) { "build/render" }).apply { mkdirs() }
    val platform = DesktopPlatform(data)
    val graph = desktopGraph(platform)
    val host = DesktopHost(graph)
    SingletonImageLoader.setSafe { ctx -> ImageLoader.Builder(ctx).components { add(OkHttpNetworkFetcherFactory(callFactory = { Http.client })) }.build() }
    if (args.getOrNull(2) == "sync") { println("sync ok: " + graph.sync.run()) }

    val width = 960; val height = 1840
    val scene = ImageComposeScene(width, height, Density(2f)) { Box(Modifier.fillMaxSize()) { DCruxRoot(host) } }
    var clock = 0L
    suspend fun settle(ms: Long) { val end = clock + ms; while (clock < end) { scene.render(clock * 1_000_000); clock += 32; delay(8) } }
    fun shot(name: String) {
        val bytes = scene.render(clock * 1_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes
        File(out, "$name.png").writeBytes(bytes); println("saved $name.png")
    }
    suspend fun click(x: Float, y: Float) {
        scene.sendPointerEvent(PointerEventType.Move, Offset(x, y)); scene.sendPointerEvent(PointerEventType.Press, Offset(x, y))
        settle(60); scene.sendPointerEvent(PointerEventType.Release, Offset(x, y)); settle(900)
    }
    // Steps are "name" (save a picture), "x,y" (click there, in picture pixels) or "wait:ms".
    val steps = args.drop(3).ifEmpty { listOf("wait:2500", "first") }
    for (s in steps) when {
        s.startsWith("wait:") -> settle(s.removePrefix("wait:").toLong())
        s.contains(',') -> s.split(',').let { click(it[0].toFloat(), it[1].toFloat()) }
        else -> shot(s)
    }
    scene.close()
    }
    kotlin.system.exitProcess(0)
}
