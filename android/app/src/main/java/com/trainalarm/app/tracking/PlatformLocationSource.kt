package com.trainalarm.app.tracking

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import java.time.Instant

object LocationFixes {
    /** An absent or negative accuracy becomes infinite so the tracker's `maxFixAccuracy` check drops the fix. */
    fun from(latitude: Double, longitude: Double, timeMillis: Long, accuracyMeters: Float?): LocationFix =
        LocationFix(
            latitude, longitude, Instant.ofEpochMilli(timeMillis),
            accuracyMeters?.takeIf { it >= 0f }?.toDouble() ?: Double.POSITIVE_INFINITY
        )
}

/** Keeps the latest GPS fix for the tracker, using the platform LocationManager (no Play Services). While in use only. */
class PlatformLocationSource(context: Context) : LocationSource, LocationListener {
    private val manager = context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @Volatile private var latest: LocationFix? = null

    /** Call only after the user has allowed location while in use. False if permission or GPS is missing. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean = try {
        manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 10_000L, 0f, this, Looper.getMainLooper())
        true
    } catch (e: SecurityException) {
        false
    } catch (e: IllegalArgumentException) {
        false
    }

    fun stop() = manager.removeUpdates(this)

    override fun latestFix(): LocationFix? = latest

    override fun onLocationChanged(location: Location) {
        latest = LocationFixes.from(
            location.latitude, location.longitude, location.time,
            if (location.hasAccuracy()) location.accuracy else null
        )
    }

    // These three are only optional from API 30, and minSdk is 29, so they must exist.
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    @Deprecated("Only called on API 28 and below")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
}
