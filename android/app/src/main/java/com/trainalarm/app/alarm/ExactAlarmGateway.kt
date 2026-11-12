package com.trainalarm.app.alarm

import java.time.Instant

/** The only thing [ExactAlarmScheduler] knows about Android, so its logic is testable on the JVM. */
interface ExactAlarmGateway {
    fun canScheduleExactAlarms(): Boolean

    /** Replaces any previous alarm. Throws [SecurityException] if the permission was revoked. */
    fun setAlarmClock(fireAt: Instant)

    fun cancelAlarm()

    /** Stops a ringing alarm (service, sound, notification), if any. */
    fun stopRinging()
}
