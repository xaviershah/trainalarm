package com.trainalarm.app.provider

import com.trainalarm.app.model.Journey
import com.trainalarm.app.model.Service
import com.trainalarm.app.model.Station
import com.trainalarm.app.model.Stop
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectoryBackedProviderTest {
    private class RecordingProvider(val service: Service) : TrainDataProvider {
        var lastCallingAt: Station? = null
        override suspend fun searchStations(query: String): List<Station> = throw ProviderError.NotImplemented("fake")
        override suspend fun departureBoard(station: Station, from: Instant, callingAt: Station?): List<Service> {
            lastCallingAt = callingAt
            return listOf(service)
        }
        override suspend fun serviceDetails(serviceId: String, date: LocalDate): Service = service
    }

    private val json = """[{"crs":"EDB","name":"Edinburgh","lat":55.9521,"lon":-3.1894},{"crs":"KGX","name":"London Kings Cross","lat":51.5309,"lon":-0.1233}]"""

    private fun rtt(id: String, name: String) = Station(id, name, 0.0, 0.0)
    private fun stop(s: Station) = Stop(s, null, null, null, null)

    private fun make(): Pair<DirectoryBackedProvider, RecordingProvider> {
        val kgx = rtt("KGX", "London Kings Cross")
        val xyz = rtt("XYZ", "Nowhere")
        val edb = rtt("EDB", "Edinburgh Waverley")
        val service = Service("s1", "LNER", Journey(kgx, edb, listOf(stop(kgx), stop(xyz), stop(edb))))
        val base = RecordingProvider(service)
        return DirectoryBackedProvider(base, StationDirectory.parse(json)) to base
    }

    @Test fun serviceDetails_getsRealCoordinatesForKnownStations() = runBlocking<Unit> {
        val (provider, _) = make()
        val service = provider.serviceDetails("s1", LocalDate.of(2026, 9, 13))
        assertEquals(55.9521, service.journey.destination.latitude, 0.0001)
        assertEquals("Edinburgh", service.journey.destination.name)
        assertEquals(-0.1233, service.journey.stops[0].station.longitude, 0.0001)
    }

    @Test fun unknownStation_keepsItsNameAndHasNoCoordinate() = runBlocking<Unit> {
        val (provider, _) = make()
        val unknown = provider.serviceDetails("s1", LocalDate.of(2026, 9, 13)).journey.stops[1].station
        assertEquals("Nowhere", unknown.name)
        assertFalse(unknown.hasCoordinate)
    }

    @Test fun departureBoard_isResolvedAndPassesCallingAtOn() = runBlocking<Unit> {
        val (provider, base) = make()
        val edb = rtt("EDB", "Edinburgh")
        val board = provider.departureBoard(rtt("KGX", "London Kings Cross"), Instant.parse("2026-09-13T14:00:00Z"), edb)
        assertEquals(edb, base.lastCallingAt)
        assertTrue(board[0].journey.destination.hasCoordinate)
    }

    @Test fun searchStations_answersFromTheDirectory() = runBlocking<Unit> {
        val (provider, _) = make()
        assertEquals(listOf("Edinburgh"), provider.searchStations("edin").map { it.name })
    }
}
