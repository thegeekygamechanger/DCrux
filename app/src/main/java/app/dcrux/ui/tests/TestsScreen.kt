package app.dcrux.ui.tests

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.dcrux.Graph
import app.dcrux.ai.ClaudeException
import app.dcrux.data.AppJson
import app.dcrux.data.Dates
import app.dcrux.data.Settings
import app.dcrux.data.TestRecord
import app.dcrux.data.TestResult
import app.dcrux.graph
import app.dcrux.pdf.PdfShareProvider
import app.dcrux.pdf.ResultPdf
import app.dcrux.ui.LocalNav
import app.dcrux.ui.Screen
import app.dcrux.ui.ScreenBar
import app.dcrux.ui.components.CruxCard
import app.dcrux.ui.components.CruxLoader
import app.dcrux.ui.components.EmptyState
import app.dcrux.ui.components.PaginationBar
import app.dcrux.ui.components.Stars
import app.dcrux.ui.components.Tag
import app.dcrux.ui.components.shortExam
import app.dcrux.ui.theme.BrandColors
import app.dcrux.ui.theme.LocalExtra
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun decode(g: Graph, record: TestRecord): TestResult? =
    g.secure.decrypt(record.payload)?.let { runCatching { AppJson.decodeFromString<TestResult>(it) }.getOrNull() }

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

@Composable
fun TestsTab(settings: Settings) {
    val g = LocalContext.current.graph
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val records by g.db.tests().observeAll().collectAsState(initial = emptyList())
    val results by produceState(emptyList<Pair<Long, TestResult>>(), records) {
        value = withContext(Dispatchers.Default) { records.mapNotNull { r -> decode(g, r)?.let { r.id to it } } }
    }
    val exams = remember(settings.exams) { settings.exams.toList().sortedByDescending { it == "UPSC CSE" } }
    var exam by rememberSaveable { mutableStateOf(exams.first()) }
    if (exam !in exams) exam = exams.first()
    val days by produceState(listOf(Dates.dayKey()), settings.lastSync) { value = g.db.news().recentDays().take(7).ifEmpty { listOf(Dates.dayKey()) } }
    var day by rememberSaveable { mutableStateOf(Dates.dayKey()) }
    if (day !in days) day = days.first()
    var difficulty by rememberSaveable { mutableStateOf("Moderate") }
    var count by rememberSaveable { mutableIntStateOf(10) }
    var busy by remember { mutableStateOf(false) }
    var optionsOpen by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    val hasKey = remember { g.secure.hasApiKey() || g.hasBuiltInKey }
    val size = 5

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item("head") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Tests", style = MaterialTheme.typography.headlineMedium)
                }
                IconButton(onClick = { nav.push(Screen.Search) }) { Icon(Icons.Rounded.Search, "Search") }
            }
        }

        item("stats") {
            val stars = results.sumOf { it.second.stars }
            val avg = if (results.isEmpty()) 0 else results.sumOf { it.second.correct * 100 / it.second.total.coerceAtLeast(1) } / results.size
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(Brush.linearGradient(BrandColors)).padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("$stars", color = Color.White, style = MaterialTheme.typography.displayMedium)
                        Text("stars earned", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.labelLarge)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${results.size} tests", color = Color.White, style = MaterialTheme.typography.titleMedium)
                        Text("$avg% average", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Lock, null, Modifier.size(14.dp), tint = Color.White)
                            Spacer(Modifier.width(4.dp))
                            Text("Encrypted on this phone", color = Color.White, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        item("builder") {
            CruxCard(color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(18.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (busy) {
                        CruxLoader("Writing your test", "$count $difficulty questions for ${shortExam(exam)}")
                        return@Column
                    }
                    Text("New test", style = MaterialTheme.typography.titleLarge)
                    val turn by animateFloatAsState(if (optionsOpen) 180f else 0f, label = "options")
                    Row(
                        Modifier.fillMaxWidth().clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest).clickable { optionsOpen = !optionsOpen }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${shortExam(exam)} · ${Dates.prettyDay(day)} · $difficulty · $count questions",
                            Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1,
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
                                days.forEach { d -> FilterChip(selected = d == day, onClick = { day = d }, label = { Text(Dates.prettyDay(d)) }, shape = CircleShape) }
                            }
                            Text("Difficulty", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                listOf("Easy", "Moderate", "Hard").forEachIndexed { i, d ->
                                    SegmentedButton(selected = difficulty == d, onClick = { difficulty = d }, shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(d) }
                                }
                            }
                            Text("Questions", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                listOf(5, 10, 15, 20).forEachIndexed { i, n ->
                                    SegmentedButton(selected = count == n, onClick = { count = n }, shape = SegmentedButtonDefaults.itemShape(i, 4)) { Text("$n") }
                                }
                            }
                        }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                    Button(
                        enabled = hasKey, modifier = Modifier.fillMaxWidth().height(54.dp),
                        onClick = {
                            busy = true; error = null
                            scope.launch {
                                try {
                                    val qs = g.ai.generateQuiz(day, exam, difficulty, count)
                                    nav.push(Screen.TestRun("${shortExam(exam)} · ${Dates.prettyDay(day)}", exam, difficulty, day, qs))
                                } catch (e: ClaudeException) {
                                    error = e.friendly
                                } catch (e: Exception) {
                                    error = e.message ?: "Could not build the test"
                                } finally {
                                    busy = false
                                }
                            }
                        },
                    ) {
                        Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                        Text(if (hasKey) "Generate test" else "Needs an AI key")
                    }
                }
            }
        }

        item("hh") { Text("History", style = MaterialTheme.typography.titleLarge) }
        if (results.isEmpty()) {
            item("none") { EmptyState("No tests yet", "Your results will be kept here, encrypted, until you reset the app.") }
        } else {
            val pageCount = (results.size + size - 1) / size
            val shown = results.drop(page.coerceIn(0, pageCount - 1) * size).take(size)
            items(shown, key = { it.first }) { (id, r) ->
                CruxCard(Modifier.fillMaxWidth(), onClick = { nav.push(Screen.TestReview(id)) }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.title, style = MaterialTheme.typography.titleMedium)
                            Text("${r.difficulty} · ${Dates.dateTime(r.takenAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(6.dp))
                            Stars(r.stars)
                        }
                        Text("${r.correct}/${r.total}", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            item("pg") { PaginationBar(page.coerceIn(0, pageCount - 1), pageCount, onPage = { page = it }) }
        }
    }
}

// ------------------------------------------------------------------ taking a test

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TestRunScreen(screen: Screen.TestRun) {
    val g = LocalContext.current.graph
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
    BackHandler { confirmLeave = true }

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
            val id = withContext(Dispatchers.Default) {
                g.db.tests().insert(TestRecord(createdAt = result.takenAt, payload = g.secure.encrypt(AppJson.encodeToString(result))))
            }
            nav.replace(Screen.TestReview(id, fresh = true))
        }
    }

    val answered = answers.count { it >= 0 }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding().navigationBarsPadding()) {
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
                            if (q.topic.isNotBlank()) Tag(q.topic)
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
        title = { Text("Leave this test?") },
        text = { Text("Your answers so far will be lost.") },
        confirmButton = { Button(onClick = { confirmLeave = false; nav.pop() }) { Text("Leave") } },
        dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Stay") } },
    )
}

// ------------------------------------------------------------------ result

@Composable
fun TestReviewScreen(id: Long, fresh: Boolean) {
    val context = LocalContext.current
    val g = context.graph
    val nav = LocalNav.current
    val result by produceState<TestResult?>(null, id) {
        value = withContext(Dispatchers.Default) { g.db.tests().get(id)?.let { decode(g, it) } }
    }
    var page by rememberSaveable { mutableIntStateOf(0) }
    val r = result
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
        ScreenBar("Result", onBack = { nav.pop() }) {
            IconButton(onClick = { nav.push(Screen.PdfPreview(id)) }) { Icon(Icons.Rounded.PictureAsPdf, "View as PDF") }
            IconButton(onClick = { PdfShareProvider.share(context, id) }) { Icon(Icons.Rounded.IosShare, "Share PDF") }
        }
        if (r == null) return@Column
        val size = 5
        val pageCount = (r.questions.size + size - 1) / size
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
                val pct = if (r.total > 0) r.correct / r.total.toFloat() else 0f
                CruxCard(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(contentAlignment = Alignment.Center) {
                            val track = MaterialTheme.colorScheme.surfaceContainerHighest
                            Canvas(Modifier.size(150.dp)) {
                                val stroke = 16.dp.toPx()
                                val inset = Offset(stroke / 2, stroke / 2)
                                val arc = Size(this.size.width - stroke, this.size.height - stroke)
                                drawArc(track, -90f, 360f, false, inset, arc, style = Stroke(stroke, cap = StrokeCap.Round))
                                drawArc(Brush.sweepGradient(BrandColors + BrandColors.first()), -90f, 360f * pct * sweep.value, false, inset, arc, style = Stroke(stroke, cap = StrokeCap.Round))
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${r.correct}/${r.total}", style = MaterialTheme.typography.displaySmall)
                                Text("${(pct * 100).toInt()}%", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Stars(shown, size = 34.dp)
                        Spacer(Modifier.height(10.dp))
                        Text(r.title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                        Text(
                            "${r.difficulty} · ${r.durationSec / 60} min ${r.durationSec % 60} s · ${Dates.dateTime(r.takenAt)}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FilledTonalButton(onClick = { nav.push(Screen.PdfPreview(id)) }) {
                                Icon(Icons.Rounded.PictureAsPdf, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("View PDF")
                            }
                            OutlinedButton(onClick = { PdfShareProvider.share(context, id) }) {
                                Icon(Icons.Rounded.IosShare, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Share")
                            }
                        }
                    }
                }
            }
            val start = page.coerceIn(0, pageCount - 1) * size
            itemsIndexed(r.questions.drop(start).take(size), key = { i, _ -> start + i }) { offset, q ->
                val i = start + offset
                val given = r.answers.getOrElse(i) { -1 }
                val right = given == q.answer
                val x = LocalExtra.current
                CruxCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(if (right) Icons.Rounded.CheckCircle else Icons.Rounded.Cancel, null, tint = if (right) x.onHigh else MaterialTheme.colorScheme.error)
                            Text("Question ${i + 1}", style = MaterialTheme.typography.labelLarge)
                            if (q.topic.isNotBlank()) Tag(q.topic)
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
            item("pg") { PaginationBar(page.coerceIn(0, pageCount - 1), pageCount, onPage = { page = it }) }
        }
    }
}

// ------------------------------------------------------------------ pdf viewer

/** Shows the PDF pages, rendered just now in memory. Leaving the screen frees them. */
@Composable
fun PdfPreviewScreen(id: Long) {
    val context = LocalContext.current
    val g = context.graph
    val nav = LocalNav.current
    val pages by produceState<List<android.graphics.Bitmap>?>(null, id) {
        value = withContext(Dispatchers.Default) {
            g.db.tests().get(id)?.let { decode(g, it) }?.let { ResultPdf.bitmaps(context.applicationContext, it) }.orEmpty()
        }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest).statusBarsPadding()) {
        ScreenBar("PDF", onBack = { nav.pop() }) {
            IconButton(onClick = { PdfShareProvider.share(context, id) }) { Icon(Icons.Rounded.IosShare, "Share PDF") }
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
                        bmp.asImageBitmap(), "Page ${i + 1}", contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White),
                    )
                }
            }
        }
    }
}
