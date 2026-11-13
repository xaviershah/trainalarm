import SwiftUI
import UIKit

extension Color {
    init(hex: UInt32) {
        self.init(red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255, blue: Double(hex & 0xFF) / 255)
    }
}

/// The palette tokens as SwiftUI colours. Orange (`accent`) and teal are fills only (spec section 5).
struct AppColors {
    let bg, surface, surface2, text, head, muted: Color
    let accent, onAccent, teal, onTeal, tealText, bad: Color

    init(_ t: PaletteTokens) {
        bg = Color(hex: t.bg); surface = Color(hex: t.surface); surface2 = Color(hex: t.surface2)
        text = Color(hex: t.text); head = Color(hex: t.head); muted = Color(hex: t.muted)
        accent = Color(hex: t.accent); onAccent = Color(hex: t.onAccent); teal = Color(hex: t.teal)
        onTeal = Color(hex: t.onTeal); tealText = Color(hex: t.tealText); bad = Color(hex: t.bad)
    }

    static let dark = AppColors(Palette.dark)
    static let light = AppColors(Palette.light)
}

private struct AppColorsKey: EnvironmentKey { static let defaultValue = AppColors.dark }

extension EnvironmentValues {
    var colors: AppColors {
        get { self[AppColorsKey.self] }
        set { self[AppColorsKey.self] = newValue }
    }
}

extension Font {
    static func anton(_ size: CGFloat) -> Font { .custom("Anton-Regular", size: size) }
    /// The dot-matrix face (Doto, Bold instance). Times are drawn with `DotTime`, not plain `Text`.
    static func dots(_ size: CGFloat) -> Font { .custom("Doto-Black_Bold", size: size) }
}

enum Fonts {
    static let required = ["Anton-Regular", "Doto-Black_Bold"]

    /// Names of required fonts that failed to load (a missing font silently falls back to the system one).
    static func missing() -> [String] { required.filter { UIFont(name: $0, size: 12) == nil } }
}

/// Applies the theme: `preferredColorScheme` from the user's setting, and the matching colours below it.
struct ThemedRoot<Content: View>: View {
    let settings: SettingsStore
    @ViewBuilder let content: () -> Content

    var body: some View {
        Themed(content: content)
            .preferredColorScheme(settings.theme == .dark ? .dark : settings.theme == .light ? .light : nil)
    }
}

private struct Themed<Content: View>: View {
    let content: () -> Content
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        content().environment(\.colors, scheme == .dark ? AppColors.dark : AppColors.light)
    }
}
