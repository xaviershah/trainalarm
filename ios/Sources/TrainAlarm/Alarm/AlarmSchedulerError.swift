/// Why the real scheduler refused. AlarmKit's own errors (e.g. `maximumLimitReached`) pass through unchanged.
enum AlarmSchedulerError: Error, Equatable {
    /// The user has not allowed alarms for this app (Settings → TrainAlarm).
    case notAuthorized
}
