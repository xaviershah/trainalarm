import SwiftUI

/// Stage 0 placeholder. Real navigation (journey select -> service picker ->
/// active journey -> alarm) lands in Stage 4, once the model/tracking/alarm
/// layers from Stages 1-3 exist. See docs/spec.md.
struct ContentView: View {
    var body: some View {
        VStack(spacing: 8) {
            Text("TrainAlarm")
                .font(.title)
            Text("Stage 0 scaffold — no journey logic yet.")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .padding()
    }
}

#Preview {
    ContentView()
}
