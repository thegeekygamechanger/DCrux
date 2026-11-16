package app.dcrux.ui.tests

import androidx.compose.foundation.layout.widthIn
import app.dcrux.ui.components.LocalWide
import app.dcrux.ui.components.CruxList
import app.dcrux.ui.OnBack
import androidx.compose.ui.graphics.ImageBitmap
import app.dcrux.LocalGraph
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.Layout
import app.dcrux.Graph
import app.dcrux.ai.AiRepo
import app.dcrux.ai.ClaudeException
import app.dcrux.data.QuizQuestion
import app.dcrux.mains.MainsStore
import app.dcrux.ui.components.Confetti
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import app.dcrux.data.AppJson
import app.dcrux.data.Dates
import app.dcrux.data.Settings
import app.dcrux.data.TestRecord
import app.dcrux.data.TestResult
import app.dcrux.ui.LocalNav
import app.dcrux.ui.Screen
import app.dcrux.ui.ScreenBar
import app.dcrux.ui.components.bottomBarInset
import app.dcrux.ui.components.CruxCard
import app.dcrux.ui.components.CruxLoader
import app.dcrux.ui.components.EmptyState
import app.dcrux.ui.components.PaginationBar
import app.dcrux.ui.components.Stars
import app.dcrux.ui.components.StopButton
import app.dcrux.ui.components.Tag
import app.dcrux.ui.components.TabTitle
import app.dcrux.ui.components.appBackground
import app.dcrux.ui.components.shortExam
import app.dcrux.ui.theme.BrandColors
import app.dcrux.ui.theme.LocalExtra
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun decode(g: Graph, record: TestRecord): TestResult? = MainsStore.decode(g, record)

fun starsFor(correct: Int, total: Int): Int {
    if (total == 0) return 0
    val pct = correct * 100 / total
    return when {
        pct >= 90 -> 5
        pct >= 75 -> 4
        pct >= 60 -> 3
        pct >= 40 -> 2
        pct > 0 -> 1
        else -> 0
    }
}

// ------------------------------------------------------------------ tab

/**
 * Two blocks side by side at equal width and, when they are close in height, equal height too, so their
 * edges line up. When the right one grows much taller (its options are open) the left keeps its own height.
 */
@Composable
private fun SideBySide(gap: Dp, left: @Composable () -> Unit, right: @Composable () -> Unit) {
    Layout(content = {
        Box(propagateMinConstraints = true) { left() }
        Box(propagateMinConstraints = true) { right() }
    }) { measurables, constraints ->
        val space = gap.roundToPx()
        val w = ((constraints.maxWidth - space) / 2).coerceAtLeast(0)
        val leftOwn = measurables[0].minIntrinsicHeight(w)
        val r = measurables[1].measure(Constraints(minWidth = w, maxWidth = w, minHeight = leftOwn))
        val together = r.height <= leftOwn + 96.dp.roundToPx()
        val l = measurables[0].measure(if (together) Constraints.fixed(w, r.height) else Constraints(minWidth = w, maxWidth = w, minHeight = leftOwn))
        layout(constraints.maxWidth, maxOf(l.height, r.height)) { l.place(0, 0); r.place(w + space, 0) }
    }
}

/** The Prelims / Mains switch: two halves of one pill, the chosen one filled. */
@Composable
private fun ModeSwitch(mains: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(5.dp)) {
        listOf(false to ("Prelims" to "Multiple choice"), true to ("Mains" to "Written answers")).forEach { (value, text) ->
            val selected = mains == value
            val bg by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, label = "mode")
            val fg by animateColorAsState(if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, label = "modeFg")
            Column(
                Modifier.weight(1f).clip(CircleShape).background(bg)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onChange(value) }).padding(vertical = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text.first, color = fg, style = MaterialTheme.typography.titleSmall)
                Text(text.second, color = fg.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** A retake asks the same questions with the options (and the questions) in a new order, so position cannot be memorised. */
private fun reshuffled(questions: List<QuizQuestion>): List<QuizQuestion> = questions.shuffled().map { q ->
    val order = q.options.indices.shuffled()
    q.copy(options = order.map { q.options[it] }, answer = order.indexOf(q.answer))
}

@Composable
fun TestsTab(settings: Settings) {
    val g = LocalGraph.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val records by g.db.tests().observeAll().collectAsState(initial = emptyList())
    val all by produceState(emptyList<Pair<Long, TestResult>>(), records) {
        value = withContext(Dispatchers.Default) { records.mapNotNull { r -> decode(g, r)?.let { r.id to it } } }
    }
    var mains by rememberSaveable { mutableStateOf(false) }
    val results = remember(all, mains) { all.filter { it.second.isMains == mains } }
    val exams = remember(settings.exams) { settings.exams.toList().sortedByDescending { it == "UPSC CSE" } }
    var exam by rememberSaveable { mutableStateOf(exams.first()) }
    if (exam !in exams) exam = exams.first()
    val days by produceState(listOf(Dates.dayKey()), settings.lastSync) { value = g.db.news().recentDays().take(7).ifEmpty { listOf(Dates.dayKey()) } }
    var day by rememberSaveable { mutableStateOf(Dates.dayKey()) }
    if (day !in days) day = days.first()
    var difficulty by rememberSaveable { mutableStateOf("Moderate") }
    var count by rememberSaveable { mutableIntStateOf(10) }
    var mainsCount by rememberSaveable { mutableIntStateOf(2) }
    var marks by rememberSaveable { mutableIntStateOf(10) }
    var busy by remember { mutableStateOf(false) }
    var buildJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var optionsOpen by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // History lists every test, labelled by type; it has its own filter and order.
    var histFilter by rememberSaveable { mutableStateOf("All") }
    var histSort by rememberSaveable { mutableStateOf("Newest") }
    var sortMenu by remember { mutableStateOf(false) }
    var page by rememberSaveable(histFilter, histSort) { mutableIntStateOf(0) }
    val history = remember(all, histFilter, histSort) {
        val list = all.filter { histFilter == "All" || (histFilter == "Mains") == it.second.isMains }
        when (histSort) {
            "Oldest" -> list.sortedBy { it.second.takenAt }
            "Highest score" -> list.sortedByDescending { it.second.percent }
            "Lowest score" -> list.sortedBy { it.second.percent }
            else -> list.sortedByDescending { it.second.takenAt }
        }
    }
    var deleteAsk by remember { mutableStateOf<Pair<Long, TestResult>?>(null) }
    var replaceAsk by remember { mutableStateOf<(() -> Unit)?>(null) }
    val hasKey = remember { g.secure.hasApiKey() || g.hasBuiltInKey }
    val draft by g.mains.draft.collectAsState()
    val evaluation by g.mains.progress.collectAsState()
    val size = if (LocalWide.current) 12 else 5

    /** Starts a Mains attempt, asking first if one is already in progress. */
    fun startMains(begin: () -> Unit) {
        if (draft != null) replaceAsk = begin else begin()
    }

    // The three blocks above the history. On a phone they are stacked; in a wide window the switch and the
    // score sit beside the new-test card, edge to edge and the same height.
    val wide = LocalWide.current
    // An attempt in progress, being evaluated, or just evaluated while you were elsewhere.
    val ev = evaluation
    val d = draft
    val showDraft = mains && (d != null || ev?.doneId != null)
    val statsCard: @Composable (Modifier) -> Unit = { m ->
        val stars = results.sumOf { it.second.stars }
        val avg = if (results.isEmpty()) 0 else results.sumOf { it.second.percent } / results.size
        Box(m.fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(Brush.linearGradient(BrandColors)).padding(20.dp), contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("$stars", color = Color.White, style = MaterialTheme.typography.displayMedium)
                    Text("stars earned", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.labelLarge)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(if (mains) "${results.size} Mains sets" else "${results.size} tests", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text("$avg% average", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Lock, null, Modifier.size(14.dp), tint = Color.White)
                        Spacer(Modifier.width(4.dp))
                        Text("Encrypted on this device", color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
    val draftCard: @Composable () -> Unit = {
        CruxCard(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primaryContainer) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val ink = MaterialTheme.colorScheme.onPrimaryContainer
                if (ev?.doneId != null) {
                    Text("Your evaluation is ready", style = MaterialTheme.typography.titleLarge, color = ink)
                    Button(onClick = {
                        g.mains.clearProgress()
                        nav.push(Screen.TestReview(ev.doneId, fresh = true, shown = if (ev.improved) null else ev.result, note = retakeNote(ev.improved, ev.previous, ev.result)))
                    }) { Text("See the result") }
                } else if (d != null) {
                    val done = d.pages.count { it.isNotEmpty() }
                    Text(if (ev?.running == true) "Evaluating your answers" else "Unfinished attempt", style = MaterialTheme.typography.titleLarge, color = ink)
                    Text("${d.title} · $done of ${d.questions.size} answers photographed", style = MaterialTheme.typography.bodyMedium, color = ink)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { nav.push(Screen.MainsRun) }) { Text(if (ev?.running == true) "Open" else "Resume") }
                        if (ev?.running != true) TextButton(onClick = { g.mains.discard() }) { Text("Discard") }
                    }
                }
            }
        }
    }
    val builderCard: @Composable (Modifier) -> Unit = { m ->
        CruxCard(m, color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(18.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (busy) {
                    if (mains) CruxLoader("Setting your Mains questions", "$mainsCount questions of $marks marks for ${shortExam(exam)}")
                    else CruxLoader("Writing your test", "$count $difficulty questions for ${shortExam(exam)}")
                    StopButton({ buildJob?.cancel(); busy = false }, Modifier.align(Alignment.CenterHorizontally))
                    return@Column
                }
                Text(if (mains) "New Mains set" else "New test", style = MaterialTheme.typography.titleLarge)
                if (mains) Text(
                    "Write each answer on paper against the clock, photograph the pages, and get them read and marked like a mentor would.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val turn by animateFloatAsState(if (optionsOpen) 180f else 0f, label = "options")
                Row(
                    Modifier.fillMaxWidth().clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest).clickable { optionsOpen = !optionsOpen }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (mains) "${shortExam(exam)} · ${Dates.prettyDay(day)} · $difficulty · $mainsCount × $marks marks"
                        else "${shortExam(exam)} · ${Dates.prettyDay(day)} · $difficulty · $count questions",
                        Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Icon(Icons.Rounded.ExpandMore, if (optionsOpen) "Hide options" else "Show options", Modifier.rotate(turn))
                }
                AnimatedVisibility(optionsOpen) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Exam", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            exams.forEach { e -> FilterChip(selected = e == exam, onClick = { exam = e }, label = { Text(shortExam(e)) }, shape = CircleShape) }
                        }
                        Text("News of", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            days.forEach { dk -> FilterChip(selected = dk == day, onClick = { day = dk }, label = { Text(Dates.prettyDay(dk)) }, shape = CircleShape) }
                        }
                        Text("Difficulty", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            listOf("Easy", "Moderate", "Hard").forEachIndexed { i, df ->
                                SegmentedButton(selected = difficulty == df, onClick = { difficulty = df }, shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(df) }
                            }
                        }
                        if (mains) {
                            Text("Marks per question", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                listOf(10, 15).forEachIndexed { i, m ->
                                    SegmentedButton(selected = marks == m, onClick = { marks = m }, shape = SegmentedButtonDefaults.itemShape(i, 2)) {
                                        Text("$m marks · ${AiRepo.wordsFor(m)} words")
                                    }
                                }
                            }
                            Text("Questions", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                listOf(1, 2, 3, 5).forEachIndexed { i, n ->
                                    SegmentedButton(selected = mainsCount == n, onClick = { mainsCount = n }, shape = SegmentedButtonDefaults.itemShape(i, 4)) { Text("$n") }
                                }
                            }
                        } else {
                            Text("Questions", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                listOf(5, 10, 15, 20).forEachIndexed { i, n ->
                                    SegmentedButton(selected = count == n, onClick = { count = n }, shape = SegmentedButtonDefaults.itemShape(i, 4)) { Text("$n") }
                                }
                            }
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                fun generate() {
                    busy = true; error = null
                    buildJob = scope.launch {
                        try {
                            val title = "${shortExam(exam)} · ${Dates.prettyDay(day)}"
                            if (mains) {
                                val qs = g.ai.generateMains(day, exam, difficulty, mainsCount, marks)
                                g.mains.start("$title · Mains", exam, difficulty, day, qs)
                                nav.push(Screen.MainsRun)
                            } else {
                                val qs = g.ai.generateQuiz(day, exam, difficulty, count)
                                nav.push(Screen.TestRun(title, exam, difficulty, day, qs))
                            }
                        } catch (e: ClaudeException) {
                            error = e.friendly
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.message ?: "Could not build the test"
                        } finally {
                            busy = false
                        }
                    }
                }
                Button(
                    enabled = hasKey, modifier = Modifier.fillMaxWidth().height(54.dp),
                    onClick = { if (mains) startMains { generate() } else generate() },
                ) {
                    Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(if (!hasKey) "Needs an AI key" else if (mains) "Set the questions" else "Generate test")
                }
            }
        }
    }

    CruxList(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp + bottomBarInset()),
        spacing = 16.dp,
    ) {
        item("head", full = true) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TabTitle("Tests", Modifier.weight(1f))
                FilledTonalIconButton(onClick = { nav.searchOpen = true }) { Icon(Icons.Rounded.Search, "Search") }
            }
        }

        if (wide) item("top", full = true) {
            SideBySide(
                16.dp,
                left = {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        ModeSwitch(mains) { mains = it; error = null }
                        statsCard(Modifier.weight(1f))
                        if (showDraft) draftCard()
                    }
                },
                right = { builderCard(Modifier.fillMaxWidth()) },
            )
        } else {
            item("mode", full = true) { ModeSwitch(mains) { mains = it; error = null } }
            item("stats") { statsCard(Modifier) }
            if (showDraft) item("draft") { draftCard() }
            item("builder") { builderCard(Modifier) }
        }

        item("hh", full = true) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("History", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    Box {
                        Row(
                            Modifier.clip(CircleShape).clickable { sortMenu = true }.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.Sort, "Sort history", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(6.dp))
                            Text(histSort, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                        DropdownMenu(sortMenu, onDismissRequest = { sortMenu = false }) {
                            listOf("Newest", "Oldest", "Highest score", "Lowest score").forEach { o ->
                                DropdownMenuItem(
                                    text = { Text(o) }, onClick = { histSort = o; sortMenu = false },
                                    trailingIcon = { if (o == histSort) Icon(Icons.Rounded.CheckCircle, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary) },
                                )
                            }
                        }
                    }
                }
                if (all.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("All", "Prelims", "Mains").forEach { f ->
                        val n = when (f) { "All" -> all.size; "Mains" -> all.count { it.second.isMains }; else -> all.count { !it.second.isMains } }
                        FilterChip(selected = histFilter == f, onClick = { histFilter = f }, label = { Text("$f · $n") }, shape = CircleShape)
                    }
                }
            }
        }
        if (history.isEmpty()) {
            item("none", full = true) {
                EmptyState(
                    if (all.isEmpty()) "No tests yet" else "No $histFilter tests yet",
                    "Your results will be kept here, encrypted, until you reset the app.",
                )
            }
        } else {
            val pageCount = (history.size + size - 1) / size
            val shown = history.drop(page.coerceIn(0, pageCount - 1) * size).take(size)
            items(shown, key = { it.first }) { (id, r) ->
                CruxCard(Modifier.fillMaxWidth(), onClick = { nav.push(Screen.TestReview(id)) }) {
                    Column(Modifier.padding(start = 16.dp, top = 14.dp, end = 8.dp, bottom = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                if (r.isMains) Tag("Mains", bg = MaterialTheme.colorScheme.primaryContainer, fg = MaterialTheme.colorScheme.onPrimaryContainer)
                                else Tag("Prelims", bg = MaterialTheme.colorScheme.tertiaryContainer, fg = MaterialTheme.colorScheme.onTertiaryContainer)
                                Spacer(Modifier.height(6.dp))
                                Text(r.title.removeSuffix(" · Mains"), style = MaterialTheme.typography.titleMedium)
                                Text("${r.difficulty} · ${Dates.dateTime(r.takenAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(r.scoreText, Modifier.padding(end = 8.dp), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Stars(r.stars)
                            if (r.attempts > 1) { Spacer(Modifier.width(8.dp)); Tag("Best of ${r.attempts}") }
                            Spacer(Modifier.weight(1f))
                            // Retaking is for beating your own score: the entry keeps whichever attempt was better.
                            if (r.percent < 100) IconButton(onClick = {
                                if (r.isMains) startMains {
                                    g.mains.start(r.title, r.exam, r.difficulty, r.dayKey, r.mains.map { it.question }, retakeOf = id)
                                    nav.push(Screen.MainsRun)
                                } else nav.push(Screen.TestRun(r.title, r.exam, r.difficulty, r.dayKey, reshuffled(r.questions), retakeOf = id))
                            }) { Icon(Icons.Rounded.Replay, "Retake to improve the score", tint = MaterialTheme.colorScheme.primary) }
                            IconButton(onClick = { deleteAsk = id to r }) { Icon(Icons.Rounded.DeleteOutline, "Delete this result", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
            item("pg", full = true) { PaginationBar(page.coerceIn(0, pageCount - 1), pageCount, onPage = { page = it }) }
        }
    }

    deleteAsk?.let { (id, r) ->
        AlertDialog(
            onDismissRequest = { deleteAsk = null },
            title = { Text("Delete this result?") },
            text = { Text("${r.title}, ${r.scoreText}. It will be removed from this device and cannot be brought back.") },
            confirmButton = { TextButton(onClick = { deleteAsk = null; scope.launch { g.db.tests().delete(id); g.cloud.syncSoon() } }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteAsk = null }) { Text("Keep") } },
        )
    }
    replaceAsk?.let { begin ->
        AlertDialog(
            onDismissRequest = { replaceAsk = null },
            title = { Text("Replace the unfinished attempt?") },
            text = { Text("You have a Mains attempt in progress. Starting a new one discards it and its page photos.") },
            confirmButton = { TextButton(onClick = { replaceAsk = null; g.mains.discard(); begin() }) { Text("Start new") } },
            dismissButton = { TextButton(onClick = { replaceAsk = null }) { Text("Keep it") } },
        )
    }
}

/** What to say above a result that came from a retake. */
fun retakeNote(improved: Boolean, previous: String, result: TestResult?): String = when {
    previous.isBlank() || result == null -> ""
    improved -> "New best. Your earlier score was $previous."
    else -> "This attempt scored ${result.scoreText}. Your earlier ${previous} is still your best and stays in your history."
}

// ------------------------------------------------------------------ taking a test

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TestRunScreen(screen: Screen.TestRun) {
    val g = LocalGraph.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val questions = screen.questions
    val answers = remember { mutableStateListOf<Int>().apply { repeat(questions.size) { add(-1) } } }
    val pager = rememberPagerState { questions.size }
    val startedAt = remember { System.currentTimeMillis() }
    var seconds by remember { mutableIntStateOf(0) }
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmSubmit by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { while (true) { delay(1000); seconds = ((System.currentTimeMillis() - startedAt) / 1000).toInt() } }
    OnBack { confirmLeave = true }

    fun submit() {
        if (saving) return
        saving = true
        scope.launch {
            val correct = questions.indices.count { answers[it] == questions[it].answer }
            val result = TestResult(
                title = screen.title, exam = screen.exam, difficulty = screen.difficulty, dayKey = screen.dayKey,
                questions = questions, answers = answers.toList(), correct = correct, total = questions.size,
                stars = starsFor(correct, questions.size), durationSec = seconds, takenAt = System.currentTimeMillis(),
            )
            val (id, improved, previous) = MainsStore.save(g, result, screen.retakeOf)
            nav.replace(Screen.TestReview(id, fresh = true, shown = if (improved) null else result, note = retakeNote(improved, previous, result)))
        }
    }

    val answered = answers.count { it >= 0 }
    Column(Modifier.fillMaxSize().appBackground().statusBarsPadding().navigationBarsPadding()) {
        ScreenBar(screen.title, onBack = { confirmLeave = true }) {
            Tag("%d:%02d".format(seconds / 60, seconds % 60))
            Spacer(Modifier.width(8.dp))
        }
        val progress by animateFloatAsState(answered / questions.size.toFloat(), spring(dampingRatio = 0.7f), label = "progress")
        LinearWavyProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        Text(
            "$answered of ${questions.size} answered · ${screen.difficulty}", Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalPager(pager, Modifier.weight(1f), pageSpacing = 12.dp, contentPadding = PaddingValues(horizontal = 16.dp)) { i ->
            val q = questions[i]
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CruxCard(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Tag("Question ${i + 1}", bg = MaterialTheme.colorScheme.primaryContainer, fg = MaterialTheme.colorScheme.onPrimaryContainer)
                            if (q.topic.isNotBlank()) Tag(q.topic, Modifier.weight(1f, fill = false))
                            ScoreTag(q.relevance)
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(q.q, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium))
                    }
                }
                q.options.forEachIndexed { j, option ->
                    val selected = answers[i] == j
                    val bg by animateColorAsState(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow, label = "opt")
                    val scale by animateFloatAsState(if (selected) 1f else 0.98f, spring(dampingRatio = 0.5f, stiffness = 500f), label = "optS")
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(bg)
                            .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(22.dp))
                            .clickable { answers[i] = if (selected) -1 else j }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size((34 * scale).dp).clip(CircleShape).background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
                            contentAlignment = Alignment.Center,
                        ) { Text("${'A' + j}", color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge) }
                        Spacer(Modifier.width(12.dp))
                        Text(option, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        PaginationBar(pager.currentPage, questions.size, onPage = { p -> scope.launch { pager.animateScrollToPage(p.coerceIn(0, questions.lastIndex)) } }, modifier = Modifier.padding(vertical = 8.dp))
        Button(onClick = { confirmSubmit = true }, enabled = !saving, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp).height(54.dp)) {
            Text(if (answered == questions.size) "Submit test" else "Submit ($answered of ${questions.size} answered)")
        }
    }

    if (confirmSubmit) AlertDialog(
        onDismissRequest = { confirmSubmit = false },
        title = { Text("Submit the test?") },
        text = { Text(if (answered < questions.size) "${questions.size - answered} questions are unanswered and will count as wrong." else "You answered every question.") },
        confirmButton = { Button(onClick = { confirmSubmit = false; submit() }) { Text("Submit") } },
        dismissButton = { TextButton(onClick = { confirmSubmit = false }) { Text("Keep going") } },
    )
    if (confirmLeave) AlertDialog(
        onDismissRequest = { confirmLeave = false },
        title = { Text("Finish before you leave?") },
        text = { Text("You have answered $answered of ${questions.size}. Stay and complete today's target; if you leave now, this attempt is lost.") },
        confirmButton = { Button(onClick = { confirmLeave = false }) { Text("Keep going") } },
        dismissButton = { TextButton(onClick = { confirmLeave = false; nav.pop() }) { Text("Leave") } },
    )
}

// ------------------------------------------------------------------ result

/** How much a question's theme matters for the chosen exam, out of 100, in the app's usual colours. */
@Composable
fun ScoreTag(score: Int, modifier: Modifier = Modifier) {
    if (score < 0) return
    val x = LocalExtra.current
    val (bg, fg) = when { score >= 70 -> x.high to x.onHigh; score >= 45 -> x.mid to x.onMid; else -> x.low to x.onLow }
    Tag("Relevance $score/100", modifier, bg = bg, fg = fg)
}

@Composable
fun TestReviewScreen(screen: Screen.TestReview) {
    val id = screen.id
    val fresh = screen.fresh
    val g = LocalGraph.current
    val nav = LocalNav.current
    val stored by produceState<TestResult?>(null, id) {
        value = withContext(Dispatchers.Default) { g.db.tests().get(id)?.let { decode(g, it) } }
    }
    var page by rememberSaveable { mutableIntStateOf(0) }
    val r = screen.shown ?: stored
    // The PDF is made from the stored result, so it is offered only when that is what is on screen.
    val canPdf = screen.shown == null && !g.platform.isDesktop
    val celebrate = fresh && r != null && r.stars >= 4
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().appBackground().statusBarsPadding()) {
        ScreenBar("Result", onBack = { nav.pop() }) {
            if (canPdf) {
                IconButton(onClick = { nav.push(Screen.PdfPreview(id)) }) { Icon(Icons.Rounded.PictureAsPdf, "View as PDF") }
                IconButton(onClick = { g.platform.sharePdf(id) }) { Icon(Icons.Rounded.IosShare, "Share PDF") }
            }
        }
        if (r == null) return@Column
        val size = if (r.isMains) 1 else 5
        val itemCount = if (r.isMains) r.mains.size else r.questions.size
        val pageCount = (itemCount + size - 1) / size
        LazyColumn(
            Modifier.weight(1f).navigationBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("score") {
                val sweep = remember { Animatable(if (fresh) 0f else 1f) }
                var shown by remember { mutableIntStateOf(if (fresh) 0 else r.stars) }
                LaunchedEffect(Unit) {
                    if (fresh) {
                        sweep.animateTo(1f, tween(1100))
                        repeat(r.stars) { delay(170); shown = it + 1 }
                    }
                }
                val pct = r.percent / 100f
                CruxCard(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(contentAlignment = Alignment.Center) {
                            val track = MaterialTheme.colorScheme.surfaceContainerHighest
                            val ring = MaterialTheme.colorScheme.primary
                            Canvas(Modifier.size(150.dp)) {
                                val stroke = 16.dp.toPx()
                                val inset = Offset(stroke / 2, stroke / 2)
                                val arc = Size(this.size.width - stroke, this.size.height - stroke)
                                drawArc(track, -90f, 360f, false, inset, arc, style = Stroke(stroke, cap = StrokeCap.Round))
                                drawArc(ring, -90f, 360f * pct * sweep.value, false, inset, arc, style = Stroke(stroke, cap = StrokeCap.Round))
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(r.scoreText, style = if (r.isMains) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.displaySmall)
                                Text(if (r.isMains) "marks · ${r.percent}%" else "${r.percent}%", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Stars(shown, size = 34.dp)
                        if (celebrate) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                if (r.stars == 5) "Outstanding. That is topper-level work." else "Well done. That is a strong score.",
                                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center,
                            )
                        }
                        if (screen.note.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                screen.note, Modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(horizontal = 12.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(r.title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                        Text(
                            "${r.difficulty} · ${r.durationSec / 60} min ${r.durationSec % 60} s · ${Dates.dateTime(r.takenAt)}" + if (r.attempts > 1) " · attempt ${r.attempts}" else "",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                        )
                        if (r.isMains) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Marked strictly, as examiners do: 40 to 50% is a competent answer, 60% is topper level. An AI estimate to learn from, not an official score.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                            )
                        }
                        if (canPdf) {
                            Spacer(Modifier.height(14.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                FilledTonalButton(onClick = { nav.push(Screen.PdfPreview(id)) }) {
                                    Icon(Icons.Rounded.PictureAsPdf, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("View PDF")
                                }
                                OutlinedButton(onClick = { g.platform.sharePdf(id) }) {
                                    Icon(Icons.Rounded.IosShare, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Share")
                                }
                            }
                        }
                    }
                }
            }
            val start = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0)) * size
            if (r.isMains) {
                // One answer per page; the switcher sits above the answer as well as below it, since the feedback is long.
                if (pageCount > 1) item("pgTop") { PaginationBar(page.coerceIn(0, pageCount - 1), pageCount, onPage = { page = it }) }
                itemsIndexed(r.mains.drop(start).take(size), key = { i, _ -> "m" + (start + i) }) { offset, a -> MainsAnswerCard(start + offset, a) }
            } else itemsIndexed(r.questions.drop(start).take(size), key = { i, _ -> start + i }) { offset, q ->
                val i = start + offset
                val given = r.answers.getOrElse(i) { -1 }
                val right = given == q.answer
                val x = LocalExtra.current
                CruxCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(if (right) Icons.Rounded.CheckCircle else Icons.Rounded.Cancel, null, tint = if (right) x.onHigh else MaterialTheme.colorScheme.error)
                            Text("Question ${i + 1}", style = MaterialTheme.typography.labelLarge)
                            if (q.topic.isNotBlank()) Tag(q.topic, Modifier.weight(1f, fill = false))
                            ScoreTag(q.relevance)
                        }
                        Text(q.q, style = MaterialTheme.typography.titleSmall)
                        q.options.forEachIndexed { j, o ->
                            val bg = when { j == q.answer -> x.high; j == given -> MaterialTheme.colorScheme.errorContainer; else -> Color.Transparent }
                            val fg = when { j == q.answer -> x.onHigh; j == given -> MaterialTheme.colorScheme.onErrorContainer; else -> MaterialTheme.colorScheme.onSurface }
                            Text("${'A' + j})  $o", color = fg, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(bg).padding(horizontal = 10.dp, vertical = 6.dp))
                        }
                        if (given < 0) Text("Not answered", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                        if (q.explanation.isNotBlank()) Text(q.explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (q.sourceTitle.isNotBlank()) {
                            Text(
                                "Source: ${q.sourceTitle}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                                modifier = if (q.sourceLink.startsWith("http") && !q.sourceLink.contains("youtube.com")) Modifier.clickable { nav.push(Screen.Browser(q.sourceLink, "Source")) } else Modifier,
                            )
                        }
                    }
                }
            }
            item("pg") { PaginationBar(page.coerceIn(0, (pageCount - 1).coerceAtLeast(0)), pageCount, onPage = { page = it }) }
        }
    }
    if (celebrate) Confetti()
    }
}

// ------------------------------------------------------------------ pdf viewer

/** Shows the PDF pages, rendered just now in memory. Leaving the screen frees them. */
@Composable
fun PdfPreviewScreen(id: Long) {
    val g = LocalGraph.current
    val nav = LocalNav.current
    val pages by produceState<List<ImageBitmap>?>(null, id) {
        value = withContext(Dispatchers.Default) { g.db.tests().get(id)?.let { decode(g, it) }?.let { g.platform.pdfPages(it) }.orEmpty() }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest).statusBarsPadding()) {
        ScreenBar("PDF", onBack = { nav.pop() }) {
            IconButton(onClick = { g.platform.sharePdf(id) }) { Icon(Icons.Rounded.IosShare, "Share PDF") }
        }
        val list = pages
        if (list == null) {
            CruxLoader("Rendering the PDF", "Made fresh each time; nothing is saved", Modifier.padding(24.dp))
        } else {
            LazyColumn(
                Modifier.weight(1f).navigationBarsPadding(),
                contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(list) { i, bmp ->
                    Image(
                        bmp, "Page ${i + 1}", contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White),
                    )
                }
            }
        }
    }
}
