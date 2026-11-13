package com.trainalarm.app.demo

import com.trainalarm.app.PermissionKind
import com.trainalarm.app.PermissionStatus
import com.trainalarm.app.PermissionsGateway
import com.trainalarm.app.alarm.AlarmScheduler
import com.trainalarm.app.model.Journey
import com.trainalarm.app.model.Service
import com.trainalarm.app.model.Station
import com.trainalarm.app.model.Stop
import com.trainalarm.app.model.StopDisplay
import com.trainalarm.app.provider.ProviderError
import com.trainalarm.app.provider.TrainDataProvider
import com.trainalarm.app.tracking.TrackerClock
import java.time.Instant
import java.time.Duration
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Scripted trains for demo mode and screenshots. Debug builds only. All times are relative to the clock at
 * the moment the provider was made, and stay fixed after that. No real alarm is ever armed (see [DemoAlarmScheduler]).
 */
class DemoProvider(clock: TrackerClock, underway: Boolean = false) : TrainDataProvider {
    /** Minutes from the epoch to the first train's departure: 100 minutes ago when [underway], so the route strip is part-way along. */
    private val firstTrainDeparture = if (underway) -100L else 20L

    /** Fixed when the provider is made: a later poll must see the same timetable, or the ETA would drift. */
    private val epoch = clock.now()

    private class Call(val crs: String, val name: String, val minutes: Long)
    private class Train(val id: String, val departsInMinutes: Long, val delay: Long, val cancelled: Boolean)

    override suspend fun searchStations(query: String): List<Station> = emptyList()

    override suspend fun departureBoard(station: Station, from: Instant, callingAt: Station?): List<Service> {
        if (station.id != "KGX") return emptyList()
        if (callingAt != null && route.drop(1).none { it.crs == callingAt.id }) return emptyList()
        return trains.map { service(it, summaryOnly = true) }
    }

    override suspend fun serviceDetails(serviceId: String, date: LocalDate): Service {
        val train = trains.firstOrNull { it.id == serviceId } ?: throw ProviderError.Http(404)
        return service(train, summaryOnly = false)
    }

    private fun service(train: Train, summaryOnly: Boolean): Service {
        val base = epoch.truncatedTo(ChronoUnit.MINUTES)
            .plusSeconds((if (train.id == "demo-1") firstTrainDeparture else train.departsInMinutes) * 60)
        val stops = route.mapIndexed { index, call ->
            val scheduled = base.plusSeconds(call.minutes * 60)
            val estimated = if (train.cancelled) null else scheduled.plusSeconds(train.delay * 60)
            val isOrigin = index == 0
            Stop(
                station = Station(call.crs, call.name, 0.0, 0.0),
                scheduledArrival = if (isOrigin) null else scheduled,
                scheduledDeparture = scheduled,
                estimatedArrival = if (isOrigin) null else estimated,
                estimatedDeparture = estimated,
                isArrivalCancelled = train.cancelled && !isOrigin,
                isDepartureCancelled = train.cancelled,
                displayAs = if (train.cancelled) StopDisplay.CANCELLED else StopDisplay.CALL,
                hasArrived = false
            )
        }
        val shown = if (summaryOnly) listOf(stops.first()) else stops
        return Service(train.id, "LNER", Journey(stops.first().station, stops.last().station, shown))
    }

    private companion object {
        val route = listOf(
            Call("KGX", "London Kings Cross", 0), Call("PBO", "Peterborough", 45),
            Call("NCL", "Newcastle", 190), Call("BWK", "Berwick-upon-Tweed", 220), Call("EDB", "Edinburgh", 250)
        )
        val trains = listOf(
            Train("demo-1", 20, 0, false), Train("demo-2", 50, 4, false), Train("demo-3", 80, 0, true)
        )
    }
}

/** Demo mode never arms a real alarm. */
class NoopAlarmScheduler : AlarmScheduler {
    override suspend fun schedule(fireAt: Instant) {}
    override suspend fun cancel() {}
}

/** A clock that runs [speed] times faster than the wall clock (`--ei demoSpeed 60`), so a demo reaches the alarm and the arrival quickly. */
class DemoClock(speed: Double, private val wall: () -> Instant = { Instant.now() }) : TrackerClock {
    private val speed = maxOf(speed, 1.0)
    private val start = wall()

    override fun now(): Instant = start.plusNanos((Duration.between(start, wall()).toNanos() * speed).toLong())

    override suspend fun sleep(seconds: Double) = delay((maxOf(seconds, 0.0) / speed * 1000).toLong())
}

/**
 * Demo mode's alarm: nothing is armed with the system, but when the demo clock reaches the fire time the app is told the
 * alarm rang, so a demo run can reach the ringing screen. Replaces any earlier alarm.
 */
class DemoAlarmScheduler(private val clock: TrackerClock, private val scope: CoroutineScope, private val onRing: () -> Unit) : AlarmScheduler {
    private var job: Job? = null

    override suspend fun schedule(fireAt: Instant) {
        job?.cancel()
        val seconds = maxOf(Duration.between(clock.now(), fireAt).toMillis() / 1000.0, 0.0)
        job = scope.launch {
            clock.sleep(seconds)
            onRing()
        }
    }

    override suspend fun cancel() {
        job?.cancel()
        job = null
    }
}

/** Demo mode asks for nothing and everything is granted. */
class DemoPermissions : PermissionsGateway {
    override val requiredKinds: List<PermissionKind> = listOf(PermissionKind.ALARM)   // listed (granted) so Settings shows a row; location stays unlisted so the demo never starts GPS
    override val optionalKinds: List<PermissionKind> = emptyList()
    override fun status(kind: PermissionKind) = PermissionStatus.GRANTED
    override suspend fun request(kind: PermissionKind) = PermissionStatus.GRANTED
}
