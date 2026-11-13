import XCTest
@testable import TrainAlarm

final class StationDirectoryNearestTests: XCTestCase {
    private let json = #"[{"crs":"WAT","name":"London Waterloo","lat":51.5031,"lon":-0.1132},{"crs":"WOK","name":"Woking","lat":51.3189,"lon":-0.5569},{"crs":"BSK","name":"Basingstoke","lat":51.2686,"lon":-1.0872}]"#

    func testNearestPicksTheClosestStation() throws {
        let directory = try StationDirectory.parse(Data(json.utf8))
        XCTAssertEqual(directory.nearest(latitude: 51.32, longitude: -0.56)?.id, "WOK")
        XCTAssertEqual(directory.nearest(latitude: 51.50, longitude: -0.12)?.id, "WAT")
        XCTAssertEqual(directory.nearest(latitude: 51.0, longitude: -1.5)?.id, "BSK")
    }

    func testNearestOfAnEmptyDirectoryIsNil() throws {
        XCTAssertNil(try StationDirectory.parse(Data("[]".utf8)).nearest(latitude: 51, longitude: 0))
    }

    func testNearestNeverReturnsAStationWithoutCoordinates() throws {
        let noCoords = #"[{"crs":"AAA","name":"Nowhere","lat":0,"lon":0},{"crs":"WOK","name":"Woking","lat":51.3189,"lon":-0.5569}]"#
        let directory = try StationDirectory.parse(Data(noCoords.utf8))
        XCTAssertEqual(directory.nearest(latitude: 0.1, longitude: 0.1)?.id, "WOK")
    }
}
