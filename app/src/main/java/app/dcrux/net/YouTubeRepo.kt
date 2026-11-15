package app.dcrux.net

import app.dcrux.data.AppJson
import app.dcrux.data.Dates
import app.dcrux.data.Video
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URLEncoder

data class ResolvedLink(
    val channelId: String,
    val channelTitle: String,
    val channelUrl: String,
    val avatar: String = "",
    val video: Video? = null,
)

/** [approximateDates] is true when the dates came from "2d ago" style text rather than timestamps. */
data class ChannelFeed(val title: String, val videos: List<Video>, val approximateDates: Boolean = false)

/** A caption track the video offers. [kind] is "asr" for auto-generated captions. */
data class CaptionTrack(val lang: String, val kind: String, val vssId: String)

/**
 * Everything here uses pages and feeds YouTube serves publicly to any browser:
 * the channel's Atom feed, oEmbed, and the normal watch/channel pages. No API key,
 * no server, no private endpoints.
 */
class YouTubeRepo {
    private val cookies = mapOf("Cookie" to "SOCS=CAI; CONSENT=YES+1")
    private val idRe = Regex("""UC[\w-]{22}""")

    private companion object {
        const val DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }

    /** Pulls every YouTube link out of pasted text (newlines, commas, spaces all work). */
    fun extractLinks(text: String): List<String> =
        Regex("""(?:https?://)?(?:[\w-]+\.)?(?:youtube\.com|youtu\.be|youtube-nocookie\.com)/[^\s,;<>"']+""", RegexOption.IGNORE_CASE)
            .findAll(text).map { m -> m.value.trimEnd('.', ')', ']').let { if (it.startsWith("http", true)) it else "https://$it" } }
            .distinct().toList()

    fun videoIdOf(url: HttpUrl): String? {
        val seg = url.pathSegments.filter { it.isNotEmpty() }
        val id = when {
            url.host.endsWith("youtu.be") -> seg.firstOrNull()
            seg.firstOrNull() == "watch" -> url.queryParameter("v")
            seg.firstOrNull() in setOf("live", "shorts", "embed", "v") -> seg.getOrNull(1)
            else -> null
        }
        return id?.takeIf { Regex("""[\w-]{11}""").matches(it) }
    }

    suspend fun resolve(link: String): ResolvedLink {
        val url = link.toHttpUrlOrNull() ?: throw IllegalArgumentException("Not a valid link")
        val seg = url.pathSegments.filter { it.isNotEmpty() }
        videoIdOf(url)?.let { return resolveVideo(it, isShort = seg.firstOrNull() == "shorts") }
        if (seg.firstOrNull() == "playlist") throw IllegalArgumentException("Playlists are not supported yet. Paste a channel or video link.")
        val first = seg.firstOrNull() ?: throw IllegalArgumentException("Paste a channel or video link")
        val channelUrl = when {
            first == "channel" && seg.size > 1 -> "https://www.youtube.com/channel/${seg[1]}"
            first.startsWith("@") -> "https://www.youtube.com/$first"
            first in setOf("c", "user") && seg.size > 1 -> "https://www.youtube.com/$first/${seg[1]}"
            else -> "https://www.youtube.com/$first"
        }
        return resolveChannel(channelUrl)
    }

    private suspend fun resolveChannel(channelUrl: String): ResolvedLink {
        val html = Http.getText(channelUrl, cookies)
        val id = Regex(""""externalId":"(UC[\w-]{22})"""").find(html)?.groupValues?.get(1)
            ?: Regex("""<link rel="canonical" href="https://www\.youtube\.com/channel/(UC[\w-]{22})"""").find(html)?.groupValues?.get(1)
            ?: Regex("""channel_id=(UC[\w-]{22})""").find(html)?.groupValues?.get(1)
            ?: Regex(""""browseId":"(UC[\w-]{22})"""").find(html)?.groupValues?.get(1)
            ?: throw IllegalStateException("Could not find that channel")
        return ResolvedLink(
            channelId = id,
            channelTitle = meta(html, "og:title") ?: "YouTube channel",
            channelUrl = "https://www.youtube.com/channel/$id",
            avatar = meta(html, "og:image").orEmpty(),
        )
    }

    private suspend fun resolveVideo(videoId: String, isShort: Boolean): ResolvedLink {
        val html = Http.getText("https://www.youtube.com/watch?v=$videoId", cookies)
        val channelId = Regex(""""channelId":"(UC[\w-]{22})"""").find(html)?.groupValues?.get(1)
            ?: throw IllegalStateException("Could not read that video (private or removed?)")
        var title = meta(html, "og:title") ?: "YouTube video"
        var author = jsonString(html, "ownerChannelName") ?: jsonString(html, "author") ?: "YouTube channel"
        runCatching {
            val o = AppJson.parseToJsonElement(
                Http.getText("https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode("https://www.youtube.com/watch?v=$videoId", "UTF-8"))
            ).jsonObject
            o["title"]?.jsonPrimitive?.content?.let { title = it }
            o["author_name"]?.jsonPrimitive?.content?.let { author = it }
        }
        val published = Dates.parse(jsonString(html, "startTimestamp"))
            ?: Dates.parse(jsonString(html, "publishDate"))
            ?: Dates.parse(jsonString(html, "uploadDate"))
            ?: System.currentTimeMillis()
        val avatar = runCatching { resolveChannel("https://www.youtube.com/channel/$channelId").avatar }.getOrDefault("")
        return ResolvedLink(
            channelId = channelId,
            channelTitle = author,
            channelUrl = "https://www.youtube.com/channel/$channelId",
            avatar = avatar,
            video = Video(
                id = videoId, channelId = channelId, title = title,
                description = jsonString(html, "shortDescription").orEmpty(),
                publishedAt = published, isShort = isShort,
            ),
        )
    }

    /**
     * Which caption tracks a video has, read from its public watch page. The player is then
     * asked for one of them by name; auto-generated tracks are not loaded unless asked for.
     */
    suspend fun captionTracks(videoId: String): List<CaptionTrack> {
        val html = Http.getText("https://www.youtube.com/watch?v=$videoId", cookies)
        val start = html.indexOf("\"captionTracks\":[")
        if (start < 0) return emptyList()
        val from = html.indexOf('[', start)
        var depth = 0
        var inString = false
        var end = -1
        var i = from
        while (i < html.length) {
            val c = html[i]
            if (inString) {
                if (c == '\\') i++ else if (c == '"') inString = false
            } else when (c) {
                '"' -> inString = true
                '[', '{' -> depth++
                ']', '}' -> { depth--; if (depth == 0) { end = i; break } }
            }
            i++
        }
        if (end < 0) return emptyList()
        return runCatching {
            AppJson.parseToJsonElement(html.substring(from, end + 1)).jsonArray.mapNotNull { el ->
                val o = el.jsonObject
                val lang = o["languageCode"]?.jsonPrimitive?.content ?: return@mapNotNull null
                CaptionTrack(lang, o["kind"]?.jsonPrimitive?.content.orEmpty(), o["vssId"]?.jsonPrimitive?.content.orEmpty())
            }
        }.getOrDefault(emptyList())
    }

    /**
     * A channel's latest uploads and live streams. YouTube's feed is the first choice, but it
     * goes missing from time to time (it answers 404 for hours), so there are two fallbacks:
     * the same feed under the channel's uploads list, then the channel's own Videos and Live
     * pages. Only when all three fail does the sync report a problem.
     */
    suspend fun fetchFeed(channelId: String): ChannelFeed {
        require(idRe.matches(channelId)) { "Bad channel id" }
        val feeds = listOf(
            "https://www.youtube.com/feeds/videos.xml?channel_id=$channelId",
            "https://www.youtube.com/feeds/videos.xml?playlist_id=UU${channelId.removePrefix("UC")}",
        )
        for (url in feeds) {
            runCatching { parseFeed(Http.getText(url), channelId) }.getOrNull()?.takeIf { it.videos.isNotEmpty() }?.let { return it }
        }
        return runCatching { fetchFromPages(channelId) }.getOrNull()?.takeIf { it.videos.isNotEmpty() }
            ?: throw java.io.IOException("YouTube did not answer. It will be tried again on the next sync.")
    }

    private fun parseFeed(xml: String, channelId: String): ChannelFeed {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val title = doc.selectFirst("feed > title")?.text().orEmpty()
        val videos = doc.select("entry").mapNotNull { e ->
            val id = e.getElementsByTag("yt:videoId").first()?.text()?.trim().orEmpty()
            if (id.isEmpty()) return@mapNotNull null
            val href = e.select("link[rel=alternate]").attr("href")
            Video(
                id = id,
                channelId = channelId,
                title = e.getElementsByTag("title").first()?.text().orEmpty(),
                description = e.getElementsByTag("media:description").first()?.wholeText().orEmpty().trim(),
                publishedAt = Dates.parse(e.getElementsByTag("published").first()?.text()) ?: System.currentTimeMillis(),
                isShort = href.contains("/shorts/"),
            )
        }
        return ChannelFeed(title, videos)
    }

    /** Reads the video list that the channel's Videos and Live pages carry for any browser. */
    private suspend fun fetchFromPages(channelId: String): ChannelFeed {
        val headers = cookies + mapOf("User-Agent" to DESKTOP_UA, "Accept-Language" to "en")
        var title = ""
        val videos = LinkedHashMap<String, Video>()
        for (tab in listOf("streams", "videos")) {
            val html = runCatching { Http.getText("https://www.youtube.com/channel/$channelId/$tab", headers) }.getOrNull() ?: continue
            if (title.isBlank()) title = meta(html, "og:title").orEmpty()
            val start = html.indexOf("var ytInitialData = ")
            val end = if (start >= 0) html.indexOf(";</script>", start) else -1
            if (start < 0 || end < 0) continue
            val root = runCatching { AppJson.parseToJsonElement(html.substring(html.indexOf('{', start), end)) }.getOrNull() ?: continue
            val items = ArrayList<JsonObject>()
            collect(root, "lockupViewModel", items)
            val now = System.currentTimeMillis()
            for (item in items) {
                val id = item["contentId"]?.jsonPrimitive?.content ?: continue
                if (!Regex("""[\w-]{11}""").matches(id) || id in videos) continue
                val meta = item["metadata"]?.jsonObject?.get("lockupMetadataViewModel")?.jsonObject ?: continue
                val name = meta["title"]?.jsonObject?.get("content")?.jsonPrimitive?.content.orEmpty()
                val texts = ArrayList<String>()
                strings(item, texts)
                // Not yet broadcast: there is nothing to watch or summarise.
                if (texts.any { it == "Upcoming" || it.startsWith("Scheduled for") }) continue
                val age = texts.firstNotNullOfOrNull(::ageOf) ?: continue
                if (name.isNotBlank()) videos[id] = Video(id = id, channelId = channelId, title = name, publishedAt = now - age)
            }
        }
        return ChannelFeed(title, videos.values.toList(), approximateDates = true)
    }

    private fun collect(el: JsonElement, key: String, out: MutableList<JsonObject>) {
        when (el) {
            is JsonObject -> el.forEach { (k, v) -> if (k == key && v is JsonObject) out += v else collect(v, key, out) }
            is JsonArray -> el.forEach { collect(it, key, out) }
            else -> {}
        }
    }

    private fun strings(el: JsonElement, out: MutableList<String>) {
        when (el) {
            is JsonObject -> el.forEach { (k, v) ->
                if ((k == "content" || k == "text") && v is JsonPrimitive && v.isString) out += v.content else strings(v, out)
            }
            is JsonArray -> el.forEach { strings(it, out) }
            else -> {}
        }
    }

    /** "2d ago", "Streamed 16h ago", "3 weeks ago" as milliseconds; null for anything else. */
    private fun ageOf(text: String): Long? {
        val m = Regex("""(\d+)\s*(second|sec|minute|min|hour|hr|day|week|wk|month|mo|year|yr|s|m|h|d|w|y)s?\s+ago""", RegexOption.IGNORE_CASE).find(text) ?: return null
        val n = m.groupValues[1].toLong()
        val unit = when (m.groupValues[2].lowercase()) {
            "second", "sec", "s" -> 1_000L
            "minute", "min", "m" -> 60_000L
            "hour", "hr", "h" -> 3_600_000L
            "day", "d" -> 86_400_000L
            "week", "wk", "w" -> 7 * 86_400_000L
            "month", "mo" -> 30 * 86_400_000L
            else -> 365 * 86_400_000L
        }
        return n * unit
    }

    /** The exact time a video went out, from its watch page; used to firm up "2d ago" style dates. */
    suspend fun publishedAt(videoId: String): Long? = runCatching {
        val html = Http.getText("https://www.youtube.com/watch?v=$videoId", cookies)
        Dates.parse(jsonString(html, "startTimestamp")) ?: Dates.parse(jsonString(html, "publishDate")) ?: Dates.parse(jsonString(html, "uploadDate"))
    }.getOrNull()

    private fun meta(html: String, property: String): String? =
        Regex("""<meta (?:property|name)="${Regex.escape(property)}" content="([^"]*)"""").find(html)
            ?.groupValues?.get(1)?.let { Parser.unescapeEntities(it, false) }?.takeIf { it.isNotBlank() }

    /** Reads `"key":"value"` out of the page's embedded JSON, decoding escapes properly. */
    private fun jsonString(html: String, key: String): String? {
        val raw = Regex(""""${Regex.escape(key)}":"((?:[^"\\]|\\.)*)"""").find(html)?.groupValues?.get(1) ?: return null
        return runCatching { AppJson.decodeFromString<String>("\"$raw\"") }.getOrNull()
    }
}
