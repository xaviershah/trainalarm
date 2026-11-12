package com.trainalarm.app

import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.trainalarm.app.alarm.DevAlarmScreen

/**
 * Stage 0 placeholder. Real navigation (journey select -> service picker ->
 * active journey -> alarm) lands in Stage 4, once the model/tracking/alarm
 * layers from Stages 1-3 exist. See docs/spec.md.
 */
class MainActivity : ComponentActivity() {
    private var resumeCount by mutableIntStateOf(0)

    override fun onResume() {
        super.onResume()
        resumeCount += 1  // re-reads permission state after the user returns from system settings
    }

    private val isDebuggable: Boolean get() = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (isDebuggable) DevAlarmScreen(resumeCount) else PlaceholderScreen()
                }
            }
        }
    }
}

@Composable
fun PlaceholderScreen() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("TrainAlarm")
        Text("Stage 0 scaffold — no journey logic yet.")
    }
}
