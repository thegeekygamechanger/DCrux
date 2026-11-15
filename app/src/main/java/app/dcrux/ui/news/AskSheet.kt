package app.dcrux.ui.news

import app.dcrux.LocalGraph
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dcrux.data.Dates
import app.dcrux.data.NewsItem
import app.dcrux.ai.AiRepo
import app.dcrux.ui.LocalNav
import app.dcrux.ui.Screen
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Stop
import app.dcrux.ui.components.Markdown
import kotlinx.coroutines.launch

private val articleStarters = listOf(
    "Why is this in the news?",
    "Explain it for my exam",
    "Key terms, explained",
    "What could be asked on this?",
    "Give me the background",
)

private val videoStarters = listOf(
    "Explain this simply",
    "What could be asked on this?",
    "Key terms, explained",
    "Show it as a diagram",
    "Give me a way to remember it",
)

/** Ask AI about one article. */
@Composable
fun AskSheet(item: NewsItem, sourceName: String, onDismiss: () -> Unit) {
    val g = LocalGraph.current
    AskSheet(remember(item.id) { g.ai.topicFor(item, sourceName) }, "$sourceName · ${Dates.prettyDay(item.dayKey)}", onDismiss)
}

/**
 * Ask AI about whatever is being read or watched. Slides up over the page; every question and
 * answer on that topic is kept and shown here again the next time it is opened. Only answers are
 * shown: how the model reasoned stays out of sight.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AskSheet(topic: AiRepo.AskTopic, subtitle: String, onDismiss: () -> Unit) {
    val g = LocalGraph.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val id = topic.id
    val video = topic.kind == "video"
    val history by remember(id) { g.db.asks().forArticle(id) }.collectAsState(initial = emptyList())
    val jobs by g.ai.asking.collectAsState()
    val job = jobs[id]
    var input by rememberSaveable(id) { mutableStateOf("") }
    val list = rememberLazyListState()
    val ready = remember { g.secure.hasApiKey() || g.hasBuiltInKey }
    val busy = job != null && job.error == null

    fun send(question: String) {
        if (question.isBlank() || busy) return
        g.ai.ask(topic, question)
        input = ""
    }
    fun openSource(url: String) { onDismiss(); nav.push(Screen.Browser(url, "Source")) }
    // Fetch the article or captions as the sheet opens, so the first answer does not wait for them.
    LaunchedEffect(id) { if (ready) g.ai.warm(topic) }

    // When a question is asked, bring it to the top of the sheet and leave it there: the answer then
    // grows downward where it can be read from the start, and the view does not jump while text streams in.
    LaunchedEffect(job?.question) {
        if (job != null) list.animateScrollToItem(history.size)
    }
    // On opening, show the latest saved exchange.
    LaunchedEffect(id) {
        val saved = g.db.asks().latest(id, 1)
        if (saved.isNotEmpty() && job == null) list.scrollToItem((history.size - 1).coerceAtLeast(0))
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).navigationBarsPadding().imePadding()) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Ask Crux AI", style = MaterialTheme.typography.headlineSmall)
                }
                Text(topic.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                Text(
                    subtitle + if (history.isNotEmpty()) " · ${history.size} asked" else "",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(), state = list,
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (history.isEmpty() && job == null) {
                    item("empty") {
                        Text(
                            if (ready) "Ask any question or doubt about this ${if (video) "video" else "article"}. Answers come from it first; when it does not cover your question, Crux AI searches the web and shows its sources. Your questions stay saved here."
                            else "Ask Crux AI needs an AI key. Add one in Settings.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(history, key = { it.id }) { a ->
                    Column {
                        Exchange(a.question, Dates.dateTime(a.askedAt), onDelete = { scope.launch { g.db.asks().delete(a.id) } }) {
                            Markdown(g.ai.answerOnly(a.answer))
                            Sources(g.ai.sourcesOf(a.answer), ::openSource)
                        }
                        // Suggested next questions belong to the latest answer only.
                        if (a.id == history.last().id && job == null) FollowUps(a.followups.lines().filter { it.isNotBlank() }, enabled = ready) { send(it) }
                    }
                }
                // Shown only until its saved copy is in the history above (never both at once).
                if (job != null && history.none { it.question == job.question && job.error == null && job.text.isNotBlank() && g.ai.answerOnly(it.answer) == g.ai.answerOnly(job.text) }) {
                    item("live") {
                        Exchange(job.question, "now", onDelete = null) {
                            when {
                                job.error != null -> {
                                    Text(job.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                                    Row {
                                        TextButton(onClick = { g.ai.clearAsk(id); g.ai.ask(topic, job.question) }) { Text("Try again") }
                                        TextButton(onClick = { g.ai.clearAsk(id) }) { Text("Dismiss") }
                                    }
                                }
                                else -> {
                                    val shown = g.ai.answerOnly(job.text)
                                    if (shown.isBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                                        LoadingIndicator(Modifier.size(36.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text(if (job.searching) "Searching the web" else if (job.thinking.isBlank()) (if (video) "Going through the video" else "Reading the article") else "Working it out", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    } else Markdown(shown)
                                }
                            }
                        }
                    }
                }
            }

            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (if (video) videoStarters else articleStarters).forEach { s -> AssistChip(onClick = { send(s) }, enabled = ready && !busy, label = { Text(s) }, shape = CircleShape) }
            }
            Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f), enabled = ready,
                    shape = RoundedCornerShape(26.dp), maxLines = 4, placeholder = { Text(if (video) "Ask about this video" else "Ask about this article") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send(input) }),
                )
                Spacer(Modifier.width(10.dp))
                if (busy) FilledIconButton(onClick = { g.ai.stopAsk(id) }, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Rounded.Stop, "Stop the answer")
                } else FilledIconButton(onClick = { send(input) }, enabled = ready && input.isNotBlank(), modifier = Modifier.size(52.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.Send, "Ask")
                }
            }
        }
    }
}

/** One question (on the right, as the user's own) and its answer underneath. */
@Composable
private fun Exchange(question: String, whenText: String, onDelete: (() -> Unit)?, modifier: Modifier = Modifier, answer: @Composable () -> Unit) {
    // Each exchange eases in: the question from the right, the answer rising under it.
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val enter by animateFloatAsState(if (shown) 1f else 0f, spring(dampingRatio = 0.8f, stiffness = 260f), label = "enter")
    Column(modifier.fillMaxWidth().graphicsLayer { alpha = enter; translationY = (1f - enter) * 40f }) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(
                question, color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp, 22.dp, 22.dp, 22.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .animateContentSize(spring(dampingRatio = 0.9f, stiffness = 300f)).padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            answer()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(whenText, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (onDelete != null) IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.DeleteOutline, "Delete this question", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** The web pages an answer rests on, when it had to look beyond the article or video. Each opens in the in-app browser. */
@Composable
private fun Sources(sources: List<Pair<String, String>>, onOpen: (String) -> Unit) {
    if (sources.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("From the web", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        sources.forEach { (title, url) ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).clickable { onOpen(url) }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Public, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(runCatching { java.net.URI(url).host }.getOrNull() ?: url, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}

/** Three next questions suggested with the latest answer; they arrive one after another. */
@Composable
private fun FollowUps(questions: List<String>, enabled: Boolean, onAsk: (String) -> Unit) {
    if (questions.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Ask next", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        questions.forEachIndexed { i, q ->
            var shown by remember(q) { mutableStateOf(false) }
            LaunchedEffect(q) { delay(120L * (i + 1)); shown = true }
            val enter by animateFloatAsState(if (shown) 1f else 0f, spring(dampingRatio = 0.7f, stiffness = 300f), label = "follow")
            Row(
                Modifier.fillMaxWidth().graphicsLayer { alpha = enter; translationX = (1f - enter) * 60f }
                    .clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.secondaryContainer)
                    .clickable(enabled = enabled) { onAsk(q) }.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(q, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Icon(Icons.AutoMirrored.Rounded.Send, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}
