import SwiftUI

struct HomeView: View {
    @Bindable var model: SessionModel
    let liveDataSetUp: Bool
    let isDemo: Bool
    let openSettings: () -> Void
    @Environment(\.colors) private var c
    @State private var picking: Field?

    enum Field: Identifiable {
        case from, to
        var id: Self { self }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                HStack {
                    Text("TrainAlarm").font(.anton(20)).textCase(.uppercase).foregroundStyle(c.head)
                    if isDemo { Pill(text: "Demo data") }
                    Spacer()
                    Button(action: openSettings) {
                        Image(systemName: "gearshape").font(.title3).foregroundStyle(c.head)
                            .frame(width: 40, height: 40).background(c.surface, in: Circle())
                    }
                    .accessibilityLabel("Settings")
                }
                Text("Where to?").font(.anton(58)).textCase(.uppercase).foregroundStyle(c.head)
                stationField("From", model.from) { picking = .from }
                if model.from == nil { nearestChip }
                stationField("To", model.to) { picking = .to }
                if liveDataSetUp { BoardSection(model: model) } else { setupCard }
            }
            .padding(18)
        }
        .refreshable { await model.loadBoard() }   // pull to refresh; the model's 20 s cooldown still applies
        .background(c.bg.ignoresSafeArea())
        .task(id: "\(model.from?.id ?? "")-\(model.to?.id ?? "")") { await model.loadBoard() }
        .sheet(item: $picking) { field in StationSearchSheet(model: model, isFrom: field == .from) }
        .sheet(isPresented: Binding(get: { model.phase == .confirming }, set: { if !$0 { model.backToSelecting() } })) {
            ConfirmSheet(model: model)
        }
    }

    private func stationField(_ title: String, _ station: Station?, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.caption).foregroundStyle(c.muted)
                Text(station?.name ?? "Choose a station").font(.title3.bold()).foregroundStyle(station == nil ? c.muted : c.head)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16).padding(.vertical, 12)
            .background(c.surface, in: RoundedRectangle(cornerRadius: 18))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(title): \(station?.name ?? "not chosen")")
    }

    private var nearestChip: some View {
        VStack(alignment: .leading, spacing: 6) {
            Button { Task { await model.useNearestStation() } } label: {
                Text(model.isFindingNearest ? "Finding your nearest station..." : "Use my nearest station")
                    .font(.footnote).foregroundStyle(c.text)
                    .padding(.horizontal, 12).padding(.vertical, 5)
                    .overlay(Capsule().stroke(c.muted.opacity(0.6), lineWidth: 1.5))
            }
            .buttonStyle(.plain)
            .disabled(model.isFindingNearest)
            if let failure = model.nearestFailure {
                Text(failure == .permissionDenied ? "Location is off. Search for your station instead."
                                                  : "Could not find you yet. Search for your station instead.")
                    .font(.footnote).foregroundStyle(c.muted)
            }
        }
    }

    private var setupCard: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Live data is not set up").font(.headline).foregroundStyle(c.head)
            Text("This build has no train data token, so it cannot show departures yet.").font(.footnote).foregroundStyle(c.text)
        }
        .frame(maxWidth: .infinity, alignment: .leading).padding(16)
        .background(c.surface, in: RoundedRectangle(cornerRadius: 18))
    }
}

private struct BoardSection: View {
    @Bindable var model: SessionModel
    @Environment(\.colors) private var c

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            switch model.board {
            case .idle:
                Text("Choose where you are and where you are going.").font(.footnote).foregroundStyle(c.muted)
            case .loading:
                ProgressView().frame(maxWidth: .infinity).padding(.top, 12)
            case .loaded(let services):
                Text("Trains from \(model.from?.name ?? "") calling at \(model.to?.name ?? "")").font(.footnote.bold()).foregroundStyle(c.muted)
                ForEach(services, id: \.id) { service in
                    Button { Task { await model.choose(service) } } label: { BoardRow(service: service, now: Date()) }
                        .buttonStyle(.plain)
                }
            case .empty:
                message("No trains found", "No trains call at \(model.to?.name ?? "that station") soon. Try again in a few minutes.")
            case .failed(let text):
                message("Could not load trains", text)
            case .rateLimited:
                message("The train data is busy", "Wait a moment, then try again.", force: false)
            }
        }
    }

    /// `force: false` keeps the cooldown, so a rate-limited board is not hit again at once.
    private func message(_ title: String, _ detail: String, force: Bool = true) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.headline).foregroundStyle(c.head)
            Text(detail).font(.footnote).foregroundStyle(c.text)
            BigButton(title: "Try again", style: .ghost) { Task { await model.loadBoard(force: force) } }
        }
        .frame(maxWidth: .infinity, alignment: .leading).padding(16)
        .background(c.surface, in: RoundedRectangle(cornerRadius: 18))
    }
}

private struct BoardRow: View {
    let service: Service
    let now: Date
    @Environment(\.colors) private var c

    var body: some View {
        let stop = service.journey.stops[0]
        let departure = stop.estimatedDeparture ?? stop.scheduledDeparture
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                if let departure {
                    DotTime(hm: TimeText.clock(departure), size: 34, color: c.head)
                    if let label = TimeText.dateLabel(for: departure, now: now) { Text(label).font(.caption2).foregroundStyle(c.muted) }
                }
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(service.operatorName).font(.subheadline.bold()).foregroundStyle(c.text)
                Text("towards \(service.journey.destination.name)").font(.footnote).foregroundStyle(c.muted)
            }
            Spacer()
            status(stop)
        }
        .padding(.horizontal, 14).padding(.vertical, 12)
        .frame(maxWidth: .infinity)
        .background(c.surface, in: RoundedRectangle(cornerRadius: 16))
        .opacity(stop.isCancelled ? 0.8 : 1)
    }

    @ViewBuilder
    private func status(_ stop: Stop) -> some View {
        if stop.isCancelled {
            Pill(text: "Cancelled", style: .bad)
        } else if let scheduled = stop.scheduledDeparture, let estimated = stop.estimatedDeparture,
                  Int((estimated.timeIntervalSince(scheduled) / 60).rounded()) > 0 {
            Pill(text: "+\(Int((estimated.timeIntervalSince(scheduled) / 60).rounded())) min", style: .late)
        } else {
            Pill(text: "On time")
        }
    }
}
