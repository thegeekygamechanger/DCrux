package app.dcrux

import android.app.Application
import android.content.Context
import android.util.Base64
import app.dcrux.ai.AiRepo
import app.dcrux.ai.ClaudeClient
import app.dcrux.data.AppDb
import app.dcrux.data.Prefs
import app.dcrux.data.SecureStore
import app.dcrux.net.CommonsRepo
import app.dcrux.net.NewsRepo
import app.dcrux.net.YouTubeRepo
import app.dcrux.sync.SyncManager
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

    /** True when this build ships with its own key, in which case the app never asks for one. */
    val hasBuiltInKey: Boolean get() = BuildConfig.KA.isNotEmpty()

    private fun builtInKey(): String? {
        if (!hasBuiltInKey) return null
        return runCatching {
            val a = Base64.decode(BuildConfig.KA, Base64.DEFAULT)
            val b = Base64.decode(BuildConfig.KB, Base64.DEFAULT)
            String(ByteArray(a.size) { (a[it].toInt() xor b[it].toInt()).toByte() })
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** The key in the encrypted store; on first run a built-in key is moved there. */
    fun apiKey(): String? {
        secure.apiKey()?.let { return it }
        return builtInKey()?.also { secure.saveApiKey(it) }
    }
}

val Context.graph: Graph get() = (applicationContext as DCruxApp).graph
