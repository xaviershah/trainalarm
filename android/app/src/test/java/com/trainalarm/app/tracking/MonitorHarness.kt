package com.trainalarm.app.tracking

import com.trainalarm.app.model.Service
import java.time.Instant

/** A location fix described relative to the tick it arrives on. */
data class FixSpec(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Double = 10.0,
    /** How old the fix already is when the tick happens. */
    val ageSeconds: Double = 0.0
)

/**
 * Drives the pure monitor the way the real driver does: it applies each action, confirming a
 * schedule unless [failNextSchedule] is set. Time only moves when a test says so.
 */
class MonitorHarness(
    boarding: String = "WAT",
    destination: String = "BSK",
    coordinate: Coordinate? = null,
    leadTime: Double = 600.0,
    start: Instant = ServiceBuilder.time("08:00:00")
) {
    val monitor = JourneyMonitor(
        TrackerPlan(boarding, destination, coordinate, TrackerConfig(leadTime = leadTime))
    )
    var state = MonitorState()
    var now: Instant = start
    val scheduled = mutableListOf<Instant>()
    val attempts = mutableListOf<Instant>()
    var cancelCount = 0
    var failNextSchedule = false

    fun start(snapshot: Service): List<TrackerEvent> = apply(monitor.start(snapshot, now))

    fun poll(service: Service, advancing: Double = 45.0, fix: FixSpec? = null): List<TrackerEvent> {
        now = now.plusMillis((advancing * 1000).toLong())
        return apply(monitor.step(state, Observation(PollResult.Success(service), now, stamp(fix))))
    }

    fun fail(kind: FailureKind = FailureKind.Network, advancing: Double = 45.0, fix: FixSpec? = null): List<TrackerEvent> {
        now = now.plusMillis((advancing * 1000).toLong())
        return apply(monitor.step(state, Observation(PollResult.Failure(kind), now, stamp(fix))))
    }

    private fun stamp(spec: FixSpec?): LocationFix? = spec?.let {
        LocationFix(it.latitude, it.longitude, now.minusMillis((it.ageSeconds * 1000).toLong()), it.accuracy)
    }

    private fun apply(step: MonitorStep): List<TrackerEvent> {
        state = step.state
        val events = step.events.toMutableList()
        when (val action = step.action) {
            AlarmAction.None -> {}
            is AlarmAction.Schedule -> {
                attempts += action.fireAt
                if (failNextSchedule) {
                    failNextSchedule = false
                    state = monitor.schedulingFailed(state)
                    events += TrackerEvent.AlarmSchedulingFailed(action.fireAt, "refused")
                } else {
                    scheduled += action.fireAt
                    state = monitor.confirmScheduled(state, action.fireAt)
                }
            }
            AlarmAction.Cancel -> cancelCount += 1
        }
        return events
    }
}
