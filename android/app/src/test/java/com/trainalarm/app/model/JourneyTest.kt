package com.trainalarm.app.model

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JourneyTest {
    private val paddington = Station("PAD", "London Paddington", 51.5154, -0.1755)
    private val reading = Station("RDG", "Reading", 51.4585, -0.9781)
    private val bristol = Station("BRI", "Bristol Temple Meads", 51.4491, -2.5813)
    private val time = Instant.parse("2026-09-13T08:00:00Z")

    private fun stopAt(station: Station) = Stop(station, time, time, time, time)

    @Test
    fun destinationStop_findsDestinationInCallingPattern() {
        val journey = Journey(paddington, bristol, listOf(stopAt(paddington), stopAt(reading), stopAt(bristol)))
        assertEquals(bristol.id, journey.destinationStop?.station?.id)
    }

    @Test
    fun destinationStop_isNullWhenDestinationDroppedFromPattern() {
        // Models the "diverted/terminated early" case from docs/spec.md §4 -
        // the destination station is no longer in the calling pattern.
        val journey = Journey(paddington, bristol, listOf(stopAt(paddington), stopAt(reading)))
        assertNull(journey.destinationStop)
    }

    @Test(expected = IllegalArgumentException::class)
    fun journey_rejectsEmptyStopList() {
        Journey(paddington, bristol, emptyList())
    }
}
