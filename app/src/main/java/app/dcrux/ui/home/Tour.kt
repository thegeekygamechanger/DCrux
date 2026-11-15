package app.dcrux.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.dcrux.graph
import kotlinx.coroutines.launch

private data class Step(val title: String, val body: String, val icon: ImageVector?, val tab: Int)

private val steps = listOf(
    Step("Pull down to sync", "On the Crux screen, drag the page down and let go. DCrux fetches the latest videos and headlines. It also syncs by itself every morning.", null, 0),
    Step("Your day, your city", "Tap the date card to set your city. You get the temperature there and a heads-up when heat, heavy rain, storms or bad air are on the way.", Icons.Rounded.LocationOn, 0),
    Step("Search from anywhere", "The magnifier slides search up over any screen. Turn on Crux AI overview for a short summary written only from your results.", Icons.Rounded.Search, 0),
    Step("Prelims and Mains", "Tests has two sides. Prelims gives you MCQs. In Mains you write on paper, photograph the pages, and get them read and marked the way a mentor would, with what to fix next time.", Icons.Rounded.EditNote, 2),
)

/** A short walk through the app, shown once. It sits over the real screens so each tip points at the real thing. */
@Composable
fun Tour(onTab: (Int) -> Unit) {
    val g = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    var index by rememberSaveable { mutableIntStateOf(0) }
    fun finish() { onTab(0); scope.launch { g.prefs.setTourDone(true) } }
    BackHandler { finish() }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.62f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 16.dp),
            shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                AnimatedContent(
                    index,
                    transitionSpec = { (slideInHorizontally { it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 3 } + fadeOut()) },
                    label = "tour",
                ) { i ->
                    val step = steps[i]
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (step.icon == null) PullDownArt() else IconArt(step.icon)
                        Spacer(Modifier.height(16.dp))
                        Text(step.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                        Text(step.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                }
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    steps.indices.forEach { i ->
                        Box(
                            Modifier.height(6.dp).width(if (i == index) 22.dp else 6.dp).clip(CircleShape)
                                .background(if (i == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { finish() }) { Text("Skip") }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = {
                        if (index == steps.lastIndex) finish() else { index++; onTab(steps[index].tab) }
                    }) { Text(if (index == steps.lastIndex) "Start" else "Next") }
                }
            }
        }
    }
}

/** A little page being dragged down by a fingertip, on a loop. */
@Composable
private fun PullDownArt() {
    val loop = rememberInfiniteTransition(label = "pull")
    val drag by loop.animateFloat(
        0f, 0f,
        infiniteRepeatable(keyframes {
            durationMillis = 2200
            0f at 0
            0f at 250
            1f at 1000 using FastOutSlowInEasing
            1f at 1450
            0f at 1800 using FastOutSlowInEasing
        }),
        label = "drag",
    )
    Box(Modifier.size(width = 190.dp, height = 132.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Icon(
            Icons.Rounded.ArrowDownward, null,
            Modifier.align(Alignment.TopCenter).padding(top = 8.dp).size(22.dp).alpha(drag).scale(0.6f + 0.4f * drag),
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(Modifier.fillMaxWidth().offset(y = (14 + 30 * drag).dp).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(3) { i ->
                Box(
                    Modifier.fillMaxWidth(if (i == 2) 0.6f else 1f).height(if (i == 0) 26.dp else 16.dp).clip(RoundedCornerShape(10.dp))
                        .background(if (i == 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest)
                )
            }
        }
        // The fingertip.
        Box(
            Modifier.align(Alignment.TopCenter).offset(x = 34.dp, y = (38 + 34 * drag).dp).size(30.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
        )
    }
}

@Composable
private fun IconArt(icon: ImageVector) {
    val loop = rememberInfiniteTransition(label = "pulse")
    val s by loop.animateFloat(0.94f, 1.06f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "s")
    Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(108.dp).scale(s).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(50.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}
