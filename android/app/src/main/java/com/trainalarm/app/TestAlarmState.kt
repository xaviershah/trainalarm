package com.trainalarm.app

import java.time.Instant

/** How long "Test alarm in 10 seconds" waits. */
const val TEST_ALARM_DELAY_SECONDS = 10L

/** The result of the test alarm on the Settings screen. */
sealed interface TestAlarmState {
    object Idle : TestAlarmState
    data class Scheduled(val at: Instant) : TestAlarmState
    object Failed : TestAlarmState
}
