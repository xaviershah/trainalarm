import XCTest
@testable import TrainAlarm

final class ComponentsTests: XCTestCase {
    func testNoPillStyleUsesOrangeOrTealAsTextAndPairsAreReadable() {
        for t in [Palette.dark, Palette.light] {
            for style in Pill.Style.allCases {
                let pair = style.tokens(t)
                XCTAssertNotEqual(pair.foreground, t.accent, "\(style): orange is a fill only")
                XCTAssertNotEqual(pair.foreground, t.teal, "\(style): teal is a fill only")
                // A pill with no fill sits on a card, so it is read against the surface.
                let background = pair.background ?? t.surface
                XCTAssertGreaterThanOrEqual(Contrast.ratio(pair.foreground, background), 4.5, "\(style)")
            }
        }
        XCTAssertEqual(Pill.Style.onTeal.tokens(Palette.dark).foreground, Palette.dark.onTeal)
        XCTAssertEqual(Pill.Style.onTeal.tokens(Palette.dark).background, Palette.dark.teal)
    }

    func testLeadStepMovesOneChoiceAndClampsAtTheEnds() {
        let c = SettingsStore.leadChoices
        XCTAssertEqual(LeadRuler.step(c, from: 15, up: true), 20)
        XCTAssertEqual(LeadRuler.step(c, from: 15, up: false), 10)
        XCTAssertEqual(LeadRuler.step(c, from: 30, up: true), 30)
        XCTAssertEqual(LeadRuler.step(c, from: 5, up: false), 5)
        XCTAssertEqual(LeadRuler.step(c, from: nil, up: true), 10)
        XCTAssertEqual(LeadRuler.step(c, from: nil, up: false), 5)
    }
}
