import XCTest
@testable import TrainAlarm

final class PaletteContrastTests: XCTestCase {
    private func assertReadable(_ name: String, _ p: PaletteTokens, file: StaticString = #filePath, line: UInt = #line) {
        let pairs: [(String, UInt32, UInt32)] = [
            ("text on bg", p.text, p.bg), ("text on surface", p.text, p.surface),
            ("head on surface", p.head, p.surface), ("muted on surface", p.muted, p.surface),
            ("onAccent on accent", p.onAccent, p.accent), ("onTeal on teal", p.onTeal, p.teal),
            ("tealText on surface", p.tealText, p.surface), ("bad on surface", p.bad, p.surface),
        ]
        for (label, fg, bg) in pairs {
            XCTAssertGreaterThanOrEqual(Contrast.ratio(fg, bg), 4.5, "\(name): \(label)", file: file, line: line)
        }
    }

    func testDarkThemeMeetsAA() { assertReadable("dark", Palette.dark) }
    func testLightThemeMeetsAA() { assertReadable("light", Palette.light) }

    func testRatioMatchesKnownValues() {
        XCTAssertEqual(Contrast.ratio(0xFFFFFF, 0x000000), 21.0, accuracy: 0.01)
        XCTAssertEqual(Contrast.ratio(0xCFC3B6, 0x201D1C), 9.68, accuracy: 0.05)
    }

    func testOrangeAndTealAreNotUsableAsTextOnTheWrongGround() {
        XCTAssertLessThan(Contrast.ratio(0x015D67, 0x201D1C), 3.0)
        XCTAssertLessThan(Contrast.ratio(0xF36509, 0xCFC3B6), 3.0)
    }
}
