package com.trainalarm.app.tracking

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class LocationFixesTest {
    @Test fun fixCarriesPositionTimeAndAccuracy() {
        assertEquals(
            LocationFix(51.5, -0.12, Instant.ofEpochMilli(1_000_000), 25.0),
            LocationFixes.from(51.5, -0.12, 1_000_000, 25f)
        )
    }

    @Test fun absentAccuracyBecomesInfinite() =
        assertEquals(Double.POSITIVE_INFINITY, LocationFixes.from(51.5, -0.12, 0, null).accuracy, 0.0)

    @Test fun negativeAccuracyBecomesInfinite() =
        assertEquals(Double.POSITIVE_INFINITY, LocationFixes.from(51.5, -0.12, 0, -1f).accuracy, 0.0)
}
