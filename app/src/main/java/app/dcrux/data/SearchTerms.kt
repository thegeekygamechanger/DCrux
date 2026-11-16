package app.dcrux.data

import java.util.Calendar
import java.util.TimeZone

/**
 * What a typed search means: the words worth looking for, and the day it names, if any.
 * "prashant sir 14 nov current affairs" is the words prashant, current, affairs and the day 14 November.
 */
data class SearchTerms(
    val words: List<String>,
    /** The day and month as they would be written in a title ("14", "nov"); empty when no date was typed. */
    val day: String = "", val month: String = "",
    /** The named day, as a span of time on this device's clock. */
    val from: Long = 0, val to: Long = 0,
) {
    val dated: Boolean get() = to > from
    private val count: Int get() = words.size + if (dated) 1 else 0
    val blank: Boolean get() = count == 0

    /** How many of the terms a result must contain: both of two, otherwise at least half, so one word that is not there does not hide everything. */
    val need: Int get() = if (count <= 2) count else (count + 1) / 2

    fun word(i: Int): String = words.getOrElse(i) { "" }

    companion object {
        const val MAX_WORDS = 6
        private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
        private val FULL = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")

        // Words that are in nearly every title or carry no meaning by themselves.
        private val FILLER = setOf(
            "the", "a", "an", "of", "for", "in", "on", "by", "and", "or", "to", "with", "from", "at", "is", "are", "was", "about",
            "sir", "mam", "maam", "madam", "ji", "video", "videos", "class", "lecture", "please", "show", "me", "find",
            // Everything here is current affairs, so these say nothing about which item is wanted.
            "current", "affairs", "daily",
        )

        private fun monthOf(token: String): Int = when {
            token.length < 3 -> -1
            token == "sept" -> 8
            else -> FULL.indexOfFirst { it == token || (token.length == 3 && it.startsWith(token)) }
        }

        private fun dayOf(token: String): Int =
            token.removeSuffix("st").removeSuffix("nd").removeSuffix("rd").removeSuffix("th").takeIf { it.length in 1..2 && it.all(Char::isDigit) }?.toInt()?.takeIf { it in 1..31 } ?: -1

        private fun yearOf(token: String?): Int = token?.takeIf { it.length == 4 && it.all(Char::isDigit) }?.toInt()?.takeIf { it in 2000..2100 } ?: -1

        fun of(query: String, now: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): SearchTerms {
            var text = query.lowercase()
            var day = -1; var month = -1; var year = -1
            // 14/11, 14-11-2026: day first, as dates are written in India.
            Regex("""(?<![\d])(\d{1,2})[/-](\d{1,2})(?:[/-](\d{2,4}))?(?![\d])""").find(text)?.let { m ->
                val d = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt()
                if (d in 1..31 && mo in 1..12) {
                    day = d; month = mo - 1
                    m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()?.let { year = if (it < 100) 2000 + it else it }
                    text = text.removeRange(m.range)
                }
            }
            val tokens = text.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.toMutableList()
            val today = Calendar.getInstance(zone).apply { timeInMillis = now }
            fun take(vararg at: Int) { at.sortedDescending().forEach { tokens.removeAt(it) } }
            if (day < 0) {
                // "14 nov", "14th november 2026", "nov 14"
                for (i in tokens.indices) {
                    val mo = monthOf(tokens[i])
                    if (mo < 0) continue
                    val before = if (i > 0) dayOf(tokens[i - 1]) else -1
                    val after = if (i + 1 < tokens.size) dayOf(tokens[i + 1]) else -1
                    if (before < 0 && after < 0) continue
                    month = mo
                    if (before > 0) {
                        day = before
                        year = yearOf(tokens.getOrNull(i + 1))
                        if (year > 0) take(i - 1, i, i + 1) else take(i - 1, i)
                    } else {
                        day = after
                        year = yearOf(tokens.getOrNull(i + 2))
                        if (year > 0) take(i, i + 1, i + 2) else take(i, i + 1)
                    }
                    break
                }
            }
            if (day < 0) {
                val at = tokens.indexOfFirst { it == "today" || it == "yesterday" }
                if (at >= 0) {
                    val c = today.clone() as Calendar
                    if (tokens[at] == "yesterday") c.add(Calendar.DAY_OF_MONTH, -1)
                    day = c.get(Calendar.DAY_OF_MONTH); month = c.get(Calendar.MONTH); year = c.get(Calendar.YEAR)
                    take(at)
                }
            }
            var from = 0L; var to = 0L
            if (day > 0) {
                val c = Calendar.getInstance(zone).apply { clear(); set(if (year > 0) year else today.get(Calendar.YEAR), month, day) }
                // No year typed and the day has not come yet this year: the person means last year's.
                if (year < 0 && c.timeInMillis - now > 2 * 86_400_000L) c.add(Calendar.YEAR, -1)
                from = c.timeInMillis
                to = (c.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
            }
            val meaningful = tokens.filter { it !in FILLER && (it.length > 1 || it[0].isDigit()) }
            val words = meaningful.ifEmpty { if (day > 0) emptyList() else tokens }.distinct().take(MAX_WORDS)
            return SearchTerms(words, if (day > 0) "$day" else "", if (day > 0) MONTHS[month] else "", from, to)
        }
    }
}
