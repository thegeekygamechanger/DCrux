package app.dcrux.ui.news

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.dcrux.LocalGraph
import app.dcrux.data.Settings
import app.dcrux.ui.LocalNav
import app.dcrux.ui.Screen
import app.dcrux.ui.ScreenBar
import app.dcrux.ui.components.CruxCard
import app.dcrux.ui.components.CruxLoader
import app.dcrux.ui.components.appBackground
import kotlinx.coroutines.launch

/**
 * On a computer a publisher's page opens in the user's own browser (which has their ad blocker and
 * their subscriptions). This screen stays behind it and offers the page in read mode.
 */
@Composable
fun BrowserScreen(url: String, title: String, settings: Settings) {
    val g = LocalGraph.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(url) { g.platform.openUrl(url) }

    Column(Modifier.fillMaxSize().appBackground().statusBarsPadding()) {
        ScreenBar(title.ifBlank { "Article" }, onBack = { nav.pop() })
        CruxCard(Modifier.fillMaxWidth().padding(16.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Opened in your browser", style = MaterialTheme.typography.titleLarge)
                Text(
                    runCatching { java.net.URI(url).host }.getOrNull() ?: url,
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (loading) CruxLoader("Preparing read mode")
                message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                Button(enabled = !loading, onClick = {
                    loading = true; message = null
                    scope.launch {
                        val text = runCatching { g.news.articleText(url) }.getOrDefault("")
                        loading = false
                        if (text.length < 200) message = "Read mode is not available for this page."
                        else nav.push(Screen.Reader(title, text, runCatching { java.net.URI(url).host }.getOrNull().orEmpty(), url))
                    }
                }) { Icon(Icons.AutoMirrored.Rounded.MenuBook, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Read it here") }
                FilledTonalButton(onClick = { g.platform.openUrl(url) }) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Open in the browser again")
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
