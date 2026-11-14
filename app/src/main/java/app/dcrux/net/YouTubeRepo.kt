package app.dcrux.net

import app.dcrux.data.AppJson
import app.dcrux.data.Dates
import app.dcrux.data.Video
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

data class ChannelFeed(val title: String, val videos: List<Video>)

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

    /** The channel's public Atom feed: its latest uploads and live streams. */
    suspend fun fetchFeed(channelId: String): ChannelFeed {
        require(idRe.matches(channelId)) { "Bad channel id" }
        val xml = Http.getText("https://www.youtube.com/feeds/videos.xml?channel_id=$channelId")
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

    private fun meta(html: String, property: String): String? =
        Regex("""<meta (?:property|name)="${Regex.escape(property)}" content="([^"]*)"""").find(html)
            ?.groupValues?.get(1)?.let { Parser.unescapeEntities(it, false) }?.takeIf { it.isNotBlank() }

    /** Reads `"key":"value"` out of the page's embedded JSON, decoding escapes properly. */
    private fun jsonString(html: String, key: String): String? {
        val raw = Regex(""""${Regex.escape(key)}":"((?:[^"\\]|\\.)*)"""").find(html)?.groupValues?.get(1) ?: return null
        return runCatching { AppJson.decodeFromString<String>("\"$raw\"") }.getOrNull()
    }
}
