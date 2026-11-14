package app.dcrux.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object Dates {
    private val patterns = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z",
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "EEE, dd MMM yyyy HH:mm Z",
        "dd MMM yyyy HH:mm:ss Z",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd",
    )

    /** Parses the date formats used by RSS, Atom and YouTube; null when unreadable. */
    fun parse(raw: String?): Long? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        for (p in patterns) {
            try {
                val f = SimpleDateFormat(p, Locale.US)
                f.isLenient = false
                if (!p.contains('Z') && !p.contains('X') && !p.contains("zzz") || p.endsWith("'Z'")) {
                    f.timeZone = TimeZone.getTimeZone("UTC")
                }
                return f.parse(s)?.time ?: continue
            } catch (_: Exception) {
            }
        }
        return null
    }

    fun dayKey(ms: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))

    fun dayStart(dayKey: String): Long =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dayKey)?.time ?: 0L

    /** "Today", "Yesterday" or "Sat, 14 Nov 2026". */
    fun prettyDay(dayKey: String): String {
        val today = dayKey()
        if (dayKey == today) return "Today"
        if (dayKey == dayKey(System.currentTimeMillis() - 86_400_000L)) return "Yesterday"
        val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dayKey) ?: return dayKey
        return SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()).format(d)
    }

    fun longDay(ms: Long): String = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(Date(ms))

    fun shortDate(ms: Long): String = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(ms))

    fun time(ms: Long): String = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms))

    fun dateTime(ms: Long): String = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault()).format(Date(ms))

    fun ago(ms: Long): String {
        val diff = System.currentTimeMillis() - ms
        val min = diff / 60_000
        return when {
            ms <= 0 -> "never"
            min < 1 -> "just now"
            min < 60 -> "$min min ago"
            min < 60 * 24 -> "${min / 60} h ago"
            min < 60 * 24 * 7 -> "${min / (60 * 24)} d ago"
            else -> shortDate(ms)
        }
    }

    /** Milliseconds until the next occurrence of [hour]:[minute] local time. */
    fun untilNext(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0)
            if (!after(now)) add(Calendar.DAY_OF_YEAR, 1)
        }
        return next.timeInMillis - now.timeInMillis
    }

    fun dayOfEpoch(ms: Long = System.currentTimeMillis()): Long {
        val tz = TimeZone.getDefault()
        return (ms + tz.getOffset(ms)) / 86_400_000L
    }
}
