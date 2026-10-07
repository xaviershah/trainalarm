import Foundation

/// How a `TrainDataProvider` call can fail. Identical on every provider and on
/// both platforms, so tracking code can treat "the feed is down" the same
/// everywhere. Cancellation is NOT an error: it propagates as `CancellationError`.
enum ProviderError: Error, Equatable {
    /// Transport failure: no connection, timeout, DNS, TLS, or a non-HTTP response.
    case network(String)
    /// Non-2xx HTTP status.
    case http(Int)
    /// Body was not valid JSON, was empty on a 200, or a required field was missing.
    case malformed(String)
    /// A capability the provider deliberately lacks (e.g. free-text station search).
    case notImplemented(String)
}
