import XCTest
@testable import TrainAlarm

final class RingTriggerTests: XCTestCase {
    private let alarm = Date(timeIntervalSince1970: 1_000_000)

    func testRingsOnceTheAlarmTimeHasPassedWhileTracking() {
        XCTAssertTrue(RingTrigger.shouldRing(phase: .tracking, alarmAt: alarm, now: alarm.addingTimeInterval(1)))
    }

    func testRingsExactlyAtTheAlarmTime() {
        XCTAssertTrue(RingTrigger.shouldRing(phase: .tracking, alarmAt: alarm, now: alarm))
    }

    func testDoesNotRingBeforeTheAlarmTime() {
        XCTAssertFalse(RingTrigger.shouldRing(phase: .tracking, alarmAt: alarm, now: alarm.addingTimeInterval(-1)))
    }

    func testDoesNotRingWithNoAlarmTime() {
        XCTAssertFalse(RingTrigger.shouldRing(phase: .tracking, alarmAt: nil, now: alarm))
    }

    func testOnlyTrackingCanStartRinging() {
        for phase: SessionPhase in [.selecting, .confirming, .ringing, .ended(.arrived), .ended(.imOff)] {
            XCTAssertFalse(RingTrigger.shouldRing(phase: phase, alarmAt: alarm, now: alarm.addingTimeInterval(60)), "\(phase)")
        }
    }
}
