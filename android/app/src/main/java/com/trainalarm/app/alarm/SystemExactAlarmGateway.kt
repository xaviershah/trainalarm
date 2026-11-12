package com.trainalarm.app.alarm

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.trainalarm.app.MainActivity
import java.time.Instant

/** Real [ExactAlarmGateway]. One fixed PendingIntent, so re-setting replaces the previous alarm. */
class SystemExactAlarmGateway(private val context: Context) : ExactAlarmGateway {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    override fun canScheduleExactAlarms(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    override fun setAlarmClock(fireAt: Instant) {
        val show = PendingIntent.getActivity(context, SHOW_REQUEST, Intent(context, MainActivity::class.java), flags)
        Log.i(TAG, "setAlarmClock for $fireAt")
        alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(fireAt.toEpochMilli(), show), fire())
    }

    override fun cancelAlarm() {
        Log.i(TAG, "cancelAlarm")
        alarmManager.cancel(fire())
    }

    override fun stopRinging() {
        context.stopService(Intent(context, AlarmService::class.java))
        AlarmPlayer.stop()
        context.getSystemService(NotificationManager::class.java).cancel(AlarmNotifications.NOTIFICATION_ID)
    }

    private fun fire(): PendingIntent =
        PendingIntent.getBroadcast(context, FIRE_REQUEST, Intent(context, AlarmReceiver::class.java), flags)

    private companion object {
        const val TAG = "ExactAlarmGateway"
        const val FIRE_REQUEST = 100
        const val SHOW_REQUEST = 101
    }
}
