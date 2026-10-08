package com.trainalarm.app.alarm

import java.time.Instant

/**
 * Sets the one alarm for a journey. Stage 3 provides the real implementation (foreground
 * service + exact alarm); until then tests use a fake.
 */
interface AlarmScheduler {
    /** Replaces any scheduled alarm with one at [fireAt] (idempotent). Throws if the OS refuses. */
    suspend fun schedule(fireAt: Instant)

    suspend fun cancel()
}
