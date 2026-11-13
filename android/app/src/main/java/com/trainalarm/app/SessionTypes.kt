package com.trainalarm.app

import com.trainalarm.app.model.Service
import com.trainalarm.app.tracking.EtaSource
import java.time.Instant

enum class EndReason { ARRIVED, IM_OFF }

sealed interface SessionPhase {
    data object Selecting : SessionPhase
    data object Confirming : SessionPhase
    data object Tracking : SessionPhase
    data object Ringing : SessionPhase
    data class Ended(val reason: EndReason) : SessionPhase
}

sealed interface BoardState {
    data object Idle : BoardState
    data object Loading : BoardState
    data class Loaded(val services: List<Service>) : BoardState
    data object Empty : BoardState
    data class Failed(val message: String) : BoardState
    data object RateLimited : BoardState
}

/** [arrival] is the best arrival at the destination after the boarding stop; null if the train does not call there. */
data class ConfirmState(val service: Service, val arrival: Instant?, val leadMinutes: Int?)

/** What the tracking screen shows, derived from tracker events. See the table in spec section 6. */
data class TrackingDisplay(
    val eta: Instant? = null,
    val scheduledArrival: Instant? = null,
    val alarmAt: Instant? = null,
    val isEstimated: Boolean = false,
    val estimateSource: EtaSource? = null,
    val feedLost: Boolean = false,
    val lastGoodAt: Instant? = null,
    val rateLimited: Boolean = false,
    val destinationDropped: Boolean = false,
    val cancelled: Boolean = false,
    /** True when the tracker could not schedule the alarm: the screen must say the alarm is NOT set. */
    val alarmNotSet: Boolean = false
) {
    /** Minutes late (positive) or early, rounded; null without both times. */
    val delayMinutes: Int?
        get() {
            val e = eta ?: return null
            val s = scheduledArrival ?: return null
            return Math.round((e.epochSecond - s.epochSecond) / 60.0).toInt()
        }
}

enum class NearestFailure { PERMISSION_DENIED, NO_FIX }
