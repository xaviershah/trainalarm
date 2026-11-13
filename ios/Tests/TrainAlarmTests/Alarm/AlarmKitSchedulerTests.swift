import XCTest
@testable import TrainAlarm

private final class FakeAlarmKitClient: AlarmKitClient {
    struct Failure: Error {}
    var authorization: AlarmAuthorization = .authorized
    var requestResult: AlarmAuthorization = .authorized
    var scheduleFailure: Error?
    var cancelFailure: Error?
    private(set) var requested = 0
    private(set) var calls: [String] = []  // "schedule" / "cancel", in order
    private(set) var scheduled: [(id: UUID, fireAt: Date)] = []
    private(set) var titles: [String] = []
    private(set) var cancelled: [UUID] = []

    func requestAuthorization() async throws -> AlarmAuthorization {
        requested += 1
        authorization = requestResult
        return requestResult
    }
    func schedule(id: UUID, fireAt: Date, title: String) async throws {
        calls.append("schedule")
        if let scheduleFailure { throw scheduleFailure }
        scheduled.append((id, fireAt))
        titles.append(title)
    }
    func cancel(id: UUID) throws {
        calls.append("cancel")
        cancelled.append(id)
        if let cancelFailure { throw cancelFailure }
    }
}

final class AlarmKitSchedulerTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_000_000)
    private var defaults: UserDefaults!

    override func setUp() { defaults = UserDefaults(suiteName: UUID().uuidString)! }

    private func make(_ client: FakeAlarmKitClient) -> AlarmKitScheduler {
        AlarmKitScheduler(client: client, defaults: defaults, now: { self.now })
    }
    private func later(_ s: TimeInterval) -> Date { now.addingTimeInterval(s) }

    func testTheAlarmIsTitledWithTheAppWording() async throws {
        let client = FakeAlarmKitClient()
        try await make(client).schedule(fireAt: later(600))
        XCTAssertEqual(client.titles, [AlarmWording.title])
    }

    func testAuthorizedSchedulesAFixedAlarmWithoutRequesting() async throws {
        let client = FakeAlarmKitClient()
        try await make(client).schedule(fireAt: later(600))
        XCTAssertEqual(client.requested, 0)
        XCTAssertEqual(client.scheduled.map(\.fireAt), [later(600)])
    }

    func testNotDeterminedRequestsThenSchedules() async throws {
        let client = FakeAlarmKitClient()
        client.authorization = .notDetermined
        try await make(client).schedule(fireAt: later(600))
        XCTAssertEqual(client.requested, 1)
        XCTAssertEqual(client.scheduled.count, 1)
    }

    func testNotDeterminedThenDeniedThrowsAndSchedulesNothing() async {
        let client = FakeAlarmKitClient()
        client.authorization = .notDetermined
        client.requestResult = .denied
        await assertNotAuthorized(make(client), client)
    }

    func testDeniedThrowsWithoutRequesting() async {
        let client = FakeAlarmKitClient()
        client.authorization = .denied
        await assertNotAuthorized(make(client), client)
        XCTAssertEqual(client.requested, 0)
    }

    private func assertNotAuthorized(_ s: AlarmKitScheduler, _ c: FakeAlarmKitClient,
                                     file: StaticString = #filePath, line: UInt = #line) async {
        do {
            try await s.schedule(fireAt: later(600))
            XCTFail("expected notAuthorized", file: file, line: line)
        } catch {
            XCTAssertEqual(error as? AlarmSchedulerError, .notAuthorized, file: file, line: line)
        }
        XCTAssertTrue(c.scheduled.isEmpty, file: file, line: line)
    }

    func testAFireTimeAtOrBeforeNowIsRaisedToTheMinimumLead() async throws {
        let client = FakeAlarmKitClient()
        let scheduler = make(client)
        try await scheduler.schedule(fireAt: now)
        try await scheduler.schedule(fireAt: later(-300))
        let minimum = later(AlarmKitScheduler.minimumLead)
        XCTAssertEqual(client.scheduled.map(\.fireAt), [minimum, minimum])
    }

    func testASecondScheduleUsesAFreshIdThenCancelsThePreviousOne() async throws {
        let client = FakeAlarmKitClient()
        let scheduler = make(client)
        try await scheduler.schedule(fireAt: later(600))
        try await scheduler.schedule(fireAt: later(900))
        XCTAssertEqual(client.calls, ["schedule", "schedule", "cancel"])
        XCTAssertNotEqual(client.scheduled[0].id, client.scheduled[1].id)
        XCTAssertEqual(client.cancelled, [client.scheduled[0].id])
    }

    func testAFailingScheduleLeavesThePreviousAlarmInPlace() async throws {
        let client = FakeAlarmKitClient()
        let scheduler = make(client)
        try await scheduler.schedule(fireAt: later(600))
        client.scheduleFailure = FakeAlarmKitClient.Failure()
        do {
            try await scheduler.schedule(fireAt: later(900))
            XCTFail("expected the failure to propagate")
        } catch is FakeAlarmKitClient.Failure {}
        XCTAssertTrue(client.cancelled.isEmpty)  // the first alarm was not torn down
        await scheduler.cancel()
        XCTAssertEqual(client.cancelled, [client.scheduled[0].id])  // and is still the current one
    }

    func testTheCurrentIdSurvivesANewSchedulerInstance() async throws {
        let client = FakeAlarmKitClient()
        try await make(client).schedule(fireAt: later(600))
        await make(client).cancel()
        XCTAssertEqual(client.cancelled, [client.scheduled[0].id])
    }

    func testCancelCancelsOnceAndCancelWithNothingScheduledDoesNothing() async throws {
        let client = FakeAlarmKitClient()
        let scheduler = make(client)
        await scheduler.cancel()  // nothing scheduled
        XCTAssertTrue(client.calls.isEmpty)
        try await scheduler.schedule(fireAt: later(600))
        await scheduler.cancel()
        await scheduler.cancel()  // twice
        XCTAssertEqual(client.cancelled.count, 1)
    }

    func testCancelSwallowsClientErrorsForAStaleId() async throws {
        let client = FakeAlarmKitClient()
        let scheduler = make(client)
        try await scheduler.schedule(fireAt: later(600))
        client.cancelFailure = FakeAlarmKitClient.Failure()
        await scheduler.cancel()  // must not throw or crash
        await scheduler.cancel()  // the stale id was forgotten: no second attempt
        XCTAssertEqual(client.cancelled.count, 1)
    }
}
