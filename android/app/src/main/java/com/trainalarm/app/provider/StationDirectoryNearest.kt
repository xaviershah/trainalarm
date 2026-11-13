package com.trainalarm.app.provider

import com.trainalarm.app.model.Station
import com.trainalarm.app.tracking.Geo

/** The station closest to a point, or null if none has coordinates. */
fun StationDirectory.nearest(latitude: Double, longitude: Double): Station? =
    stations.filter { it.hasCoordinate }.minByOrNull { Geo.distanceMetres(latitude, longitude, it.latitude, it.longitude) }
