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
)

private class ErasedElsewhere : Exception()

data class CloudStatus(val on: Boolean = false, val syncing: Boolean = false, val error: String? = null)

/**
 * Sync across a person's own devices, with no account. One device makes a pairing code and the
 * others enter it. The code never leaves the devices: a key derived from it encrypts everything
 * before upload (AES-256-GCM), so the service stores data it cannot read, under an address that
 * is also derived from the code. Devices merge rather than overwrite: the union of what each has,
 * the newer of two versions of the same thing, and deletions remembered so they stay deleted.
 */
class CloudSync(private val g: Graph) {
    val status = MutableStateFlow(CloudStatus(on = false))
    private val lock = Mutex()
    private var loop: Job? = null
    private var soon: Job? = null
    private val memoryFile: File get() = File(g.platform.filesDir, "cloud_memory")

    /** True when this build can sync at all (it needs the DCrux service). */
    val available: Boolean get() = g.usesService

    fun code(): String? = g.secure.syncSecret()?.let(::pretty)

    /** Begins the background rhythm: now, then every few minutes while the app is open. */
    fun start() {
        status.value = CloudStatus(on = g.secure.syncSecret() != null)
        if (loop != null || !available) return
        loop = g.scope.launch {
            while (true) {
                if (g.secure.syncSecret() != null) syncNow()
                delay(5 * 60_000L)
            }
        }
    }

    /** Asks for a sync shortly, after something worth sharing has changed; several calls in a row make one sync. */
    fun syncSoon() {
        if (g.secure.syncSecret() == null) return
        soon?.cancel()
        soon = g.scope.launch { delay(15_000); syncNow() }
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

    private fun forget() { runCatching { memoryFile.delete() } }

    suspend fun syncNow() {
        val secret = g.secure.syncSecret() ?: return
        if (!lock.tryLock()) return
        try {
            status.value = CloudStatus(on = true, syncing = true)
            val error = withContext(Dispatchers.IO) { runCatching { exchange(Keys(secret)) }.exceptionOrNull() }
            if (error is ErasedElsewhere) {
                // Another of the person's devices ended sync and erased the stored copy: stop here too. What is on this device stays.
                g.secure.setSyncSecret(null)
                forget()
                g.prefs.setCloudLastSync(0)
                status.value = CloudStatus(on = false, error = "Sync was turned off and its stored copy erased from another of your devices. Your data on this device is untouched.")
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
            val got = g.claude.serviceCall("GET", "/v1/sync/${keys.id}") ?: throw IllegalStateException("No connection to the sync service")
            val (remote, version) = when (got.first) {
                200 -> {
                    val o = AppJson.parseToJsonElement(got.second).jsonObject
                    if (o["erased"]?.jsonPrimitive?.content == "true") throw ErasedElsewhere()
                    val data = o["data"]?.jsonPrimitive?.content.orEmpty()
                    val state = keys.open(data) ?: throw IllegalStateException("This code does not open the stored data. Check the code.")
                    state to (o["version"]?.jsonPrimitive?.intOrNull ?: 0)
                }
                404 -> CloudState() to 0
                503 -> throw IllegalStateException("Sync is not available on the service right now")
                else -> throw IllegalStateException("The sync service answered ${got.first}")
            }
            val now = System.currentTimeMillis()
            val first = memory.version == 0 && memory.keys.isEmpty()
            val local = snapshot(memory, now, adoptRemoteSettings = first && remote.settings.at > 0)
            // What this device had at the last exchange and no longer has was deleted here.
            val gone = remote.gone.toMutableMap()
            (memory.keys - keysOf(local)).forEach { k -> gone[k] = maxOf(gone[k] ?: 0, now) }
            val merged = merge(remote, local, gone.filterValues { now - it < 180L * 86_400_000L })
            apply(merged, local)
            if (merged != remote) {
                val put = g.claude.serviceCall("PUT", "/v1/sync/${keys.id}", """{"base":$version,"data":"${keys.seal(merged)}"}""")
                    ?: throw IllegalStateException("No connection to the sync service")
                if (put.first == 409) return@repeat          // another device saved meanwhile: fetch, merge and try again
                if (put.first != 200) throw IllegalStateException(if (put.first == 413) "There is too much to sync in one go" else "The sync service answered ${put.first}")
            }
            memory = SyncMemory(
                version = if (merged != remote) version + 1 else version, keys = keysOf(merged),
                settingsHash = merged.settings.copy(at = 0).hashCode(), settingsAt = merged.settings.at,
                noteHash = merged.notes.associate { it.id to it.copy(at = 0).hashCode() }, noteAt = merged.notes.associate { it.id to it.at },
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

    companion object {
        // 25 symbols of 5 bits each: 125 bits, far beyond guessing, and short enough to type once.
        // The alphabet leaves out I, L, O and U so nothing is mistaken for a digit.
        private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        private const val LENGTH = 25

        private fun newCode(): String = SecureRandom().let { r -> (1..LENGTH).map { ALPHABET[r.nextInt(32)] }.joinToString("") }

        /** As shown to the user: five groups of five. */
        fun pretty(secret: String): String = secret.chunked(5).joinToString("-")

        /** What the user typed, forgiving case, spacing and the letters left out of the alphabet. Null when it cannot be a code. */
        fun normalise(typed: String): String? {
            val s = typed.uppercase().replace('O', '0').replace('I', '1').replace('L', '1').filter { it.isLetterOrDigit() }
            return s.takeIf { it.length == LENGTH && it.all { c -> c in ALPHABET } }
        }
    }
}
