package com.trainalarm.app

import com.trainalarm.app.model.Service
import com.trainalarm.app.model.Station
import com.trainalarm.app.provider.StationDirectory
import com.trainalarm.app.provider.TrainDataProvider
import com.trainalarm.app.tracking.FakeAlarmScheduler
import com.trainalarm.app.tracking.FakeClock
import com.trainalarm.app.tracking.FakeLocationSource
import com.trainalarm.app.tracking.LocationFix
import com.trainalarm.app.tracking.ServiceBuilder
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SessionModelNearestTest {
    private class Provider : TrainDataProvider {
        override suspend fun searchStations(query: String): List<Station> = emptyList()
        override suspend fun departureBoard(station: Station, from: Instant, callingAt: Station?): List<Service> =
            listOf(ServiceBuilder.happyPath())
        override suspend fun serviceDetails(serviceId: String, date: LocalDate): Service = ServiceBuilder.happyPath()
    }

    private class Permissions : PermissionsGateway {
        override val requiredKinds = listOf(PermissionKind.ALARM)
        override val optionalKinds = listOf(PermissionKind.LOCATION)
        val statuses = mutableMapOf(PermissionKind.ALARM to PermissionStatus.GRANTED, PermissionKind.LOCATION to PermissionStatus.GRANTED)
        val answers = mutableMapOf<PermissionKind, PermissionStatus>()
        val requested = mutableListOf<PermissionKind>()
        override fun status(kind: PermissionKind) = statuses[kind] ?: PermissionStatus.GRANTED
        override suspend fun request(kind: PermissionKind): PermissionStatus {
            requested += kind
            return answers[kind] ?: status(kind)
        }
    }

    private class Control : LocationControl {
        var started = 0
        var stopped = 0
        override fun start(): Boolean { started += 1; return true }
        override fun stop() { stopped += 1 }
    }

    private class MemoryKeyValues : KeyValues {
        val map = mutableMapOf<String, Any>()
        override fun getString(key: String) = map[key] as? String
        override fun putString(key: String, value: String) { map[key] = value }
        override fun getInt(key: String) = map[key] as? Int
        override fun putInt(key: String, value: Int) { map[key] = value }
    }

    private class Harness(scope: CoroutineScope) {
        val directory = StationDirectory.parse(
            """[{"crs":"WAT","name":"London Waterloo","lat":51.5031,"lon":-0.1132},{"crs":"WOK","name":"Woking","lat":51.3189,"lon":-0.5569},{"crs":"BSK","name":"Basingstoke","lat":51.2686,"lon":-1.0872}]"""
        )
        val location = FakeLocationSource()
        val control = Control()
        val permissions = Permissions()
        val clock = FakeClock(ServiceBuilder.time("07:00:00"))
        val model = SessionModel(
            Provider(), FakeAlarmScheduler(), directory, location, clock, permissions,
            SettingsStore(MemoryKeyValues()), scope, locationControl = control
        )
    }

    private fun run(block: suspend Harness.(CoroutineScope) -> Unit) = runBlocking<Unit> { Harness(this).block(this) }

    private fun fix(lat: Double, lon: Double, accuracy: Double = 20.0, at: String = "07:00:00") =
        LocationFix(lat, lon, ServiceBuilder.time(at), accuracy)

    @Test fun nearestFillsFromAndStopsTheGps() = run {
        location.fix = fix(51.32, -0.56)
        model.useNearestStation()
        assertEquals("WOK", model.from?.id)
        assertNull(model.nearestFailure)
        assertFalse(model.isFindingNearest)
        assertEquals(1, control.started)
        assertEquals(1, control.stopped)
    }

    @Test fun nearestAsksForLocationOnlyWhenNotDeterminedAndReportsARefusal() = run {
        permissions.statuses[PermissionKind.LOCATION] = PermissionStatus.NOT_DETERMINED
        permissions.answers[PermissionKind.LOCATION] = PermissionStatus.DENIED
        model.useNearestStation()
        assertEquals(listOf(PermissionKind.LOCATION), permissions.requested)
        assertEquals(NearestFailure.PERMISSION_DENIED, model.nearestFailure)
        assertNull(model.from)
        assertEquals(0, control.started)
    }

    @Test fun nearestNeverReprompsADeniedPermission() = run {
        permissions.statuses[PermissionKind.LOCATION] = PermissionStatus.DENIED
        model.useNearestStation()
        assertEquals(emptyList<PermissionKind>(), permissions.requested)
        assertEquals(NearestFailure.PERMISSION_DENIED, model.nearestFailure)
    }

    @Test fun nearestWithNoFixInTimeIsReported() = run { scope ->
        val job = scope.launch { model.useNearestStation() }
        for (attempt in 1..SessionModel.FIX_WAIT_ATTEMPTS) {
            clock.awaitSleepCount(attempt)
            clock.tick(SessionModel.FIX_WAIT_INTERVAL_SECONDS)
        }
        job.join()
        assertEquals(NearestFailure.NO_FIX, model.nearestFailure)
        assertNull(model.from)
        assertEquals(1, control.stopped)
    }

    @Test fun aFixWithUnknownAccuracyIsNotUsed() = run { scope ->
        location.fix = fix(51.32, -0.56, accuracy = Double.POSITIVE_INFINITY)
        val job = scope.launch { model.useNearestStation() }
        for (attempt in 1..SessionModel.FIX_WAIT_ATTEMPTS) {
            clock.awaitSleepCount(attempt)
            clock.tick(SessionModel.FIX_WAIT_INTERVAL_SECONDS)
        }
        job.join()
        assertEquals(NearestFailure.NO_FIX, model.nearestFailure)
    }

    @Test fun startBeginsTheGpsAndImOffStopsIt() = run {
        model.selectFrom(model.search("waterloo")[0])
        model.selectTo(model.search("basing")[0])
        model.loadBoard()
        model.choose((model.board as BoardState.Loaded).services[0])
        model.setLead(15)
        model.start()
        clock.awaitSleepCount(1)
        assertEquals(1, control.started)
        model.imOff()
        assertEquals(1, control.stopped)
    }

    private suspend fun Harness.runThroughTheWait(job: kotlinx.coroutines.Job) {
        for (attempt in 1..SessionModel.FIX_WAIT_ATTEMPTS) {
            clock.awaitSleepCount(attempt)
            clock.tick(SessionModel.FIX_WAIT_INTERVAL_SECONDS)
        }
        job.join()
    }

    @Test fun aStaleFixIsIgnoredAndALaterFreshFixIsUsed() = run { scope ->
        location.fix = fix(51.5031, -0.1132, at = "06:50:00")
        val job = scope.launch { model.useNearestStation() }
        clock.awaitSleepCount(1)
        location.fix = fix(51.32, -0.56)
        clock.tick(SessionModel.FIX_WAIT_INTERVAL_SECONDS)
        job.join()
        assertEquals("WOK", model.from?.id)
        assertNull(model.nearestFailure)
    }

    @Test fun onlyAStaleFixIsNoFix() = run { scope ->
        location.fix = fix(51.32, -0.56, at = "06:50:00")
        runThroughTheWait(scope.launch { model.useNearestStation() })
        assertEquals(NearestFailure.NO_FIX, model.nearestFailure)
        assertNull(model.from)
    }

    @Test fun pickingFromWhileTheLookupWaitsKeepsTheUsersChoice() = run { scope ->
        location.fix = fix(51.32, -0.56, at = "06:50:00")
        val job = scope.launch { model.useNearestStation() }
        clock.awaitSleepCount(1)
        model.selectFrom(model.search("basing")[0])
        location.fix = fix(51.32, -0.56)
        clock.tick(SessionModel.FIX_WAIT_INTERVAL_SECONDS)
        job.join()
        assertEquals("BSK", model.from?.id)
        assertNull(model.nearestFailure)
        assertEquals(1, control.stopped)
    }

    @Test fun startingWhileTheLookupWaitsLeavesTheSessionsGpsRunning() = run { scope ->
        model.selectFrom(model.search("waterloo")[0])
        model.selectTo(model.search("basing")[0])
        model.loadBoard()
        model.choose((model.board as BoardState.Loaded).services[0])
        model.setLead(15)
        val job = scope.launch { model.useNearestStation() }
        clock.awaitSleepCount(1)
        model.start()
        location.fix = fix(51.32, -0.56)
        clock.tick(SessionModel.FIX_WAIT_INTERVAL_SECONDS)
        job.join()
        assertEquals("WAT", model.from?.id)
        assertEquals(0, control.stopped)
        model.imOff()
        assertEquals(1, control.stopped)
    }

    @Test fun cancellingTheLookupStopsTheGps() = run { scope ->
        val job = scope.launch { model.useNearestStation() }
        clock.awaitSleepCount(1)
        job.cancel()
        job.join()
        assertEquals(1, control.stopped)
        assertFalse(model.isFindingNearest)
    }

    @Test fun aDoubleTapRunsOneLookupOnly() = run { scope ->
        val first = scope.launch { model.useNearestStation() }
        clock.awaitSleepCount(1)
        model.useNearestStation()   // second tap while the first is waiting for a fix
        assertEquals(1, control.started)
        assertEquals(0, control.stopped)
        location.fix = fix(51.32, -0.56)
        clock.tick(SessionModel.FIX_WAIT_INTERVAL_SECONDS)
        first.join()
        assertEquals("WOK", model.from?.id)
        assertEquals(1, control.started)
        assertEquals(1, control.stopped)
    }
}
