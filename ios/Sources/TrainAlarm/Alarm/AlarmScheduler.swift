import Foundation

/// Sets the one alarm for a journey. Real implementation: `AlarmKitScheduler`. Tests use a fake.
protocol AlarmScheduler {
    /// Replaces any scheduled alarm with one at `fireAt` (idempotent). Throws if the OS
    /// refuses, for example when authorization is denied.
    func schedule(fireAt: Date) async throws
    func cancel() async
}
