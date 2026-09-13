package com.trainalarm.app.model

/**
 * A user's planned trip: an origin, a destination, and the ordered stops of
 * the specific [Service] they've picked between them (inclusive of origin
 * and destination). Stage 2's live-change handling re-derives this from a
 * fresh [Service] on every poll - see docs/spec.md §4.
 */
data class Journey(
    val origin: Station,
    val destination: Station,
    val stops: List<Stop>
) {
    init {
        require(stops.isNotEmpty()) { "A journey must have at least one stop." }
    }

    /** Null if the destination has dropped out of the calling pattern (diversion/cancellation) - see docs/spec.md §4. */
    val destinationStop: Stop?
        get() = stops.lastOrNull { it.station.id == destination.id }
}
