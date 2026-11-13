package com.trainalarm.app.tracking

import java.time.Instant
import kotlinx.coroutines.delay

/** The real clock for the tracker. `sleep` throws `CancellationException` when the coroutine is cancelled. */
class SystemTrackerClock : TrackerClock {
    override fun now(): Instant = Instant.now()

    override suspend fun sleep(seconds: Double) = delay((maxOf(0.0, seconds) * 1000).toLong())
}
