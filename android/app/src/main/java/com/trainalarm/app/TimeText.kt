package com.trainalarm.app

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Time text for the UI. Times are always shown in London time, whatever the device zone,
 * and countdowns come from absolute instants. See spec section 4.
 */
object TimeText {
    val london: ZoneId = ZoneId.of("Europe/London")

    data class HM(val hours: String, val minutes: String) {
        val plain: String get() = "$hours:$minutes"
    }

    private fun two(n: Number) = String.format(Locale.ROOT, "%02d", n)

    fun clock(at: Instant): HM {
        val t = at.atZone(london)
        return HM(two(t.hour), two(t.minute))
    }

    /** Time left as hours and minutes, rounded down. Negative values show 0:00. */
    fun countdown(seconds: Long): HM {
        val totalMinutes = maxOf(0L, seconds) / 60
        return HM((totalMinutes / 60).toString(), two(totalMinutes % 60))
    }

    /** null on the same London day as [now], otherwise a label such as "Thu 2 Jul". Locale.US keeps month names identical to iOS. */
    fun dateLabel(date: Instant, now: Instant): String? {
        val day = date.atZone(london).toLocalDate()
        if (day == now.atZone(london).toLocalDate()) return null
        return day.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.US))
    }
}
