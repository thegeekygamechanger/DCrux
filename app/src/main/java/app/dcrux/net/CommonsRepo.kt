package app.dcrux.net

import app.dcrux.data.AppJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** A photo from Wikimedia Commons with what its licence requires: who made it and under which terms. */
data class OpenImage(val url: String, val credit: String, val license: String, val page: String)

/**
 * Finds openly licensed photographs on Wikimedia Commons (public domain, CC0, CC BY, CC BY-SA).
 * Anything under another licence is skipped. The picture is shown with its credit and never stored
 * beyond the normal image cache.
 */
class CommonsRepo {
    private val cache = ConcurrentHashMap<String, List<OpenImage>>()

    private fun allowed(license: String): Boolean {
        val l = license.lowercase()
        return l.startsWith("cc0") || l.startsWith("public domain") || l.startsWith("pd") ||
            l.startsWith("cc by ") || l.startsWith("cc by-sa") || l == "cc by"
    }

    suspend fun search(query: String, limit: Int = 12): List<OpenImage> {
        val q = query.trim().take(80)
        if (q.length < 3) return emptyList()
        cache[q]?.let { return it }
        val url = "https://commons.wikimedia.org/w/api.php?action=query&generator=search&gsrnamespace=6&gsrlimit=$limit" +
            "&gsrsearch=" + URLEncoder.encode("filetype:bitmap $q", "UTF-8") +
            "&prop=imageinfo&iiprop=url%7Cextmetadata%7Cmime%7Csize&iiurlwidth=1000" +
            "&iiextmetadatafilter=LicenseShortName%7CArtist&format=json"
        val pages = runCatching {
            AppJson.parseToJsonElement(Http.getText(url)).jsonObject["query"]?.jsonObject?.get("pages")?.jsonObject
        }.getOrNull() ?: return emptyList()
        val found = pages.values.mapNotNull { el ->
            val p = el as? JsonObject ?: return@mapNotNull null
            val info = (p["imageinfo"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull()?.jsonObject ?: return@mapNotNull null
            val meta = info["extmetadata"] as? JsonObject
            val license = (meta?.get("LicenseShortName") as? JsonObject)?.get("value")?.jsonPrimitive?.content.orEmpty()
            val mime = info["mime"]?.jsonPrimitive?.content.orEmpty()
            val width = info["width"]?.jsonPrimitive?.intOrNull ?: 0
            val height = info["height"]?.jsonPrimitive?.intOrNull ?: 1
            if (!allowed(license) || mime != "image/jpeg" || width < 700 || width < height) return@mapNotNull null
            val artist = Jsoup.parse((meta?.get("Artist") as? JsonObject)?.get("value")?.jsonPrimitive?.content.orEmpty()).text().take(40)
            val index = p["index"]?.jsonPrimitive?.intOrNull ?: 99
            index to OpenImage(
                url = (info["thumburl"] ?: info["url"])?.jsonPrimitive?.content ?: return@mapNotNull null,
                credit = artist.ifBlank { "Unknown author" },
                license = license,
                page = info["descriptionurl"]?.jsonPrimitive?.content.orEmpty(),
            )
        }.sortedBy { it.first }.map { it.second }
        if (found.isNotEmpty()) cache[q] = found
        return found
    }

    /** The [pick]-th suitable photo for the query (wraps around), or null. */
    suspend fun find(query: String, pick: Int = 0): OpenImage? = search(query).let { if (it.isEmpty()) null else it[pick.mod(it.size)] }
}
