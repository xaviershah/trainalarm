package com.trainalarm.app.tracking

import java.time.Instant

/** Why a poll failed, derived from `ProviderError` by the driver. */
sealed class FailureKind {
    data object Network : FailureKind()
    data class Http(val code: Int) : FailureKind()
    data object Malformed : FailureKind()
    data object Other : FailureKind()
}

enum class EtaSource { LAST_KNOWN_TRAJECTORY, GPS_PACE }

/** What the tracker tells the rest of the app. See the spec's Events table. */
sealed class TrackerEvent {
    data class EtaChanged(val oldEta: Instant?, val newEta: Instant, val fireAt: Instant) : TrackerEvent()
    data class DestinationLost(val at: Instant) : TrackerEvent()
    data class ServiceCancelled(val at: Instant) : TrackerEvent()
    data class DestinationRestored(val newEta: Instant?) : TrackerEvent()
    data class FeedLost(val lastGoodAt: Instant?, val kind: FailureKind) : TrackerEvent()
    data class EtaEstimated(
        val eta: Instant,
        val source: EtaSource,
        val staleness: Double,
        val rescheduled: Boolean
    ) : TrackerEvent()
    data class FeedRecovered(val outage: Double, val freshEta: Instant?, val deltaFromFallback: Double?) : TrackerEvent()
    data class AlarmSchedulingFailed(val fireAt: Instant, val message: String) : TrackerEvent()
    data class Arrived(val at: Instant) : TrackerEvent()
}
