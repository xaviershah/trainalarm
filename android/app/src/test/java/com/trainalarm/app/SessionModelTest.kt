package com.trainalarm.app

import com.trainalarm.app.model.Service
import com.trainalarm.app.model.Station
import com.trainalarm.app.provider.ProviderError
import com.trainalarm.app.provider.StationDirectory
import com.trainalarm.app.provider.TrainDataProvider
import com.trainalarm.app.tracking.EtaSource
import com.trainalarm.app.tracking.FailureKind
import com.trainalarm.app.tracking.TrackerEvent
import com.trainalarm.app.tracking.FakeAlarmScheduler
import com.trainalarm.app.tracking.FakeClock
import com.trainalarm.app.tracking.FakeLocationSource
import com.trainalarm.app.tracking.ServiceBuilder
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionModelTest {
    private class ScriptedProvider : TrainDataProvider {
        var board: () -> List<Service> = { listOf(ServiceBuilder.happyPath()) }
        var details: () -> Service = { ServiceBuilder.happyPath() }
        var boardCalls = 0
        var lastCallingAt: Station? = null
        override suspend fun searchStations(query: String): List<Station> = emptyList()
        override suspend fun departureBoard(station: Station, from: Instant, callingAt: Station?): List<Service> {
            boardCalls += 1
            lastCallingAt = callingAt
            return board()
        }
        override suspend fun serviceDetails(serviceId: String, date: LocalDate): Service = details()
    }

    private class FakePermissions : PermissionsGateway {
        override var requiredKinds = listOf(PermissionKind.ALARM)
        override var optionalKinds = listOf(PermissionKind.LOCATION)
        val statuses = mutableMapOf(PermissionKind.ALARM to PermissionStatus.GRANTED, PermissionKind.LOCATION to PermissionStatus.GRANTED)
        val answers = mutableMapOf<PermissionKind, PermissionStatus>()
        val requested = mutableListOf<PermissionKind>()
        override fun status(kind: PermissionKind) = statuses[kind] ?: PermissionStatus.GRANTED
        override suspend fun request(kind: PermissionKind): PermissionStatus {
            kotlinx.coroutines.yield()   // a real prompt suspends; let a second caller run meanwhile
            requested += kind
            return answers[kind] ?: status(kind)
        }
    }

    private class MemoryKeyValues : KeyValues {
        val map = mutableMapOf<String, Any>()
        override fun getString(key: String) = map[key] as? String
        override fun putString(key: String, value: String) { map[key] = value }
        override fun getInt(key: String) = map[key] as? Int
        override fun putInt(key: String, value: Int) { map[key] = value }
    }

    private class Harness(scope: CoroutineScope, now: String) {
        val directory = StationDirectory.parse(
            """[{"crs":"WAT","name":"London Waterloo","lat":51.5031,"lon":-0.1132},{"crs":"WOK","name":"Woking","lat":51.3189,"lon":-0.5569},{"crs":"BSK","name":"Basingstoke","lat":51.2686,"lon":-1.0872}]"""
        )
        val provider = ScriptedProvider()
        val scheduler = FakeAlarmScheduler()
        val clock = FakeClock(ServiceBuilder.time(now))
        val permissions = FakePermissions()
        val settings = SettingsStore(MemoryKeyValues())
        val model = SessionModel(provider, scheduler, directory, FakeLocationSource(), clock, permissions, settings, scope)
        val wat = directory.station("WAT")!!
        val bsk = directory.station("BSK")!!
    }

    private fun run(now: String = "07:00:00", block: suspend Harness.() -> Unit) = runBlocking<Unit> { Harness(this, now).block() }

    /** Picks WAT to BSK, loads the board, and chooses the first train. */
    private suspend fun Harness.confirmed(lead: Int? = 15) {
        model.selectFrom(wat)
        model.selectTo(bsk)
        model.loadBoard()
        val loaded = model.board as BoardState.Loaded
        model.choose(loaded.services[0])
        if (lead != null) model.setLead(lead)
    }

    @Test fun search_usesTheDirectory() = run { assertEquals(listOf("WOK"), model.search("woki").map { it.id }) }

    @Test fun theBoardNeedsTwoDifferentStations() = run {
        model.selectFrom(wat)
        assertFalse(model.canLoadBoard)
        model.selectTo(wat)
        assertFalse(model.canLoadBoard)
        model.loadBoard()
        assertEquals(BoardState.Idle, model.board)
        assertEquals(0, provider.boardCalls)
    }

    @Test fun theBoardIsFilteredToTrainsCallingAtTheDestination() = run {
        model.selectFrom(wat)
        model.selectTo(bsk)
        model.loadBoard()
        assertEquals("BSK", provider.lastCallingAt?.id)
        assertEquals(1, (model.board as BoardState.Loaded).services.size)
    }

    @Test fun anEmptyBoardIsEmptyNotAnError() = run {
        provider.board = { emptyList() }
        model.selectFrom(wat)
        model.selectTo(bsk)
        model.loadBoard()
        assertEquals(BoardState.Empty, model.board)
    }

    @Test fun http429ShowsTheRateLimitedState() = run {
        provider.board = { throw ProviderError.Http(429) }
        model.selectFrom(wat)
        model.selectTo(bsk)
        model.loadBoard()
        assertEquals(BoardState.RateLimited, model.board)
    }

    @Test fun anotherErrorShowsAMessage() = run {
        provider.board = { throw ProviderError.Network("offline") }
        model.selectFrom(wat)
        model.selectTo(bsk)
        model.loadBoard()
        assertEquals(BoardState.Failed("Can't reach the train data. Check your connection and try again."), model.board)
    }

    @Test fun refreshingTheBoardTooSoonIsIgnoredUnlessForced() = run {
        model.selectFrom(wat)
        model.selectTo(bsk)
        model.loadBoard()
        model.loadBoard()
        assertEquals(1, provider.boardCalls)
        clock.tick(21.0)
        model.loadBoard()
        assertEquals(2, provider.boardCalls)
        model.loadBoard(force = true)
        assertEquals(3, provider.boardCalls)
    }

    @Test fun choosingATrainFindsTheArrivalAtTheDestination() = run {
        confirmed(lead = null)
        assertEquals(SessionPhase.Confirming, model.phase)
        assertEquals(ServiceBuilder.time("08:52:00"), model.confirm?.arrival)
    }

    @Test fun aTrainThatDoesNotCallAtTheDestinationCannotStart() = run {
        provider.details = { ServiceBuilder.service(listOf(ServiceBuilder.stop("WAT"), ServiceBuilder.stop("WOK", scheduledArrival = "08:23:00"))) }
        confirmed()
        assertNull(model.confirm?.arrival)
        assertFalse(model.canStart)
    }

    @Test fun firstRunHasNoLeadTimeAndCannotStartUntilOneIsChosen() = run {
        confirmed(lead = null)
        assertNull(model.confirm?.leadMinutes)
        assertFalse(model.canStart)
        model.setLead(15)
        assertTrue(model.canStart)
        assertEquals(ServiceBuilder.time("08:37:00"), model.alarmAt)
    }

    @Test fun everyMinuteFromOneToThirtyIsAcceptedAndMovesTheAlarmTime() = run {
        confirmed(lead = null)
        model.setLead(1)
        assertEquals(ServiceBuilder.time("08:51:00"), model.alarmAt)
        model.setLead(7)
        assertEquals(ServiceBuilder.time("08:45:00"), model.alarmAt)
        model.setLead(30)
        assertEquals(ServiceBuilder.time("08:22:00"), model.alarmAt)
    }

    @Test fun aLeadTimeOutsideOneToThirtyIsIgnored() = run {
        confirmed(lead = 10)
        model.setLead(0)
        assertEquals(10, model.confirm?.leadMinutes)
        model.setLead(31)
        assertEquals(10, model.confirm?.leadMinutes)
    }

    @Test fun aSavedDefaultLeadTimePrefillsTheChoice() = run {
        settings.setDefaultLeadMinutes(10)
        confirmed(lead = null)
        assertEquals(10, model.confirm?.leadMinutes)
    }

    @Test fun anArrivalSoonerThanTheLeadTimeRingsStraightAway() = run(now = "08:50:00") {
        confirmed(lead = 10)
        assertTrue(model.ringsStraightAway)
    }

    @Test fun aRefusedAlarmPermissionBlocksStart() = run {
        permissions.statuses[PermissionKind.ALARM] = PermissionStatus.DENIED
        confirmed()
        model.start()
        assertEquals(SessionPhase.Confirming, model.phase)
        assertEquals(PermissionKind.ALARM, model.blockedPermission)
        assertEquals(emptyList<Instant>(), scheduler.attempts)
        assertEquals(emptyList<PermissionKind>(), permissions.requested)
    }

    @Test fun aRefusedLocationPermissionIsAWarningNotABlock() = run {
        permissions.statuses[PermissionKind.LOCATION] = PermissionStatus.NOT_DETERMINED
        permissions.answers[PermissionKind.LOCATION] = PermissionStatus.DENIED
        confirmed()
        model.start()
        assertEquals(SessionPhase.Tracking, model.phase)
        assertEquals(listOf(PermissionKind.LOCATION), model.permissionWarnings)
        model.imOff()
    }

    @Test fun startSchedulesTheTimetableAlarmAndSavesTheLeadTime() = run {
        confirmed(lead = 15)
        model.start()
        clock.awaitSleepCount(1)
        assertEquals(SessionPhase.Tracking, model.phase)
        assertEquals(ServiceBuilder.time("08:37:00"), scheduler.scheduled.first())
        assertEquals(15, settings.defaultLeadMinutes.value)
        assertEquals(60.0, model.trackerConfig?.pollInterval)
        assertEquals(900.0, model.trackerConfig?.leadTime)
        model.imOff()
    }

    @Test fun aSecondStartWhileAPermissionPromptIsOpenDoesNothing() = run {
        permissions.statuses[PermissionKind.ALARM] = PermissionStatus.NOT_DETERMINED
        permissions.answers[PermissionKind.ALARM] = PermissionStatus.GRANTED
        confirmed(lead = 15)
        coroutineScope {
            launch { model.start() }
            launch { model.start() }
        }
        clock.awaitSleepCount(1)
        assertEquals(1, scheduler.attempts.size)
        model.imOff()
    }

    @Test fun imOffStopsTheTrackerCancelsTheAlarmAndEnds() = run {
        confirmed()
        model.start()
        clock.awaitSleepCount(1)
        model.imOff()
        assertEquals(SessionPhase.Ended(EndReason.IM_OFF), model.phase)
        assertEquals(1, scheduler.cancelCount)
        model.returnHome()
        assertEquals(SessionPhase.Selecting, model.phase)
        assertEquals(BoardState.Idle, model.board)
    }

    // Stale results (generation guards)

    private class GatedProvider : TrainDataProvider {
        val boardWaiters = mutableListOf<CompletableDeferred<List<Service>>>()
        val detailWaiters = mutableListOf<CompletableDeferred<Service>>()
        override suspend fun searchStations(query: String): List<Station> = emptyList()
        override suspend fun departureBoard(station: Station, from: Instant, callingAt: Station?): List<Service> {
            val d = CompletableDeferred<List<Service>>()
            boardWaiters += d
            return d.await()
        }
        override suspend fun serviceDetails(serviceId: String, date: LocalDate): Service {
            val d = CompletableDeferred<Service>()
            detailWaiters += d
            return d.await()
        }
        suspend fun waitForBoardCalls(n: Int) { while (boardWaiters.size < n) yield() }
        suspend fun waitForDetailCalls(n: Int) { while (detailWaiters.size < n) yield() }
    }

    private class GatedHarness(scope: CoroutineScope) {
        val directory = StationDirectory.parse(
            """[{"crs":"WAT","name":"London Waterloo","lat":51.5031,"lon":-0.1132},{"crs":"WOK","name":"Woking","lat":51.3189,"lon":-0.5569},{"crs":"BSK","name":"Basingstoke","lat":51.2686,"lon":-1.0872}]"""
        )
        val provider = GatedProvider()
        val model = SessionModel(provider, FakeAlarmScheduler(), directory, FakeLocationSource(), FakeClock(ServiceBuilder.time("07:00:00")),
            FakePermissions(), SettingsStore(MemoryKeyValues()), scope)
        val wat = directory.station("WAT")!!
        val bsk = directory.station("BSK")!!
        val wok = directory.station("WOK")!!
    }

    private fun gated(block: suspend CoroutineScope.(GatedHarness) -> Unit) = runBlocking<Unit> { block(GatedHarness(this)) }

    @Test fun aBoardResponseAfterTheStationChangedIsDiscarded() = gated { h ->
        h.model.selectFrom(h.wat)
        h.model.selectTo(h.bsk)
        val load = launch { h.model.loadBoard() }
        h.provider.waitForBoardCalls(1)
        h.model.selectTo(h.wok)
        h.provider.boardWaiters[0].complete(listOf(ServiceBuilder.happyPath()))
        load.join()
        assertEquals(BoardState.Idle, h.model.board)
    }

    @Test fun theNewerOfTwoOutOfOrderBoardResponsesWins() = gated { h ->
        h.model.selectFrom(h.wat)
        h.model.selectTo(h.bsk)
        val first = launch { h.model.loadBoard() }
        h.provider.waitForBoardCalls(1)
        val second = launch { h.model.loadBoard(force = true) }
        h.provider.waitForBoardCalls(2)
        h.provider.boardWaiters[1].complete(emptyList())
        second.join()
        h.provider.boardWaiters[0].complete(listOf(ServiceBuilder.happyPath()))
        first.join()
        assertEquals(BoardState.Empty, h.model.board)
    }

    private fun gatedChoose(change: (SessionModel, GatedHarness) -> Unit) = gated { h ->
        h.model.selectFrom(h.wat)
        h.model.selectTo(h.bsk)
        val load = launch { h.model.loadBoard() }
        h.provider.waitForBoardCalls(1)
        h.provider.boardWaiters[0].complete(listOf(ServiceBuilder.happyPath()))
        load.join()
        val services = (h.model.board as BoardState.Loaded).services
        val choose = launch { h.model.choose(services[0]) }
        h.provider.waitForDetailCalls(1)
        change(h.model, h)
        h.provider.detailWaiters[0].complete(ServiceBuilder.happyPath())
        choose.join()
        assertEquals(SessionPhase.Selecting, h.model.phase)
        assertNull(h.model.confirm)
    }

    @Test fun aChooseResultAfterGoingBackIsDiscarded() = gatedChoose { m, _ -> m.backToSelecting() }

    @Test fun aChooseResultAfterTheStationChangedIsDiscarded() = gatedChoose { m, h -> m.selectTo(h.wok) }

    // Tracker events

    private fun t(hhmmss: String) = ServiceBuilder.time(hhmmss)

    private suspend fun Harness.tracking() {
        confirmed(lead = 15)
        model.start()
        clock.awaitSleepCount(1)
    }

    private suspend fun waitUntil(condition: () -> Boolean) = withTimeout(2_000) { while (!condition()) yield() }

    @Test fun startShowsTheInitialDisplay() = run {
        tracking()
        assertEquals(t("08:52:00"), model.display.eta)
        assertEquals(t("08:47:00"), model.display.scheduledArrival)
        assertEquals(5, model.display.delayMinutes)
        assertEquals(t("08:37:00"), model.display.alarmAt)
        model.imOff()
    }

    @Test fun etaChangedUpdatesArrivalDelayAndAlarm() = run {
        tracking()
        model.handle(TrackerEvent.EtaChanged(t("08:52:00"), t("08:57:00"), t("08:42:00")))
        assertEquals(t("08:57:00"), model.display.eta)
        assertEquals(10, model.display.delayMinutes)
        assertEquals(t("08:42:00"), model.display.alarmAt)
        assertFalse(model.display.isEstimated)
        model.imOff()
    }

    @Test fun etaEstimatedIsLabelledAsAnEstimate() = run {
        tracking()
        model.handle(TrackerEvent.EtaEstimated(t("08:55:00"), EtaSource.GPS_PACE, 120.0, true))
        assertEquals(t("08:55:00"), model.display.eta)
        assertTrue(model.display.isEstimated)
        assertEquals(EtaSource.GPS_PACE, model.display.estimateSource)
        model.imOff()
    }

    @Test fun cancelledAndDroppedShowCardsAndKeepTrackingAndTheAlarm() = run {
        tracking()
        model.handle(TrackerEvent.ServiceCancelled(t("08:10:00")))
        model.handle(TrackerEvent.DestinationLost(t("08:11:00")))
        assertTrue(model.display.cancelled)
        assertTrue(model.display.destinationDropped)
        assertEquals(SessionPhase.Tracking, model.phase)
        assertEquals(0, scheduler.cancelCount)
        model.imOff()
    }

    @Test fun destinationRestoredClearsTheCards() = run {
        tracking()
        model.handle(TrackerEvent.DestinationLost(t("08:11:00")))
        model.handle(TrackerEvent.ServiceCancelled(t("08:12:00")))
        model.handle(TrackerEvent.DestinationRestored(t("08:53:00")))
        assertFalse(model.display.cancelled)
        assertFalse(model.display.destinationDropped)
        assertEquals(t("08:53:00"), model.display.eta)
        model.imOff()
    }

    @Test fun feedLostShowsHealthAndRecoveredClearsIt() = run {
        tracking()
        model.handle(TrackerEvent.FeedLost(t("08:00:00"), FailureKind.Network))
        assertTrue(model.display.feedLost)
        assertEquals(t("08:00:00"), model.display.lastGoodAt)
        assertFalse(model.display.rateLimited)
        model.handle(TrackerEvent.FeedRecovered(300.0, t("08:56:00"), null))
        assertFalse(model.display.feedLost)
        assertEquals(t("08:56:00"), model.display.eta)
        model.imOff()
    }

    @Test fun aFeedLossFromHttp429ShowsRateLimited() = run {
        tracking()
        model.handle(TrackerEvent.FeedLost(null, FailureKind.Http(429)))
        assertTrue(model.display.rateLimited)
        model.handle(TrackerEvent.FeedRecovered(60.0, null, null))
        assertFalse(model.display.rateLimited)
        model.imOff()
    }

    @Test fun alarmSchedulingFailedShowsAlarmNotSetUntilAnEtaChangeSucceeds() = run {
        tracking()
        model.handle(TrackerEvent.AlarmSchedulingFailed(t("08:37:00"), "refused"))
        assertTrue(model.display.alarmNotSet)
        model.handle(TrackerEvent.EtaChanged(t("08:52:00"), t("08:53:00"), t("08:38:00")))
        assertFalse(model.display.alarmNotSet)
        model.imOff()
    }

    @Test fun aRetryThatSucceedsClearsAlarmNotSetWithoutAnEtaChange() = run {
        scheduler.failuresRemaining = 1
        tracking()
        waitUntil { model.display.alarmNotSet }
        clock.tick(60.0)
        waitUntil { !model.display.alarmNotSet }
        assertEquals(1, scheduler.scheduled.size)
        model.imOff()
    }

    @Test fun repeatedEventsDoNotToggleState() = run {
        tracking()
        model.handle(TrackerEvent.FeedLost(t("08:00:00"), FailureKind.Network))
        val once = model.display
        model.handle(TrackerEvent.FeedLost(t("08:00:00"), FailureKind.Network))
        assertEquals(once, model.display)
        model.handle(TrackerEvent.DestinationLost(t("08:11:00")))
        model.handle(TrackerEvent.DestinationRestored(null))
        model.handle(TrackerEvent.DestinationLost(t("08:20:00")))
        assertTrue(model.display.destinationDropped)
        model.imOff()
    }

    @Test fun arrivedStopsTheTrackerAndEndsTheSession() = run {
        tracking()
        model.handle(TrackerEvent.Arrived(t("08:52:00")))
        assertEquals(SessionPhase.Ended(EndReason.ARRIVED), model.phase)
        assertEquals(1, scheduler.cancelCount)
    }

    @Test fun eventsAfterTheSessionEndedAreIgnored() = run {
        tracking()
        model.imOff()
        val before = model.display
        model.handle(TrackerEvent.FeedLost(null, FailureKind.Network))
        assertEquals(before, model.display)
        assertEquals(SessionPhase.Ended(EndReason.IM_OFF), model.phase)
    }

    @Test fun theAlarmRingingMovesToRingingAndImOffStillEndsTheSession() = run {
        model.alarmRang()
        assertEquals(SessionPhase.Selecting, model.phase)
        tracking()
        model.alarmRang()
        assertEquals(SessionPhase.Ringing, model.phase)
        model.imOff()
        assertEquals(SessionPhase.Ended(EndReason.IM_OFF), model.phase)
    }

    @Test fun trackerDelaysFlowThroughToTheDisplay() = run {
        tracking()
        provider.details = { ServiceBuilder.happyPath(destinationEstimate = "08:57:00") }
        clock.tick(60.0)
        waitUntil { model.display.eta == t("08:57:00") }
        assertEquals(10, model.display.delayMinutes)
        model.imOff()
    }

    // Event collector lifetime

    @Test fun aLateTrackerEventAfterImOffDoesNotChangeTheDisplay() = run {
        tracking()
        model.imOff()
        val before = model.display
        provider.details = { ServiceBuilder.happyPath(destinationEstimate = "08:57:00") }
        clock.tick(60.0)
        repeat(50) { yield() }
        assertEquals(before, model.display)
        assertEquals(SessionPhase.Ended(EndReason.IM_OFF), model.phase)
    }

    @Test fun aSecondSessionIsNotAffectedByEventsBufferedFromTheFirst() = run {
        tracking()
        provider.details = { ServiceBuilder.happyPath(destinationEstimate = "08:57:00") }
        clock.tick(60.0)   // the first tracker's delay event is now queued for the collector, which has not run yet
        model.imOff()
        model.returnHome()
        provider.details = { ServiceBuilder.happyPath() }
        confirmed(lead = 15)
        model.start()
        repeat(50) { yield() }
        assertEquals(t("08:52:00"), model.display.eta)
        assertEquals(SessionPhase.Tracking, model.phase)
        model.imOff()
    }

    @Test fun arrivalFromTheEventStreamStillFinishesTheEndOfSessionWork() = runBlocking<Unit> {
        val h = Harness(this, "07:00:00")
        var stopped = 0
        val control = object : LocationControl {
            override fun start(): Boolean = true
            override fun stop() { stopped += 1 }
        }
        val model = SessionModel(h.provider, h.scheduler, h.directory, FakeLocationSource(), h.clock, h.permissions, h.settings, this, locationControl = control)
        model.selectFrom(h.wat)
        model.selectTo(h.bsk)
        model.loadBoard()
        model.choose((model.board as BoardState.Loaded).services[0])
        model.setLead(15)
        model.start()
        h.clock.awaitSleepCount(1)
        h.clock.tick(3 * 3600.0)   // 10:00, past the 08:52 arrival plus the grace period
        waitUntil { model.phase == SessionPhase.Ended(EndReason.ARRIVED) }
        assertEquals(1, stopped)
        assertTrue(h.scheduler.cancelCount >= 1)   // the tracker cancels on arrival, and the model stops it too
    }

    // Double events

    @Test fun feedRecoveredWithNoPriorLossChangesNothing() = run {
        tracking()
        val before = model.display
        model.handle(TrackerEvent.FeedRecovered(0.0, null, null))
        assertEquals(before, model.display)
        assertFalse(model.display.feedLost)
        assertFalse(model.display.rateLimited)
        assertEquals(SessionPhase.Tracking, model.phase)
        model.imOff()
    }

    @Test fun ringingDeliveredTwiceStaysRinging() = run {
        tracking()
        val before = model.display
        model.alarmRang()
        model.alarmRang()
        assertEquals(SessionPhase.Ringing, model.phase)
        assertEquals(before, model.display)
        model.imOff()
        assertEquals(SessionPhase.Ended(EndReason.IM_OFF), model.phase)
    }

    @Test fun arrivedTwiceEndsOnceAndCancelsTheAlarmOnce() = run {
        tracking()
        model.handle(TrackerEvent.Arrived(t("08:52:00")))
        model.handle(TrackerEvent.Arrived(t("08:52:00")))
        assertEquals(SessionPhase.Ended(EndReason.ARRIVED), model.phase)
        assertEquals(1, scheduler.cancelCount)
    }

    @Test fun anyEventAfterArrivedIsIgnored() = run {
        tracking()
        model.handle(TrackerEvent.Arrived(t("08:52:00")))
        val before = model.display
        model.handle(TrackerEvent.FeedLost(null, FailureKind.Http(429)))
        model.handle(TrackerEvent.EtaChanged(t("08:52:00"), t("09:10:00"), t("08:55:00")))
        model.handle(TrackerEvent.ServiceCancelled(t("08:53:00")))
        model.alarmRang()
        assertEquals(before, model.display)
        assertEquals(SessionPhase.Ended(EndReason.ARRIVED), model.phase)
    }

    @Test fun aNon429FeedLossAfterA429ClearsRateLimitedAndKeepsFeedLost() = run {
        tracking()
        model.handle(TrackerEvent.FeedLost(t("08:00:00"), FailureKind.Http(429)))
        assertTrue(model.display.rateLimited)
        model.handle(TrackerEvent.FeedLost(t("08:01:00"), FailureKind.Network))
        assertFalse(model.display.rateLimited)
        assertTrue(model.display.feedLost)
        assertEquals(t("08:01:00"), model.display.lastGoodAt)
        model.imOff()
    }
}
