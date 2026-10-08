package com.trainalarm.app.tracking

/**
 * Tuning for the journey tracker. Durations are seconds. Provider times are
 * minute-granular, so a one-minute shift is exactly 60s: the reschedule threshold is
 * inclusive (>=).
 */
data class TrackerConfig(
    /** How long before the destination ETA the alarm should fire (the user's setting; no default). */
    val leadTime: Double,
    val rescheduleThreshold: Double = 60.0,
    val pollInterval: Double = 45.0,
    /** A GPS-based fallback ETA is never earlier than the last live ETA minus this. */
    val guardMargin: Double = 60.0,
    /** Minimum approach speed, in metres per second, for the GPS fallback to be trusted. */
    val minPace: Double = 1.0,
    val maxFixAgePolls: Int = 3,
    /** Fixes less accurate than this many metres are ignored. */
    val maxFixAccuracy: Double = 200.0,
    /** How long after the ETA the tracker waits for an arrival before ending anyway. */
    val arrivalGrace: Double = 600.0
) {
    /** Fixes older than this are ignored. */
    val maxFixAge: Double get() = maxFixAgePolls * pollInterval
}
