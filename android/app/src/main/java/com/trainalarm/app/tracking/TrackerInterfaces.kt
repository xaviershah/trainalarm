package com.trainalarm.app.tracking

import java.time.Instant

/** The tracker's only source of time. Production uses the system clock; tests drive a fake. */
interface TrackerClock {
    fun now(): Instant

    /** Suspends for [seconds]. Throws CancellationException if the caller is cancelled. */
    suspend fun sleep(seconds: Double)
}

data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val timestamp: Instant,
    /** Horizontal accuracy in metres. */
    val accuracy: Double
)

interface LocationSource {
    fun latestFix(): LocationFix?
}
