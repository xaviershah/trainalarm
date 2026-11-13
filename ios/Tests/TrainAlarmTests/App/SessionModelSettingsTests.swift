import XCTest
@testable import TrainAlarm

@MainActor
final class SessionModelSettingsTests: XCTestCase {
    private struct BoardProvider: TrainDataProvider {
        func searchStations(query: String) async throws -> [Station] { [] }
        func departureBoard(station: Station, from: Date, callingAt: Station?) async throws -> [Service] { [ServiceBuilder.happyPath()] }
        func serviceDetails(serviceId: String, date: Date) async throws -> Service { ServiceBuilder.happyPath() }
    }

    private final class Permissions: PermissionsGateway {
        var requiredKinds: [PermissionKind] = [.alarm]
        var optionalKinds: [PermissionKind] = [.location]
        var statuses: [PermissionKind: PermissionStatus] = [.alarm: .granted, .location: .notDetermined]
        private(set) var requested: [PermissionKind] = []
        func status(_ kind: PermissionKind) -> PermissionStatus { statuses[kind] ?? .granted }
        func request(_ kind: PermissionKind) async -> PermissionStatus {
            requested.append(kind)
            statuses[kind] = .granted
            return .granted
        }
    }

    private struct Rig {
        let model: SessionModel
        let scheduler: FakeAlarmScheduler
        let clock: FakeClock
        let permissions: Permissions
        let wat: Station
        let bsk: Station
    }

    private let directoryJSON = #"[{"crs":"WAT","name":"London Waterloo","lat":51.5031,"lon":-0.1132},{"crs":"BSK","name":"Basingstoke","lat":51.2686,"lon":-1.0872}]"#

    private func makeRig() throws -> Rig {
        let directory = try StationDirectory.parse(Data(directoryJSON.utf8))
        let scheduler = FakeAlarmScheduler()
        let clock = FakeClock(start: ServiceBuilder.time("07:00:00"))
        let permissions = Permissions()
        let settings = SettingsStore(defaults: UserDefaults(suiteName: "settings-test-\(UUID().uuidString)")!)
        let model = SessionModel(provider: BoardProvider(), scheduler: scheduler, directory: directory,
                                 location: FakeLocationSource(), clock: clock, permissions: permissions, settings: settings)
        return Rig(model: model, scheduler: scheduler, clock: clock, permissions: permissions,
                   wat: directory.station(crs: "WAT")!, bsk: directory.station(crs: "BSK")!)
    }

    func testSettingsListsRequiredPermissionsThenOptionalOnes() throws {
        XCTAssertEqual(try makeRig().model.settingsPermissionKinds, [.alarm, .location])
    }

    func testPermissionStatusReadsTheGateway() throws {
        let rig = try makeRig()
        XCTAssertEqual(rig.model.permissionStatus(.alarm), .granted)
        XCTAssertEqual(rig.model.permissionStatus(.location), .notDetermined)
    }

    func testRequestingAPermissionAsksTheGatewayAndRefreshes() async throws {
        let rig = try makeRig()
        let before = rig.model.permissionRevision
        await rig.model.requestPermission(.location)
        XCTAssertEqual(rig.permissions.requested, [.location])
        XCTAssertEqual(rig.model.permissionStatus(.location), .granted)
        XCTAssertGreaterThan(rig.model.permissionRevision, before)
    }

    func testRefreshingPermissionsBumpsTheRevision() throws {
        let rig = try makeRig()
        let before = rig.model.permissionRevision
        rig.model.refreshPermissions()
        XCTAssertEqual(rig.model.permissionRevision, before + 1)
    }

    func testTestAlarmRingsTenSecondsFromNow() async throws {
        let rig = try makeRig()
        await rig.model.scheduleTestAlarm()
        let expected = rig.clock.now().addingTimeInterval(10)
        XCTAssertEqual(rig.scheduler.scheduled, [expected])
        XCTAssertEqual(rig.model.testAlarm, .scheduled(expected))
    }

    func testAFailedTestAlarmIsReported() async throws {
        let rig = try makeRig()
        rig.scheduler.failuresRemaining = 1
        await rig.model.scheduleTestAlarm()
        XCTAssertEqual(rig.model.testAlarm, .failed)
        XCTAssertTrue(rig.scheduler.scheduled.isEmpty)
    }

    func testTestAlarmIsRefusedWhileTrackingSoTheRealAlarmSurvives() async throws {
        let rig = try makeRig()
        rig.model.selectFrom(rig.wat)
        rig.model.selectTo(rig.bsk)
        await rig.model.loadBoard()
        guard case let .loaded(services) = rig.model.board else { return XCTFail("board not loaded") }
        await rig.model.choose(services[0])
        rig.model.setLead(15)
        await rig.model.start()
        await rig.clock.waitForSleepCount(1)
        let attempts = rig.scheduler.attempts.count

        XCTAssertFalse(rig.model.canTestAlarm)
        await rig.model.scheduleTestAlarm()

        XCTAssertEqual(rig.scheduler.attempts.count, attempts)
        XCTAssertEqual(rig.model.testAlarm, .idle)
        await rig.model.imOff()
    }

    func testPermissionStatusIsSaidInWordsNotColourAlone() {
        XCTAssertEqual(SettingsView.pillText(.denied), "Off")
        XCTAssertEqual(SettingsView.pillText(.notDetermined), "Not asked")
        XCTAssertTrue(SettingsView.statusWords(.denied).contains("system settings"))
        XCTAssertEqual(SettingsView.statusWords(.granted), "Allowed")
    }

    func testRefreshClearsATestAlarmWhoseTimeHasPassed() async throws {
        let rig = try makeRig()
        await rig.model.scheduleTestAlarm()
        rig.clock.tick(advancing: 11)
        rig.model.refreshTestAlarm()
        XCTAssertEqual(rig.model.testAlarm, .idle)
    }

    func testRefreshKeepsATestAlarmThatIsStillAhead() async throws {
        let rig = try makeRig()
        await rig.model.scheduleTestAlarm()
        let state = rig.model.testAlarm
        rig.clock.tick(advancing: 5)
        rig.model.refreshTestAlarm()
        XCTAssertEqual(rig.model.testAlarm, state)
    }

    func testRefreshClearsAFailedTestAlarm() async throws {
        let rig = try makeRig()
        rig.scheduler.failuresRemaining = 1
        await rig.model.scheduleTestAlarm()
        rig.model.refreshTestAlarm()
        XCTAssertEqual(rig.model.testAlarm, .idle)
    }

    func testStartingAJourneyClearsTheTestAlarmNote() async throws {
        let rig = try makeRig()
        await rig.model.scheduleTestAlarm()
        rig.model.selectFrom(rig.wat)
        rig.model.selectTo(rig.bsk)
        await rig.model.loadBoard()
        guard case let .loaded(services) = rig.model.board else { return XCTFail("board not loaded") }
        await rig.model.choose(services[0])
        rig.model.setLead(15)
        await rig.model.start()
        await rig.clock.waitForSleepCount(1)
        XCTAssertEqual(rig.model.testAlarm, .idle)
        await rig.model.imOff()
    }
}
