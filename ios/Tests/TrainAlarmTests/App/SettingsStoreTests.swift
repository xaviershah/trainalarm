import XCTest
@testable import TrainAlarm

final class SettingsStoreTests: XCTestCase {
    private var suite = ""
    private var defaults: UserDefaults!

    override func setUp() {
        suite = "settings-test-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)!
    }

    override func tearDown() { defaults.removePersistentDomain(forName: suite) }

    func testFirstRunHasSystemThemeAndNoLeadTime() {
        let store = SettingsStore(defaults: defaults)
        XCTAssertEqual(store.theme, .system)
        XCTAssertNil(store.defaultLeadMinutes)
    }

    func testThemeAndLeadTimePersistAcrossInstances() {
        let first = SettingsStore(defaults: defaults)
        first.setTheme(.light)
        first.setDefaultLeadMinutes(20)
        let second = SettingsStore(defaults: defaults)
        XCTAssertEqual(second.theme, .light)
        XCTAssertEqual(second.defaultLeadMinutes, 20)
    }

    func testTheChoicesAreEveryMinuteFromOneToThirty() {
        XCTAssertEqual(SettingsStore.leadChoices, Array(1...30))
    }

    func testOnlyLeadTimesFromOneToThirtyAreAccepted() {
        let store = SettingsStore(defaults: defaults)
        store.setDefaultLeadMinutes(0)
        XCTAssertNil(store.defaultLeadMinutes)
        store.setDefaultLeadMinutes(31)
        XCTAssertNil(store.defaultLeadMinutes)
        store.setDefaultLeadMinutes(1)
        XCTAssertEqual(store.defaultLeadMinutes, 1)
        store.setDefaultLeadMinutes(30)
        XCTAssertEqual(store.defaultLeadMinutes, 30)
        store.setDefaultLeadMinutes(17)
        XCTAssertEqual(store.defaultLeadMinutes, 17)
    }

    func testAnOldStoredFiveMinuteStepStillReads() {
        defaults.set(15, forKey: "defaultLeadMinutes")
        XCTAssertEqual(SettingsStore(defaults: defaults).defaultLeadMinutes, 15)
    }

    func testAStoredSeventeenNowReadsBackAsSeventeen() {
        defaults.set(17, forKey: "defaultLeadMinutes")
        XCTAssertEqual(SettingsStore(defaults: defaults).defaultLeadMinutes, 17)
    }

    func testAStoredZeroOrThirtyOneReadsAsUnset() {
        for stored in [0, 31] {
            defaults.set(stored, forKey: "defaultLeadMinutes")
            XCTAssertNil(SettingsStore(defaults: defaults).defaultLeadMinutes, "stored \(stored)")
        }
    }

    func testAnUnknownStoredThemeFallsBackToSystem() {
        defaults.set("neon", forKey: "theme")
        XCTAssertEqual(SettingsStore(defaults: defaults).theme, .system)
    }

    func testAnInvalidStoredLeadTimeReadsAsUnset() {
        defaults.set(99, forKey: "defaultLeadMinutes")
        XCTAssertNil(SettingsStore(defaults: defaults).defaultLeadMinutes)
    }
}
