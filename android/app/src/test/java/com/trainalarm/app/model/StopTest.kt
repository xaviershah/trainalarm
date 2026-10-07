package com.trainalarm.app.model

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StopTest {
    private val reading = Station("READING", "Reading", 51.4585, -0.9781)

    @Test
    fun bestArrival_prefersLiveEstimateOverSchedule() {
        val scheduled = Instant.parse("2026-09-13T08:00:00Z")
        val estimated = Instant.parse("2026-09-13T08:07:00Z")
        val stop = Stop(reading, scheduled, scheduled, estimated, estimated)
        assertEquals(estimated, stop.bestArrival)
    }

    @Test
    fun bestArrival_fallsBackToScheduleWhenNoLiveData() {
        val scheduled = Instant.parse("2026-09-13T08:00:00Z")
        val stop = Stop(reading, scheduled, scheduled, null, null)
        assertEquals(scheduled, stop.bestArrival)
    }

    @Test
    fun isCancelled_isFalseByDefault() {
        val stop = Stop(reading, null, null, null, null)
        assertFalse(stop.isArrivalCancelled)
        assertFalse(stop.isDepartureCancelled)
        assertFalse(stop.isCancelled)
        assertNull(stop.displayAs)
        assertFalse(stop.hasArrived)
    }

    @Test
    fun isCancelled_isTrueWhenOnlyDepartureIsCancelled() {
        val stop = Stop(reading, null, null, null, null, isDepartureCancelled = true)
        assertFalse(stop.isArrivalCancelled)
        assertTrue(stop.isDepartureCancelled)
        assertTrue(stop.isCancelled)
    }
}
