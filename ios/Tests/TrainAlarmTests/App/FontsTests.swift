import XCTest
@testable import TrainAlarm

final class FontsTests: XCTestCase {
    func testBundledFontsLoad() {
        XCTAssertEqual(Fonts.missing(), [], "UIAppFonts in project.yml must list the bundled font files")
    }
}
