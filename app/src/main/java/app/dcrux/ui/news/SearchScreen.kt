package app.dcrux.ui.news

import app.dcrux.data.SearchTerms
import app.dcrux.data.search
import app.dcrux.data.searchCount
import app.dcrux.LocalGraph
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Newspaper
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dcrux.ui.components.appBackground
import app.dcrux.ai.ClaudeException
import app.dcrux.data.Dates
import app.dcrux.data.NewsItem
import app.dcrux.data.Settings
import app.dcrux.data.Video
import app.dcrux.net.Sources
import app.dcrux.ui.LocalNav
import app.dcrux.ui.Screen
import app.dcrux.ui.ScreenBar
import app.dcrux.ui.components.CruxCard
import app.dcrux.ui.components.CruxLoader
import app.dcrux.ui.components.EmptyState
import app.dcrux.ui.components.FancySwitch
import app.dcrux.ui.components.Markdown
import app.dcrux.ui.components.PaginationBar
import app.dcrux.ui.components.RelevancePill
import app.dcrux.ui.components.StopButton
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Search slides up from the bottom over whatever is on screen.
 * It looks through everything synced on the phone; the AI overview is written only from the hits.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchSheet(settings: Settings, onDismiss: () -> Unit) {
    val g = LocalGraph.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    var query by remember { mutableStateOf(nav.searchQuery) }
    var aiOn by rememberSaveable { mutableStateOf(false) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var videos by remember { mutableStateOf<List<Video>>(emptyList()) }
    var news by remember { mutableStateOf<List<NewsItem>>(emptyList()) }
    var newsTotal by remember { mutableIntStateOf(0) }
    var overview by remember { mutableStateOf<String?>(null) }
    var overviewFor by remember { mutableStateOf("") }
    var overviewBusy by remember { mutableStateOf(false) }
    var overviewError by remember { mutableStateOf<String?>(null) }
    var overviewJob by remember { mutableStateOf<Job?>(null) }
    val size = 8
    val listState = rememberLazyListState()
    val hasKey = remember { g.secure.hasApiKey() || g.hasBuiltInKey }
    val q = query.trim()

    // Wait for the sheet to finish sliding up before raising the keyboard, or the two fight.
    LaunchedEffect(Unit) { if (query.isBlank()) { delay(350); runCatching { focus.requestFocus() } } }
    LaunchedEffect(query) { nav.searchQuery = query }
    fun go(screen: Screen) { keyboard?.hide(); onDismiss(); nav.push(screen) }

    // Results follow the typing, with a short pause so the database is not hit per keystroke.
    LaunchedEffect(q, page) {
        if (q.length < 2) { videos = emptyList(); news = emptyList(); newsTotal = 0; return@LaunchedEffect }
        delay(250)
        val terms = SearchTerms.of(q)
        videos = if (page == 0) g.db.videos().search(terms, 8) else emptyList()
        news = g.db.news().search(terms, size, page * size)
        newsTotal = g.db.news().searchCount(terms)
    }

    fun writeOverview() {
        if (!aiOn || !hasKey || q.length < 2) return
        overviewJob?.cancel()
        keyboard?.hide()
        overviewJob = scope.launch {
            overviewBusy = true; overview = null; overviewError = null; overviewFor = q
            // The overview is the first row; make sure it is the one on screen.
            launch { listState.animateScrollToItem(0) }
            try {
                val terms = SearchTerms.of(q)
                val v = g.db.videos().search(terms, 4)
                val n = g.db.news().search(terms, 10, 0)
                val hits = v.map { it.title to (it.summary ?: it.detailed ?: it.description).take(700) } +
                    n.map { "${Sources.byId(settings, it.sourceId)?.name ?: it.sourceId}: ${it.title}" to listOfNotNull(it.snippet, it.note).joinToString(" ") }
                if (hits.isEmpty()) { overviewError = "Nothing synced matches this yet."; return@launch }
                overview = g.ai.overview(q, hits) { overview = it }
            } catch (e: ClaudeException) {
                overviewError = e.friendly
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                overviewError = e.message ?: "Could not write the overview"
            } finally {
                overviewBusy = false
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).imePadding()) {
        Text("Search", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 22.dp, bottom = 10.dp))
        OutlinedTextField(
            value = query, onValueChange = { query = it; page = 0 },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(focus),
            shape = CircleShape, singleLine = true,
            placeholder = { Text("Search videos, summaries and headlines") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = ""; overview = null }) { Icon(Icons.Rounded.Close, "Clear") } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); writeOverview() }),
        )
        Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("Crux AI overview", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (hasKey) "Written from your results when you press search" else "Needs an AI key",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FancySwitch(aiOn, { aiOn = it; if (it) writeOverview() else { overviewJob?.cancel(); overview = null; overviewError = null } }, enabled = hasKey)
        }

        LazyColumn(
            Modifier.weight(1f).navigationBarsPadding(), state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (aiOn && (overviewBusy || overview != null || overviewError != null)) {
                item("ai") {
                    CruxCard(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primaryContainer) {
                        Column(Modifier.padding(18.dp).animateContentSize()) {
                            Text("Overview for “$overviewFor”", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Spacer(Modifier.height(6.dp))
                            when {
                                overviewError != null -> Text(overviewError.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                                overview.isNullOrBlank() -> {
                                    CruxLoader("Reading your results")
                                    StopButton({ overviewJob?.cancel(); overviewBusy = false; overview = null; aiOn = false })
                                }
                                else -> {
                                    Markdown(overview.orEmpty(), color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    // Still being written: it can be stopped where it is.
                                    if (overviewBusy) StopButton({ overviewJob?.cancel(); overviewBusy = false }, Modifier.padding(top = 8.dp))
                                    Text(
                                        "Numbers in brackets refer to the results below, in order. Written only from them.",
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                                        modifier = Modifier.padding(top = 8.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            when {
                q.length < 2 -> item("hint") { EmptyState("Search your library", "Everything you have synced: video titles, summaries, headlines and AI notes. Words can be in any order, and a date such as 14 nov works too.") }
                videos.isEmpty() && news.isEmpty() -> item("none") { EmptyState("No matches", "Try fewer or different words, or sync on the Crux screen to bring in more.") }
                else -> {
                    if (videos.isNotEmpty()) {
                        item("vh") { Text("Videos", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp)) }
                        items(videos, key = { "v" + it.id }) { v ->
                            ResultRow(Icons.Rounded.PlayCircle, v.title, Dates.shortDate(v.publishedAt), v.relevance) { go(Screen.VideoDetail(v.id)) }
                        }
                    }
                    if (news.isNotEmpty()) {
                        item("nh") { Text("Headlines · $newsTotal", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp)) }
                        items(news, key = { "n" + it.id }) { n ->
                            val src = Sources.byId(settings, n.sourceId)?.name ?: n.sourceId
                            ResultRow(Icons.Rounded.Newspaper, n.title, "$src · ${Dates.prettyDay(n.dayKey)}", n.relevance) {
                                go(Screen.Reader(n.title, n.snippet, "$src · ${n.section} · ${Dates.dateTime(n.publishedAt)}", n.link, n.relevance, n.note, askId = n.id))
                            }
                        }
                        item("pg") { PaginationBar(page, (newsTotal + size - 1) / size, onPage = { page = it }) }
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun ResultRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, meta: String, relevance: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                RelevancePill(relevance)
            }
        }
    }
}
