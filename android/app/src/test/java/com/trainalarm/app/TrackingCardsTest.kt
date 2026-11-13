package com.trainalarm.app

import com.trainalarm.app.tracking.EtaSource
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackingCardsTest {
    private fun titles(d: TrackingDisplay) = TrackingCards.make(d, "Edinburgh").map { it.title }

    @Test fun nothingFlagged_showsNoCards() = assertEquals(emptyList<String>(), titles(TrackingDisplay()))

    @Test fun cardsAreOrderedMostUrgentFirst() {
        val d = TrackingDisplay(isEstimated = true, feedLost = true, destinationDropped = true, cancelled = true, alarmNotSet = true)
        assertEquals(
            listOf("Your alarm is NOT set", "Train cancelled", "Edinburgh is no longer on this route", "No live data", "Estimated arrival"),
            titles(d)
        )
    }

    @Test fun rateLimited_replacesTheNoLiveDataCard() =
        assertEquals(listOf("Train data is busy"), titles(TrackingDisplay(feedLost = true, rateLimited = true)))

    @Test fun noLiveData_saysWhenTheLastGoodUpdateWas() {
        val card = TrackingCards.make(TrackingDisplay(feedLost = true, lastGoodAt = Instant.parse("2026-09-13T08:00:00Z")), "Edinburgh")[0]
        assertEquals("Using the timetable and your GPS. Last good update 09:00.", card.detail)
        assertEquals(StateCard.Tone.PLAIN, card.tone)
    }

    @Test fun estimatedArrival_explainsItsSource() {
        assertEquals("Worked out from your speed and position.",
            TrackingCards.make(TrackingDisplay(isEstimated = true, estimateSource = EtaSource.GPS_PACE), "X")[0].detail)
        assertEquals("Worked out from the last live update.",
            TrackingCards.make(TrackingDisplay(isEstimated = true, estimateSource = EtaSource.LAST_KNOWN_TRAJECTORY), "X")[0].detail)
    }

    @Test fun tonesMatchTheMockup() {
        assertEquals(StateCard.Tone.WARN, TrackingCards.make(TrackingDisplay(cancelled = true), "X")[0].tone)
        assertEquals(StateCard.Tone.ERROR, TrackingCards.make(TrackingDisplay(alarmNotSet = true), "X")[0].tone)
        assertEquals(StateCard.Tone.INFO, TrackingCards.make(TrackingDisplay(isEstimated = true), "X")[0].tone)
    }
}
