package app.dcrux.net

import app.dcrux.Graph
import app.dcrux.data.AppJson
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Feedback written in Settings. It goes to the DCrux service, which passes it on to the developer by email.
 * The app does not know the address it ends up at, and the service keeps no copy.
 */
object Feedback {
    const val MIN = 10
    const val MAX = 4000

    fun emailLooksRight(email: String): Boolean = Regex("""^[^\s@<>"',;]+@[^\s@<>"',;]+\.[^\s@<>"',;]{2,}$""").matches(email)

    /** Sends it. Null when it went; otherwise what to tell the person. */
    suspend fun send(g: Graph, kind: String, message: String, email: String): String? {
        val where = if (g.platform.isDesktop) "Desktop (${System.getProperty("os.name").orEmpty()})" else "Android"
        val body = buildJsonObject {
            put("kind", kind); put("message", message.trim()); put("email", email.trim())
            put("app", "${g.platform.versionName} · $where")
        }.toString()
        val answer = g.claude.serviceCall("POST", "/v1/feedback", body) ?: return "Could not reach the service. Check the connection and try again."
        if (answer.first == 200) return null
        return runCatching { AppJson.parseToJsonElement(answer.second).jsonObject["error"]!!.jsonObject["message"]!!.jsonPrimitive.content }.getOrNull()
            ?: "Could not send it just now. Please try again in a while."
    }
}
