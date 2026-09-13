package com.trainalarm.app.provider

import com.trainalarm.app.model.Service
import com.trainalarm.app.model.Station
import java.time.Instant
import java.time.LocalDate

/**
 * Everything the rest of the app knows about talking to a country's rail
 * data. Tracking, ETA and alarm logic (Stages 2-3) depend ONLY on this
 * interface and the model types - never on a provider's raw response shape.
 * See docs/spec.md §2 for the architecture rationale.
 *
 * A concrete implementation (e.g. an RTT-backed provider) lives alongside
 * this file as a separate class implementing this interface.
 */
interface TrainDataProvider {

    /** Find stations matching a free-text search (e.g. a name the user typed). */
    suspend fun searchStations(query: String): List<Station>

    /** Live/scheduled departures from [station] at or after [from]. */
    suspend fun departureBoard(station: Station, from: Instant): List<Service>

    /**
     * The full stop-by-stop detail for one specific service, including
     * live times where available. Called both to select a journey and,
     * repeatedly, to poll for changes while a journey is active (§4).
     */
    suspend fun serviceDetails(serviceId: String, date: LocalDate): Service
}
