import SwiftUI

/// Theme-independent: the dark core with an orange and teal glow, the countdown and one big button.
struct RingingView: View {
    let model: SessionModel
    private let c = AppColors.dark
    private let cream = Color(hex: 0xF3ECE3)

    var body: some View {
        ZStack {
            c.bg.ignoresSafeArea()
            glow.ignoresSafeArea()
            TimelineView(.periodic(from: .now, by: 1)) { context in content(now: context.date) }
        }
        .preferredColorScheme(.dark)   // keeps the status bar light on the dark core in a light-theme app
    }

    private var glow: some View {
        ZStack {
            RadialGradient(colors: [c.accent.opacity(0.55), .clear], center: .init(x: 0.3, y: 0.35), startRadius: 8, endRadius: 360)
            RadialGradient(colors: [c.teal.opacity(0.6), .clear], center: .init(x: 0.75, y: 0.65), startRadius: 8, endRadius: 380)
        }
    }

    private func content(now: Date) -> some View {
        VStack(spacing: 18) {
            Spacer()
            Text(AlarmWording.title).font(.anton(40)).textCase(.uppercase).foregroundStyle(cream)
            if let name = model.to?.name {
                Text(name).font(.anton(24)).textCase(.uppercase).foregroundStyle(cream.opacity(0.85))
            }
            if let eta = model.display.eta, eta > now {
                DotTime(hm: TimeText.countdown(eta.timeIntervalSince(now)), size: 110, color: cream)
                Text("to go").font(.subheadline).foregroundStyle(cream.opacity(0.8))
            } else if model.display.eta != nil {
                Text("Arriving now").font(.anton(30)).textCase(.uppercase).foregroundStyle(cream)
            }
            Spacer()
            BigButton(title: AlarmWording.stopText, style: .cream) { Task { await model.imOff() } }
        }
        .padding(24)
        .accessibilityElement(children: .contain)
    }
}
