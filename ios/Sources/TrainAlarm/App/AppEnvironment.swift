import Foundation

/// Builds the real app (RTT provider, AlarmKit, Core Location) or, in debug builds without a token or
/// with `-demo` or `-scenario <name>`, the demo app (spec section 6). Release builds never contain demo code.
@MainActor
struct AppEnvironment {
    let model: SessionModel
    let settings: SettingsStore
    /// False when there is no RTT token and this is not a demo: the picker says live data is not set up.
    let liveDataSetUp: Bool
    let isDemo: Bool
    let opensSettings: Bool
    let applyScenario: (() async -> Void)?

    static func make(arguments: [String] = ProcessInfo.processInfo.arguments) -> AppEnvironment {
        let settings = SettingsStore()
        let directory: StationDirectory
        do { directory = try StationDirectory.load() } catch {
            fatalError("The bundled station list is missing or malformed: \(error)")
        }
        let clock = SystemTrackerClock()
        let location = CoreLocationSource()
        let token = RttToken.current

        #if DEBUG
        let scenario = Scenario.from(arguments: arguments)
        if scenario != nil || token == nil || arguments.contains("-demo") {
            // The demo has its own clock (`-demo-speed N` makes it faster) and an alarm that tells the app when it rings.
            let demoClock = DemoClock(speed: Scenario.speed(from: arguments))
            let ring = RingBox()
            let model = SessionModel(
                provider: DirectoryBackedProvider(base: DemoProvider(clock: demoClock, underway: scenario?.trainIsUnderway ?? false), directory: directory),
                scheduler: DemoAlarmScheduler(clock: demoClock) { ring.handler?() }, directory: directory, location: location,
                clock: demoClock, permissions: DemoPermissions(), settings: settings, locationControl: location)
            ring.handler = { [weak model] in model?.alarmRang() }
            return AppEnvironment(
                model: model, settings: settings, liveDataSetUp: true, isDemo: true,
                opensSettings: scenario?.opensSettings ?? false,
                applyScenario: scenario.map { s in { await s.apply(to: model, settings: settings, directory: directory, now: Date()) } })
        }
        #endif

        let base: TrainDataProvider = token.map { RttProvider(accessToken: $0) } ?? UnavailableProvider()
        let model = SessionModel(
            provider: DirectoryBackedProvider(base: base, directory: directory),
            scheduler: AlarmKitScheduler(client: SystemAlarmKitClient()), directory: directory, location: location,
            clock: clock, permissions: SystemPermissions(), settings: settings, locationControl: location)
        return AppEnvironment(model: model, settings: settings, liveDataSetUp: token != nil, isDemo: false,
                              opensSettings: false, applyScenario: nil)
    }
}

/// Used when there is no RTT token in a non-demo build. Every call fails, and the picker says why.
struct UnavailableProvider: TrainDataProvider {
    func searchStations(query: String) async throws -> [Station] { throw ProviderError.notImplemented("Live data is not set up") }
    func departureBoard(station: Station, from: Date, callingAt: Station?) async throws -> [Service] {
        throw ProviderError.notImplemented("Live data is not set up")
    }
    func serviceDetails(serviceId: String, date: Date) async throws -> Service { throw ProviderError.notImplemented("Live data is not set up") }
}
