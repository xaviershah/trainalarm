package com.trainalarm.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.trainalarm.app.AppEnvironment
import com.trainalarm.app.SessionPhase
import com.trainalarm.app.alarm.AlarmService
import com.trainalarm.app.theme.LocalColors
import com.trainalarm.app.theme.TrainAlarmTheme
import kotlinx.coroutines.launch

@Composable
fun RootScreen(environment: AppEnvironment) {
    val choice by environment.settings.theme.collectAsState()
    TrainAlarmTheme(choice) {
        val ringScope = rememberCoroutineScope()
        val ringContext = LocalContext.current
        val c = LocalColors.current
        var showingSettings by remember { mutableStateOf(environment.opensSettings) }
        LaunchedEffect(Unit) { environment.applyScenario?.invoke() }
        Box(Modifier.fillMaxSize().background(c.bg)) {
            when (environment.model.phase) {
                SessionPhase.Selecting, SessionPhase.Confirming ->
                    HomeScreen(environment.model, environment.liveDataSetUp, environment.isDemo) { showingSettings = true }
                SessionPhase.Tracking -> TrackingScreen(environment.model)
                SessionPhase.Ringing -> RingingScreen(environment.model.to?.name.orEmpty(), environment.model.display.eta) {
                    ringContext.startService(AlarmService.stopIntent(ringContext))   // silence first
                    ringScope.launch { environment.model.imOff() }
                }
                is SessionPhase.Ended -> EndedScreen(environment.model, (environment.model.phase as SessionPhase.Ended).reason)
            }
            if (showingSettings) SettingsScreen(environment.model, environment.settings) { showingSettings = false }
        }
    }
}
