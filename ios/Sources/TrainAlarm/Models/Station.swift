import Foundation

/// A physical station. `id` is provider-specific (e.g. a CRS code for UK
/// rail) - treat it as an opaque identifier, not a fixed format, since a
/// future non-UK provider will use a different scheme.
struct Station: Identifiable, Equatable, Hashable {
    let id: String
    let name: String
    let latitude: Double
    let longitude: Double
}
