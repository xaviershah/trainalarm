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

    func testLeadStepMovesOneMinuteAndClampsAtTheEnds() {
        let c = SettingsStore.leadChoices
        XCTAssertEqual(LeadRuler.step(c, from: 15, up: true), 16)
        XCTAssertEqual(LeadRuler.step(c, from: 15, up: false), 14)
        XCTAssertEqual(LeadRuler.step(c, from: 30, up: true), 30)
        XCTAssertEqual(LeadRuler.step(c, from: 1, up: false), 1)
        XCTAssertEqual(LeadRuler.step(c, from: nil, up: true), 2)
        XCTAssertEqual(LeadRuler.step(c, from: nil, up: false), 1)
    }

    func testMinutesTextIsSingularForOne() {
        XCTAssertEqual(minutesText(1), "1 minute")
        XCTAssertEqual(minutesText(2), "2 minutes")
        XCTAssertEqual(minutesText(30), "30 minutes")
    }

    func testTheValueLineNamesTheChosenMinuteOrSaysNoneIsChosen() {
        XCTAssertEqual(leadValueLine(nil), "Not chosen")
        XCTAssertEqual(leadValueLine(1), "1 minute before arrival")
        XCTAssertEqual(leadValueLine(7), "7 minutes before arrival")
    }

    func testRulerLayoutSpacesEveryChoiceEvenlyBetweenTheInsets() {
        for width in [320.0, 375.0, 402.0] {
            let layout = RulerLayout(count: 30, width: CGFloat(width), inset: 16)
            XCTAssertEqual(layout.x(at: 0), 16, accuracy: 0.001)
            XCTAssertEqual(layout.x(at: 29), CGFloat(width) - 16, accuracy: 0.001)
            let expected = (CGFloat(width) - 32) / 29
            for i in 1..<30 {
                XCTAssertEqual(layout.x(at: i) - layout.x(at: i - 1), expected, accuracy: 0.001)
            }
        }
    }

    func testRulerLayoutFindsEveryChoiceFromItsOwnPosition() {
        // The real ruler is the screen width minus 18 points of padding on each side.
        for screen in [320.0, 375.0, 402.0] {
            let layout = RulerLayout(count: 30, width: CGFloat(screen) - 36, inset: 16)
            for i in 0..<30 { XCTAssertEqual(layout.index(at: layout.x(at: i)), i, "screen \(screen), tick \(i)") }
            for i in 1..<30 { XCTAssertGreaterThan(layout.x(at: i), layout.x(at: i - 1)) }   // a tick at every choice, in order
            XCTAssertEqual(layout.index(at: layout.x(at: 6) + 3), 6)   // a little off a tick still picks it
            XCTAssertEqual(layout.index(at: -50), 0)                    // beyond either end clamps
            XCTAssertEqual(layout.index(at: 9999), 29)
            XCTAssertEqual(layout.index(at: .nan), 0)
        }
    }

    func testRulerLayoutKeepsAtLeastEightPointsBetweenTicksOnNarrowPhones() {
        // Confirm sheet: the ruler is the screen width minus 18 points of padding on each side (36).
        for screen in [320.0, 375.0, 402.0] {
            let layout = RulerLayout(count: 30, width: CGFloat(screen) - 36, inset: 16)
            XCTAssertGreaterThanOrEqual(layout.spacing, 8, "confirm, screen \(screen)")
        }
        // Settings: the ruler sits in a card, so it loses 18 (screen) + 14 (card) on each side (64).
        // 320 wide is only an Android possibility; ~7.7 still separates the 1.5-wide ticks.
        for screen in [320.0, 375.0, 402.0] {
            let layout = RulerLayout(count: 30, width: CGFloat(screen) - 64, inset: 16)
            XCTAssertGreaterThanOrEqual(layout.spacing, screen < 375 ? 7.5 : 8, "settings, screen \(screen)")
        }
    }

    func testOnlyMultiplesOfFiveAreMajorTicks() {
        XCTAssertEqual(SettingsStore.leadChoices.filter(RulerLayout.isMajor), [5, 10, 15, 20, 25, 30])
    }
}
