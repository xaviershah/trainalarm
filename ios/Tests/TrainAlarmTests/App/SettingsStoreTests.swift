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

    func testALeadTimeOutsideTheChoicesIsIgnored() {
        let store = SettingsStore(defaults: defaults)
        store.setDefaultLeadMinutes(17)
        XCTAssertNil(store.defaultLeadMinutes)
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
