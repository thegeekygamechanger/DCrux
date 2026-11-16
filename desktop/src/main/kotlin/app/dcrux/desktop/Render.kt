package app.dcrux.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
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
 * with no display (and in automated builds). Usage: Render <data dir> <output dir> [sync|nosync] [steps...].
 */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
fun main(args: Array<String>) {
    // Everything runs on the interface thread, as in the real window. On any other thread, changes that arrive
    // from the database after the first frame can go unnoticed and the picture shows less than the app would.
    val ui = Dispatchers.Main
    runBlocking(ui) {
    val data = File(args.getOrElse(0) { "build/render-data" }).apply { mkdirs() }
    val out = File(args.getOrElse(1) { "build/render" }).apply { mkdirs() }
    val platform = DesktopPlatform(data)
    val graph = desktopGraph(platform)
    val host = DesktopHost(graph)
    SingletonImageLoader.setSafe { ctx -> ImageLoader.Builder(ctx).components { add(OkHttpNetworkFetcherFactory(callFactory = { Http.client })) }.build() }
    if (args.getOrNull(2) == "sync") { println("sync ok: " + graph.sync.run()) }

    // -Drender.size=1920x1080x1 draws a landscape window; the default is a phone-shaped one at double density.
    val (width, height, scale) = System.getProperty("render.size", "960x1840x2").split('x').let { Triple(it[0].toInt(), it[1].toInt(), it.getOrElse(2) { "1" }.toFloat()) }
    val scene = ImageComposeScene(width, height, Density(scale), coroutineContext = ui) { Box(Modifier.fillMaxSize()) { DCruxRoot(host) } }
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
    val keySource = object : java.awt.Component() {}
    var mouse = Offset(width / 2f, height / 2f)
    // Steps are "name" (save a picture), "x,y" (click there, in picture pixels), "wait:ms", "scroll:n" (wheel
    // notches at the last place clicked; negative scrolls up), "type:text" (an underscore is a space) or "enter".
    val steps = args.drop(3).ifEmpty { listOf("wait:2500", "first") }
    for (s in steps) when {
        s.startsWith("wait:") -> settle(s.removePrefix("wait:").toLong())
        s.startsWith("scroll:") -> {
            val notches = s.removePrefix("scroll:").toFloat()
            scene.sendPointerEvent(PointerEventType.Move, mouse)
            // A few small turns of the wheel, as a hand makes, so lists follow as they would in the window.
            val steps = kotlin.math.ceil(kotlin.math.abs(notches) / 3f).toInt().coerceAtLeast(1)
            repeat(steps) { scene.sendPointerEvent(PointerEventType.Scroll, mouse, scrollDelta = Offset(0f, notches / steps)); settle(120) }
            settle(600)
        }
        s.startsWith("type:") -> {
            // Typed characters reach a text field the way a keyboard's do on a computer: as "key typed" events.
            for (ch in s.removePrefix("type:").replace('_', ' ')) {
                scene.sendKeyEvent(KeyEvent(key = Key.Unknown, type = KeyEventType.Unknown, codePoint = ch.code, nativeEvent = java.awt.event.KeyEvent(keySource, java.awt.event.KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, java.awt.event.KeyEvent.VK_UNDEFINED, ch)))
                settle(40)
            }
            settle(300)
        }
        s == "enter" -> {
            scene.sendKeyEvent(KeyEvent(key = Key.Enter, type = KeyEventType.KeyDown, nativeEvent = java.awt.event.KeyEvent(keySource, java.awt.event.KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, java.awt.event.KeyEvent.VK_ENTER, '\n')))
            scene.sendKeyEvent(KeyEvent(key = Key.Enter, type = KeyEventType.KeyUp, nativeEvent = java.awt.event.KeyEvent(keySource, java.awt.event.KeyEvent.KEY_RELEASED, System.currentTimeMillis(), 0, java.awt.event.KeyEvent.VK_ENTER, '\n')))
            settle(600)
        }
        s.contains(',') -> s.split(',').let { mouse = Offset(it[0].toFloat(), it[1].toFloat()); click(mouse.x, mouse.y) }
        else -> shot(s)
    }
    scene.close()
    }
    kotlin.system.exitProcess(0)
}
