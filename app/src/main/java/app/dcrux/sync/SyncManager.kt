package app.dcrux.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.dcrux.Graph
import app.dcrux.data.Channel
import app.dcrux.data.Dates
import app.dcrux.graph
import app.dcrux.net.Sources
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.TimeUnit

data class SyncState(val running: Boolean = false, val message: String = "", val error: String? = null, val finishedAt: Long = 0)

data class AddReport(val added: List<String>, val failed: List<Pair<String, String>>)

class SyncManager(private val g: Graph) {
    val state = MutableStateFlow(SyncState())
    private val mutex = Mutex()

    fun syncNow() {
        g.scope.launch { run() }
    }

    private suspend fun seedDefault() {
        if (g.prefs.get().seeded) return
        g.db.channels().insert(
            Channel(
                id = DEFAULT_CHANNEL_ID, title = "UPSC Wallah", url = DEFAULT_LINK,
                addedAt = System.currentTimeMillis(), isDefault = true,
            )
        )
        g.prefs.setSeeded(true)
        // Best effort: also pull in the exact video the default link points at, and the channel avatar.
        runCatching {
            val r = g.youtube.resolve(DEFAULT_LINK)
            r.video?.let { g.db.videos().insertAll(listOf(it)) }
            g.db.channels().touch(r.channelId, r.channelTitle, r.avatar, 0)
        }
    }

    /** One full sync: channels, papers, then AI relevance. Returns false when nothing could be reached. */
    suspend fun run(): Boolean {
        if (!mutex.tryLock()) return true
        try {
            state.value = SyncState(running = true, message = "Starting")
            seedDefault()
            val errors = mutableListOf<String>()
            var ok = 0

            for (ch in g.db.channels().all()) {
                state.value = SyncState(running = true, message = "Syncing ${ch.title}")
                try {
                    val feed = g.youtube.fetchFeed(ch.id)
                    val inserted = g.db.videos().insertAll(feed.videos)
                    feed.videos.forEach { g.db.videos().updateMeta(it.id, it.title, it.description) }
                    if (feed.approximateDates) {
                        // Dates read as "2d ago" are firmed up for the newest few videos that are new to the app.
                        feed.videos.filterIndexed { i, _ -> inserted.getOrElse(i) { -1L } != -1L }.sortedByDescending { it.publishedAt }.take(6).forEach { v ->
                            g.youtube.publishedAt(v.id)?.let { g.db.videos().setPublishedAt(v.id, it) }
                        }
                    }
                    val avatar = if (ch.avatarUrl.isBlank()) runCatching { g.youtube.resolve(ch.url.ifBlank { "https://www.youtube.com/channel/${ch.id}" }).avatar }.getOrDefault("") else ""
                    g.db.channels().touch(ch.id, feed.title, avatar, System.currentTimeMillis())
                    ok++
                } catch (e: Exception) {
                    errors += "${ch.title}: ${if (e is java.io.IOException && e.message?.startsWith("YouTube") == true) e.message else "could not be reached"}"
                }
            }

            val settings = g.prefs.get()
            val sources = Sources.enabled(settings)
            for (src in sources) {
                state.value = SyncState(running = true, message = "Fetching ${src.name}")
                try {
                    g.db.news().insertAll(g.news.fetch(src))
                    ok++
                } catch (e: Exception) {
                    errors += "${src.name}: ${e.message ?: "failed"}"
                }
            }

            if (ok > 0 && settings.aiCurate && g.apiKey() != null) {
                state.value = SyncState(running = true, message = "Checking exam relevance")
                val today = Dates.dayKey()
                // The paper the user reads gets rated first, then the others.
                for (src in sources.sortedByDescending { it.id == settings.newsSource }) g.ai.curateNews(src.id, today, limit = 30)
                g.ai.rateNewVideos()
            }

            if (ok > 0) g.prefs.setLastSync(System.currentTimeMillis())
            state.value = SyncState(
                running = false, finishedAt = System.currentTimeMillis(),
                error = when {
                    ok == 0 && errors.isNotEmpty() -> "Could not sync. Check your connection."
                    errors.isNotEmpty() -> "Some sources failed: " + errors.take(3).joinToString(" · ")
                    else -> null
                },
            )
            return ok > 0
        } finally {
            mutex.unlock()
        }
    }

    /** Adds every YouTube link found in the pasted text and syncs them straight away. */
    suspend fun addLinks(text: String): AddReport {
        val links = g.youtube.extractLinks(text)
        if (links.isEmpty()) return AddReport(emptyList(), listOf(text.take(40) to "No YouTube link found"))
        val added = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, String>>()
        for (link in links) {
            try {
                val r = g.youtube.resolve(link)
                g.db.channels().insert(Channel(id = r.channelId, title = r.channelTitle, url = r.channelUrl, avatarUrl = r.avatar, addedAt = System.currentTimeMillis()))
                r.video?.let { g.db.videos().insertAll(listOf(it)) }
                runCatching {
                    val feed = g.youtube.fetchFeed(r.channelId)
                    g.db.videos().insertAll(feed.videos)
                    g.db.channels().touch(r.channelId, feed.title, r.avatar, System.currentTimeMillis())
                }
                added += r.channelTitle
            } catch (e: Exception) {
                failed += link to (e.message ?: "Could not open this link")
            }
        }
        if (added.isNotEmpty() && g.prefs.get().aiCurate) g.scope.launch { g.ai.rateNewVideos() }
        return AddReport(added.distinct(), failed)
    }

    /** Puts the default channel back and syncs. */
    fun restoreDefault() {
        g.scope.launch {
            g.prefs.setSeeded(false)
            run()
        }
    }

    suspend fun removeChannel(id: String) {
        g.db.videos().deleteForChannel(id)
        g.db.channels().delete(id)
    }

    companion object {
        const val DEFAULT_LINK = "https://www.youtube.com/live/mmIzRBELdMk"
        const val DEFAULT_CHANNEL_ID = "UCqOy6oOu6RPJNHYQ8f_Ybvg"
        private const val WORK = "dcrux-daily-sync"

        /** Daily background sync, early morning, whenever the phone has a connection. */
        fun schedule(context: Context, enabled: Boolean, wifiOnly: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(WORK)
                return
            }
            val request = PeriodicWorkRequestBuilder<SyncWorker>(24, TimeUnit.HOURS, 3, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
                .setInitialDelay(Dates.untilNext(6, 30), TimeUnit.MILLISECONDS)
                .build()
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        if (applicationContext.graph.sync.run()) Result.success() else if (runAttemptCount < 3) Result.retry() else Result.failure()
}
