package app.dcrux.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.dcrux.ui.components.appBackground
import app.dcrux.BuildConfig
import app.dcrux.ui.LocalNav
import app.dcrux.ui.ScreenBar
import app.dcrux.ui.components.AnimatedLogo
import app.dcrux.ui.components.CruxCard
import app.dcrux.ui.components.Tag

private data class Notice(val name: String, val by: String, val license: String, val note: String)

private val notices = listOf(
    Notice("Kotlin, kotlinx.coroutines, kotlinx.serialization", "JetBrains", "Apache License 2.0", "The language and its core libraries."),
    Notice("Jetpack Compose and Material 3", "Google / The Android Open Source Project", "Apache License 2.0", "The whole interface, including the Material 3 Expressive components and motion."),
    Notice("AndroidX: Core, Activity, Fragment, Lifecycle, SplashScreen, Room, WorkManager, DataStore, Biometric", "Google / The Android Open Source Project", "Apache License 2.0", "Storage, background sync, app lock and app plumbing."),
    Notice("Material Icons", "Google", "Apache License 2.0", "Every icon in the app."),
    Notice("OkHttp and Okio", "Square, Inc.", "Apache License 2.0", "All network requests."),
    Notice("Coil", "Coil Contributors", "Apache License 2.0", "Thumbnail loading."),
    Notice("jsoup", "Jonathan Hedley", "MIT License", "Reading RSS, Atom and HTML."),
    Notice("Inter", "The Inter Project Authors (Rasmus Andersson)", "SIL Open Font License 1.1", "Body and label typeface."),
    Notice("TeX Gyre Heros", "GUST e-foundry, based on URW Nimbus Sans", "GUST Font License", "Headline typeface. It is the freely licensed Helvetica-metric design; Helvetica itself is proprietary and is not bundled."),
    Notice("ISRG Root X1", "Internet Security Research Group (Let's Encrypt)", "Public root certificate", "Bundled so secure connections work on Android 7."),
    Notice("YouTube IFrame Player", "Google LLC", "YouTube API Services Terms of Service", "Videos play in YouTube's own embedded player with its own controls. DCrux adds no ads; YouTube may show its own."),
    Notice("Claude API", "Anthropic", "Anthropic Commercial Terms", "Summaries, relevance scores, tests and overviews are written by Claude using the key in this app."),
    Notice("News headlines", "Their respective publishers", "All rights with the publishers", "DCrux reads each publisher's public feed and shows the headline, the feed's short description and a link. Articles and e-papers are read on the publisher's own site."),
    Notice("Photographs", "Wikimedia Commons contributors", "Public domain, CC0, CC BY and CC BY-SA only", "Pictures in the daily carousel and in summaries are fetched live from Wikimedia Commons. Only openly licensed photos are used, each shown with its author and licence."),
    Notice("Daily spark quotations", "Public-domain works and traditional sayings", "Public domain", "Each line is shown with its source."),
    Notice("Mind Flow sounds", "Generated on the device", "No third-party audio", "Every sound is synthesised in real time; the app contains no recordings."),
    Notice("DCrux icon and artwork", "Original to DCrux", "Apache License 2.0", "Drawn as vectors and code, designed with AI assistance."),
)

@Composable
fun AboutScreen() {
    val context = LocalContext.current
    val nav = LocalNav.current
    fun open(url: String) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

    Column(Modifier.fillMaxSize().appBackground().statusBarsPadding()) {
        ScreenBar("About", onBack = { nav.pop() })
        LazyColumn(
            Modifier.weight(1f).navigationBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("hero") {
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    AnimatedLogo(128.dp)
                    Spacer(Modifier.height(14.dp))
                    Text("DCrux", style = MaterialTheme.typography.displaySmall)
                    Text("The daily crux of current affairs", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Tag("Version ${BuildConfig.VERSION_NAME}")
                }
            }
            item("dev") {
                CruxCard(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primaryContainer) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Developed by", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("Harsh Kumar", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { open("https://theharshkumar.in") }) {
                            Icon(Icons.Rounded.Language, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("theharshkumar.in")
                        }
                    }
                }
            }
            item("oss") {
                CruxCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Open source", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "DCrux is free and open source under the Apache License 2.0. No account, no server, no analytics: the app talks only to YouTube, the news publishers you choose and the AI service.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FilledTonalButton(onClick = { open("https://github.com/thegeekygamechanger/DCrux") }) {
                            Icon(Icons.Rounded.Code, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Source code on GitHub")
                            Spacer(Modifier.width(6.dp)); Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(16.dp))
                        }
                    }
                }
            }
            item("lh") {
                Text("Licences and credits", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp, start = 6.dp))
                Text(
                    "Everything DCrux is built with, and whose it is.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp),
                )
            }
            items(notices, key = { it.name }) { n ->
                var open by rememberSaveable(n.name) { mutableStateOf(false) }
                CruxCard(Modifier.fillMaxWidth(), radius = 22.dp, onClick = { open = !open }) {
                    Column(Modifier.padding(16.dp).animateContentSize()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(n.name, style = MaterialTheme.typography.titleSmall)
                                Text(n.license, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            }
                            Icon(Icons.Rounded.ExpandMore, null, Modifier.rotate(if (open) 180f else 0f))
                        }
                        if (open) {
                            Spacer(Modifier.height(8.dp))
                            Text(n.by, style = MaterialTheme.typography.bodySmall)
                            Text(n.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item("foot") {
                Text(
                    "AI-written text can be wrong. Check important facts against the original video or article.",
                    Modifier.fillMaxWidth().padding(top = 12.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                )
            }
        }
    }
}
