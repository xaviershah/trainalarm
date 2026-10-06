import XCTest
@testable import TrainAlarm

final class StationDirectoryTests: XCTestCase {

    private func bundled() throws -> StationDirectory { try StationDirectory.load() }

    private func small(_ names: [String]) throws -> StationDirectory {
        let records = names.enumerated().map { i, name -> String in
            let c = Character(UnicodeScalar(UInt8(65 + i)))
            return "{\"crs\":\"A\(c)\(c)\",\"name\":\"\(name)\",\"lat\":51.5,\"lon\":-0.1}"
        }
        return try StationDirectory.parse(Data("[\(records.joined(separator: ","))]".utf8))
    }

    private func assertMalformed(_ json: String, _ message: String, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertThrowsError(try StationDirectory.parse(Data(json.utf8)), file: file, line: line) {
            XCTAssertEqual($0 as? StationDirectoryError, .malformed(message), file: file, line: line)
        }
    }

    func testBundledCopyIsIdenticalToCanonicalFile() throws {
        let bundledURL = try XCTUnwrap(StationDirectory.bundledFileURL())
        var root = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { root.deleteLastPathComponent() }   // up to the repo root
        let canonical = root.appendingPathComponent("data/stations.json")
        XCTAssertEqual(try Data(contentsOf: bundledURL), try Data(contentsOf: canonical))
    }

    func testRecordCountIsInExpectedRange() throws {
        XCTAssertTrue((2600...2650).contains(try bundled().stations.count))
    }

    func testEveryCrsIsThreeUppercaseLettersAndUnique() throws {
        let ids = try bundled().stations.map(\.id)
        XCTAssertTrue(ids.allSatisfy { $0.range(of: "^[A-Z]{3}$", options: .regularExpression) != nil })
        XCTAssertEqual(ids.count, Set(ids).count)
    }

    func testEveryCoordinateIsInsideTheUkBoundingBox() throws {
        XCTAssertTrue(try bundled().stations.allSatisfy {
            (49.8...60.9).contains($0.latitude) && (-8.7...1.8).contains($0.longitude)
        })
    }

    func testKnownStationsResolve() throws {
        let dir = try bundled()
        XCTAssertEqual(dir.station(crs: "KGX")?.name, "London Kings Cross")
        XCTAssertEqual(dir.station(crs: "MAN")?.name, "Manchester Piccadilly")
        XCTAssertEqual(dir.station(crs: "EDB")?.name, "Edinburgh")
        XCTAssertEqual(dir.station(crs: "CDF")?.name, "Cardiff Central")
        XCTAssertEqual(try XCTUnwrap(dir.station(crs: "KGX")).latitude, 51.5309, accuracy: 0.001)
        XCTAssertEqual(try XCTUnwrap(dir.station(crs: "KGX")).longitude, -0.1229, accuracy: 0.001)
    }

    func testLookupIgnoresCaseAndWhitespace() throws {
        let dir = try bundled()
        XCTAssertEqual(dir.station(crs: "kgx")?.id, "KGX")
        XCTAssertEqual(dir.station(crs: " KGX ")?.id, "KGX")
    }

    func testUnknownAndCoordinateLessCodesReturnNil() throws {
        let dir = try bundled()
        XCTAssertNil(dir.station(crs: "ZZZ"))
        XCTAssertNil(dir.station(crs: "PDX"))   // NaPTAN has no coordinates for PDX
    }

    func testSearchPutsPrefixMatchesBeforeOtherMatches() throws {
        let dir = try small(["Waltham Cross", "Gerrards Cross", "Cross Gates"])
        XCTAssertEqual(dir.search(name: "cross").map(\.name), ["Cross Gates", "Gerrards Cross", "Waltham Cross"])
    }

    func testSearchIsCaseInsensitiveAndFindsRealStations() throws {
        XCTAssertTrue(try bundled().search(name: "KINGS CROSS").contains { $0.id == "KGX" })
    }

    func testBlankQueryReturnsNothing() throws {
        let dir = try bundled()
        XCTAssertTrue(dir.search(name: "").isEmpty)
        XCTAssertTrue(dir.search(name: "   ").isEmpty)
    }

    func testAmpersandsAndParenthesesAreSearchableAndNeverPatterns() throws {
        let dir = try bundled()
        XCTAssertTrue(dir.search(name: "harrow & wealdstone").contains { $0.id == "HRW" })
        XCTAssertTrue(dir.search(name: "richmond (london)").contains { $0.id == "RMD" })
        _ = dir.search(name: "(")
        _ = dir.search(name: "[a-")
    }

    func testParseRejectsAMissingField() {
        assertMalformed(#"[{"crs":"KGX","name":"X","lat":51.5}]"#, "record 0 is missing crs, name, lat or lon")
    }

    func testParseRejectsANonArray() {
        assertMalformed(#"{"crs":"KGX"}"#, "stations file was not a JSON array of objects")
    }

    func testParseRejectsDuplicateCrs() {
        assertMalformed(
            #"[{"crs":"KGX","name":"A","lat":51.5,"lon":-0.1},{"crs":"KGX","name":"B","lat":51.5,"lon":-0.1}]"#,
            "duplicate CRS KGX")
    }

    func testLoadThrowsResourceMissingWhenTheBundleHasNoFile() {
        // The test bundle does not contain stations.json (only the app bundle does).
        XCTAssertThrowsError(try StationDirectory.load(from: Bundle(for: Self.self))) {
            XCTAssertEqual($0 as? StationDirectoryError, .resourceMissing)
        }
    }
}
