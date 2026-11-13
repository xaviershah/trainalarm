package com.trainalarm.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.trainalarm.app.ui.RootScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val environment = AppEnvironment.get(this, intent.getStringExtra("scenario"), intent.getIntExtra("demoSpeed", 1).coerceAtLeast(1).toDouble())
        environment.prompter.delegate = ActivityPermissionPrompter(this)
        setContent { RootScreen(environment) }
    }

    override fun onDestroy() {
        // The environment outlives the Activity; do not keep a destroyed Activity alive.
        if (isFinishing) AppEnvironment.get(this, null).prompter.delegate = null
        super.onDestroy()
    }
}
