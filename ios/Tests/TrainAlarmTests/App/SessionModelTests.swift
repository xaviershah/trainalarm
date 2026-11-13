import XCTest
@testable import TrainAlarm

@MainActor
final class SessionModelTests: XCTestCase {
    final class ScriptedProvider: TrainDataProvider {
        var board: Result<[Service], Error> = .success([])
        var details: Result<Service, Error> = .success(ServiceBuilder.happyPath())
        private(set) var boardCalls = 0
        private(set) var lastCallingAt: Station?
        func searchStations(query: String) async throws -> [Station] { [] }
        func departureBoard(station: Station, from: Date, callingAt: Station?) async throws -> [Service] {
            boardCalls += 1
            lastCallingAt = callingAt
            return try board.get()
        }
        func serviceDetails(serviceId: String, date: Date) async throws -> Service { try details.get() }
    }

    final class FakePermissions: PermissionsGateway {
        var requiredKinds: [PermissionKind] = [.alarm]
        var optionalKinds: [PermissionKind] = [.location]
        var statuses: [PermissionKind: PermissionStatus] = [.alarm: .granted, .location: .granted]
        var answers: [PermissionKind: PermissionStatus] = [:]
        private(set) var requested: [PermissionKind] = []
        func status(_ kind: PermissionKind) -> PermissionStatus { statuses[kind] ?? .granted }
        func request(_ kind: PermissionKind) async -> PermissionStatus {
            requested.append(kind)
            return answers[kind] ?? status(kind)
        }
    }

    struct Harness {
        let model: SessionModel
        let provider: ScriptedProvider
        let scheduler: FakeAlarmScheduler
        let clock: FakeClock
        let permissions: FakePermissions
        let settings: SettingsStore
        let wat: Station
        let bsk: Station
    }

    private let directoryJSON = #"[{"crs":"WAT","name":"London Waterloo","lat":51.5031,"lon":-0.1132},{"crs":"WOK","name":"Woking","lat":51.3189,"lon":-0.5569},{"crs":"BSK","name":"Basingstoke","lat":51.2686,"lon":-1.0872}]"#
    private var suite = ""

    override func setUp() { suite = "session-test-\(UUID().uuidString)" }
    override func tearDown() { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }

    private func makeHarness(now: String = "07:00:00") throws -> Harness {
        let directory = try StationDirectory.parse(Data(directoryJSON.utf8))
        let provider = ScriptedProvider()
        provider.board = .success([ServiceBuilder.happyPath()])
        let scheduler = FakeAlarmScheduler()
        let clock = FakeClock(start: ServiceBuilder.time(now))
        let permissions = FakePermissions()
        let settings = SettingsStore(defaults: UserDefaults(suiteName: suite)!)
        let model = SessionModel(provider: provider, scheduler: scheduler, directory: directory,
                                 location: FakeLocationSource(), clock: clock, permissions: permissions, settings: settings)
        return Harness(model: model, provider: provider, scheduler: scheduler, clock: clock, permissions: permissions,
                       settings: settings, wat: directory.station(crs: "WAT")!, bsk: directory.station(crs: "BSK")!)
    }

    /// Picks WAT to BSK, loads the board, and chooses the first train.
    private func confirmed(_ h: Harness, lead: Int? = 15) async {
        h.model.selectFrom(h.wat)
        h.model.selectTo(h.bsk)
        await h.model.loadBoard()
        guard case let .loaded(services) = h.model.board else { return XCTFail("board not loaded") }
        await h.model.choose(services[0])
        if let lead { h.model.setLead(lead) }
    }

    func testSearchUsesTheDirectory() throws {
        XCTAssertEqual(try makeHarness().model.search("woki").map(\.id), ["WOK"])
    }

    func testTheBoardNeedsTwoDifferentStations() async throws {
        let h = try makeHarness()
        h.model.selectFrom(h.wat)
        XCTAssertFalse(h.model.canLoadBoard)
        h.model.selectTo(h.wat)
        XCTAssertFalse(h.model.canLoadBoard)
        await h.model.loadBoard()
        XCTAssertEqual(h.model.board, .idle)
        XCTAssertEqual(h.provider.boardCalls, 0)
    }

    func testTheBoardIsFilteredToTrainsCallingAtTheDestination() async throws {
        let h = try makeHarness()
        h.model.selectFrom(h.wat)
        h.model.selectTo(h.bsk)
        await h.model.loadBoard()
        XCTAssertEqual(h.provider.lastCallingAt?.id, "BSK")
        if case .loaded(let services) = h.model.board { XCTAssertEqual(services.count, 1) } else { XCTFail("not loaded") }
    }

    func testAnEmptyBoardIsEmptyNotAnError() async throws {
        let h = try makeHarness()
        h.provider.board = .success([])
        h.model.selectFrom(h.wat)
        h.model.selectTo(h.bsk)
        await h.model.loadBoard()
        XCTAssertEqual(h.model.board, .empty)
    }

    func testHttp429ShowsTheRateLimitedState() async throws {
        let h = try makeHarness()
        h.provider.board = .failure(ProviderError.http(429))
        h.model.selectFrom(h.wat)
        h.model.selectTo(h.bsk)
        await h.model.loadBoard()
        XCTAssertEqual(h.model.board, .rateLimited)
    }

    func testAnotherErrorShowsAMessage() async throws {
        let h = try makeHarness()
        h.provider.board = .failure(ProviderError.network("offline"))
        h.model.selectFrom(h.wat)
        h.model.selectTo(h.bsk)
        await h.model.loadBoard()
        XCTAssertEqual(h.model.board, .failed("Can't reach the train data. Check your connection and try again."))
    }

    func testRefreshingTheBoardTooSoonIsIgnoredUnlessForced() async throws {
        let h = try makeHarness()
        h.model.selectFrom(h.wat)
        h.model.selectTo(h.bsk)
        await h.model.loadBoard()
        await h.model.loadBoard()
        XCTAssertEqual(h.provider.boardCalls, 1)
        h.clock.tick(advancing: 21)
        await h.model.loadBoard()
        XCTAssertEqual(h.provider.boardCalls, 2)
        await h.model.loadBoard(force: true)
        XCTAssertEqual(h.provider.boardCalls, 3)
    }

    func testChoosingATrainFindsTheArrivalAtTheDestination() async throws {
        let h = try makeHarness()
        await confirmed(h, lead: nil)
        XCTAssertEqual(h.model.phase, .confirming)
        XCTAssertEqual(h.model.confirm?.arrival, ServiceBuilder.time("08:52:00"))
    }

    func testATrainThatDoesNotCallAtTheDestinationCannotStart() async throws {
        let h = try makeHarness()
        h.provider.details = .success(ServiceBuilder.service(stops: [ServiceBuilder.stop("WAT"), ServiceBuilder.stop("WOK", scheduledArrival: "08:23:00")]))
        await confirmed(h)
        XCTAssertNil(h.model.confirm?.arrival)
        XCTAssertFalse(h.model.canStart)
    }

    func testFirstRunHasNoLeadTimeAndCannotStartUntilOneIsChosen() async throws {
        let h = try makeHarness()
        await confirmed(h, lead: nil)
        XCTAssertNil(h.model.confirm?.leadMinutes)
        XCTAssertFalse(h.model.canStart)
        h.model.setLead(15)
        XCTAssertTrue(h.model.canStart)
        XCTAssertEqual(h.model.alarmAt, ServiceBuilder.time("08:37:00"))
    }

    func testEveryMinuteFromOneToThirtyIsAcceptedAndMovesTheAlarmTime() async throws {
        let h = try makeHarness()
        await confirmed(h, lead: nil)
        h.model.setLead(1)
        XCTAssertEqual(h.model.alarmAt, ServiceBuilder.time("08:51:00"))
        h.model.setLead(7)
        XCTAssertEqual(h.model.alarmAt, ServiceBuilder.time("08:45:00"))
        h.model.setLead(30)
        XCTAssertEqual(h.model.alarmAt, ServiceBuilder.time("08:22:00"))
    }

    func testALeadTimeOutsideOneToThirtyIsIgnored() async throws {
        let h = try makeHarness()
        await confirmed(h, lead: 10)
        h.model.setLead(0)
        XCTAssertEqual(h.model.confirm?.leadMinutes, 10)
        h.model.setLead(31)
        XCTAssertEqual(h.model.confirm?.leadMinutes, 10)
    }

    func testASavedDefaultLeadTimePrefillsTheChoice() async throws {
        let h = try makeHarness()
        h.settings.setDefaultLeadMinutes(10)
        await confirmed(h, lead: nil)
        XCTAssertEqual(h.model.confirm?.leadMinutes, 10)
    }

    func testAnArrivalSoonerThanTheLeadTimeRingsStraightAway() async throws {
        let h = try makeHarness(now: "08:50:00")
        await confirmed(h, lead: 10)
        XCTAssertTrue(h.model.ringsStraightAway)
    }

    func testARefusedAlarmPermissionBlocksStart() async throws {
        let h = try makeHarness()
        h.permissions.statuses[.alarm] = .denied
        await confirmed(h)
        await h.model.start()
        XCTAssertEqual(h.model.phase, .confirming)
        XCTAssertEqual(h.model.blockedPermission, .alarm)
        XCTAssertEqual(h.scheduler.attempts, [])
        XCTAssertEqual(h.permissions.requested, [], "a denied permission is never re-prompted automatically")
    }

    func testARefusedLocationPermissionIsAWarningNotABlock() async throws {
        let h = try makeHarness()
        h.permissions.statuses[.location] = .notDetermined
        h.permissions.answers[.location] = .denied
        await confirmed(h)
        await h.model.start()
        XCTAssertEqual(h.model.phase, .tracking)
        XCTAssertEqual(h.model.permissionWarnings, [.location])
        await h.model.imOff()
    }

    func testStartSchedulesTheTimetableAlarmAndSavesTheLeadTime() async throws {
        let h = try makeHarness()
        await confirmed(h, lead: 15)
        await h.model.start()
        await h.clock.waitForSleepCount(1)
        XCTAssertEqual(h.model.phase, .tracking)
        XCTAssertEqual(h.scheduler.scheduled.first, ServiceBuilder.time("08:37:00"))
        XCTAssertEqual(h.settings.defaultLeadMinutes, 15)
        XCTAssertEqual(h.model.trackerConfig?.pollInterval, 60)
        XCTAssertEqual(h.model.trackerConfig?.leadTime, 900)
        await h.model.imOff()
    }

    func testASecondStartWhileAPermissionPromptIsOpenDoesNothing() async throws {
        let h = try makeHarness()
        h.permissions.statuses[.alarm] = .notDetermined
        h.permissions.answers[.alarm] = .granted
        await confirmed(h, lead: 15)
        async let first: Void = h.model.start()
        async let second: Void = h.model.start()
        _ = await (first, second)
        await h.clock.waitForSleepCount(1)
        XCTAssertEqual(h.scheduler.attempts.count, 1)
        await h.model.imOff()
    }

    func testImOffStopsTheTrackerCancelsTheAlarmAndEnds() async throws {
        let h = try makeHarness()
        await confirmed(h)
        await h.model.start()
        await h.clock.waitForSleepCount(1)
        await h.model.imOff()
        XCTAssertEqual(h.model.phase, .ended(.imOff))
        XCTAssertEqual(h.scheduler.cancelCount, 1)
        h.model.returnHome()
        XCTAssertEqual(h.model.phase, .selecting)
        XCTAssertEqual(h.model.board, .idle)
    }

    // MARK: Stale results (generation guards)

    final class GatedProvider: TrainDataProvider {
        var boardWaiters: [CheckedContinuation<[Service], Never>] = []
        var detailWaiters: [CheckedContinuation<Service, Never>] = []
        func searchStations(query: String) async throws -> [Station] { [] }
        func departureBoard(station: Station, from: Date, callingAt: Station?) async throws -> [Service] {
            await withCheckedContinuation { boardWaiters.append($0) }
        }
        func serviceDetails(serviceId: String, date: Date) async throws -> Service {
            await withCheckedContinuation { detailWaiters.append($0) }
        }
        func waitForBoardCalls(_ n: Int) async { while boardWaiters.count < n { await Task.yield() } }
        func waitForDetailCalls(_ n: Int) async { while detailWaiters.count < n { await Task.yield() } }
    }

    private func gatedModel() throws -> (SessionModel, GatedProvider, Station, Station, Station) {
        let directory = try StationDirectory.parse(Data(directoryJSON.utf8))
        let provider = GatedProvider()
        let model = SessionModel(provider: provider, scheduler: FakeAlarmScheduler(), directory: directory,
                                 location: FakeLocationSource(), clock: FakeClock(start: ServiceBuilder.time("07:00:00")),
                                 permissions: FakePermissions(), settings: SettingsStore(defaults: UserDefaults(suiteName: suite)!))
        return (model, provider, directory.station(crs: "WAT")!, directory.station(crs: "BSK")!, directory.station(crs: "WOK")!)
    }

    func testABoardResponseAfterTheStationChangedIsDiscarded() async throws {
        let (model, provider, wat, bsk, wok) = try gatedModel()
        model.selectFrom(wat)
        model.selectTo(bsk)
        let load = Task { await model.loadBoard() }
        await provider.waitForBoardCalls(1)
        model.selectTo(wok)
        provider.boardWaiters[0].resume(returning: [ServiceBuilder.happyPath()])
        await load.value
        XCTAssertEqual(model.board, .idle)
    }

    func testTheNewerOfTwoOutOfOrderBoardResponsesWins() async throws {
        let (model, provider, wat, bsk, _) = try gatedModel()
        model.selectFrom(wat)
        model.selectTo(bsk)
        let first = Task { await model.loadBoard() }
        await provider.waitForBoardCalls(1)
        let second = Task { await model.loadBoard(force: true) }
        await provider.waitForBoardCalls(2)
        provider.boardWaiters[1].resume(returning: [])
        await second.value
        provider.boardWaiters[0].resume(returning: [ServiceBuilder.happyPath()])
        await first.value
        XCTAssertEqual(model.board, .empty)
    }

    private func gatedChoose(_ change: (SessionModel, Station) -> Void) async throws {
        let (model, provider, wat, bsk, wok) = try gatedModel()
        model.selectFrom(wat)
        model.selectTo(bsk)
        let load = Task { await model.loadBoard() }
        await provider.waitForBoardCalls(1)
        provider.boardWaiters[0].resume(returning: [ServiceBuilder.happyPath()])
        await load.value
        guard case let .loaded(services) = model.board else { return XCTFail("board not loaded") }
        let choose = Task { await model.choose(services[0]) }
        await provider.waitForDetailCalls(1)
        change(model, wok)
        provider.detailWaiters[0].resume(returning: ServiceBuilder.happyPath())
        await choose.value
        XCTAssertEqual(model.phase, .selecting)
        XCTAssertNil(model.confirm)
    }

    func testAChooseResultAfterGoingBackIsDiscarded() async throws {
        try await gatedChoose { model, _ in model.backToSelecting() }
    }

    func testAChooseResultAfterTheStationChangedIsDiscarded() async throws {
        try await gatedChoose { model, wok in model.selectTo(wok) }
    }

    // MARK: Tracker events

    private func tracking(_ h: Harness) async {
        await confirmed(h, lead: 15)
        await h.model.start()
        await h.clock.waitForSleepCount(1)
    }

    private func waitUntil(_ condition: () -> Bool, file: StaticString = #filePath, line: UInt = #line) async {
        for _ in 0..<2000 {
            if condition() { return }
            try? await Task.sleep(nanoseconds: 1_000_000)
        }
        XCTFail("timed out", file: file, line: line)
    }

    private func t(_ hhmmss: String) -> Date { ServiceBuilder.time(hhmmss) }

    func testStartShowsTheInitialDisplay() async throws {
        let h = try makeHarness()
        await tracking(h)
        XCTAssertEqual(h.model.display.eta, t("08:52:00"))
        XCTAssertEqual(h.model.display.scheduledArrival, t("08:47:00"))
        XCTAssertEqual(h.model.display.delayMinutes, 5)
        XCTAssertEqual(h.model.display.alarmAt, t("08:37:00"))
        await h.model.imOff()
    }

    func testEtaChangedUpdatesArrivalDelayAndAlarm() async throws {
        let h = try makeHarness()
        await tracking(h)
        await h.model.handle(.etaChanged(oldEta: t("08:52:00"), newEta: t("08:57:00"), fireAt: t("08:42:00")))
        XCTAssertEqual(h.model.display.eta, t("08:57:00"))
        XCTAssertEqual(h.model.display.delayMinutes, 10)
        XCTAssertEqual(h.model.display.alarmAt, t("08:42:00"))
        XCTAssertFalse(h.model.display.isEstimated)
        await h.model.imOff()
    }

    func testEtaEstimatedIsLabelledAsAnEstimate() async throws {
        let h = try makeHarness()
        await tracking(h)
        await h.model.handle(.etaEstimated(eta: t("08:55:00"), source: .gpsPace, staleness: 120, rescheduled: true))
        XCTAssertEqual(h.model.display.eta, t("08:55:00"))
        XCTAssertTrue(h.model.display.isEstimated)
        XCTAssertEqual(h.model.display.estimateSource, .gpsPace)
        await h.model.imOff()
    }

    func testCancelledAndDroppedShowCardsAndKeepTrackingAndTheAlarm() async throws {
        let h = try makeHarness()
        await tracking(h)
        await h.model.handle(.serviceCancelled(at: t("08:10:00")))
        await h.model.handle(.destinationLost(at: t("08:11:00")))
        XCTAssertTrue(h.model.display.cancelled)
        XCTAssertTrue(h.model.display.destinationDropped)
        XCTAssertEqual(h.model.phase, .tracking)
        XCTAssertEqual(h.scheduler.cancelCount, 0, "the tracker leaves the alarm armed")
        await h.model.imOff()
    }

    func testDestinationRestoredClearsTheCards() async throws {
        let h = try makeHarness()
        await tracking(h)
        await h.model.handle(.destinationLost(at: t("08:11:00")))
        await h.model.handle(.serviceCancelled(at: t("08:12:00")))
        await h.model.handle(.destinationRestored(newEta: t("08:53:00")))
        XCTAssertFalse(h.model.display.cancelled)
        XCTAssertFalse(h.model.display.destinationDropped)
        XCTAssertEqual(h.model.display.eta, t("08:53:00"))
        await h.model.imOff()
    }

    func testFeedLostShowsHealthAndRecoveredClearsIt() async throws {
        let h = try makeHarness()
        await tracking(h)
        await h.model.handle(.feedLost(lastGoodAt: t("08:00:00"), kind: .network))
        XCTAssertTrue(h.model.display.feedLost)
        XCTAssertEqual(h.model.display.lastGoodAt, t("08:00:00"))
        XCTAssertFalse(h.model.display.rateLimited)
        await h.model.handle(.feedRecovered(outage: 300, freshEta: t("08:56:00"), deltaFromFallback: nil))
        XCTAssertFalse(h.model.display.feedLost)
        XCTAssertEqual(h.model.display.eta, t("08:56:00"))
        await h.model.imOff()
    }

    func testAFeedLossFromHttp429ShowsRateLimited() async throws {
        let h = try makeHarness()
        await tracking(h)
        await h.model.handle(.feedLost(lastGoodAt: nil, kind: .http(429)))
        XCTAssertTrue(h.model.display.rateLimited)
        await h.model.handle(.feedRecovered(outage: 60, freshEta: nil, deltaFromFallback: nil))
        XCTAssertFalse(h.model.display.rateLimited)
        await h.model.imOff()
    }

    func testAlarmSchedulingFailedShowsAlarmNotSetUntilAnEtaChangeSucceeds() async throws {
        let h = try makeHarness()
        await tracking(h)
        await h.model.handle(.alarmSchedulingFailed(fireAt: t("08:37:00"), message: "refused"))
        XCTAssertTrue(h.model.display.alarmNotSet)
        await h.model.handle(.etaChanged(oldEta: t("08:52:00"), newEta: t("08:53:00"), fireAt: t("08:38:00")))
        XCTAssertFalse(h.model.display.alarmNotSet)
        await h.model.imOff()
    }

    func testARetryThatSucceedsClearsAlarmNotSetWithoutAnEtaChange() async throws {
        let h = try makeHarness()
        h.scheduler.failuresRemaining = 1
        await tracking(h)
        await waitUntil { h.model.display.alarmNotSet }
        h.clock.tick(advancing: 60)
        await waitUntil { !h.model.display.alarmNotSet }
        XCTAssertEqual(h.scheduler.scheduled.count, 1)
        await h.model.imOff()
    }

    func testRepeatedEventsDoNotToggleState() async throws {
        let h = try makeHarness()
        await tracking(h)
        await h.model.handle(.feedLost(lastGoodAt: t("08:00:00"), kind: .network))
        let once = h.model.display
        await h.model.handle(.feedLost(lastGoodAt: t("08:00:00"), kind: .network))
        XCTAssertEqual(h.model.display, once)
        await h.model.handle(.destinationLost(at: t("08:11:00")))
        await h.model.handle(.destinationRestored(newEta: nil))
        await h.model.handle(.destinationLost(at: t("08:20:00")))
        XCTAssertTrue(h.model.display.destinationDropped)
        await h.model.imOff()
    }

    func testArrivedStopsTheTrackerAndEndsTheSession() async throws {
        let h = try makeHarness()
        await tracking(h)
        await h.model.handle(.arrived(at: t("08:52:00")))
        XCTAssertEqual(h.model.phase, .ended(.arrived))
        XCTAssertEqual(h.scheduler.cancelCount, 1)
    }

    func testEventsAfterTheSessionEndedAreIgnored() async throws {
        let h = try makeHarness()
        await tracking(h)
        await h.model.imOff()
        let before = h.model.display
        await h.model.handle(.feedLost(lastGoodAt: nil, kind: .network))
        XCTAssertEqual(h.model.display, before)
        XCTAssertEqual(h.model.phase, .ended(.imOff))
    }

    func testTheAlarmRingingMovesToRingingAndImOffStillEndsTheSession() async throws {
        let h = try makeHarness()
        h.model.alarmRang()
        XCTAssertEqual(h.model.phase, .selecting, "alarmRang is ignored when not tracking")
        await tracking(h)
        h.model.alarmRang()
        XCTAssertEqual(h.model.phase, .ringing)
        await h.model.imOff()
        XCTAssertEqual(h.model.phase, .ended(.imOff))
    }

    func testComingBackToTheAppAfterTheAlarmTimeShowsTheRingingScreen() async throws {
        let h = try makeHarness()
        await tracking(h)
        let alarmAt = try XCTUnwrap(h.model.display.alarmAt)
        h.model.appBecameActive(now: alarmAt.addingTimeInterval(-1))
        XCTAssertEqual(h.model.phase, .tracking)
        h.model.appBecameActive(now: alarmAt.addingTimeInterval(1))
        XCTAssertEqual(h.model.phase, .ringing)
    }

    func testTrackerDelaysFlowThroughToTheDisplay() async throws {
        let h = try makeHarness()
        await tracking(h)
        h.provider.details = .success(ServiceBuilder.happyPath(destinationEstimate: "08:57:00"))
        h.clock.tick(advancing: 60)
        await waitUntil { h.model.display.eta == self.t("08:57:00") }
        XCTAssertEqual(h.model.display.delayMinutes, 10)
        await h.model.imOff()
    }

    // MARK: Event collector lifetime

    func testALateTrackerEventAfterImOffDoesNotChangeTheDisplay() async throws {
        let h = try makeHarness()
        await tracking(h)
        await h.model.imOff()
        let before = h.model.display
        h.provider.details = .success(ServiceBuilder.happyPath(destinationEstimate: "08:57:00"))
        h.clock.tick(advancing: 60)
        for _ in 0..<50 { await Task.yield() }
        try await Task.sleep(nanoseconds: 50_000_000)
        XCTAssertEqual(h.model.display, before)
        XCTAssertEqual(h.model.phase, .ended(.imOff))
    }

    func testASecondSessionIsNotAffectedByEventsBufferedFromTheFirst() async throws {
        let h = try makeHarness()
        await tracking(h)
        // Let the first tracker buffer a delay event without the main actor draining it.
        h.provider.details = .success(ServiceBuilder.happyPath(destinationEstimate: "08:57:00"))
        h.clock.tick(advancing: 60)
        Thread.sleep(forTimeInterval: 0.2)
        await h.model.imOff()
        h.model.returnHome()
        h.provider.details = .success(ServiceBuilder.happyPath())
        await confirmed(h, lead: 15)
        await h.model.start()
        for _ in 0..<50 { await Task.yield() }
        try await Task.sleep(nanoseconds: 50_000_000)
        XCTAssertEqual(h.model.display.eta, t("08:52:00"), "the first session's delay must not reach the second")
        XCTAssertEqual(h.model.phase, .tracking)
        await h.model.imOff()
    }

    func testArrivalFromTheEventStreamStillFinishesTheEndOfSessionWork() async throws {
        let directory = try StationDirectory.parse(Data(directoryJSON.utf8))
        let control = SessionModelNearestTests.FakeLocationControl()
        let h = try makeHarness()
        let model = SessionModel(provider: h.provider, scheduler: h.scheduler, directory: directory,
                                 location: FakeLocationSource(), clock: h.clock, permissions: h.permissions,
                                 settings: h.settings, locationControl: control)
        model.selectFrom(h.wat); model.selectTo(h.bsk)
        await model.loadBoard()
        guard case let .loaded(services) = model.board else { return XCTFail("board not loaded") }
        await model.choose(services[0])
        model.setLead(15)
        await model.start()
        await h.clock.waitForSleepCount(1)
        h.clock.tick(advancing: 3 * 3600)   // 10:00, past the 08:52 arrival plus the grace period
        await waitUntil { model.phase == .ended(.arrived) }
        XCTAssertEqual(control.stopped, 1)
        XCTAssertGreaterThanOrEqual(h.scheduler.cancelCount, 1)   // the tracker cancels on arrival, and the model stops it too
    }

    // MARK: Double events

    func testFeedRecoveredWithNoPriorLossChangesNothingButTheFlags() async throws {
        let h = try makeHarness()
        await tracking(h)
        let before = h.model.display
        await h.model.handle(.feedRecovered(outage: 0, freshEta: nil, deltaFromFallback: nil))
        XCTAssertEqual(h.model.display, before)
        XCTAssertFalse(h.model.display.feedLost)
        XCTAssertFalse(h.model.display.rateLimited)
        XCTAssertEqual(h.model.phase, .tracking)
        await h.model.imOff()
    }

    func testRingingDeliveredTwiceStaysRinging() async throws {
        let h = try makeHarness()
        await tracking(h)
        let before = h.model.display
        h.model.alarmRang()
        h.model.alarmRang()
        XCTAssertEqual(h.model.phase, .ringing)
        XCTAssertEqual(h.model.display, before)
        await h.model.imOff()
        XCTAssertEqual(h.model.phase, .ended(.imOff))
    }

    func testArrivedTwiceEndsOnceAndCancelsTheAlarmOnce() async throws {
        let h = try makeHarness()
        await tracking(h)
        await h.model.handle(.arrived(at: t("08:52:00")))
        await h.model.handle(.arrived(at: t("08:52:00")))
        XCTAssertEqual(h.model.phase, .ended(.arrived))
        XCTAssertEqual(h.scheduler.cancelCount, 1)
    }

    func testAnyEventAfterArrivedIsIgnored() async throws {
        let h = try makeHarness()
        await tracking(h)
        await h.model.handle(.arrived(at: t("08:52:00")))
        let before = h.model.display
        await h.model.handle(.feedLost(lastGoodAt: nil, kind: .http(429)))
        await h.model.handle(.etaChanged(oldEta: t("08:52:00"), newEta: t("09:10:00"), fireAt: t("08:55:00")))
        await h.model.handle(.serviceCancelled(at: t("08:53:00")))
        h.model.alarmRang()
        XCTAssertEqual(h.model.display, before)
        XCTAssertEqual(h.model.phase, .ended(.arrived))
    }

    func testANon429FeedLossAfterA429ClearsRateLimitedAndKeepsFeedLost() async throws {
        let h = try makeHarness()
        await tracking(h)
        await h.model.handle(.feedLost(lastGoodAt: t("08:00:00"), kind: .http(429)))
        XCTAssertTrue(h.model.display.rateLimited)
        await h.model.handle(.feedLost(lastGoodAt: t("08:01:00"), kind: .network))
        XCTAssertFalse(h.model.display.rateLimited)
        XCTAssertTrue(h.model.display.feedLost)
        XCTAssertEqual(h.model.display.lastGoodAt, t("08:01:00"))
        await h.model.imOff()
    }
}
