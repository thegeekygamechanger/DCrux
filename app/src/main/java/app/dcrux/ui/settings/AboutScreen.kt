package app.dcrux.ui.settings

import app.dcrux.LocalGraph
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
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import app.dcrux.ui.components.CruxSheet
import app.dcrux.ui.components.SheetPara
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.dcrux.ui.components.appBackground
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
    Notice("Claude API", "Anthropic", "Anthropic Commercial Terms", "Summaries, relevance scores, tests, answers and Mains evaluations are written by Claude."),
    Notice("Weather and air quality", "Open-Meteo.com", "CC BY 4.0", "The temperature and forecast in the home header, and the figures the alerts are worked out from. City names on phones without a built-in lookup come from BigDataCloud."),
    Notice("Air quality (official monitors)", "Central Pollution Control Board, via data.gov.in", "Government Open Data License - India", "Where the DCrux service has them, the AQI for Indian cities comes from CPCB's monitoring stations. Elsewhere it is an estimate on India's AQI scale from Open-Meteo's modelled particle levels."),
    Notice("News headlines", "Their respective publishers", "All rights with the publishers", "DCrux reads each publisher's public feed and shows the headline, the feed's short description and a link. Articles and e-papers are read on the publisher's own site."),
    Notice("Photographs", "Wikimedia Commons contributors", "Public domain, CC0, CC BY and CC BY-SA only", "Pictures in the daily carousel and in summaries are fetched live from Wikimedia Commons. Only openly licensed photos are used, each shown with its author and licence."),
    Notice("Daily spark quotations", "Public-domain works and traditional sayings", "Public domain", "Each line is shown with its source."),
    Notice("Mind Flow sounds", "Generated on the device", "No third-party audio", "Every sound is synthesised in real time; the app contains no recordings."),
    Notice("DCrux icon and artwork", "Original to DCrux", "Apache License 2.0", "Drawn as vectors and code, designed with AI assistance."),
)

private enum class Info(val title: String, val line: String, val icon: ImageVector) {
    APP("About the app", "Who made it, version, source code", Icons.Rounded.Info),
    AI("How Crux AI works", "What it reads, how it scores, where it can be wrong", Icons.Rounded.AutoAwesome),
    PRIVACY("Privacy", "What stays on the device and what leaves it", Icons.Rounded.Shield),
    TERMS("Terms of use", "The rules for using DCrux", Icons.Rounded.Gavel),
    LICENCES("Licences and credits", "Everything DCrux is built with, and whose it is", Icons.Rounded.Code),
}

@Composable
fun AboutScreen() {
    val g = LocalGraph.current
    val nav = LocalNav.current
    fun open(url: String) = g.platform.openUrl(url)
    var sheet by rememberSaveable { mutableStateOf<Info?>(null) }

    Column(Modifier.fillMaxSize().appBackground().statusBarsPadding()) {
        ScreenBar("About", onBack = { nav.pop() })
        LazyColumn(
            Modifier.weight(1f).navigationBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item("hero") {
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    AnimatedLogo(112.dp)
                    Spacer(Modifier.height(14.dp))
                    Text("DCrux", style = MaterialTheme.typography.displaySmall)
                    Text("The daily crux of current affairs", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Tag("Version ${g.platform.versionName}")
                }
            }
            items(Info.entries, key = { it.name }) { info ->
                CruxCard(Modifier.fillMaxWidth(), radius = 24.dp, onClick = { sheet = info }) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(info.icon, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(info.title, style = MaterialTheme.typography.titleMedium)
                            Text(info.line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Rounded.ExpandLess, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
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

    when (sheet) {
        Info.APP -> CruxSheet("About the app", { sheet = null }, Icons.Rounded.Info) {
            Text(
                "DCrux turns the channels and papers an aspirant follows into a daily crux: notes from each video, headlines scored for your exam, tests for Prelims and Mains, and sounds to study by.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column {
                Text("Developed by", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Harsh Kumar", style = MaterialTheme.typography.headlineSmall)
            }
            FilledTonalButton(onClick = { open("https://theharshkumar.in") }) {
                Icon(Icons.Rounded.Language, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("theharshkumar.in")
            }
            SheetPara("Version", "${g.platform.versionName} (build ${g.platform.versionCode}). Updates come from the project's GitHub releases; see Settings → App update.")
            SheetPara("Open source", "DCrux is free and open source under the Apache License 2.0. No account, no ads, no analytics.")
            FilledTonalButton(onClick = { open("https://github.com/thegeekygamechanger/DCrux") }) {
                Icon(Icons.Rounded.Code, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Source code on GitHub")
                Spacer(Modifier.width(6.dp)); Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(16.dp))
            }
        }
        Info.AI -> CruxSheet("How Crux AI works", { sheet = null }, Icons.Rounded.AutoAwesome) {
            SheetPara("It works from what it is given", "Video notes are written from the video's own captions. Headline scores and notes are written from the publisher's headline and short description. Ask Crux AI answers from the article or video you are on; when that does not cover your question it searches the web and lists the pages it used, and when it still cannot confirm an answer it says so. Search overviews use only your search results. When the material is thin, Crux AI is told to say so instead of filling in.")
            SheetPara("One score, out of 100", "Relevance is the sum of four parts: syllabus fit (40), likelihood of a question (30), depth (20) and currency (10). 70 or more means study this, 45 to 69 is worth a read. Every Prelims and Mains question carries the same kind of score for the exam you chose.")
            SheetPara("Mains evaluation", "Crux AI first copies out what it can read on your pages and marks unreadable words as [illegible]. You can open that reading under every answer. The marked copy sets a tick, half-tick or cross and a short remark against each section of what you wrote, page by page. Marks are given only for what is on the page, against five fixed criteria that add up to the question's marks, and marked strictly, as examiners do: 40 to 50 percent is a competent answer. Time advice compares your recorded writing time with the UPSC pace of 250 marks in three hours.")
            SheetPara("Where it can be wrong", "No AI is free of mistakes. It can misread handwriting, miss a point you made, or be wrong about a fact. If the reading of your page is wrong, the marks are wrong too: retake the photo in good light and evaluate again. Treat the marks as a mentor's estimate, not an official score, and check facts against standard sources.")
        }
        Info.PRIVACY -> CruxSheet("Privacy", { sheet = null }, Icons.Rounded.Shield) {
            SheetPara("No account, no tracking", "DCrux has no sign-in, no ads and no analytics. Nobody receives a record of what you read or how you score.")
            SheetPara("What stays on the phone", "Your channels, video notes, headlines, questions you asked, test results and settings are stored only on this device. Test results and the app-lock PIN are encrypted with a key held in the phone's secure hardware. Android backups are switched off, so none of it is copied to the cloud. It stays until you choose Reset in Settings or uninstall the app.")
            SheetPara("What goes to Crux AI", "To write notes, scores, tests, answers and evaluations, the text involved (captions, headlines, your question) is sent to Anthropic's Claude API; in the published app it travels through the DCrux relay. For a Mains evaluation, the photos of your answer pages are sent the same way. The relay does not store or log what is sent or what comes back. It records the time and size of each request against a scrambled install number, and counts the day's requests per install and per network address, to stop abuse. When Ask Crux AI searches the web, the search is run by the AI service, not from your phone. Anthropic handles the content under its own terms.")
            SheetPara("Photos of your answers", "Page photos are kept in the app's private storage only until the evaluation finishes, then deleted. What remains is Crux AI's reading of the pages and its feedback, stored encrypted with your result. DCrux does not ask for camera permission: your phone's own camera app takes the picture.")
            SheetPara("Location", "Optional. If you allow it, or type your city, only a city-level position (rounded to about a kilometre) is kept on the device and sent to Open-Meteo to fetch the weather. On phones without a built-in place lookup it is also sent to BigDataCloud to get the city's name. For Indian cities, the city's name and that rounded position also go to the DCrux service, which looks up the official air-quality monitors. Remove it any time from the weather sheet.")
            SheetPara("Other services the app talks to", "YouTube (channel pages and the embedded player), the news publishers you pick (their feeds and sites, opened in the in-app browser), Wikimedia Commons (photos) and GitHub (app updates). Each sees your network address, as any website does, and applies its own privacy policy.")
            SheetPara("Deleting everything", "Settings → Data → Reset the app erases all of the above from the phone.")
        }
        Info.TERMS -> CruxSheet("Terms of use", { sheet = null }, Icons.Rounded.Gavel) {
            SheetPara("A study aid", "DCrux is a free study aid for competitive-exam preparation. It is not affiliated with UPSC, any State Public Service Commission, any publisher or any YouTube channel.")
            SheetPara("AI content", "Notes, scores, questions, answers and evaluations are written by an AI and can be wrong or incomplete. Marks given to a Mains answer are an estimate to learn from, not a prediction of your result. Verify anything you rely on against the original source or a standard reference.")
            SheetPara("Other people's content", "Videos belong to their creators and play in YouTube's own player. Headlines and articles belong to their publishers and are read on the publishers' sites; paid e-papers need your own subscription. Do not use DCrux to copy or redistribute that content.")
            SheetPara("Fair use of the free AI service", "Crux AI is offered free through a shared service with limits that keep it available to everyone. Do not try to extract its credentials, automate requests, or use it for anything other than the app's features. Access can be limited for misuse.")
            SheetPara("Weather alerts", "Alerts are derived from forecast data and may be late or wrong. They are not official warnings. In severe weather follow the India Meteorological Department and local authorities.")
            SheetPara("No warranty", "The app is provided as is, without warranty of any kind, under the Apache License 2.0. The developer is not liable for loss arising from its use, to the extent the law allows.")
            SheetPara("Changes", "These terms may change with an app update; the current text is always here.")
        }
        Info.LICENCES -> CruxSheet("Licences and credits", { sheet = null }, Icons.Rounded.Code) {
            notices.forEach { n ->
                var open by rememberSaveable(n.name) { mutableStateOf(false) }
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable { open = !open }.padding(14.dp).animateContentSize(),
                ) {
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
        null -> {}
    }
}
