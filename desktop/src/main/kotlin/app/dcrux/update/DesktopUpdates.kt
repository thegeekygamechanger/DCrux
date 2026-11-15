package app.dcrux.update

import app.dcrux.Graph
import app.dcrux.data.AppJson
import app.dcrux.net.Http
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Updates on a computer: the app looks at the project's latest GitHub release and, when it is
 * newer, opens the download for this system in the browser. The system's own installer does the rest.
 */
class DesktopUpdates(private val g: Graph) : Updates {
    override val state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    private val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    override suspend fun check(force: Boolean) {
        val s = g.prefs.get()
        if (!force && (!s.autoUpdate || System.currentTimeMillis() - s.lastUpdateCheck < 20 * 3_600_000L)) return
        state.value = UpdateState.Checking
        try {
            val rel = AppJson.parseToJsonElement(
                Http.getText("https://api.github.com/repos/thegeekygamechanger/DCrux/releases/latest", mapOf("Accept" to "application/vnd.github+json"))
            ).jsonObject
            val version = rel["tag_name"]?.jsonPrimitive?.content.orEmpty().removePrefix("v")
            val wanted = if (windows) listOf(".msi", ".exe") else listOf(".deb", ".AppImage", ".tar.gz")
            val asset = rel["assets"]?.jsonArray.orEmpty().map { it.jsonObject }.firstOrNull { a ->
                val name = a["name"]?.jsonPrimitive?.content.orEmpty()
                wanted.any { name.endsWith(it, ignoreCase = true) }
            }
            g.prefs.setLastUpdateCheck(System.currentTimeMillis())
            state.value = if (asset != null && Updates.isNewer(version, g.platform.versionName)) UpdateState.Available(
                UpdateInfo(version, rel["body"]?.jsonPrimitive?.content.orEmpty(), asset["browser_download_url"]?.jsonPrimitive?.content.orEmpty(), asset["size"]?.jsonPrimitive?.longOrNull ?: 0)
            ) else UpdateState.UpToDate
        } catch (e: Exception) {
            state.value = if (force) UpdateState.Failed("Could not reach GitHub. Check the internet and try again.") else UpdateState.Idle
        }
    }

    override fun install(info: UpdateInfo) {
        g.platform.openUrl(info.apkUrl)
        state.value = UpdateState.Confirming(info)
    }

    override fun onAppForeground() {}
    override fun cleanUp() {}
}
