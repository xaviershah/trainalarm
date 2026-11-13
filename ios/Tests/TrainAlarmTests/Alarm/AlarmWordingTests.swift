import AlarmKit
import XCTest
@testable import TrainAlarm

final class AlarmWordingTests: XCTestCase {
    func testTheStopButtonSaysImOffAndTheTitleIsNearlyThere() {
        XCTAssertEqual(AlarmWording.stopText, "I'm off")
        XCTAssertEqual(AlarmWording.title, "Nearly there")
    }

    func testTheTintIsTheBrandOrange() {
        XCTAssertEqual(AlarmWording.tintHex, 0xF36509)
    }

    func testAnAlertingAlarmCountsAsRinging() {
        XCTAssertTrue(AlarmRingSignal.isRinging([.scheduled, .alerting]))
    }

    func testScheduledOrPausedAlarmsDoNotCountAsRinging() {
        XCTAssertFalse(AlarmRingSignal.isRinging([.scheduled, .paused]))
        XCTAssertFalse(AlarmRingSignal.isRinging([]))
    }
}
