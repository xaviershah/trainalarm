#if DEBUG
import SwiftUI

/// Throwaway dev harness for the Stage 3 device checklist. Not product UI (Stage 4).
struct DevAlarmView: View {
    @State private var seconds = "60"
    @State private var status = "Idle"
    @State private var lastRing = "none yet"
    @State private var scheduler = AlarmKitScheduler(client: SystemAlarmKitClient())
    private let client = SystemAlarmKitClient()

    var body: some View {
        Form {
            Section("Dev alarm (debug builds only)") {
                TextField("Seconds from now", text: $seconds).keyboardType(.numberPad)
                Button("Schedule alarm") { Task { await schedule(after: Double(seconds) ?? 60) } }
                Button("Schedule 30 s in the past") { Task { await schedule(after: -30) } }
                Button("Cancel alarm", role: .destructive) {
                    Task { await scheduler.cancel(); status = "Cancelled" }
                }
                Button("Check authorization") { status = "Authorization: \(client.authorization)" }
                Text(status).font(.footnote).foregroundStyle(.secondary)
                Text("Ring signal: \(lastRing)").font(.footnote).foregroundStyle(.secondary)
            }
        }
        .task { for await _ in AlarmRingSignal.stream() { lastRing = Date().formatted(date: .omitted, time: .standard) } }
    }

    private func schedule(after delay: TimeInterval) async {
        let fireAt = Date().addingTimeInterval(delay)
        do {
            try await scheduler.schedule(fireAt: fireAt)
            status = "Scheduled for \(fireAt.formatted(date: .omitted, time: .standard)) (asked for \(Int(delay)) s)"
        } catch {
            status = "Error: \(error)"
        }
    }
}
#endif
