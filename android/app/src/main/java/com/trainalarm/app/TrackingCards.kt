package com.trainalarm.app

import com.trainalarm.app.tracking.EtaSource

data class StateCard(val tone: Tone, val title: String, val detail: String) {
    enum class Tone { WARN, ERROR, INFO, PLAIN }
}

object TrackingCards {
    /** The cards the tracking screen shows, most urgent first (spec section 6). */
    fun make(d: TrackingDisplay, destination: String): List<StateCard> {
        val cards = mutableListOf<StateCard>()
        if (d.alarmNotSet) {
            cards += StateCard(StateCard.Tone.ERROR, "Your alarm is NOT set", "TrainAlarm could not schedule it and will keep trying. Do not rely on it yet.")
        }
        if (d.cancelled) {
            cards += StateCard(StateCard.Tone.WARN, "Train cancelled", "This train will not run. Your alarm stays set until you tap I'm off.")
        }
        if (d.destinationDropped) {
            cards += StateCard(StateCard.Tone.ERROR, "$destination is no longer on this route", "The train has been diverted. Your alarm stays set. Check with station staff.")
        }
        if (d.rateLimited) {
            cards += StateCard(StateCard.Tone.PLAIN, "Train data is busy", "Too many requests. Retrying shortly.")
        } else if (d.feedLost) {
            val last = d.lastGoodAt?.let { " Last good update ${TimeText.clock(it).plain}." } ?: ""
            cards += StateCard(StateCard.Tone.PLAIN, "No live data", "Using the timetable and your GPS.$last")
        }
        if (d.isEstimated) {
            val how = if (d.estimateSource == EtaSource.GPS_PACE) "Worked out from your speed and position." else "Worked out from the last live update."
            cards += StateCard(StateCard.Tone.INFO, "Estimated arrival", how)
        }
        return cards
    }
}
