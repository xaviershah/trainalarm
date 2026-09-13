package com.trainalarm.app.model

import java.time.Instant

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
    val isCancelled: Boolean = false
) {
    /** Best-known arrival time: live estimate if we have one, else the timetable. */
    val bestArrival: Instant?
        get() = estimatedArrival ?: scheduledArrival
}
