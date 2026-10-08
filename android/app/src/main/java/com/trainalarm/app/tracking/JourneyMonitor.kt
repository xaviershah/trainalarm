package com.trainalarm.app.tracking

import com.trainalarm.app.model.Service
import com.trainalarm.app.model.Stop
import com.trainalarm.app.model.StopDisplay
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

private fun Instant.shifted(bySeconds: Double): Instant = plusMillis(Math.round(bySeconds * 1000))

private fun secondsBetween(from: Instant, to: Instant): Double = Duration.between(from, to).toMillis() / 1000.0

/**
 * The pure decision core of the tracker. It performs no I/O and never throws: the driver feeds
 * it one [Observation] per tick and applies the [AlarmAction] it returns, then reports back with
 * [confirmScheduled] or [schedulingFailed]. See the spec's "Monitor rules".
 */
class JourneyMonitor(private val plan: TrackerPlan) {
    private val config get() = plan.config

    private data class ScheduleDecision(val state: MonitorState, val action: AlarmAction, val announces: Boolean)

    /**
     * Builds the first step from the selection-time snapshot, so the timetable-based fallback
     * alarm is scheduled before any poll has happened.
     */
    fun start(snapshot: Service, now: Instant): MonitorStep =
        observeService(snapshot, now, MonitorState(startedAt = now))

    fun step(state: MonitorState, observation: Observation): MonitorStep {
        if (state.finished) return MonitorStep(state, emptyList(), AlarmAction.None)
        var current = state
        val fix = observation.fix
        if (fix != null) {
            // Sampled every tick, up or down, so the window is not empty when an outage starts.
            current = current.copy(
                fixes = (current.fixes + fix).filter { secondsBetween(it.timestamp, observation.now) <= config.maxFixAge }
            )
        }
        return when (val result = observation.result) {
            is PollResult.Success ->
                observeService(result.service, observation.now, current.copy(lastGoodAt = observation.now))
            is PollResult.Failure -> observeFailure(result.kind, observation.now, current)
        }
    }

    /** Call after the scheduler accepted a [AlarmAction.Schedule]. */
    fun confirmScheduled(state: MonitorState, fireAt: Instant): MonitorState =
        state.copy(scheduledFire = fireAt, retryPending = false)

    /** Call after the scheduler refused a [AlarmAction.Schedule]: the next tick asks again. */
    fun schedulingFailed(state: MonitorState): MonitorState = state.copy(retryPending = true)

    // Successful poll

    private fun observeService(service: Service, now: Instant, input: MonitorState): MonitorStep {
        var state = input
        val events = mutableListOf<TrackerEvent>()

        val destination = destinationStop(service)
        val newStanding = classify(destination, service)
        val previous = state.standing

        // The ETA is resolved first because FeedRecovered reports it ahead of every other event.
        var resolved: Instant? = null
        if (newStanding == Standing.CALLING && destination != null) {
            val live = destination.estimatedArrival
            resolved = when {
                live != null -> {
                    state = state.copy(sawLive = true, lastLiveEta = live)
                    live
                }
                // A live estimate that has vanished must not silently drop a known delay.
                state.sawLive && !destination.hasArrived -> state.lastEta
                else -> destination.scheduledArrival ?: state.lastEta
            }
        }

        if (state.feedDown) {
            val outage = secondsBetween(state.outageStartedAt ?: now, now)
            val fallback = state.lastFallbackEta
            val delta = if (resolved != null && fallback != null) secondsBetween(fallback, resolved) else null
            events += TrackerEvent.FeedRecovered(outage, resolved, delta)
            state = state.copy(feedDown = false, outageStartedAt = null, lastFallbackEta = null)
        }

        state = state.copy(standing = newStanding)
        if (newStanding == Standing.LOST && previous != Standing.LOST) events += TrackerEvent.DestinationLost(now)
        if (newStanding == Standing.CANCELLED && previous != Standing.CANCELLED) events += TrackerEvent.ServiceCancelled(now)
        if (newStanding != Standing.CALLING || destination == null) {
            // Lost or cancelled: leave the scheduled alarm alone (cancelling it risks silence). A pending retry waits for restoration.
            return MonitorStep(state, events, AlarmAction.None)
        }

        if (previous != Standing.CALLING) events += TrackerEvent.DestinationRestored(resolved)
        val eta = resolved ?: return MonitorStep(state, events, AlarmAction.None)

        // Arrival ends tracking, before anything is scheduled.
        if (destination.hasArrived || now.isAfter(eta.shifted(config.arrivalGrace))) {
            events += TrackerEvent.Arrived(now)
            return MonitorStep(state.copy(finished = true), events, AlarmAction.Cancel)
        }

        val previousEta = state.lastEta
        state = state.copy(lastEta = eta)
        val decision = decideSchedule(eta.shifted(-config.leadTime), now, state)
        state = decision.state
        val action = decision.action
        if (decision.announces && action is AlarmAction.Schedule) {
            events += TrackerEvent.EtaChanged(previousEta, eta, action.fireAt)
        }
        return MonitorStep(state, events, action)
    }

    /**
     * The first stop with `destinationId` after the boarding stop (the whole list if the boarding
     * station is not found). Never `Journey.destination`: that is the service's terminus.
     */
    private fun destinationStop(service: Service): Stop? {
        val stops = service.journey.stops
        val boardingIndex = stops.indexOfFirst { it.station.id == plan.boardingId }
        val start = if (boardingIndex >= 0) boardingIndex + 1 else 0
        return stops.drop(start).firstOrNull { it.station.id == plan.destinationId }
    }

    private fun classify(stop: Stop?, service: Service): Standing = when {
        stop == null -> Standing.LOST
        service.journey.stops.all { it.isCancelled } -> Standing.CANCELLED
        notCalledAt(stop) -> Standing.LOST
        else -> Standing.CALLING
    }

    /**
     * The destination's arrival is cancelled, or the provider says the train no longer calls
     * there. A null or unknown `displayAs` is not enough on its own: the schema reads a missing
     * value as PASS, but that has not been checked against real data.
     */
    private fun notCalledAt(stop: Stop): Boolean =
        stop.isArrivalCancelled ||
            stop.displayAs == StopDisplay.CANCELLED ||
            stop.displayAs == StopDisplay.DIVERTED ||
            stop.displayAs == StopDisplay.PASS

    // Scheduling

    /**
     * Decides whether to (re)schedule for [desired]. `announces` says whether a live-data
     * `EtaChanged` should accompany the action (false for retries and, in Task 3, fallback ETAs).
     */
    private fun decideSchedule(desired: Instant, now: Instant, input: MonitorState): ScheduleDecision {
        var state = input
        val clamped = !desired.isAfter(now)
        if (state.retryPending) {
            // A previous schedule call failed: ask again, without announcing the same change twice.
            if (clamped) state = state.copy(alarmDue = true)
            return ScheduleDecision(state, AlarmAction.Schedule(if (clamped) now else desired), false)
        }
        if (state.alarmDue) return ScheduleDecision(state, AlarmAction.None, false)
        val scheduled = state.scheduledFire
        if (scheduled != null) {
            if (!scheduled.isAfter(now)) {
                // The confirmed alarm time has passed: it has fired, so a later slip must not re-arm it.
                return ScheduleDecision(state.copy(alarmDue = true), AlarmAction.None, false)
            }
            if (abs(secondsBetween(scheduled, desired)) < config.rescheduleThreshold) {
                return ScheduleDecision(state, AlarmAction.None, false)
            }
        }
        if (clamped) state = state.copy(alarmDue = true)
        return ScheduleDecision(state, AlarmAction.Schedule(if (clamped) now else desired), true)
    }

    // Failed poll

    private fun observeFailure(kind: FailureKind, now: Instant, input: MonitorState): MonitorStep {
        var state = input
        val events = mutableListOf<TrackerEvent>()
        if (!state.feedDown) {
            state = state.copy(feedDown = true, outageStartedAt = now)
            events += TrackerEvent.FeedLost(state.lastGoodAt, kind)
        }
        // A lost or cancelled destination has no ETA to estimate. The anchor is the last live ETA
        // (or the snapshot's), which the fallback itself never overwrites, so the guard cannot creep.
        val anchor = state.lastLiveEta ?: state.lastEta
        if (state.standing != Standing.CALLING || anchor == null) {
            return MonitorStep(state, events, AlarmAction.None)
        }

        val gps = gpsEta(now, state)
        val eta = if (gps != null) maxOf(gps, anchor.shifted(-config.guardMargin)) else anchor
        state = state.copy(lastFallbackEta = eta)
        val decision = decideSchedule(eta.shifted(-config.leadTime), now, state)
        state = decision.state
        val staleness = secondsBetween(state.lastGoodAt ?: state.startedAt ?: now, now)
        events += TrackerEvent.EtaEstimated(
            eta,
            if (gps != null) EtaSource.GPS_PACE else EtaSource.LAST_KNOWN_TRAJECTORY,
            staleness,
            decision.action is AlarmAction.Schedule
        )
        return MonitorStep(state, events, decision.action)
    }

    /**
     * Approach speed over the usable fixes (fresh and accurate enough), extrapolated to the
     * destination. Null when there is no coordinate, fewer than two usable fixes, or the train is
     * not closing in faster than `minPace`.
     */
    private fun gpsEta(now: Instant, state: MonitorState): Instant? {
        val destination = plan.destinationCoordinate ?: return null
        val usable = state.fixes.filter {
            secondsBetween(it.timestamp, now) <= config.maxFixAge && it.accuracy <= config.maxFixAccuracy
        }
        if (usable.size < 2) return null
        val oldest = usable.minBy { it.timestamp }
        val newest = usable.maxBy { it.timestamp }
        val elapsed = secondsBetween(oldest.timestamp, newest.timestamp)
        if (elapsed <= 0) return null
        val before = Geo.distanceMetres(oldest.latitude, oldest.longitude, destination.latitude, destination.longitude)
        val after = Geo.distanceMetres(newest.latitude, newest.longitude, destination.latitude, destination.longitude)
        val pace = (before - after) / elapsed  // approach speed, metres per second
        if (pace < config.minPace) return null
        return now.shifted(after / pace)
    }
}
