package com.trainalarm.app.alarm

import java.time.Instant

/**
 * Sets the one alarm for a journey. Real implementation: `ExactAlarmScheduler`. Tests use a fake.
 */
interface AlarmScheduler {
    /** Replaces any scheduled alarm with one at [fireAt] (idempotent). Throws if the OS refuses. */
    suspend fun schedule(fireAt: Instant)

    suspend fun cancel()
}
