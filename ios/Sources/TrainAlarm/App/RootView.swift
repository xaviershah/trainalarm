import SwiftUI

struct RootView: View {
    let environment: AppEnvironment
    @State private var showingSettings = false
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        ThemedRoot(settings: environment.settings) {
            ZStack {
                switch environment.model.phase {
                case .selecting, .confirming:
                    HomeView(model: environment.model, liveDataSetUp: environment.liveDataSetUp,
                             isDemo: environment.isDemo) { showingSettings = true }
                case .tracking:
                    TrackingView(model: environment.model)
                case .ringing:
                    RingingView(model: environment.model)
                case .ended(let reason):
                    EndedView(model: environment.model, reason: reason)
                }
            }
            .task {
                await environment.applyScenario?()
                if environment.opensSettings { showingSettings = true }
            }
            .task {
                // Real builds only: the demo alarm scheduler never rings.
                guard !environment.isDemo else { return }
                for await _ in AlarmRingSignal.stream() { environment.model.alarmRang() }
            }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active { environment.model.appBecameActive(now: Date()) }
            }
            .sheet(isPresented: $showingSettings) { SettingsView(model: environment.model, settings: environment.settings) }
        }
    }
}
