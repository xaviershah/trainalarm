package com.trainalarm.app.alarm

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "alarm fired")
        try {
            ContextCompat.startForegroundService(context, Intent(context, AlarmService::class.java))
        } catch (e: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException (API 31+): still notify and ring, without a service.
            Log.w(TAG, "could not start the alarm service; ringing without it", e)
            try {
                val app = context.applicationContext
                val manager = app.getSystemService(NotificationManager::class.java)
                manager.notify(AlarmNotifications.NOTIFICATION_ID, AlarmNotifications.build(app))
                AlarmPlayer.start(app) {
                    AlarmPlayer.stop()
                    manager.cancel(AlarmNotifications.NOTIFICATION_ID)
                }
            } catch (fallback: Exception) {
                Log.e(TAG, "the alarm fallback failed too", fallback)
            }
        }
    }

    private companion object {
        const val TAG = "AlarmReceiver"
    }
}
