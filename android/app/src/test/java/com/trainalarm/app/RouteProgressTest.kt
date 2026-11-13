package com.trainalarm.app

import com.trainalarm.app.model.Stop
import com.trainalarm.app.tracking.ServiceBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteProgressTest {
    private fun stop(id: String, dep: String? = null, arr: String? = null) = Stop(
        ServiceBuilder.station(id), arr?.let(ServiceBuilder::time), dep?.let(ServiceBuilder::time), null, null
    )

    private val stops = listOf(stop("AAA", dep = "08:00:00"), stop("BBB", arr = "08:30:00"), stop("CCC", arr = "09:00:00"))
    private fun t(hhmmss: String) = ServiceBuilder.time(hhmmss)

    @Test fun beforeDeparture_theTrainHasNotMoved() {
        val p = RouteProgress.make(stops, "AAA", "CCC", null, t("07:50:00"))
        assertEquals(0.0, p?.fraction ?: -1.0, 0.0)
        assertEquals("BBB", p?.nextStop?.station?.id)
        assertEquals(2, p?.stopsLeft)
    }

    @Test fun halfwayThroughTheJourney() {
        val p = RouteProgress.make(stops, "AAA", "CCC", null, t("08:30:00"))
        assertEquals(0.5, p?.fraction ?: -1.0, 0.001)
        assertEquals("CCC", p?.nextStop?.station?.id)
        assertEquals(1, p?.stopsLeft)
    }

    @Test fun afterArrival_theStripIsFull() {
        val p = RouteProgress.make(stops, "AAA", "CCC", null, t("09:10:00"))
        assertEquals(1.0, p?.fraction ?: -1.0, 0.0)
        assertNull(p?.nextStop)
        assertEquals(0, p?.stopsLeft)
    }

    @Test fun aLiveEta_stretchesTheJourney() {
        val p = RouteProgress.make(stops, "AAA", "CCC", t("09:10:00"), t("08:35:00"))
        assertEquals(0.5, p?.fraction ?: -1.0, 0.001)
    }

    @Test fun aStationThatIsNotOnTheRoute_givesNothing() {
        assertNull(RouteProgress.make(stops, "ZZZ", "CCC", null, t("08:00:00")))
        assertNull(RouteProgress.make(stops, "AAA", "ZZZ", null, t("08:00:00")))
    }

    @Test fun aDestinationBeforeTheBoardingStop_givesNothing() =
        assertNull(RouteProgress.make(stops, "CCC", "AAA", null, t("08:00:00")))

    @Test fun missingDepartureTime_keepsTheMarkerAtTheStart() {
        val noTimes = listOf(stop("AAA"), stop("BBB", arr = "08:30:00"), stop("CCC", arr = "09:00:00"))
        assertEquals(0.0, RouteProgress.make(noTimes, "AAA", "CCC", null, t("08:45:00"))?.fraction ?: -1.0, 0.0)
    }
}
