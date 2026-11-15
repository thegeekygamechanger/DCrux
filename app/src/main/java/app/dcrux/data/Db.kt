package app.dcrux.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface ChannelDao {
    /** Used by "Reset app". */
    @Query("DELETE FROM channels")
    suspend fun clear()

    @Query("SELECT * FROM channels ORDER BY isDefault DESC, addedAt ASC")
    fun observeAll(): Flow<List<Channel>>

    @Query("SELECT * FROM channels ORDER BY isDefault DESC, addedAt ASC")
    suspend fun all(): List<Channel>

    @Query("SELECT * FROM channels WHERE id = :id")
    suspend fun get(id: String): Channel?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(c: Channel): Long

    @Query(
        "UPDATE channels SET title = CASE WHEN :title = '' THEN title ELSE :title END, " +
            "avatarUrl = CASE WHEN :avatar = '' THEN avatarUrl ELSE :avatar END, lastSyncAt = :at WHERE id = :id"
    )
    suspend fun touch(id: String, title: String, avatar: String, at: Long)

    @Query("DELETE FROM channels WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface VideoDao {
    /** Used by "Reset app". */
    @Query("DELETE FROM videos")
    suspend fun clear()

    @Query(
        "SELECT * FROM videos WHERE channelId = :ch AND (:shorts = 1 OR isShort = 0) " +
            "ORDER BY publishedAt DESC LIMIT :limit OFFSET :offset"
    )
    fun page(ch: String, shorts: Int, limit: Int, offset: Int): Flow<List<Video>>

    @Query("SELECT COUNT(*) FROM videos WHERE channelId = :ch AND (:shorts = 1 OR isShort = 0)")
    fun count(ch: String, shorts: Int): Flow<Int>

    @Query("SELECT * FROM videos WHERE id = :id")
    fun observe(id: String): Flow<Video?>

    @Query("SELECT * FROM videos WHERE id = :id")
    suspend fun get(id: String): Video?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(v: List<Video>): List<Long>

    @Query(
        "UPDATE videos SET title = :title, description = CASE WHEN :desc = '' THEN description ELSE :desc END WHERE id = :id"
    )
    suspend fun updateMeta(id: String, title: String, desc: String)

    @Query("UPDATE videos SET publishedAt = :at WHERE id = :id")
    suspend fun setPublishedAt(id: String, at: Long)

    @Query("UPDATE videos SET summary = :text, basis = :basis, aiModel = :model WHERE id = :id")
    suspend fun setSummary(id: String, text: String, basis: String, model: String)

    @Query("UPDATE videos SET detailed = :text, basis = :basis, aiModel = :model WHERE id = :id")
    suspend fun setDetailed(id: String, text: String, basis: String, model: String)

    @Query("UPDATE videos SET relevance = :relevance WHERE id = :id")
    suspend fun setRelevance(id: String, relevance: String)

    @Query("SELECT * FROM videos WHERE relevance IS NULL ORDER BY publishedAt DESC LIMIT :n")
    suspend fun unrated(n: Int): List<Video>

    @Query("SELECT * FROM videos WHERE publishedAt >= :from AND publishedAt < :to AND summary IS NOT NULL ORDER BY publishedAt DESC")
    suspend fun summarisedBetween(from: Long, to: Long): List<Video>

    @Query(
        "SELECT * FROM videos WHERE title LIKE '%' || :q || '%' OR summary LIKE '%' || :q || '%' OR detailed LIKE '%' || :q || '%' " +
            "ORDER BY publishedAt DESC LIMIT :limit"
    )
    suspend fun search(q: String, limit: Int): List<Video>

    @Query("DELETE FROM videos WHERE channelId = :ch")
    suspend fun deleteForChannel(ch: String)
}

@Dao
interface NewsDao {
    /** Used by "Reset app". */
    @Query("DELETE FROM news")
    suspend fun clear()

    @Query(
        "SELECT * FROM news WHERE sourceId = :src AND dayKey = :day AND topScore >= :minScore " +
            "ORDER BY CASE WHEN :byScore = 1 THEN topScore ELSE 0 END DESC, publishedAt DESC LIMIT :limit OFFSET :offset"
    )
    fun page(src: String, day: String, minScore: Int, byScore: Int, limit: Int, offset: Int): Flow<List<NewsItem>>

    @Query("SELECT COUNT(*) FROM news WHERE sourceId = :src AND dayKey = :day AND topScore >= :minScore")
    fun count(src: String, day: String, minScore: Int): Flow<Int>

    @Query("SELECT COUNT(*) FROM news WHERE sourceId = :src AND dayKey = :day AND relevance IS NULL")
    fun unratedCount(src: String, day: String): Flow<Int>

    @Query("SELECT DISTINCT dayKey FROM news WHERE sourceId = :src ORDER BY dayKey DESC")
    fun days(src: String): Flow<List<String>>

    @Query("SELECT DISTINCT dayKey FROM news ORDER BY dayKey DESC LIMIT 30")
    suspend fun recentDays(): List<String>

    @Query("SELECT * FROM news WHERE id = :id")
    fun observe(id: String): Flow<NewsItem?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<NewsItem>): List<Long>

    @Query("SELECT * FROM news WHERE sourceId = :src AND dayKey = :day AND relevance IS NULL ORDER BY publishedAt DESC LIMIT :n")
    suspend fun unrated(src: String, day: String, n: Int): List<NewsItem>

    @Query("UPDATE news SET relevance = :relevance, note = :note, topScore = :top WHERE id = :id")
    suspend fun setRelevance(id: String, relevance: String, note: String, top: Int)

    @Query("SELECT * FROM news WHERE dayKey = :day AND sourceId IN (:srcs) ORDER BY topScore DESC, publishedAt DESC LIMIT :n")
    suspend fun forDay(day: String, srcs: List<String>, n: Int): List<NewsItem>

    @Query(
        "SELECT * FROM news WHERE title LIKE '%' || :q || '%' OR snippet LIKE '%' || :q || '%' OR note LIKE '%' || :q || '%' " +
            "ORDER BY publishedAt DESC LIMIT :limit OFFSET :offset"
    )
    suspend fun search(q: String, limit: Int, offset: Int): List<NewsItem>

    @Query("SELECT COUNT(*) FROM news WHERE title LIKE '%' || :q || '%' OR snippet LIKE '%' || :q || '%' OR note LIKE '%' || :q || '%'")
    suspend fun searchCount(q: String): Int

    @Query("DELETE FROM news WHERE sourceId = :src")
    suspend fun deleteForSource(src: String)
}

@Dao
interface TestDao {
    /** Used by "Reset app". */
    @Query("DELETE FROM tests")
    suspend fun clear()

    @Insert
    suspend fun insert(t: TestRecord): Long

    @Query("SELECT * FROM tests ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<TestRecord>>

    @Query("SELECT * FROM tests WHERE id = :id")
    suspend fun get(id: Long): TestRecord?

    @Query("UPDATE tests SET payload = :payload WHERE id = :id")
    suspend fun setPayload(id: Long, payload: String)

    @Query("DELETE FROM tests WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface AskDao {
    /** Used by "Reset app". */
    @Query("DELETE FROM asks")
    suspend fun clear()

    @Query("SELECT * FROM asks WHERE articleId = :articleId ORDER BY askedAt ASC")
    fun forArticle(articleId: String): Flow<List<Ask>>

    @Query("SELECT COUNT(*) FROM asks WHERE articleId = :articleId")
    fun count(articleId: String): Flow<Int>

    @Query("SELECT * FROM asks WHERE articleId = :articleId ORDER BY askedAt DESC LIMIT :n")
    suspend fun latest(articleId: String, n: Int): List<Ask>

    @Query("SELECT * FROM asks ORDER BY askedAt DESC")
    fun observeAll(): Flow<List<Ask>>

    @Insert
    suspend fun insert(a: Ask): Long

    @Query("DELETE FROM asks WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(entities = [Channel::class, Video::class, NewsItem::class, TestRecord::class, Ask::class], version = 3, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun channels(): ChannelDao
    abstract fun videos(): VideoDao
    abstract fun news(): NewsDao
    abstract fun tests(): TestDao
    abstract fun asks(): AskDao
}

/** Empties every table: "Reset app". (Room's own clearAllTables exists only on Android.) */
suspend fun AppDb.wipe() {
    channels().clear(); videos().clear(); news().clear(); tests().clear(); asks().clear()
}

/**
 * Every change of the database's shape, as the SQL that makes it, keyed by the version it starts
 * from. Each platform wraps these in its own migration type, so the steps are written once.
 */
val DB_MIGRATIONS: Map<Int, List<String>> = mapOf(
    // 1 -> 2 adds the questions asked about articles. Nothing already on the device is touched.
    1 to listOf(
        "CREATE TABLE IF NOT EXISTS `asks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `articleId` TEXT NOT NULL, " +
            "`question` TEXT NOT NULL, `answer` TEXT NOT NULL, `askedAt` INTEGER NOT NULL, `thinking` TEXT NOT NULL, `followups` TEXT NOT NULL)",
        "CREATE INDEX IF NOT EXISTS `index_asks_articleId` ON `asks` (`articleId`)",
    ),
    // 2 -> 3 records, with each question, the title of what it was asked on. Existing questions keep an empty title.
    2 to listOf("ALTER TABLE `asks` ADD COLUMN `topic` TEXT NOT NULL DEFAULT ''"),
)
