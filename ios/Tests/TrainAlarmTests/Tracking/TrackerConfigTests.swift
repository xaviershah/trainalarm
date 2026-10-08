import XCTest
@testable import TrainAlarm

final class TrackerConfigTests: XCTestCase {
    func testDefaultsMatchTheSpec() {
        let config = TrackerConfig(leadTime: 600)
        XCTAssertEqual(config.leadTime, 600)
        XCTAssertEqual(config.rescheduleThreshold, 60)
        XCTAssertEqual(config.pollInterval, 45)
        XCTAssertEqual(config.guardMargin, 60)
        XCTAssertEqual(config.minPace, 1.0)
        XCTAssertEqual(config.maxFixAgePolls, 3)
        XCTAssertEqual(config.maxFixAge, 135)
        XCTAssertEqual(config.maxFixAccuracy, 200)
        XCTAssertEqual(config.arrivalGrace, 600)
    }

    func testMaxFixAgeFollowsThePollInterval() {
        var config = TrackerConfig(leadTime: 600)
        config.pollInterval = 30
        XCTAssertEqual(config.maxFixAge, 90)
    }
}
