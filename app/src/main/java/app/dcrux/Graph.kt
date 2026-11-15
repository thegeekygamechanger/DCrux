package app.dcrux

import app.dcrux.ai.AiRepo
import app.dcrux.ai.ClaudeClient
import app.dcrux.ai.ServiceAuth
import app.dcrux.data.AppDb
import app.dcrux.data.Prefs
import app.dcrux.data.SecureStore
import app.dcrux.mains.MainsStore
import app.dcrux.net.CommonsRepo
import app.dcrux.net.NewsRepo
import app.dcrux.net.WeatherRepo
import app.dcrux.net.YouTubeRepo
import app.dcrux.sync.CloudSync
import app.dcrux.sync.SyncManager
import app.dcrux.update.Updates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okio.ByteString.Companion.decodeBase64

/** How this build reaches the AI: a masked key baked in (personal builds), or the DCrux service and its signing secret. */
class BuildKeys(val ka: String, val kb: String, val px: String, val sa: String, val sb: String)

/** The app's few long-lived objects, created once. The same on every platform; what differs comes in through [platform]. */
class Graph(
    val platform: Platform,
    val secure: SecureStore,
    val prefs: Prefs,
    private val keys: BuildKeys,
    openDb: () -> AppDb,
    makeUpdates: (Graph) -> Updates,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db: AppDb by lazy(openDb)
    val youtube = YouTubeRepo()
    val news = NewsRepo()
    val commons = CommonsRepo()
    val claude = ClaudeClient()
    val ai: AiRepo by lazy { AiRepo(this) }
    val sync: SyncManager by lazy { SyncManager(this) }
    val updates: Updates by lazy { makeUpdates(this) }
    val weather: WeatherRepo by lazy { WeatherRepo(platform, claude) }
    val mains: MainsStore by lazy { MainsStore(this) }
    val cloud: CloudSync by lazy { CloudSync(this) }

    /** True when this build is set up to use the DCrux service instead of a key on the device. */
    val usesService: Boolean get() = keys.ka.isEmpty() && keys.px.isNotEmpty() && keys.sa.isNotEmpty()

    /** True when the AI works out of the box (a built-in key or the service), so the app never asks for a key. */
    val hasBuiltInKey: Boolean get() = keys.ka.isNotEmpty() || usesService

    private fun unmask(a64: String, b64: String): String? = runCatching {
        val a = a64.decodeBase64()!!.toByteArray()
        val b = b64.decodeBase64()!!.toByteArray()
        String(ByteArray(a.size) { (a[it].toInt() xor b[it].toInt()).toByte() })
    }.getOrNull()?.takeIf { it.isNotBlank() }

    init {
        prefs.onSharedChange = { cloud.syncSoon() }
        if (usesService) {
            unmask(keys.sa, keys.sb)?.let { secret ->
                claude.service = ServiceAuth(keys.px, secure.deviceId(), secret.toByteArray())
            }
        }
    }

    /**
     * What to call the AI with: a key the user saved, else the built-in key (moved into the
     * encrypted store on first use), else the marker that sends requests through the service.
     */
    fun apiKey(): String? {
        secure.apiKey()?.let { return it }
        if (keys.ka.isNotEmpty()) return unmask(keys.ka, keys.kb)?.also { secure.saveApiKey(it) }
        return if (usesService) ClaudeClient.VIA_SERVICE else null
    }
}
