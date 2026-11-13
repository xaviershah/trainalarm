package com.trainalarm.app.alarm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the alarm is ringing right now. `AlarmService` runs in the app's process, so a plain process-wide
 * flow is enough: the session model collects it and moves to the ringing phase.
 */
object AlarmEvents {
    private val state = MutableStateFlow(false)
    val ringing: StateFlow<Boolean> = state.asStateFlow()

    fun started() { state.value = true }
    fun stopped() { state.value = false }
}
