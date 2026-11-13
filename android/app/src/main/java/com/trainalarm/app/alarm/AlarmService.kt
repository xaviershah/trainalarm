package com.trainalarm.app.alarm

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat

class AlarmService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRinging()
            return START_NOT_STICKY
        }
        Log.i(TAG, "alarm service started")
        val notification = AlarmNotifications.build(this)
        try {
            ServiceCompat.startForeground(this, AlarmNotifications.NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } catch (e: Exception) {
            // e.g. SecurityException or ForegroundServiceStartNotAllowedException: still notify and ring.
            // stopRinging() (Stop action or timeout) cancels the notification and stops the service as usual.
            Log.w(TAG, "could not go foreground; ringing without foreground status", e)
            getSystemService(NotificationManager::class.java).notify(AlarmNotifications.NOTIFICATION_ID, notification)
        }
        AlarmPlayer.start(this) { stopRinging() }
        AlarmEvents.started()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        AlarmPlayer.stop()
        AlarmEvents.stopped()
        super.onDestroy()
    }

    private fun stopRinging() {
        Log.i(TAG, "alarm stopped")
        AlarmPlayer.stop()
        AlarmEvents.stopped()
        getSystemService(NotificationManager::class.java).cancel(AlarmNotifications.NOTIFICATION_ID)
        stopSelf()
    }

    companion object {
        private const val TAG = "AlarmService"
        const val ACTION_STOP = "com.trainalarm.app.alarm.STOP"
        fun stopIntent(context: Context): Intent = Intent(context, AlarmService::class.java).setAction(ACTION_STOP)
    }
}
