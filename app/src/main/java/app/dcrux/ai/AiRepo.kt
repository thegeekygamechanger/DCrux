package app.dcrux.ai

import app.dcrux.Graph
import app.dcrux.data.AppJson
import app.dcrux.data.Dates
import app.dcrux.data.ExamRelevance
import app.dcrux.data.ModelInfo
import app.dcrux.data.NewsItem
import app.dcrux.data.QuizQuestion
import app.dcrux.data.Video
import app.dcrux.net.Sources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
        AiWork.begin(g.app)
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
            AiWork.end(g.app)
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
                    "Write detailed study notes: a \"## \" heading for each topic covered, the key facts, figures, names and " +
                        "dates exactly as stated, terms explained in a line, and a final \"## Revision checklist\" of 5-8 bullets."
                } else {
                    "Write the crux: 6-10 bullet points a student can revise in two minutes, then a short \"## Why it matters\" section."
                }
                val user = buildString {
                    append("Video title: ").append(video.title).append('\n')
                    if (channel.isNotBlank()) append("Channel: ").append(channel).append('\n')
                    append("Published: ").append(Dates.longDay(video.publishedAt)).append('\n')
                    append("Source material: ")
                    append(
                        if (captions != null) "auto-generated captions of the full video (may contain recognition errors; spoken language may be Hindi or mixed)"
                        else "ONLY the title and description; the video itself was not available"
                    )
                    append("\n<material>\n").append(material).append("\n</material>\n\n")
                    append(task).append('\n')
                    append("Write in ").append(s.language).append(". Use only Markdown \"## \" headings, \"- \" bullets and **bold**.\n")
                    if (captions != null) {
                        append("Make it visual where that helps understanding:\n")
                        append("- Begin with one line of the form `:::image <2-4 English words>` naming a concrete, photographable subject central to the video (a place, institution, object or natural feature), not a living person.\n")
                        append("- Where a process, a sequence of events or a comparison is explained, add a diagram on its own line, in exactly this form: ")
                        append("`:::diagram {\"title\":\"...\",\"kind\":\"flow\",\"items\":[{\"label\":\"...\",\"detail\":\"...\"}]}` ")
                        append("where kind is \"flow\" (steps in order), \"timeline\" (label is the date or period) or \"compare\" (2-3 items side by side). ")
                        append("2 to 6 items, short labels, valid JSON on a single line, at most ").append(if (detailed) 4 else 2).append(" diagrams, and only content from the material. Skip diagrams when nothing calls for one.\n")
                    }
                    if (captions == null) append("Because only the title and description are available, keep strictly to what they state, and open with one line saying this summary is limited to the description.\n")
                    append("Then rate how relevant this video is (0-100) for each of these exams, with a one-line reason tied to the syllabus: ")
                    append(exams.joinToString("; ")).append('.')
                }
                val out = withModel { apiKey, model ->
                    val c = g.claude.complete(
                        apiKey, model, SYSTEM, user, schema(SUMMARY_SCHEMA),
                        effort = if (detailed) "medium" else "low",
                        maxTokens = if (detailed) 24000 else 10000,
                    ) { text -> setJob(key, AiJob(if (detailed) "Writing detailed notes" else "Writing the crux", text.length)) }
                    decode<SummaryOut>(c.text) to c.model
                }
                val (result, model) = out
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
                "Scale: 80-100 squarely in the syllabus and the kind of thing asked (polity, governance, economy, international relations, " +
                    "environment, science and technology, schemes, reports, history and culture); 50-79 useful background; 20-49 marginal; " +
                    "0-19 not exam material (crime, celebrity, sports results, routine politics).\n"
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
        val explanation: String = "", val source_id: String = "", val topic: String = "",
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
            )
        }
        if (questions.isEmpty()) throw ClaudeException(ClaudeException.Kind.PARSE, "no questions")
        return questions.take(count)
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

        const val SYSTEM =
            "You are DCrux, a study assistant for Indian competitive-exam aspirants (UPSC and State PSCs). " +
                "You work only from the source material supplied in the request. Never add facts, figures, names, dates or events " +
                "that are not in that material, and never invent news. When the material is thin, unclear or garbled, say so plainly " +
                "rather than filling the gap. Be precise and neutral."

        private const val REL =
            """{"type":"array","items":{"type":"object","properties":{"exam":{"type":"string"},"score":{"type":"integer"},"note":{"type":"string"}},"required":["exam","score","note"],"additionalProperties":false}}"""

        const val SUMMARY_SCHEMA =
            """{"type":"object","properties":{"markdown":{"type":"string"},"relevance":$REL},"required":["markdown","relevance"],"additionalProperties":false}"""

        const val RATE_SCHEMA =
            """{"type":"object","properties":{"items":{"type":"array","items":{"type":"object","properties":{"id":{"type":"string"},"note":{"type":"string"},"relevance":$REL},"required":["id","note","relevance"],"additionalProperties":false}}},"required":["items"],"additionalProperties":false}"""

        const val QUIZ_SCHEMA =
            """{"type":"object","properties":{"questions":{"type":"array","items":{"type":"object","properties":{"question":{"type":"string"},"options":{"type":"array","items":{"type":"string"}},"answer_index":{"type":"integer"},"explanation":{"type":"string"},"source_id":{"type":"string"},"topic":{"type":"string"}},"required":["question","options","answer_index","explanation","source_id","topic"],"additionalProperties":false}}},"required":["questions"],"additionalProperties":false}"""
    }
}
