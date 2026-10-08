package com.trainalarm.app.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackerConfigTest {
    @Test
    fun defaultsMatchTheSpec() {
        val config = TrackerConfig(leadTime = 600.0)
        assertEquals(600.0, config.leadTime, 0.0)
        assertEquals(60.0, config.rescheduleThreshold, 0.0)
        assertEquals(45.0, config.pollInterval, 0.0)
        assertEquals(60.0, config.guardMargin, 0.0)
        assertEquals(1.0, config.minPace, 0.0)
        assertEquals(3, config.maxFixAgePolls)
        assertEquals(135.0, config.maxFixAge, 0.0)
        assertEquals(200.0, config.maxFixAccuracy, 0.0)
        assertEquals(600.0, config.arrivalGrace, 0.0)
    }

    @Test
    fun maxFixAgeFollowsThePollInterval() {
        assertEquals(90.0, TrackerConfig(leadTime = 600.0, pollInterval = 30.0).maxFixAge, 0.0)
    }
}
