import Foundation

enum PermissionKind: Equatable, Hashable { case alarm, notifications, location, fullScreenIntent }
enum PermissionStatus: Equatable { case granted, denied, notDetermined }

/// What the app asks the OS for. The real implementation is `SystemPermissions`; tests use a fake. Main actor only: the OS prompts and `CLLocationManager` need it.
@MainActor
protocol PermissionsGateway {
    /// Refusing any of these blocks Start.
    var requiredKinds: [PermissionKind] { get }
    /// Refusing any of these only shows a warning.
    var optionalKinds: [PermissionKind] { get }
    func status(_ kind: PermissionKind) -> PermissionStatus
    /// Shows the system prompt when the status is `notDetermined`; returns the resulting status.
    func request(_ kind: PermissionKind) async -> PermissionStatus
}

/// Starts and stops GPS updates. The tracker only reads `LocationSource.latestFix()`, so something
/// has to start the updates: the model does, once location is allowed.
protocol LocationControl {
    func start()
    func stop()
}

extension CoreLocationSource: LocationControl {}
