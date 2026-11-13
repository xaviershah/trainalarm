package com.trainalarm.app

import android.content.Context
import android.content.Intent
import android.util.Log
import com.trainalarm.app.alarm.AlarmEvents
import com.trainalarm.app.alarm.ExactAlarmScheduler
import com.trainalarm.app.alarm.SystemExactAlarmGateway
import com.trainalarm.app.demo.DemoEntry
import com.trainalarm.app.model.Service
import com.trainalarm.app.model.Station
import com.trainalarm.app.provider.DirectoryBackedProvider
import com.trainalarm.app.provider.ProviderError
import com.trainalarm.app.provider.RttProvider
import com.trainalarm.app.provider.StationDirectory
import com.trainalarm.app.provider.TrainDataProvider
import com.trainalarm.app.tracking.PlatformLocationSource
import com.trainalarm.app.tracking.SystemTrackerClock
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** What the debug demo entry hands back (see `DemoEntry`). Release builds never get one. */
class DemoSetup(val model: SessionModel, val opensSettings: Boolean, val apply: suspend () -> Unit)

/** Lets the Activity that is on screen answer the model's permission requests. */
class SwitchablePrompter : PermissionPrompter {
    @Volatile var delegate: PermissionPrompter? = null
    override suspend fun requestRuntimePermission(permission: String) { delegate?.requestRuntimePermission(permission) }
    override suspend fun openAndAwaitReturn(intent: Intent) { delegate?.openAndAwaitReturn(intent) }
}

/**
 * The composition root: builds the real app (RTT provider, exact alarms, platform GPS) or, in debug builds
 * without a token or with the `scenario` intent extra, the demo app. One per process, so the session
 * survives Activity recreation. A killed process ends the session (resume is Stage 5).
 */
class AppEnvironment private constructor(
    val model: SessionModel,
    val settings: SettingsStore,
    val prompter: SwitchablePrompter,
    val liveDataSetUp: Boolean,
    val isDemo: Boolean,
    val opensSettings: Boolean,
    val applyScenario: (suspend () -> Unit)?
) {
    companion object {
        @Volatile private var instance: AppEnvironment? = null

        fun get(context: Context, scenarioExtra: String?, demoSpeed: Double = 1.0): AppEnvironment =
            instance ?: synchronized(this) { instance ?: build(context.applicationContext, scenarioExtra, demoSpeed).also { instance = it } }

        private fun build(context: Context, scenarioExtra: String?, demoSpeed: Double): AppEnvironment {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val keyValues = SharedPrefsKeyValues(context.getSharedPreferences("trainalarm", Context.MODE_PRIVATE))
            val settings = SettingsStore(keyValues)
            val directory = StationDirectory.load(context)
            val prompter = SwitchablePrompter()
            val token = RttToken.current

            val demo = DemoEntry.create(scenarioExtra, token == null, directory, settings, scope, demoSpeed)
            if (demo != null) {
                return AppEnvironment(demo.model, settings, prompter, true, true, demo.opensSettings, onceOnly(demo.apply))
            }
            val location = PlatformLocationSource(context)
            val base: TrainDataProvider = token?.let { RttProvider(it) } ?: UnavailableProvider()
            val model = SessionModel(
                provider = DirectoryBackedProvider(base, directory),
                scheduler = ExactAlarmScheduler(SystemExactAlarmGateway(context)),
                directory = directory, location = location, clock = SystemTrackerClock(),
                permissions = SystemPermissions(AndroidPermissionPlatform(context, prompter), KeyValuesAskedStore(keyValues)),
                settings = settings, scope = scope, locationControl = location.asControl(),
                onAlarmError = { message, error -> Log.e("TrainAlarm", message, error) }
            )
            scope.launch { AlarmEvents.ringing.collect { if (it) model.alarmRang() } }
            return AppEnvironment(model, settings, prompter, token != null, false, false, null)
        }
    }
}

/** The scenario is applied once, even if the Activity is recreated (a second run would start a second tracker). */
private fun onceOnly(block: suspend () -> Unit): suspend () -> Unit {
    val done = java.util.concurrent.atomic.AtomicBoolean(false)
    return { if (done.compareAndSet(false, true)) block() }
}

/** Used when there is no RTT token in a non-demo build. Every call fails, and the picker says why. */
class UnavailableProvider : TrainDataProvider {
    override suspend fun searchStations(query: String): List<Station> = throw ProviderError.NotImplemented("Live data is not set up")
    override suspend fun departureBoard(station: Station, from: Instant, callingAt: Station?): List<Service> =
        throw ProviderError.NotImplemented("Live data is not set up")
    override suspend fun serviceDetails(serviceId: String, date: LocalDate): Service =
        throw ProviderError.NotImplemented("Live data is not set up")
}
