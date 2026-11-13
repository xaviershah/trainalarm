import CoreLocation
import XCTest
@testable import TrainAlarm

final class LocationFixTests: XCTestCase {
    private func location(accuracy: Double) -> CLLocation {
        CLLocation(coordinate: CLLocationCoordinate2D(latitude: 51.5, longitude: -0.12), altitude: 0,
                   horizontalAccuracy: accuracy, verticalAccuracy: 0, timestamp: Date(timeIntervalSince1970: 1_000))
    }

    func testFixCarriesPositionTimeAndAccuracy() {
        let fix = LocationFix(location: location(accuracy: 25))
        XCTAssertEqual(fix, LocationFix(latitude: 51.5, longitude: -0.12, timestamp: Date(timeIntervalSince1970: 1_000), accuracy: 25))
    }

    func testNegativeAccuracyMeansInvalidAndBecomesInfinite() {
        XCTAssertEqual(LocationFix(location: location(accuracy: -1)).accuracy, .infinity)
    }

    func testSourceKeepsTheLatestFixFromTheDelegate() {
        let source = CoreLocationSource()
        XCTAssertNil(source.latestFix())
        source.locationManager(CLLocationManager(), didUpdateLocations: [location(accuracy: 10), location(accuracy: 30)])
        XCTAssertEqual(source.latestFix()?.accuracy, 30)
    }
}
