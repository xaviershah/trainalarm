import SwiftUI
import UIKit

struct ConfirmSheet: View {
    @Bindable var model: SessionModel
    @Environment(\.colors) private var c

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Wake me before \(model.to?.name ?? "your stop")")
                .font(.anton(30)).textCase(.uppercase).foregroundStyle(c.head)
            if let confirm = model.confirm {
                if let arrival = confirm.arrival {
                    HStack(spacing: 14) {
                        AlarmDial(alarmAt: model.alarmAt, arrival: arrival, lead: confirm.leadMinutes).frame(width: 150, height: 150)
                        VStack(alignment: .leading, spacing: 6) {
                            Text("Alarm at").font(.caption).foregroundStyle(c.muted)
                            if let alarm = model.alarmAt { DotTime(hm: TimeText.clock(alarm), size: 42, color: c.head) }
                            else { Text("Choose minutes").font(.footnote).foregroundStyle(c.muted) }
                            Text("Arrives").font(.caption).foregroundStyle(c.muted)
                            DotTime(hm: TimeText.clock(arrival), size: 42, color: c.head.opacity(0.5))
                        }
                    }
                    LeadRuler(choices: SettingsStore.leadChoices, selected: confirm.leadMinutes) { model.setLead($0) }
                    if model.ringsStraightAway { notice("The alarm will ring straight away.") }
                } else {
                    notice("This train does not call at \(model.to?.name ?? "that station"). Pick another.")
                }
            }
            if let kind = model.blockedPermission {
                notice(kind == .alarm ? "Alarms are off for TrainAlarm. Allow them in Settings so it can wake you." : "A permission is off. Allow it in Settings to continue.")
            }
            ForEach(model.permissionWarnings, id: \.self) { kind in
                if kind == .location { notice("Location is off. Live tracking will rely on train data only.") }
            }
            BigButton(title: model.blockedPermission == nil ? "Start tracking" : "Allow alarms to continue",
                      isEnabled: model.canStart) {
                if model.blockedPermission != nil, let url = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(url)
                } else {
                    Task { await model.start() }
                }
            }
        }
        .padding(18)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .presentationDetents([.fraction(0.7), .large])
        .presentationBackground(c.surface)
    }

    private func notice(_ text: String) -> some View {
        Text(text).font(.footnote).foregroundStyle(c.text)
            .frame(maxWidth: .infinity, alignment: .leading).padding(12)
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(c.bad, lineWidth: 1.5))
    }
}
