package app.dcrux

import android.app.Application
import android.content.Context
import android.util.Base64
import app.dcrux.ai.AiRepo
import app.dcrux.ai.ClaudeClient
import app.dcrux.ai.ServiceAuth
import app.dcrux.data.AppDb
import app.dcrux.data.Prefs
import app.dcrux.data.SecureStore
import app.dcrux.net.CommonsRepo
import app.dcrux.mains.MainsStore
import app.dcrux.net.NewsRepo
import app.dcrux.net.WeatherRepo
import app.dcrux.net.YouTubeRepo
import app.dcrux.sync.SyncManager
import app.dcrux.update.UpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** The app's few long-lived objects, created once. */
class Graph(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db: AppDb by lazy { AppDb.open(app) }
    val prefs = Prefs(app)
    val secure = SecureStore(app)
    val youtube = YouTubeRepo()
    val news = NewsRepo()
    val commons = CommonsRepo()
    val claude = ClaudeClient()
    val ai: AiRepo by lazy { AiRepo(this) }
    val sync: SyncManager by lazy { SyncManager(this) }
    val updates: UpdateManager by lazy { UpdateManager(this) }
    val weather: WeatherRepo by lazy { WeatherRepo(app) }
    val mains: MainsStore by lazy { MainsStore(this) }

    /** True when this build is set up to use the DCrux service instead of a key on the phone. */
    val usesService: Boolean get() = BuildConfig.KA.isEmpty() && BuildConfig.PX.isNotEmpty() && BuildConfig.SA.isNotEmpty()

    /** True when the AI works out of the box (a built-in key or the service), so the app never asks for a key. */
    val hasBuiltInKey: Boolean get() = BuildConfig.KA.isNotEmpty() || usesService

    private fun unmask(a64: String, b64: String): String? = runCatching {
        val a = Base64.decode(a64, Base64.DEFAULT)
        val b = Base64.decode(b64, Base64.DEFAULT)
        String(ByteArray(a.size) { (a[it].toInt() xor b[it].toInt()).toByte() })
    }.getOrNull()?.takeIf { it.isNotBlank() }

    init {
        if (usesService) {
            unmask(BuildConfig.SA, BuildConfig.SB)?.let { secret ->
                claude.service = ServiceAuth(BuildConfig.PX, secure.deviceId(), secret.toByteArray())
            }
        }
    }

    /**
     * What to call the AI with: a key the user saved, else the built-in key (moved into the
     * encrypted store on first use), else the marker that sends requests through the service.
     */
    fun apiKey(): String? {
        secure.apiKey()?.let { return it }
        if (BuildConfig.KA.isNotEmpty()) return unmask(BuildConfig.KA, BuildConfig.KB)?.also { secure.saveApiKey(it) }
        return if (usesService) ClaudeClient.VIA_SERVICE else null
    }
}

val Context.graph: Graph get() = (applicationContext as DCruxApp).graph
