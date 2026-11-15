package app.dcrux.data

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
