package app.dcrux.desktop

import app.dcrux.data.Channel
import app.dcrux.data.SearchTerms
import app.dcrux.data.Video
import app.dcrux.data.search
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Calendar
import java.util.TimeZone

/** A self-check of search: a small library of real-looking titles, and the queries a person would type. Usage: SearchCheck <folder> */
fun main(args: Array<String>) {
    val root = File(args.getOrElse(0) { "build/search-check" }).apply { deleteRecursively(); mkdirs() }
    var failed = 0
    fun check(what: String, ok: Boolean) { println((if (ok) "ok    " else "FAIL  ") + what); if (!ok) failed++ }
    val zone = TimeZone.getTimeZone("Asia/Kolkata")
    fun at(day: Int, hour: Int): Long = Calendar.getInstance(zone).apply { clear(); set(2026, Calendar.NOVEMBER, day, hour, 0) }.timeInMillis
    val now = at(17, 1)
    runBlocking {
        val g = desktopGraph(DesktopPlatform(root))
        g.db.channels().insert(Channel("ch", "UPSC Wallah", "https://youtube.com/channel/ch", addedAt = 1))
        g.db.videos().insertAll(listOf(
            Video(id = "h13", channelId = "ch", title = "The Hindu Analysis | 13th November 2026 | The Hindu Newspaper - Practice Questions | Prashant Sir", publishedAt = at(13, 9)),
            Video(id = "h14", channelId = "ch", title = "The Hindu Analysis | 14th November 2026 | The Hindu Newspaper - Practice Questions | Prashant Sir", publishedAt = at(14, 9)),
            Video(id = "h16", channelId = "ch", title = "The Hindu Analysis | 16th November 2026 | The Hindu Newspaper - Practice Questions | Prashant Sir", publishedAt = at(16, 9)),
            Video(id = "s14", channelId = "ch", title = "India Bangladesh Relation New Challenge For India! #physicswallah #upsc #currentaffairs", publishedAt = at(14, 11), isShort = true),
            Video(id = "daf", channelId = "ch", title = "How to Fill UPSC DAF 2 ? Complete Service Preference List, Cadre Rules & Hobby | UPSC Interview", publishedAt = at(14, 20)),
            Video(id = "eco", channelId = "ch", title = "Economy One Shot | Monetary Policy 2014 to 2026", publishedAt = at(10, 9)),
        ))
        g.db.videos().setSummary("h14", "- RBI keeps the repo rate unchanged\n- Supreme Court on the Governor's assent to bills", "captions", "m")
        suspend fun ids(q: String) = g.db.videos().search(SearchTerms.of(q, now, zone), 8).map { it.id }

        val asked = ids("prashant sir 14 nov current affairs")
        check("the search that failed finds the 14 November Hindu analysis first: $asked", asked.firstOrNull() == "h14")
        check("words in any order: ${ids("hindu prashant analysis")}", ids("analysis prashant hindu").toSet() == setOf("h13", "h14", "h16"))
        check("newest first among equals", ids("hindu analysis").firstOrNull() == "h16")
        check("a date by itself gives that day, the one titled with it first: ${ids("14 nov")}", ids("14 nov").let { it.first() == "h14" && it.toSet() == setOf("h14", "s14", "daf") })
        check("the date written other ways", listOf("14th november", "nov 14", "14 November 2026", "14/11", "14-11-2026").all { ids(it).firstOrNull() == "h14" })
        check("16 nov is the 16th's paper, not the 14th's: ${ids("hindu 16 nov")}", ids("hindu 16 nov").firstOrNull() == "h16")
        check("a day number is not found inside a year (14 is not 2014): ${ids("policy 14 nov")}", "eco" !in ids("14 nov"))
        check("the channel's name counts: ${ids("upsc wallah daf")}", ids("upsc wallah daf").firstOrNull() == "daf")
        check("words from the notes count: ${ids("repo rate")}", ids("repo rate") == listOf("h14"))
        check("capitals and punctuation do not matter", ids("  PRASHANT,  Hindu!! ").size == 3)
        check("two words must both be there: ${ids("bangladesh daf")}", ids("bangladesh daf").isEmpty())
        check("one missing word out of several does not hide the rest: ${ids("india bangladesh relation zzzz")}", ids("india bangladesh relation zzzz") == listOf("s14"))
        check("nothing matches nonsense", ids("qqqq zzzz").isEmpty())
        check("yesterday means the 16th: ${ids("hindu yesterday")}", ids("hindu yesterday").firstOrNull() == "h16")
        val t = SearchTerms.of("the of sir", now, zone)
        check("a search made only of filler words still searches for them: ${t.words}", t.words.isNotEmpty())
    }
    println(if (failed == 0) "ALL PASSED" else "$failed FAILED")
    kotlin.system.exitProcess(if (failed == 0) 0 else 1)
}
