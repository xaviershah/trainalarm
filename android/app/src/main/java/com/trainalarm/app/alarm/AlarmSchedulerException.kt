package com.trainalarm.app.alarm

/** Why the real scheduler refused. Thrown from [AlarmScheduler.schedule]; the tracker reports it as a refusal. */
sealed class AlarmSchedulerException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The user has not granted "Alarms & reminders" (SCHEDULE_EXACT_ALARM), or revoked it. */
    class ExactAlarmNotPermitted(cause: Throwable? = null) :
        AlarmSchedulerException("Exact alarms are not allowed for this app", cause)
}
