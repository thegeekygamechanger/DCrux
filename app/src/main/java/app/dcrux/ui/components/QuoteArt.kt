package app.dcrux.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

private class Scene(
    val sky: List<Color>, val orb: Color, val hills: List<Color>,
    val night: Boolean = false, val water: Boolean = false, val aurora: Boolean = false,
)

private val scenes = listOf(
    Scene(listOf(Color(0xFF2B1B5A), Color(0xFFC2417A), Color(0xFFFFB26B)), Color(0xFFFFE6A6), listOf(Color(0xFF6A4399), Color(0xFF42287A), Color(0xFF20123D))),
    Scene(listOf(Color(0xFF0E2A5C), Color(0xFF3F6FBF), Color(0xFFFFC38A)), Color(0xFFFFF1C9), listOf(Color(0xFF2B5C9A), Color(0xFF173F73), Color(0xFF0B2545)), water = true),
    Scene(listOf(Color(0xFF050718), Color(0xFF121A4A), Color(0xFF2A2F7A)), Color(0xFFF4F1DE), listOf(Color(0xFF1F2459), Color(0xFF12163F), Color(0xFF070920)), night = true),
    Scene(listOf(Color(0xFF0F4C5C), Color(0xFF5FA8A3), Color(0xFFF2E8B6)), Color(0xFFFFF7D6), listOf(Color(0xFF3A8D68), Color(0xFF1F6248), Color(0xFF0D3A2B))),
    Scene(listOf(Color(0xFF3D1E5F), Color(0xFFE0563F), Color(0xFFFFD27A)), Color(0xFFFFF0C2), listOf(Color(0xFFCB6D34), Color(0xFF934324), Color(0xFF4E1F18))),
    Scene(listOf(Color(0xFF04101F), Color(0xFF0B3B4F), Color(0xFF0E6B62)), Color(0xFFE8F6EF), listOf(Color(0xFF0E4A4A), Color(0xFF082F36), Color(0xFF041A22)), night = true, aurora = true),
)

private fun DrawScope.ridge(rnd: Random, base: Float, height: Float, color: Color) {
    val w = size.width
    val a1 = rnd.nextFloat() * 6f; val a2 = rnd.nextFloat() * 6f; val a3 = rnd.nextFloat() * 6f
    val f1 = 1.2f + rnd.nextFloat() * 1.4f; val f2 = 3f + rnd.nextFloat() * 2.5f; val f3 = 7f + rnd.nextFloat() * 5f
    val path = Path().apply {
        moveTo(0f, size.height)
        var x = 0f
        while (x <= w) {
            val t = x / w * 2f * PI.toFloat()
            val y = base - height * (0.55f * sin(t * f1 + a1) + 0.3f * sin(t * f2 + a2) + 0.15f * sin(t * f3 + a3) + 1f) / 2f
            lineTo(x, y)
            x += w / 90f
        }
        lineTo(w, size.height)
        close()
    }
    drawPath(path, color)
}

/**
 * A small landscape painted in code: sky, sun or moon, stars, layered ridges, water.
 * [scene] picks the kind of picture (tied to the quotation); [seed] changes every day,
 * so the same line never gets the same picture twice. Nothing here is a photograph.
 */
@Composable
fun QuoteArt(scene: Int, seed: Int, modifier: Modifier = Modifier) {
    val sc = scenes[scene.mod(scenes.size)]
    Canvas(modifier) {
        val rnd = Random(seed)
        val w = size.width
        val h = size.height
        drawRect(Brush.verticalGradient(sc.sky))

        if (sc.night) {
            repeat(70) {
                val x = rnd.nextFloat() * w
                val y = rnd.nextFloat() * h * 0.6f
                drawCircle(Color.White.copy(alpha = 0.35f + rnd.nextFloat() * 0.6f), radius = 0.6f + rnd.nextFloat() * 1.8f, center = Offset(x, y))
            }
        }
        if (sc.aurora) {
            repeat(3) { k ->
                val band = Path().apply {
                    val top = h * (0.08f + 0.1f * k)
                    val ph = rnd.nextFloat() * 6f
                    moveTo(0f, top)
                    var x = 0f
                    while (x <= w) { lineTo(x, top + h * 0.07f * sin(x / w * 5f + ph)); x += w / 60f }
                    x = w
                    while (x >= 0f) { lineTo(x, top + h * 0.2f + h * 0.06f * sin(x / w * 4f + ph + 1f)); x -= w / 60f }
                    close()
                }
                drawPath(band, Brush.verticalGradient(listOf(Color(if (k == 1) 0xFFB07CFF else 0xFF58F0B0).copy(alpha = 0.35f), Color.Transparent), startY = h * 0.05f, endY = h * 0.45f))
            }
        }

        val orb = Offset(w * (0.62f + rnd.nextFloat() * 0.26f), h * (if (sc.night) 0.2f + rnd.nextFloat() * 0.1f else 0.38f + rnd.nextFloat() * 0.1f))
        val r = h * (if (sc.night) 0.075f else 0.11f)
        drawCircle(Brush.radialGradient(listOf(sc.orb.copy(alpha = 0.55f), Color.Transparent), center = orb, radius = r * 3.4f), radius = r * 3.4f, center = orb)
        drawCircle(sc.orb, radius = r, center = orb)

        if (sc.water) {
            val horizon = h * 0.6f
            ridge(rnd, horizon, h * 0.1f, sc.hills[0])
            drawRect(Brush.verticalGradient(listOf(sc.hills[1], sc.hills[2]), startY = horizon, endY = h), topLeft = Offset(0f, horizon), size = Size(w, h - horizon))
            repeat(16) { i ->
                val y = horizon + (h - horizon) * (i + 1) / 17f
                val half = r * (1f + i * 0.3f) * (0.6f + rnd.nextFloat() * 0.6f)
                drawLine(sc.orb.copy(alpha = 0.5f - i * 0.02f), Offset(orb.x - half, y), Offset(orb.x + half, y), strokeWidth = 2.2f)
            }
        } else {
            ridge(rnd, h * 0.74f, h * 0.3f, sc.hills[0])
            ridge(rnd, h * 0.86f, h * 0.26f, sc.hills[1])
            ridge(rnd, h * 1.0f, h * 0.22f, sc.hills[2])
        }
        // darken the lower half so the quotation stays readable on any picture
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.62f)), startY = h * 0.3f, endY = h))
    }
}
