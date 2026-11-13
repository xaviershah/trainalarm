import os
import SwiftUI

@main
struct TrainAlarmApp: App {
    @State private var environment = AppEnvironment.make()

    init() {
        let missing = Fonts.missing()
        if !missing.isEmpty {
            Logger(subsystem: "com.trainalarm.app", category: "ui").warning("Fonts failed to load: \(missing.joined(separator: ", "))")
        }
    }

    var body: some Scene {
        WindowGroup { RootView(environment: environment) }
    }
}
