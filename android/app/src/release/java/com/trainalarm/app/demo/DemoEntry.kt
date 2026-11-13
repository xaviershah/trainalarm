package com.trainalarm.app.demo

import com.trainalarm.app.DemoSetup
import com.trainalarm.app.SettingsStore
import com.trainalarm.app.provider.StationDirectory
import kotlinx.coroutines.CoroutineScope

/** Release builds contain no demo code. */
object DemoEntry {
    @Suppress("UNUSED_PARAMETER")
    fun create(scenarioExtra: String?, noToken: Boolean, directory: StationDirectory, settings: SettingsStore, scope: CoroutineScope, speed: Double): DemoSetup? = null
}
