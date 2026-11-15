package app.dcrux.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity(tableName = "channels")
data class Channel(
    @PrimaryKey val id: String,
    val title: String,
    val url: String,
    val avatarUrl: String = "",
    val addedAt: Long,
    val lastSyncAt: Long = 0,
    val isDefault: Boolean = false,
)

@Entity(tableName = "videos", indices = [Index("channelId"), Index("publishedAt")])
data class Video(
    @PrimaryKey val id: String,
    val channelId: String,
    val title: String,
    val description: String = "",
    val publishedAt: Long,
    val isShort: Boolean = false,
    /** Markdown written by the model; null until the user asks for it. */
    val summary: String? = null,
    val detailed: String? = null,
    /** JSON list of [ExamRelevance]. */
    val relevance: String? = null,
    /** What the AI text was based on: "captions" or "description". */
    val basis: String? = null,
    val aiModel: String? = null,
) {
    val thumbUrl: String get() = "https://i.ytimg.com/vi/$id/hqdefault.jpg"
    val watchUrl: String get() = "https://www.youtube.com/watch?v=$id"
}

/**
 * A headline index entry. Only what the publisher's feed exposes (headline, short
 * snippet, link) is kept; the article itself is always read live from the publisher.
 */
@Entity(tableName = "news", indices = [Index("sourceId"), Index("dayKey"), Index("publishedAt")])
data class NewsItem(
    @PrimaryKey val id: String,
    val sourceId: String,
    val section: String,
    val title: String,
    val snippet: String,
    val link: String,
    val publishedAt: Long,
    val dayKey: String,
    val relevance: String? = null,
    /** One AI line on why it matters, written only from the headline and snippet. */
    val note: String? = null,
    val topScore: Int = -1,
)

@Entity(tableName = "tests")
data class TestRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    /** AES-GCM encrypted JSON of [TestResult]; the key never leaves the Android Keystore. */
    val payload: String,
)

/** A question the user asked about one news item, with the answer it got. Kept until the app is reset. */
@Entity(tableName = "asks", indices = [Index("articleId")])
data class Ask(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val articleId: String,
    val question: String,
    val answer: String,
    val askedAt: Long,
    /** The model's own summary of how it reasoned, shown on request. */
    val thinking: String = "",
    /** Suggested next questions, one per line. */
    val followups: String = "",
    /** The title of the article or video the question was asked on, for the history page. */
    @ColumnInfo(defaultValue = "") val topic: String = "",
)

@Serializable
data class ExamRelevance(val exam: String, val score: Int, val note: String = "")

@Serializable
data class QuizQuestion(
    val q: String,
    val options: List<String>,
    val answer: Int,
    val explanation: String = "",
    val sourceId: String = "",
    val sourceTitle: String = "",
    val sourceLink: String = "",
    val topic: String = "",
    /** How likely this theme is to be asked in the chosen exam, out of 100; -1 when not rated. */
    val relevance: Int = -1,
)

/** One descriptive (Mains) question, written from a news item of the chosen day. */
@Serializable
data class MainsQuestion(
    val q: String,
    val marks: Int,
    val words: Int,
    val topic: String = "",
    val paper: String = "",
    /** What the directive word asks the candidate to do, in one line. */
    val demand: String = "",
    val relevance: Int = -1,
    val sourceId: String = "",
    val sourceTitle: String = "",
    val sourceLink: String = "",
    /** The headline and feed snippet the question was written from, given to the evaluator as context. */
    val context: String = "",
)

@Serializable
data class MainsCriterion(val name: String, val score: Float, val outOf: Float, val comment: String = "")

@Serializable
data class MainsError(val written: String, val issue: String)

/**
 * One section of the answer as the examiner marked it: the student's own words, a tick, half-tick
 * or cross ([mark] is "good", "partial", "wrong" or "weak"), and the remark for the margin.
 */
@Serializable
data class MainsMark(val page: Int = 1, val section: String = "", val text: String = "", val mark: String = "partial", val remark: String = "")

/** What the evaluator read on the pages and how it marked it. */
@Serializable
data class MainsEval(
    val legibility: Int = 0,
    val isAnswer: Boolean = true,
    val problem: String = "",
    val transcript: String = "",
    val wordCount: Int = 0,
    val marks: Float = 0f,
    val breakdown: List<MainsCriterion> = emptyList(),
    val verdict: String = "",
    val strengths: List<String> = emptyList(),
    val improvements: List<String> = emptyList(),
    val missing: List<String> = emptyList(),
    val errors: List<MainsError> = emptyList(),
    val structure: String = "",
    val outline: List<String> = emptyList(),
    val examTips: List<String> = emptyList(),
    val timeAdvice: String = "",
    val model: String = "",
    /** The marked copy: every section of the answer in the order written, each with its mark and remark. */
    val marked: List<MainsMark> = emptyList(),
)

@Serializable
data class MainsAnswer(
    val question: MainsQuestion,
    /** Time spent writing, as recorded by the question's timer. */
    val seconds: Int = 0,
    val pages: Int = 0,
    val eval: MainsEval? = null,
    /** Why there is no evaluation (not attempted, or the evaluation failed). */
    val note: String = "",
)

@Serializable
data class TestResult(
    val title: String,
    val exam: String,
    val difficulty: String,
    val dayKey: String,
    val questions: List<QuizQuestion>,
    val answers: List<Int>,
    val correct: Int,
    val total: Int,
    val stars: Int,
    val durationSec: Int,
    val takenAt: Long,
    val model: String = "",
    /** "prelims" (multiple choice) or "mains" (handwritten answers, evaluated). */
    val kind: String = "prelims",
    val mains: List<MainsAnswer> = emptyList(),
    /** Mains marks; for Prelims the score is [correct] of [total]. */
    val marks: Float = 0f,
    val maxMarks: Float = 0f,
    /** How many times this test has been taken; the stored result is the best one. */
    val attempts: Int = 1,
) {
    val isMains: Boolean get() = kind == "mains"
    val percent: Int get() = if (isMains) (if (maxMarks > 0) (marks * 100 / maxMarks).toInt() else 0) else (if (total > 0) correct * 100 / total else 0)
    val scoreText: String get() = if (isMains) "${fmtMarks(marks)}/${fmtMarks(maxMarks)}" else "$correct/$total"
}

fun fmtMarks(v: Float): String = if (v % 1f == 0f) v.toInt().toString() else "%.1f".format(v)

/** An unfinished Mains attempt, kept (encrypted) so that a phone that closes the app to open the camera loses nothing. */
@Serializable
data class MainsDraft(
    val title: String,
    val exam: String,
    val difficulty: String,
    val dayKey: String,
    val questions: List<MainsQuestion>,
    val seconds: List<Int>,
    /** File names of the page photos for each question, inside the app's private folder. */
    val pages: List<List<String>>,
    /** Which questions have had their timer stopped by the student. */
    val stopped: List<Boolean>,
    val startedAt: Long,
    val current: Int = 0,
    val pendingCapture: String = "",
    val pendingFor: Int = -1,
    val retakeOf: Long = 0,
    /** The question whose clock is running, and when it was started (wall-clock, so it keeps time with the screen off). */
    val runningIndex: Int = -1,
    val runningSince: Long = 0,
) {
    fun elapsed(index: Int, now: Long = System.currentTimeMillis()): Int =
        seconds.getOrElse(index) { 0 } + if (runningIndex == index && runningSince > 0) ((now - runningSince) / 1000).toInt().coerceAtLeast(0) else 0
}

@Serializable
data class Place(val name: String, val lat: Double, val lon: Double, val region: String = "")

@Serializable
data class Weather(
    val at: Long,
    val temp: Double,
    val feels: Double,
    val code: Int,
    val max: Double,
    val min: Double,
    val rain: Double,
    val uv: Double,
    val dayCode: Int,
    val aqi: Int = -1,
    val pm25: Double = -1.0,
    /** 1 between sunrise and sunset at the place, 0 at night, -1 when unknown. */
    val isDay: Int = -1,
    val pm10: Double = -1.0,
    /** Where [aqi] comes from: "cpcb" (official monitors) or "estimate" (worked out from modelled particle levels). */
    val aqiSource: String = "",
    /** With official monitors: how many, the worst one and its index, the day's peak, and when they last reported. */
    val aqiStations: Int = 0,
    val aqiWorstStation: String = "",
    val aqiWorst: Int = -1,
    val aqiPeak: Int = -1,
    val aqiUpdated: String = "",
    /** When the official reading was fetched; the monitors report hourly, so it is reused for most of an hour. */
    val aqiAt: Long = 0,
)

@Serializable
data class ModelInfo(
    val id: String,
    val name: String,
    val maxTokens: Int = 16000,
    val structured: Boolean = true,
    val efforts: List<String> = emptyList(),
    val fallbacks: Boolean = false,
)

@Serializable
data class CustomSource(val id: String, val name: String, val url: String)
