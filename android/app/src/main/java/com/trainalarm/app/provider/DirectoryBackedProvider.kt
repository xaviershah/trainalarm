package com.trainalarm.app.provider

import com.trainalarm.app.model.Service
import com.trainalarm.app.model.Station
import java.time.Instant
import java.time.LocalDate

/** RTT stations carry latitude and longitude 0 until resolved through [StationDirectory]. */
val Station.hasCoordinate: Boolean get() = latitude != 0.0 || longitude != 0.0

/**
 * Wraps a provider so that stations get real coordinates and station search works. RTT has no
 * search endpoint and its stations have no coordinates (docs/spec.md section 3), so both come from
 * the bundled NaPTAN directory, keyed by CRS. Stations the directory does not know stay as RTT
 * gave them, with `hasCoordinate == false`.
 */
class DirectoryBackedProvider(
    private val base: TrainDataProvider,
    private val directory: StationDirectory
) : TrainDataProvider {

    override suspend fun searchStations(query: String): List<Station> = directory.search(query)

    override suspend fun departureBoard(station: Station, from: Instant, callingAt: Station?): List<Service> =
        base.departureBoard(station, from, callingAt).map(::resolved)

    override suspend fun serviceDetails(serviceId: String, date: LocalDate): Service =
        resolved(base.serviceDetails(serviceId, date))

    private fun resolve(station: Station): Station = directory.station(station.id) ?: station

    private fun resolved(service: Service): Service {
        val journey = service.journey
        return service.copy(
            journey = journey.copy(
                origin = resolve(journey.origin),
                destination = resolve(journey.destination),
                stops = journey.stops.map { it.copy(station = resolve(it.station)) }
            )
        )
    }
}
