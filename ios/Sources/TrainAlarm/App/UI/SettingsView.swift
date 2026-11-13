import SwiftUI

struct SettingsView: View {
    let model: SessionModel
    let settings: SettingsStore
    @Environment(\.colors) private var c
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    #if DEBUG
    @State private var showingDevHarness = false
    #endif

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                HStack {
                    Text("Settings").font(.anton(34)).textCase(.uppercase).foregroundStyle(c.head)
                    Spacer()
                    Button("Done") { dismiss() }.font(.headline).foregroundStyle(c.head)
                }
                section("Appearance") {
                    Picker("Theme", selection: Binding(get: { settings.theme }, set: { settings.setTheme($0) })) {
                        Text("System").tag(ThemeChoice.system)
                        Text("Dark").tag(ThemeChoice.dark)
                        Text("Light").tag(ThemeChoice.light)
                    }
                    .pickerStyle(.segmented)
                }
                section("Alert time") {
                    LeadRuler(choices: SettingsStore.leadChoices, selected: settings.defaultLeadMinutes) { settings.setDefaultLeadMinutes($0) }
                    Text("Minutes before arrival. You can still change it for each journey.").font(.footnote).foregroundStyle(c.muted)
                }
                section("Permissions") {
                    ForEach(model.settingsPermissionKinds, id: \.self) { kind in permissionRow(kind) }
                }
                section("Test alarm") {
                    BigButton(title: "Test alarm in 10 seconds", style: .ghost, isEnabled: model.canTestAlarm) {
                        Task { await model.scheduleTestAlarm() }
                    }
                    testAlarmNote
                }
                section("About") {
                    Text("Station data: NaPTAN, Open Government Licence v3.0.").font(.footnote).foregroundStyle(c.text)
                    Text("Train times: Realtime Trains, realtimetrains.co.uk.").font(.footnote).foregroundStyle(c.text)
                    Text("Version \(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "")")
                        .font(.footnote).foregroundStyle(c.muted)
                }
                #if DEBUG
                Button("Developer alarm harness") { showingDevHarness = true }
                    .font(.footnote).foregroundStyle(c.muted)
                    .sheet(isPresented: $showingDevHarness) { DevAlarmView() }
                #endif
            }
            .padding(18)
        }
        .background(c.bg.ignoresSafeArea())
        .presentationBackground(c.bg)
        .onAppear { model.refreshTestAlarm() }
        .onChange(of: scenePhase) { _, phase in if phase == .active { model.refreshPermissions(); model.refreshTestAlarm() } }
    }

    private func section<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title).font(.anton(20)).textCase(.uppercase).foregroundStyle(c.head)
            VStack(alignment: .leading, spacing: 10) { content() }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(14)
                .background(c.surface, in: RoundedRectangle(cornerRadius: 18))
        }
    }

    private func permissionRow(_ kind: PermissionKind) -> some View {
        let status = model.permissionStatus(kind)
        return HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(Self.title(kind)).font(.subheadline.bold()).foregroundStyle(c.head)
                Text(Self.reason(kind)).font(.footnote).foregroundStyle(c.muted)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("\(Self.title(kind)). \(Self.reason(kind)) \(Self.statusWords(status)).")
            Spacer()
            if status == .granted {
                Pill(text: "On", style: .plain).accessibilityHidden(true)
            } else {
                Pill(text: Self.pillText(status), style: .bad).accessibilityHidden(true)
                Button("Fix") { fix(kind, status) }.font(.subheadline.bold()).foregroundStyle(c.tealText)
                    .accessibilityLabel("Fix \(Self.title(kind)) permission")
                    .accessibilityHint(status == .notDetermined ? "Asks for permission." : "Opens system settings.")
            }
        }
    }

    static func pillText(_ status: PermissionStatus) -> String { status == .denied ? "Off" : "Not asked" }

    static func statusWords(_ status: PermissionStatus) -> String {
        switch status {
        case .granted: return "Allowed"
        case .denied: return "Not allowed. Turn it on in system settings"
        case .notDetermined: return "Not asked yet"
        }
    }

    private func fix(_ kind: PermissionKind, _ status: PermissionStatus) {
        if status == .notDetermined {
            Task { await model.requestPermission(kind) }
        } else if let url = URL(string: UIApplication.openSettingsURLString) {
            UIApplication.shared.open(url)
        }
    }

    @ViewBuilder private var testAlarmNote: some View {
        switch model.testAlarm {
        case .idle:
            if !model.canTestAlarm { Text("Not while a journey is running.").font(.footnote).foregroundStyle(c.muted) }
        case .scheduled(let at):
            HStack(spacing: 6) {
                Text("Rings at").font(.footnote).foregroundStyle(c.muted)
                DotTime(hm: TimeText.clock(at), size: 18, color: c.head)
            }
        case .failed:
            Text("Could not set the test alarm. Check the permissions above.").font(.footnote).foregroundStyle(c.bad)
        }
    }

    static func title(_ kind: PermissionKind) -> String {
        switch kind {
        case .alarm: return "Alarms"
        case .notifications: return "Notifications"
        case .location: return "Location"
        case .fullScreenIntent: return "Full-screen alerts"
        }
    }

    static func reason(_ kind: PermissionKind) -> String {
        switch kind {
        case .alarm: return "Needed to wake you."
        case .notifications: return "Needed to show the alarm."
        case .location: return "Sharpens the arrival time. Optional."
        case .fullScreenIntent: return "Lets the alarm take over the lock screen. Optional."
        }
    }
}
