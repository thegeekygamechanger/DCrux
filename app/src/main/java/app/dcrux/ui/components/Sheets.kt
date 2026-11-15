package app.dcrux.ui.components

import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** The app's slide-up sheet: a title, then scrolling content. Used for everything that pops up from the bottom. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CruxSheet(
    title: String,
    onDismiss: () -> Unit,
    icon: ImageVector? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val maxHeight = with(LocalDensity.current) { (LocalWindowInfo.current.containerSize.height * 0.9f).toDp() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).navigationBarsPadding()) {
            Row(Modifier.padding(start = 22.dp, end = 22.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                }
                Text(title, style = MaterialTheme.typography.headlineSmall)
            }
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 22.dp, end = 22.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}

/** A heading and a paragraph, for the plain-text sheets (terms, privacy). */
@Composable
fun SheetPara(heading: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (heading.isNotBlank()) Text(heading, style = MaterialTheme.typography.titleSmall)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private class Piece(val x: Float, val angle: Float, val speed: Float, val spin: Float, val color: Color, val w: Float, val h: Float, val fromLeft: Boolean, val delay: Float)

/**
 * Party poppers: two bursts of paper fired up from the bottom corners, falling back under gravity.
 * Drawn once over the screen; it does not take touches.
 */
@Composable
fun Confetti(modifier: Modifier = Modifier, colors: List<Color> = ConfettiColors) {
    val t = remember { Animatable(0f) }
    val pieces = remember {
        val r = Random(System.nanoTime())
        List(150) { i ->
            val left = i % 2 == 0
            // Aim up and inwards, in a fan.
            val a = if (left) r.nextDouble(-85.0, -35.0) else r.nextDouble(-145.0, -95.0)
            Piece(
                x = if (left) 0.02f else 0.98f, angle = (a * PI / 180).toFloat(), speed = r.nextDouble(0.75, 1.75).toFloat(),
                spin = r.nextDouble(-900.0, 900.0).toFloat(), color = colors[r.nextInt(colors.size)],
                w = r.nextDouble(6.0, 12.0).toFloat(), h = r.nextDouble(10.0, 20.0).toFloat(), fromLeft = left,
                delay = if (i < 100) 0f else 0.22f,
            )
        }
    }
    LaunchedEffect(Unit) { t.animateTo(1f, tween(3600, easing = LinearEasing)) }
    Canvas(modifier.fillMaxSize()) {
        val time = t.value * 3.6f
        if (t.value >= 1f) return@Canvas
        val unit = size.height
        pieces.forEach { p ->
            val s = time - p.delay * 3.6f
            if (s <= 0f) return@forEach
            val vx = cos(p.angle) * p.speed * unit * 0.62f
            val vy = sin(p.angle) * p.speed * unit * 0.95f
            // Air drag on the sideways motion, gravity on the vertical.
            val x = p.x * size.width + vx * (1 - kotlin.math.exp(-1.6f * s)) / 1.6f
            val y = size.height + vy * s + 0.5f * unit * 0.95f * s * s
            if (y > size.height + 40f) return@forEach
            val fade = ((3.6f - time) / 0.8f).coerceIn(0f, 1f)
            rotate(p.spin * s, Offset(x, y)) {
                drawRect(p.color.copy(alpha = fade), Offset(x - p.w.dp.toPx() / 2, y - p.h.dp.toPx() / 2), Size(p.w.dp.toPx(), p.h.dp.toPx() * kotlin.math.abs(cos(s * 6f + p.spin)).coerceAtLeast(0.25f)))
            }
        }
    }
}

val ConfettiColors = listOf(
    Color(0xFF4553C9), Color(0xFF6A78E0), Color(0xFF1FA97A), Color(0xFFF5A300), Color(0xFF2FA4E7), Color(0xFFE9C46A),
)
