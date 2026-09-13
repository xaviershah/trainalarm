import Foundation

/// A specific train running on a specific day. `id` and `operatorName` are
/// provider-specific (e.g. an RTT service UID + TOC name for UK rail).
struct Service: Equatable {
    let id: String
    let operatorName: String
    let journey: Journey
}
