import Foundation

/// Colour tokens as 0xRRGGBB. Same names and values on Android. See spec section 5.
struct PaletteTokens: Equatable {
    let bg, surface, surface2, text, head, muted: UInt32
    let accent, onAccent, teal, onTeal, tealText, bad: UInt32
}

enum Palette {
    static let dark = PaletteTokens(
        bg: 0x201D1C, surface: 0x2C2724, surface2: 0x3A322E, text: 0xCFC3B6, head: 0xF3ECE3, muted: 0xB7AA9C,
        accent: 0xF36509, onAccent: 0x201D1C, teal: 0x015D67, onTeal: 0xF3ECE3, tealText: 0x2FA3B1, bad: 0xFF8F7A)
    static let light = PaletteTokens(
        bg: 0xCFC3B6, surface: 0xE4DCD2, surface2: 0xF1EBE3, text: 0x201D1C, head: 0x201D1C, muted: 0x6C584C,
        accent: 0xF36509, onAccent: 0x201D1C, teal: 0x015D67, onTeal: 0xF3ECE3, tealText: 0x015D67, bad: 0xA32C1B)
}

/// WCAG 2.x contrast ratio between two 0xRRGGBB colours.
enum Contrast {
    static func ratio(_ a: UInt32, _ b: UInt32) -> Double {
        let (la, lb) = (luminance(a), luminance(b))
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }
    private static func luminance(_ hex: UInt32) -> Double {
        func channel(_ shift: UInt32) -> Double {
            let c = Double((hex >> shift) & 0xFF) / 255
            return c <= 0.03928 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
}
