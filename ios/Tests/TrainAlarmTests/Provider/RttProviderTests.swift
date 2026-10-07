import XCTest
@testable import TrainAlarm

/// Serves canned responses so RttProvider's error mapping is tested without a network.
final class StubURLProtocol: URLProtocol {
    enum Reply {
        case response(status: Int, body: Data)
        case nonHTTP
        case fail(URLError.Code)
    }
    static var reply: Reply = .response(status: 200, body: Data())

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        switch Self.reply {
        case let .response(status, body):
            let http = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!
            client?.urlProtocol(self, didReceive: http, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: body)
            client?.urlProtocolDidFinishLoading(self)
        case .nonHTTP:
            let response = URLResponse(url: request.url!, mimeType: nil, expectedContentLength: 0, textEncodingName: nil)
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocolDidFinishLoading(self)
        case let .fail(code):
            client?.urlProtocol(self, didFailWithError: URLError(code))
        }
    }

    override func stopLoading() {}
}

final class RttProviderTests: XCTestCase {
    private let waterloo = Station(id: "WAT", name: "London Waterloo", latitude: 51.5031, longitude: -0.1132)

    private func makeProvider() -> RttProvider {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [StubURLProtocol.self]
        return RttProvider(accessToken: "test", session: URLSession(configuration: configuration))
    }

    private func reply(_ status: Int, _ body: String = "") {
        StubURLProtocol.reply = .response(status: status, body: Data(body.utf8))
    }

    private func thrownError(_ operation: () async throws -> Void) async -> Error? {
        do { try await operation(); return nil } catch { return error }
    }

    private func serviceDetailsError() async -> Error? {
        let provider = makeProvider()
        return await thrownError {
            _ = try await provider.serviceDetails(serviceId: "gb-nr:L01525:2026-09-13", date: Date())
        }
    }

    private func fixtureData(_ name: String) throws -> Data {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: name, withExtension: "json", subdirectory: "Fixtures"))
        return try Data(contentsOf: url)
    }

    func testSearchStationsIsNotImplemented() async {
        let provider = RttProvider(accessToken: "test")
        do {
            _ = try await provider.searchStations(query: "Reading")
            XCTFail("expected ProviderError.notImplemented")
        } catch let error as ProviderError {
            guard case .notImplemented = error else {
                return XCTFail("expected notImplemented, got \(error)")
            }
        } catch {
            XCTFail("unexpected error \(error)")
        }
    }

    func testServiceDetailsParsesAGoodResponse() async throws {
        StubURLProtocol.reply = .response(status: 200, body: try fixtureData("gb-nr-service"))
        let service = try await makeProvider().serviceDetails(serviceId: "x", date: Date())
        XCTAssertEqual(service.id, "gb-nr:L01525:2026-09-13")
    }

    func testHttpErrorsCarryTheStatusCode() async {
        for status in [401, 404, 429, 500] {  // 401 bad token, 429 rate limit
            reply(status)
            let error = await serviceDetailsError()
            XCTAssertEqual(error as? ProviderError, .http(status))
        }
    }

    func testTransportFailureIsNetwork() async {
        StubURLProtocol.reply = .fail(.notConnectedToInternet)
        let error = await serviceDetailsError()
        guard case .network? = error as? ProviderError else { return XCTFail("expected network, got \(String(describing: error))") }
    }

    func testNonHTTPResponseIsNetwork() async {
        StubURLProtocol.reply = .nonHTTP
        let error = await serviceDetailsError()
        guard case .network? = error as? ProviderError else { return XCTFail("expected network, got \(String(describing: error))") }
    }

    func testCancelledRequestIsCancellationErrorNotNetwork() async {
        StubURLProtocol.reply = .fail(.cancelled)
        let error = await serviceDetailsError()
        XCTAssertTrue(error is CancellationError, "got \(String(describing: error))")
    }

    func testNonJsonBodyIsMalformed() async {
        reply(200, "<html>oops</html>")
        let error = await serviceDetailsError()
        guard case .malformed? = error as? ProviderError else { return XCTFail("expected malformed, got \(String(describing: error))") }
    }

    func testEmptyOkBodyIsMalformed() async {
        reply(200, "")
        let error = await serviceDetailsError()
        guard case .malformed? = error as? ProviderError else { return XCTFail("expected malformed, got \(String(describing: error))") }
    }

    func testJsonArrayBodyIsMalformed() async {
        reply(200, "[]")
        let error = await serviceDetailsError()
        guard case .malformed? = error as? ProviderError else { return XCTFail("expected malformed, got \(String(describing: error))") }
    }

    func testMissingServiceObjectIsMalformed() async {
        reply(200, "{}")
        let error = await serviceDetailsError()
        XCTAssertEqual(error as? ProviderError, .malformed("missing 'service' object"))
    }

    func testServiceDetails204IsMalformed() async {
        reply(204)
        let error = await serviceDetailsError()
        XCTAssertEqual(error as? ProviderError, .malformed("empty (204) response"))
    }

    func testDepartureBoard204IsAnEmptyBoardNotAnError() async throws {
        reply(204)
        let board = try await makeProvider().departureBoard(station: waterloo, from: Date())
        XCTAssertEqual(board, [])
    }

    func testDepartureBoardReturnsEveryLineUp() async throws {
        StubURLProtocol.reply = .response(status: 200, body: try fixtureData("gb-nr-location"))
        let board = try await makeProvider().departureBoard(station: waterloo, from: Date())
        XCTAssertEqual(board.map(\.id), ["gb-nr:L02001:2026-09-13", "gb-nr:L02002:2026-09-13"])
    }

    func testDepartureBoardWithNoServicesKeyIsEmpty() async throws {
        reply(200, "{}")
        let board = try await makeProvider().departureBoard(station: waterloo, from: Date())
        XCTAssertEqual(board, [])
    }
}
