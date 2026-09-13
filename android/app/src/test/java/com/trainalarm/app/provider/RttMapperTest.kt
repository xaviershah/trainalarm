package com.trainalarm.app.provider

import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Tests RttMapper against a fixture built from RTT's real, verified schema
 * (docs/spec.md §3) - not a live call. If this fixture ever stops matching
 * the real API, that's a signal to re-check the schema, not to loosen
 * these assertions.
 */
class RttMapperTest {

    private fun loadFixture(name: String): JSONObject {
        val stream = javaClass.getResourceAsStream("/fixtures/$name")
            ?: error("Fixture $name not found on test classpath")
        return JSONObject(stream.bufferedReader().readText())
    }

    @Test
    fun fullService_parsesCompleteCallingPattern() {
        val fixture = loadFixture("gb-nr-service.json")
        val service = RttMapper.fullService(fixture.getJSONObject("service"))

        assertEquals("gb-nr:L01525:2026-09-13", service.id)
        assertEquals("South Western Railway", service.operatorName)
        assertEquals("WAT", service.journey.origin.id)
        assertEquals("BSK", service.journey.destination.id)
        assertEquals(3, service.journey.stops.size)
    }

    @Test
    fun fullService_prefersLiveEstimateOverSchedule() {
        val fixture = loadFixture("gb-nr-service.json")
        val service = RttMapper.fullService(fixture.getJSONObject("service"))

        val woking = service.journey.stops[1]
        assertEquals(Instant.parse("2026-09-13T08:23:00Z"), woking.scheduledArrival)
        assertEquals(Instant.parse("2026-09-13T08:27:00Z"), woking.estimatedArrival)
    }

    @Test
    fun fullService_destinationStopResolvesFromCallingPattern() {
        val fixture = loadFixture("gb-nr-service.json")
        val service = RttMapper.fullService(fixture.getJSONObject("service"))

        val destinationStop = service.journey.destinationStop
        assertEquals("BSK", destinationStop?.station?.id)
        // realtimeEstimate is the only live field present for this stop -
        // bestRealtime must fall through to it, not just realtimeActual/Forecast.
        assertEquals(Instant.parse("2026-09-13T08:52:00Z"), destinationStop?.estimatedArrival)
    }

    @Test
    fun fullService_noStopsAreCancelledInHappyPathFixture() {
        val fixture = loadFixture("gb-nr-service.json")
        val service = RttMapper.fullService(fixture.getJSONObject("service"))
        assertFalse(service.journey.stops.any { it.isCancelled })
    }
}
