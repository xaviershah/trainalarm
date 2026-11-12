package com.trainalarm.app.alarm

import java.time.Duration
import java.time.Instant

/** Real [AlarmScheduler]: an exact alarm-clock alarm that wakes [AlarmReceiver]. */
class ExactAlarmScheduler(
    private val gateway: ExactAlarmGateway,
    private val now: () -> Instant = Instant::now
) : AlarmScheduler {
    override suspend fun schedule(fireAt: Instant) {
        if (!gateway.canScheduleExactAlarms()) throw AlarmSchedulerException.ExactAlarmNotPermitted()
        val earliest = now().plus(MINIMUM_LEAD)
        val fire = if (fireAt.isBefore(earliest)) earliest else fireAt
        try {
            gateway.setAlarmClock(fire)
        } catch (e: SecurityException) {
            throw AlarmSchedulerException.ExactAlarmNotPermitted(e)
        }
    }

    override suspend fun cancel() {
        try {
            gateway.cancelAlarm()
        } finally {
            gateway.stopRinging()
        }
    }

    companion object {
        /** Behaviour for a fire time of "now" or earlier is unverified on both platforms, so never pass one. */
        val MINIMUM_LEAD: Duration = Duration.ofSeconds(2)
    }
}
