import AlarmKit
import SwiftUI

enum AlarmAuthorization: Equatable { case authorized, denied, notDetermined }

/// The only thing `AlarmKitScheduler` knows about AlarmKit, so its logic is testable with a fake.
protocol AlarmKitClient {
    var authorization: AlarmAuthorization { get }
    func requestAuthorization() async throws -> AlarmAuthorization
    func schedule(id: UUID, fireAt: Date, title: String) async throws
    func cancel(id: UUID) throws
}

struct AlarmKitMetadata: AlarmMetadata {}

/// Real AlarmKit (iOS 26+). Scheduling and cancelling were verified on a device (Stage 3); the stop-button
/// text and tint are not yet verified on a device. Not covered by unit tests.
struct SystemAlarmKitClient: AlarmKitClient {
    private var manager: AlarmManager { AlarmManager.shared }

    var authorization: AlarmAuthorization { Self.map(manager.authorizationState) }

    func requestAuthorization() async throws -> AlarmAuthorization {
        Self.map(try await manager.requestAuthorization())
    }

    func schedule(id: UUID, fireAt: Date, title: String) async throws {
        let stop = AlarmButton(text: LocalizedStringResource(stringLiteral: AlarmWording.stopText),
                               textColor: .white, systemImageName: "stop.circle")
        let alert = AlarmPresentation.Alert(title: LocalizedStringResource(stringLiteral: title), stopButton: stop)
        let attributes = AlarmAttributes<AlarmKitMetadata>(presentation: AlarmPresentation(alert: alert),
                                                           tintColor: AlarmWording.tint)
        let configuration = AlarmManager.AlarmConfiguration<AlarmKitMetadata>(
            countdownDuration: nil, schedule: .fixed(fireAt), attributes: attributes,
            stopIntent: nil, secondaryIntent: nil)
        _ = try await manager.schedule(id: id, configuration: configuration)
    }

    func cancel(id: UUID) throws { try manager.cancel(id: id) }

    private static func map(_ state: AlarmManager.AuthorizationState) -> AlarmAuthorization {
        switch state {
        case .authorized: return .authorized
        case .denied: return .denied
        case .notDetermined: return .notDetermined
        @unknown default: return .denied
        }
    }
}
