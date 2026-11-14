package app.dcrux.ui.components

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The background every screen shares: the theme surface with a soft wash of the brand
 * colours falling from the top, so screens feel lit rather than flat.
 */
@Composable
fun Modifier.appBackground(): Modifier {
    val c = MaterialTheme.colorScheme
    val top = c.primary.copy(alpha = 0.17f)
    val mid = c.tertiary.copy(alpha = 0.07f)
    return this.background(c.surface).drawBehind {
        drawRect(Brush.verticalGradient(listOf(top, mid, Color.Transparent), endY = 460.dp.toPx()))
    }
}

/** A screen's main title, set in the headline face with a gradient running through it. */
@Composable
fun TabTitle(text: String, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Text(
        text, modifier, maxLines = 1, overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.headlineLarge.copy(brush = Brush.linearGradient(listOf(c.primary, c.secondary, c.tertiary))),
    )
}
