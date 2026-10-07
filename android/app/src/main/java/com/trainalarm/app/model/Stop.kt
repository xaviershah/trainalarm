package com.trainalarm.app.model

import java.time.Instant

/**
 * What the provider says this location is for the train (RTT `displayAs`, a
 * per-location field). [Stop.displayAs] is null when the provider didn't say.
 */
enum class StopDisplay { CALL, CANCELLED, DIVERTED, STARTS, TERMINATES, PASS, UNKNOWN }

/**
 * One station within a [Journey]. Scheduled times come from the timetable;
 * estimated times are refined by live running data where available (may be
 * null if the provider has nothing live yet, or if this stop is in the
 * past). See docs/spec.md §4 for how these get updated as a journey runs.
 */
data class Stop(
    val station: Station,
    val scheduledArrival: Instant?,
    val scheduledDeparture: Instant?,
    val estimatedArrival: Instant?,
    val estimatedDeparture: Instant?,
    val isArrivalCancelled: Boolean = false,
    val isDepartureCancelled: Boolean = false,
    val displayAs: StopDisplay? = null,
    /** True once the train has arrived here (the arrival has a `realtimeActual`). */
    val hasArrived: Boolean = false
) {
    /** Either activity cancelled. Use the per-activity flags when the difference matters. */
    val isCancelled: Boolean
        get() = isArrivalCancelled || isDepartureCancelled

    /** Best-known arrival time: live estimate if we have one, else the timetable. */
    val bestArrival: Instant?
        get() = estimatedArrival ?: scheduledArrival
}
