import SwiftUI

/// Two dots, drawn by us: the Doto colon glyph reads as a plus sign.
struct ColonDots: View {
    let size: CGFloat

    var body: some View {
        VStack(spacing: size * 0.16) {
            Circle().frame(width: size * 0.13, height: size * 0.13)
            Circle().frame(width: size * 0.13, height: size * 0.13)
        }
        .frame(width: size * 0.3)
    }
}

/// A time or countdown in the dot-matrix face.
struct DotTime: View {
    let hm: TimeText.HM
    let size: CGFloat
    let color: Color

    var body: some View {
        HStack(spacing: 0) {
            Text(hm.hours).font(.dots(size))
            ColonDots(size: size)
            Text(hm.minutes).font(.dots(size))
        }
        .lineLimit(1)
        .fixedSize()
        .foregroundStyle(color)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(hm.plain)
    }
}

struct Pill: View {
    enum Style: CaseIterable {
        case plain, late, bad, onTeal

        /// Text colour and fill (nil = no fill) from the palette tokens. Orange and teal are fills only,
        /// so neither is ever a text colour; teal fills carry `onTeal` text.
        func tokens(_ t: PaletteTokens) -> (foreground: UInt32, background: UInt32?) {
            switch self {
            case .plain: return (t.text, t.surface2)
            case .late: return (t.onAccent, t.accent)
            case .bad: return (t.bad, nil)
            case .onTeal: return (t.onTeal, t.teal)
            }
        }
    }
    let text: String
    var style: Style = .plain
    @Environment(\.colors) private var c
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let tokens = style.tokens(scheme == .dark ? Palette.dark : Palette.light)
        Text(text)
            .font(.caption.bold())
            .padding(.horizontal, 11).padding(.vertical, 4)
            .foregroundStyle(Color(hex: tokens.foreground))
            .background(tokens.background.map { Color(hex: $0) } ?? .clear, in: Capsule())
            .overlay { if style == .bad { Capsule().stroke(c.bad, lineWidth: 1.5) } }
            .fixedSize()
    }
}

struct BigButton: View {
    enum Style { case accent, ghost, cream }
    let title: String
    var style: Style = .accent
    var isEnabled = true
    let action: () -> Void
    @Environment(\.colors) private var c

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.anton(26)).textCase(.uppercase).tracking(0.5)
                .frame(maxWidth: .infinity).padding(.vertical, 16)
                .foregroundStyle(foreground)
                .background(background, in: RoundedRectangle(cornerRadius: 18))
                .overlay { if style == .ghost { RoundedRectangle(cornerRadius: 18).stroke(c.muted.opacity(0.5), lineWidth: 1.5) } }
        }
        .buttonStyle(.plain)
        .disabled(!isEnabled)
        .opacity(isEnabled ? 1 : 0.45)
    }

    private var foreground: Color {
        switch style {
        case .accent: return c.onAccent
        case .ghost: return c.head
        case .cream: return Color(hex: 0x201D1C)
        }
    }

    private var background: Color {
        switch style {
        case .accent: return c.accent
        case .ghost: return .clear
        case .cream: return Color(hex: 0xF3ECE3)
        }
    }
}

/// "1 minute", "7 minutes".
func minutesText(_ n: Int) -> String { n == 1 ? "1 minute" : "\(n) minutes" }

/// The line above the lead-time ruler: what is chosen, in words.
func leadValueLine(_ minutes: Int?) -> String {
    minutes.map { "\(minutesText($0)) before arrival" } ?? "Not chosen"
}

/// Where the ruler puts each choice, in points from its left edge. Pure, so it is tested without drawing:
/// the ticks, the marker, the labels and tap/drag all use these same positions.
struct RulerLayout: Equatable {
    let count: Int
    let width: CGFloat
    let inset: CGFloat

    var spacing: CGFloat { count > 1 ? max(0, width - inset * 2) / CGFloat(count - 1) : 0 }

    /// The x of the choice at `index`.
    func x(at index: Int) -> CGFloat { inset + spacing * CGFloat(index) }

    /// The choice nearest to `x`, clamped to the first and last.
    func index(at x: CGFloat) -> Int {
        guard count > 1, spacing > 0 else { return 0 }
        let raw = ((x - inset) / spacing).rounded()
        guard raw.isFinite else { return 0 }
        return Int(min(max(raw, 0), CGFloat(count - 1)))
    }

    /// Ticks at multiples of 5 are taller and carry a label.
    static func isMajor(_ minutes: Int) -> Bool { minutes % 5 == 0 }
}

/// Picks a lead time from `choices`: tap or drag along the tick ruler. The marker sits on the choice, and a line above
/// the ruler says what is chosen. One tick per choice; ticks at multiples of 5 are taller and labelled.
struct LeadRuler: View {
    /// The next choice up or down from `selected`, clamped at both ends. Nothing chosen starts at the first choice.
    static func step(_ choices: [Int], from selected: Int?, up: Bool) -> Int {
        let index = selected.flatMap { choices.firstIndex(of: $0) } ?? 0
        return choices[up ? min(choices.count - 1, index + 1) : max(0, index - 1)]
    }

    let choices: [Int]
    let selected: Int?
    let onSelect: (Int) -> Void
    @Environment(\.colors) private var c
    private let inset: CGFloat = 16

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(leadValueLine(selected)).font(.callout.weight(.semibold))
                .foregroundStyle(selected == nil ? c.muted : c.head)
            GeometryReader { geo in
                let layout = RulerLayout(count: choices.count, width: geo.size.width, inset: inset)
                VStack(spacing: 6) {
                    ZStack(alignment: .leading) {
                        Canvas { context, size in
                            for (index, minutes) in choices.enumerated() {
                                let height = size.height * (RulerLayout.isMajor(minutes) ? 0.52 : 0.36)
                                let rect = CGRect(x: layout.x(at: index) - 0.75, y: (size.height - height) / 2, width: 1.5, height: height)
                                context.fill(Path(rect), with: .color(c.muted))
                            }
                        }
                        if let selected, let index = choices.firstIndex(of: selected) {
                            Rectangle().fill(c.accent).frame(width: 3).offset(x: layout.x(at: index) - 1.5)
                        }
                    }
                    .frame(height: 44)
                    .background(c.surface2, in: RoundedRectangle(cornerRadius: 12))
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .contentShape(Rectangle())
                    .gesture(DragGesture(minimumDistance: 0).onChanged { value in
                        onSelect(choices[layout.index(at: value.location.x)])
                    })
                    ZStack(alignment: .leading) {
                        ForEach(Array(choices.enumerated()), id: \.element) { index, minutes in
                            if RulerLayout.isMajor(minutes) {
                                Text("\(minutes)").font(.dots(17)).foregroundStyle(minutes == selected ? c.head : c.muted)
                                    .position(x: layout.x(at: index), y: 9)
                            }
                        }
                    }
                    .frame(height: 18)
                }
            }
            .frame(height: 70)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Minutes before arrival")
        .accessibilityValue(selected.map(minutesText) ?? "not chosen")
        .accessibilityAdjustableAction { direction in
            onSelect(Self.step(choices, from: selected, up: direction == .increment))
        }
    }
}

/// A round dial: the alarm time (orange dot) and the arrival time (cream dot) at their minutes past the hour.
struct AlarmDial: View {
    let alarmAt: Date?
    let arrival: Date
    let lead: Int?
    @Environment(\.colors) private var c

    var body: some View {
        ZStack {
            Circle().fill(c.surface2)
            Canvas { context, size in
                let center = CGPoint(x: size.width / 2, y: size.height / 2)
                let radius = min(size.width, size.height) / 2
                for tick in 0..<60 {
                    let angle = Double(tick) * .pi / 30
                    var path = Path()
                    path.move(to: point(center, radius * 0.80, angle))
                    path.addLine(to: point(center, radius * 0.86, angle))
                    context.stroke(path, with: .color(c.muted), lineWidth: 1.4)
                }
                func dot(_ date: Date, _ color: Color) {
                    let minute = Double(TimeText.clock(date).minutes) ?? 0
                    let p = point(center, radius * 0.83, minute * .pi / 30)
                    context.fill(Path(ellipseIn: CGRect(x: p.x - 6, y: p.y - 6, width: 12, height: 12)), with: .color(color))
                }
                dot(arrival, c.head)
                if let alarmAt { dot(alarmAt, c.accent) }
            }
            Circle().fill(Color(hex: 0x201D1C)).padding(34)
            VStack(spacing: 0) {
                Text(lead.map(String.init) ?? "-").font(.dots(40)).foregroundStyle(Color(hex: 0xF3ECE3))
                Text("min before").font(.caption2.bold()).foregroundStyle(Color(hex: 0xF3ECE3))
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(lead.map { "Alarm \(minutesText($0)) before arrival" } ?? "No alarm time chosen")
    }

    private func point(_ center: CGPoint, _ radius: CGFloat, _ angle: Double) -> CGPoint {
        CGPoint(x: center.x + radius * CGFloat(sin(angle)), y: center.y - radius * CGFloat(cos(angle)))
    }
}
