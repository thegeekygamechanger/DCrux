package app.dcrux.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Newspaper
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Quiz
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dcrux.data.Ask
import app.dcrux.data.Dates
import app.dcrux.data.Settings
import app.dcrux.data.TestResult
import app.dcrux.graph
import app.dcrux.mains.MainsStore
import app.dcrux.net.Sources
import app.dcrux.ui.LocalNav
import app.dcrux.ui.Screen
import app.dcrux.ui.ScreenBar
import app.dcrux.ui.components.CruxCard
import app.dcrux.ui.components.CruxSheet
import app.dcrux.ui.components.EmptyState
import app.dcrux.ui.components.Markdown
import app.dcrux.ui.components.PaginationBar
import app.dcrux.ui.components.Tag
import app.dcrux.ui.components.appBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One line of history: a question asked somewhere, or a test taken. */
private class Entry(
    val key: String, val kind: String, val at: Long, val title: String, val where: String,
    val ask: Ask? = null, val testId: Long = 0, val score: String = "",
)

private val filters = listOf("All", "Videos", "Articles", "Prelims", "Mains")

/**
 * Everything the student has done with Crux AI, newest first: each question with when and
 * where it was asked, and each test. Filter by kind, page through it, and open any entry.
 */
@Composable
fun HistoryScreen(settings: Settings) {
    val g = LocalContext.current.graph
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val asks by remember { g.db.asks().observeAll() }.collectAsState(initial = emptyList())
    val records by remember { g.db.tests().observeAll() }.collectAsState(initial = emptyList())
    var filter by rememberSaveable { mutableStateOf("All") }
    var page by rememberSaveable(filter) { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<Entry?>(null) }
    val size = 10

    val entries by produceState(emptyList<Entry>(), asks, records) {
        value = withContext(Dispatchers.Default) {
            val tests = records.mapNotNull { r -> MainsStore.decode(g, r)?.let { r.id to it } }.map { (id, t: TestResult) ->
                Entry("t$id", if (t.isMains) "Mains" else "Prelims", t.takenAt, t.title.removeSuffix(" · Mains"), "${t.exam} · ${t.difficulty}", testId = id, score = t.scoreText)
            }
            val asked = asks.map { a ->
                val video = a.articleId.startsWith("video:")
                // Questions saved before titles were kept: look the title up from what is still on the phone.
                val where = a.topic.ifBlank {
                    if (video) g.db.videos().get(a.articleId.removePrefix("video:"))?.title.orEmpty()
                    else g.db.news().observe(a.articleId).first()?.title.orEmpty()
                }.ifBlank { if (video) "A video" else "An article" }
                Entry("a${a.id}", if (video) "Videos" else "Articles", a.askedAt, a.question, where, ask = a)
            }
            (tests + asked).sortedByDescending { it.at }
        }
    }
    val shown = remember(entries, filter) { entries.filter { filter == "All" || it.kind == filter } }
    val pageCount = (shown.size + size - 1) / size
    val current = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))

    Column(Modifier.fillMaxSize().appBackground().statusBarsPadding()) {
        ScreenBar("History", onBack = { nav.pop() })
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            filters.forEach { f ->
                val n = if (f == "All") entries.size else entries.count { it.kind == f }
                FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text("$f · $n") }, shape = CircleShape)
            }
        }
        LazyColumn(
            Modifier.weight(1f).navigationBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (shown.isEmpty()) item("none") {
                EmptyState(
                    if (entries.isEmpty()) "Nothing here yet" else "Nothing under $filter yet",
                    "Questions you ask Crux AI and tests you take are listed here, with when and where, until you reset the app.",
                )
            }
            items(shown.drop(current * size).take(size), key = { it.key }) { e ->
                val icon: ImageVector = when (e.kind) { "Videos" -> Icons.Rounded.PlayCircle; "Articles" -> Icons.Rounded.Newspaper; "Mains" -> Icons.Rounded.EditNote; else -> Icons.Rounded.Quiz }
                CruxCard(Modifier.fillMaxWidth(), radius = 24.dp, onClick = { if (e.ask != null) open = e else nav.push(Screen.TestReview(e.testId)) }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Tag(when (e.kind) { "Videos" -> "Asked on a video"; "Articles" -> "Asked on an article"; else -> "${e.kind} test" })
                                Text(Dates.dateTime(e.at), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(e.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(e.where, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (e.score.isNotBlank()) Text(e.score, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            if (pageCount > 1) item("pg") { PaginationBar(current, pageCount, onPage = { page = it }) }
        }
    }

    open?.let { e ->
        val a = e.ask ?: return@let
        val video = a.articleId.startsWith("video:")
        CruxSheet("You asked", { open = null }, Icons.Rounded.AutoAwesome) {
            Text(a.question, style = MaterialTheme.typography.titleMedium)
            Text("${if (video) "On the video" else "On the article"}: ${e.where}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(Dates.dateTime(a.askedAt), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(16.dp)) {
                Markdown(g.ai.answerOnly(a.answer))
            }
            g.ai.sourcesOf(a.answer).forEach { (title, url) ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .clickable { open = null; nav.push(Screen.Browser(url, "Source")) }.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Public, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = {
                    open = null
                    scope.launch {
                        if (video) nav.push(Screen.VideoDetail(a.articleId.removePrefix("video:")))
                        else g.db.news().observe(a.articleId).first()?.let { n ->
                            val src = Sources.byId(settings, n.sourceId)?.name ?: n.sourceId
                            nav.push(Screen.Reader(n.title, n.snippet, "$src · ${n.section} · ${Dates.dateTime(n.publishedAt)}", n.link, n.relevance, n.note, askId = n.id))
                        }
                    }
                }) { Text(if (video) "Open the video" else "Open the article") }
                TextButton(onClick = { open = null; scope.launch { g.db.asks().delete(a.id) } }) { Text("Delete") }
            }
        }
    }
}
