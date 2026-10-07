package com.trainalarm.app.provider

import com.trainalarm.app.model.Journey
import com.trainalarm.app.model.Service
import com.trainalarm.app.model.Station
import com.trainalarm.app.model.Stop
import com.trainalarm.app.model.StopDisplay
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Null for a missing key or a JSON null. Real Android org.json's
 * `optString(key, null)` returns the string "null" for a JSON null, so never use that form.
 */
internal fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key)

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
class RttProvider(
    private val accessToken: String,
    private val transport: HttpTransport = HttpUrlConnectionTransport()
) : TrainDataProvider {

    private val baseUrl = "https://data.rtt.io"

    override suspend fun searchStations(query: String): List<Station> {
        // RTT's API is location-code based (CRS/TIPLOC), not a free-text
        // station search endpoint - see docs/spec.md §3. A real
        // implementation needs a separate static station name->code
        // dataset (e.g. the UK CRS code list) to resolve free text before
        // calling /gb-nr/location. Left unimplemented rather than guessed.
        throw ProviderError.NotImplemented(
            "RTT has no free-text station search endpoint; resolve to a " +
                "CRS/TIPLOC code via a static station list first."
        )
    }

    override suspend fun departureBoard(station: Station, from: Instant): List<Service> {
        // 204 is "valid query, no services found": an empty board, not an error.
        val json = getJson(
            "/gb-nr/location",
            mapOf("code" to station.id, "timeFrom" to from.toString())
        ) ?: return emptyList()
        val services = json.optJSONArray("services") ?: JSONArray()
        return (0 until services.length())
            .mapNotNull { services.optJSONObject(it) }
            .map { RttMapper.serviceSummary(it, station) }
    }

    /** [date] is currently ignored: [serviceId] already identifies the day. Reuse `service.id` when polling. */
    override suspend fun serviceDetails(serviceId: String, date: java.time.LocalDate): Service {
        val json = getJson("/gb-nr/service", mapOf("uniqueIdentity" to serviceId))
            ?: throw ProviderError.Malformed("empty (204) response")
        val service = json.optJSONObject("service")
            ?: throw ProviderError.Malformed("missing 'service' object")
        return RttMapper.fullService(service)
    }

    /**
     * Returns null for HTTP 204. Only IOException and JSONException are caught, never
     * Exception, so CancellationException propagates unchanged.
     */
    private suspend fun getJson(path: String, query: Map<String, String>): JSONObject? {
        val qs = query.entries.joinToString("&") { (k, v) ->
            "$k=${java.net.URLEncoder.encode(v, "UTF-8")}"
        }
        val result = try {
            transport.get(
                "$baseUrl$path?$qs",
                mapOf("Authorization" to "Bearer $accessToken", "Accept" to "application/json")
            )
        } catch (e: IOException) {
            throw ProviderError.Network(e.message ?: "I/O failure", e)
        }
        if (result.status == 204) return null
        if (result.status !in 200..299) throw ProviderError.Http(result.status)
        if (result.body.isBlank()) throw ProviderError.Malformed("empty response body")
        return try {
            JSONObject(result.body)
        } catch (e: JSONException) {
            throw ProviderError.Malformed("invalid JSON: ${e.message}", e)
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
            else geographicLocation.optStringOrNull("description") ?: "UNKNOWN"
        return Station(
            id = id,
            name = geographicLocation.optStringOrNull("description") ?: id,
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
        return parseTime(temporal.optStringOrNull("realtimeActual"))
            ?: parseTime(temporal.optStringOrNull("realtimeForecast"))
            ?: parseTime(temporal.optStringOrNull("realtimeEstimate"))
    }

    private fun scheduled(temporal: JSONObject?): Instant? =
        parseTime(temporal?.optStringOrNull("scheduleAdvertised"))

    private fun isActivityCancelled(activity: JSONObject?): Boolean =
        activity?.optBoolean("isCancelled", false) ?: false

    /** Absent or JSON null is null; an unrecognised string is UNKNOWN. */
    private fun displayAs(raw: String?): StopDisplay? = when (raw) {
        null -> null
        "CALL" -> StopDisplay.CALL
        "CANCELLED" -> StopDisplay.CANCELLED
        "DIVERTED" -> StopDisplay.DIVERTED
        "STARTS" -> StopDisplay.STARTS
        "TERMINATES" -> StopDisplay.TERMINATES
        "PASS" -> StopDisplay.PASS
        else -> StopDisplay.UNKNOWN
    }

    /**
     * Builds a [Stop] from one location's `temporalData`. The `pass` activity is
     * deliberately not mapped: a PASS stop has null arrival and departure.
     */
    private fun makeStop(station: Station, temporal: JSONObject): Stop {
        val arrival = temporal.optJSONObject("arrival")
        val departure = temporal.optJSONObject("departure")
        return Stop(
            station = station,
            scheduledArrival = scheduled(arrival),
            scheduledDeparture = scheduled(departure),
            estimatedArrival = bestRealtime(arrival),
            estimatedDeparture = bestRealtime(departure),
            isArrivalCancelled = isActivityCancelled(arrival),
            isDepartureCancelled = isActivityCancelled(departure),
            displayAs = displayAs(temporal.optStringOrNull("displayAs")),
            hasArrived = parseTime(arrival?.optStringOrNull("realtimeActual")) != null
        )
    }

    /** Wraps org.json's JSONException (missing required field) so both mappers fail alike. */
    private inline fun <T> asMalformed(what: String, block: () -> T): T =
        try {
            block()
        } catch (e: JSONException) {
            throw ProviderError.Malformed("$what: ${e.message}", e)
        }

    /** Maps one entry of NetworkRailServiceLocations into a [Stop]. */
    fun stop(serviceLocation: JSONObject): Stop = asMalformed("service location") {
        val location = station(serviceLocation.getJSONObject("location"))
        makeStop(location, serviceLocation.getJSONObject("temporalData"))
    }

    /**
     * A /gb-nr/location entry only carries this one station's temporal data
     * plus origin/destination - not the full calling pattern. This builds a
     * single-stop Journey suitable for a departure-board picker screen;
     * call [RttProvider.serviceDetails] afterward for the full stop list.
     */
    fun serviceSummary(lineUp: JSONObject, atStation: Station): Service = asMalformed("location line-up") {
        val scheduleMetadata = lineUp.getJSONObject("scheduleMetadata")
        val thisStop = makeStop(atStation, lineUp.getJSONObject("temporalData"))
        val originPairs = lineUp.optJSONArray("origin")
        val destinationPairs = lineUp.optJSONArray("destination")
        val origin = if (originPairs != null && originPairs.length() > 0)
            station(originPairs.getJSONObject(0).getJSONObject("location")) else atStation
        val destination = if (destinationPairs != null && destinationPairs.length() > 0)
            station(destinationPairs.getJSONObject(0).getJSONObject("location")) else atStation

        Service(
            id = scheduleMetadata.getString("uniqueIdentity"),
            operatorName = scheduleMetadata.optJSONObject("operator")?.optStringOrNull("name") ?: "Unknown",
            journey = Journey(origin, destination, listOf(thisStop))
        )
    }

    /** Maps a full /gb-nr/service response body into a [Service] with its complete stop list. */
    fun fullService(service: JSONObject): Service = asMalformed("service") {
        val scheduleMetadata = service.getJSONObject("scheduleMetadata")
        val locations = service.getJSONArray("locations")
        if (locations.length() == 0) throw ProviderError.Malformed("service had no locations")
        val stops = (0 until locations.length()).map { i -> stop(locations.getJSONObject(i)) }

        val originPairs = service.optJSONArray("origin")
        val destinationPairs = service.optJSONArray("destination")
        val origin = if (originPairs != null && originPairs.length() > 0)
            station(originPairs.getJSONObject(0).getJSONObject("location")) else stops.first().station
        val destination = if (destinationPairs != null && destinationPairs.length() > 0)
            station(destinationPairs.getJSONObject(0).getJSONObject("location")) else stops.last().station

        Service(
            id = scheduleMetadata.getString("uniqueIdentity"),
            operatorName = scheduleMetadata.optJSONObject("operator")?.optStringOrNull("name") ?: "Unknown",
            journey = Journey(origin, destination, stops)
        )
    }
}
