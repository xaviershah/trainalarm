package com.trainalarm.app.alarm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.trainalarm.app.AppEnvironment
import com.trainalarm.app.SessionPhase
import com.trainalarm.app.ui.RingingScreen
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * Shown over the lock screen while the alarm rings. If the process was started by the alarm itself
 * (no session in memory, resume is Stage 5) it shows the screen without a countdown.
 */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        // The alarm must stay stoppable even if the app state cannot be built (for example, the process was
        // started by the alarm itself and loading the stations failed): then the screen has no countdown.
        val model = runCatching { AppEnvironment.get(this, null).model }.getOrNull()
        setContent {
            RingingScreen(model?.to?.name.orEmpty(), model?.display?.eta) {
                // Silence first: ending the session can wait on a network read. MainScope outlives this activity.
                startService(AlarmService.stopIntent(this@AlarmActivity))
                if (model != null && (model.phase == SessionPhase.Ringing || model.phase == SessionPhase.Tracking)) {
                    MainScope().launch { model.imOff() }
                }
                finish()
            }
        }
    }
}
