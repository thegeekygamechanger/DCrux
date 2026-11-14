package app.dcrux.ui.news

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.TextDecrease
import androidx.compose.material.icons.rounded.TextIncrease
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import app.dcrux.data.Settings
import app.dcrux.graph
import app.dcrux.ui.LocalNav
import app.dcrux.ui.Screen
import app.dcrux.ui.ScreenBar
import app.dcrux.ui.components.CruxCard
import app.dcrux.ui.components.Markdown
import app.dcrux.ui.components.RelevancePanel
import app.dcrux.ui.theme.Heros
import app.dcrux.ui.theme.LocalExtra
import kotlinx.coroutines.launch

/** Read mode: warm paper, generous type, nothing else on the page. */
@Composable
fun ReaderScreen(screen: Screen.Reader, settings: Settings) {
    val g = LocalContext.current.graph
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val dark = LocalExtra.current.dark
    val paper by animateColorAsState(if (dark) Color(0xFF17161B) else Color(0xFFF8F1E3), label = "paper")
    val ink = if (dark) Color(0xFFE9E3D8) else Color(0xFF2B2622)
    val scale = settings.readerScale

    CompositionLocalProvider(LocalContentColor provides ink) {
    Column(Modifier.fillMaxSize().background(paper).statusBarsPadding()) {
        ScreenBar("Read mode", onBack = { nav.pop() }) {
            IconButton(onClick = { scope.launch { g.prefs.setReaderScale((scale - 0.1f).coerceAtLeast(0.8f)) } }) { Icon(Icons.Rounded.TextDecrease, "Smaller text") }
            IconButton(onClick = { scope.launch { g.prefs.setReaderScale((scale + 0.1f).coerceAtMost(1.6f)) } }) { Icon(Icons.Rounded.TextIncrease, "Larger text") }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 22.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (screen.meta.isNotBlank()) Text(screen.meta, color = ink.copy(alpha = 0.65f), style = MaterialTheme.typography.labelMedium)
            Text(
                screen.title, color = ink,
                style = MaterialTheme.typography.headlineMedium.let { it.copy(fontFamily = Heros, fontSize = it.fontSize * scale, lineHeight = it.lineHeight * scale) },
            )
            if (screen.body.isNotBlank()) Markdown(screen.body, scale = scale * 1.08f, color = ink)
            if (!screen.note.isNullOrBlank()) {
                Row {
                    Icon(Icons.Rounded.AutoAwesome, null, Modifier.padding(top = 3.dp).size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(screen.note, color = ink, style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic))
                }
            }
            if (screen.relevance != null) {
                CruxCard(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.padding(18.dp)) {
                        Text("Exam relevance", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(12.dp))
                        RelevancePanel(screen.relevance)
                    }
                }
            }
            screen.link?.let { link ->
                val video = link.contains("youtube.com/watch")
                if (!video) {
                    Text(
                        "This is the publisher's headline and short description. The full article is on their site.",
                        color = ink.copy(alpha = 0.65f), style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = { nav.push(Screen.Browser(link, "Article")) }) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Read the full article")
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    }
}
