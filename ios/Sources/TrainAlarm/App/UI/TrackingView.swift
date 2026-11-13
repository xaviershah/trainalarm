import SwiftUI

struct TrackingView: View {
    @Bindable var model: SessionModel
    @Environment(\.colors) private var c

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            content(now: context.date)
        }
        .background(c.bg.ignoresSafeArea())
    }

    private func content(now: Date) -> some View {
        let display = model.display
        return ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                hero(display, now)
                alarmCard(display)
                if let progress = routeProgress(now) { RouteStripView(progress: progress, from: model.from?.name ?? "", to: model.to?.name ?? "") }
                ForEach(TrackingCards.make(display: display, destination: model.to?.name ?? "Your stop"), id: \.title) { StateCardView(card: $0) }
                Text("Keep the app open for live updates.").font(.footnote).foregroundStyle(c.muted)
            }
            .padding(18)
        }
        .safeAreaInset(edge: .bottom) {
            BigButton(title: "I'm off") { Task { await model.imOff() } }.padding(18).background(c.bg)
        }
    }

    private func routeProgress(_ now: Date) -> RouteProgress? {
        guard let confirm = model.confirm, let from = model.from, let to = model.to else { return nil }
        return RouteProgress.make(stops: confirm.service.journey.stops, from: from.id, to: to.id, eta: model.display.eta, now: now)
    }

    private func hero(_ d: TrackingDisplay, _ now: Date) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(model.to?.name ?? "").font(.anton(26)).textCase(.uppercase).foregroundStyle(c.onTeal)
            if let eta = d.eta {
                DotTime(hm: TimeText.countdown(eta.timeIntervalSince(now)), size: 84, color: c.onTeal)
                HStack(spacing: 10) {
                    Text("to go. Arrives").font(.footnote).foregroundStyle(c.onTeal)
                    DotTime(hm: TimeText.clock(eta), size: 22, color: c.onTeal)
                    // The onTeal pill would vanish on the teal card, so "early" uses the plain (surface) pill.
                    if let delay = d.delayMinutes, delay > 0 { Pill(text: "\(delay) min late", style: .late) }
                    else if let delay = d.delayMinutes, delay < 0 { Pill(text: "\(-delay) min early", style: .plain) }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(18)
        .background(c.teal, in: RoundedRectangle(cornerRadius: 28))
    }

    private func alarmCard(_ d: TrackingDisplay) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text("Alarm").font(.caption).foregroundStyle(c.muted)
                if let alarm = d.alarmAt { DotTime(hm: TimeText.clock(alarm), size: 34, color: c.head) }
                if let lead = model.confirm?.leadMinutes {
                    Text("\(lead) minutes before arrival").font(.footnote).foregroundStyle(c.muted)
                }
            }
            Spacer()
            if d.alarmNotSet { Pill(text: "NOT SET", style: .bad) }
            else { Text("Set").font(.subheadline.bold()).foregroundStyle(c.tealText) }
        }
        .padding(.horizontal, 14).padding(.vertical, 12)
        .background(c.surface, in: RoundedRectangle(cornerRadius: 16))
        .accessibilityElement(children: .combine)
    }
}

struct StateCardView: View {
    let card: StateCard
    @Environment(\.colors) private var c

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(card.title).font(prominent ? .anton(24) : .headline).textCase(prominent ? .uppercase : nil).foregroundStyle(titleColor)
            Text(card.detail).font(.footnote).foregroundStyle(detailColor)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 16).padding(.vertical, 14)
        .background(background, in: RoundedRectangle(cornerRadius: 18))
        .overlay { if card.tone == .error { RoundedRectangle(cornerRadius: 18).stroke(c.bad, lineWidth: 1.5) } }
        .accessibilityElement(children: .combine)
    }

    private var prominent: Bool { card.tone == .warn || card.tone == .info }
    private var titleColor: Color {
        switch card.tone { case .warn: return c.onAccent; case .info: return c.onTeal; case .error, .plain: return c.head }
    }
    private var detailColor: Color {
        switch card.tone { case .warn: return c.onAccent; case .info: return c.onTeal; case .error, .plain: return c.text }
    }
    private var background: Color {
        switch card.tone { case .warn: return c.accent; case .info: return c.teal; case .error, .plain: return c.surface }
    }
}

struct RouteStripView: View {
    let progress: RouteProgress
    let from: String
    let to: String
    @Environment(\.colors) private var c

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ProgressTrack(fraction: progress.fraction).frame(height: 22)
            HStack {
                Text(from)
                Spacer()
                Text(to)
            }
            .font(.caption).foregroundStyle(c.muted)
            if let next = progress.nextStop {
                HStack {
                    Text("Next stop").foregroundStyle(c.muted)
                    Text(next.station.name).bold().foregroundStyle(c.head)
                    Spacer()
                    if let time = next.bestArrival { DotTime(hm: TimeText.clock(time), size: 20, color: c.head) }
                }
                .font(.subheadline)
            }
            Text(progress.stopsLeft == 1 ? "1 stop to go" : "\(progress.stopsLeft) stops to go").font(.footnote).foregroundStyle(c.muted)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label)
    }

    private var label: String {
        var parts = ["Route from \(from) to \(to)", "\(Int((progress.fraction * 100).rounded())) percent of the way"]
        if let next = progress.nextStop {
            parts.append("Next stop \(next.station.name)" + (next.bestArrival.map { " at \(TimeText.clock($0).plain)" } ?? ""))
        }
        parts.append(progress.stopsLeft == 1 ? "1 stop to go" : "\(progress.stopsLeft) stops to go")
        return parts.joined(separator: ". ")
    }
}

private struct ProgressTrack: View {
    let fraction: Double
    @Environment(\.colors) private var c

    var body: some View {
        Canvas { context, size in
            let filledUntil = size.width * CGFloat(fraction)
            var x: CGFloat = 0.75
            while x < size.width {
                context.fill(Path(CGRect(x: x, y: 0, width: 1.5, height: size.height)), with: .color(x < filledUntil ? c.teal : c.muted))
                x += 7
            }
            context.fill(Path(CGRect(x: max(0, min(size.width - 3, filledUntil - 1.5)), y: -3, width: 3, height: size.height + 6)), with: .color(c.accent))
        }
        .accessibilityHidden(true)
    }
}

struct EndedView: View {
    let model: SessionModel
    let reason: EndReason
    @Environment(\.colors) private var c

    var body: some View {
        VStack(spacing: 16) {
            Spacer()
            StateCardView(card: card)
            Spacer()
            BigButton(title: "Back to start") { model.returnHome() }
        }
        .padding(18)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(c.bg.ignoresSafeArea())
    }

    private var card: StateCard {
        switch reason {
        case .arrived:
            return StateCard(tone: .info, title: "Arrived", detail: "\(model.to?.name ?? "Your stop"). Tracking has stopped and the alarm is cleared.")
        case .imOff:
            return StateCard(tone: .plain, title: "Tracking stopped", detail: "Your alarm is cleared. Safe travels.")
        }
    }
}
