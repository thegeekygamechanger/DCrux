package app.dcrux.net

import app.dcrux.data.CustomSource
import app.dcrux.data.Dates
import app.dcrux.data.NewsItem
import app.dcrux.data.Settings
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.security.MessageDigest

data class Feed(val section: String, val url: String)

data class NewsSource(
    val id: String,
    val name: String,
    val short: String,
    val tagline: String,
    val feeds: List<Feed>,
    val home: String,
    /** The publisher's own e-paper, opened live in the in-app browser (their login applies). */
    val epaper: String? = null,
    val accent: Long = 0xFF6C3BFF,
    val custom: Boolean = false,
)

/**
 * Only the publishers' own public feeds are used. DCrux keeps a headline index
 * (headline, the feed's short snippet, link); the article is always read on the
 * publisher's site.
 */
object Sources {
    val builtIn: List<NewsSource> = listOf(
        NewsSource(
            "hindu", "The Hindu", "TH", "National daily · editorials, economy, science",
            listOf(
                Feed("National", "https://www.thehindu.com/news/national/feeder/default.rss"),
                Feed("Editorial", "https://www.thehindu.com/opinion/editorial/feeder/default.rss"),
                Feed("Lead", "https://www.thehindu.com/opinion/lead/feeder/default.rss"),
                Feed("International", "https://www.thehindu.com/news/international/feeder/default.rss"),
                Feed("Economy", "https://www.thehindu.com/business/Economy/feeder/default.rss"),
                Feed("Science", "https://www.thehindu.com/sci-tech/science/feeder/default.rss"),
                Feed("Environment", "https://www.thehindu.com/sci-tech/energy-and-environment/feeder/default.rss"),
            ),
            "https://www.thehindu.com", "https://epaper.thehindu.com", 0xFF1E6FD9,
        ),
        NewsSource(
            "ie", "The Indian Express", "IE", "Explained, editorials, UPSC current affairs",
            listOf(
                Feed("Explained", "https://indianexpress.com/section/explained/feed/"),
                Feed("Editorial", "https://indianexpress.com/section/opinion/editorials/feed/"),
                Feed("UPSC", "https://indianexpress.com/section/upsc-current-affairs/feed/"),
                Feed("India", "https://indianexpress.com/section/india/feed/"),
            ),
            "https://indianexpress.com", "https://epaper.indianexpress.com", 0xFFD9342B,
        ),
        NewsSource(
            "pib", "PIB", "PIB", "Press Information Bureau · Government of India releases",
            listOf(Feed("Releases", "https://pib.gov.in/RssMain.aspx?ModId=6&Lang=1&Regid=3")),
            "https://pib.gov.in", null, 0xFF0E8F6E,
        ),
        NewsSource(
            "air", "News On AIR", "AIR", "Akashvani news service",
            listOf(Feed("News", "https://www.newsonair.gov.in/feed/")),
            "https://www.newsonair.gov.in", null, 0xFFB05CFF,
        ),
        NewsSource(
            "dte", "Down To Earth", "DTE", "Environment, climate and development",
            listOf(Feed("Environment", "https://www.downtoearth.org.in/feed")),
            "https://www.downtoearth.org.in", null, 0xFF2E9E3F,
        ),
        NewsSource(
            "mint", "Mint", "MINT", "Economy and policy",
            listOf(Feed("Economy", "https://www.livemint.com/rss/economy"), Feed("Politics", "https://www.livemint.com/rss/politics")),
            "https://www.livemint.com", "https://epaper.livemint.com", 0xFFF28C1B,
        ),
        NewsSource(
            "bl", "BusinessLine", "BL", "Economy and markets from The Hindu group",
            listOf(Feed("Economy", "https://www.thehindubusinessline.com/economy/feeder/default.rss")),
            "https://www.thehindubusinessline.com", "https://epaper.thehindubusinessline.com", 0xFF00838F,
        ),
        NewsSource(
            "bs", "Business Standard", "BS", "Economy and finance",
            listOf(Feed("Economy", "https://www.business-standard.com/rss/economy-102.rss")),
            "https://www.business-standard.com", "https://epaper.business-standard.com", 0xFFC2185B,
        ),
        NewsSource(
            "ht", "Hindustan Times", "HT", "India news",
            listOf(Feed("India", "https://www.hindustantimes.com/feeds/rss/india-news/rssfeed.xml")),
            "https://www.hindustantimes.com", "https://epaper.hindustantimes.com", 0xFF00A3B4,
        ),
        NewsSource(
            "toi", "The Times of India", "TOI", "India news",
            listOf(Feed("India", "https://timesofindia.indiatimes.com/rssfeeds/-2128936835.cms")),
            "https://timesofindia.indiatimes.com", "https://epaper.indiatimes.com", 0xFFE5484D,
        ),
    )

    fun custom(c: CustomSource) = NewsSource(
        c.id, c.name, c.name.take(3).uppercase(), "Your source", listOf(Feed("Feed", c.url)),
        c.url, null, 0xFF7A5CFF, custom = true,
    )

    fun all(s: Settings): List<NewsSource> = builtIn + s.customSources.map(::custom)

    fun enabled(s: Settings): List<NewsSource> = all(s).filter { it.id in s.enabledSources || it.custom }

    fun byId(s: Settings, id: String): NewsSource? = all(s).firstOrNull { it.id == id }
}

/** An e-paper on its publisher's own site, opened live in the in-app browser. */
data class EPaper(val name: String, val url: String, val free: Boolean)

object EPapers {
    val all = listOf(
        EPaper("The Tribune", "https://epaper.tribuneindia.com/", true),
        EPaper("The Statesman", "https://epaper.thestatesman.com/", true),
        EPaper("Free Press Journal", "https://epaper.freepressjournal.in/", true),
        EPaper("The Hans India", "https://epaper.thehansindia.com/", true),
        EPaper("Telangana Today", "https://epaper.telanganatoday.com/", true),
        EPaper("The Pioneer", "https://epaper.dailypioneer.com/", true),
        EPaper("The Daily Guardian", "https://epaper.thedailyguardian.com/", true),
        EPaper("Navhind Times", "https://epaper.navhindtimes.in/", true),
        EPaper("The Hindu", "https://epaper.thehindu.com/", false),
        EPaper("The Indian Express", "https://epaper.indianexpress.com/", false),
        EPaper("Hindustan Times", "https://epaper.hindustantimes.com/", false),
        EPaper("Mint", "https://epaper.livemint.com/", false),
        EPaper("Business Standard", "https://epaper.business-standard.com/", false),
    )
}

/** Hosts that only serve ads or tracking; the in-app browser can skip them. */
object AdBlock {
    private val hosts = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "googletagservices.com", "adservice.google.",
        "google-analytics.com", "googletagmanager.com", "taboola.com", "outbrain.com", "criteo.com", "criteo.net",
        "adnxs.com", "amazon-adsystem.com", "pubmatic.com", "rubiconproject.com", "moatads.com", "scorecardresearch.com",
        "adsrvr.org", "openx.net", "smartadserver.com", "media.net", "zedo.com", "quantserve.com", "chartbeat.com",
        "izooto.com", "vdo.ai", "colombiaonline.com", "adform.net", "teads.tv", "inmobi.com", "facebook.net",
    )

    fun blocks(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return hosts.any { h == it || h.endsWith(".$it") || (it.endsWith(".") && h.contains(it)) }
    }
}

class NewsRepo {
    /** Fetches every section of a source. Throws only when no section could be read. */
    suspend fun fetch(source: NewsSource): List<NewsItem> = coroutineScope {
        val results = source.feeds.map { feed ->
            async { runCatching { fetchFeed(source, feed) } }
        }.awaitAll()
        if (results.all { it.isFailure }) throw results.first().exceptionOrNull() ?: IllegalStateException("No feed")
        results.flatMap { it.getOrDefault(emptyList()) }.distinctBy { it.id }
    }

    private suspend fun fetchFeed(source: NewsSource, feed: Feed): List<NewsItem> {
        val url = normalise(feed.url)
        val body = Http.getText(url)
        return if (url.contains("://t.me/")) parseTelegram(source, feed, body) else parseFeed(source, feed, body)
    }

    private fun normalise(url: String): String {
        val u = url.trim().let { if (it.startsWith("http", true)) it else "https://$it" }
        // A public Telegram channel is read through Telegram's own web preview.
        val tg = Regex("""https?://(?:t\.me|telegram\.me)/(?:s/)?([A-Za-z0-9_]{4,})/?$""").find(u)
        return if (tg != null) "https://t.me/s/${tg.groupValues[1]}" else u
    }

    private fun parseFeed(source: NewsSource, feed: Feed, xml: String): List<NewsItem> {
        val doc = Jsoup.parse(xml.trimStart('﻿'), "", Parser.xmlParser())
        val now = System.currentTimeMillis()
        val nodes = doc.select("item").ifEmpty { doc.select("entry") }
        return nodes.mapIndexedNotNull { index, n ->
            val title = clean(n.firstText("title"))
            val link = n.firstText("link").ifBlank { n.select("link[href]").attr("href") }.trim()
            if (title.isBlank() || !link.startsWith("http")) return@mapIndexedNotNull null
            val rawSnippet = n.firstText("description").ifBlank { n.firstText("summary") }
                .ifBlank { n.firstText("content:encoded") }.ifBlank { n.firstText("content") }
            val published = Dates.parse(n.firstText("pubDate")) ?: Dates.parse(n.firstText("published"))
                ?: Dates.parse(n.firstText("updated")) ?: Dates.parse(n.firstText("dc:date"))
                // Feeds without dates (PIB) list newest first: keep that order.
                ?: (now - index * 1000L)
            NewsItem(
                id = idOf(link), sourceId = source.id, section = feed.section, title = title,
                snippet = shorten(clean(rawSnippet)), link = link,
                publishedAt = minOf(published, now), dayKey = Dates.dayKey(minOf(published, now)),
            )
        }
    }

    private fun parseTelegram(source: NewsSource, feed: Feed, html: String): List<NewsItem> {
        val doc = Jsoup.parse(html)
        val now = System.currentTimeMillis()
        return doc.select(".tgme_widget_message").mapNotNull { m ->
            val text = m.selectFirst(".tgme_widget_message_text")?.wholeText()?.trim().orEmpty()
            val link = m.selectFirst("a.tgme_widget_message_date")?.attr("href").orEmpty()
            if (text.isBlank() || !link.startsWith("http")) return@mapNotNull null
            val published = Dates.parse(m.selectFirst("time[datetime]")?.attr("datetime")) ?: now
            val firstLine = text.lineSequence().first { it.isNotBlank() }.trim()
            NewsItem(
                id = idOf(link), sourceId = source.id, section = feed.section,
                title = if (firstLine.length > 160) firstLine.take(157) + "…" else firstLine,
                snippet = shorten(text.removePrefix(firstLine).replace(Regex("\\s+"), " ").trim()),
                link = link, publishedAt = published, dayKey = Dates.dayKey(published),
            )
        }
    }

    private fun Element.firstText(tag: String): String {
        val e = children().firstOrNull { it.tagName().equals(tag, ignoreCase = true) } ?: return ""
        return e.wholeText().ifBlank { e.text() }
    }

    private fun clean(raw: String): String =
        if (raw.isBlank()) "" else Jsoup.parse(raw).text().replace(Regex("\\s+"), " ").trim()

    /** Keeps the snippet short on purpose: a pointer to the article, not a copy of it. */
    private fun shorten(s: String, max: Int = 280): String {
        if (s.length <= max) return s
        val cut = s.take(max)
        return cut.substring(0, cut.lastIndexOf(' ').takeIf { it > max / 2 } ?: max).trimEnd(',', ';', ':') + "…"
    }

    private fun idOf(link: String): String =
        MessageDigest.getInstance("SHA-1").digest(link.toByteArray()).take(10).joinToString("") { "%02x".format(it) }
}
