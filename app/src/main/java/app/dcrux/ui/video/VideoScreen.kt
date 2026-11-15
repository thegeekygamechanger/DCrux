package app.dcrux.ui.video

import app.dcrux.LocalGraph
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import app.dcrux.ui.news.AskSheet
import androidx.compose.material3.AlertDialog
import app.dcrux.ui.components.StopButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.dcrux.ui.components.appBackground
import app.dcrux.data.Dates
import app.dcrux.data.Settings
import app.dcrux.ui.LocalNav
import app.dcrux.ui.Screen
import app.dcrux.ui.ScreenBar
import app.dcrux.ui.components.CruxCard
import app.dcrux.ui.components.CruxLoader
import app.dcrux.ui.components.Markdown
import app.dcrux.ui.components.ReadModeButton
import app.dcrux.ui.components.RelevancePanel
import app.dcrux.ui.components.Tag
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun VideoScreen(videoId: String, settings: Settings) {
    val g = LocalGraph.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val video by remember(videoId) { g.db.videos().observe(videoId) }.collectAsState(initial = null)
    val jobs by g.ai.jobs.collectAsState()
    var detailed by rememberSaveable { mutableStateOf(settings.preferDetailed) }
    val hasKey = remember { g.secure.hasApiKey() || g.hasBuiltInKey }

    val controller = remember { PlayerController() }
    var playerReady by remember { mutableStateOf(false) }
    var playerError by remember { mutableStateOf<Int?>(null) }
    var userPlayed by remember { mutableStateOf(false) }
    var grabbing by remember { mutableStateOf(false) }
    var transcript by remember { mutableStateOf(g.ai.transcripts[videoId]) }
    val started = remember { mutableSetOf<Boolean>() }
    // Set when the video's captions could not be read; the summary is then not written automatically.
    var noCaptions by remember { mutableStateOf(false) }

    val v = video
    val text = if (detailed) v?.detailed else v?.summary
    val job = jobs[g.ai.videoKey(videoId, detailed)]

    var genJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var confirmFor by remember { mutableStateOf<Boolean?>(null) }
    fun stop() {
        genJob?.cancel()
        g.ai.cancel(g.ai.videoKey(videoId, detailed))
    }

    fun generate(mode: Boolean, fromDescription: Boolean = false, quiet: Boolean = false) {
        val current = video ?: return
        noCaptions = false
        genJob = scope.launch {
            var captions = transcript
            if (captions == null && !fromDescription) {
                grabbing = true
                controller.busy = true
                g.platform.aiWorkBegin()
                try {
                // Give the player a moment to come up, then let it load its caption track.
                withTimeoutOrNull(9_000) { snapshotFlow { playerReady || playerError != null }.first { it } }
                if (playerReady && playerError == null) {
                    // Prefer captions someone wrote, in the summary language; otherwise the auto-generated track.
                    val tracks = runCatching { g.youtube.captionTracks(current.id) }.getOrDefault(emptyList())
                    val want = if (settings.language == "English") "en" else "hi"
                    val track = tracks.sortedWith(
                        compareBy({ it.kind == "asr" }, { !it.lang.startsWith(want) }, { !it.lang.startsWith("en") && !it.lang.startsWith("hi") })
                    ).firstOrNull()
                    if (track != null) {
                        controller.grabCaptions(track.lang, track.kind, track.vssId)
                        captions = withTimeoutOrNull(25_000) { snapshotFlow { transcript }.filterNotNull().first() }
                        controller.doneCaptions(restore = !userPlayed)
                    }
                }
                // Hand over to the summary job (which holds the app alive itself) before letting go.
                if (captions != null) g.ai.summarize(current, mode, captions)
                } finally {
                    // Also runs when Stop is pressed while the captions are being read.
                    if (controller.busy && captions == null) runCatching { controller.doneCaptions(restore = !userPlayed) }
                    grabbing = false
                    controller.busy = false
                    g.platform.aiWorkEnd()
                }
                if (captions != null) return@launch
            }
            // The summary must be of what is said in the video. Without captions, stop and say so.
            if (captions == null && !fromDescription) { if (!quiet) noCaptions = true; return@launch }
            g.ai.summarize(current, mode, captions)
        }
    }

    // Nothing is written without a yes: opening a video (or flipping the toggle) asks first.
    LaunchedEffect(v?.id, detailed) {
        if (v == null || !hasKey || job != null || grabbing) return@LaunchedEffect
        if (text == null && started.add(detailed)) confirmFor = detailed
    }
    confirmFor?.let { mode ->
        AlertDialog(
            onDismissRequest = { confirmFor = null },
            title = { Text(if (mode) "Write detailed notes?" else "Write the summary?") },
            text = {
                Text(
                    "Crux AI will read what is said in this video, write " + (if (mode) "detailed notes" else "the summary") +
                        ", and then check every point against the video. " + (if (mode) "This takes a minute or two." else "This takes under a minute.") + " You can stop it at any time."
                )
            },
            confirmButton = { Button(onClick = { confirmFor = null; generate(mode) }) { Text("Continue") } },
            dismissButton = { TextButton(onClick = { confirmFor = null }) { Text("Not now") } },
        )
    }

    // Ask AI about this video: its notes and captions are what the answers rest on.
    var askOpen by remember { mutableStateOf(false) }
    if (askOpen && v != null) AskSheet(remember(v) { g.ai.topicFor(v) }, "Video · ${Dates.shortDate(v.publishedAt)}") { askOpen = false }

    Column(Modifier.fillMaxSize().appBackground().statusBarsPadding()) {
        ScreenBar("Video crux", onBack = { nav.pop() }) {
            if (text != null && v != null) {
                IconButton(onClick = { askOpen = true }) { Icon(Icons.Rounded.AutoAwesome, "Ask Crux AI", tint = MaterialTheme.colorScheme.primary) }
                ReadModeButton(onClick = {
                    nav.push(Screen.Reader(v.title, text, (if (detailed) "Detailed notes" else "Summary") + " · " + Dates.shortDate(v.publishedAt), v.watchUrl, v.relevance, askId = "video:${v.id}"))
                })
            }
            IconButton(onClick = {
                g.platform.openUrl("https://www.youtube.com/watch?v=$videoId")
            }) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, "Open in YouTube") }
        }

        // One scrolling column: the player moves up with the page instead of staying pinned above it.
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding()) {
        YouTubePlayer(
            videoId, controller,
            Modifier.padding(horizontal = 12.dp).fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(24.dp)).background(Color.Black),
            onReady = { playerReady = true },
            onState = { s -> if (s == 1 && !grabbing) userPlayed = true },
            onError = { playerError = it },
            onTranscript = { t ->
                if (t.length > (transcript?.length ?: 0)) {
                    transcript = t
                    g.ai.transcripts[videoId] = t
                }
            },
        )

        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) inner@{
            if (v == null) return@inner
            Text(v.title, style = MaterialTheme.typography.headlineSmall)
            Text(Dates.dateTime(v.publishedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (playerError != null) {
                Text(
                    "This video cannot be played inside other apps (the uploader's setting). Use the open-in-YouTube button above.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                )
            }

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("Summary", "Detailed").forEachIndexed { i, label ->
                    SegmentedButton(
                        selected = detailed == (i == 1), onClick = { detailed = i == 1 },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                    ) { Text(label) }
                }
            }

            CruxCard(Modifier.fillMaxWidth()) {
                AnimatedContent(
                    targetState = when {
                        grabbing -> "grab"
                        job?.running == true -> "run"
                        job?.error != null -> "error"
                        noCaptions -> "nocaps"
                        text != null -> "text"
                        !hasKey -> "nokey"
                        else -> "idle"
                    },
                    transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "crux",
                    modifier = Modifier.animateContentSize(),
                ) { state ->
                    Column(Modifier.fillMaxWidth().padding(18.dp)) {
                        when (state) {
                            "text" -> {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                    Tag(
                                        if (v.basis == "captions") "From what is said in the video, checked against it" else "From title and description only",
                                        bg = if (v.basis == "captions") MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.errorContainer,
                                        fg = if (v.basis == "captions") MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                Markdown(text.orEmpty(), scale = settings.readerScale)
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "Written by Crux AI. Check important facts against the video.",
                                        Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    TextButton(onClick = { generate(detailed) }) {
                                        Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Redo")
                                    }
                                }
                            }
                            "grab" -> {
                                CruxLoader("Listening to the video", "Reading its captions; the player runs muted for a few seconds")
                                StopButton({ stop() }, Modifier.align(Alignment.CenterHorizontally))
                            }
                            "nocaps" -> {
                                Text("Could not read this video's captions", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "A summary of what is said needs the video's captions. This video has none, or the player did not load them. " +
                                        "Press play once and try again.",
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(12.dp))
                                Button(onClick = { generate(detailed) }) { Text("Try again") }
                                TextButton(onClick = { generate(detailed, fromDescription = true) }) { Text("Use only the title and description instead") }
                                if (text != null) TextButton(onClick = { noCaptions = false }) { Text("Back to the saved text") }
                            }
                            "run" -> {
                                CruxLoader(
                                    job?.stage ?: "Working",
                                    if ((job?.chars ?: 0) > 0) "${job?.chars} characters so far" else "Crux AI is reading and thinking",
                                )
                                StopButton({ stop() }, Modifier.align(Alignment.CenterHorizontally))
                            }
                            "error" -> {
                                Text("Could not write this", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(4.dp))
                                Text(job?.error.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.height(12.dp))
                                Button(onClick = { g.ai.clearJob(g.ai.videoKey(videoId, detailed)); generate(detailed) }) { Text("Try again") }
                            }
                            "nokey" -> {
                                Text("Add an AI key", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(4.dp))
                                Text("Summaries are written by Crux AI with your own key, stored only on this phone.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(12.dp))
                                FilledTonalButton(onClick = { nav.openSettings() }) { Text("Open Settings") }
                            }
                            else -> Button(onClick = { generate(detailed) }) {
                                Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                                Text(if (detailed) "Write detailed notes" else "Write the crux")
                            }
                        }
                    }
                }
            }

            if (v.relevance != null) {
                CruxCard(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.padding(18.dp)) {
                        Text("Exam relevance", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(12.dp))
                        RelevancePanel(v.relevance)
                    }
                }
            }
        }
        }
    }
}
