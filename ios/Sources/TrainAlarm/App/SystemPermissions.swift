import CoreLocation
import Foundation

enum PermissionMapping {
    static func status(_ authorization: AlarmAuthorization) -> PermissionStatus {
        switch authorization {
        case .authorized: return .granted
        case .denied: return .denied
        case .notDetermined: return .notDetermined
        }
    }

    static func status(_ authorization: CLAuthorizationStatus) -> PermissionStatus {
        switch authorization {
        case .authorizedAlways, .authorizedWhenInUse: return .granted
        case .denied, .restricted: return .denied
        case .notDetermined: return .notDetermined
        @unknown default: return .denied
        }
    }
}

/// The real iOS permissions: AlarmKit (required) and location while in use (optional). Notification
/// and full-screen-intent permissions are Android only, so they are always granted here.
@MainActor
final class SystemPermissions: NSObject, PermissionsGateway, CLLocationManagerDelegate {
    var requiredKinds: [PermissionKind] { [.alarm] }
    var optionalKinds: [PermissionKind] { [.location] }

    private let alarmClient: AlarmKitClient
    private let manager = CLLocationManager()
    private var pending: CheckedContinuation<PermissionStatus, Never>?

    init(alarmClient: AlarmKitClient = SystemAlarmKitClient()) {
        self.alarmClient = alarmClient
        super.init()
        manager.delegate = self
    }

    func status(_ kind: PermissionKind) -> PermissionStatus {
        switch kind {
        case .alarm: return PermissionMapping.status(alarmClient.authorization)
        case .location: return PermissionMapping.status(manager.authorizationStatus)
        case .notifications, .fullScreenIntent: return .granted
        }
    }

    func request(_ kind: PermissionKind) async -> PermissionStatus {
        switch kind {
        case .alarm:
            guard let result = try? await alarmClient.requestAuthorization() else { return .denied }
            return PermissionMapping.status(result)
        case .location:
            guard manager.authorizationStatus == .notDetermined else { return status(.location) }
            return await withCheckedContinuation { continuation in
                pending = continuation
                manager.requestWhenInUseAuthorization()
            }
        case .notifications, .fullScreenIntent:
            return .granted
        }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        Task { @MainActor in
            let status = self.manager.authorizationStatus
            guard status != .notDetermined, let continuation = self.pending else { return }
            self.pending = nil
            continuation.resume(returning: PermissionMapping.status(status))
        }
    }
}
