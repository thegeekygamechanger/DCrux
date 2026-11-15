package app.dcrux.mains

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import app.dcrux.Graph
import app.dcrux.ai.AiRepo
import app.dcrux.ai.ClaudeException
import app.dcrux.data.AppJson
import app.dcrux.data.MainsAnswer
import app.dcrux.data.MainsDraft
import app.dcrux.data.MainsEval
import app.dcrux.data.MainsQuestion
import app.dcrux.data.TestRecord
import app.dcrux.data.TestResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/** Where an evaluation has got to. [doneId] is set once the result is saved. */
data class EvalProgress(
    val done: Int, val total: Int, val stage: String, val error: String? = null,
    val doneId: Long? = null, val result: TestResult? = null, val improved: Boolean = true, val previous: String = "",
) {
    val running: Boolean get() = error == null && doneId == null
}

/**
 * One Mains attempt in progress: its questions, timers and page photos.
 *
 * It lives outside the screen and is written to the app's private storage (encrypted) on every
 * change, because many phones close the app to make room for the camera. Page photos are kept
 * only until the answers are evaluated, then deleted; what remains is the encrypted result.
 */
class MainsStore(private val g: Graph) {
    val draft = MutableStateFlow<MainsDraft?>(null)
    val progress = MutableStateFlow<EvalProgress?>(null)
    val loaded = MutableStateFlow(false)

    /** True when the app was closed while the camera was open and the photo has just been recovered: go straight back to the attempt. */
    val resumeNow = MutableStateFlow(false)

    private val dir: File get() = File(g.app.filesDir, "mains").apply { mkdirs() }
    private val draftFile: File get() = File(g.app.filesDir, "mains_draft")
    private val disk = Mutex()
    private var job: Job? = null

    /** Evaluations already finished in this attempt, so "Try again" after a failure does not redo them. */
    private val finished = ConcurrentHashMap<Int, MainsEval>()

    init {
        g.scope.launch(Dispatchers.IO) {
            val saved = runCatching {
                draftFile.takeIf { it.exists() }?.readText()?.let { g.secure.decrypt(it) }?.let { AppJson.decodeFromString<MainsDraft>(it) }
            }.getOrNull()
            if (saved != null) {
                // Drop pages whose files are gone, and pick up a photo taken while the app was closed.
                var d = saved.copy(pages = saved.pages.map { list -> list.filter { File(dir, it).length() > 0 } })
                if (d.pendingCapture.isNotBlank() && d.pendingFor in d.questions.indices) {
                    val page = adoptCapture(File(dir, d.pendingCapture))
                    if (page != null) d = settled(d).copy(
                        pages = d.pages.mapIndexed { i, l -> if (i == d.pendingFor && l.size < MAX_PAGES) l + page else l },
                        stopped = d.stopped.mapIndexed { i, v -> v || i == d.pendingFor }, current = d.pendingFor,
                    )
                    d = d.copy(pendingCapture = "", pendingFor = -1)
                    resumeNow.value = true
                }
                draft.value = d
                persist()
            } else {
                runCatching { dir.listFiles()?.forEach { it.delete() } }
            }
            loaded.value = true
        }
    }

    private fun persist() {
        val snapshot = draft.value
        g.scope.launch(Dispatchers.IO) {
            disk.withLock {
                runCatching {
                    if (snapshot == null) draftFile.delete()
                    else draftFile.writeText(g.secure.encrypt(AppJson.encodeToString(snapshot)))
                }
            }
        }
    }

    private fun change(save: Boolean = true, block: (MainsDraft) -> MainsDraft) {
        draft.update { it?.let(block) }
        if (save) persist()
    }

    fun start(title: String, exam: String, difficulty: String, dayKey: String, questions: List<MainsQuestion>, retakeOf: Long = 0) {
        runCatching { dir.listFiles()?.forEach { it.delete() } }
        finished.clear()
        progress.value = null
        draft.value = MainsDraft(
            title = title, exam = exam, difficulty = difficulty, dayKey = dayKey, questions = questions,
            seconds = List(questions.size) { 0 }, pages = List(questions.size) { emptyList() }, stopped = List(questions.size) { false },
            startedAt = System.currentTimeMillis(), retakeOf = retakeOf,
        )
        persist()
    }

    fun discard() {
        job?.cancel()
        finished.clear()
        progress.value = null
        draft.value = null
        runCatching { dir.listFiles()?.forEach { it.delete() } }
        persist()
    }

    fun setCurrent(index: Int) = change { it.copy(current = index) }

    /** Adds the running clock's time to its question and stops it. */
    private fun settled(d: MainsDraft): MainsDraft {
        if (d.runningIndex !in d.seconds.indices || d.runningSince <= 0) return d.copy(runningIndex = -1, runningSince = 0)
        val total = d.elapsed(d.runningIndex)
        return d.copy(seconds = d.seconds.mapIndexed { i, s -> if (i == d.runningIndex) total else s }, runningIndex = -1, runningSince = 0)
    }

    /** Starts the clock of one question (stopping any other). Clocks run on wall time, so they keep going with the screen off. */
    fun startTimer(index: Int) {
        val d = draft.value ?: return
        if (d.runningIndex == index || index !in d.questions.indices) return
        change { settled(it).copy(runningIndex = index, runningSince = System.currentTimeMillis(), stopped = it.stopped.mapIndexed { i, s -> if (i == index) false else s }) }
    }

    /** Stops the running clock. [finished] marks that question as written, so its clock does not restart by itself. */
    fun stopTimer(finished: Boolean = false) {
        val d = draft.value ?: return
        if (d.runningIndex < 0 && !finished) return
        val index = d.runningIndex
        change { cur -> settled(cur).let { s -> if (finished && index >= 0) s.copy(stopped = s.stopped.mapIndexed { i, v -> v || i == index }) else s } }
    }

    fun resetTimer(index: Int) = change { settled(it).copy(seconds = it.seconds.mapIndexed { i, s -> if (i == index) 0 else s }, stopped = it.stopped.mapIndexed { i, s -> if (i == index) false else s }) }

    fun file(name: String) = File(dir, name)

    fun removePage(index: Int, name: String) {
        runCatching { File(dir, name).delete() }
        finished.remove(index)
        change { it.copy(pages = it.pages.mapIndexed { i, l -> if (i == index) l - name else l }) }
    }

    /** Where the camera should write the next photo. Remembered on disk in case the app is closed meanwhile. */
    fun newCapture(index: Int): Uri {
        val name = "cap_${System.currentTimeMillis()}.jpg"
        val f = File(dir, name).apply { createNewFile() }
        change { it.copy(pendingCapture = name, pendingFor = index) }
        return FileProvider.getUriForFile(g.app, "${g.app.packageName}.capture", f)
    }

    suspend fun captureFinished() = withContext(Dispatchers.IO) {
        val d = draft.value ?: return@withContext
        if (d.pendingCapture.isBlank()) return@withContext
        val page = adoptCapture(File(dir, d.pendingCapture))
        val index = d.pendingFor
        finished.remove(index)
        change { c ->
            val cur = if (page != null) settled(c) else c
            cur.copy(
                pendingCapture = "", pendingFor = -1,
                pages = if (page == null) cur.pages else cur.pages.mapIndexed { i, l -> if (i == index && l.size < MAX_PAGES) l + page else l },
                stopped = if (page == null) cur.stopped else cur.stopped.mapIndexed { i, s -> s || i == index },
            )
        }
    }

    /** Turns a full-size camera file into a page of sensible size. Null when the camera wrote nothing. */
    private fun adoptCapture(raw: File): String? {
        if (!raw.exists()) return null
        val name = if (raw.length() > 0) savePage(shrink { raw.inputStream() }) else null
        raw.delete()
        return name
    }

    /** Adds pictures or PDFs chosen from the phone. Returns how many pages were added. */
    suspend fun importFiles(index: Int, uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        val d = draft.value ?: return@withContext 0
        var room = MAX_PAGES - d.pages[index].size
        val added = mutableListOf<String>()
        for (uri in uris) {
            if (room <= 0) break
            val type = g.app.contentResolver.getType(uri).orEmpty()
            val names = if (type == "application/pdf" || uri.toString().lowercase().endsWith(".pdf")) pdfPages(uri, room)
            else listOfNotNull(savePage(shrink { g.app.contentResolver.openInputStream(uri) }))
            added += names
            room -= names.size
        }
        if (added.isNotEmpty()) {
            finished.remove(index)
            change { c -> val cur = settled(c); cur.copy(
                pages = cur.pages.mapIndexed { i, l -> if (i == index) l + added else l },
                stopped = cur.stopped.mapIndexed { i, s -> s || i == index },
            ) }
        }
        added.size
    }

    private fun pdfPages(uri: Uri, limit: Int): List<String> {
        // PdfRenderer needs a seekable file, which not every document provider gives; copy it first.
        val tmp = File(dir, "import_${System.nanoTime()}.pdf")
        return try {
            g.app.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } } ?: return emptyList()
            ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { pdf ->
                    (0 until minOf(pdf.pageCount, limit)).mapNotNull { i ->
                        pdf.openPage(i).use { page ->
                            val scale = MAX_EDGE.toFloat() / maxOf(page.width, page.height)
                            val bmp = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                            Canvas(bmp).drawColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            savePage(bmp)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            emptyList()
        } finally {
            tmp.delete()
        }
    }

    /** Decodes a picture no larger than needed, upright, with its long edge at [MAX_EDGE] or less. */
    private fun shrink(open: () -> InputStream?): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        val bmp = open()?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return@runCatching null
        val turn = runCatching {
            open()?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            }
        }.getOrNull() ?: 0f
        val scale = minOf(1f, MAX_EDGE.toFloat() / maxOf(bmp.width, bmp.height))
        if (turn == 0f && scale == 1f) bmp
        else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postScale(scale, scale); postRotate(turn) }, true)
    }.getOrNull()

    private fun savePage(bmp: Bitmap?): String? {
        bmp ?: return null
        val name = "p_${System.nanoTime()}.jpg"
        return runCatching {
            File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            bmp.recycle()
            name
        }.getOrNull()
    }

    fun clearProgress() { progress.value = null }

    /** Evaluates every answered question and saves the result. Keeps going if the screen is left. */
    fun evaluate() {
        if (progress.value?.running == true) return
        stopTimer()
        val d = draft.value ?: return
        val answered = d.questions.indices.filter { d.pages[it].isNotEmpty() }
        if (answered.isEmpty()) return
        progress.value = EvalProgress(finished.keys.count { it in answered }, answered.size, "Reading your handwriting")
        job = g.scope.launch {
            try {
                val gate = Semaphore(2)
                coroutineScope {
                    answered.filter { !finished.containsKey(it) }.map { i ->
                        async {
                            gate.withPermit {
                                val pages = withContext(Dispatchers.IO) { d.pages[i].map { File(dir, it).readBytes() } }
                                val eval = g.ai.evaluateMains(d.questions[i], d.exam, pages, d.seconds[i]) { stage ->
                                    progress.update { p -> p?.copy(stage = stage) }
                                }
                                finished[i] = eval
                                progress.update { p -> p?.copy(done = finished.keys.count { it in answered }, stage = "Marking against the question") }
                            }
                        }
                    }.awaitAll()
                }
                val answers = d.questions.mapIndexed { i, q ->
                    MainsAnswer(q, d.seconds[i], d.pages[i].size, finished[i], if (finished[i] == null) "Not attempted" else "")
                }
                val marks = answers.sumOf { (it.eval?.marks ?: 0f).toDouble() }.toFloat()
                val max = d.questions.sumOf { it.marks }.toFloat()
                var result = TestResult(
                    title = d.title, exam = d.exam, difficulty = d.difficulty, dayKey = d.dayKey, questions = emptyList(), answers = emptyList(),
                    correct = 0, total = d.questions.size, stars = mainsStars(marks, max), durationSec = d.seconds.sum(),
                    takenAt = System.currentTimeMillis(), model = answers.firstNotNullOfOrNull { it.eval?.model }.orEmpty(),
                    kind = "mains", mains = answers, marks = marks, maxMarks = max,
                )
                val (id, improved, previous) = save(g, result, d.retakeOf)
                if (d.retakeOf != 0L) result = result.copy(attempts = (g.db.tests().get(id)?.let { decode(g, it) }?.attempts ?: 1))
                withContext(Dispatchers.IO) { runCatching { dir.listFiles()?.forEach { it.delete() } } }
                finished.clear()
                draft.value = null
                persist()
                progress.value = EvalProgress(answered.size, answered.size, "", doneId = id, result = result, improved = improved, previous = previous)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val why = (e as? ClaudeException)?.friendly ?: e.message ?: "The evaluation could not be finished"
                progress.update { p -> (p ?: EvalProgress(0, answered.size, "")).copy(error = why) }
            }
        }
    }

    companion object {
        const val MAX_PAGES = 6
        const val MAX_EDGE = 1600

        fun decode(g: Graph, record: TestRecord): TestResult? =
            g.secure.decrypt(record.payload)?.let { runCatching { AppJson.decodeFromString<TestResult>(it) }.getOrNull() }

        /** Mains is marked hard: 60% is a topper's score, so the stars sit lower than for Prelims. */
        fun mainsStars(marks: Float, max: Float): Int {
            if (max <= 0f || marks <= 0f) return 0
            val pct = marks * 100 / max
            return when {
                pct >= 60 -> 5
                pct >= 50 -> 4
                pct >= 40 -> 3
                pct >= 30 -> 2
                else -> 1
            }
        }

        /**
         * Stores a result. A retake keeps one entry in the history: the better of the two scores stays,
         * and the number of attempts goes up. Returns the entry's id, whether the score improved, and
         * the earlier score as text.
         */
        suspend fun save(g: Graph, result: TestResult, retakeOf: Long): Triple<Long, Boolean, String> = withContext(Dispatchers.Default) {
            val old = if (retakeOf != 0L) g.db.tests().get(retakeOf)?.let { decode(g, it) } else null
            if (old == null) {
                val id = g.db.tests().insert(TestRecord(createdAt = result.takenAt, payload = g.secure.encrypt(AppJson.encodeToString(result))))
                return@withContext Triple(id, true, "")
            }
            val attempts = old.attempts + 1
            val better = if (result.isMains) result.marks > old.marks else result.correct > old.correct
            val keep = if (better) result.copy(attempts = attempts) else old.copy(attempts = attempts)
            g.db.tests().setPayload(retakeOf, g.secure.encrypt(AppJson.encodeToString(keep)))
            Triple(retakeOf, better, old.scoreText)
        }

        /** Seconds a question of [marks] gets at the exam's pace, and a plain verdict on the time taken. */
        fun timeVerdict(marks: Int, seconds: Int): Pair<String, Boolean> {
            val target = AiRepo.targetSeconds(marks)
            if (seconds <= 0) return "Time not recorded" to true
            val diff = seconds - target
            return when {
                // Far inside the time usually means a thin answer, not a fast one.
                seconds < target / 2 -> "Very quick: ${-diff / 60} min ${-diff % 60} s unused. Spend it on depth" to true
                diff <= 0 -> "On pace: ${-diff / 60} min ${-diff % 60} s inside the exam's time" to true
                diff <= target / 4 -> "A little slow: ${diff / 60} min ${diff % 60} s over the exam's time" to false
                else -> {
                    val doable = 180 * 60 / seconds.coerceAtLeast(1)
                    "Too slow: ${diff / 60} min ${diff % 60} s over. At this speed about $doable such answers fit in 3 hours" to false
                }
            }
        }
    }
}
