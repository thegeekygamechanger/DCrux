package app.dcrux.ui.news

import app.dcrux.LocalGraph
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dcrux.data.Dates
import app.dcrux.data.NewsItem
import app.dcrux.data.Settings
import app.dcrux.net.EPapers
import app.dcrux.net.NewsSource
import app.dcrux.net.Sources
import app.dcrux.ui.LocalNav
import app.dcrux.ui.Screen
import app.dcrux.ui.components.bottomBarInset
import app.dcrux.ui.components.EmptyState
import app.dcrux.ui.components.PaginationBar
import app.dcrux.ui.components.TabTitle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
import app.dcrux.ui.components.paperTurn
import app.dcrux.ui.components.ReadModeButton
import app.dcrux.ui.components.RelevancePill
import app.dcrux.ui.components.Tag
import app.dcrux.ui.theme.Heros
import app.dcrux.ui.theme.LocalExtra
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NewsTab(settings: Settings) {
    val g = LocalGraph.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val sources = remember(settings.enabledSources, settings.customSources) { Sources.enabled(settings) }
    val source = sources.firstOrNull { it.id == settings.newsSource } ?: sources.firstOrNull()
    val sync by g.sync.state.collectAsState()
    var showEpapers by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                TabTitle("Papers")
            }
            IconButton(onClick = { nav.searchOpen = true }) { Icon(Icons.Rounded.Search, "Search") }
            IconButton(onClick = { showEpapers = true }) { Icon(Icons.AutoMirrored.Rounded.MenuBook, "E-papers") }
            if (sync.running) LoadingIndicator(Modifier.size(40.dp))
            else FilledTonalIconButton(onClick = { g.sync.syncNow() }) { Icon(Icons.Rounded.Refresh, "Refresh now") }
        }

        if (source == null) {
            EmptyState("No sources switched on", "Pick your crux sources in Settings.") {
                Button(onClick = { nav.openSettings() }) { Text("Open Settings") }
            }
            return@Column
        }

        PaperView(sources, source, settings)
    }

    if (showEpapers) {
        ModalBottomSheet(onDismissRequest = { showEpapers = false }) {
            LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
                item {
                    Text("E-papers", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Each one opens live on its publisher's own site inside DCrux. Nothing is downloaded or kept. " +
                            "Free ones may still ask for a sign-in; subscription papers use your own login.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                    )
                }
                items(EPapers.all, key = { it.url }) { e ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable { showEpapers = false; nav.push(Screen.Browser(e.url, e.name)) }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(e.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        val x = LocalExtra.current
                        if (e.free) Tag("Free", bg = x.high, fg = x.onHigh) else Tag("Subscription", bg = x.mid, fg = x.onMid)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ColumnScope.PaperView(sources: List<NewsSource>, source: NewsSource, settings: Settings) {
    val g = LocalGraph.current
    val scope = rememberCoroutineScope()
    val days by remember(source.id) { g.db.news().days(source.id) }.collectAsState(initial = null)
    var pickedDay by rememberSaveable(source.id) { mutableStateOf<String?>(null) }
    var picks by rememberSaveable { mutableStateOf(false) }
    var open by rememberSaveable { mutableStateOf(false) }
    val dayList = days ?: return
    val day = pickedDay?.takeIf { it in dayList } ?: dayList.firstOrNull() ?: Dates.dayKey()
    val minScore = if (picks) 60 else -1
    val size = settings.pageSize
    val total by remember(source.id, day, minScore) { g.db.news().count(source.id, day, minScore) }.collectAsState(initial = 0)
    val unrated by remember(source.id, day) { g.db.news().unratedCount(source.id, day) }.collectAsState(initial = 0)
    val jobs by g.ai.jobs.collectAsState()
    val job = jobs[g.ai.newsKey(source.id, day)]
    val hasKey = remember { g.secure.hasApiKey() || g.hasBuiltInKey }
    val pageCount = ((total + size - 1) / size).coerceAtLeast(1)
    val pager = rememberPagerState { pageCount }

    // Opening a paper rates whatever is still unrated for that day.
    LaunchedEffect(source.id, day, settings.aiCurate) {
        if (settings.aiCurate && hasKey) g.ai.curateNews(source.id, day)
    }
    LaunchedEffect(source.id, day, picks) { pager.scrollToPage(0) }

    // One line says what is showing; tap it and the filters open underneath.
    val turn by animateFloatAsState(if (open) 180f else 0f, label = "filters")
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable { open = !open }.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(Color(source.accent)))
            Spacer(Modifier.width(10.dp))
            Text(
                "${source.name} · ${Dates.prettyDay(day)} · ${if (picks) "Crux picks" else "All"}",
                Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Rounded.ExpandMore, if (open) "Hide filters" else "Show filters", Modifier.rotate(turn))
        }
        Spacer(Modifier.width(8.dp))
        when {
            job?.running == true -> LoadingIndicator(Modifier.size(36.dp))
            unrated > 0 && hasKey -> FilledTonalIconButton(onClick = { scope.launch { g.ai.clearJob(g.ai.newsKey(source.id, day)); g.ai.curateNews(source.id, day) } }) {
                Icon(Icons.Rounded.AutoAwesome, "Rate $unrated headlines")
            }
        }
    }
    AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Column {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(sources, key = { it.id }) { s ->
                    FilterChip(
                        selected = s.id == source.id,
                        onClick = { scope.launch { g.prefs.setNewsSource(s.id) } },
                        label = { Text(s.name) },
                        leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(Color(s.accent))) },
                        shape = CircleShape,
                    )
                }
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(dayList, key = { it }) { d ->
                    FilterChip(selected = d == day, onClick = { pickedDay = d }, label = { Text(Dates.prettyDay(d)) }, shape = CircleShape)
                }
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                listOf("All", "Crux picks").forEachIndexed { i, label ->
                    SegmentedButton(selected = picks == (i == 1), onClick = { picks = i == 1 }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
                }
            }
        }
    }
    job?.error?.let {
        Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
    }

    if (dayList.isEmpty()) {
        EmptyState("Nothing synced yet", "Refresh to fetch today's headlines from ${source.name}.") {
            Button(onClick = { g.sync.syncNow() }) { Text("Sync now") }
        }
        return
    }

    if (total == 0) {
        EmptyState(
            if (picks) "No crux picks yet" else "Nothing for this day",
            if (picks) "Picks are headlines scoring 60 or more for your exams. Rate the day's headlines first." else "Try another day or refresh.",
        )
        return
    }

    HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth().clipToBounds(), beyondViewportPageCount = 1) { page ->
        val items by remember(source.id, day, minScore, size, page) {
            g.db.news().page(source.id, day, minScore, if (picks) 1 else 0, size, page * size)
        }.collectAsState(initial = emptyList())
        Box(Modifier.fillMaxSize().paperTurn(pager, page, settings.pageFlip).padding(horizontal = 12.dp, vertical = 4.dp)) {
            PaperSheet(source, day, page, pageCount, items) { p -> scope.launch { pager.animateScrollToPage(p.coerceIn(0, pageCount - 1)) } }
        }
    }
}

@Composable
private fun PaperSheet(source: NewsSource, day: String, page: Int, pageCount: Int, items: List<NewsItem>, onPage: (Int) -> Unit) {
    val nav = LocalNav.current
    val g = LocalGraph.current
    var askAbout by remember { mutableStateOf<NewsItem?>(null) }
    askAbout?.let { item -> AskSheet(item, source.name) { askAbout = null } }
    val dark = LocalExtra.current.dark
    val paper = if (dark) Color(0xFF24232A) else Color(0xFFFFFBF2)
    val ink = if (dark) Color(0xFFEDE8E0) else Color(0xFF211F1C)
    val accent = Color(source.accent)
    Column(
        Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp)).background(paper).verticalScroll(rememberScrollState()).padding(18.dp),
    ) {
        Text(source.name, color = ink, style = MaterialTheme.typography.headlineMedium.copy(fontFamily = Heros), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        HorizontalDivider(thickness = 2.dp, color = ink)
        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(Dates.longDay(Dates.dayStart(day)), color = ink.copy(alpha = 0.75f), style = MaterialTheme.typography.labelMedium)
            Text("Page ${page + 1} of $pageCount", color = ink.copy(alpha = 0.75f), style = MaterialTheme.typography.labelMedium)
        }
        HorizontalDivider(thickness = 1.dp, color = ink.copy(alpha = 0.5f))
        items.forEachIndexed { i, n ->
            Column(Modifier.fillMaxWidth().clickable { nav.push(Screen.Browser(n.link, source.name)) }.padding(vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tag(n.section, bg = accent.copy(alpha = 0.16f), fg = if (dark) Color.White else accent)
                    RelevancePill(n.relevance)
                    Spacer(Modifier.weight(1f))
                    Text(Dates.time(n.publishedAt), color = ink.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.height(8.dp))
                Text(n.title, color = ink, style = MaterialTheme.typography.titleLarge.copy(fontFamily = Heros))
                if (n.snippet.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(n.snippet, color = ink.copy(alpha = 0.82f), style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                if (!n.note.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Row {
                        Icon(Icons.Rounded.AutoAwesome, null, Modifier.padding(top = 2.dp).size(14.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(6.dp))
                        Text(n.note, color = ink.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val asked by remember(n.id) { g.db.asks().count(n.id) }.collectAsState(initial = 0)
                    FilledTonalButton(onClick = { askAbout = n }, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                        Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                        Text(if (asked > 0) "Ask Crux AI · $asked" else "Ask Crux AI")
                    }
                    Spacer(Modifier.weight(1f))
                    ReadModeButton(onClick = {
                        nav.push(Screen.Reader(n.title, n.snippet, "${source.name} · ${n.section} · ${Dates.dateTime(n.publishedAt)}", n.link, n.relevance, n.note, askId = n.id))
                    })
                    IconButton(onClick = { nav.push(Screen.Browser(n.link, source.name)) }) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, "Read at the source", tint = ink.copy(alpha = 0.8f))
                    }
                }
            }
            if (i < items.lastIndex) HorizontalDivider(color = ink.copy(alpha = 0.18f))
        }
        // Page numbers sit at the foot of the sheet and scroll with it, leaving the screen to the news.
        HorizontalDivider(color = ink.copy(alpha = 0.18f))
        CompositionLocalProvider(LocalContentColor provides ink) {
            PaginationBar(page, pageCount, onPage = onPage, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
        }
        // The sheet runs to the bottom of the screen, under the floating bar; this lets the page numbers scroll clear of it.
        Spacer(Modifier.height(bottomBarInset()))
    }
}
