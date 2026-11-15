package app.dcrux.ui.tests

import app.dcrux.ui.OnBack
import app.dcrux.LocalGraph
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FactCheck
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.dcrux.ai.AiRepo
import app.dcrux.data.MainsAnswer
import app.dcrux.data.MainsMark
import app.dcrux.data.fmtMarks
import app.dcrux.mains.MainsStore
import app.dcrux.ui.LocalNav
import app.dcrux.ui.Screen
import app.dcrux.ui.ScreenBar
import app.dcrux.ui.components.CruxCard
import app.dcrux.ui.components.CruxLoader
import app.dcrux.ui.components.PaginationBar
import app.dcrux.ui.components.StopButton
import app.dcrux.ui.components.Tag
import app.dcrux.ui.components.appBackground
import app.dcrux.ui.theme.LocalExtra
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun clock(seconds: Int) = "%d:%02d".format(seconds / 60, seconds % 60)

// ------------------------------------------------------------------ writing and photographing

/**
 * A Mains attempt: read the question, write the answer on paper against the clock, then
 * photograph or upload the pages. Everything here is held by the MainsStore, so leaving the
 * screen (or the phone closing the app for the camera) loses nothing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainsRunScreen() {
    val g = LocalGraph.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val draftState by g.mains.draft.collectAsState()
    val progress by g.mains.progress.collectAsState()
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmEvaluate by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }

    // When the evaluation is saved, move on to the result.
    LaunchedEffect(progress?.doneId) {
        val p = progress ?: return@LaunchedEffect
        val id = p.doneId ?: return@LaunchedEffect
        g.mains.clearProgress()
        nav.replace(Screen.TestReview(id, fresh = true, shown = if (p.improved) null else p.result, note = retakeNote(p.improved, p.previous, p.result)))
    }

    val d = draftState
    if (d == null) {
        // Nothing in progress (already evaluated, or discarded elsewhere).
        LaunchedEffect(Unit) { if (progress?.doneId == null) nav.pop() }
        Box(Modifier.fillMaxSize().appBackground())
        return
    }

    val evaluating = progress?.running == true
    val pager = rememberPagerState(initialPage = d.current.coerceIn(0, d.questions.lastIndex)) { d.questions.size }
    val index = pager.currentPage
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(500) } }

    // Arriving on a question that has not been written yet starts its clock; leaving it stops the clock.
    LaunchedEffect(index) {
        g.mains.setCurrent(index)
        val cur = g.mains.draft.value ?: return@LaunchedEffect
        if (!cur.stopped[index] && cur.pages[index].isEmpty() && !evaluating) g.mains.startTimer(index) else if (cur.runningIndex != index) g.mains.stopTimer()
    }
    // Keep the screen on while a clock runs, so the time stays in view while writing.
    DisposableEffect(d.runningIndex >= 0) {
        g.platform.keepScreenOn(d.runningIndex >= 0)
        onDispose { g.platform.keepScreenOn(false) }
    }
    OnBack { if (evaluating) nav.pop() else confirmLeave = true }

    val camera = g.platform.rememberCamera { scope.launch { importing = true; g.mains.captureFinished(); importing = false } }
    val picker = g.platform.rememberPagePicker { picked ->
        if (picked.isNotEmpty()) scope.launch {
            importing = true
            val added = g.mains.importFiles(index, picked)
            importing = false
            message = if (added == 0) "Those files could not be read. Choose photos (JPG or PNG) or a PDF." else null
        }
    }

    val answered = d.pages.count { it.isNotEmpty() }
    Column(Modifier.fillMaxSize().appBackground().statusBarsPadding().navigationBarsPadding()) {
        ScreenBar(d.title, onBack = { if (evaluating) nav.pop() else confirmLeave = true })
        Text(
            "$answered of ${d.questions.size} answers photographed · ${d.difficulty}", Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (progress != null && progress?.doneId == null) {
            // Evaluating, or it failed and can be tried again.
            val p = progress!!
            Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                if (p.error != null) {
                    Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    Text("The evaluation did not finish", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(6.dp))
                    Text(p.error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Text("Your pages are safe. Answers already evaluated will not be redone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { g.mains.clearProgress(); g.mains.evaluate() }) { Text("Try again") }
                        OutlinedButton(onClick = { g.mains.clearProgress() }) { Text("Back to my pages") }
                    }
                } else {
                    CruxLoader(p.stage.ifBlank { "Evaluating" }, "${p.done} of ${p.total} answers evaluated")
                    StopButton({ g.mains.stopEvaluation() })
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Each answer is read word by word and then marked, which takes about a minute. You can leave this screen; the result will be waiting in Tests.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@Column
        }

        HorizontalPager(pager, Modifier.weight(1f), pageSpacing = 12.dp, contentPadding = PaddingValues(horizontal = 16.dp)) { i ->
            val q = d.questions[i]
            val seconds = d.elapsed(i, now)
            val target = AiRepo.targetSeconds(q.marks)
            val running = d.runningIndex == i
            val pages = d.pages[i]
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CruxCard(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Tag("Question ${i + 1}", bg = MaterialTheme.colorScheme.primaryContainer, fg = MaterialTheme.colorScheme.onPrimaryContainer)
                            Tag("${q.marks} marks · ${q.words} words")
                            if (q.paper.isNotBlank()) Tag(q.paper)
                            ScoreTag(q.relevance)
                        }
                        Text(q.q, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium))
                        if (q.topic.isNotBlank()) Text(q.topic, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (q.demand.isNotBlank()) Row {
                            Icon(Icons.Rounded.Lightbulb, null, Modifier.size(16.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(6.dp))
                            Text("What it asks: ${q.demand}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                // The clock.
                val over = seconds > target
                val x = LocalExtra.current
                val clockBg by animateColorAsState(if (over) x.mid else MaterialTheme.colorScheme.surfaceContainerLow, label = "clock")
                val clockInk = if (over) x.onMid else MaterialTheme.colorScheme.onSurface
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(clockBg).padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Timer, null, tint = clockInk)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(clock(seconds), style = MaterialTheme.typography.headlineMedium, color = clockInk)
                        Text(
                            if (over) "Over the exam's ${clock(target)} for ${q.marks} marks" else "Exam pace: ${clock(target)} for ${q.marks} marks",
                            style = MaterialTheme.typography.labelMedium, color = clockInk.copy(alpha = 0.8f),
                        )
                    }
                    if (seconds > 0 && !running) IconButton(onClick = { g.mains.resetTimer(i) }) { Icon(Icons.Rounded.Replay, "Reset the clock", tint = clockInk) }
                    FilledTonalButton(onClick = { if (running) g.mains.stopTimer(finished = true) else g.mains.startTimer(i) }) {
                        Icon(if (running) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (running) "Done writing" else if (seconds > 0) "Resume" else "Start")
                    }
                }

                // The pages.
                CruxCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Your answer pages · ${pages.size}/${MainsStore.MAX_PAGES}", style = MaterialTheme.typography.titleMedium)
                        if (pages.isEmpty()) Text(
                            "Write the answer on paper. When you finish, photograph each page in good light with the whole page in the frame, or upload a scan.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ) else Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            pages.forEachIndexed { n, name ->
                                Box {
                                    AsyncImage(
                                        g.mains.file(name), "Page ${n + 1}", contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(width = 92.dp, height = 124.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                    )
                                    Text(
                                        "${n + 1}", Modifier.align(Alignment.BottomStart).padding(6.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 7.dp, vertical = 2.dp),
                                        color = Color.White, style = MaterialTheme.typography.labelSmall,
                                    )
                                    Box(
                                        Modifier.align(Alignment.TopEnd).padding(4.dp).size(26.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).clickable { g.mains.removePage(i, name) },
                                        contentAlignment = Alignment.Center,
                                    ) { Icon(Icons.Rounded.Close, "Remove page ${n + 1}", Modifier.size(16.dp), tint = Color.White) }
                                }
                            }
                        }
                        if (importing && i == index) Text("Adding the page…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        message?.let { if (i == index) Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        val room = pages.size < MainsStore.MAX_PAGES
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            // A computer has no camera to hand the page to; pages come from files there.
                            if (camera != null) Button(enabled = room && !importing, modifier = Modifier.weight(1f), onClick = {
                                message = null
                                runCatching { camera(g.mains.newCapture(i)) }.onFailure { message = "The camera could not be opened. Use Upload instead." }
                            }) { Icon(Icons.Rounded.CameraAlt, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Camera") }
                            FilledTonalButton(enabled = room && !importing, modifier = Modifier.weight(1f), onClick = {
                                message = null
                                runCatching { picker() }.onFailure { message = "No file picker was found on this device." }
                            }) { Icon(Icons.Rounded.UploadFile, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (camera == null) "Choose files" else "Upload") }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        if (d.questions.size > 1) PaginationBar(
            pager.currentPage, d.questions.size,
            onPage = { p -> scope.launch { pager.animateScrollToPage(p.coerceIn(0, d.questions.lastIndex)) } }, modifier = Modifier.padding(vertical = 8.dp),
        )
        Button(
            onClick = { if (answered < d.questions.size) confirmEvaluate = true else g.mains.evaluate() }, enabled = answered > 0 && !importing,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 12.dp).height(54.dp),
        ) {
            Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
            Text(if (answered == 0) "Add your pages to evaluate" else if (answered == 1) "Evaluate my answer" else "Evaluate $answered answers")
        }
    }

    if (confirmEvaluate) AlertDialog(
        onDismissRequest = { confirmEvaluate = false },
        title = { Text("Evaluate now?") },
        text = { Text("${d.questions.size - answered} of ${d.questions.size} questions have no pages and will score zero, as they would in the exam.") },
        confirmButton = { Button(onClick = { confirmEvaluate = false; g.mains.evaluate() }) { Text("Evaluate") } },
        dismissButton = { TextButton(onClick = { confirmEvaluate = false }) { Text("Keep writing") } },
    )
    if (confirmLeave) AlertDialog(
        onDismissRequest = { confirmLeave = false },
        title = { Text("Finish before you leave?") },
        text = {
            Text(
                if (answered == d.questions.size) "Every answer is photographed. Evaluate them to complete today's target."
                else "You have ${d.questions.size - answered} of ${d.questions.size} answers still to write. Stay and complete today's target. If you must go, the attempt is saved and waits for you in Tests."
            )
        },
        confirmButton = { Button(onClick = { confirmLeave = false }) { Text("Keep going") } },
        dismissButton = { TextButton(onClick = { confirmLeave = false; g.mains.stopTimer(); nav.pop() }) { Text("Leave for now") } },
    )
}

// ------------------------------------------------------------------ one evaluated answer

@Composable
private fun Section(icon: ImageVector, title: String, tint: Color = MaterialTheme.colorScheme.primary, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(18.dp), tint = tint)
            Spacer(Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.titleSmall)
        }
        content()
    }
}

@Composable
private fun Points(items: List<String>, numbered: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        items.forEachIndexed { i, t ->
            Row {
                Text(if (numbered) "${i + 1}." else "•", Modifier.width(if (numbered) 22.dp else 14.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(t, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * The answer sheet as an examiner hands it back: page by page, each section of the student's own
 * writing with a tick, half-tick or cross beside it and a short remark for the margin.
 */
@Composable
private fun MarkedCopy(marks: List<MainsMark>, total: String, outOf: Int) {
    val x = LocalExtra.current
    val paper = if (x.dark) Color(0xFF24232B) else Color(0xFFFBF7EC)
    val ink = if (x.dark) Color(0xFFE6E1D6) else Color(0xFF2B2A33)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.FactCheck, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text("Your marked copy", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            val good = marks.count { it.mark == "good" }
            val wrong = marks.count { it.mark == "wrong" }
            Text("✓ $good  ·  ✗ $wrong  ·  ${marks.size} parts", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(paper).padding(vertical = 6.dp)) {
            var lastPage = 0
            marks.forEach { m ->
                if (m.page != lastPage) {
                    lastPage = m.page
                    Text(
                        "PAGE ${m.page}", Modifier.padding(start = 14.dp, top = 8.dp, bottom = 2.dp),
                        style = MaterialTheme.typography.labelSmall, color = ink.copy(alpha = 0.55f),
                    )
                }
                val (tint, icon) = when (m.mark) {
                    "good" -> (if (x.dark) Color(0xFF7AD9A2) else Color(0xFF1B7F4B)) to Icons.Rounded.Check
                    "wrong" -> MaterialTheme.colorScheme.error to Icons.Rounded.Close
                    "weak" -> ink.copy(alpha = 0.6f) to Icons.Rounded.Remove
                    else -> (if (x.dark) Color(0xFFFFC94D) else Color(0xFFA06A00)) to Icons.Rounded.Remove
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp)) {
                    // The examiner's stroke in the margin.
                    Box(Modifier.padding(top = 2.dp).size(24.dp).clip(CircleShape).background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                        Icon(icon, m.mark, Modifier.size(16.dp), tint = tint)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        if (m.section.isNotBlank()) Text(m.section, style = MaterialTheme.typography.labelMedium, color = ink.copy(alpha = 0.6f))
                        Text(m.text, style = MaterialTheme.typography.bodyMedium, color = ink)
                        Text(
                            m.remark, Modifier.padding(top = 3.dp).clip(RoundedCornerShape(8.dp)).background(tint.copy(alpha = 0.13f)).padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelLarge, color = tint,
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                Text("$total / $outOf", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** The mentor's feedback on one answer: marks by criterion, what to fix, and what the AI actually read. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainsAnswerCard(index: Int, a: MainsAnswer) {
    val nav = LocalNav.current
    val q = a.question
    val e = a.eval
    val x = LocalExtra.current
    var showRead by rememberSaveable(index) { mutableStateOf(false) }
    CruxCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Tag("Question ${index + 1}", bg = MaterialTheme.colorScheme.primaryContainer, fg = MaterialTheme.colorScheme.onPrimaryContainer)
                Tag("${q.marks} marks · ${q.words} words")
                if (q.paper.isNotBlank()) Tag(q.paper)
                ScoreTag(q.relevance)
            }
            Text(q.q, style = MaterialTheme.typography.titleSmall)

            if (e == null) {
                Text(a.note.ifBlank { "Not attempted" } + ". 0 of ${q.marks} marks.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                return@Column
            }

            // Marks, and the time taken against the exam's pace.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(fmtMarks(e.marks), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
                Text(" / ${q.marks}", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    if (a.seconds > 0) Text("Written in ${clock(a.seconds)}", style = MaterialTheme.typography.labelLarge)
                    if (e.wordCount > 0) Text("about ${e.wordCount} of ${q.words} words", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (!e.isAnswer) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.errorContainer).padding(14.dp)) {
                    Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("This could not be marked", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        Text(e.problem, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
                if (e.improvements.isNotEmpty()) Points(e.improvements)
            } else {
                if (e.verdict.isNotBlank()) Text(e.verdict, style = MaterialTheme.typography.bodyLarge)

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    e.breakdown.forEach { c ->
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row {
                                Text(c.name, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                                Text("${fmtMarks(c.score)} / ${fmtMarks(c.outOf)}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            }
                            LinearProgressIndicator(
                                progress = { if (c.outOf > 0) (c.score / c.outOf).coerceIn(0f, 1f) else 0f },
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape), drawStopIndicator = {},
                            )
                            if (c.comment.isNotBlank()) Text(c.comment, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                if (e.marked.isNotEmpty()) MarkedCopy(e.marked, fmtMarks(e.marks), q.marks)

                if (e.improvements.isNotEmpty()) Section(Icons.Rounded.TrendingUp, "Fix these first") { Points(e.improvements, numbered = true) }
                if (e.errors.isNotEmpty()) Section(Icons.Rounded.ErrorOutline, "Mistakes to correct", MaterialTheme.colorScheme.error) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        e.errors.forEach { err ->
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp)) {
                                Text("You wrote: “${err.written}”", style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic))
                                Text(err.issue, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                if (e.missing.isNotEmpty()) Section(Icons.Rounded.Lightbulb, "What a top answer adds") { Points(e.missing) }
                if (e.strengths.isNotEmpty()) Section(Icons.Rounded.CheckCircle, "What worked", x.onHigh) { Points(e.strengths) }
                if (e.structure.isNotBlank()) Section(Icons.Rounded.FormatListNumbered, "Structure") { Text(e.structure, style = MaterialTheme.typography.bodyMedium) }
                if (e.outline.isNotEmpty()) Section(Icons.Rounded.School, "How a topper would lay it out") { Points(e.outline, numbered = true) }

                Section(Icons.Rounded.Timer, "Time") {
                    val (verdict, ok) = MainsStore.timeVerdict(q.marks, a.seconds)
                    Text(
                        verdict, Modifier.clip(CircleShape).background(if (ok) x.high else x.mid).padding(horizontal = 12.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelLarge, color = if (ok) x.onHigh else x.onMid,
                    )
                    if (a.seconds > 0) Text(
                        "You took ${clock(a.seconds)}. The exam gives about ${clock(AiRepo.targetSeconds(q.marks))} for ${q.marks} marks (250 marks in 3 hours, the UPSC pace).",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (e.timeAdvice.isNotBlank()) Text(e.timeAdvice, style = MaterialTheme.typography.bodyMedium)
                }
                if (e.examTips.isNotEmpty()) Section(Icons.Rounded.AutoAwesome, "In the exam hall") { Points(e.examTips) }
            }

            // What was read from the pages, so a misreading is never hidden.
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable { showRead = !showRead }.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Visibility, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("What Crux AI read from your pages", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${a.pages} ${if (a.pages == 1) "page" else "pages"} · ${e.legibility}% read with confidence",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.Rounded.ExpandMore, if (showRead) "Hide" else "Show", Modifier.rotate(if (showRead) 180f else 0f))
                }
                if (showRead) {
                    Text(e.transcript.ifBlank { "Nothing could be read." }, style = MaterialTheme.typography.bodySmall)
                    Text(
                        "The marks rest on this reading. If it does not match what you wrote, retake the photos in better light and evaluate again with Retake.",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (e.legibility in 1..69 && e.isAnswer) Text(
                "A good part of the handwriting could not be read with confidence, so some points may have been missed. Clearer photos or neater writing will give a fairer mark; examiners face the same difficulty.",
                style = MaterialTheme.typography.bodySmall, color = x.onMid,
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(x.mid).padding(12.dp),
            )
            if (q.sourceTitle.isNotBlank()) Text(
                "Set from: ${q.sourceTitle}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                modifier = if (q.sourceLink.startsWith("http")) Modifier.clickable { nav.push(Screen.Browser(q.sourceLink, "Source")) } else Modifier,
            )
        }
    }
}
