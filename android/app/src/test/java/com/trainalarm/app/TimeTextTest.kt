package com.trainalarm.app

import java.time.Instant
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimeTextTest {
    private fun at(s: String) = Instant.parse(s)

    @Test fun clock_usesLondonTimeInSummer() =
        assertEquals(TimeText.HM("18", "50"), TimeText.clock(at("2026-07-01T17:50:00Z")))

    @Test fun clock_acrossTheMarchChangeover() {
        assertEquals(TimeText.HM("00", "59"), TimeText.clock(at("2026-03-29T00:59:00Z")))
        assertEquals(TimeText.HM("02", "01"), TimeText.clock(at("2026-03-29T01:01:00Z")))
    }

    @Test fun clock_acrossMidnight() {
        assertEquals(TimeText.HM("23", "59"), TimeText.clock(at("2026-07-01T22:59:00Z")))
        assertEquals(TimeText.HM("00", "01"), TimeText.clock(at("2026-07-01T23:01:00Z")))
    }

    @Test fun clock_ignoresTheDeviceZone() {
        val saved = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
        try {
            assertEquals("18:50", TimeText.clock(at("2026-07-01T17:50:00Z")).plain)
        } finally {
            TimeZone.setDefault(saved)
        }
    }

    @Test fun countdown_roundsDownToTheMinute() {
        assertEquals(TimeText.HM("2", "38"), TimeText.countdown(158L * 60 + 59))
        assertEquals(TimeText.HM("0", "12"), TimeText.countdown(12L * 60))
    }

    @Test fun countdown_neverGoesNegative() =
        assertEquals(TimeText.HM("0", "00"), TimeText.countdown(-30))

    @Test fun countdown_handlesZeroAndHugeValues() {
        assertEquals(TimeText.HM("0", "00"), TimeText.countdown(0))
        assertEquals(TimeText.HM("0", "00"), TimeText.countdown(Long.MIN_VALUE))
        assertEquals(true, TimeText.countdown(Long.MAX_VALUE).hours.isNotEmpty())
    }

    @Test fun dateLabel_isNullOnTheSameLondonDay() =
        assertNull(TimeText.dateLabel(at("2026-07-01T22:59:00Z"), at("2026-07-01T22:50:00Z")))

    @Test fun dateLabel_appearsAfterLondonMidnight() =
        assertEquals("Thu 2 Jul", TimeText.dateLabel(at("2026-07-01T23:10:00Z"), at("2026-07-01T22:50:00Z")))
}
