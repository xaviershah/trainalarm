import Foundation

/// The RTT bearer token, set at build time for the developer's own devices only. RTT forbids a
/// token in a distributable app (spec section 7), so builds that contain one are never shared.
enum RttToken {
    /// nil for nothing, whitespace, or the unsubstituted build-setting placeholder.
    static func parse(_ raw: Any?) -> String? {
        guard let text = (raw as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
              !text.isEmpty, text != "$(RTT_ACCESS_TOKEN)" else { return nil }
        return text
    }

    static var current: String? { parse(Bundle.main.object(forInfoDictionaryKey: "RTTAccessToken")) }
}
