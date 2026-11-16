package app.dcrux.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

// Search. A query is split into words (see SearchTerms); a row is a result when enough of them appear in it,
// in any order, and results with more of the words, and more of them in the title, come first. A date in the
// query ("14 nov") counts as one more word: it is matched by the day the item was published or by the date
// written in its title. Each word is a parameter; unused ones are passed empty and count for nothing.
private const val V_TEXT = "(v.title || ' ' || IFNULL(c.title, '') || ' ' || IFNULL(v.description, '') || ' ' || IFNULL(v.summary, '') || ' ' || IFNULL(v.detailed, ''))"
private const val V_M1 = "(:w1 != '' AND $V_TEXT LIKE '%' || :w1 || '%')"
private const val V_T1 = "(:w1 != '' AND v.title LIKE '%' || :w1 || '%')"
private const val V_M2 = "(:w2 != '' AND $V_TEXT LIKE '%' || :w2 || '%')"
private const val V_T2 = "(:w2 != '' AND v.title LIKE '%' || :w2 || '%')"
private const val V_M3 = "(:w3 != '' AND $V_TEXT LIKE '%' || :w3 || '%')"
private const val V_T3 = "(:w3 != '' AND v.title LIKE '%' || :w3 || '%')"
private const val V_M4 = "(:w4 != '' AND $V_TEXT LIKE '%' || :w4 || '%')"
private const val V_T4 = "(:w4 != '' AND v.title LIKE '%' || :w4 || '%')"
private const val V_M5 = "(:w5 != '' AND $V_TEXT LIKE '%' || :w5 || '%')"
private const val V_T5 = "(:w5 != '' AND v.title LIKE '%' || :w5 || '%')"
private const val V_M6 = "(:w6 != '' AND $V_TEXT LIKE '%' || :w6 || '%')"
private const val V_T6 = "(:w6 != '' AND v.title LIKE '%' || :w6 || '%')"
private const val V_TD = "(:dated = 1 AND (' ' || v.title || ' ') GLOB '*[^0-9]' || :day || '[^0-9]*' AND v.title LIKE '%' || :month || '%')"
private const val V_MD = "(:dated = 1 AND ((v.publishedAt >= :from AND v.publishedAt < :to) OR $V_TD))"
private const val V_MATCHED = "(" + V_M1 + " + " + V_M2 + " + " + V_M3 + " + " + V_M4 + " + " + V_M5 + " + " + V_M6 + " + " + V_MD + ")"
private const val V_TITLED = "(" + V_T1 + " + " + V_T2 + " + " + V_T3 + " + " + V_T4 + " + " + V_T5 + " + " + V_T6 + " + " + V_TD + ")"
private const val N_TEXT = "(title || ' ' || IFNULL(snippet, '') || ' ' || IFNULL(note, '') || ' ' || IFNULL(section, ''))"
private const val N_M1 = "(:w1 != '' AND $N_TEXT LIKE '%' || :w1 || '%')"
private const val N_T1 = "(:w1 != '' AND title LIKE '%' || :w1 || '%')"
private const val N_M2 = "(:w2 != '' AND $N_TEXT LIKE '%' || :w2 || '%')"
private const val N_T2 = "(:w2 != '' AND title LIKE '%' || :w2 || '%')"
private const val N_M3 = "(:w3 != '' AND $N_TEXT LIKE '%' || :w3 || '%')"
private const val N_T3 = "(:w3 != '' AND title LIKE '%' || :w3 || '%')"
private const val N_M4 = "(:w4 != '' AND $N_TEXT LIKE '%' || :w4 || '%')"
private const val N_T4 = "(:w4 != '' AND title LIKE '%' || :w4 || '%')"
private const val N_M5 = "(:w5 != '' AND $N_TEXT LIKE '%' || :w5 || '%')"
private const val N_T5 = "(:w5 != '' AND title LIKE '%' || :w5 || '%')"
private const val N_M6 = "(:w6 != '' AND $N_TEXT LIKE '%' || :w6 || '%')"
private const val N_T6 = "(:w6 != '' AND title LIKE '%' || :w6 || '%')"
private const val N_TD = "(:dated = 1 AND (' ' || title || ' ') GLOB '*[^0-9]' || :day || '[^0-9]*' AND title LIKE '%' || :month || '%')"
private const val N_MD = "(:dated = 1 AND ((publishedAt >= :from AND publishedAt < :to) OR $N_TD))"
private const val N_MATCHED = "(" + N_M1 + " + " + N_M2 + " + " + N_M3 + " + " + N_M4 + " + " + N_M5 + " + " + N_M6 + " + " + N_MD + ")"
private const val N_TITLED = "(" + N_T1 + " + " + N_T2 + " + " + N_T3 + " + " + N_T4 + " + " + N_T5 + " + " + N_T6 + " + " + N_TD + ")"

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

    /** Every video that has notes or a score: what is worth carrying to another device. */
    @Query("SELECT * FROM videos WHERE summary IS NOT NULL OR detailed IS NOT NULL")
    suspend fun withNotes(): List<Video>

    @Query("SELECT * FROM videos WHERE relevance IS NULL ORDER BY publishedAt DESC LIMIT :n")
    suspend fun unrated(n: Int): List<Video>

    @Query("SELECT * FROM videos WHERE publishedAt >= :from AND publishedAt < :to AND summary IS NOT NULL ORDER BY publishedAt DESC")
    suspend fun summarisedBetween(from: Long, to: Long): List<Video>

    @Query(
        "SELECT v.* FROM videos v LEFT JOIN channels c ON c.id = v.channelId WHERE " + V_MATCHED + " >= :need " +
            "ORDER BY " + V_MATCHED + " DESC, " + V_TITLED + " DESC, v.publishedAt DESC LIMIT :limit"
    )
    suspend fun find(w1: String, w2: String, w3: String, w4: String, w5: String, w6: String, dated: Int, from: Long, to: Long, day: String, month: String, need: Int, limit: Int): List<Video>

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
        "SELECT * FROM news WHERE " + N_MATCHED + " >= :need " +
            "ORDER BY " + N_MATCHED + " DESC, " + N_TITLED + " DESC, publishedAt DESC LIMIT :limit OFFSET :offset"
    )
    suspend fun find(w1: String, w2: String, w3: String, w4: String, w5: String, w6: String, dated: Int, from: Long, to: Long, day: String, month: String, need: Int, limit: Int, offset: Int): List<NewsItem>

    @Query("SELECT COUNT(*) FROM news WHERE " + N_MATCHED + " >= :need")
    suspend fun findCount(w1: String, w2: String, w3: String, w4: String, w5: String, w6: String, dated: Int, from: Long, to: Long, day: String, month: String, need: Int): Int

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

    @Query("SELECT * FROM tests")
    suspend fun all(): List<TestRecord>

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

    @Query("SELECT * FROM asks")
    suspend fun all(): List<Ask>

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

suspend fun VideoDao.search(t: SearchTerms, limit: Int): List<Video> =
    if (t.blank) emptyList() else find(t.word(0), t.word(1), t.word(2), t.word(3), t.word(4), t.word(5), if (t.dated) 1 else 0, t.from, t.to, t.day, t.month, t.need, limit)

suspend fun NewsDao.search(t: SearchTerms, limit: Int, offset: Int): List<NewsItem> =
    if (t.blank) emptyList() else find(t.word(0), t.word(1), t.word(2), t.word(3), t.word(4), t.word(5), if (t.dated) 1 else 0, t.from, t.to, t.day, t.month, t.need, limit, offset)

suspend fun NewsDao.searchCount(t: SearchTerms): Int =
    if (t.blank) 0 else findCount(t.word(0), t.word(1), t.word(2), t.word(3), t.word(4), t.word(5), if (t.dated) 1 else 0, t.from, t.to, t.day, t.month, t.need)
