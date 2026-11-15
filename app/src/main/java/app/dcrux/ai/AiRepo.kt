package app.dcrux.ai

import app.dcrux.Graph
import app.dcrux.data.AppJson
import app.dcrux.data.Ask
import app.dcrux.data.Dates
import app.dcrux.data.ExamRelevance
import app.dcrux.data.MainsCriterion
import app.dcrux.data.MainsError
import app.dcrux.data.MainsEval
import app.dcrux.data.MainsMark
import app.dcrux.data.MainsQuestion
import app.dcrux.data.ModelInfo
import app.dcrux.data.NewsItem
import app.dcrux.data.QuizQuestion
import app.dcrux.data.Video
import app.dcrux.net.Sources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.ConcurrentHashMap

/** Live status of one AI task, shown by the animated loading states. */
data class AiJob(val stage: String, val chars: Int = 0, val error: String? = null, val running: Boolean = true)

class AiRepo(private val g: Graph) {
    val jobs = MutableStateFlow<Map<String, AiJob>>(emptyMap())

    /** Captions captured from the player for this session only; never written to disk. */
    val transcripts = ConcurrentHashMap<String, String>()

    private val running = ConcurrentHashMap<String, Job>()

    private fun setJob(key: String, job: AiJob?) = jobs.update { if (job == null) it - key else it + (key to job) }

    fun clearJob(key: String) = setJob(key, null)

    fun cancel(key: String) {
        running.remove(key)?.cancel()
        setJob(key, null)
    }

    // ---------------------------------------------------------------- model choice

    private val switchable = setOf(
        ClaudeException.Kind.NOT_FOUND, ClaudeException.Kind.PERMISSION, ClaudeException.Kind.RATE_LIMIT,
        ClaudeException.Kind.OVERLOADED, ClaudeException.Kind.SERVER,
    )

    suspend fun refreshModels(apiKey: String? = null): List<ModelInfo> {
        val key = apiKey ?: g.apiKey() ?: throw ClaudeException(ClaudeException.Kind.NO_KEY, "no key")
        val models = g.claude.listModels(key).sortedBy { m -> AUTO_ORDER.indexOf(m.id).let { if (it < 0) 99 else it } }
        g.prefs.setModels(models)
        return models
    }

    /**
     * Auto: start with the default model and move down the list when one is unavailable,
     * rate-limited or overloaded. Manual: only the model picked in Settings.
     */
    private suspend fun <T> withModel(block: suspend (String, ModelInfo) -> T): T {
        g.platform.aiWorkBegin()
        try {
            var attempt = 0
            while (true) {
                try {
                    return withModelOnce(block)
                } catch (e: ClaudeException) {
                    // A dropped connection (app switched, network blip) is worth trying again.
                    if (e.kind != ClaudeException.Kind.NETWORK || attempt >= 2) throw e
                    attempt++
                    delay(1500L * attempt)
                }
            }
        } finally {
            g.platform.aiWorkEnd()
        }
    }

    private suspend fun <T> withModelOnce(block: suspend (String, ModelInfo) -> T): T {
        val key = g.apiKey() ?: throw ClaudeException(ClaudeException.Kind.NO_KEY, "no key")
        val s = g.prefs.get()
        val models = s.models.ifEmpty { refreshModels(key) }
        if (models.isEmpty()) throw ClaudeException(ClaudeException.Kind.NOT_FOUND, "No models available")
        val chain = if (s.autoModel) {
            AUTO_ORDER.mapNotNull { id -> models.firstOrNull { it.id == id } }.ifEmpty { models.take(2) }
        } else {
            listOf(models.firstOrNull { it.id == s.modelId } ?: models.first())
        }
        var last: ClaudeException? = null
        for ((i, m) in chain.withIndex()) {
            try {
                return block(key, m)
            } catch (e: ClaudeException) {
                last = e
                if (e.kind !in switchable) throw e
                if (i == chain.lastIndex && (e.kind == ClaudeException.Kind.RATE_LIMIT || e.kind == ClaudeException.Kind.OVERLOADED)) {
                    delay((e.retryAfterSec.coerceIn(2, 20)) * 1000L)
                    return block(key, m)
                }
            }
        }
        throw last ?: ClaudeException(ClaudeException.Kind.SERVER, "failed")
    }

    private fun schema(raw: String): JsonObject = AppJson.parseToJsonElement(raw).jsonObject

    private inline fun <reified T> decode(text: String): T {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) throw ClaudeException(ClaudeException.Kind.PARSE, "no json")
        return try {
            AppJson.decodeFromString<T>(text.substring(start, end + 1))
        } catch (e: Exception) {
            throw ClaudeException(ClaudeException.Kind.PARSE, e.message ?: "parse")
        }
    }

    private fun describe(e: Throwable): String = when (e) {
        is ClaudeException -> e.friendly
        else -> e.message ?: "Something went wrong"
    }

    // ---------------------------------------------------------------- video summaries

    @Serializable
    private data class SummaryOut(val markdown: String, val relevance: List<ExamRelevance> = emptyList())

    fun videoKey(videoId: String, detailed: Boolean) = "video:$videoId:" + if (detailed) "detailed" else "summary"

    /** Starts (or keeps) a summary job. Survives leaving the screen; the result lands in the database. */
    fun summarize(video: Video, detailed: Boolean, transcript: String?) {
        val key = videoKey(video.id, detailed)
        if (running[key]?.isActive == true) return
        val captions = transcript?.takeIf { it.length > 200 } ?: transcripts[video.id]
        setJob(key, AiJob(if (captions != null) "Reading the captions" else "Reading the description"))
        running[key] = g.scope.launch {
            try {
                val s = g.prefs.get()
                val exams = s.exams.toList()
                val channel = g.db.channels().get(video.channelId)?.title.orEmpty()
                val basis = if (captions != null) "captions" else "description"
                val material = captions ?: video.description
                val task = if (detailed) {
                    "Write detailed study notes. Give each concept its own \"## \" heading and under it the facts, figures, names, dates, lists and examples the video gives, " +
                        "as \"- \" bullets with the key term in **bold** and a few words of explanation the first time a term appears. " +
                        "Keep every exam pointer the speaker gives (previous-year questions, likely questions, memory aids) under the concept it belongs to. " +
                        "End with a \"## Revision checklist\" of 5-8 bullets a student can test themselves against."
                } else {
                    "Write the crux: 6-10 \"- \" bullets, most important first, each a complete point that stands on its own (the fact and why it matters), " +
                        "that a student can revise in two minutes. Then a short \"## Why it matters\" section of 2-3 lines on what this means for the exam."
                }
                val context = buildString {
                    append("Video title: ").append(video.title).append('\n')
                    if (channel.isNotBlank()) append("Channel: ").append(channel).append('\n')
                    append("Published: ").append(Dates.longDay(video.publishedAt)).append('\n')
                    append("Source material: ")
                    append(
                        if (captions != null) "auto-generated captions of the full video (machine-made, so words, names and numbers can be misheard; the spoken language may be Hindi or mixed)"
                        else "ONLY the title and description; the video itself was not available"
                    )
                    append("\n<material>\n").append(material).append("\n</material>")
                }
                val user = buildString {
                    append(task).append('\n')
                    append("Write in ").append(s.language).append(". Use only Markdown \"## \" headings, \"- \" bullets and **bold**.\n")
                    if (captions != null) {
                        append("How to write it:\n")
                        append("- These are the notes a very good teacher would hand out after this class, not a retelling of the video. Organise by concept, in the order that makes the subject easiest to learn, not the order things were said. Merge repetitions. Leave out greetings, promotion, jokes, housekeeping and filler.\n")
                        append("- Do not narrate: never write \"the teacher says\", \"in this video\", \"he explains\" or the like. State the content itself.\n")
                        append("- Plain, natural language a first-time reader follows: short sentences, everyday words, one idea per bullet.\n")
                        append("Accuracy comes before everything else:\n")
                        append("- Every fact, figure, name, date, list and example must be in the captions. Do not add facts from your own knowledge, even true ones, and do not complete a list the speaker left incomplete.\n")
                        append("- The captions are machine-made. You may correct the spelling of a name or term they have clearly misheard when you are certain what was said. When a number, name or term is garbled or could be read two ways, do not guess: leave it out, or keep the point and write \"(figure unclear in the video)\".\n")
                        append("- When the speaker gives an opinion, an estimate or says they are unsure, present it as that, not as fact.\n")
                        append("Make it visual where that helps understanding:\n")
                        append("- Begin with one line of the form `:::image <2-4 English words>` naming a concrete, photographable subject central to the video (a place, institution, object or natural feature), not a living person.\n")
                        append("- Where a process, a sequence of events or a comparison is explained, add a diagram on its own line, in exactly this form: ")
                        append("`:::diagram {\"title\":\"...\",\"kind\":\"flow\",\"items\":[{\"label\":\"...\",\"detail\":\"...\"}]}` ")
                        append("where kind is \"flow\" (steps in order), \"timeline\" (label is the date or period) or \"compare\" (2-3 items side by side). ")
                        append("2 to 6 items, short labels, valid JSON on a single line, at most ").append(if (detailed) 4 else 2).append(" diagrams, and only content from the material. Skip diagrams when nothing calls for one.\n")
                    }
                    if (captions == null) append("Because only the title and description are available, keep strictly to what they state, and open with one line saying this summary is limited to the description.\n")
                    append("Then rate how relevant this video is for each of these exams, with a one-line reason tied to the syllabus: ")
                    append(exams.joinToString("; ")).append(".\n").append(SCORING)
                }
                val out = withModel { apiKey, model ->
                    val c = g.claude.complete(
                        apiKey, model, SYSTEM, user, schema(SUMMARY_SCHEMA), effort = "medium",
                        maxTokens = if (detailed) 24000 else 10000, context = context,
                    ) { text -> setJob(key, AiJob(if (detailed) "Writing detailed notes" else "Writing the crux", text.length)) }
                    decode<SummaryOut>(c.text) to c.model
                }
                // Second pass: every line is checked against the captions, and anything they do not support is corrected or removed.
                var checked = out.first.markdown.trim()
                if (captions != null && checked.isNotBlank()) {
                    setJob(key, AiJob("Checking every point against the video", checked.length))
                    checked = runCatching { verified(context, checked) }.getOrElse { if (it is CancellationException) throw it; checked }
                }
                val out2 = out.first.copy(markdown = checked) to out.second
                val (result, model) = out2
                if (result.markdown.isBlank()) throw ClaudeException(ClaudeException.Kind.PARSE, "empty")
                if (detailed) g.db.videos().setDetailed(video.id, result.markdown.trim(), basis, model)
                else g.db.videos().setSummary(video.id, result.markdown.trim(), basis, model)
                if (result.relevance.isNotEmpty()) g.db.videos().setRelevance(video.id, AppJson.encodeToString(clamp(result.relevance)))
                setJob(key, null)
            } catch (e: CancellationException) {
                setJob(key, null)
                throw e
            } catch (e: Exception) {
                setJob(key, AiJob("", error = describe(e), running = false))
            } finally {
                running.remove(key)
            }
        }
    }

    @Serializable
    private data class Issue(val quote: String = "", val problem: String = "", val fix: String = "")

    @Serializable
    private data class IssuesOut(val issues: List<Issue> = emptyList())

    /**
     * Checks notes against the captions they were written from. The checker returns only the lines
     * it could not find support for, each with a corrected line (or nothing, to drop it), and those
     * are applied here. [context] is the same block the notes were written from, so it is read from
     * the API's cache.
     */
    private suspend fun verified(context: String, notes: String): String {
        val user = "Below are study notes written from the material above. Check them against the material line by line, as a strict fact-checker.\n" +
            "List every line of the notes that the material does not support: a fact, figure, name, date, list item or claim that is not in the material, contradicts it, " +
            "goes beyond what was said, or rests on a caption that is clearly garbled. Check the content of any `:::diagram` line the same way.\n" +
            "For each: quote is that whole line of the notes copied character for character; problem is the reason in a few words; fix is the corrected line that the material does support " +
            "(same Markdown form as the original line), or an empty string when the line should simply be removed.\n" +
            "Do not list matters of style, order or wording, and do not add new points. If every line is supported, return an empty list.\n\n<notes>\n" + notes + "\n</notes>"
        val out = withModel { apiKey, model ->
            decode<IssuesOut>(g.claude.complete(apiKey, model, SYSTEM, user, schema(ISSUES_SCHEMA), effort = "low", maxTokens = 8000, context = context).text)
        }
        if (out.issues.isEmpty()) return notes
        val fixes = out.issues.filter { it.quote.isNotBlank() }.associate { it.quote.trim() to it.fix.trim() }
        return notes.lines().mapNotNull { line -> if (line.trim() in fixes) fixes[line.trim()]?.takeIf { it.isNotBlank() } else line }.joinToString("\n").trim()
    }

    // ---------------------------------------------------------------- relevance

    @Serializable
    private data class RatedItem(val id: String, val note: String = "", val relevance: List<ExamRelevance> = emptyList())

    @Serializable
    private data class RatedOut(val items: List<RatedItem> = emptyList())

    private fun clamp(list: List<ExamRelevance>) = list.map { it.copy(score = it.score.coerceIn(0, 100)) }

    private suspend fun rate(kind: String, rows: List<Triple<String, String, String>>): List<RatedItem> {
        if (rows.isEmpty()) return emptyList()
        val exams = g.prefs.get().exams.toList()
        val user = buildString {
            append("Below are ").append(rows.size).append(' ').append(kind).append(". For each one, rate its relevance (0-100) for every exam in this list: ")
            append(exams.joinToString("; ")).append(".\n")
            append(
                SCORING + "\n"
            )
            append("Per exam, \"note\" names the syllabus area in at most 10 words. The item-level \"note\" is one sentence on why it matters for an aspirant, ")
            append("using only what the headline and snippet say. Do not add facts that are not in them. Return every id exactly as given.\n\n")
            rows.forEach { (id, title, snippet) ->
                append("id: ").append(id).append("\nheadline: ").append(title)
                if (snippet.isNotBlank()) append("\nsnippet: ").append(snippet)
                append("\n\n")
            }
        }
        val known = rows.map { it.first }.toSet()
        return withModel { apiKey, model ->
            decode<RatedOut>(g.claude.complete(apiKey, model, SYSTEM, user, schema(RATE_SCHEMA), effort = "low", maxTokens = 16000).text)
        }.items.filter { it.id in known }
    }

    fun newsKey(sourceId: String, day: String) = "news:$sourceId:$day"

    /** Rates the not-yet-rated headlines of one paper for one day. Safe to call repeatedly. */
    suspend fun curateNews(sourceId: String, day: String, limit: Int = 40) {
        val key = newsKey(sourceId, day)
        if (jobs.value[key]?.running == true) return
        val pending = g.db.news().unrated(sourceId, day, limit)
        if (pending.isEmpty() || g.apiKey() == null) return
        setJob(key, AiJob("Checking exam relevance"))
        try {
            for (chunk in pending.chunked(20)) {
                val rated = rate("news headlines", chunk.map { Triple(it.id, it.title, it.snippet) })
                for (r in rated) {
                    val rel = clamp(r.relevance)
                    g.db.news().setRelevance(r.id, AppJson.encodeToString(rel), r.note.trim(), rel.maxOfOrNull { it.score } ?: 0)
                }
            }
            setJob(key, null)
        } catch (e: CancellationException) {
            setJob(key, null)
            throw e
        } catch (e: Exception) {
            setJob(key, AiJob("", error = describe(e), running = false))
        }
    }

    /** Gives new videos a first relevance score from their title and description. */
    suspend fun rateNewVideos(limit: Int = 20) {
        if (g.apiKey() == null) return
        val pending = g.db.videos().unrated(limit)
        if (pending.isEmpty()) return
        runCatching {
            rate("YouTube video titles", pending.map { Triple(it.id, it.title, it.description.take(240)) }).forEach { r ->
                g.db.videos().setRelevance(r.id, AppJson.encodeToString(clamp(r.relevance)))
            }
        }
    }

    // ---------------------------------------------------------------- daily test

    @Serializable
    private data class QuizItem(
        val question: String, val options: List<String>, val answer_index: Int,
        val explanation: String = "", val source_id: String = "", val topic: String = "", val relevance: Int = -1,
    )

    @Serializable
    private data class QuizOut(val questions: List<QuizItem> = emptyList())

    /** Builds a test from the day's synced headlines (and any video summaries made that day). */
    suspend fun generateQuiz(day: String, exam: String, difficulty: String, count: Int): List<QuizQuestion> {
        val s = g.prefs.get()
        val sources = Sources.enabled(s).map { it.id }
        val news = g.db.news().forDay(day, sources, 60)
        val from = Dates.dayStart(day)
        val videos = g.db.videos().summarisedBetween(from, from + 86_400_000L).take(4)
        if (news.size + videos.size < 3) throw IllegalStateException("Not enough synced news for ${Dates.prettyDay(day)} yet. Refresh and try again.")
        val style = when (difficulty) {
            "Easy" -> "Direct, single-fact questions a first-time reader of the news could answer."
            "Hard" -> "Exam-grade: multi-statement questions (\"Consider the following statements… Which of the above is/are correct?\"), " +
                "close distractors, and links between the news and the static syllabus."
            else -> "Application level: connect the news to its syllabus concept; some two-statement questions."
        }
        val user = buildString {
            append("Create ").append(count).append(" multiple-choice questions for the ").append(exam).append(" exam. Difficulty: ").append(difficulty).append(". ").append(style).append('\n')
            append("Rules:\n")
            append("- Every question is anchored to exactly one item below; put that item's id in source_id.\n")
            append("- Everything about the news itself must come from that item's text. You may connect it to well-established static syllabus knowledge (constitutional provisions, institutions, geography, basic economics) only where you are certain of it.\n")
            append("- Never ask for a detail the item does not give. If an item is too thin for a fair question, skip it.\n")
            append("- Exactly 4 options, exactly one correct; answer_index is 0-3. Spread the correct option across positions.\n")
            append("- explanation: 2-3 sentences on why the answer is right. topic: the syllabus area in a few words.\n")
            append("- relevance: a whole number 0-100 for how much this question's theme matters for ").append(exam).append(". ").append(Q_SCORING).append("\n")
            append("- Write in ").append(s.language).append(".\n\nItems:\n")
            news.forEach { n ->
                append("id: ").append(n.id).append("\nsource: ").append(Sources.byId(s, n.sourceId)?.name ?: n.sourceId)
                append("\nheadline: ").append(n.title)
                if (n.snippet.isNotBlank()) append("\nsnippet: ").append(n.snippet)
                append("\n\n")
            }
            videos.forEach { v ->
                append("id: ").append(v.id).append("\nsource: video summary\nheadline: ").append(v.title)
                append("\nsnippet: ").append((v.detailed ?: v.summary).orEmpty().take(2500)).append("\n\n")
            }
        }
        val out = withModel { apiKey, model ->
            decode<QuizOut>(g.claude.complete(apiKey, model, SYSTEM, user, schema(QUIZ_SCHEMA), effort = "low", maxTokens = 32000).text)
        }
        val byId = news.associateBy { it.id }
        val vById = videos.associateBy { it.id }
        val questions = out.questions.filter { it.options.size == 4 && it.answer_index in 0..3 && it.question.isNotBlank() }.map { q ->
            val n: NewsItem? = byId[q.source_id]
            val v: Video? = vById[q.source_id]
            QuizQuestion(
                q = q.question.trim(), options = q.options.map { it.trim() }, answer = q.answer_index,
                explanation = q.explanation.trim(), sourceId = q.source_id,
                sourceTitle = n?.title ?: v?.title.orEmpty(), sourceLink = n?.link ?: v?.watchUrl.orEmpty(), topic = q.topic.trim(),
                relevance = q.relevance.coerceIn(-1, 100),
            )
        }
        if (questions.isEmpty()) throw ClaudeException(ClaudeException.Kind.PARSE, "no questions")
        return questions.take(count)
    }


    // ---------------------------------------------------------------- mains: questions

    @Serializable
    private data class MainsItem(
        val question: String, val topic: String = "", val paper: String = "", val demand: String = "",
        val source_id: String = "", val relevance: Int = -1,
    )

    @Serializable
    private data class MainsOut(val questions: List<MainsItem> = emptyList())

    /** Writes descriptive (Mains) questions from the day's synced headlines. */
    suspend fun generateMains(day: String, exam: String, difficulty: String, count: Int, marks: Int): List<MainsQuestion> {
        val s = g.prefs.get()
        val sources = Sources.enabled(s).map { it.id }
        val news = g.db.news().forDay(day, sources, 60)
        if (news.size < 3) throw IllegalStateException("Not enough synced news for ${Dates.prettyDay(day)} yet. Pull down on Crux to sync and try again.")
        val words = wordsFor(marks)
        val style = when (difficulty) {
            "Easy" -> "Straightforward: \"Discuss\", \"Explain\" or \"Describe\" one clear theme."
            "Hard" -> "Demanding: \"Critically examine\", \"Analyse\" or \"To what extent\", needing more than one dimension and a balanced judgement."
            else -> "Standard exam level: \"Discuss\", \"Examine\" or \"Comment\", with a clear issue and its implications."
        }
        val user = buildString {
            append("Write ").append(count).append(" descriptive (Mains) questions for the ").append(exam).append(" exam, each for ").append(marks)
            append(" marks with a limit of ").append(words).append(" words. Difficulty: ").append(difficulty).append(". ").append(style).append('\n')
            append("Rules:\n")
            append("- Every question grows from exactly one item below; put that item's id in source_id. Pick the items that matter most for this exam's Mains syllabus and skip crime, sport results, celebrity and routine politics.\n")
            append("- Frame it the way that exam does: the news is the peg, the question tests the underlying syllabus theme (so a student who knows the subject can answer without having read the article). Do not ask for a detail only the article contains.\n")
            append("- Never state as fact in the question anything the item does not say.\n")
            append("- One directive word per question. demand: one line saying exactly what that directive requires here (for example \"Give both sides and reach a reasoned view\").\n")
            append("- paper: the paper of this exam's Mains it belongs to (for UPSC CSE: GS1, GS2, GS3 or GS4; otherwise that exam's own paper name). topic: the syllabus area in a few words.\n")
            append("- relevance: a whole number 0-100 for how much this theme matters for ").append(exam).append(" Mains. ").append(Q_SCORING).append('\n')
            append("- Write in ").append(s.language).append(".\n\nItems:\n")
            news.forEach { n ->
                append("id: ").append(n.id).append("\nsource: ").append(Sources.byId(s, n.sourceId)?.name ?: n.sourceId)
                append("\nheadline: ").append(n.title)
                if (n.snippet.isNotBlank()) append("\nsnippet: ").append(n.snippet)
                append("\n\n")
            }
        }
        val out = withModel { apiKey, model ->
            decode<MainsOut>(g.claude.complete(apiKey, model, SYSTEM, user, schema(MAINS_Q_SCHEMA), effort = "low", maxTokens = 12000).text)
        }
        val byId = news.associateBy { it.id }
        val questions = out.questions.filter { it.question.isNotBlank() }.map { q ->
            val n = byId[q.source_id]
            MainsQuestion(
                q = q.question.trim(), marks = marks, words = words, topic = q.topic.trim(), paper = q.paper.trim(), demand = q.demand.trim(),
                relevance = q.relevance.coerceIn(-1, 100), sourceId = q.source_id, sourceTitle = n?.title.orEmpty(), sourceLink = n?.link.orEmpty(),
                context = n?.let { listOf(it.title, it.snippet).filter { t -> t.isNotBlank() }.joinToString(". ").take(600) }.orEmpty(),
            )
        }
        if (questions.isEmpty()) throw ClaudeException(ClaudeException.Kind.PARSE, "no questions")
        return questions.take(count)
    }

    // ---------------------------------------------------------------- mains: evaluation

    @Serializable
    private data class CritOut(val criterion: String = "", val score: Float = 0f, val comment: String = "")

    @Serializable
    private data class ErrOut(val written: String = "", val issue: String = "")

    @Serializable
    private data class EvalOut(
        val legibility: Int = 0, val is_answer: Boolean = true, val problem: String = "", val transcript: String = "",
        val word_count: Int = 0, val breakdown: List<CritOut> = emptyList(), val verdict: String = "",
        val strengths: List<String> = emptyList(), val improvements: List<String> = emptyList(), val missing: List<String> = emptyList(),
        val errors: List<ErrOut> = emptyList(), val structure: String = "", val outline: List<String> = emptyList(),
        val exam_tips: List<String> = emptyList(), val time_advice: String = "", val marked: List<MarkOut> = emptyList(),
    )

    @Serializable
    private data class MarkOut(val page: Int = 1, val section: String = "", val text: String = "", val mark: String = "partial", val remark: String = "")

    /**
     * Reads the photographed pages of one handwritten answer and marks it. The transcript comes back
     * with the marks so the student can see exactly what was read; marks are the sum of the criteria,
     * each capped at its own maximum here, so the total can never drift from its parts.
     */
    suspend fun evaluateMains(q: MainsQuestion, exam: String, pages: List<ByteArray>, seconds: Int, onStage: (String) -> Unit = {}): MainsEval {
        val s = g.prefs.get()
        val criteria = criteriaFor(q.marks)
        val target = targetSeconds(q.marks)
        val user = buildString {
            append("The ").append(pages.size).append(if (pages.size == 1) " photograph above is" else " photographs above are, in order,")
            append(" a student's handwritten answer.\n\n")
            append("Exam: ").append(exam).append(" (Mains)\n")
            append("Question (").append(q.marks).append(" marks, about ").append(q.words).append(" words): ").append(q.q).append('\n')
            if (q.demand.isNotBlank()) append("What the directive demands: ").append(q.demand).append('\n')
            if (q.paper.isNotBlank() || q.topic.isNotBlank()) append("Paper and topic: ").append(listOf(q.paper, q.topic).filter { it.isNotBlank() }.joinToString(" · ")).append('\n')
            if (q.context.isNotBlank()) append("News item the question was set from (context for you, not a model answer): ").append(q.context).append('\n')
            if (seconds > 0) {
                append("Time the student took to write: ").append(seconds / 60).append(" min ").append(seconds % 60).append(" s. ")
                append("At the UPSC pace of 3 hours for 250 marks, a ").append(q.marks).append("-mark answer gets about ").append(target / 60).append(" min ").append(target % 60).append(" s.\n")
            } else append("The time taken was not recorded.\n")
            append("\nDo this in order:\n")
            append("1. transcript: copy out what is written, page by page (start each page with \"Page N:\"), in the language and script it is written in. ")
            append("Write [illegible] for any word you cannot read with confidence; never guess it, complete it or correct it. Describe a diagram, map or flowchart in square brackets, e.g. [diagram: flowchart of ...]. ")
            append("legibility is 0-100: the share of the writing you could read with confidence. word_count is your count of the words written.\n")
            append("2. is_answer: false if the pages are blank, are not a handwritten answer, cannot be read at all, or answer a different question; then explain in problem, give every criterion 0, and leave the feedback lists empty except for one line in improvements on what to do next. Otherwise true and problem is empty.\n")
            append("3. breakdown: mark exactly these criteria, in this order, using halves where needed: ")
            append(criteria.joinToString("; ") { "${it.first} (out of ${fmt(it.second)})" }).append(". ")
            append("Each comment says, in one or two sentences, what in the answer earned or lost those marks.\n")
            append("4. verdict: two or three plain sentences a good teacher would say to this student about this answer.\n")
            append("5. strengths: what is done well, each tied to something actually written (up to 4). improvements: the specific changes that would raise the marks most, most valuable first, each one doable in the next attempt (up to 6).\n")
            append("6. missing: the important points or dimensions a top answer to this question would cover that this answer does not (up to 6).\n")
            append("7. errors: every factual or conceptual mistake in the answer: written is the student's own words quoted from the transcript, issue is what is wrong and the correct position if you are certain of it. Empty if there are none. Do not list spelling slips here.\n")
            append("8. structure: one or two sentences on the introduction, the body (headings, paragraphs, flow), the conclusion, and any diagram.\n")
            append("9. outline: how a topper would lay this answer out in ").append(q.words).append(" words: 5-8 short lines (introduction, each body heading with its key point, conclusion).\n")
            append("10. exam_tips: 2-4 habits for the exam hall drawn from what you saw in this answer (length against the word limit, handwriting, underlining, spacing, use of the margin, time).\n")
            append("11. time_advice: one or two sentences on the time taken against the pace above and the length written; if the time was not recorded, say only what the length suggests.\n")
            append("12. marked: now mark the copy as an examiner does with a pen. Go through the answer page by page and section by section in the order written ")
            append("(heading, introduction, each point or paragraph, any diagram, conclusion); every section appears once, at most 16 entries. For each: page is the page number it is on; ")
            append("section is a short label such as \"Introduction\", \"Point 3\" or \"Conclusion\"; text is the student's own words for that section copied exactly from your transcript ")
            append("(if longer than about 25 words, the first 25 followed by …); mark is \"good\" (correct, relevant and well put: a tick), \"partial\" (right idea but thin, imprecise or unexplained), ")
            append("\"wrong\" (factually or conceptually wrong: a cross) or \"weak\" (vague, irrelevant or adds nothing); remark is what you would write in the margin there: ")
            append("at most 12 words, specific to those words, no praise without a reason. The marks you give in breakdown must agree with these ticks and crosses.\n")
            append("Write all feedback in ").append(s.language).append(".")
        }
        val (out, model) = withModel { apiKey, m ->
            onStage("Reading your handwriting")
            val c = g.claude.complete(
                apiKey, m, MAINS_SYSTEM, user, schema(MAINS_EVAL_SCHEMA), effort = "medium", maxTokens = 20000, images = pages,
            ) { text -> onStage(if (text.length < 600) "Reading your handwriting" else "Marking against the question") }
            decode<EvalOut>(c.text) to c.model
        }
        val answered = out.is_answer && out.transcript.isNotBlank()
        val breakdown = criteria.mapIndexed { i, (name, max) ->
            val got = out.breakdown.getOrNull(i)
            // Round to the nearest half mark and never above the criterion's maximum.
            val score = if (answered) (Math.round((got?.score ?: 0f).coerceIn(0f, max) * 2) / 2f) else 0f
            MainsCriterion(name, score, max, got?.comment?.trim().orEmpty())
        }
        fun clean(list: List<String>, n: Int) = list.map { it.trim() }.filter { it.isNotBlank() }.take(n)
        return MainsEval(
            legibility = out.legibility.coerceIn(0, 100), isAnswer = answered,
            problem = out.problem.trim().ifBlank { if (answered) "" else "No readable answer was found on the pages." },
            transcript = out.transcript.trim(), wordCount = out.word_count.coerceAtLeast(0),
            marks = breakdown.sumOf { it.score.toDouble() }.toFloat(), breakdown = breakdown, verdict = out.verdict.trim(),
            strengths = if (answered) clean(out.strengths, 4) else emptyList(), improvements = clean(out.improvements, 6),
            missing = if (answered) clean(out.missing, 6) else emptyList(),
            errors = if (answered) out.errors.filter { it.written.isNotBlank() && it.issue.isNotBlank() }.map { MainsError(it.written.trim(), it.issue.trim()) }.take(8) else emptyList(),
            structure = out.structure.trim(), outline = clean(out.outline, 8), examTips = clean(out.exam_tips, 4),
            timeAdvice = out.time_advice.trim(), model = model,
            marked = if (answered) out.marked.filter { it.text.isNotBlank() && it.remark.isNotBlank() }.take(16).map {
                MainsMark(
                    page = it.page.coerceIn(1, pages.size.coerceAtLeast(1)), section = it.section.trim(), text = it.text.trim(),
                    mark = it.mark.trim().lowercase().takeIf { m -> m in setOf("good", "partial", "wrong", "weak") } ?: "partial", remark = it.remark.trim(),
                )
            } else emptyList(),
        )
    }

    private fun fmt(v: Float) = if (v % 1f == 0f) v.toInt().toString() else v.toString()

    // ---------------------------------------------------------------- ask about an article

    /** A question being answered right now about one article or video. */
    data class AskJob(val question: String, val text: String = "", val error: String? = null, val thinking: String = "", val searching: Boolean = false)

    /**
     * What a question is about: an article, or a video's notes. [material] loads the text the answer
     * must rest on; [id] is what the saved questions are filed under.
     */
    class AskTopic(val id: String, val kind: String, val title: String, val meta: String, val material: suspend () -> String)

    /** The answer without the trailing ">> follow-up" lines and the source lines kept with it. */
    fun answerOnly(raw: String): String =
        raw.lines().filterNot { it.trimStart().startsWith(">>") || it.startsWith(SRC) }.joinToString("\n").trim()

    /** Web pages an answer rests on, as (title, link). Empty when it came from the material alone. */
    fun sourcesOf(raw: String): List<Pair<String, String>> =
        raw.lines().filter { it.startsWith(SRC) }.mapNotNull { l -> l.removePrefix(SRC).split(" | ", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }

    private fun followupsOf(raw: String): List<String> =
        raw.lines().map { it.trim() }.filter { it.startsWith(">>") }.map { it.removePrefix(">>").trim() }.filter { it.length > 5 }.take(3)

    /** Keyed by topic id. An entry is there while its answer is being written, or after it failed. */
    val asking = MutableStateFlow<Map<String, AskJob>>(emptyMap())

    private val articleTexts = ConcurrentHashMap<String, String>()

    /** The text each open topic rests on, loaded once (when its sheet opens) and reused for every question on it. */
    private val materials = ConcurrentHashMap<String, String>()
    private val askJobs = ConcurrentHashMap<String, Job>()

    private suspend fun materialOf(topic: AskTopic): String {
        materials[topic.id]?.takeIf { it.isNotBlank() }?.let { return it }
        return topic.material().trim().also { if (it.isNotBlank()) { if (materials.size > 12) materials.clear(); materials[topic.id] = it } }
    }

    /** Fetches a topic's text ahead of the first question, so the answer does not wait for it. */
    fun warm(topic: AskTopic) {
        g.scope.launch { g.claude.warm() }
        if (materials.containsKey(topic.id)) return
        g.scope.launch { runCatching { materialOf(topic) } }
    }

    /** Stops the answer being written for [id]. What had been written so far is kept, marked as unfinished. */
    fun stopAsk(id: String) { askJobs[id]?.cancel() }

    fun clearAsk(id: String) = asking.update { it - id }

    fun topicFor(item: NewsItem, sourceName: String) = AskTopic(
        id = item.id, kind = "article", title = item.title,
        meta = "$sourceName · ${item.section} · ${Dates.longDay(item.publishedAt)}" + if (item.snippet.isNotBlank()) "\nSummary line: ${item.snippet}" else "",
    ) {
        articleTexts[item.id] ?: g.news.articleText(item.link).also {
            if (it.isNotBlank()) { if (articleTexts.size > 20) articleTexts.clear(); articleTexts[item.id] = it }
        }
    }

    /** A video: the captions when they are in memory, with the notes written from them. */
    fun topicFor(video: Video) = AskTopic(
        id = "video:${video.id}", kind = "video", title = video.title, meta = "YouTube video · ${Dates.longDay(video.publishedAt)}",
    ) {
        buildString {
            (video.detailed ?: video.summary)?.let { append("Notes written from the video:\n").append(it).append("\n\n") }
            transcripts[video.id]?.let { append("Captions of the video (auto-generated, may contain recognition errors):\n").append(it.take(60_000)) }
        }
    }

    /** Whatever is open in read mode. A link to a publisher's page is read live; otherwise the text on screen is the material. */
    fun topicForText(id: String, title: String, meta: String, body: String, link: String?) = AskTopic(id, if (link?.contains("youtube.com") == true) "video" else "article", title, meta) {
        val live = if (link != null && link.startsWith("http") && !link.contains("youtube.com")) runCatching { g.news.articleText(link) }.getOrDefault("") else ""
        val captions = if (id.startsWith("video:")) transcripts[id.removePrefix("video:")] else null
        (if (live.length > body.length) live else body) + (captions?.let { "\n\nCaptions of the video (auto-generated, may contain recognition errors):\n" + it.take(60_000) } ?: "")
    }

    fun ask(item: NewsItem, sourceName: String, question: String) = ask(topicFor(item, sourceName), question)

    /**
     * Answers [question] about [topic] and saves the exchange. It runs outside the screen, so closing
     * the sheet (or the app) does not lose it. The answer rests on the material first; only when the
     * material does not hold it may the model search the web, and then the pages it used are kept
     * with the answer. Earlier questions on the same topic are passed along so follow-ups make sense.
     */
    fun ask(topic: AskTopic, question: String) {
        val q = question.trim()
        val id = topic.id
        if (q.isEmpty() || asking.value[id]?.let { it.error == null } == true) return
        asking.update { it + (id to AskJob(q)) }
        askJobs[id] = g.scope.launch {
            // Timings for the debug build only: where an answer's wait is spent.
            val t0 = System.currentTimeMillis()
            var firstAt = 0L
            fun mark(what: String) { if (DEBUG_TIMINGS) println("DCruxAsk: $what at ${System.currentTimeMillis() - t0} ms") }
            try {
                val s = g.prefs.get()
                val material = materialOf(topic)
                mark("material ready (${material.length} chars)")
                val earlier = g.db.asks().latest(id, 4).reversed()
                val thing = if (topic.kind == "video") "video" else "news item"
                val user = buildString {
                    append("A student is studying this ").append(thing).append(" and has a question or doubt about it.\n")
                    append("Title: ").append(topic.title).append('\n')
                    if (topic.meta.isNotBlank()) append(topic.meta).append('\n')
                    if (material.isBlank()) append("The full text could not be loaded, so only the title and the lines above are available.\n")
                    else append("Its text is in the material block above.\n")
                    if (earlier.isNotEmpty()) {
                        append("\nEarlier questions and answers on this:\n")
                        earlier.forEach { append("Q: ").append(it.question).append("\nA: ").append(answerOnly(it.answer).take(1200)).append("\n") }
                    }
                    append("\nQuestion: ").append(q).append("\n\n")
                    append("Answer as a mentor would for an aspirant of ").append(s.exams.joinToString(", ")).append(", in ").append(s.language).append(".\n")
                    append("Where the answer comes from, in this order:\n")
                    append("1. The material above. Take everything about this ").append(thing).append(" from it.\n")
                    append("2. Well-established syllabus knowledge (the Constitution, institutions, definitions, standard concepts) that you are certain of, to explain or give background.\n")
                    append(STEP3)
                    append("Never guess and never fill a gap with something plausible. If the material, your certain knowledge and the search still do not give a reliable answer, say plainly that you could not find one and what the student should check.\n")
                    append("How to write it: direct, no preamble, the answer first and then the explanation. Short paragraphs or \"- \" bullets, **bold** for the key terms; \"## \" headings only when the answer has clear parts.\n")
                    append("Make it visual when that helps understanding: for a process, a sequence of events or a comparison add a diagram on its own line, exactly as ")
                    append("`:::diagram {\"title\":\"...\",\"kind\":\"flow\",\"items\":[{\"label\":\"...\",\"detail\":\"...\"}]}` where kind is \"flow\", \"timeline\" or \"compare\" ")
                    append("(2 to 6 items, short labels, valid JSON on one line, one diagram at most); for a concrete place, institution, object or natural feature central to the answer ")
                    append("you may add one line `:::image <2-4 English words>` (never a living person). Skip both when nothing calls for them.\n")
                    append("End the answer with one line beginning \"Basis:\" that says what it rests on: the ").append(thing).append(", standard syllabus knowledge, or web search.\n")
                    append("After that, add exactly three short follow-up questions this student might ask next, each on its own line beginning with \">> \" and nothing else on that line.")
                }
                // First without the web: the search tool, merely by being offered, more than doubles the wait for the first
                // words (measured), and most questions are answered by the material. The model asks for the web with one
                // word when it needs it, and only then is the question run again with search switched on.
                val needsWeb = "3. If 1 and 2 do not answer the question, or it asks for a current fact, date, figure or development you are not certain of, " +
                    "do not attempt an answer: reply with the single word SEARCH and nothing else. The question will then be given back to you with web search available.\n"
                val withWeb = "3. 1 and 2 were not enough for this question, so search the web: prefer official and standard sources " +
                    "(government sites, PIB, ministries, courts, RBI, UN bodies, NCERT, established newspapers), and state what those pages say.\n"
                suspend fun run(search: Boolean) = withModel { apiKey, model ->
                    g.claude.complete(
                        // Quick setting: measured at about a second to the first words, against five with the reasoning step,
                        // on the same model. The grounding rules in the prompt do the work of keeping it right.
                        apiKey, model, MENTOR_SYSTEM, user.replace(STEP3, if (search) withWeb else needsWeb), effort = "low", maxTokens = 12000, webSearch = search,
                        context = material.takeIf { it.isNotBlank() }?.let { "<material>\n" + it.take(90_000) + "\n</material>" },
                        onSearching = { asking.update { m -> m + (id to (m[id] ?: AskJob(q)).copy(searching = true)) } },
                        onText = { text ->
                            if (firstAt == 0L) { firstAt = System.currentTimeMillis(); mark("first text (search=$search)") }
                            // The one-word request for the web is not an answer and is never shown.
                            val asksForWeb = !search && WANTS_WEB.startsWith(text.trim().take(WANTS_WEB.length)) && text.trim().length <= WANTS_WEB.length + 2
                            asking.update { m -> m + (id to (m[id] ?: AskJob(q)).copy(text = if (asksForWeb) "" else text, searching = text.trim().equals(WANTS_WEB, ignoreCase = true))) }
                        },
                    )
                }
                mark("request sent")
                var done = run(search = false)
                mark("first pass done")
                if (done.text.trim().trimEnd('.').equals(WANTS_WEB, ignoreCase = true)) {
                    asking.update { m -> m + (id to AskJob(q, searching = true)) }
                    done = try {
                        run(search = true)
                    } catch (e: ClaudeException) {
                        // Web search can be switched off for the account or the service: say so rather than guess.
                        if (e.kind != ClaudeException.Kind.BAD_REQUEST && e.kind != ClaudeException.Kind.PERMISSION) throw e
                        throw ClaudeException(ClaudeException.Kind.BAD_REQUEST, "This is not covered by the ${thing} and the web search is not available right now, so Crux AI will not guess. Try again later.")
                    }
                }
                val answer = answerOnly(done.text)
                if (answer.isBlank()) throw ClaudeException(ClaudeException.Kind.PARSE, "empty")
                val stored = answer + done.sources.joinToString("") { (title, url) -> "\n$SRC${title.replace(" | ", " - ").replace("\n", " ")} | $url" }
                g.db.asks().insert(Ask(articleId = id, question = q, answer = stored, askedAt = System.currentTimeMillis(), followups = followupsOf(done.text).joinToString("\n"), topic = topic.title))
                asking.update { it - id }
            } catch (e: CancellationException) {
                // Stopped by the student: keep what had been written, clearly marked as unfinished.
                val partial = answerOnly(asking.value[id]?.text.orEmpty())
                if (partial.length >= 60) withContext(NonCancellable) {
                    g.db.asks().insert(Ask(articleId = id, question = q, answer = "$partial\n\n(Stopped before the answer was complete.)", askedAt = System.currentTimeMillis(), topic = topic.title))
                }
                asking.update { it - id }
                throw e
            } catch (e: Exception) {
                asking.update { it + (id to AskJob(q, error = describe(e))) }
            } finally {
                askJobs.remove(id)
            }
        }
    }

    // ---------------------------------------------------------------- search overview

    /** Streams a short overview written only from the search hits it is given. */
    suspend fun overview(query: String, hits: List<Pair<String, String>>, onText: (String) -> Unit): String {
        val s = g.prefs.get()
        val user = buildString {
            append("A student searched their own synced library for: \"").append(query).append("\".\n")
            append("Write a short overview (4-7 bullets, then one line on exam relevance for ").append(s.exams.joinToString(", ")).append(") ")
            append("using only the numbered results below. Cite results like [2] after the point they support. ")
            append("If the results do not really cover the query, say so in one line instead of filling in from memory.\n")
            append("Write in ").append(s.language).append(". Use only \"- \" bullets and **bold**.\n\nResults:\n")
            hits.forEachIndexed { i, (title, text) -> append('[').append(i + 1).append("] ").append(title).append('\n').append(text).append("\n\n") }
        }
        return withModel { apiKey, model ->
            g.claude.complete(apiKey, model, SYSTEM, user, effort = "low", maxTokens = 6000, onText = onText).text
        }.trim()
    }

    companion object {
        /** Default first, then progressively lighter models. */
        val AUTO_ORDER = listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5")

        /**
         * One rubric for every score in the app, so 70 means the same thing on a video and on a headline.
         * The score is the sum of four parts and is always out of 100.
         */
        const val SCORING =
            "Score each exam out of 100 as the sum of four parts, and use the whole range: " +
                "syllabus fit, 0-40 (is the topic named in that exam's syllabus? state-level exams also count their own state's affairs); " +
                "likelihood of a question, 0-30 (the kind of thing that exam has asked: polity, governance, economy, international relations, " +
                "environment, science and technology, schemes, reports, history and culture rank high; crime, celebrity, sports results and routine politics rank near zero); " +
                "depth, 0-20 (does it explain concepts, causes or provisions, not just an event?); " +
                "currency, 0-10 (is it about a development of the past year?). " +
                "A score of 70 or more means study this, 45-69 worth a read, below 45 skip. Give the total only, as a whole number from 0 to 100."

        /** Word limit that goes with a question's marks (UPSC: 10 marks 150 words, 15 marks 250 words). */
        fun wordsFor(marks: Int) = when { marks <= 10 -> 150; marks <= 15 -> 250; else -> marks * 17 }

        /** Writing time a question of [marks] gets at the UPSC pace: 250 marks in 3 hours. */
        fun targetSeconds(marks: Int) = marks * 180 * 60 / 250

        /** The marking scheme, the same for every answer: name and maximum for a question worth [marks]. */
        fun criteriaFor(marks: Int): List<Pair<String, Float>> {
            val shares = listOf(
                "Understanding the question" to 0.2f, "Content and accuracy" to 0.4f, "Structure and flow" to 0.15f,
                "Examples, data and diagrams" to 0.15f, "Presentation and language" to 0.1f,
            )
            return shares.map { (name, share) -> name to Math.round(marks * share * 2) / 2f }
        }

        /** How a single question's theme is scored for one exam; the same four parts as everywhere else in the app. */
        const val Q_SCORING =
            "Build it as: syllabus fit 0-40, likelihood that exam asks on this theme 0-30, depth of concept tested 0-20, currency 0-10; give the total only."

        const val MAINS_SYSTEM =
            "You are DCrux's Mains answer evaluator: an experienced, fair and exacting examiner and mentor for India's civil-service exams " +
                "(UPSC and the State PSCs). You are shown photographs of one handwritten answer and must read it and mark it.\n" +
                "Rules that override everything else:\n" +
                "1. Read first. Transcribe the handwriting faithfully. Where you cannot read a word with confidence write [illegible]; never guess, complete, tidy or improve the student's text.\n" +
                "2. Judge only what is on the pages. Never credit a point the student did not write, and never say the student wrote something they did not. Everything you praise or criticise must be traceable to the transcript.\n" +
                "3. If the pages are blank, unreadable, not a handwritten answer, or answer a different question, say exactly that and give no marks. Do not invent an answer to mark.\n" +
                "4. Mark as real examiners do: strictly and realistically. A competent answer earns about 40-50% of the marks, a very good one 55-65%, and more than that only for an exceptional answer. Do not flatter, do not inflate, and do not soften a weak answer.\n" +
                "5. Be certain before you call something wrong. Quote the student's words, say what is wrong, and give the correct position only when you are sure of it; when you are not sure, say \"verify this\" instead of asserting.\n" +
                "6. Do not invent facts in your own suggestions: no made-up statistics, case names, article numbers, committee names, report titles or dates. Name such a thing only when you are certain it is real and apt. " +
                "Otherwise say what kind of evidence would strengthen the answer and where a student finds it (NCERT, the Constitution, the Economic Survey, the Budget, ARC and Finance Commission reports, PIB).\n" +
                "7. Every line of feedback must be specific to this answer and something the student can act on. No generic advice, no filler, no praise without a reason."

        const val MAINS_Q_SCHEMA =
            """{"type":"object","properties":{"questions":{"type":"array","items":{"type":"object","properties":{"question":{"type":"string"},"topic":{"type":"string"},"paper":{"type":"string"},"demand":{"type":"string"},"source_id":{"type":"string"},"relevance":{"type":"integer"}},"required":["question","topic","paper","demand","source_id","relevance"],"additionalProperties":false}}},"required":["questions"],"additionalProperties":false}"""

        private const val STRS = """{"type":"array","items":{"type":"string"}}"""

        const val MAINS_EVAL_SCHEMA =
            """{"type":"object","properties":{"transcript":{"type":"string"},"legibility":{"type":"integer"},"word_count":{"type":"integer"},"is_answer":{"type":"boolean"},"problem":{"type":"string"},"breakdown":{"type":"array","items":{"type":"object","properties":{"criterion":{"type":"string"},"score":{"type":"number"},"comment":{"type":"string"}},"required":["criterion","score","comment"],"additionalProperties":false}},"verdict":{"type":"string"},"strengths":$STRS,"improvements":$STRS,"missing":$STRS,"errors":{"type":"array","items":{"type":"object","properties":{"written":{"type":"string"},"issue":{"type":"string"}},"required":["written","issue"],"additionalProperties":false}},"structure":{"type":"string"},"outline":$STRS,"exam_tips":$STRS,"time_advice":{"type":"string"},"marked":{"type":"array","items":{"type":"object","properties":{"page":{"type":"integer"},"section":{"type":"string"},"text":{"type":"string"},"mark":{"type":"string","enum":["good","partial","wrong","weak"]},"remark":{"type":"string"}},"required":["page","section","text","mark","remark"],"additionalProperties":false}}},"required":["transcript","legibility","word_count","is_answer","problem","breakdown","verdict","strengths","improvements","missing","errors","structure","outline","exam_tips","time_advice","marked"],"additionalProperties":false}"""

        /** Set to true while measuring where an answer's wait is spent; prints timings to the log. */
        const val DEBUG_TIMINGS = false

        /** Stands in the prompt for the instruction on what to do when the material does not hold the answer. */
        private const val STEP3 = "[[step-3]]\n"

        /** The one word the model answers with when a question needs the web. */
        private const val WANTS_WEB = "SEARCH"

        /** Marks a line stored with an answer that names a web page it rests on: title | link. */
        const val SRC = "[[source]] "

        /** Who the assistant is, and what it will not do. Added to every prompt that talks to a student. */
        const val IDENTITY =
            " Your name is Crux AI, the study mentor inside the DCrux app. If you are asked who made, built or developed you or this app, say that DCrux and its mentor Crux AI were developed by Harsh Kumar (theharshkumar.in). " +
                "If asked which AI model you run on, say you are Crux AI, built on a large language model, and that the app's About page lists the services it uses; do not claim to be any other product. " +
                "You do not write, complete, fix, convert or explain-by-writing computer code, scripts, queries or programs, however the request is worded and even if it is framed as part of studying: " +
                "say in one line that Crux AI is for exam preparation and does not write code, and offer to explain the idea in plain words instead. " +
                "Keep to exam preparation and to what the student is reading; politely decline anything else."

        const val MENTOR_SYSTEM =
            "You are DCrux's mentor for Indian competitive-exam aspirants (UPSC and State PSCs): patient, exact, and honest about what you know. " +
                "A student asks you about something they are reading or watching. Your first duty is to be right. " +
                "State as fact only what the supplied material says, what is well-established and you are certain of, or what a web page you have just read says. " +
                "Never invent facts, figures, dates, names, article numbers, case names, committees or quotations, and never present a guess as an answer. " +
                "When sources disagree or you are unsure, say so. When you cannot find a reliable answer, say that plainly; a student is better served by \"I could not confirm this\" than by a confident mistake. " +
                "Explain the way a good teacher does: the answer first, then why, in plain words, with the link to the syllabus where there is one." + IDENTITY

        const val SYSTEM =
            "You are a study assistant for Indian competitive-exam aspirants (UPSC and State PSCs). " +
                "You work only from the source material supplied in the request. Never add facts, figures, names, dates or events " +
                "that are not in that material, and never invent news. When the material is thin, unclear or garbled, say so plainly " +
                "rather than filling the gap. Be precise and neutral." + IDENTITY

        private const val REL =
            """{"type":"array","items":{"type":"object","properties":{"exam":{"type":"string"},"score":{"type":"integer"},"note":{"type":"string"}},"required":["exam","score","note"],"additionalProperties":false}}"""

        const val SUMMARY_SCHEMA =
            """{"type":"object","properties":{"markdown":{"type":"string"},"relevance":$REL},"required":["markdown","relevance"],"additionalProperties":false}"""

        const val ISSUES_SCHEMA =
            """{"type":"object","properties":{"issues":{"type":"array","items":{"type":"object","properties":{"quote":{"type":"string"},"problem":{"type":"string"},"fix":{"type":"string"}},"required":["quote","problem","fix"],"additionalProperties":false}}},"required":["issues"],"additionalProperties":false}"""

        const val RATE_SCHEMA =
            """{"type":"object","properties":{"items":{"type":"array","items":{"type":"object","properties":{"id":{"type":"string"},"note":{"type":"string"},"relevance":$REL},"required":["id","note","relevance"],"additionalProperties":false}}},"required":["items"],"additionalProperties":false}"""

        const val QUIZ_SCHEMA =
            """{"type":"object","properties":{"questions":{"type":"array","items":{"type":"object","properties":{"question":{"type":"string"},"options":{"type":"array","items":{"type":"string"}},"answer_index":{"type":"integer"},"explanation":{"type":"string"},"source_id":{"type":"string"},"topic":{"type":"string"},"relevance":{"type":"integer"}},"required":["question","options","answer_index","explanation","source_id","topic","relevance"],"additionalProperties":false}}},"required":["questions"],"additionalProperties":false}"""
    }
}
