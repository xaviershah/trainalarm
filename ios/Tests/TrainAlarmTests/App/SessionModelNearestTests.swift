import XCTest
@testable import TrainAlarm

@MainActor
final class SessionModelNearestTests: XCTestCase {
    final class FakeLocationControl: LocationControl {
        private(set) var started = 0
        private(set) var stopped = 0
        func start() { started += 1 }
        func stop() { stopped += 1 }
    }

    struct Harness {
        let model: SessionModel
        let provider: SessionModelTests.ScriptedProvider
        let location: FakeLocationSource
        let control: FakeLocationControl
        let permissions: SessionModelTests.FakePermissions
        let clock: FakeClock
    }

    private let json = #"[{"crs":"WAT","name":"London Waterloo","lat":51.5031,"lon":-0.1132},{"crs":"WOK","name":"Woking","lat":51.3189,"lon":-0.5569},{"crs":"BSK","name":"Basingstoke","lat":51.2686,"lon":-1.0872}]"#
    private var suite = ""

    override func setUp() { suite = "nearest-test-\(UUID().uuidString)" }
    override func tearDown() { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }

    private func make() throws -> Harness {
        let provider = SessionModelTests.ScriptedProvider()
        provider.board = .success([ServiceBuilder.happyPath()])
        let location = FakeLocationSource()
        let control = FakeLocationControl()
        let permissions = SessionModelTests.FakePermissions()
        let clock = FakeClock(start: ServiceBuilder.time("07:00:00"))
        let model = SessionModel(provider: provider, scheduler: FakeAlarmScheduler(),
                                 directory: try StationDirectory.parse(Data(json.utf8)), location: location, clock: clock,
                                 permissions: permissions, settings: SettingsStore(defaults: UserDefaults(suiteName: suite)!),
                                 locationControl: control)
        return Harness(model: model, provider: provider, location: location, control: control, permissions: permissions, clock: clock)
    }

    private func fix(_ lat: Double, _ lon: Double, accuracy: Double = 20, at time: String = "07:00:00") -> LocationFix {
        LocationFix(latitude: lat, longitude: lon, timestamp: ServiceBuilder.time(time), accuracy: accuracy)
    }

    func testNearestFillsFromAndStopsTheGps() async throws {
        let h = try make()
        h.location.fix = fix(51.32, -0.56)
        await h.model.useNearestStation()
        XCTAssertEqual(h.model.from?.id, "WOK")
        XCTAssertNil(h.model.nearestFailure)
        XCTAssertFalse(h.model.isFindingNearest)
        XCTAssertEqual(h.control.started, 1)
        XCTAssertEqual(h.control.stopped, 1)
    }

    func testNearestAsksForLocationOnlyWhenNotDeterminedAndReportsARefusal() async throws {
        let h = try make()
        h.permissions.statuses[.location] = .notDetermined
        h.permissions.answers[.location] = .denied
        await h.model.useNearestStation()
        XCTAssertEqual(h.permissions.requested, [.location])
        XCTAssertEqual(h.model.nearestFailure, .permissionDenied)
        XCTAssertNil(h.model.from)
        XCTAssertEqual(h.control.started, 0)
    }

    func testNearestNeverReprompsADeniedPermission() async throws {
        let h = try make()
        h.permissions.statuses[.location] = .denied
        await h.model.useNearestStation()
        XCTAssertEqual(h.permissions.requested, [])
        XCTAssertEqual(h.model.nearestFailure, .permissionDenied)
    }

    func testNearestWithNoFixInTimeIsReported() async throws {
        let h = try make()
        let task = Task { await h.model.useNearestStation() }
        for attempt in 1...SessionModel.fixWaitAttempts {
            await h.clock.waitForSleepCount(attempt)
            h.clock.tick(advancing: SessionModel.fixWaitInterval)
        }
        await task.value
        XCTAssertEqual(h.model.nearestFailure, .noFix)
        XCTAssertNil(h.model.from)
        XCTAssertEqual(h.control.stopped, 1)
    }

    func testAFixWithUnknownAccuracyIsNotUsed() async throws {
        let h = try make()
        h.location.fix = fix(51.32, -0.56, accuracy: .infinity)
        let task = Task { await h.model.useNearestStation() }
        for attempt in 1...SessionModel.fixWaitAttempts {
            await h.clock.waitForSleepCount(attempt)
            h.clock.tick(advancing: SessionModel.fixWaitInterval)
        }
        await task.value
        XCTAssertEqual(h.model.nearestFailure, .noFix)
    }

    func testStartBeginsTheGpsAndImOffStopsIt() async throws {
        let h = try make()
        let wat = h.model.search("waterloo")[0], bsk = h.model.search("basing")[0]
        h.model.selectFrom(wat)
        h.model.selectTo(bsk)
        await h.model.loadBoard()
        guard case let .loaded(services) = h.model.board else { return XCTFail("board not loaded") }
        await h.model.choose(services[0])
        h.model.setLead(15)
        await h.model.start()
        await h.clock.waitForSleepCount(1)
        XCTAssertEqual(h.control.started, 1)
        await h.model.imOff()
        XCTAssertEqual(h.control.stopped, 1)
    }

    private func runThroughTheWait(_ h: Harness, _ task: Task<Void, Never>) async {
        for attempt in 1...SessionModel.fixWaitAttempts {
            await h.clock.waitForSleepCount(attempt)
            h.clock.tick(advancing: SessionModel.fixWaitInterval)
        }
        await task.value
    }

    func testAStaleFixIsIgnoredAndALaterFreshFixIsUsed() async throws {
        let h = try make()
        h.location.fix = fix(51.5031, -0.1132, at: "06:50:00")
        let task = Task { await h.model.useNearestStation() }
        await h.clock.waitForSleepCount(1)
        h.location.fix = fix(51.32, -0.56)
        h.clock.tick(advancing: SessionModel.fixWaitInterval)
        await task.value
        XCTAssertEqual(h.model.from?.id, "WOK")
        XCTAssertNil(h.model.nearestFailure)
    }

    func testOnlyAStaleFixIsNoFix() async throws {
        let h = try make()
        h.location.fix = fix(51.32, -0.56, at: "06:50:00")
        let task = Task { await h.model.useNearestStation() }
        await runThroughTheWait(h, task)
        XCTAssertEqual(h.model.nearestFailure, .noFix)
        XCTAssertNil(h.model.from)
    }

    func testPickingFromWhileTheLookupWaitsKeepsTheUsersChoice() async throws {
        let h = try make()
        h.location.fix = fix(51.32, -0.56, at: "06:50:00")
        let task = Task { await h.model.useNearestStation() }
        await h.clock.waitForSleepCount(1)
        h.model.selectFrom(h.model.search("basing")[0])
        h.location.fix = fix(51.32, -0.56)
        h.clock.tick(advancing: SessionModel.fixWaitInterval)
        await task.value
        XCTAssertEqual(h.model.from?.id, "BSK")
        XCTAssertNil(h.model.nearestFailure)
        XCTAssertEqual(h.control.stopped, 1)
    }

    func testStartingWhileTheLookupWaitsLeavesTheSessionsGpsRunning() async throws {
        let h = try make()
        h.model.selectFrom(h.model.search("waterloo")[0])
        h.model.selectTo(h.model.search("basing")[0])
        await h.model.loadBoard()
        guard case let .loaded(services) = h.model.board else { return XCTFail("board not loaded") }
        await h.model.choose(services[0])
        h.model.setLead(15)
        let task = Task { await h.model.useNearestStation() }
        await h.clock.waitForSleepCount(1)
        await h.model.start()
        h.location.fix = fix(51.32, -0.56)
        h.clock.tick(advancing: SessionModel.fixWaitInterval)
        await task.value
        XCTAssertEqual(h.model.from?.id, "WAT")
        XCTAssertEqual(h.control.stopped, 0)
        await h.model.imOff()
        XCTAssertEqual(h.control.stopped, 1)
    }

    func testCancellingTheLookupStopsTheGps() async throws {
        let h = try make()
        let task = Task { await h.model.useNearestStation() }
        await h.clock.waitForSleepCount(1)
        task.cancel()
        await task.value
        XCTAssertEqual(h.control.stopped, 1)
        XCTAssertFalse(h.model.isFindingNearest)
    }

    func testADoubleTapRunsOneLookupOnly() async throws {
        let h = try make()
        let first = Task { await h.model.useNearestStation() }
        await h.clock.waitForSleepCount(1)
        await h.model.useNearestStation()   // second tap while the first is waiting for a fix
        XCTAssertEqual(h.control.started, 1)
        XCTAssertEqual(h.control.stopped, 0, "the second tap must not stop the first lookup's GPS")
        h.location.fix = fix(51.32, -0.56)
        h.clock.tick(advancing: SessionModel.fixWaitInterval)
        await first.value
        XCTAssertEqual(h.model.from?.id, "WOK")
        XCTAssertEqual(h.control.started, 1)
        XCTAssertEqual(h.control.stopped, 1)
    }
}
