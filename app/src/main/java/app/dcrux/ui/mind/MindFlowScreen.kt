package app.dcrux.ui.mind

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.dcrux.audio.MindFlow
import app.dcrux.audio.SoundId
import app.dcrux.ui.theme.BrandColors
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

private val presets = listOf(
    "Deep sleep" to mapOf(SoundId.RAIN to 0.55f, SoundId.BROWN to 0.4f, SoundId.DELTA to 0.3f),
    "Calm focus" to mapOf(SoundId.STREAM to 0.5f, SoundId.PINK to 0.2f, SoundId.ALPHA to 0.3f),
    "Meditate" to mapOf(SoundId.DRONE to 0.5f, SoundId.BOWL to 0.6f, SoundId.THETA to 0.25f),
    "Seaside night" to mapOf(SoundId.OCEAN to 0.65f, SoundId.NIGHT to 0.35f, SoundId.WIND to 0.2f),
    "Fireside" to mapOf(SoundId.FIRE to 0.65f, SoundId.WIND to 0.25f),
)

@Composable
fun MindFlowTab() {
    val context = LocalContext.current
    val levels by MindFlow.levels.collectAsState()
    val timerEnd by MindFlow.timerEnd.collectAsState()
    val paused by MindFlow.paused.collectAsState()
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(timerEnd) { while (timerEnd > 0) { now = SystemClock.elapsedRealtime(); delay(1000) } }

    // Android 13+: the "now playing" notification needs permission; playback works either way.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun ensureNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LazyVerticalGrid(
        GridCells.Fixed(2), Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(2) }) {
            Column {
                Text("Mind Flow", style = MaterialTheme.typography.headlineMedium)
            }
        }
        item(span = { GridItemSpan(2) }) { BreathCard(playing = levels.isNotEmpty() && !paused) }
        item(span = { GridItemSpan(2) }) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.forEach { (name, mix) ->
                    AssistChip(onClick = { ensureNotifications(); MindFlow.setMix(context, mix) }, label = { Text(name) }, shape = CircleShape)
                }
            }
        }
        items(SoundId.entries.toList(), key = { it.name }) { id ->
            SoundCard(id, levels[id] ?: 0f) { level -> if (level > 0f) ensureNotifications(); MindFlow.set(context, id, level) }
        }
        item(span = { GridItemSpan(2) }) {
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
                    FilterChip(selected = timerEnd == 0L, onClick = { MindFlow.setTimer(0) }, label = { Text("Off") }, shape = CircleShape)
                    listOf(15, 30, 45, 60, 90).forEach { m ->
                        FilterChip(selected = false, onClick = { MindFlow.setTimer(m) }, label = { Text("$m min") }, shape = CircleShape)
                    }
                }
                AnimatedVisibility(levels.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { if (paused) MindFlow.resume(context) else MindFlow.pause(context) }, modifier = Modifier.weight(1f).height(52.dp)) {
                            Icon(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null); Spacer(Modifier.width(8.dp)); Text(if (paused) "Play" else "Pause")
                        }
                        FilledTonalButton(onClick = { MindFlow.stopAll(context) }, modifier = Modifier.weight(1f).height(52.dp)) {
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

/** A slow breathing guide: in for 4, hold for 4, out for 6. */
@Composable
private fun BreathCard(playing: Boolean) {
    val t = rememberInfiniteTransition(label = "breath")
    val phase by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(keyframes {
            durationMillis = 14_000
            0f at 0 using FastOutSlowInEasing
            1f at 4_000
            1f at 8_000 using FastOutSlowInEasing
            0f at 14_000
        }),
        label = "phase",
    )
    val clock by t.animateFloat(0f, 14f, infiniteRepeatable(keyframes { durationMillis = 14_000; 0f at 0; 14f at 14_000 }), label = "clock")
    val word = when {
        clock < 4f -> "Breathe in"
        clock < 8f -> "Hold"
        else -> "Breathe out"
    }
    Box(
        Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(32.dp)).background(Brush.linearGradient(listOf(Color(0xFF1B1245), Color(0xFF3A1C71), Color(0xFF6A2C8F)))),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(150.dp)) {
            val r = size.minDimension / 2
            drawCircle(Brush.radialGradient(listOf(BrandColors[2].copy(alpha = 0.35f), Color.Transparent)), radius = r * (0.7f + 0.3f * phase))
            drawCircle(Brush.radialGradient(listOf(Color(0xFFFFE27A), BrandColors[2], BrandColors[0])), radius = r * (0.32f + 0.36f * phase))
        }
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(word, color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text(if (playing) "Sounds are playing" else "Pick a sound or a mix below", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
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
