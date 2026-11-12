package com.trainalarm.app.alarm

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Duration
import java.time.Instant

private class FakeGateway : ExactAlarmGateway {
    var permitted = true
    var setFailure: Exception? = null
    var cancelFailure: Exception? = null
    var current: Instant? = null
    var setCalls = 0
    var cancelCalls = 0
    var stopCalls = 0

    override fun canScheduleExactAlarms() = permitted
    override fun setAlarmClock(fireAt: Instant) {
        setCalls += 1
        setFailure?.let { throw it }
        current = fireAt  // like the real PendingIntent: a new alarm replaces the old one
    }
    override fun cancelAlarm() { cancelCalls += 1; cancelFailure?.let { throw it }; current = null }
    override fun stopRinging() { stopCalls += 1 }
}

class ExactAlarmSchedulerTest {
    private val now = Instant.parse("2026-11-10T08:00:00Z")
    private val gateway = FakeGateway()
    private val scheduler = ExactAlarmScheduler(gateway) { now }

    private fun later(seconds: Long): Instant = now.plusSeconds(seconds)

    @Test
    fun permittedSchedulesTheAlarmClock() = runBlocking {
        scheduler.schedule(later(600))
        assertEquals(later(600), gateway.current)
    }

    @Test
    fun notPermittedThrowsAndSchedulesNothing() = runBlocking {
        gateway.permitted = false
        try {
            scheduler.schedule(later(600))
            fail("expected ExactAlarmNotPermitted")
        } catch (e: AlarmSchedulerException.ExactAlarmNotPermitted) {
        }
        assertEquals(0, gateway.setCalls)
        assertNull(gateway.current)
    }

    @Test
    fun aSecurityExceptionFromTheOsBecomesTheTypedException() = runBlocking {
        gateway.setFailure = SecurityException("revoked")  // revoked between the check and the call
        try {
            scheduler.schedule(later(600))
            fail("expected ExactAlarmNotPermitted")
        } catch (e: AlarmSchedulerException.ExactAlarmNotPermitted) {
            assertTrue(e.cause is SecurityException)
        }
    }

    @Test
    fun aFireTimeAtOrBeforeNowIsRaisedToTheMinimumLead() = runBlocking {
        val minimum = now.plus(ExactAlarmScheduler.MINIMUM_LEAD)
        scheduler.schedule(now)
        assertEquals(minimum, gateway.current)
        scheduler.schedule(later(-300))
        assertEquals(minimum, gateway.current)
        assertEquals(Duration.ofSeconds(2), ExactAlarmScheduler.MINIMUM_LEAD)
    }

    @Test
    fun aSecondScheduleReplacesTheFirst() = runBlocking {
        scheduler.schedule(later(600))
        scheduler.schedule(later(900))
        assertEquals(later(900), gateway.current)
        assertEquals(0, gateway.cancelCalls)  // replaced in place, never torn down first
    }

    @Test
    fun aFailingSecondScheduleLeavesTheFirstInPlace() = runBlocking {
        scheduler.schedule(later(600))
        gateway.permitted = false
        try {
            scheduler.schedule(later(900))
            fail("expected ExactAlarmNotPermitted")
        } catch (e: AlarmSchedulerException.ExactAlarmNotPermitted) {
        }
        assertEquals(later(600), gateway.current)
        assertEquals(0, gateway.cancelCalls)
    }

    @Test
    fun cancelCancelsTheAlarmAndStopsARingingService() = runBlocking {
        scheduler.schedule(later(600))
        scheduler.cancel()
        assertNull(gateway.current)
        assertEquals(1, gateway.stopCalls)
    }

    @Test
    fun cancelWithNothingScheduledAndCancelTwiceAreSafe() = runBlocking {
        scheduler.cancel()
        scheduler.cancel()
        assertEquals(2, gateway.cancelCalls)
        assertEquals(2, gateway.stopCalls)
    }

    @Test
    fun cancelStillStopsARingingServiceWhenCancelAlarmThrows() = runBlocking {
        gateway.cancelFailure = IllegalStateException("boom")
        try {
            scheduler.cancel()
            fail("expected the cancelAlarm exception to propagate")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
        assertEquals(1, gateway.stopCalls)
    }
}
