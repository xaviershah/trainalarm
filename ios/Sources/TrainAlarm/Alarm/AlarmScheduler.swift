import Foundation

/// Sets the one alarm for a journey. Stage 3 provides the real implementation (AlarmKit);
/// until then tests use a fake.
protocol AlarmScheduler {
    /// Replaces any scheduled alarm with one at `fireAt` (idempotent). Throws if the OS
    /// refuses, for example when authorization is denied.
    func schedule(fireAt: Date) async throws
    func cancel() async
}
