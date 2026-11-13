package com.trainalarm.app

import com.trainalarm.app.model.Service
import com.trainalarm.app.model.Station
import com.trainalarm.app.provider.StationDirectory
import com.trainalarm.app.provider.TrainDataProvider
import com.trainalarm.app.tracking.FakeAlarmScheduler
import com.trainalarm.app.tracking.FakeClock
import com.trainalarm.app.tracking.FakeLocationSource
import com.trainalarm.app.tracking.ServiceBuilder
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionModelSettingsTest {
    private class BoardProvider : TrainDataProvider {
        override suspend fun searchStations(query: String): List<Station> = emptyList()
        override suspend fun departureBoard(station: Station, from: Instant, callingAt: Station?): List<Service> = listOf(ServiceBuilder.happyPath())
        override suspend fun serviceDetails(serviceId: String, date: LocalDate): Service = ServiceBuilder.happyPath()
    }

    private class Permissions : PermissionsGateway {
        override val requiredKinds = listOf(PermissionKind.ALARM)
        override val optionalKinds = listOf(PermissionKind.LOCATION)
        val statuses = mutableMapOf(PermissionKind.ALARM to PermissionStatus.GRANTED, PermissionKind.LOCATION to PermissionStatus.NOT_DETERMINED)
        val requested = mutableListOf<PermissionKind>()
        override fun status(kind: PermissionKind) = statuses[kind] ?: PermissionStatus.GRANTED
        override suspend fun request(kind: PermissionKind): PermissionStatus {
            requested += kind
            statuses[kind] = PermissionStatus.GRANTED
            return PermissionStatus.GRANTED
        }
    }

    private class Memory : KeyValues {
        val map = mutableMapOf<String, Any>()
        override fun getString(key: String) = map[key] as? String
        override fun putString(key: String, value: String) { map[key] = value }
        override fun getInt(key: String) = map[key] as? Int
        override fun putInt(key: String, value: Int) { map[key] = value }
    }

    private class Rig(scope: CoroutineScope) {
        val directory = StationDirectory.parse(
            """[{"crs":"WAT","name":"London Waterloo","lat":51.5031,"lon":-0.1132},{"crs":"BSK","name":"Basingstoke","lat":51.2686,"lon":-1.0872}]"""
        )
        val scheduler = FakeAlarmScheduler()
        val clock = FakeClock(ServiceBuilder.time("07:00:00"))
        val permissions = Permissions()
        val model = SessionModel(BoardProvider(), scheduler, directory, FakeLocationSource(), clock, permissions, SettingsStore(Memory()), scope)
    }

    private fun run(block: suspend Rig.() -> Unit) = runBlocking<Unit> { Rig(this).block() }

    @Test fun settingsListsRequiredPermissionsThenOptionalOnes() = run {
        assertEquals(listOf(PermissionKind.ALARM, PermissionKind.LOCATION), model.settingsPermissionKinds)
    }

    @Test fun permissionStatusReadsTheGateway() = run {
        assertEquals(PermissionStatus.GRANTED, model.permissionStatus(PermissionKind.ALARM))
        assertEquals(PermissionStatus.NOT_DETERMINED, model.permissionStatus(PermissionKind.LOCATION))
    }

    @Test fun requestingAPermissionAsksTheGatewayAndRefreshes() = run {
        val before = model.permissionRevision
        model.requestPermission(PermissionKind.LOCATION)
        assertEquals(listOf(PermissionKind.LOCATION), permissions.requested)
        assertEquals(PermissionStatus.GRANTED, model.permissionStatus(PermissionKind.LOCATION))
        assertTrue(model.permissionRevision > before)
    }

    @Test fun refreshingPermissionsBumpsTheRevision() = run {
        val before = model.permissionRevision
        model.refreshPermissions()
        assertEquals(before + 1, model.permissionRevision)
    }

    @Test fun testAlarmRingsTenSecondsFromNow() = run {
        model.scheduleTestAlarm()
        val expected = clock.now().plusSeconds(10)
        assertEquals(listOf(expected), scheduler.scheduled)
        assertEquals(TestAlarmState.Scheduled(expected), model.testAlarm)
    }

    @Test fun aFailedTestAlarmIsReported() = run {
        scheduler.failuresRemaining = 1
        model.scheduleTestAlarm()
        assertEquals(TestAlarmState.Failed, model.testAlarm)
        assertTrue(scheduler.scheduled.isEmpty())
    }

    @Test fun testAlarmIsRefusedWhileTrackingSoTheRealAlarmSurvives() = run {
        model.selectFrom(directory.station("WAT")!!)
        model.selectTo(directory.station("BSK")!!)
        model.loadBoard()
        model.choose((model.board as BoardState.Loaded).services[0])
        model.setLead(15)
        model.start()
        clock.awaitSleepCount(1)
        val attempts = scheduler.attempts.size

        assertFalse(model.canTestAlarm)
        model.scheduleTestAlarm()

        assertEquals(attempts, scheduler.attempts.size)
        assertEquals(TestAlarmState.Idle, model.testAlarm)
        model.imOff()
    }

    @Test fun permissionStatusIsSaidInWordsNotColourAlone() {
        assertEquals("Off", com.trainalarm.app.ui.permissionPillText(PermissionStatus.DENIED))
        assertEquals("Not asked", com.trainalarm.app.ui.permissionPillText(PermissionStatus.NOT_DETERMINED))
        assertTrue(com.trainalarm.app.ui.permissionStatusWords(PermissionStatus.DENIED).contains("system settings"))
        assertEquals("Allowed", com.trainalarm.app.ui.permissionStatusWords(PermissionStatus.GRANTED))
    }

    @Test fun refreshClearsATestAlarmWhoseTimeHasPassed() = run {
        model.scheduleTestAlarm()
        clock.tick(11.0)
        model.refreshTestAlarm()
        assertEquals(TestAlarmState.Idle, model.testAlarm)
    }

    @Test fun refreshKeepsATestAlarmThatIsStillAhead() = run {
        model.scheduleTestAlarm()
        val state = model.testAlarm
        clock.tick(5.0)
        model.refreshTestAlarm()
        assertEquals(state, model.testAlarm)
    }

    @Test fun refreshClearsAFailedTestAlarm() = run {
        scheduler.failuresRemaining = 1
        model.scheduleTestAlarm()
        model.refreshTestAlarm()
        assertEquals(TestAlarmState.Idle, model.testAlarm)
    }

    @Test fun startingAJourneyClearsTheTestAlarmNote() = run {
        model.scheduleTestAlarm()
        model.selectFrom(directory.station("WAT")!!)
        model.selectTo(directory.station("BSK")!!)
        model.loadBoard()
        model.choose((model.board as BoardState.Loaded).services[0])
        model.setLead(15)
        model.start()
        clock.awaitSleepCount(1)
        assertEquals(TestAlarmState.Idle, model.testAlarm)
        model.imOff()
    }
}
