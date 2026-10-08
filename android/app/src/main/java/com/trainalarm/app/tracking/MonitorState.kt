package com.trainalarm.app.tracking

import com.trainalarm.app.model.Service
import java.time.Instant

/** Where the destination stands for the train. */
enum class Standing { CALLING, LOST, CANCELLED }

/** What the driver must do to the alarm after a monitor step. */
sealed class AlarmAction {
    data object None : AlarmAction()
    data class Schedule(val fireAt: Instant) : AlarmAction()
    data object Cancel : AlarmAction()
}

sealed class PollResult {
    data class Success(val service: Service) : PollResult()
    data class Failure(val kind: FailureKind) : PollResult()
}

/** One tick's input to the monitor. */
data class Observation(
    val result: PollResult,
    val now: Instant,
    /** The latest location fix sampled this tick, if any (sampled whether or not the feed is up). */
    val fix: LocationFix?
)

/** The fixed inputs of a tracked journey. */
data class TrackerPlan(
    val boardingId: String,
    val destinationId: String,
    /** Resolved by the caller from the station directory; null disables the GPS fallback. */
    val destinationCoordinate: Coordinate?,
    val config: TrackerConfig
)

/** Everything the monitor remembers between ticks. */
data class MonitorState(
    val finished: Boolean = false,
    val standing: Standing = Standing.CALLING,
    val startedAt: Instant? = null,
    val lastEta: Instant? = null,
    val lastLiveEta: Instant? = null,
    val sawLive: Boolean = false,
    val lastGoodAt: Instant? = null,
    /** The fire time the scheduler has confirmed. */
    val scheduledFire: Instant? = null,
    /** A `schedule` call failed; the next tick must issue it again without a new `EtaChanged`. */
    val retryPending: Boolean = false,
    val alarmDue: Boolean = false,
    val feedDown: Boolean = false,
    val outageStartedAt: Instant? = null,
    val lastFallbackEta: Instant? = null,
    val fixes: List<LocationFix> = emptyList()
)

data class MonitorStep(
    val state: MonitorState,
    val events: List<TrackerEvent>,
    val action: AlarmAction
)
