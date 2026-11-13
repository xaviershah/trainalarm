package com.trainalarm.app.alarm

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmEventsTest {
    @After fun reset() = AlarmEvents.stopped()

    @Test fun `not ringing until the service starts the player`() {
        assertFalse(AlarmEvents.ringing.value)
    }

    @Test fun `ringing from start until stop`() {
        AlarmEvents.started()
        assertTrue(AlarmEvents.ringing.value)
        AlarmEvents.stopped()
        assertFalse(AlarmEvents.ringing.value)
    }

    @Test fun `stopping twice is harmless`() {
        AlarmEvents.stopped()
        AlarmEvents.stopped()
        assertFalse(AlarmEvents.ringing.value)
    }
}
