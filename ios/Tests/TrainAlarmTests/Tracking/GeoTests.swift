import XCTest
@testable import TrainAlarm

final class GeoTests: XCTestCase {
    func testHaversineDistanceVectors() {
        // lat1, lon1, lat2, lon2, expected metres
        let vectors: [(Double, Double, Double, Double, Double)] = [
            (0, 0, 0, 1, 111194.927),
            (51.5031, -0.1132, 51.5031, -0.1132, 0.0),
            (51.5031, -0.1132, 51.2679, -1.0877, 72505.005),
            (90, 0, 0, 0, 10007543.398),
            (51.5, 0, 51.6, 0, 11119.493),
            (0, 0, 0, 180, 20015086.796),
        ]
        for (lat1, lon1, lat2, lon2, expected) in vectors {
            let actual = Geo.distanceMetres(fromLatitude: lat1, longitude: lon1, toLatitude: lat2, longitude: lon2)
            XCTAssertEqual(actual, expected, accuracy: 0.01, "(\(lat1),\(lon1)) to (\(lat2),\(lon2))")
        }
    }
}
