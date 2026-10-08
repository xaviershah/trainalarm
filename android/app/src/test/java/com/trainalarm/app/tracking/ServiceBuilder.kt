package com.trainalarm.app.tracking

import com.trainalarm.app.model.Journey
import com.trainalarm.app.model.Service
import com.trainalarm.app.model.Station
import com.trainalarm.app.model.Stop
import com.trainalarm.app.model.StopDisplay
import com.trainalarm.app.provider.RttMapper
import org.json.JSONObject
import java.time.Instant

/**
 * Builds [Service] values for tracker tests. All times are on 2026-09-13 UTC, like the
 * fixtures. Provider stations carry latitude/longitude 0, exactly as [RttMapper] produces them.
 */
object ServiceBuilder {
    const val HAPPY_PATH_ID = "gb-nr:L01525:2026-09-13"

    fun time(hhmmss: String): Instant = Instant.parse("2026-09-13T${hhmmss}Z")

    fun station(id: String) = Station(id, id, 0.0, 0.0)

    fun stop(
        id: String,
        scheduledArrival: String? = null,
        estimatedArrival: String? = null,
        isArrivalCancelled: Boolean = false,
        isDepartureCancelled: Boolean = false,
        displayAs: StopDisplay? = null,
        hasArrived: Boolean = false
    ) = Stop(
        station = station(id),
        scheduledArrival = scheduledArrival?.let(::time),
        scheduledDeparture = null,
        estimatedArrival = estimatedArrival?.let(::time),
        estimatedDeparture = null,
        isArrivalCancelled = isArrivalCancelled,
        isDepartureCancelled = isDepartureCancelled,
        displayAs = displayAs,
        hasArrived = hasArrived
    )

    fun service(stops: List<Stop>, id: String = HAPPY_PATH_ID) = Service(
        id = id,
        operatorName = "South Western Railway",
        journey = Journey(stops.first().station, stops.last().station, stops)
    )

    /** WAT -> WOK -> BSK, mirroring `gb-nr-service.json`: BSK is scheduled 08:47:00. */
    fun happyPath(destinationEstimate: String? = "08:52:00", id: String = HAPPY_PATH_ID) = service(
        listOf(
            stop("WAT"),
            stop("WOK", scheduledArrival = "08:23:00", estimatedArrival = "08:27:00"),
            stop("BSK", scheduledArrival = "08:47:00", estimatedArrival = destinationEstimate)
        ),
        id
    )

    /** Loads a shared fixture (without the `.json` extension) through the real mapper. */
    fun loadFixture(name: String): Service {
        val stream = ServiceBuilder::class.java.getResourceAsStream("/fixtures/$name.json")
            ?: error("Fixture $name.json not found on test classpath")
        return RttMapper.fullService(JSONObject(stream.bufferedReader().readText()).getJSONObject("service"))
    }

    /** A copy of [service] with the stop at [index] replaced. */
    fun replacing(service: Service, index: Int, stop: Stop): Service {
        val stops = service.journey.stops.toMutableList()
        stops[index] = stop
        return service.copy(journey = Journey(service.journey.origin, service.journey.destination, stops))
    }
}
