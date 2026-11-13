package com.trainalarm.app.demo

import com.trainalarm.app.DemoSetup
import com.trainalarm.app.SessionModel
import com.trainalarm.app.SettingsStore
import com.trainalarm.app.provider.DirectoryBackedProvider
import com.trainalarm.app.provider.StationDirectory
import com.trainalarm.app.tracking.LocationFix
import com.trainalarm.app.tracking.LocationSource
import com.trainalarm.app.tracking.SystemTrackerClock
import java.time.Instant
import kotlinx.coroutines.CoroutineScope

/** Debug builds: the demo app when a scenario is named or there is no RTT token. */
object DemoEntry {
    fun create(scenarioExtra: String?, noToken: Boolean, directory: StationDirectory, settings: SettingsStore, scope: CoroutineScope, speed: Double): DemoSetup? {
        val scenario = Scenario.from(scenarioExtra)
        if (scenario == null && !noToken) return null
        val clock = DemoClock(speed)
        var modelRef: SessionModel? = null
        val model = SessionModel(
            provider = DirectoryBackedProvider(DemoProvider(clock, underway = scenario?.trainIsUnderway ?: false), directory),
            scheduler = DemoAlarmScheduler(clock, scope) { modelRef?.alarmRang() }, directory = directory,
            location = object : LocationSource { override fun latestFix(): LocationFix? = null },
            clock = clock, permissions = DemoPermissions(), settings = settings, scope = scope
        )
        modelRef = model
        return DemoSetup(model, scenario?.opensSettings ?: false) { scenario?.apply(model, directory, Instant.now()) }
    }
}
