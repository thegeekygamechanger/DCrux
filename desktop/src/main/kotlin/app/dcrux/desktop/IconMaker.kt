package app.dcrux.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.dcrux.ui.components.AnimatedLogo
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/** Draws the app's own logo to a PNG, for the window and the installers. Usage: IconMaker <file> [pixels] */
fun main(args: Array<String>) {
    val px = args.getOrNull(1)?.toIntOrNull() ?: 512
    val scene = ImageComposeScene(px, px, Density(px / 128f)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { AnimatedLogo(120.dp, animate = false) }
    }
    scene.render(0)
    File(args[0]).apply { parentFile?.mkdirs() }.writeBytes(scene.render(16_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes)
    scene.close()
    kotlin.system.exitProcess(0)
}
