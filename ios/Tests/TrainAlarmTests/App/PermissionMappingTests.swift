import CoreLocation
import XCTest
@testable import TrainAlarm

@MainActor
final class PermissionMappingTests: XCTestCase {
    func testAlarmKitAuthorizationMapsToPermissionStatus() {
        XCTAssertEqual(PermissionMapping.status(AlarmAuthorization.authorized), .granted)
        XCTAssertEqual(PermissionMapping.status(AlarmAuthorization.denied), .denied)
        XCTAssertEqual(PermissionMapping.status(AlarmAuthorization.notDetermined), .notDetermined)
    }

    func testLocationAuthorizationMapsToPermissionStatus() {
        XCTAssertEqual(PermissionMapping.status(CLAuthorizationStatus.authorizedWhenInUse), .granted)
        XCTAssertEqual(PermissionMapping.status(CLAuthorizationStatus.authorizedAlways), .granted)
        XCTAssertEqual(PermissionMapping.status(CLAuthorizationStatus.denied), .denied)
        XCTAssertEqual(PermissionMapping.status(CLAuthorizationStatus.restricted), .denied)
        XCTAssertEqual(PermissionMapping.status(CLAuthorizationStatus.notDetermined), .notDetermined)
    }

    func testIosRequiresAlarmsAndOnlyAsksForLocationOptionally() {
        let permissions = SystemPermissions()
        XCTAssertEqual(permissions.requiredKinds, [.alarm])
        XCTAssertEqual(permissions.optionalKinds, [.location])
        XCTAssertEqual(permissions.status(.notifications), .granted)
        XCTAssertEqual(permissions.status(.fullScreenIntent), .granted)
    }
}
