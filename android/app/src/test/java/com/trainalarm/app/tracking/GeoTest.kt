package com.trainalarm.app.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoTest {
    @Test
    fun haversineDistanceVectors() {
        // lat1, lon1, lat2, lon2, expected metres
        val vectors = listOf(
            listOf(0.0, 0.0, 0.0, 1.0, 111194.927),
            listOf(51.5031, -0.1132, 51.5031, -0.1132, 0.0),
            listOf(51.5031, -0.1132, 51.2679, -1.0877, 72505.005),
            listOf(90.0, 0.0, 0.0, 0.0, 10007543.398),
            listOf(51.5, 0.0, 51.6, 0.0, 11119.493),
            listOf(0.0, 0.0, 0.0, 180.0, 20015086.796)
        )
        for ((lat1, lon1, lat2, lon2, expected) in vectors) {
            assertEquals("($lat1,$lon1) to ($lat2,$lon2)", expected, Geo.distanceMetres(lat1, lon1, lat2, lon2), 0.01)
        }
    }
}
