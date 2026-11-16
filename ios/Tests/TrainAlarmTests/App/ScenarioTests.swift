#if DEBUG
import XCTest
@testable import TrainAlarm

@MainActor
final class ScenarioTests: XCTestCase {
    private let json = #"[{"crs":"KGX","name":"London Kings Cross","lat":51.5309,"lon":-0.1233},{"crs":"PBO","name":"Peterborough","lat":52.5748,"lon":-0.2504},{"crs":"EDB","name":"Edinburgh","lat":55.9521,"lon":-3.1894}]"#
    private var suite = ""
    private var settings: SettingsStore!

    override func setUp() { suite = "scenario-test-\(UUID().uuidString)" }
    override func tearDown() { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }

    private func make() throws -> (SessionModel, StationDirectory, FakeClock) {
        let directory = try StationDirectory.parse(Data(json.utf8))
        let clock = FakeClock(start: Date(timeIntervalSince1970: 1_783_000_000))
        settings = SettingsStore(defaults: UserDefaults(suiteName: suite)!)
        let model = SessionModel(provider: DemoProvider(clock: clock), scheduler: NoopAlarmScheduler(), directory: directory,
                                 location: FakeLocationSource(), clock: clock, permissions: DemoPermissions(),
                                 settings: settings)
        return (model, directory, clock)
    }

    private func run(_ scenario: Scenario) async throws -> (SessionModel, Date) {
        let (model, directory, clock) = try make()
        await scenario.apply(to: model, settings: settings, directory: directory, now: clock.now())
        return (model, clock.now())
    }

    func testTheLaunchArgumentNamesAScenario() {
        XCTAssertEqual(Scenario.from(arguments: ["app", "-scenario", "tracking-late"]), .trackingLate)
        XCTAssertEqual(Scenario.from(arguments: ["-scenario", "feed-lost"]), .feedLost)
        XCTAssertNil(Scenario.from(arguments: ["-scenario", "nonsense"]))
        XCTAssertNil(Scenario.from(arguments: ["-scenario"]))
        XCTAssertNil(Scenario.from(arguments: []))
    }

    func testEveryScenarioNameRoundTrips() {
        for scenario in Scenario.allCases { XCTAssertEqual(Scenario(rawValue: scenario.rawValue), scenario) }
        XCTAssertEqual(Scenario.allCases.count, 12)
    }

    func testDemoBoardHasThreeTrainsOneCancelledAndOnlyFromKingsCross() async throws {
        let clock = FakeClock(start: Date(timeIntervalSince1970: 1_783_000_000))
        let provider = DemoProvider(clock: clock)
        let kgx = Station(id: "KGX", name: "London Kings Cross", latitude: 0, longitude: 0)
        let edb = Station(id: "EDB", name: "Edinburgh", latitude: 0, longitude: 0)
        let board = try await provider.departureBoard(station: kgx, from: clock.now(), callingAt: edb)
        XCTAssertEqual(board.count, 3)
        XCTAssertTrue(board[2].journey.stops[0].isCancelled)
        let none = try await provider.departureBoard(station: edb, from: clock.now(), callingAt: kgx)
        XCTAssertEqual(none, [])
        let details = try await provider.serviceDetails(serviceId: "demo-1", date: clock.now())
        XCTAssertEqual(details.journey.stops.count, 5)
    }

    func testHomeAndSettingsLeaveTheModelAlone() async throws {
        for scenario in [Scenario.home, .settings] {
            let (model, _) = try await run(scenario)
            XCTAssertEqual(model.phase, .selecting)
            XCTAssertNil(model.from)
        }
        XCTAssertTrue(Scenario.settings.opensSettings)
        XCTAssertFalse(Scenario.home.opensSettings)
    }

    func testBoardAndConfirmScenarios() async throws {
        let (boardModel, _) = try await run(.board)
        guard case .loaded(let services) = boardModel.board else { return XCTFail("board not loaded") }
        XCTAssertEqual(services.count, 3)
        let (confirmModel, _) = try await run(.confirm)
        XCTAssertEqual(confirmModel.phase, .confirming)
        XCTAssertEqual(confirmModel.confirm?.leadMinutes, 7)
        XCTAssertNotNil(confirmModel.alarmAt)
    }

    func testTheConfirmScenarioUsesAnOddLeadTimeAndTheTrackingScenariosKeepFifteen() async throws {
        let (confirmModel, _) = try await run(.confirm)
        XCTAssertEqual(confirmModel.confirm?.leadMinutes, 7)
        let (trackingModel, _) = try await run(.tracking)
        XCTAssertEqual(trackingModel.confirm?.leadMinutes, 15)
    }

    func testTheSettingsScenarioSetsTheDefaultLeadTimeToSeven() async throws {
        _ = try await run(.settings)
        XCTAssertEqual(settings.defaultLeadMinutes, 7)
    }

    func testTrackingScenariosShowTheirStates() async throws {
        let (tracking, _) = try await run(.tracking)
        XCTAssertEqual(tracking.phase, .tracking)
        XCTAssertEqual(tracking.display.delayMinutes, 0)
        await tracking.imOff()

        let (late, _) = try await run(.trackingLate)
        XCTAssertEqual(late.display.delayMinutes, 4)
        await late.imOff()

        let (cancelled, _) = try await run(.cancelled)
        XCTAssertTrue(cancelled.display.cancelled)
        await cancelled.imOff()

        let (dropped, _) = try await run(.dropped)
        XCTAssertTrue(dropped.display.destinationDropped)
        await dropped.imOff()

        let (feed, _) = try await run(.feedLost)
        XCTAssertTrue(feed.display.feedLost)
        XCTAssertNotNil(feed.display.lastGoodAt)
        await feed.imOff()

        let (unset, _) = try await run(.alarmNotSet)
        XCTAssertTrue(unset.display.alarmNotSet)
        await unset.imOff()
    }

    func testRingingAndEndedScenarios() async throws {
        let (ringing, _) = try await run(.ringing)
        XCTAssertEqual(ringing.phase, .ringing)
        await ringing.imOff()
        let (ended, _) = try await run(.ended)
        XCTAssertEqual(ended.phase, .ended(.arrived))
    }

    func testDemoSpeedArgument() {
        XCTAssertEqual(Scenario.speed(from: ["app", "-demo-speed", "60"]), 60)
        XCTAssertEqual(Scenario.speed(from: ["app"]), 1)
        XCTAssertEqual(Scenario.speed(from: ["-demo-speed"]), 1)
        XCTAssertEqual(Scenario.speed(from: ["-demo-speed", "0"]), 1)
        XCTAssertEqual(Scenario.speed(from: ["-demo-speed", "fast"]), 1)
    }

    func testDemoClockRunsFasterThanTheWallClock() async throws {
        let clock = DemoClock(speed: 60)
        let before = clock.now()
        try await Task.sleep(nanoseconds: 100_000_000)
        XCTAssertGreaterThan(clock.now().timeIntervalSince(before), 3)   // 0.1 s of wall time is 6 s of demo time
    }

    func testDemoClockSleepIsShortenedBySpeed() async throws {
        let start = Date()
        try await DemoClock(speed: 60).sleep(for: 6)   // 0.1 s of wall time
        XCTAssertLessThan(Date().timeIntervalSince(start), 1.0)
    }

    func testDemoAlarmRingsWhenTheClockReachesTheFireTime() async throws {
        let clock = FakeClock(start: Date(timeIntervalSince1970: 1_000))
        var rang = 0
        let scheduler = DemoAlarmScheduler(clock: clock) { rang += 1 }
        try await scheduler.schedule(fireAt: clock.now().addingTimeInterval(30))
        await clock.waitForSleepCount(1)
        XCTAssertEqual(rang, 0)
        clock.tick(advancing: 30)
        for _ in 0..<2000 where rang == 0 { try await Task.sleep(nanoseconds: 1_000_000) }
        XCTAssertEqual(rang, 1)
    }

    func testCancellingTheDemoAlarmStopsItRinging() async throws {
        let clock = FakeClock(start: Date(timeIntervalSince1970: 1_000))
        var rang = 0
        let scheduler = DemoAlarmScheduler(clock: clock) { rang += 1 }
        try await scheduler.schedule(fireAt: clock.now().addingTimeInterval(30))
        await clock.waitForSleepCount(1)
        await scheduler.cancel()
        clock.tick(advancing: 30)
        try await Task.sleep(nanoseconds: 50_000_000)
        XCTAssertEqual(rang, 0)
    }

    func testDemoTimesDoNotMoveWhenTheClockDoes() async throws {
        let clock = FakeClock(start: Date(timeIntervalSince1970: 1_783_000_000))
        let provider = DemoProvider(clock: clock)
        let first = try await provider.serviceDetails(serviceId: "demo-1", date: clock.now())
        clock.tick(advancing: 120)
        let second = try await provider.serviceDetails(serviceId: "demo-1", date: clock.now())
        XCTAssertEqual(first.journey.stops.map(\.scheduledDeparture), second.journey.stops.map(\.scheduledDeparture))
    }

    func testAnUnderwayDemoTrainShowsTheRouteStripPartWayAlong() async throws {
        let clock = FakeClock(start: Date(timeIntervalSince1970: 1_783_000_000))
        let details = try await DemoProvider(clock: clock, underway: true).serviceDetails(serviceId: "demo-1", date: clock.now())
        let progress = try XCTUnwrap(RouteProgress.make(stops: details.journey.stops, from: "KGX", to: "EDB", eta: nil, now: clock.now()))
        XCTAssertGreaterThan(progress.fraction, 0.3)
        XCTAssertLessThan(progress.fraction, 0.5)
        XCTAssertTrue(Scenario.tracking.trainIsUnderway)
        XCTAssertFalse(Scenario.confirm.trainIsUnderway)
    }
}
#endif
