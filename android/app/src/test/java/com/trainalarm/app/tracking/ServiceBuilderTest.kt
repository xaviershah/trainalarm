package com.trainalarm.app.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

class ServiceBuilderTest {
    @Test
    fun happyPathMirrorsTheSharedFixture() {
        val built = ServiceBuilder.happyPath()
        val loaded = ServiceBuilder.loadFixture("gb-nr-service")

        assertEquals(loaded.id, built.id)
        assertEquals(loaded.journey.stops.map { it.station.id }, built.journey.stops.map { it.station.id })
        assertEquals(loaded.journey.stops.map { it.scheduledArrival }, built.journey.stops.map { it.scheduledArrival })
        assertEquals(loaded.journey.stops.map { it.estimatedArrival }, built.journey.stops.map { it.estimatedArrival })
    }

    @Test
    fun replacingSwapsOneStopOnly() {
        val original = ServiceBuilder.happyPath()
        val changed = ServiceBuilder.replacing(
            original, 2, ServiceBuilder.stop("BSK", scheduledArrival = "08:47:00", estimatedArrival = "09:00:00")
        )

        assertEquals(original.journey.stops[0], changed.journey.stops[0])
        assertEquals(ServiceBuilder.time("09:00:00"), changed.journey.stops[2].estimatedArrival)
        assertEquals(original.id, changed.id)
    }
}
