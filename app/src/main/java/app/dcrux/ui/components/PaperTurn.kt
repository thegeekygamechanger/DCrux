package app.dcrux.ui.components

import androidx.compose.foundation.pager.PagerState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import kotlin.math.absoluteValue

/**
 * Turns the outgoing page like a sheet of paper: it stays pinned at the spine and swings
 * over on its left edge while the next page waits underneath. Used by the newspaper pages
 * and by the book-style reader.
 */
fun Modifier.paperTurn(pager: PagerState, page: Int, enabled: Boolean): Modifier {
    if (!enabled) return this
    return this
        .zIndex(-page.toFloat())
        .graphicsLayer {
            val offset = (page - pager.currentPage) - pager.currentPageOffsetFraction
            when {
                offset <= -1f || offset >= 1f -> alpha = 0f
                offset < 0f -> {
                    // leaving: pinned in place, rotating around the left edge towards the reader
                    translationX = -offset * size.width
                    transformOrigin = TransformOrigin(0f, 0.5f)
                    cameraDistance = 34f * density
                    rotationY = offset * 92f
                    alpha = if (offset < -0.96f) 0f else 1f
                }
                else -> {
                    // arriving: lies flat underneath, uncovered as the sheet above turns
                    translationX = -offset * size.width
                    alpha = 1f
                }
            }
        }
        .drawWithContent {
            drawContent()
            val offset = (page - pager.currentPage) - pager.currentPageOffsetFraction
            if (offset < 0f && offset > -1f) {
                // the turning sheet darkens towards its far edge as it lifts
                drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.28f * offset.absoluteValue))))
            } else if (offset > 0f && offset < 1f) {
                // shadow cast on the page below
                drawRect(Color.Black.copy(alpha = 0.22f * (1f - offset)))
            }
        }
}
