package app.dcrux.ui.mind

import app.dcrux.ui.components.LocalWide
import app.dcrux.LocalGraph
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Grain
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.Opacity
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tsunami
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.dcrux.audio.SoundId
import app.dcrux.data.Dates
import app.dcrux.ui.components.bottomBarInset
import app.dcrux.ui.components.PhotoCarousel
import app.dcrux.ui.components.Slide
import app.dcrux.ui.components.TabTitle
import kotlinx.coroutines.delay

private fun iconOf(id: SoundId): ImageVector = when (id) {
    SoundId.RAIN -> Icons.Rounded.WaterDrop
    SoundId.OCEAN -> Icons.Rounded.Tsunami
    SoundId.STREAM -> Icons.Rounded.Waves
    SoundId.WIND -> Icons.Rounded.Air
    SoundId.FIRE -> Icons.Rounded.LocalFireDepartment
    SoundId.NIGHT -> Icons.Rounded.NightsStay
    SoundId.BROWN -> Icons.Rounded.Grain
    SoundId.PINK -> Icons.Rounded.Opacity
    SoundId.WHITE -> Icons.Rounded.GraphicEq
    SoundId.HEART -> Icons.Rounded.Favorite
    SoundId.DRONE -> Icons.Rounded.Spa
    SoundId.BOWL -> Icons.Rounded.SelfImprovement
    SoundId.DELTA -> Icons.Rounded.Bedtime
    SoundId.THETA -> Icons.Rounded.Psychology
    SoundId.ALPHA -> Icons.Rounded.Headphones
}

/** Short, original lines for winding down. Five are shown each day. */
private val calmLines = listOf(
    "Breathe in for four, out for six." to "A slower out-breath settles the body",
    "Let your shoulders drop." to "Unclench the jaw, soften the hands",
    "Nothing needs solving in this minute." to "Let the thought pass like weather",
    "Listen for the farthest sound you can hear." to "Then the nearest one",
    "Rest is part of the work." to "Tomorrow's focus starts tonight",
    "Feel your feet on the floor." to "You are here, and that is enough",
    "One page, one breath, one step." to "Small is still forward",
    "Put the day down. It will keep." to "Pick it up again in the morning",
    "Unhurried is a pace too." to "No rush in the next ten minutes",
    "Notice five things you can see." to "A quick way back to the present",
    "Loosen your grip on the hour." to "Let it be as long as it is",
    "Quiet is not empty." to "Stay with it a little longer",
)

private val calmQueries = listOf(
    "calm lake reflection mountains", "zen garden stones", "lotus flower pond", "bamboo forest path",
    "moonlit sea night", "mountain lake dawn mist", "forest waterfall", "meadow sunrise fog",
)

private val presets = listOf(
    "Deep sleep" to mapOf(SoundId.RAIN to 0.55f, SoundId.BROWN to 0.4f, SoundId.DELTA to 0.3f),
    "Calm focus" to mapOf(SoundId.STREAM to 0.5f, SoundId.PINK to 0.2f, SoundId.ALPHA to 0.3f),
    "Meditate" to mapOf(SoundId.DRONE to 0.5f, SoundId.BOWL to 0.6f, SoundId.THETA to 0.25f),
    "Seaside night" to mapOf(SoundId.OCEAN to 0.65f, SoundId.NIGHT to 0.35f, SoundId.WIND to 0.2f),
    "Fireside" to mapOf(SoundId.FIRE to 0.65f, SoundId.WIND to 0.25f),
)

@Composable
fun MindFlowTab() {
    val g = LocalGraph.current
    val sounds = g.platform.sounds
    val levels by sounds.levels.collectAsState()
    val timerEnd by sounds.timerEnd.collectAsState()
    val paused by sounds.paused.collectAsState()
    var now by remember { mutableLongStateOf(g.platform.uptime()) }
    LaunchedEffect(timerEnd) { while (timerEnd > 0) { now = g.platform.uptime(); delay(1000) } }
    val ensureNotifications = g.platform.rememberNotificationAsk()

    LazyVerticalGrid(
        if (LocalWide.current) GridCells.Adaptive(250.dp) else GridCells.Fixed(2), Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp + bottomBarInset()),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                TabTitle("Mind Flow")
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            val day = remember(Dates.dayKey()) { Dates.dayOfEpoch().toInt() }
            val slides = remember(day) {
                List(5) { i ->
                    val (line, caption) = calmLines[(day * 5 + i).mod(calmLines.size)]
                    Slide(line, caption, calmQueries[(day + i).mod(calmQueries.size)], scene = i + 2)
                }
            }
            PhotoCarousel("Daily calm", Icons.Rounded.Spa, slides, day)
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.forEach { (name, mix) ->
                    AssistChip(onClick = { ensureNotifications(); sounds.setMix(mix) }, label = { Text(name) }, shape = CircleShape)
                }
            }
        }
        items(SoundId.entries.toList(), key = { it.name }) { id ->
            SoundCard(id, levels[id] ?: 0f) { level -> if (level > 0f) ensureNotifications(); sounds.set(id, level) }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Timer, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    val left = ((timerEnd - now) / 1000).coerceAtLeast(0)
                    Text(
                        if (timerEnd > 0) "Sleep timer · %d:%02d left".format(left / 60, left % 60) else "Sleep timer",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = timerEnd == 0L, onClick = { sounds.setTimer(0) }, label = { Text("Off") }, shape = CircleShape)
                    listOf(15, 30, 45, 60, 90).forEach { m ->
                        FilterChip(selected = false, onClick = { sounds.setTimer(m) }, label = { Text("$m min") }, shape = CircleShape)
                    }
                }
                AnimatedVisibility(levels.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { if (paused) sounds.resume() else sounds.pause() }, modifier = Modifier.weight(1f).height(52.dp)) {
                            Icon(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null); Spacer(Modifier.width(8.dp)); Text(if (paused) "Play" else "Pause")
                        }
                        FilledTonalButton(onClick = { sounds.stopAll() }, modifier = Modifier.weight(1f).height(52.dp)) {
                            Icon(Icons.Rounded.Stop, null); Spacer(Modifier.width(8.dp)); Text("Stop")
                        }
                    }
                }
                Text(
                    "Every sound is generated on your phone as it plays. There are no recordings, so nothing here is copyrighted. " +
                        "Binaural tones need headphones.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SoundCard(id: SoundId, level: Float, onLevel: (Float) -> Unit) {
    val on = level > 0f
    val bg by animateColorAsState(if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow, label = "bg")
    val fg by animateColorAsState(if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface, label = "fg")
    val pop by animateFloatAsState(if (on) 1.15f else 1f, spring(dampingRatio = 0.4f, stiffness = 300f), label = "pop")
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(bg).clickable { onLevel(if (on) 0f else 0.6f) }.padding(16.dp).animateContentSize(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape)
                    .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) { Icon(iconOf(id), null, Modifier.graphicsLayer { scaleX = pop; scaleY = pop }, tint = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant) }
            if (id.headphones) {
                Spacer(Modifier.weight(1f))
                Icon(Icons.Rounded.Headphones, "Needs headphones", Modifier.size(16.dp), tint = fg.copy(alpha = 0.6f))
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(id.title, color = fg, style = MaterialTheme.typography.titleMedium)
        Text(id.hint, color = fg.copy(alpha = 0.72f), style = MaterialTheme.typography.bodySmall)
        if (on) Slider(value = level, onValueChange = { onLevel(it.coerceAtLeast(0.05f)) }, valueRange = 0.05f..1f, modifier = Modifier.height(36.dp))
    }
}
