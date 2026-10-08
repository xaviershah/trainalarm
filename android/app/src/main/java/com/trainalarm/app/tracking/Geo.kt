package com.trainalarm.app.tracking

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class Coordinate(val latitude: Double, val longitude: Double)

object Geo {
    const val EARTH_RADIUS_METRES = 6_371_000.0

    /** Great-circle (Haversine) distance in metres. */
    fun distanceMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaPhi = Math.toRadians(lat2 - lat1)
        val deltaLambda = Math.toRadians(lon2 - lon1)
        val a = sin(deltaPhi / 2) * sin(deltaPhi / 2) +
            cos(phi1) * cos(phi2) * sin(deltaLambda / 2) * sin(deltaLambda / 2)
        return 2 * EARTH_RADIUS_METRES * asin(min(1.0, sqrt(a)))
    }
}
