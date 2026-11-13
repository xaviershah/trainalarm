package com.trainalarm.app.tracking

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemTrackerClockTest {
    @Test fun now_isCloseToTheSystemTime() {
        assertTrue(Duration.between(SystemTrackerClock().now(), Instant.now()).abs() < Duration.ofSeconds(1))
    }

    @Test fun sleep_waitsAtLeastTheRequestedTime() = runBlocking<Unit> {
        val start = System.nanoTime()
        SystemTrackerClock().sleep(0.05)
        assertTrue((System.nanoTime() - start) / 1_000_000 >= 40)
    }

    @Test fun sleep_throwsCancellationWhenCancelled() = runBlocking<Unit> {
        var thrown: Throwable? = null
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                SystemTrackerClock().sleep(30.0)
            } catch (e: Throwable) {
                thrown = e
                throw e
            }
        }
        job.cancelAndJoin()
        assertTrue(thrown is CancellationException)
    }
}
