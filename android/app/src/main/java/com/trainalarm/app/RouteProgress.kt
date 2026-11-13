package com.trainalarm.app

import com.trainalarm.app.model.Stop
import java.time.Duration
import java.time.Instant

/**
 * Where the train is between the boarding station and the destination, for the route strip. Stop times are
 * the ones captured when tracking started (the tracker does not publish updated stops), so only [eta] is live.
 */
data class RouteProgress(val fraction: Double, val nextStop: Stop?, val stopsLeft: Int) {
    companion object {
        fun make(stops: List<Stop>, fromId: String, toId: String, eta: Instant?, now: Instant): RouteProgress? {
            val boardIndex = stops.indexOfFirst { it.station.id == fromId }
            if (boardIndex < 0) return null
            val destIndex = stops.indices.firstOrNull { it > boardIndex && stops[it].station.id == toId } ?: return null
            val ahead = stops.subList(boardIndex + 1, destIndex + 1).filter { !it.hasArrived && (it.bestArrival ?: Instant.MAX) > now }
            var fraction = 0.0
            val board = stops[boardIndex]
            val departure = board.estimatedDeparture ?: board.scheduledDeparture
            val arrival = eta ?: stops[destIndex].bestArrival
            if (departure != null && arrival != null && arrival > departure) {
                fraction = (Duration.between(departure, now).toMillis().toDouble() / Duration.between(departure, arrival).toMillis()).coerceIn(0.0, 1.0)
            }
            return RouteProgress(fraction, ahead.firstOrNull(), ahead.size)
        }
    }
}
