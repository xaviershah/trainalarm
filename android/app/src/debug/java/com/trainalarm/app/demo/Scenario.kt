package com.trainalarm.app.demo

import com.trainalarm.app.BoardState
import com.trainalarm.app.SessionModel
import com.trainalarm.app.SettingsStore
import com.trainalarm.app.provider.StationDirectory
import com.trainalarm.app.tracking.FailureKind
import com.trainalarm.app.tracking.TrackerEvent
import java.time.Instant
import kotlinx.coroutines.delay

/**
 * Opens the app in a named state with no live data, for screenshots and demos. Debug builds only.
 * Android reads the `scenario` intent extra.
 */
enum class Scenario(val argument: String) {
    HOME("home"), BOARD("board"), CONFIRM("confirm"), TRACKING("tracking"),
    TRACKING_LATE("tracking-late"), CANCELLED("cancelled"), DROPPED("dropped"),
    FEED_LOST("feed-lost"), ALARM_NOT_SET("alarm-not-set"), RINGING("ringing"),
    ENDED("ended"), SETTINGS("settings");

    val opensSettings: Boolean get() = this == SETTINGS

    /** The screens with a route strip show a train that has already left, so the marker is part-way along. */
    val trainIsUnderway: Boolean
        get() = this in setOf(TRACKING, TRACKING_LATE, CANCELLED, DROPPED, FEED_LOST, ALARM_NOT_SET)

    /** Drives the model from its empty state to this scenario, using the demo trains from KGX to EDB. */
    suspend fun apply(model: SessionModel, settings: SettingsStore, directory: StationDirectory, now: Instant) {
        if (this == SETTINGS) settings.setDefaultLeadMinutes(SETTINGS_LEAD)
        if (this == HOME || this == SETTINGS) return
        val from = directory.station("KGX") ?: return
        val to = directory.station("EDB") ?: return
        model.selectFrom(from)
        model.selectTo(to)
        model.loadBoard()
        if (this == BOARD) return
        val loaded = model.board as? BoardState.Loaded ?: return
        model.choose(loaded.services[0])
        model.setLead(if (this == CONFIRM) CONFIRM_LEAD else 15)
        if (this == CONFIRM) return
        model.start()
        // The tracker publishes its own start-up events. Let them arrive first, or they overwrite ours.
        delay(500)
        val eta = model.display.eta ?: now
        val alarm = model.display.alarmAt ?: now
        when (this) {
            TRACKING_LATE -> model.handle(TrackerEvent.EtaChanged(eta, eta.plusSeconds(240), alarm.plusSeconds(240)))
            CANCELLED -> model.handle(TrackerEvent.ServiceCancelled(now))
            DROPPED -> model.handle(TrackerEvent.DestinationLost(now))
            FEED_LOST -> model.handle(TrackerEvent.FeedLost(now.minusSeconds(360), FailureKind.Network))
            ALARM_NOT_SET -> model.handle(TrackerEvent.AlarmSchedulingFailed(alarm, "demo"))
            RINGING -> model.alarmRang()
            ENDED -> model.handle(TrackerEvent.Arrived(now))
            else -> Unit
        }
    }

    companion object {
        /** The default lead time the `settings` scenario shows, and the lead time of the `confirm` scenario.
         *  Both are not multiples of 5, so the screenshots show a minute that has no label of its own. */
        const val SETTINGS_LEAD = 7
        const val CONFIRM_LEAD = 7

        fun from(argument: String?): Scenario? = values().firstOrNull { it.argument == argument }
    }
}
