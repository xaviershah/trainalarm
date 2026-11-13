import Foundation
import Observation

/// The one holder of session state. It owns the tracker and shows what the screens need; it
/// holds no tracking logic (that is `JourneyMonitor`). See spec section 6.
@MainActor @Observable
final class SessionModel {
    static let boardCooldown: TimeInterval = 20
    /// Polling every 45 s would use about 80 of RTT's 100 requests an hour. Stopgap until a proxy exists.
    static let pollInterval: TimeInterval = 60

    private(set) var phase: SessionPhase = .selecting
    private(set) var from: Station?
    private(set) var to: Station?
    private(set) var board: BoardState = .idle
    private(set) var confirm: ConfirmState?
    private(set) var blockedPermission: PermissionKind?
    private(set) var permissionWarnings: [PermissionKind] = []
    private(set) var trackerConfig: TrackerConfig?
    private(set) var display = TrackingDisplay()
    private(set) var isFindingNearest = false
    private(set) var nearestFailure: NearestFailure?
    @ObservationIgnored private var eventTask: Task<Void, Never>?

    @ObservationIgnored private let provider: TrainDataProvider
    @ObservationIgnored private let scheduler: AlarmScheduler
    @ObservationIgnored private let directory: StationDirectory
    @ObservationIgnored private let location: LocationSource
    @ObservationIgnored private let clock: TrackerClock
    @ObservationIgnored private let permissions: PermissionsGateway
    @ObservationIgnored private let settings: SettingsStore
    @ObservationIgnored private let locationControl: LocationControl?
    @ObservationIgnored private var lastBoardLoad: Date?
    /// Bumped whenever the picked stations change or a newer load starts; a late board response is dropped.
    @ObservationIgnored private var boardGeneration = 0
    /// Bumped whenever the picked stations change, the user goes back, or a newer choose starts.
    @ObservationIgnored private var chooseGeneration = 0
    /// Bumped by `selectFrom`, `start` and `returnHome`; a nearest lookup from before drops its result.
    @ObservationIgnored private var nearestGeneration = 0
    @ObservationIgnored private var tracker: JourneyTracker?

    init(provider: TrainDataProvider, scheduler: AlarmScheduler, directory: StationDirectory,
         location: LocationSource, clock: TrackerClock, permissions: PermissionsGateway, settings: SettingsStore,
         locationControl: LocationControl? = nil) {
        self.locationControl = locationControl
        self.provider = provider
        self.scheduler = scheduler
        self.directory = directory
        self.location = location
        self.clock = clock
        self.permissions = permissions
        self.settings = settings
    }

    // MARK: Picking

    func search(_ query: String) -> [Station] { directory.search(name: query) }

    var canLoadBoard: Bool {
        guard let from, let to else { return false }
        return from.id != to.id
    }

    func selectFrom(_ station: Station?) { guard isEditable else { return }; nearestGeneration += 1; from = station; resetBoard() }
    func selectTo(_ station: Station?) { guard isEditable else { return }; to = station; resetBoard() }

    private var isEditable: Bool { phase == .selecting || phase == .confirming }

    private func resetBoard() {
        boardGeneration += 1
        chooseGeneration += 1
        board = .idle
        lastBoardLoad = nil
        confirm = nil
        phase = .selecting
    }

    /// Loads the departures from `from` that call at `to`. Ignored within `boardCooldown` of the last
    /// load unless `force`, to protect the request budget.
    func loadBoard(force: Bool = false) async {
        guard canLoadBoard, let from, let to else { return }
        let now = clock.now()
        if !force, let last = lastBoardLoad, now.timeIntervalSince(last) < Self.boardCooldown { return }
        lastBoardLoad = now
        boardGeneration += 1
        let generation = boardGeneration
        board = .loading
        do {
            let services = try await provider.departureBoard(station: from, from: now, callingAt: to)
            guard generation == boardGeneration else { return }
            board = services.isEmpty ? .empty : .loaded(services)
        } catch is CancellationError {
            if generation == boardGeneration { board = .idle }
        } catch ProviderError.http(429) {
            if generation == boardGeneration { board = .rateLimited }
        } catch {
            guard generation == boardGeneration else { return }
            board = .failed(Self.describe(error))
        }
    }

    // MARK: Confirming

    /// Fetches the tapped train's full stops once, to find the arrival at the destination.
    func choose(_ service: Service) async {
        guard phase == .selecting, let from, let to else { return }
        chooseGeneration += 1
        let generation = chooseGeneration
        do {
            let details = try await provider.serviceDetails(serviceId: service.id, date: clock.now())
            guard generation == chooseGeneration, phase == .selecting else { return }
            confirm = ConfirmState(service: details, arrival: Self.arrival(of: details, from: from.id, to: to.id),
                                   leadMinutes: settings.defaultLeadMinutes)
            blockedPermission = nil
            permissionWarnings = []
            phase = .confirming
        } catch is CancellationError {
        } catch {
            guard generation == chooseGeneration else { return }
            board = .failed(Self.describe(error))
        }
    }

    func backToSelecting() {
        chooseGeneration += 1
        confirm = nil
        blockedPermission = nil
        permissionWarnings = []
        phase = .selecting
    }

    func setLead(_ minutes: Int) {
        guard SettingsStore.leadChoices.contains(minutes) else { return }
        confirm?.leadMinutes = minutes
    }

    var alarmAt: Date? {
        guard let confirm, let arrival = confirm.arrival, let lead = confirm.leadMinutes else { return nil }
        return arrival.addingTimeInterval(-Double(lead) * 60)
    }

    /// The scheduler fires at once when the alarm time has passed; the confirm sheet says so.
    var ringsStraightAway: Bool {
        guard let alarmAt else { return false }
        return alarmAt <= clock.now()
    }

    /// True from the moment `start()` begins until it returns, so a second tap while a permission
    /// prompt is open cannot start a second tracker.
    private(set) var isStarting = false

    var canStart: Bool { phase == .confirming && alarmAt != nil && !isStarting }

    // MARK: Starting and ending

    func start() async {
        guard canStart, let confirm, let from, let to, let lead = confirm.leadMinutes else { return }
        endEventCollection()   // no collector from an earlier session may outlive into this one
        isStarting = true
        nearestGeneration += 1
        defer { isStarting = false }
        blockedPermission = nil
        for kind in permissions.requiredKinds {
            var status = permissions.status(kind)
            if status == .notDetermined { status = await permissions.request(kind) }
            if status != .granted { blockedPermission = kind; return }
        }
        var warnings: [PermissionKind] = []
        for kind in permissions.optionalKinds {
            var status = permissions.status(kind)
            if status == .notDetermined { status = await permissions.request(kind) }
            if status != .granted { warnings.append(kind) }
        }
        permissionWarnings = warnings
        if permissions.status(.location) == .granted { locationControl?.start() }
        let config = TrackerConfig(leadTime: Double(lead) * 60, pollInterval: Self.pollInterval)
        trackerConfig = config
        let plan = TrackerPlan(
            boardingId: from.id, destinationId: to.id,
            destinationCoordinate: to.hasCoordinate ? Coordinate(latitude: to.latitude, longitude: to.longitude) : nil,
            config: config)
        let tracker = JourneyTracker(plan: plan, snapshot: confirm.service, provider: provider,
                                     locationSource: location,
                                     scheduler: ReportingScheduler(base: scheduler) { [weak self] fireAt in
                                         self?.alarmScheduled(fireAt)
                                     },
                                     clock: clock)
        self.tracker = tracker
        settings.setDefaultLeadMinutes(lead)
        let destination = Self.destinationStop(of: confirm.service, from: from.id, to: to.id)
        display = TrackingDisplay(eta: destination?.bestArrival, scheduledArrival: destination?.scheduledArrival, alarmAt: alarmAt)
        testAlarm = .idle
        phase = .tracking
        eventTask = Task { [weak self] in
            for await event in tracker.events { await self?.handle(event) }
        }
        await tracker.start()
    }

    /// Stops the tracker, cancels the alarm and ends the session.
    func imOff() async {
        endEventCollection()
        await tracker?.stop()
        tracker = nil
        locationControl?.stop()
        phase = .ended(.imOff)
    }

    /// Back to the empty picker after the session has ended.
    func returnHome() {
        endEventCollection()
        nearestGeneration += 1
        boardGeneration += 1
        chooseGeneration += 1
        board = .idle
        lastBoardLoad = nil
        confirm = nil
        blockedPermission = nil
        permissionWarnings = []
        display = TrackingDisplay()
        phase = .selecting
    }

    // MARK: Tracker events

    /// Applies one tracker event (spec section 6). Events are state transitions, so a repeat changes
    /// nothing, and events after the session ended are ignored.
    func handle(_ event: TrackerEvent) async {
        guard phase == .tracking || phase == .ringing else { return }
        switch event {
        case let .etaChanged(_, newEta, fireAt):
            display.eta = newEta
            display.alarmAt = fireAt
            display.isEstimated = false
            display.estimateSource = nil
            display.alarmNotSet = false
        case let .etaEstimated(eta, source, _, _):
            display.eta = eta
            display.isEstimated = true
            display.estimateSource = source
        case .destinationLost:
            display.destinationDropped = true
        case .serviceCancelled:
            display.cancelled = true
        case let .destinationRestored(newEta):
            display.destinationDropped = false
            display.cancelled = false
            if let newEta { display.eta = newEta }
        case let .feedLost(lastGoodAt, kind):
            display.feedLost = true
            display.lastGoodAt = lastGoodAt
            display.rateLimited = (kind == .http(429))
        case let .feedRecovered(_, freshEta, _):
            display.feedLost = false
            display.rateLimited = false
            if let freshEta {
                display.eta = freshEta
                display.isEstimated = false
                display.estimateSource = nil
            }
        case .alarmSchedulingFailed:
            display.alarmNotSet = true
        case .arrived:
            await tracker?.stop()
            tracker = nil
            locationControl?.stop()
            phase = .ended(.arrived)
            endEventCollection()   // last: this runs inside the collector, so the end-of-session work above must finish first
        }
    }

    /// Cancels and forgets the tracker event collector, so a stopped tracker's buffered events cannot reach a later session.
    private func endEventCollection() {
        eventTask?.cancel()
        eventTask = nil
    }

    /// Called by the platform when the alarm starts ringing.
    func alarmRang() {
        if phase == .tracking { phase = .ringing }
    }

    /// Called when the app returns to the foreground: if the alarm time passed while the app was away,
    /// show the ringing screen.
    func appBecameActive(now: Date) {
        if RingTrigger.shouldRing(phase: phase, alarmAt: display.alarmAt, now: now) { alarmRang() }
    }

    /// The scheduler accepted `fireAt`, so the alarm is armed whatever failed before. The tracker
    /// announces nothing when a retry succeeds on an unchanged ETA, so the model learns it here.
    private func alarmScheduled(_ fireAt: Date) {
        guard phase == .tracking || phase == .ringing else { return }
        display.alarmAt = fireAt
        display.alarmNotSet = false
    }

    /// Passes every call through and reports each success to the model.
    private struct ReportingScheduler: AlarmScheduler {
        let base: AlarmScheduler
        let onScheduled: @MainActor (Date) -> Void
        func schedule(fireAt: Date) async throws {
            try await base.schedule(fireAt: fireAt)
            await onScheduled(fireAt)
        }
        func cancel() async { await base.cancel() }
    }

    // MARK: Settings

    static let testAlarmDelay: TimeInterval = 10

    /// Bumped whenever the permission picture may have changed, so the Settings screen re-reads it.
    private(set) var permissionRevision = 0
    private(set) var testAlarm: TestAlarmState = .idle

    /// Every permission this platform asks for: the required ones first.
    var settingsPermissionKinds: [PermissionKind] { permissions.requiredKinds + permissions.optionalKinds }

    func permissionStatus(_ kind: PermissionKind) -> PermissionStatus {
        _ = permissionRevision   // makes a view that calls this re-render after a refresh
        return permissions.status(kind)
    }

    func refreshPermissions() { permissionRevision += 1 }

    func requestPermission(_ kind: PermissionKind) async {
        _ = await permissions.request(kind)
        refreshPermissions()
    }

    /// The scheduler holds one alarm, so a test alarm is only allowed before a journey starts.
    var canTestAlarm: Bool { phase == .selecting || phase == .confirming }

    /// Called when Settings appears: drops a note about a test alarm that has already rung, or one that failed.
    func refreshTestAlarm() {
        switch testAlarm {
        case .scheduled(let at) where at <= clock.now(): testAlarm = .idle
        case .failed: testAlarm = .idle
        default: break
        }
    }

    func scheduleTestAlarm() async {
        guard canTestAlarm else { return }
        let fireAt = clock.now().addingTimeInterval(Self.testAlarmDelay)
        do {
            try await scheduler.schedule(fireAt: fireAt)
            testAlarm = .scheduled(fireAt)
        } catch {
            testAlarm = .failed
        }
    }

    // MARK: Nearest station

    static let fixWaitAttempts = 10
    static let fixWaitInterval: TimeInterval = 0.5

    /// The "Nearest" chip. Asks for location only now, never re-prompts a refusal, fills From with the
    /// closest station, and stops the GPS again.
    func useNearestStation() async {
        guard isEditable, !isFindingNearest else { return }
        nearestGeneration += 1
        let generation = nearestGeneration
        isFindingNearest = true
        nearestFailure = nil
        var startedGps = false
        defer {
            isFindingNearest = false
            // Only stop a GPS this lookup started, and never one a starting or running session now owns.
            if startedGps && isEditable && !isStarting { locationControl?.stop() }
        }
        var status = permissions.status(.location)
        if status == .notDetermined { status = await permissions.request(.location) }
        guard generation == nearestGeneration else { return }
        guard status == .granted else { nearestFailure = .permissionDenied; return }
        locationControl?.start()
        startedGps = true
        let maxAge = TrackerConfig(leadTime: 0, pollInterval: Self.pollInterval).maxFixAge
        for attempt in 0...Self.fixWaitAttempts {
            guard generation == nearestGeneration else { return }
            if let fix = location.latestFix(), fix.accuracy.isFinite,
               clock.now().timeIntervalSince(fix.timestamp) <= maxAge,
               let station = directory.nearest(latitude: fix.latitude, longitude: fix.longitude) {
                selectFrom(station)
                return
            }
            if attempt == Self.fixWaitAttempts { break }
            do { try await clock.sleep(for: Self.fixWaitInterval) } catch { return }
        }
        guard generation == nearestGeneration else { return }
        nearestFailure = .noFix
    }

    // MARK: Helpers

    static func destinationStop(of service: Service, from fromId: String, to toId: String) -> Stop? {
        let stops = service.journey.stops
        guard let boardIndex = stops.firstIndex(where: { $0.station.id == fromId }) else { return nil }
        return stops.dropFirst(boardIndex + 1).first { $0.station.id == toId }
    }

    static func arrival(of service: Service, from fromId: String, to toId: String) -> Date? {
        destinationStop(of: service, from: fromId, to: toId)?.bestArrival
    }

    private static func describe(_ error: Error) -> String {
        switch error as? ProviderError {
        case .network(_)?: return "Can't reach the train data. Check your connection and try again."
        case .http(let code)?: return "The train data service returned an error (\(code)). Try again in a moment."
        case .malformed(_)?: return "The train data was not in the expected form."
        default: return "Something went wrong loading trains. Try again."
        }
    }
}
