package app.dcrux.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import kotlinx.serialization.Serializable
import coil3.compose.AsyncImage
import app.dcrux.net.OpenImage
import app.dcrux.graph
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.produceState
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ChromeReaderMode
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dcrux.data.AppJson
import app.dcrux.data.ExamRelevance
import app.dcrux.ui.theme.Heros
import app.dcrux.ui.theme.LocalExtra

// ------------------------------------------------------------------ logo

private val LogoColors = listOf(Color(0xFF1E1670), Color(0xFF3A2CC4), Color(0xFF5847F0))

/** The DCrux mark, drawn live: an indigo disc with a slow sheen, the D, and one amber point that breathes. */
@Composable
fun AnimatedLogo(size: Dp, modifier: Modifier = Modifier, animate: Boolean = true) {
    val t = rememberInfiniteTransition(label = "logo")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(12000, easing = LinearEasing)), label = "spin")
    val pulse by t.animateFloat(0.85f, 1.2f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "pulse")
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 108f
        rotate(if (animate) spin else 0f) {
            drawCircle(Brush.sweepGradient(listOf(LogoColors[2], LogoColors[0], LogoColors[2])), radius = 46f * s)
        }
        drawCircle(Brush.linearGradient(LogoColors, Offset(20f * s, 20f * s), Offset(88f * s, 88f * s)), radius = 42.5f * s)
        val d = Path().apply {
            fillType = PathFillType.EvenOdd
            moveTo(36f * s, 32f * s); lineTo(53f * s, 32f * s)
            cubicTo(65.2f * s, 32f * s, 75f * s, 41.8f * s, 75f * s, 54f * s)
            cubicTo(75f * s, 66.2f * s, 65.2f * s, 76f * s, 53f * s, 76f * s)
            lineTo(36f * s, 76f * s); lineTo(33.5f * s, 73.5f * s); lineTo(33.5f * s, 34.5f * s); close()
            moveTo(46.5f * s, 44.5f * s); lineTo(46.5f * s, 63.5f * s); lineTo(62f * s, 54f * s); close()
        }
        drawPath(d, Color.White)
        drawCircle(Color(0xFFFFB020), radius = 4.6f * s * (if (animate) pulse else 1f), center = Offset(79f * s, 30f * s))
    }
}

// ------------------------------------------------------------------ pagination

/** Previous / numbered pages / next. Hidden when there is only one page. */
@Composable
fun PaginationBar(page: Int, pageCount: Int, onPage: (Int) -> Unit, modifier: Modifier = Modifier) {
    if (pageCount <= 1) return
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(onClick = { onPage(page - 1) }, enabled = page > 0, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Rounded.ChevronLeft, "Previous page")
        }
        Spacer(Modifier.width(6.dp))
        val window = 5
        val start = (page - window / 2).coerceIn(0, (pageCount - window).coerceAtLeast(0))
        val end = minOf(pageCount, start + window)
        if (start > 0) Text("…", Modifier.padding(horizontal = 2.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (i in start until end) {
            val selected = i == page
            val bg by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, label = "pg")
            val fg by animateColorAsState(if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, label = "pgfg")
            val w by animateFloatAsState(if (selected) 44f else 36f, spring(dampingRatio = 0.55f, stiffness = 500f), label = "pgw")
            Box(
                Modifier.padding(horizontal = 2.dp).height(36.dp).width(w.dp).clip(CircleShape).background(bg).clickable { onPage(i) },
                contentAlignment = Alignment.Center,
            ) { Text("${i + 1}", color = fg, style = MaterialTheme.typography.labelLarge) }
        }
        if (end < pageCount) Text("…", Modifier.padding(horizontal = 2.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        FilledTonalIconButton(onClick = { onPage(page + 1) }, enabled = page < pageCount - 1, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Rounded.ChevronRight, "Next page")
        }
    }
}

// ------------------------------------------------------------------ relevance

fun parseRelevance(json: String?): List<ExamRelevance> =
    json?.let { runCatching { AppJson.decodeFromString<List<ExamRelevance>>(it) }.getOrNull() }.orEmpty()

fun shortExam(name: String): String = name.substringBefore(" (").trim()

@Composable
private fun levelColors(score: Int): Pair<Color, Color> {
    val e = LocalExtra.current
    return when {
        score >= 70 -> e.high to e.onHigh
        score >= 45 -> e.mid to e.onMid
        else -> e.low to e.onLow
    }
}

fun levelWord(score: Int) = when {
    score >= 70 -> "High"
    score >= 45 -> "Medium"
    else -> "Low"
}

/** Compact pill: the best-matching exam and its score. */
@Composable
fun RelevancePill(json: String?, modifier: Modifier = Modifier) {
    val top = remember(json) { parseRelevance(json).maxByOrNull { it.score } } ?: return
    val (bg, fg) = levelColors(top.score)
    Row(
        modifier.clip(CircleShape).background(bg).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${shortExam(top.exam)} ${top.score}/100", color = fg, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

/** Full per-exam breakdown with animated bars. */
@Composable
fun RelevancePanel(json: String?, modifier: Modifier = Modifier) {
    val list = remember(json) { parseRelevance(json).sortedByDescending { it.score } }
    if (list.isEmpty()) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        list.forEach { r ->
            val (bg, fg) = levelColors(r.score)
            val p by animateFloatAsState(r.score / 100f, tween(900), label = "rel")
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.exam, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${levelWord(r.score)} · ${r.score}/100", color = fg, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.clip(CircleShape).background(bg).padding(horizontal = 10.dp, vertical = 3.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { p }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                    color = fg, trackColor = bg, drawStopIndicator = {},
                )
                if (r.note.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(r.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ markdown

private fun inline(text: String) = buildAnnotatedString {
    val parts = text.replace("`", "").split("**")
    parts.forEachIndexed { i, part ->
        val clean = part.replace(Regex("(?<![*\\w])\\*(?!\\s)|(?<!\\s)\\*(?![*\\w])"), "")
        if (i % 2 == 1 && parts.size > 2) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(clean) } else append(clean)
    }
}

/** Renders the small Markdown subset the model is asked to use: headings, bullets, numbers, bold. */
@Composable
fun Markdown(text: String, modifier: Modifier = Modifier, scale: Float = 1f, color: Color = MaterialTheme.colorScheme.onSurface) {
    val body = MaterialTheme.typography.bodyLarge.let { it.copy(fontSize = it.fontSize * scale, lineHeight = it.lineHeight * scale) }
    Column(modifier) {
        text.lines().forEach { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() -> Spacer(Modifier.height(6.dp))
                line.startsWith(":::image ") -> OpenPicture(line.removePrefix(":::image ").trim(), Modifier.padding(vertical = 8.dp))
                line.startsWith(":::diagram ") -> Diagram(line.removePrefix(":::diagram ").trim(), color, Modifier.padding(vertical = 8.dp))
                line.startsWith("#") -> {
                    val level = line.takeWhile { it == '#' }.length
                    val size = (if (level <= 1) 22 else if (level == 2) 19 else 17) * scale
                    Text(
                        inline(line.trimStart('#').trim()), color = color,
                        style = MaterialTheme.typography.titleLarge.copy(fontFamily = Heros, fontSize = size.sp, lineHeight = (size * 1.3f).sp),
                        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
                    )
                }
                line.startsWith("- ") || line.startsWith("* ") || line.startsWith("• ") -> {
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Box(Modifier.padding(top = (9 * scale).dp, end = 12.dp).size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                        Text(inline(line.drop(2).trim()), style = body, color = color)
                    }
                }
                Regex("^\\d{1,2}[.)] ").containsMatchIn(line) -> {
                    val num = line.substringBefore(' ')
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Text(num, Modifier.width(28.dp), style = body.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.primary)
                        Text(inline(line.substringAfter(' ').trim()), style = body, color = color)
                    }
                }
                else -> Text(inline(line), style = body, color = color, modifier = Modifier.padding(vertical = 3.dp))
            }
        }
    }
}

// ------------------------------------------------------------------ loading

/** The "Claude is working" state: morphing indicator, stage text, shimmering lines. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CruxLoader(stage: String, detail: String = "", modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(-1f, 2f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "x")
    val base = MaterialTheme.colorScheme.surfaceContainerHighest
    val shine = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        ContainedLoadingIndicator(Modifier.size(64.dp))
        Spacer(Modifier.height(14.dp))
        AnimatedContent(stage, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "stage") {
            Text(it, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        }
        if (detail.isNotBlank()) {
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(18.dp))
        listOf(0.95f, 0.8f, 0.9f, 0.6f).forEach { w ->
            Box(
                Modifier.padding(vertical = 5.dp).fillMaxWidth(w).height(12.dp).clip(CircleShape).background(
                    Brush.horizontalGradient(
                        0f to base, ((x - 0.3f).coerceIn(0f, 1f)) to base, x.coerceIn(0f, 1f) to shine,
                        ((x + 0.3f).coerceIn(0f, 1f)) to base, 1f to base,
                    )
                )
            )
        }
    }
}

// ------------------------------------------------------------------ small pieces

/** A switch whose thumb icon flips between a tick and a cross. */
@Composable
fun FancySwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Switch(
        checked = checked, onCheckedChange = onCheckedChange, modifier = modifier, enabled = enabled,
        thumbContent = {
            AnimatedContent(checked, transitionSpec = { (scaleIn() + fadeIn()) togetherWith (scaleOut() + fadeOut()) }, label = "thumb") { on ->
                Icon(if (on) Icons.Rounded.Check else Icons.Rounded.Close, null, Modifier.size(SwitchDefaults.IconSize))
            }
        },
    )
}

@Composable
fun ReadModeButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick, modifier) { Icon(Icons.AutoMirrored.Rounded.ChromeReaderMode, "Read mode") }
}

@Composable
fun Stars(count: Int, modifier: Modifier = Modifier, size: Dp = 20.dp, max: Int = 5) {
    Row(modifier) {
        repeat(max) { i ->
            val on = i < count
            val s by animateFloatAsState(if (on) 1f else 0.85f, spring(dampingRatio = 0.4f, stiffness = 300f), label = "star")
            Icon(
                if (on) Icons.Rounded.Star else Icons.Rounded.StarBorder, null,
                Modifier.size(size).graphicsLayer { scaleX = s; scaleY = s },
                tint = if (on) LocalExtra.current.star else MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedLogo(72.dp)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

/** A rounded container used for every card in the app. */
@Composable
fun CruxCard(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    radius: Dp = 28.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    if (onClick != null) Surface(onClick = onClick, modifier = modifier, shape = shape, color = color, content = content)
    else Surface(modifier = modifier, shape = shape, color = color, content = content)
}

@Composable
fun Tag(text: String, modifier: Modifier = Modifier, bg: Color = MaterialTheme.colorScheme.secondaryContainer, fg: Color = MaterialTheme.colorScheme.onSecondaryContainer) {
    Text(
        text, color = fg, style = MaterialTheme.typography.labelMedium, maxLines = 1,
        modifier = modifier.clip(CircleShape).background(bg).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

// ------------------------------------------------------------------ pictures and diagrams

/** An openly licensed photo for the subject, with its credit. Shows nothing when none is found. */
@Composable
fun OpenPicture(query: String, modifier: Modifier = Modifier) {
    val g = LocalContext.current.graph
    val image by produceState<OpenImage?>(null, query) { value = runCatching { g.commons.find(query) }.getOrNull() }
    val img = image ?: return
    Column(modifier) {
        AsyncImage(
            img.url, query, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        )
        Text(
            "Photo: ${img.credit} · ${img.license} · Wikimedia Commons",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, start = 4.dp), maxLines = 1,
        )
    }
}

@Serializable
private data class DiagramItem(val label: String = "", val detail: String = "")

@Serializable
private data class DiagramSpec(val title: String = "", val kind: String = "flow", val items: List<DiagramItem> = emptyList())

/**
 * Draws a small diagram from a one-line description written by the AI:
 * "flow" (steps joined by arrows), "timeline" (dated points on a line) or "compare" (side-by-side cards).
 * A line that cannot be read is simply skipped.
 */
@Composable
fun Diagram(json: String, ink: Color, modifier: Modifier = Modifier) {
    val spec = remember(json) { runCatching { AppJson.decodeFromString<DiagramSpec>(json) }.getOrNull() }
    val items = spec?.items?.filter { it.label.isNotBlank() }.orEmpty()
    if (spec == null || items.size < 2) return
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(accent.copy(alpha = 0.08f)).padding(16.dp),
    ) {
        if (spec.title.isNotBlank()) {
            Text(spec.title, color = ink, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 12.dp))
        }
        when (spec.kind) {
            "compare" -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items.take(3).forEach { item ->
                    Column(
                        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(12.dp),
                    ) {
                        Text(item.label, color = accent, style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(6.dp))
                        Text(item.detail, color = ink, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            "timeline" -> items.forEachIndexed { i, item ->
                Row(Modifier.height(IntrinsicSize.Min)) {
                    Column(Modifier.width(22.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(accent))
                        if (i < items.lastIndex) Box(Modifier.width(2.dp).weight(1f).background(accent.copy(alpha = 0.4f)))
                    }
                    Column(Modifier.padding(start = 8.dp, bottom = 14.dp)) {
                        Text(item.label, color = accent, style = MaterialTheme.typography.labelLarge)
                        if (item.detail.isNotBlank()) Text(item.detail, color = ink, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            else -> items.forEachIndexed { i, item ->
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Text(item.label, color = ink, style = MaterialTheme.typography.titleSmall)
                        if (item.detail.isNotBlank()) Text(item.detail, color = ink.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall)
                    }
                    if (i < items.lastIndex) Icon(Icons.Rounded.ArrowDownward, null, Modifier.padding(vertical = 2.dp).size(20.dp), tint = accent)
                }
            }
        }
    }
}
