package app.dcrux.ui.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

private enum class Phase { DAWN, DAY, DUSK, NIGHT }
private enum class Sky { CLEAR, PARTLY, CLOUDY, FOG, DRIZZLE, RAIN, SNOW, STORM }

private fun skyOf(code: Int?): Sky = when (code) {
    null, 0, 1 -> Sky.CLEAR
    2 -> Sky.PARTLY
    3 -> Sky.CLOUDY
    45, 48 -> Sky.FOG
    51, 53, 55, 56, 57 -> Sky.DRIZZLE
    71, 73, 75, 77, 85, 86 -> Sky.SNOW
    95, 96, 99 -> Sky.STORM
    else -> Sky.RAIN
}

private fun phaseOf(hour: Float, isDay: Boolean?): Phase {
    val byClock = when {
        hour < 5f -> Phase.NIGHT
        hour < 7f -> Phase.DAWN
        hour < 16.5f -> Phase.DAY
        hour < 18.75f -> Phase.DUSK
        else -> Phase.NIGHT
    }
    // The weather service knows the real sunrise and sunset for the place; trust it over the clock.
    return when {
        isDay == false && byClock == Phase.DAY -> Phase.NIGHT
        isDay == true && byClock == Phase.NIGHT -> if (hour < 12f) Phase.DAWN else Phase.DUSK
        else -> byClock
    }
}

private class Cloud(val x: Float, val y: Float, val scale: Float, val speed: Int)
private class Drop(val x: Float, val y: Float, val speed: Int, val len: Float)
private class Star(val x: Float, val y: Float, val r: Float, val phase: Float)
private class Ridge(val f1: Float, val p1: Float, val f2: Float, val p2: Float)

/**
 * A small landscape drawn behind the date: the sky for the hour, the sun or the moon and stars,
 * and the weather over it (cloud, rain, snow, fog, storm), with the land and the light shifting
 * with the temperature. The hills, clouds and stars are laid out afresh each time the app opens,
 * so no two visits look quite the same. Everything is drawn here; no picture is downloaded.
 */
@Composable
fun WeatherScene(code: Int?, temp: Double?, isDay: Boolean?, hour: Float, modifier: Modifier = Modifier) {
    val seed = remember { Random.nextInt() }
    val sky = skyOf(code)
    val phase = phaseOf(hour, isDay)
    val rnd = remember(seed, sky) { Random(seed) }
    val ridges = remember(seed) { Random(seed).let { r -> List(2) { Ridge(r.nextFloat() * 5f + 4f, r.nextFloat() * 6.28f, r.nextFloat() * 9f + 9f, r.nextFloat() * 6.28f) } } }
    val cloudCount = when (sky) { Sky.CLEAR -> rnd.nextInt(0, 2); Sky.PARTLY -> 3; Sky.FOG -> 2; Sky.CLOUDY, Sky.SNOW -> 5; else -> 6 }
    val clouds = remember(seed, sky) { Random(seed + 1).let { r -> List(cloudCount) { Cloud(r.nextFloat(), 0.08f + r.nextFloat() * 0.34f, 0.7f + r.nextFloat() * 0.7f, r.nextInt(1, 4)) } } }
    val dropCount = when (sky) { Sky.DRIZZLE -> 26; Sky.RAIN -> 64; Sky.STORM -> 90; Sky.SNOW -> 46; else -> 0 }
    val drops = remember(seed, sky) { Random(seed + 2).let { r -> List(dropCount) { Drop(r.nextFloat(), r.nextFloat(), r.nextInt(1, 4), 0.6f + r.nextFloat() * 0.8f) } } }
    val stars = remember(seed) { Random(seed + 3).let { r -> List(46) { Star(r.nextFloat(), r.nextFloat() * 0.62f, 0.6f + r.nextFloat() * 1.1f, r.nextFloat()) } } }

    val loop = rememberInfiniteTransition(label = "scene")
    val drift by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(90_000, easing = LinearEasing)), label = "drift")
    val fall by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(if (sky == Sky.SNOW) 9000 else 1500, easing = LinearEasing)), label = "fall")
    val pulse by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "pulse")

    val hot = (temp ?: 25.0) >= 36
    val cold = (temp ?: 25.0) <= 10

    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val night = phase == Phase.NIGHT

        // ---- sky
        var top: Color
        var bottom: Color
        when (phase) {
            Phase.DAWN -> { top = Color(0xFF35458A); bottom = Color(0xFFEFA06E) }
            Phase.DAY -> { top = Color(0xFF2863C4); bottom = Color(0xFF86BDEE) }
            Phase.DUSK -> { top = Color(0xFF33388A); bottom = Color(0xFFEC8457) }
            Phase.NIGHT -> { top = Color(0xFF080D2B); bottom = Color(0xFF242D68) }
        }
        val grey = when (sky) { Sky.CLEAR -> 0f; Sky.PARTLY -> 0.15f; Sky.CLOUDY, Sky.SNOW -> 0.55f; Sky.FOG -> 0.6f; Sky.DRIZZLE -> 0.6f; Sky.RAIN -> 0.75f; Sky.STORM -> 0.9f }
        val greyTop = if (night) Color(0xFF12151D) else if (sky == Sky.STORM) Color(0xFF39414E) else Color(0xFF66748A)
        val greyBottom = if (night) Color(0xFF272C38) else if (sky == Sky.FOG) Color(0xFFC3CAD3) else Color(0xFF9FAAB8)
        top = lerp(top, greyTop, grey)
        bottom = lerp(bottom, greyBottom, grey)
        // Heat warms the horizon, cold cools it.
        if (hot && !night) bottom = lerp(bottom, Color(0xFFF4B463), 0.35f * (1f - grey))
        if (cold) bottom = lerp(bottom, Color(0xFFD5E6F6), 0.3f)
        drawRect(Brush.verticalGradient(listOf(top, bottom)))

        // ---- stars and moon
        if (night && grey < 0.7f) {
            stars.forEach { s ->
                val twinkle = 0.45f + 0.55f * (0.5f + 0.5f * sin(2 * PI.toFloat() * (pulse * 3f + s.phase)))
                drawCircle(Color.White.copy(alpha = twinkle * (1f - grey)), s.r.dp.toPx() * 0.9f, Offset(s.x * w, s.y * h))
            }
        }
        if (night && sky != Sky.STORM && sky != Sky.RAIN) {
            val c = Offset(w * 0.56f, h * 0.27f)
            val r = h * 0.11f
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.22f * (1f - grey * 0.6f)), Color.Transparent), c, r * 3f), r * 3f, c)
            drawCircle(Color(0xFFEDEFF7).copy(alpha = 1f - grey * 0.5f), r, c)
            drawCircle(lerp(top, bottom, 0.3f), r * 0.86f, Offset(c.x + r * 0.42f, c.y - r * 0.18f))   // the bite that makes it a crescent
        }

        // ---- sun: it crosses the card from sunrise to sunset
        if (!night && (sky == Sky.CLEAR || sky == Sky.PARTLY || sky == Sky.FOG)) {
            val frac = ((hour - 6f) / 12f).coerceIn(0.04f, 0.96f)
            val c = Offset(w * (0.14f + 0.72f * frac), h * (0.78f - sin(frac * PI.toFloat()) * 0.58f))
            val r = h * (if (hot) 0.15f else 0.125f)
            val warm = phase != Phase.DAY
            val core = if (warm) Color(0xFFFFC27A) else Color(0xFFFFEDB0)
            val veil = if (sky == Sky.FOG) 0.45f else 1f
            drawCircle(Brush.radialGradient(listOf(core.copy(alpha = (if (hot) 0.6f else 0.45f) * veil), Color.Transparent), c, r * 3.6f), r * 3.6f, c)
            drawCircle(core.copy(alpha = veil), r, c)
        }

        // ---- far and near hills; their colour follows the light and the temperature
        val (far, near) = when {
            sky == Sky.SNOW || (cold && (temp ?: 25.0) <= 2) -> Color(0xFFDCE6F0) to Color(0xFFB9C8D8)
            night -> Color(0xFF151C3D) to Color(0xFF0B0F24)
            phase != Phase.DAY -> Color(0xFF4A3A6E) to Color(0xFF281F45)
            hot && sky == Sky.CLEAR -> Color(0xFFC7A066) to Color(0xFF9C7645)
            grey > 0.5f -> Color(0xFF466355) to Color(0xFF2C4439)
            else -> Color(0xFF3F9168) to Color(0xFF256B4D)
        }
        ridges.forEachIndexed { i, rg ->
            val base = h * (if (i == 0) 0.70f else 0.84f)
            val amp = h * (if (i == 0) 0.17f else 0.11f)
            val path = Path().apply {
                moveTo(0f, h)
                var x = 0f
                while (x <= w + 8f) {
                    val u = x / w
                    lineTo(x, base - amp * (0.6f * sin(u * rg.f1 + rg.p1) + 0.4f * sin(u * rg.f2 + rg.p2)))
                    x += 8f
                }
                lineTo(w, h); close()
            }
            drawPath(path, if (i == 0) far else near)
        }

        // ---- clouds, drifting
        val cloudColor = when {
            sky == Sky.STORM -> Color(0xFF2B313C)
            night -> Color(0xFF3B4466)
            sky == Sky.RAIN || sky == Sky.DRIZZLE -> Color(0xFF7C8796)
            phase != Phase.DAY -> Color(0xFFF6C9B4)
            else -> Color.White
        }.copy(alpha = if (sky == Sky.CLEAR || sky == Sky.PARTLY) 0.82f else 0.9f)
        clouds.forEach { c -> cloud(Offset((((c.x + drift * c.speed) % 1f) * 1.5f - 0.25f) * w, c.y * h), h * 0.11f * c.scale, cloudColor) }

        // ---- fog lies in bands over the land
        if (sky == Sky.FOG) repeat(3) { i ->
            val x = (((i * 0.37f + drift * (i + 1)) % 1f) * 1.6f - 0.4f) * w
            drawRoundRect(Color.White.copy(alpha = 0.26f), Offset(x, h * (0.46f + i * 0.14f)), Size(w * 0.8f, h * 0.12f), CornerRadius(h * 0.06f))
        }

        // ---- rain and snow
        if (sky == Sky.SNOW) drops.forEach { d ->
            val y = ((d.y + fall * d.speed) % 1f) * h
            val x = d.x * w + sin((fall * d.speed + d.y) * 2 * PI.toFloat()) * 6.dp.toPx()
            drawCircle(Color.White.copy(alpha = 0.9f), (1.4f + d.len).dp.toPx(), Offset(x, y))
        } else drops.forEach { d ->
            val y = ((d.y + fall * d.speed) % 1f) * (h + 20.dp.toPx()) - 10.dp.toPx()
            val x = d.x * w * 1.1f - (y / h) * 10.dp.toPx()
            val len = 9.dp.toPx() * d.len
            drawLine(Color(0xFFD9E8FA).copy(alpha = 0.6f), Offset(x, y), Offset(x - len * 0.22f, y + len), strokeWidth = 1.3f.dp.toPx(), cap = StrokeCap.Round)
        }

        // ---- lightning: two quick flashes every few seconds
        if (sky == Sky.STORM) {
            val flash = when { pulse in 0.50f..0.515f -> 0.5f; pulse in 0.53f..0.55f -> 0.32f; else -> 0f }
            if (flash > 0f) drawRect(Color.White.copy(alpha = flash))
        }
    }
}

/** A cloud is four overlapping puffs on a flat base. */
private fun DrawScope.cloud(at: Offset, r: Float, color: Color) {
    drawRoundRect(color, Offset(at.x - r * 1.9f, at.y), Size(r * 3.8f, r * 0.95f), CornerRadius(r * 0.5f))
    drawCircle(color, r * 0.85f, Offset(at.x - r * 1.05f, at.y + r * 0.1f))
    drawCircle(color, r * 1.15f, Offset(at.x - r * 0.1f, at.y - r * 0.25f))
    drawCircle(color, r * 0.9f, Offset(at.x + r * 0.95f, at.y + r * 0.05f))
}
