package com.trainalarm.app.tracking

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingFakesTest {
    @Test
    fun fakeClockSleepResumesOnTickAndTimeAdvances() = runBlocking<Unit> {
        val clock = FakeClock(ServiceBuilder.time("08:00:00"))
        val sleeper = launch { clock.sleep(45.0) }

        clock.awaitSleepCount(1)
        clock.tick(45.0)
        sleeper.join()

        assertEquals(ServiceBuilder.time("08:00:45"), clock.now())
        assertTrue(sleeper.isCompleted && !sleeper.isCancelled)
    }

    @Test
    fun fakeClockSleepIsCancelledWhenTheSleeperIsCancelled() = runBlocking<Unit> {
        val clock = FakeClock(ServiceBuilder.time("08:00:00"))
        var seen: Throwable? = null
        val sleeper = launch {
            try {
                clock.sleep(45.0)
            } catch (e: CancellationException) {
                seen = e
                throw e
            }
        }

        clock.awaitSleepCount(1)
        sleeper.cancelAndJoin()

        assertTrue(seen is CancellationException)
    }

    @Test
    fun fakeAlarmSchedulerRecordsFailedAttemptsSeparately() = runBlocking<Unit> {
        val scheduler = FakeAlarmScheduler()
        scheduler.failuresRemaining = 1
        val fire = ServiceBuilder.time("08:37:00")

        try {
            scheduler.schedule(fire)
            throw AssertionError("expected a refusal")
        } catch (e: FakeAlarmScheduler.Refused) {
        }
        scheduler.schedule(fire)

        assertEquals(listOf(fire, fire), scheduler.attempts)
        assertEquals(listOf(fire), scheduler.scheduled)
    }

    @Test
    fun fakeProviderServesInOrderThenRepeatsTheLast() = runBlocking<Unit> {
        val provider = FakeProvider()
        val first = ServiceBuilder.happyPath(destinationEstimate = "08:52:00")
        val second = ServiceBuilder.happyPath(destinationEstimate = "08:55:00")
        provider.results += Result.success(first)
        provider.results += Result.success(second)

        val seen = List(3) { provider.serviceDetails("x", java.time.LocalDate.of(2026, 9, 13)) }

        assertEquals(listOf(first, second, second), seen)
        assertEquals(3, provider.calls)
    }
}
