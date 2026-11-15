package app.dcrux.ui.components

import app.dcrux.LocalGraph
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dcrux.net.OpenImage
import app.dcrux.ui.theme.Heros
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay

/** One card of a carousel: its line, a caption, what photo to look for, and which painted scene backs it. */
data class Slide(val text: String, val caption: String, val query: String, val scene: Int)

/**
 * An auto-advancing carousel of lines over pictures. Each slide starts with a picture painted
 * in code (so it works offline) and is replaced by an openly licensed photo from Wikimedia
 * Commons, shown with its credit, once that loads. [daySeed] changes daily, and with it the pictures.
 */
@Composable
fun PhotoCarousel(label: String, icon: ImageVector, slides: List<Slide>, daySeed: Int, modifier: Modifier = Modifier) {
    if (slides.isEmpty()) return
    val g = LocalGraph.current
    val pager = rememberPagerState { slides.size }
    LaunchedEffect(pager) {
        while (true) {
            delay(6500)
            if (!pager.isScrollInProgress) pager.animateScrollToPage((pager.currentPage + 1) % slides.size, animationSpec = tween(700))
        }
    }
    Box(modifier.fillMaxWidth().height(232.dp).clip(RoundedCornerShape(32.dp))) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { i ->
            val slide = slides[i]
            Box(Modifier.fillMaxSize()) {
                QuoteArt(scene = slide.scene, seed = daySeed * 31 + i, modifier = Modifier.fillMaxSize())
                val photo by produceState<OpenImage?>(null, daySeed, slide.query) {
                    value = runCatching { g.commons.find(slide.query, pick = daySeed + i) }.getOrNull()
                }
                photo?.let { ph ->
                    AsyncImage(ph.url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.25f), Color.Black.copy(alpha = 0.72f)))))
                    Text(
                        "Photo: ${ph.credit} · ${ph.license}", color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.TopEnd).padding(top = 18.dp, end = 16.dp).width(170.dp),
                    )
                }
                Column(Modifier.align(Alignment.BottomStart).padding(start = 20.dp, end = 20.dp, bottom = 34.dp)) {
                    Text(
                        slide.text, color = Color.White, maxLines = 4, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleLarge.copy(fontFamily = Heros, fontWeight = FontWeight.Bold),
                    )
                    if (slide.caption.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(slide.caption, color = Color.White.copy(alpha = 0.88f), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Row(Modifier.align(Alignment.TopStart).padding(start = 20.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Color(0xFFFFE27A), modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
        Row(Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(slides.size) { i ->
                val w by animateFloatAsState(if (i == pager.currentPage) 22f else 7f, spring(dampingRatio = 0.6f), label = "dot")
                Box(Modifier.height(7.dp).width(w.dp).clip(CircleShape).background(Color.White.copy(alpha = if (i == pager.currentPage) 1f else 0.5f)))
            }
        }
    }
}
