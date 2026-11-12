package com.trainalarm.app.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log

/** Plays the default alarm sound on the alarm stream until [stop] or the safety timeout. The user's alarm volume is not touched. */
object AlarmPlayer {
    private const val TAG = "AlarmPlayer"
    const val TIMEOUT_MS = 5 * 60 * 1000L
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null

    fun start(context: Context, onTimeout: () -> Unit) {
        stop()
        val candidates = listOfNotNull(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
        )
        // Armed even if no sound plays, so the notification and service never outlive the timeout.
        handler.postDelayed(onTimeout, TIMEOUT_MS)
        if (candidates.isEmpty()) {
            Log.e(TAG, "no default alarm or ringtone sound on this device")
            return
        }
        for (uri in candidates) {
            val started = tryPlay(context, uri)
            if (started != null) {
                player = started
                return
            }
        }
        Log.e(TAG, "no alarm sound could be played; the notification stays up without audio")
    }

    /** Returns a playing player, or null (after releasing it and logging) if anything failed. */
    private fun tryPlay(context: Context, uri: Uri): MediaPlayer? {
        val candidate = MediaPlayer()
        return try {
            candidate.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            candidate.setDataSource(context.applicationContext, uri)
            candidate.isLooping = true
            candidate.prepare()
            candidate.start()
            candidate
        } catch (e: Exception) {
            Log.e(TAG, "could not play $uri", e)
            candidate.runCatching { release() }
            null
        }
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        player?.let {
            it.runCatching { stop() }
            it.runCatching { release() }
        }
        player = null
    }
}
