package app.dcrux.sync

import app.dcrux.Graph
import app.dcrux.data.AppJson
import app.dcrux.data.Ask
import app.dcrux.data.Channel
import app.dcrux.data.CustomSource
import app.dcrux.data.Place
import app.dcrux.data.Settings
import app.dcrux.data.TestRecord
import app.dcrux.data.TestResult
import app.dcrux.data.Video
import app.dcrux.mains.MainsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// What travels between a person's devices. Headlines are not here (each device reads the papers
// itself), nor are PDFs or page photos (they are never stored anywhere).

@Serializable
data class CloudChannel(val id: String, val title: String, val url: String, val avatarUrl: String = "", val addedAt: Long = 0, val isDefault: Boolean = false)

@Serializable
data class CloudNote(
    val id: String, val channelId: String, val title: String, val publishedAt: Long, val isShort: Boolean = false,
    val summary: String? = null, val detailed: String? = null, val relevance: String? = null,
    val basis: String? = null, val aiModel: String? = null, val at: Long = 0,
)

@Serializable
data class CloudAsk(val articleId: String, val question: String, val answer: String, val askedAt: Long, val followups: String = "", val topic: String = "")

@Serializable
data class CloudTest(val createdAt: Long, val result: TestResult)

@Serializable
data class CloudSettings(
    val at: Long = 0, val exams: Set<String> = emptySet(), val language: String = "", val enabledSources: Set<String> = emptySet(),
    val customSources: List<CustomSource> = emptyList(), val newsSource: String = "", val preferDetailed: Boolean = false,
    val showShorts: Boolean = false, val place: Place? = null,
)

/**
 * Everything one person's devices share. [gone] remembers what was deleted and when ("c:" a channel,
 * "a:" a question, "t:" a test), so a deletion on one device is not undone by another that still has the item.
 */
@Serializable
data class CloudState(
    val v: Int = 1, val settings: CloudSettings = CloudSettings(), val channels: List<CloudChannel> = emptyList(),
    val notes: List<CloudNote> = emptyList(), val asks: List<CloudAsk> = emptyList(), val tests: List<CloudTest> = emptyList(),
    val gone: Map<String, Long> = emptyMap(),
)

/** What this device remembers of the last exchange, to tell "deleted here" from "new there" and to date its own changes. */
@Serializable
private data class SyncMemory(
    val version: Int = 0, val keys: Set<String> = emptySet(), val settingsHash: Int = 0, val settingsAt: Long = 0,
    val noteHash: Map<String, Int> = emptyMap(), val noteAt: Map<String, Long> = emptyMap(),
    /** Deletions this device knows of. The relay keeps nothing on disk, so the devices are the ones that remember. */
    val gone: Map<String, Long> = emptyMap(),
    /** The service's mark on the copy this device last saw. With the version it says exactly which copy that was. */
    val mark: String = "",
)

private class ErasedElsewhere : Exception()

data class CloudStatus(val on: Boolean = false, val syncing: Boolean = false, val error: String? = null)

/** How entering a short pairing code ended. */
enum class ShortJoin { Joined, Wrong, Busy, Offline }

/**
 * Sync across a person's own devices, with no account. One device makes a long secret key and the
 * others receive it, either by scanning its QR code or by typing a 4-character code that is good once
 * and for a few minutes (see [offerShortCodes]). The key never leaves the devices unprotected: a key derived from it encrypts everything
 * before upload (AES-256-GCM), so the service only ever handles data it cannot read (and keeps it in memory
 * only, never on disk, while passing it between the devices), under an address that
 * is also derived from the code. Devices merge rather than overwrite: the union of what each has,
 * the newer of two versions of the same thing, and deletions remembered so they stay deleted.
 */
class CloudSync(private val g: Graph) {
    val status = MutableStateFlow(CloudStatus(on = false))
    private val lock = Mutex()
    private var loop: Job? = null
    private var soon: Job? = null
    private var listener: Job? = null
    private val remoteFile: File get() = File(g.platform.filesDir, "cloud_remote")
    private val memoryFile: File get() = File(g.platform.filesDir, "cloud_memory")

    /** True when this build can sync at all (it needs the DCrux service). */
    val available: Boolean get() = g.usesService

    fun code(): String? = g.secure.syncSecret()?.let(::pretty)

    /**
     * Begins syncing: once now, and from then on whenever something changes, here or on another device.
     * Nothing is polled. While the app is in use it keeps one idle connection open on which the service
     * says "your data changed" the moment another device saves; only then is anything downloaded. A change
     * made here is uploaded within a second of it happening. A slow safety sync covers anything missed.
     */
    fun start() {
        status.value = CloudStatus(on = g.secure.syncSecret() != null)
        if (loop != null || !available) return
        loop = g.scope.launch {
            while (true) {
                if (g.secure.syncSecret() != null) syncNow()
                delay(20 * 60_000L)
            }
        }
    }

    /**
     * Tells sync whether the app is in use. In use, it syncs at once (so opening the app always shows the
     * latest) and listens for changes from other devices; put away, it stops listening and costs nothing.
     */
    fun setActive(on: Boolean) {
        if (!available) return
        listener?.cancel()
        listener = null
        if (!on) return
        listener = g.scope.launch {
            if (g.secure.syncSecret() != null) syncNow()
            var pause = 5_000L
            while (true) {
                val secret = g.secure.syncSecret()
                if (secret == null) { delay(4_000); continue }
                val have = loadMemory().let { m -> if (m.mark.isEmpty()) "${m.version}" else "${m.version}.${m.mark}" }
                val answer = g.claude.serviceCall("GET", "/v1/sync/${Keys(secret).id}/wait?have=$have")
                when (answer?.first) {
                    200 -> {
                        // Something newer is stored (or this device has never synced): fetch and merge it.
                        syncNow()
                        if (status.value.error != null) delay(pause.also { pause = minOf(pause * 2, 120_000L) }) else pause = 5_000L
                    }
                    204 -> pause = 5_000L                                  // nothing changed in the last minute: ask again
                    null -> delay(pause.also { pause = minOf(pause * 2, 120_000L) })   // offline: back off
                    else -> delay(5 * 60_000L)                              // the service cannot do this right now
                }
            }
        }
    }

    /** Asks for a sync shortly, after something worth sharing has changed; several calls in a row make one sync. */
    fun syncSoon() {
        if (g.secure.syncSecret() == null) return
        soon?.cancel()
        soon = g.scope.launch { delay(700); syncNow() }
    }

    /** Turns sync on with a new code and uploads what this device has. Returns the code to enter on the other devices. */
    suspend fun enable(): String {
        val secret = newCode()
        g.secure.setSyncSecret(secret)
        forget()
        status.value = CloudStatus(on = true)
        syncNow()
        return pretty(secret)
    }

    /** Joins the devices that share [code]. False when the code is not the right shape. */
    suspend fun join(code: String): Boolean {
        val secret = normalise(code) ?: return false
        g.secure.setSyncSecret(secret)
        forget()
        status.value = CloudStatus(on = true)
        syncNow()
        return true
    }

    /** Stops syncing on this device; with [eraseCloud], also deletes the stored copy for every device. Nothing on this device is removed. */
    suspend fun disable(eraseCloud: Boolean) {
        val secret = g.secure.syncSecret()
        if (eraseCloud && secret != null) g.claude.serviceCall("DELETE", "/v1/sync/${Keys(secret).id}")
        g.secure.setSyncSecret(null)
        forget()
        g.prefs.setCloudLastSync(0)
        status.value = CloudStatus(on = false)
    }

    private fun forget() { runCatching { memoryFile.delete(); remoteFile.delete() } }

    // ------------------------------------------------------------------ pairing by a short code

    /**
     * Lets another device join by typing four characters, for as long as this is running (cancel it to stop).
     * [show] is given each code as it becomes valid, or null while none can be offered.
     *
     * Four characters are far too few to be a key, so they are not one. The two devices use the code to agree
     * a fresh strong key that only someone who knows the code can agree (SPEKE, a password-authenticated key
     * exchange), and the real sync key crosses locked with that. Whoever watches the exchange learns nothing
     * to test guesses against; whoever guesses gets one try, because a code is used up by a single attempt
     * and replaced. A code also lapses by itself after a few minutes.
     */
    suspend fun offerShortCodes(show: (String?) -> Unit) {
        var pause = 2_000L
        while (true) {
            val secret = g.secure.syncSecret() ?: return
            val code = newShort()
            val x = Pake.exponent()
            val a = Pake.raise(Pake.base(code), x)
            if (!Pake.usable(a)) continue
            val room = Pake.room(code)
            val made = g.claude.serviceCall("PUT", "/v1/pair/$room", """{"a":"${Pake.text(a)}"}""")
            if (made?.first == 409) { delay(50); continue }       // another device happens to be using that room: take another code
            val opened = made?.takeIf { it.first == 200 }?.let { runCatching { AppJson.parseToJsonElement(it.second).jsonObject }.getOrNull() }
            val token = opened?.get("token")?.jsonPrimitive?.content
            if (token == null) {
                show(null)
                delay(pause.also { pause = minOf(pause * 2, 60_000L) })
                continue
            }
            pause = 2_000L
            val until = System.currentTimeMillis() + ((opened["ttl"]?.jsonPrimitive?.intOrNull ?: 300) - 15) * 1000L
            show(code)
            var answered = false
            try {
                var theirs: BigInteger? = null
                while (theirs == null && System.currentTimeMillis() < until) {
                    val heard = g.claude.serviceCall("GET", "/v1/pair/$room/wait?token=$token")
                    when (heard?.first) {
                        200 -> theirs = runCatching { Pake.number(AppJson.parseToJsonElement(heard.second).jsonObject["b"]!!.jsonPrimitive.content) }.getOrNull() ?: break
                        204 -> Unit
                        null -> delay(3_000)
                        else -> break
                    }
                }
                if (theirs != null && Pake.usable(theirs)) {
                    val key = Pake.key(a, theirs, Pake.raise(theirs, x))
                    answered = g.claude.serviceCall("PUT", "/v1/pair/$room/answer?token=$token", """{"e":"${Pake.lock(key, secret)}"}""")?.first == 200
                }
            } finally {
                if (!answered) withContext(NonCancellable) { g.claude.serviceCall("DELETE", "/v1/pair/$room?token=$token") }
            }
        }
    }

    /** Joins the devices of whoever is showing [typed] right now. */
    suspend fun joinShort(typed: String): ShortJoin {
        val code = normaliseShort(typed) ?: return ShortJoin.Wrong
        val y = Pake.exponent()
        val b = Pake.raise(Pake.base(code), y)
        val got = g.claude.serviceCall("POST", "/v1/pair/${Pake.room(code)}/join", """{"b":"${Pake.text(b)}"}""") ?: return ShortJoin.Offline
        if (got.first == 429) return ShortJoin.Busy
        if (got.first != 200) return if (got.first in 500..599) ShortJoin.Offline else ShortJoin.Wrong
        val secret = runCatching {
            val o = AppJson.parseToJsonElement(got.second).jsonObject
            val a = Pake.number(o["a"]!!.jsonPrimitive.content)!!
            check(Pake.usable(a))
            Pake.unlock(Pake.key(a, b, Pake.raise(a, y)), o["e"]!!.jsonPrimitive.content)
        }.getOrNull() ?: return ShortJoin.Wrong      // the two keys differ: the code typed is not the code shown
        return if (join(secret)) ShortJoin.Joined else ShortJoin.Wrong
    }

    suspend fun syncNow() {
        val secret = g.secure.syncSecret() ?: return
        if (!lock.tryLock()) return
        try {
            status.value = CloudStatus(on = true, syncing = true)
            val error = withContext(Dispatchers.IO) { runCatching { exchange(Keys(secret)) }.exceptionOrNull() }
            if (error is ErasedElsewhere) {
                // Another of the person's devices ended sync for the whole group: stop here too. What is on this device stays.
                g.secure.setSyncSecret(null)
                forget()
                g.prefs.setCloudLastSync(0)
                status.value = CloudStatus(on = false, error = "Sync was turned off for all your devices from another one of them. Your data on this device is untouched.")
                return
            }
            if (error == null) g.prefs.setCloudLastSync(System.currentTimeMillis())
            status.value = CloudStatus(on = g.secure.syncSecret() != null, error = error?.let { it.message ?: "Sync did not finish" })
        } finally {
            lock.unlock()
        }
    }

    // ------------------------------------------------------------------ the exchange

    private suspend fun exchange(keys: Keys) {
        var memory = loadMemory()
        repeat(4) {
            // The stored copy as last seen is kept on this device (still encrypted), so when nothing changed on the
            // service only a few bytes come back instead of the whole of it.
            val cached = runCatching { remoteFile.takeIf { it.exists() }?.readText()?.split('\n', limit = 2)?.takeIf { it.size == 2 } }.getOrNull()
            val got = g.claude.serviceCall("GET", "/v1/sync/${keys.id}" + (cached?.let { "?have=${it[0]}" } ?: "")) ?: throw IllegalStateException("No connection to the sync service")
            // Which copy this is: its version, and the service's mark on it when it gave one ("version.mark").
            val (remote, held) = when (got.first) {
                304 -> (keys.open(cached!![1]) ?: throw IllegalStateException("The saved copy could not be read")) to cached[0]
                200 -> {
                    val o = AppJson.parseToJsonElement(got.second).jsonObject
                    if (o["erased"]?.jsonPrimitive?.content == "true") throw ErasedElsewhere()
                    val data = o["data"]?.jsonPrimitive?.content.orEmpty()
                    val state = keys.open(data) ?: throw IllegalStateException("This code does not open the stored data. Check the code.")
                    val v = "${o["version"]?.jsonPrimitive?.intOrNull ?: 0}" + (o["mark"]?.jsonPrimitive?.content?.let { ".$it" } ?: "")
                    runCatching { remoteFile.writeText("$v\n$data") }
                    state to v
                }
                404 -> CloudState() to "0"
                503 -> throw IllegalStateException("Sync is not available on the service right now")
                else -> throw IllegalStateException("The sync service answered ${got.first}")
            }
            val version = held.substringBefore('.').toIntOrNull() ?: 0
            var saved = held
            val now = System.currentTimeMillis()
            val first = memory.version == 0 && memory.keys.isEmpty()
            val local = snapshot(memory, now, adoptRemoteSettings = first && remote.settings.at > 0)
            // What this device had at the last exchange and no longer has was deleted here.
            val gone = remote.gone.toMutableMap()
            memory.gone.forEach { (k, at) -> gone[k] = maxOf(gone[k] ?: 0, at) }
            (memory.keys - keysOf(local)).forEach { k -> gone[k] = maxOf(gone[k] ?: 0, now) }
            val merged = merge(remote, local, gone.filterValues { now - it < 180L * 86_400_000L })
            apply(merged, local)
            if (merged != remote) {
                val sealed = keys.seal(merged)
                val put = g.claude.serviceCall("PUT", "/v1/sync/${keys.id}", """{"base":$version,"data":"$sealed"}""")
                    ?: throw IllegalStateException("No connection to the sync service")
                if (put.first == 409) { runCatching { remoteFile.delete() }; return@repeat }   // another device saved meanwhile: fetch, merge and try again
                if (put.first == 200) {
                    val o = runCatching { AppJson.parseToJsonElement(put.second).jsonObject }.getOrNull()
                    saved = "${o?.get("version")?.jsonPrimitive?.intOrNull ?: (version + 1)}" + (o?.get("mark")?.jsonPrimitive?.content?.let { ".$it" } ?: "")
                    runCatching { remoteFile.writeText("$saved\n$sealed") }
                }
                if (put.first != 200) throw IllegalStateException(if (put.first == 413) "There is too much to sync in one go" else "The sync service answered ${put.first}")
            }
            memory = SyncMemory(
                version = saved.substringBefore('.').toIntOrNull() ?: 0, mark = saved.substringAfter('.', ""), keys = keysOf(merged),
                settingsHash = merged.settings.copy(at = 0).hashCode(), settingsAt = merged.settings.at,
                noteHash = merged.notes.associate { it.id to it.copy(at = 0).hashCode() }, noteAt = merged.notes.associate { it.id to it.at },
                gone = merged.gone,
            )
            saveMemory(memory)
            return
        }
        throw IllegalStateException("Other devices kept saving at the same moment. It will try again shortly.")
    }

    private fun keyOf(a: CloudAsk) = "a:${a.articleId}|${a.askedAt}"
    private fun keysOf(s: CloudState): Set<String> =
        s.channels.map { "c:${it.id}" }.toSet() + s.asks.map(::keyOf) + s.tests.map { "t:${it.createdAt}" }

    private fun settingsOf(s: Settings, at: Long) = CloudSettings(at, s.exams, s.language, s.enabledSources, s.customSources, s.newsSource, s.preferDetailed, s.showShorts, s.place)

    /** This device's data in the shape that is shared. Changes made here since the last exchange are dated now. */
    private suspend fun snapshot(memory: SyncMemory, now: Long, adoptRemoteSettings: Boolean): CloudState {
        val prefs = g.prefs.get()
        val bare = settingsOf(prefs, 0)
        val settingsAt = when {
            adoptRemoteSettings -> 0                                   // a device joining takes the shared settings as they are
            bare.hashCode() != memory.settingsHash -> now
            else -> memory.settingsAt
        }
        val notes = g.db.videos().withNotes().map { v ->
            val n = CloudNote(v.id, v.channelId, v.title, v.publishedAt, v.isShort, v.summary, v.detailed, v.relevance, v.basis, v.aiModel)
            n.copy(at = if (n.hashCode() != memory.noteHash[v.id]) now else memory.noteAt[v.id] ?: now)
        }
        return CloudState(
            settings = bare.copy(at = settingsAt),
            channels = g.db.channels().all().map { CloudChannel(it.id, it.title, it.url, it.avatarUrl, it.addedAt, it.isDefault) },
            notes = notes,
            asks = g.db.asks().all().map { CloudAsk(it.articleId, it.question, it.answer, it.askedAt, it.followups, it.topic) },
            tests = g.db.tests().all().mapNotNull { r -> MainsStore.decode(g, r)?.let { CloudTest(r.createdAt, it) } },
        )
    }

    private fun merge(remote: CloudState, local: CloudState, gone: Map<String, Long>): CloudState {
        fun alive(key: String, madeAt: Long) = (gone[key] ?: -1) < madeAt
        val channels = (remote.channels + local.channels).distinctBy { it.id }.filter { alive("c:${it.id}", it.addedAt) }
        val asks = (remote.asks + local.asks).distinctBy(::keyOf).filter { alive(keyOf(it), it.askedAt) }
        // The same test on two devices: the one retaken more, or more recently, is the current one.
        val tests = (remote.tests + local.tests).groupBy { it.createdAt }.map { (_, same) ->
            same.maxWith(compareBy<CloudTest> { it.result.attempts }.thenBy { it.result.takenAt })
        }.filter { alive("t:${it.createdAt}", it.result.takenAt) }
        val theirs = remote.notes.associateBy { it.id }
        val ours = local.notes.associateBy { it.id }
        val notes = (theirs.keys + ours.keys).map { id ->
            val a = theirs[id]; val b = ours[id]
            if (a == null) b!! else if (b == null) a else {
                val newer = if (b.at >= a.at) b else a
                val older = if (newer === b) a else b
                newer.copy(
                    summary = newer.summary ?: older.summary, detailed = newer.detailed ?: older.detailed,
                    relevance = newer.relevance ?: older.relevance, basis = newer.basis ?: older.basis, aiModel = newer.aiModel ?: older.aiModel,
                )
            }
        }
        return CloudState(
            settings = if (local.settings.at > remote.settings.at) local.settings else remote.settings,
            channels = channels.sortedBy { it.id }, notes = notes.sortedBy { it.id }, asks = asks.sortedBy(::keyOf),
            tests = tests.sortedBy { it.createdAt }, gone = gone.toSortedMap(),
        )
    }

    /** Brings this device up to the merged state: adds what it lacks, updates what is newer elsewhere, removes what was deleted elsewhere. */
    private suspend fun apply(merged: CloudState, local: CloudState) {
        // Settings
        if (merged.settings.copy(at = 0) != local.settings.copy(at = 0) && merged.settings.at > 0) merged.settings.let { s ->
            if (s.exams.isNotEmpty()) g.prefs.setExams(s.exams)
            if (s.language.isNotBlank()) g.prefs.setLanguage(s.language)
            if (s.enabledSources.isNotEmpty()) g.prefs.setEnabledSources(s.enabledSources)
            g.prefs.setCustomSources(s.customSources)
            if (s.newsSource.isNotBlank()) g.prefs.setNewsSource(s.newsSource)
            g.prefs.setPreferDetailed(s.preferDetailed)
            g.prefs.setShowShorts(s.showShorts)
            if (s.place != local.settings.place) g.prefs.setPlace(s.place)
        }
        // Channels
        val haveChannels = local.channels.map { it.id }.toSet()
        val wantChannels = merged.channels.map { it.id }.toSet()
        merged.channels.filter { it.id !in haveChannels }.forEach { g.db.channels().insert(Channel(it.id, it.title, it.url, it.avatarUrl, it.addedAt, isDefault = it.isDefault)) }
        (haveChannels - wantChannels).forEach { g.sync.removeChannel(it) }
        // Questions asked
        val haveAsks = local.asks.map(::keyOf).toSet()
        val wantAsks = merged.asks.map(::keyOf).toSet()
        merged.asks.filter { keyOf(it) !in haveAsks }.forEach { g.db.asks().insert(Ask(articleId = it.articleId, question = it.question, answer = it.answer, askedAt = it.askedAt, followups = it.followups, topic = it.topic)) }
        if ((haveAsks - wantAsks).isNotEmpty()) g.db.asks().all().filter { keyOf(CloudAsk(it.articleId, it.question, it.answer, it.askedAt)) !in wantAsks }.forEach { g.db.asks().delete(it.id) }
        // Tests
        val rows = g.db.tests().all().associateBy { it.createdAt }
        val mine = local.tests.associateBy { it.createdAt }
        merged.tests.forEach { t ->
            val row = rows[t.createdAt]
            val payload by lazy { g.secure.encrypt(AppJson.encodeToString(t.result)) }
            if (row == null) g.db.tests().insert(TestRecord(createdAt = t.createdAt, payload = payload))
            else if (mine[t.createdAt]?.result != t.result) g.db.tests().setPayload(row.id, payload)
        }
        val wantTests = merged.tests.map { it.createdAt }.toSet()
        rows.values.filter { it.createdAt !in wantTests }.forEach { g.db.tests().delete(it.id) }
        // Video notes
        val mineNotes = local.notes.associateBy { it.id }
        merged.notes.forEach { n ->
            val have = mineNotes[n.id]
            if (have != null && have.copy(at = 0) == n.copy(at = 0)) return@forEach
            g.db.videos().insertAll(listOf(Video(id = n.id, channelId = n.channelId, title = n.title, publishedAt = n.publishedAt, isShort = n.isShort)))
            if (n.summary != null && n.summary != have?.summary) g.db.videos().setSummary(n.id, n.summary, n.basis ?: "captions", n.aiModel.orEmpty())
            if (n.detailed != null && n.detailed != have?.detailed) g.db.videos().setDetailed(n.id, n.detailed, n.basis ?: "captions", n.aiModel.orEmpty())
            if (n.relevance != null && n.relevance != have?.relevance) g.db.videos().setRelevance(n.id, n.relevance)
        }
    }

    private fun loadMemory(): SyncMemory = runCatching {
        memoryFile.takeIf { it.exists() }?.readText()?.let { g.secure.decrypt(it) }?.let { AppJson.decodeFromString<SyncMemory>(it) }
    }.getOrNull() ?: SyncMemory()

    private fun saveMemory(m: SyncMemory) { runCatching { memoryFile.writeText(g.secure.encrypt(AppJson.encodeToString(m))) } }

    // ------------------------------------------------------------------ the code and the keys made from it

    /** The address the data is stored under and the key that encrypts it, both derived from the pairing code and neither reversible to it. */
    private class Keys(secret: String) {
        private val master = hmac("dcrux-sync-v1".toByteArray(), secret.toByteArray())
        val id: String = hmac(master, "address".toByteArray()).joinToString("") { "%02x".format(it) }
        private val key = SecretKeySpec(hmac(master, "encryption".toByteArray()), "AES")

        fun seal(state: CloudState): String {
            val plain = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(AppJson.encodeToString(state).toByteArray()) } }.toByteArray()
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
            return (iv + cipher.doFinal(plain)).toByteString().base64()
        }

        fun open(data: String): CloudState? = runCatching {
            val raw = data.decodeBase64()!!.toByteArray()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, raw, 0, 12)) }
            val plain = GZIPInputStream(ByteArrayInputStream(cipher.doFinal(raw, 12, raw.size - 12))).use { it.readBytes() }
            AppJson.decodeFromString<CloudState>(String(plain))
        }.getOrNull()

        private companion object {
            fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)
        }
    }

    /** The arithmetic of pairing by a short code: SPEKE over the 2048-bit group of RFC 3526, with SHA-256 and AES-256-GCM. */
    private object Pake {
        // A safe prime: p and (p - 1) / 2 are both prime, so the squares form a group of prime order.
        val P = BigInteger(
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74" +
                "020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F1437" +
                "4FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
                "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF05" +
                "98DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB" +
                "9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
                "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF695581718" +
                "3995497CEA956AE515D2261898FA051015728E5A8AACAA68FFFFFFFFFFFFFFFF",
            16,
        )
        private val TWO = BigInteger.valueOf(2)

        private fun sha(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").also { d -> parts.forEach(d::update) }.digest()

        /** The room two devices meet in. Only 12 bits of a hash of the code, so the room does not give the code away. */
        fun room(code: String): String = sha("dcrux-pair-room|$code".toByteArray()).let { "%03x".format(((it[0].toInt() and 0xff) shl 4) or ((it[1].toInt() and 0xff) shr 4)) }

        /** The code turned into a group element nobody knows a logarithm of: a wide hash, reduced, then squared. */
        fun base(code: String): BigInteger {
            val wide = (0 until 9).map { sha("dcrux-pair-v1|$it|$code".toByteArray()) }.reduce { acc, part -> acc + part }
            return BigInteger(1, wide).mod(P).modPow(TWO, P)
        }

        fun exponent(): BigInteger = BigInteger(256, SecureRandom()).setBit(255)
        fun raise(base: BigInteger, e: BigInteger): BigInteger = base.modPow(e, P)

        /** Refuses the few values that would make the agreed key predictable. */
        fun usable(v: BigInteger): Boolean = v > BigInteger.ONE && v < P - BigInteger.ONE

        private fun fixed(v: BigInteger): ByteArray = v.toByteArray().let { raw ->
            ByteArray(256).also { out -> val n = minOf(raw.size, 256); raw.copyInto(out, 256 - n, raw.size - n, raw.size) }
        }

        fun text(v: BigInteger): String = fixed(v).toByteString().base64()
        fun number(text: String): BigInteger? = text.decodeBase64()?.toByteArray()?.takeIf { it.size == 256 }?.let { BigInteger(1, it) }

        /** The key both sides reach when, and only when, they started from the same code. */
        fun key(a: BigInteger, b: BigInteger, shared: BigInteger): SecretKeySpec =
            SecretKeySpec(sha("dcrux-pair-key".toByteArray(), fixed(a), fixed(b), fixed(shared)), "AES")

        fun lock(key: SecretKeySpec, secret: String): String {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
            return (iv + cipher.doFinal(secret.toByteArray())).toByteString().base64()
        }

        fun unlock(key: SecretKeySpec, locked: String): String? = runCatching {
            val raw = locked.decodeBase64()!!.toByteArray()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, raw, 0, 12)) }
            String(cipher.doFinal(raw, 12, raw.size - 12))
        }.getOrNull()
    }

    companion object {
        // 25 symbols of 5 bits each: 125 bits, far beyond guessing, and short enough to type once.
        // The alphabet leaves out I, L, O and U so nothing is mistaken for a digit.
        private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        private const val LENGTH = 25

        private fun newCode(): String = SecureRandom().let { r -> (1..LENGTH).map { ALPHABET[r.nextInt(32)] }.joinToString("") }

        /** The short pairing code: easy to read out and type, and worth nothing once used or a few minutes old. */
        const val SHORT_LENGTH = 4
        private fun newShort(): String = SecureRandom().let { r -> (1..SHORT_LENGTH).map { ALPHABET[r.nextInt(32)] }.joinToString("") }

        private fun cleaned(typed: String): String = typed.uppercase().replace('O', '0').replace('I', '1').replace('L', '1').filter { it.isLetterOrDigit() }

        /** A typed short code, forgiving case and spacing. Null when it cannot be one. */
        fun normaliseShort(typed: String): String? = cleaned(typed).takeIf { it.length == SHORT_LENGTH && it.all { c -> c in ALPHABET } }

        /** The room a short code leads to (for the self-check). */
        internal fun roomOf(code: String): String = Pake.room(code)

        /** Whether the numbers pairing rests on are what they should be (for the self-check). */
        internal fun pairingSound(): Boolean = Pake.P.bitLength() == 2048 && Pake.P.isProbablePrime(40) && Pake.P.shiftRight(1).isProbablePrime(40)

        private const val QR_PREFIX = "dcrux-sync:"

        /** What a pairing QR code holds: the code itself, marked so nothing else is mistaken for one. */
        fun qrText(prettyCode: String): String = QR_PREFIX + prettyCode

        /** The pairing code inside a scanned QR code, or null when the QR code is not a DCrux one. */
        fun fromQr(scanned: String): String? = scanned.trim().takeIf { it.startsWith(QR_PREFIX) }?.removePrefix(QR_PREFIX)?.let(::normalise)

        /** As shown to the user: five groups of five. */
        fun pretty(secret: String): String = secret.chunked(5).joinToString("-")

        /** What the user typed, forgiving case, spacing and the letters left out of the alphabet. Null when it cannot be a code. */
        fun normalise(typed: String): String? {
            val s = cleaned(typed)
            return s.takeIf { it.length == LENGTH && it.all { c -> c in ALPHABET } }
        }
    }
}
