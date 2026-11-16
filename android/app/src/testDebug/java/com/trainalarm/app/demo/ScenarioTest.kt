package com.trainalarm.app.demo

import com.trainalarm.app.EndReason
import com.trainalarm.app.BoardState
import com.trainalarm.app.SessionModel
import com.trainalarm.app.SessionPhase
import com.trainalarm.app.SettingsStore
import com.trainalarm.app.KeyValues
import com.trainalarm.app.model.Station
import com.trainalarm.app.provider.StationDirectory
import com.trainalarm.app.tracking.FakeClock
import com.trainalarm.app.tracking.FakeLocationSource
import java.time.Instant
import java.time.LocalDate
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenarioTest {
    private class MemoryKeyValues : KeyValues {
        val map = mutableMapOf<String, Any>()
        override fun getString(key: String) = map[key] as? String
        override fun putString(key: String, value: String) { map[key] = value }
        override fun getInt(key: String) = map[key] as? Int
        override fun putInt(key: String, value: Int) { map[key] = value }
    }

    private var lastSettings: SettingsStore? = null

    private val json = """[{"crs":"KGX","name":"London Kings Cross","lat":51.5309,"lon":-0.1233},{"crs":"PBO","name":"Peterborough","lat":52.5748,"lon":-0.2504},{"crs":"EDB","name":"Edinburgh","lat":55.9521,"lon":-3.1894}]"""
    private val start = Instant.parse("2026-07-01T12:00:00Z")

    private suspend fun run(scope: CoroutineScope, scenario: Scenario): SessionModel {
        val directory = StationDirectory.parse(json)
        val clock = FakeClock(start)
        val settings = SettingsStore(MemoryKeyValues()).also { lastSettings = it }
        val model = SessionModel(
            DemoProvider(clock), NoopAlarmScheduler(), directory, FakeLocationSource(), clock,
            DemoPermissions(), settings, scope
        )
        scenario.apply(model, settings, directory, clock.now())
        return model
    }

    private fun scenario(name: Scenario, block: suspend (SessionModel) -> Unit) = runBlocking<Unit> {
        val model = run(this, name)
        block(model)
        if (model.phase == SessionPhase.Tracking || model.phase == SessionPhase.Ringing) model.imOff()
    }

    @Test fun theIntentExtraNamesAScenario() {
        assertEquals(Scenario.TRACKING_LATE, Scenario.from("tracking-late"))
        assertEquals(Scenario.FEED_LOST, Scenario.from("feed-lost"))
        assertNull(Scenario.from("nonsense"))
        assertNull(Scenario.from(null))
    }

    @Test fun everyScenarioNameRoundTrips() {
        for (s in Scenario.values()) assertEquals(s, Scenario.from(s.argument))
        assertEquals(12, Scenario.values().size)
    }

    @Test fun demoBoardHasThreeTrainsOneCancelledAndOnlyFromKingsCross() = runBlocking<Unit> {
        val provider = DemoProvider(FakeClock(start))
        val kgx = Station("KGX", "London Kings Cross", 0.0, 0.0)
        val edb = Station("EDB", "Edinburgh", 0.0, 0.0)
        val board = provider.departureBoard(kgx, start, edb)
        assertEquals(3, board.size)
        assertTrue(board[2].journey.stops[0].isCancelled)
        assertEquals(emptyList<Any>(), provider.departureBoard(edb, start, kgx))
        assertEquals(5, provider.serviceDetails("demo-1", LocalDate.of(2026, 7, 1)).journey.stops.size)
    }

    @Test fun homeAndSettingsLeaveTheModelAlone() {
        for (s in listOf(Scenario.HOME, Scenario.SETTINGS)) scenario(s) { model ->
            assertEquals(SessionPhase.Selecting, model.phase)
            assertNull(model.from)
        }
        assertTrue(Scenario.SETTINGS.opensSettings)
        assertFalse(Scenario.HOME.opensSettings)
    }

    @Test fun boardAndConfirmScenarios() {
        scenario(Scenario.BOARD) { model -> assertEquals(3, (model.board as BoardState.Loaded).services.size) }
        scenario(Scenario.CONFIRM) { model ->
            assertEquals(SessionPhase.Confirming, model.phase)
            assertEquals(7, model.confirm?.leadMinutes)
            assertNotNull(model.alarmAt)
        }
    }

    @Test fun theConfirmScenarioUsesAnOddLeadTimeAndTheTrackingScenariosKeepFifteen() {
        scenario(Scenario.CONFIRM) { model -> assertEquals(7, model.confirm?.leadMinutes) }
        scenario(Scenario.TRACKING) { model -> assertEquals(15, model.confirm?.leadMinutes) }
    }

    @Test fun theSettingsScenarioSetsTheDefaultLeadTimeToSeven() {
        scenario(Scenario.SETTINGS) { _ -> assertEquals(7, lastSettings!!.defaultLeadMinutes.value) }
    }

    @Test fun trackingScenariosShowTheirStates() {
        scenario(Scenario.TRACKING) { assertEquals(SessionPhase.Tracking, it.phase); assertEquals(0, it.display.delayMinutes) }
        scenario(Scenario.TRACKING_LATE) { assertEquals(4, it.display.delayMinutes) }
        scenario(Scenario.CANCELLED) { assertTrue(it.display.cancelled) }
        scenario(Scenario.DROPPED) { assertTrue(it.display.destinationDropped) }
        scenario(Scenario.FEED_LOST) { assertTrue(it.display.feedLost); assertNotNull(it.display.lastGoodAt) }
        scenario(Scenario.ALARM_NOT_SET) { assertTrue(it.display.alarmNotSet) }
    }

    @Test fun ringingAndEndedScenarios() {
        scenario(Scenario.RINGING) { assertEquals(SessionPhase.Ringing, it.phase) }
        scenario(Scenario.ENDED) { assertEquals(SessionPhase.Ended(EndReason.ARRIVED), it.phase) }
    }

    @Test fun demoClockRunsFasterThanTheWallClock() = runBlocking<Unit> {
        val clock = DemoClock(60.0)
        val before = clock.now()
        delay(100)
        assertTrue(Duration.between(before, clock.now()).seconds >= 3)   // 0.1 s of wall time is 6 s of demo time
    }

    @Test fun demoClockSleepIsShortenedBySpeed() = runBlocking<Unit> {
        val start = System.nanoTime()
        DemoClock(60.0).sleep(6.0)   // 0.1 s of wall time
        assertTrue(System.nanoTime() - start < 1_000_000_000L)
    }

    @Test fun demoAlarmRingsWhenTheClockReachesTheFireTime() = runBlocking<Unit> {
        val clock = FakeClock(Instant.parse("2026-11-12T08:00:00Z"))
        var rang = 0
        val scheduler = DemoAlarmScheduler(clock, this) { rang += 1 }
        scheduler.schedule(clock.now().plusSeconds(30))
        clock.awaitSleepCount(1)
        assertEquals(0, rang)
        clock.tick(30.0)
        withTimeout(2_000) { while (rang == 0) yield() }
        assertEquals(1, rang)
    }

    @Test fun cancellingTheDemoAlarmStopsItRinging() = runBlocking<Unit> {
        val clock = FakeClock(Instant.parse("2026-11-12T08:00:00Z"))
        var rang = 0
        val scheduler = DemoAlarmScheduler(clock, this) { rang += 1 }
        scheduler.schedule(clock.now().plusSeconds(30))
        clock.awaitSleepCount(1)
        scheduler.cancel()
        clock.tick(30.0)
        repeat(20) { yield() }
        assertEquals(0, rang)
    }

    @Test fun demoTimesDoNotMoveWhenTheClockDoes() = runBlocking<Unit> {
        val clock = FakeClock(Instant.parse("2026-11-12T08:00:00Z"))
        val provider = DemoProvider(clock)
        val first = provider.serviceDetails("demo-1", LocalDate.of(2026, 11, 12))
        clock.tick(120.0)
        val second = provider.serviceDetails("demo-1", LocalDate.of(2026, 11, 12))
        assertEquals(first.journey.stops.map { it.scheduledDeparture }, second.journey.stops.map { it.scheduledDeparture })
    }

    @Test fun anUnderwayDemoTrainShowsTheRouteStripPartWayAlong() = runBlocking<Unit> {
        val clock = FakeClock(Instant.parse("2026-11-12T08:00:00Z"))
        val details = DemoProvider(clock, underway = true).serviceDetails("demo-1", LocalDate.of(2026, 11, 12))
        val progress = com.trainalarm.app.RouteProgress.make(details.journey.stops, "KGX", "EDB", null, clock.now())!!
        assertTrue(progress.fraction > 0.3 && progress.fraction < 0.5)
        assertTrue(Scenario.TRACKING.trainIsUnderway)
        assertFalse(Scenario.CONFIRM.trainIsUnderway)
    }
}
