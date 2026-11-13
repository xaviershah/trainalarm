import XCTest
@testable import TrainAlarm

final class RttTokenTests: XCTestCase {
    func testMissingOrEmptyValuesAreNoToken() {
        XCTAssertNil(RttToken.parse(nil))
        XCTAssertNil(RttToken.parse(""))
        XCTAssertNil(RttToken.parse("   "))
        XCTAssertNil(RttToken.parse(42))
    }

    func testAnUnsubstitutedPlaceholderIsNoToken() {
        XCTAssertNil(RttToken.parse("$(RTT_ACCESS_TOKEN)"))
    }

    func testARealValueIsTrimmed() {
        XCTAssertEqual(RttToken.parse("  abc123\n"), "abc123")
    }
}
