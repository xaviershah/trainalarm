package com.trainalarm.app.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.trainalarm.app.R

object AlarmNotifications {
    const val CHANNEL_ID = "alarm"
    const val NOTIFICATION_ID = 1001

    fun build(context: Context): Notification {
        ensureChannel(context)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(context, 0, Intent(context, AlarmActivity::class.java), flags)
        val stop = PendingIntent.getService(context, 1, AlarmService.stopIntent(context), flags)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(context.getString(R.string.alarm_title))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .addAction(0, context.getString(R.string.alarm_stop), stop)
            .build()
    }

    private fun ensureChannel(context: Context) {
        // No channel sound: AlarmPlayer plays the audio itself, on the alarm stream.
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.alarm_channel_name),
            NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
            enableVibration(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
