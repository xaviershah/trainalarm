package com.trainalarm.app.provider

import com.trainalarm.app.model.Journey
import com.trainalarm.app.model.Service
import com.trainalarm.app.model.Station
import com.trainalarm.app.model.Stop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Realtime Trains' next-generation API (data.rtt.io, gb-nr namespace).
 * Schema verified against the real OpenAPI spec on 2026-09-13 - see
 * docs/spec.md §3. NOT the legacy api.rtt.io, which RTT is shutting down
 * 30 Sep 2026.
 *
 * [accessToken] is a bearer token from https://api-portal.rtt.io - per
 * RTT's own docs, this must not ship inside a distributable client app;
 * it should be proxied through a server you control before release. Fine
 * for local development/testing in the meantime.
 */
class RttProvider(private val accessToken: String) : TrainDataProvider {

    private val baseUrl = "https://data.rtt.io"

    override suspend fun searchStations(query: String): List<Station> {
        // RTT's API is location-code based (CRS/TIPLOC), not a free-text
        // station search endpoint - see docs/spec.md §3. A real
        // implementation needs a separate static station name->code
        // dataset (e.g. the UK CRS code list) to resolve free text before
        // calling /gb-nr/location. Left unimplemented rather than guessed.
        throw NotImplementedError(
            "RTT has no free-text station search endpoint; resolve to a " +
                "CRS/TIPLOC code via a static station list first."
        )
    }

    override suspend fun departureBoard(station: Station, from: Instant): List<Service> {
        val json = getJson(
            "/gb-nr/location",
            mapOf("code" to station.id, "timeFrom" to from.toString())
        )
        val services = json.optJSONArray("services") ?: JSONArray()
        return (0 until services.length()).map { i ->
            RttMapper.serviceSummary(services.getJSONObject(i), station)
        }
    }

    override suspend fun serviceDetails(serviceId: String, date: java.time.LocalDate): Service {
        val json = getJson(
            "/gb-nr/service",
            mapOf("uniqueIdentity" to serviceId)
        )
        return RttMapper.fullService(json.getJSONObject("service"))
    }

    private suspend fun getJson(path: String, query: Map<String, String>): JSONObject =
        withContext(Dispatchers.IO) {
            val qs = query.entries.joinToString("&") { (k, v) ->
                "$k=${java.net.URLEncoder.encode(v, "UTF-8")}"
            }
            val url = URL("$baseUrl$path?$qs")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Accept", "application/json")
            try {
                val body = connection.inputStream.bufferedReader().use(BufferedReader::readText)
                JSONObject(body)
            } finally {
                connection.disconnect()
            }
        }
}

/**
 * Pure JSON -> domain-model mapping, factored out so it's testable against
 * fixture JSON without a network call. Field names here must match the
 * verified schema in docs/spec.md §3 - do not add a field without checking
 * it against the real spec first.
 */
object RttMapper {

    fun station(geographicLocation: JSONObject): Station {
        val shortCodes = geographicLocation.optJSONArray("shortCodes")
        val id = if (shortCodes != null && shortCodes.length() > 0) shortCodes.getString(0)
            else geographicLocation.optString("description", "UNKNOWN")
        return Station(
            id = id,
            name = geographicLocation.optString("description", id),
            // RTT's GeographicLocation doesn't carry lat/lon - a separate
            // static station dataset supplies coordinates for GPS use.
            // See docs/spec.md §3.
            latitude = 0.0,
            longitude = 0.0
        )
    }

    private fun parseTime(iso: String?): Instant? {
        if (iso.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(iso).toInstant()
        } catch (e: DateTimeParseException) {
            try { Instant.parse(iso) } catch (e2: DateTimeParseException) { null }
        }
    }

    /** Best real-time instant for one activity (arrival/departure), per IndividualTemporalData. */
    private fun bestRealtime(temporal: JSONObject?): Instant? {
        if (temporal == null) return null
        return parseTime(temporal.optString("realtimeActual", null))
            ?: parseTime(temporal.optString("realtimeForecast", null))
            ?: parseTime(temporal.optString("realtimeEstimate", null))
    }

    private fun scheduled(temporal: JSONObject?): Instant? =
        parseTime(temporal?.optString("scheduleAdvertised", null))

    private fun isCancelled(locationTemporalData: JSONObject): Boolean {
        val arrival = locationTemporalData.optJSONObject("arrival")
        val departure = locationTemporalData.optJSONObject("departure")
        return (arrival?.optBoolean("isCancelled", false) ?: false) ||
            (departure?.optBoolean("isCancelled", false) ?: false)
    }

    /** Maps one entry of NetworkRailServiceLocations into a [Stop]. */
    fun stop(serviceLocation: JSONObject): Stop {
        val location = station(serviceLocation.getJSONObject("location"))
        val temporal = serviceLocation.getJSONObject("temporalData")
        val arrival = temporal.optJSONObject("arrival")
        val departure = temporal.optJSONObject("departure")
        return Stop(
            station = location,
            scheduledArrival = scheduled(arrival),
            scheduledDeparture = scheduled(departure),
            estimatedArrival = bestRealtime(arrival),
            estimatedDeparture = bestRealtime(departure),
            isCancelled = isCancelled(temporal)
        )
    }

    /**
     * A /gb-nr/location entry only carries this one station's temporal data
     * plus origin/destination - not the full calling pattern. This builds a
     * single-stop Journey suitable for a departure-board picker screen;
     * call [RttProvider.serviceDetails] afterward for the full stop list.
     */
    fun serviceSummary(lineUp: JSONObject, atStation: Station): Service {
        val scheduleMetadata = lineUp.getJSONObject("scheduleMetadata")
        val temporal = lineUp.getJSONObject("temporalData")
        val thisStop = Stop(
            station = atStation,
            scheduledArrival = scheduled(temporal.optJSONObject("arrival")),
            scheduledDeparture = scheduled(temporal.optJSONObject("departure")),
            estimatedArrival = bestRealtime(temporal.optJSONObject("arrival")),
            estimatedDeparture = bestRealtime(temporal.optJSONObject("departure")),
            isCancelled = isCancelled(temporal)
        )
        val originPairs = lineUp.optJSONArray("origin")
        val destinationPairs = lineUp.optJSONArray("destination")
        val origin = if (originPairs != null && originPairs.length() > 0)
            station(originPairs.getJSONObject(0).getJSONObject("location")) else atStation
        val destination = if (destinationPairs != null && destinationPairs.length() > 0)
            station(destinationPairs.getJSONObject(0).getJSONObject("location")) else atStation

        return Service(
            id = scheduleMetadata.getString("uniqueIdentity"),
            operatorName = scheduleMetadata.optJSONObject("operator")?.optString("name") ?: "Unknown",
            journey = Journey(origin, destination, listOf(thisStop))
        )
    }

    /** Maps a full /gb-nr/service response body into a [Service] with its complete stop list. */
    fun fullService(service: JSONObject): Service {
        val scheduleMetadata = service.getJSONObject("scheduleMetadata")
        val locations = service.getJSONArray("locations")
        val stops = (0 until locations.length()).map { i -> stop(locations.getJSONObject(i)) }

        val originPairs = service.optJSONArray("origin")
        val destinationPairs = service.optJSONArray("destination")
        val origin = if (originPairs != null && originPairs.length() > 0)
            station(originPairs.getJSONObject(0).getJSONObject("location")) else stops.first().station
        val destination = if (destinationPairs != null && destinationPairs.length() > 0)
            station(destinationPairs.getJSONObject(0).getJSONObject("location")) else stops.last().station

        return Service(
            id = scheduleMetadata.getString("uniqueIdentity"),
            operatorName = scheduleMetadata.optJSONObject("operator")?.optString("name") ?: "Unknown",
            journey = Journey(origin, destination, stops)
        )
    }
}
