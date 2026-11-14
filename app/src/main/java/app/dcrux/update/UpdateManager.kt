package app.dcrux.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import app.dcrux.BuildConfig
import app.dcrux.Graph
import app.dcrux.data.AppJson
import app.dcrux.graph
import app.dcrux.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Request
import java.io.IOException

/** A newer build published on GitHub. */
data class UpdateInfo(val version: String, val notes: String, val apkUrl: String, val sizeBytes: Long)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val percent: Int) : UpdateState
    /** Android has not yet been told that DCrux may install its own updates. */
    data class NeedsPermission(val info: UpdateInfo) : UpdateState
    /** The system's own "Update this app?" prompt is showing (or about to). */
    data class Confirming(val info: UpdateInfo) : UpdateState
    data class Failed(val message: String, val info: UpdateInfo? = null) : UpdateState
}

/**
 * Over-the-air updates from the project's GitHub releases.
 *
 * The newest release's APK is streamed straight into Android's package installer: the app
 * never writes an APK file of its own, so there is nothing to clean up afterwards, and the
 * system replaces the old build with the new one. Android only accepts the update when it is
 * signed with the same key as the installed app, so a tampered download cannot be installed.
 */
class UpdateManager(private val g: Graph) {
    val state = MutableStateFlow<UpdateState>(UpdateState.Idle)

    /** Looks at the latest release. Quietly does nothing more than once a day unless [force]d. */
    suspend fun check(force: Boolean = false) {
        if (state.value is UpdateState.Downloading || state.value is UpdateState.Confirming) return
        val settings = g.prefs.get()
        if (!force && (!settings.autoUpdate || System.currentTimeMillis() - settings.lastUpdateCheck < 24 * 3_600_000L)) return
        state.value = UpdateState.Checking
        try {
            val root = AppJson.parseToJsonElement(Http.getText(LATEST, mapOf("Accept" to "application/vnd.github+json"))).jsonObject
            g.prefs.setLastUpdateCheck(System.currentTimeMillis())
            val version = root["tag_name"]?.jsonPrimitive?.content.orEmpty().removePrefix("v")
            val asset = (root["assets"] as? JsonArray)?.map { it.jsonObject }
                ?.firstOrNull { it["name"]?.jsonPrimitive?.content?.endsWith(".apk") == true }
            val url = asset?.get("browser_download_url")?.jsonPrimitive?.content
            state.value = if (url != null && isNewer(version, BuildConfig.VERSION_NAME)) {
                UpdateState.Available(
                    UpdateInfo(version, root["body"]?.jsonPrimitive?.content.orEmpty().trim(), url, asset["size"]?.jsonPrimitive?.longOrNull ?: 0L)
                )
            } else UpdateState.UpToDate
        } catch (e: Exception) {
            // A silent daily check that fails is not worth an error on screen; it is tried again
            // shortly, and again the next time the app is opened.
            state.value = if (force) UpdateState.Failed("Could not check for updates. Check your connection.") else UpdateState.Idle
            if (!force && !retried) {
                retried = true
                g.scope.launch { delay(30_000); check() }
            }
        }
    }

    /** True when Android will let DCrux hand it an update (always true before Android 8). */
    fun mayInstall(): Boolean = Build.VERSION.SDK_INT < 26 || g.app.packageManager.canRequestPackageInstalls()

    /** Every time the app comes to the front: pick up where an update was left, and look for a new one. */
    fun onAppForeground() {
        val s = state.value
        if (s is UpdateState.NeedsPermission && mayInstall()) install(s.info)
        else g.scope.launch { check() }
    }

    /** Downloads the update into an installer session and hands it to Android to install. */
    fun install(info: UpdateInfo) {
        if (state.value is UpdateState.Downloading) return
        // Ask for the one-time permission first, in the app's own words, rather than downloading
        // and then running into Android's refusal.
        if (!mayInstall()) { state.value = UpdateState.NeedsPermission(info); return }
        state.value = UpdateState.Downloading(info, 0)
        g.scope.launch {
            val installer = g.app.packageManager.packageInstaller
            var sessionId = -1
            try {
                withContext(Dispatchers.IO) {
                    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                        setAppPackageName(g.app.packageName)
                        if (info.sizeBytes > 0) setSize(info.sizeBytes)
                    }
                    sessionId = installer.createSession(params)
                    installer.openSession(sessionId).use { session ->
                        Http.client.newCall(Request.Builder().url(info.apkUrl).header("User-Agent", Http.APP_UA).build()).execute().use { res ->
                            if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
                            val total = res.body.contentLength().takeIf { it > 0 } ?: info.sizeBytes
                            session.openWrite("DCrux.apk", 0, if (total > 0) total else -1).use { out ->
                                val input = res.body.byteStream()
                                val buffer = ByteArray(64 * 1024)
                                var done = 0L
                                var shown = -1
                                while (true) {
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    out.write(buffer, 0, n)
                                    done += n
                                    val pct = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
                                    if (pct != shown) { shown = pct; state.value = UpdateState.Downloading(info, pct) }
                                }
                                session.fsync(out)
                            }
                        }
                        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                        val result = PendingIntent.getBroadcast(
                            g.app, sessionId, Intent(g.app, UpdateReceiver::class.java).setAction(UpdateReceiver.ACTION).putExtra(UpdateReceiver.EXTRA_VERSION, info.version), flags,
                        )
                        pending = info
                        session.commit(result.intentSender)
                    }
                }
                state.value = UpdateState.Confirming(info)
            } catch (e: Exception) {
                if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
                state.value = UpdateState.Failed("The update could not be downloaded. Try again.", info)
            }
        }
    }

    /** Called by [UpdateReceiver] with what Android decided. */
    fun onInstallResult(status: Int, message: String?) {
        val info = pending
        state.value = when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> info?.let { UpdateState.Confirming(it) } ?: UpdateState.Idle
            PackageInstaller.STATUS_SUCCESS -> UpdateState.UpToDate
            PackageInstaller.STATUS_FAILURE_ABORTED -> info?.let { UpdateState.Available(it) } ?: UpdateState.Idle
            else -> UpdateState.Failed(
                when (status) {
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough free space to install the update."
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "This update does not fit this phone."
                    PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INVALID ->
                        "Android refused the update because it does not match the installed app."
                    else -> "The update was not installed" + (message?.let { ": $it" } ?: ".")
                },
                info,
            )
        }
    }

    /** Removes anything a broken-off update left with the system installer. */
    fun cleanUp() {
        runCatching {
            val installer = g.app.packageManager.packageInstaller
            installer.mySessions.forEach { if (!it.isActive) installer.abandonSession(it.sessionId) }
        }
    }

    @Volatile private var pending: UpdateInfo? = null
    @Volatile private var retried = false

    companion object {
        const val REPO = "thegeekygamechanger/DCrux"
        const val LATEST = "https://api.github.com/repos/$REPO/releases/latest"

        /** "1.10.0" is newer than "1.9.3": compare number by number, not as text. */
        fun isNewer(candidate: String, current: String): Boolean {
            fun parts(v: String) = v.trim().split('.', '-', '+').map { it.toIntOrNull() ?: 0 }
            val a = parts(candidate); val b = parts(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}

/** Receives Android's verdict on an update and, when asked, opens the system's confirmation screen. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            confirm?.let { runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        }
        context.graph.updates.onInstallResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }

    companion object {
        const val ACTION = "app.dcrux.action.UPDATE_RESULT"
        const val EXTRA_VERSION = "version"
    }
}
