package com.trainalarm.app.provider

import com.trainalarm.app.model.Service
import com.trainalarm.app.model.Station
import com.trainalarm.app.model.StopDisplay
import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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

    @Test
    fun fullService_allStopsCancelledWhenServiceIsCancelled() {
        val fixture = loadFixture("gb-nr-service-cancelled.json")
        val service = RttMapper.fullService(fixture.getJSONObject("service"))

        assertEquals(3, service.journey.stops.size)
        assertTrue(service.journey.stops.all { it.isCancelled })
        // Cancelled stops still carry their scheduled times - a cancelled
        // service is not the same as "no data", the alarm/tracking layer
        // needs the schedule to know what was supposed to happen.
        assertEquals(
            Instant.parse("2026-09-13T09:47:00Z"),
            service.journey.destinationStop?.scheduledArrival
        )
        // No realtime fields are present on a cancelled stop in this fixture.
        assertEquals(null, service.journey.destinationStop?.estimatedArrival)
    }

    @Test(expected = ProviderError.Malformed::class)
    fun fullService_throwsOnMissingLocationsField() {
        // A malformed/incomplete response (missing "locations" entirely)
        // must fail loudly, not silently produce an empty or wrong journey.
        val fixture = loadFixture("gb-nr-service-malformed.json")
        RttMapper.fullService(fixture.getJSONObject("service"))
    }

    @Test
    fun fullService_destinationStopIsNullWhenServiceTerminatesEarly() {
        // The service's "destination" field still says Basingstoke, but the
        // calling pattern only reaches Woking (TERMINATES) - models a real
        // early-termination/diversion case from docs/spec.md §4.
        val fixture = loadFixture("gb-nr-service-destination-dropped.json")
        val service = RttMapper.fullService(fixture.getJSONObject("service"))

        assertEquals("BSK", service.journey.destination.id)
        assertEquals(2, service.journey.stops.size)
        assertEquals(null, service.journey.destinationStop)
    }

    private fun fullService(name: String): Service =
        RttMapper.fullService(loadFixture("$name.json").getJSONObject("service"))

    @Test
    fun departureCancelledOnIntermediateStop_isNotArrivalCancelled() {
        val woking = fullService("gb-nr-service-departure-cancelled").journey.stops[1]
        assertFalse(woking.isArrivalCancelled)
        assertTrue(woking.isDepartureCancelled)
        assertTrue(woking.isCancelled)
    }

    @Test
    fun displayAs_isMappedPerStop() {
        val stops = fullService("gb-nr-service-display-as").journey.stops
        assertEquals(
            listOf(
                StopDisplay.CALL, StopDisplay.PASS, StopDisplay.DIVERTED,
                StopDisplay.CANCELLED, StopDisplay.UNKNOWN, StopDisplay.TERMINATES
            ),
            stops.map { it.displayAs }
        )
    }

    @Test
    fun passStop_hasNoArrivalOrDepartureBecausePassActivityIsNotMapped() {
        val pass = fullService("gb-nr-service-display-as").journey.stops[1]
        assertNull(pass.scheduledArrival)
        assertNull(pass.scheduledDeparture)
        assertNull(pass.bestArrival)
    }

    @Test
    fun hasArrived_onlyWhenArrivalHasRealtimeActual() {
        val stops = fullService("gb-nr-service-display-as").journey.stops
        assertEquals(listOf(false, false, false, false, false, true), stops.map { it.hasArrived })
    }

    @Test
    fun explicitJsonNulls_mapToNull() {
        val stops = fullService("gb-nr-service-nulls").journey.stops
        assertNull(stops[0].displayAs)
        assertNull(stops[0].estimatedDeparture)
        assertNull(stops[1].displayAs)
        assertNull(stops[1].estimatedArrival)
        assertFalse(stops[1].hasArrived)
    }

    @Test
    fun wrongCaseDisplayAs_isUnknown_andUnparseableActualIsNotArrived() {
        val location = JSONObject(
            """{"location": {"description": "Reading", "shortCodes": ["RDG"]},
                "temporalData": {
                  "arrival": {"scheduleAdvertised": "2026-09-13T08:00:00Z", "realtimeActual": "garbage"},
                  "displayAs": "call"}}"""
        )
        val stop = RttMapper.stop(location)
        assertEquals(StopDisplay.UNKNOWN, stop.displayAs)
        assertFalse(stop.hasArrived)
        assertNull(stop.estimatedArrival)
    }

    @Test
    fun nullOperatorName_fallsBackToUnknown() {
        // The JVM library's optString(key) returns "" for a JSON null, so this fails
        // without optStringOrNull (on a device it would be the string "null").
        val lineUp = JSONObject(
            """{"scheduleMetadata": {"uniqueIdentity": "gb-nr:X:2026-09-13", "operator": {"name": null}},
                "temporalData": {}}"""
        )
        val atStation = Station("WAT", "London Waterloo", 51.5031, -0.1132)
        assertEquals("Unknown", RttMapper.serviceSummary(lineUp, atStation).operatorName)
    }

    @Test
    fun fullService_throwsMalformedForEmptyLocations() {
        val service = JSONObject(
            """{"scheduleMetadata": {"uniqueIdentity": "gb-nr:X:2026-09-13"}, "locations": []}"""
        )
        val error = assertThrows(ProviderError.Malformed::class.java) { RttMapper.fullService(service) }
        assertEquals("service had no locations", error.reason)
    }

    @Test(expected = ProviderError.Malformed::class)
    fun stop_throwsMalformedWhenTemporalDataIsMissing() {
        RttMapper.stop(JSONObject("""{"location": {"description": "Reading"}}"""))
    }

    private val waterloo = Station("WAT", "London Waterloo", 51.5031, -0.1132)

    private fun lineUp(index: Int): JSONObject =
        loadFixture("gb-nr-location.json").getJSONArray("services").getJSONObject(index)

    @Test
    fun serviceSummary_mapsALineUpEntry() {
        val service = RttMapper.serviceSummary(lineUp(0), waterloo)

        assertEquals("gb-nr:L02001:2026-09-13", service.id)
        assertEquals("South Western Railway", service.operatorName)
        assertEquals("WAT", service.journey.origin.id)
        assertEquals("BSK", service.journey.destination.id)
        assertEquals(1, service.journey.stops.size)
        val stop = service.journey.stops[0]
        assertEquals(Instant.parse("2026-09-13T14:00:00Z"), stop.scheduledDeparture)
        assertEquals(Instant.parse("2026-09-13T14:02:00Z"), stop.estimatedDeparture)
        assertEquals(StopDisplay.CALL, stop.displayAs)
        assertFalse(stop.isCancelled)
    }

    @Test
    fun serviceSummary_mapsACancelledLineUp() {
        val service = RttMapper.serviceSummary(lineUp(1), waterloo)

        assertEquals("SOU", service.journey.destination.id)
        val stop = service.journey.stops[0]
        assertFalse(stop.isArrivalCancelled)
        assertTrue(stop.isDepartureCancelled)
        assertEquals(StopDisplay.CANCELLED, stop.displayAs)
    }

    @Test
    fun serviceSummary_fallsBackToTheBoardStationWithoutOriginOrDestination() {
        val bare = JSONObject(
            """{"scheduleMetadata": {"uniqueIdentity": "gb-nr:X:2026-09-13"},
                "temporalData": {"departure": {"scheduleAdvertised": "2026-09-13T14:00:00Z"}}}"""
        )
        val service = RttMapper.serviceSummary(bare, waterloo)
        assertEquals("WAT", service.journey.origin.id)
        assertEquals("WAT", service.journey.destination.id)
    }

    @Test(expected = ProviderError.Malformed::class)
    fun serviceSummary_throwsMalformedWithoutScheduleMetadata() {
        RttMapper.serviceSummary(JSONObject("""{"temporalData": {}}"""), waterloo)
    }
}
