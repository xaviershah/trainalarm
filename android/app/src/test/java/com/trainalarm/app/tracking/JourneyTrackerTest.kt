package com.trainalarm.app.tracking

import com.trainalarm.app.alarm.AlarmScheduler
import com.trainalarm.app.model.Service
import com.trainalarm.app.provider.ProviderError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

/** Collects a tracker's event flow so tests can wait for it deterministically. */
private class EventCollector(scope: CoroutineScope, flow: Flow<TrackerEvent>) {
    val events = mutableListOf<TrackerEvent>()
    var finished = false
        private set

    init {
        scope.launch {
            flow.collect { events += it }
            finished = true
        }
    }

    suspend fun awaitCount(count: Int) = withTimeout(2_000) { while (events.size < count) yield() }

    suspend fun awaitFinished() = withTimeout(2_000) { while (!finished) yield() }
}

private class Rig(scope: CoroutineScope, results: List<Result<Service>>, coordinate: Coordinate?) {
    val clock = FakeClock(ServiceBuilder.time("08:00:00"))
    val provider = FakeProvider().also { it.results += results }
    val scheduler = FakeAlarmScheduler()
    val location = FakeLocationSource()
    val tracker = JourneyTracker(
        TrackerPlan("WAT", "BSK", coordinate, TrackerConfig(leadTime = 600.0)),
        ServiceBuilder.happyPath(), provider, location, scheduler, clock
    )
    val events = EventCollector(scope, tracker.events)
}

class JourneyTrackerTest {
    private class Boom : Exception("boom")

    private fun t(hhmmss: String) = ServiceBuilder.time(hhmmss)
    private fun happy(estimate: String? = "08:52:00"): Service = ServiceBuilder.happyPath(estimate)
    private fun etaChanged(old: String?, new: String, fire: String) =
        TrackerEvent.EtaChanged(old?.let(::t), t(new), t(fire))

    /** Starts a tracker, runs [block], and always stops the tracker afterwards. */
    private fun tracking(
        results: List<Result<Service>>,
        coordinate: Coordinate? = null,
        setup: (Rig) -> Unit = {},
        block: suspend (Rig) -> Unit
    ) = runBlocking<Unit> {
        val rig = Rig(this, results, coordinate)
        setup(rig)
        rig.tracker.start(this)
        try {
            block(rig)
        } finally {
            rig.tracker.stop()
        }
    }

    // Wiring through the real loop

    @Test
    fun startSchedulesTheTimetableAlarmBeforeAnyPoll() = tracking(listOf(Result.success(happy()))) { rig ->
        rig.clock.awaitSleepCount(1)
        rig.events.awaitCount(1)

        assertEquals(listOf<TrackerEvent>(etaChanged(null, "08:52:00", "08:42:00")), rig.events.events)
        assertEquals(listOf(t("08:42:00")), rig.scheduler.scheduled)
        assertEquals(0, rig.provider.calls)
    }

    @Test
    fun eachTickPollsTheProviderOnceAndAppliesTheChange() = tracking(listOf(Result.success(happy("08:57:00")))) { rig ->
        rig.clock.awaitSleepCount(1)

        rig.clock.tick(45.0)
        rig.clock.awaitSleepCount(2)
        rig.events.awaitCount(2)
        assertEquals(1, rig.provider.calls)
        assertEquals(listOf(ServiceBuilder.HAPPY_PATH_ID), rig.provider.requestedIds)
        assertEquals(etaChanged("08:52:00", "08:57:00", "08:47:00"), rig.events.events.last())
        assertEquals(listOf(t("08:42:00"), t("08:47:00")), rig.scheduler.scheduled)

        rig.clock.tick(45.0)
        rig.clock.awaitSleepCount(3)
        assertEquals(2, rig.provider.calls)
        rig.tracker.stop()
        rig.events.awaitFinished()
        assertEquals(2, rig.events.events.size)  // same ETA again: nothing new
    }

    @Test
    fun aProviderErrorBecomesAFailedPollWithItsKind() = tracking(listOf(Result.failure(ProviderError.Http(503)))) { rig ->
        rig.clock.awaitSleepCount(1)

        rig.clock.tick(45.0)
        rig.clock.awaitSleepCount(2)
        rig.events.awaitCount(3)

        assertEquals(
            listOf(
                TrackerEvent.FeedLost(null, FailureKind.Http(503)),
                TrackerEvent.EtaEstimated(t("08:52:00"), EtaSource.LAST_KNOWN_TRAJECTORY, 45.0, false)
            ),
            rig.events.events.drop(1)
        )
    }

    @Test
    fun anErrorThatIsNotAProviderErrorIsKindOtherAndPollingContinues() = tracking(listOf(Result.failure(Boom()))) { rig ->
        rig.clock.awaitSleepCount(1)

        rig.clock.tick(45.0)
        rig.clock.awaitSleepCount(2)
        rig.clock.tick(45.0)
        rig.clock.awaitSleepCount(3)
        rig.events.awaitCount(3)

        assertEquals(TrackerEvent.FeedLost(null, FailureKind.Other), rig.events.events[1])
        assertEquals(2, rig.provider.calls)
    }

    @Test
    fun locationFixesAreSampledEachTickAndReachTheGpsFallback() = tracking(
        listOf(Result.success(happy()), Result.failure(ProviderError.Network("down"))),
        coordinate = Coordinate(0.0, 1.0)
    ) { rig ->
        rig.clock.awaitSleepCount(1)

        rig.location.fix = LocationFix(0.0, 0.0, t("08:00:45"), 10.0)
        rig.clock.tick(45.0)  // 08:00:45, good poll
        rig.clock.awaitSleepCount(2)
        rig.location.fix = LocationFix(0.0, 0.02, t("08:01:45"), 10.0)
        rig.clock.tick(60.0)  // 08:01:45, failed poll: GPS ETA 08:50:45 is clamped by the guard to 08:51:00
        rig.clock.awaitSleepCount(3)
        rig.events.awaitCount(3)

        assertEquals(TrackerEvent.EtaEstimated(t("08:51:00"), EtaSource.GPS_PACE, 60.0, true), rig.events.events.last())
        assertEquals(listOf(t("08:42:00"), t("08:41:00")), rig.scheduler.scheduled)
    }

    @Test
    fun aSchedulerRefusalIsReportedThenRetriedOnTheNextTick() = tracking(
        listOf(Result.success(happy())),
        setup = { it.scheduler.failuresRemaining = 1 }
    ) { rig ->
        rig.clock.awaitSleepCount(1)
        rig.events.awaitCount(2)

        assertEquals(2, rig.events.events.size)
        assertEquals(etaChanged(null, "08:52:00", "08:42:00"), rig.events.events[0])
        val refusal = rig.events.events[1] as TrackerEvent.AlarmSchedulingFailed
        assertEquals(t("08:42:00"), refusal.fireAt)
        assertEquals(emptyList<java.time.Instant>(), rig.scheduler.scheduled)

        rig.clock.tick(45.0)
        rig.clock.awaitSleepCount(2)
        assertEquals(listOf(t("08:42:00")), rig.scheduler.scheduled)
        assertEquals(listOf(t("08:42:00"), t("08:42:00")), rig.scheduler.attempts)
        rig.tracker.stop()
        rig.events.awaitFinished()
        assertEquals(2, rig.events.events.size)  // the retry announces nothing new
    }

    @Test
    fun arrivalCancelsTheAlarmAndEndsTheFlow() {
        val arrived = ServiceBuilder.replacing(
            happy(), 2,
            ServiceBuilder.stop("BSK", scheduledArrival = "08:47:00", estimatedArrival = "08:50:00", hasArrived = true)
        )
        tracking(listOf(Result.success(arrived))) { rig ->
            rig.clock.awaitSleepCount(1)

            rig.clock.tick(45.0)
            rig.events.awaitFinished()

            assertEquals(TrackerEvent.Arrived(t("08:00:45")), rig.events.events.last())
            assertEquals(1, rig.scheduler.cancelCount)
            assertEquals(1, rig.clock.sleepCount)  // never slept again
        }
    }

    // Scenarios 25-26: stop and cancellation

    @Test
    fun stopCancelsTheAlarmAndEndsTheFlowWithoutPolling() = tracking(listOf(Result.success(happy()))) { rig ->
        rig.clock.awaitSleepCount(1)

        rig.tracker.stop()
        rig.events.awaitFinished()

        assertEquals(1, rig.scheduler.cancelCount)
        assertEquals(0, rig.provider.calls)
        rig.clock.tick(45.0)  // nobody is sleeping any more
        yield()
        assertEquals(0, rig.provider.calls)
    }

    @Test
    fun cancellationDuringTheSleepIsNotReportedAsAnOutage() = tracking(listOf(Result.failure(ProviderError.Http(500)))) { rig ->
        rig.clock.awaitSleepCount(1)  // a poll would show up as feedLost

        rig.tracker.stop()
        rig.events.awaitFinished()

        assertEquals(listOf<TrackerEvent>(etaChanged(null, "08:52:00", "08:42:00")), rig.events.events)
    }

    @Test
    fun aCancellationExceptionFromTheProviderIsSkippedNotAnOutage() = tracking(
        listOf(Result.failure(CancellationException("timeout")))
    ) { rig ->
        rig.clock.awaitSleepCount(1)

        rig.clock.tick(45.0)
        rig.clock.awaitSleepCount(2)  // the loop carried on and is sleeping again

        assertEquals(1, rig.provider.calls)
        rig.tracker.stop()
        rig.events.awaitFinished()
        assertEquals(1, rig.events.events.size)  // only the initial EtaChanged; no FeedLost
    }

    @Test
    fun aCancellationExceptionFromTheSchedulerDoesNotEndTheLoop() = runBlocking<Unit> {
        val clock = FakeClock(t("08:00:00"))
        val provider = FakeProvider().also { it.results += Result.success(happy("08:57:00")) }
        val attempts = mutableListOf<java.time.Instant>()
        val scheduler = object : AlarmScheduler {
            override suspend fun schedule(fireAt: java.time.Instant) {
                attempts += fireAt
                if (attempts.size == 1) throw CancellationException("timeout inside scheduler")
            }

            override suspend fun cancel() {}
        }
        val tracker = JourneyTracker(
            TrackerPlan("WAT", "BSK", null, TrackerConfig(leadTime = 600.0)),
            happy(), provider, FakeLocationSource(), scheduler, clock
        )
        val events = EventCollector(this, tracker.events)
        tracker.start(this)
        try {
            clock.awaitSleepCount(1)  // the loop survived the spurious cancellation
            clock.tick(45.0)
            clock.awaitSleepCount(2)
            events.awaitCount(2)

            assertEquals(1, provider.calls)
            assertEquals(listOf(t("08:42:00"), t("08:47:00")), attempts)
            assertEquals(false, events.finished)
        } finally {
            tracker.stop()
        }
    }

    @Test
    fun aSpuriousSchedulerCancellationOnAClampedAlarmIsRetriedNotLost() = runBlocking<Unit> {
        val clock = FakeClock(t("08:00:00"))
        // ETA 08:08 with a 10 minute lead: the fire time is already past, so the alarm is clamped to now.
        val provider = FakeProvider().also { it.results += Result.success(happy("08:08:00")) }
        val attempts = mutableListOf<java.time.Instant>()
        val scheduled = mutableListOf<java.time.Instant>()
        val scheduler = object : AlarmScheduler {
            override suspend fun schedule(fireAt: java.time.Instant) {
                attempts += fireAt
                if (attempts.size == 2) throw CancellationException("timeout inside scheduler")  // the clamped call
                scheduled += fireAt
            }

            override suspend fun cancel() {}
        }
        val tracker = JourneyTracker(
            TrackerPlan("WAT", "BSK", null, TrackerConfig(leadTime = 600.0)),
            happy(), provider, FakeLocationSource(), scheduler, clock
        )
        val events = EventCollector(this, tracker.events)
        tracker.start(this)
        try {
            clock.awaitSleepCount(1)
            clock.tick(45.0)  // 08:00:45: clamped schedule(now) is cancelled spuriously
            clock.awaitSleepCount(2)
            clock.tick(45.0)  // 08:01:30: the retry must ask again
            clock.awaitSleepCount(3)

            assertEquals(listOf(t("08:42:00"), t("08:00:45"), t("08:01:30")), attempts)
            assertEquals(listOf(t("08:42:00"), t("08:01:30")), scheduled)
            assertEquals(false, events.finished)
        } finally {
            tracker.stop()
        }
    }
}
