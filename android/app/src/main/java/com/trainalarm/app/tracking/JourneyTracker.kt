package com.trainalarm.app.tracking

import com.trainalarm.app.alarm.AlarmScheduler
import com.trainalarm.app.model.Service
import com.trainalarm.app.provider.ProviderError
import com.trainalarm.app.provider.TrainDataProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.time.ZoneOffset

/**
 * The thin driver around the pure [JourneyMonitor]: it owns the poll loop, applies the monitor's
 * alarm actions through the injected scheduler, and publishes the monitor's events on a flow.
 * All decisions live in [JourneyMonitor]; this class only does I/O and timing.
 */
class JourneyTracker(
    plan: TrackerPlan,
    private val snapshot: Service,
    private val provider: TrainDataProvider,
    private val locationSource: LocationSource,
    private val scheduler: AlarmScheduler,
    private val clock: TrackerClock
) {
    private val monitor = JourneyMonitor(plan)
    private val pollInterval = plan.config.pollInterval
    private val channel = Channel<TrackerEvent>(Channel.UNLIMITED)

    /** Events in the order they happened. Completes on arrival or [stop]. Collect it once. */
    val events: Flow<TrackerEvent> = channel.receiveAsFlow()

    private var state = MonitorState()
    private var job: Job? = null

    /**
     * Schedules the timetable-based alarm from the snapshot, then polls every `pollInterval`
     * until arrival or [stop]. The loop runs in [scope].
     */
    fun start(scope: CoroutineScope): Job {
        job?.let { return it }
        return scope.launch { run() }.also { job = it }
    }

    /** Stops polling, cancels the alarm and completes the event flow. Safe to call more than once. */
    suspend fun stop() {
        job?.cancelAndJoin()
        job = null
        scheduler.cancel()
        channel.close()
    }

    private suspend fun run() {
        try {
            apply(monitor.start(snapshot, clock.now()))
            while (!state.finished) {
                clock.sleep(pollInterval)  // a cancellation here ends the loop: not an outage
                tick()
            }
        } finally {
            channel.close()
        }
    }

    private suspend fun tick() {
        val fix = locationSource.latestFix()
        val result: PollResult = try {
            PollResult.Success(provider.serviceDetails(snapshot.id, clock.now().atZone(ZoneOffset.UTC).toLocalDate()))
        } catch (e: CancellationException) {
            // A real cancellation (stop()) must propagate; anything else (e.g. a timeout) is not an outage.
            currentCoroutineContext().ensureActive()
            return
        } catch (e: ProviderError) {
            PollResult.Failure(failureKind(e))
        } catch (e: Exception) {
            PollResult.Failure(FailureKind.Other)
        }
        currentCoroutineContext().ensureActive()
        apply(monitor.step(state, Observation(result, clock.now(), fix)))
    }

    private fun failureKind(error: ProviderError): FailureKind = when (error) {
        is ProviderError.Network -> FailureKind.Network
        is ProviderError.Http -> FailureKind.Http(error.code)
        is ProviderError.Malformed -> FailureKind.Malformed
        is ProviderError.NotImplemented -> FailureKind.Other
    }

    private suspend fun apply(step: MonitorStep) {
        state = step.state
        val pending = step.events.toMutableList()
        when (val action = step.action) {
            AlarmAction.None -> {}
            is AlarmAction.Schedule -> try {
                scheduler.schedule(action.fireAt)
                state = monitor.confirmScheduled(state, action.fireAt)
            } catch (e: Exception) {
                // A real cancellation (stop()) propagates. A spurious CancellationException is a
                // refusal like any other, so the next tick asks again instead of losing the alarm.
                if (e is CancellationException) currentCoroutineContext().ensureActive()
                state = monitor.schedulingFailed(state)
                pending += TrackerEvent.AlarmSchedulingFailed(action.fireAt, e.message ?: e.toString())
            }
            AlarmAction.Cancel -> scheduler.cancel()
        }
        pending.forEach { channel.trySend(it) }
    }
}
