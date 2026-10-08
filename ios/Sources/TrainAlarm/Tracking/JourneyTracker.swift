import Foundation

/// The thin driver around the pure `JourneyMonitor`: it owns the poll loop, applies the
/// monitor's alarm actions through the injected scheduler, and publishes the monitor's events on
/// a stream. All decisions live in `JourneyMonitor`; this type only does I/O and timing.
actor JourneyTracker {
    /// Events in the order they happened. Finishes when the journey ends (arrival) or on `stop()`.
    nonisolated let events: AsyncStream<TrackerEvent>

    private let continuation: AsyncStream<TrackerEvent>.Continuation
    private let monitor: JourneyMonitor
    private let snapshot: Service
    private let provider: TrainDataProvider
    private let locationSource: LocationSource
    private let scheduler: AlarmScheduler
    private let clock: TrackerClock
    private var state = MonitorState()
    private var loop: Task<Void, Never>?

    init(
        plan: TrackerPlan,
        snapshot: Service,
        provider: TrainDataProvider,
        locationSource: LocationSource,
        scheduler: AlarmScheduler,
        clock: TrackerClock
    ) {
        let (stream, continuation) = AsyncStream.makeStream(of: TrackerEvent.self)
        self.events = stream
        self.continuation = continuation
        self.monitor = JourneyMonitor(plan: plan)
        self.snapshot = snapshot
        self.provider = provider
        self.locationSource = locationSource
        self.scheduler = scheduler
        self.clock = clock
    }

    /// Schedules the timetable-based alarm from the snapshot, then polls every `pollInterval`
    /// until arrival or `stop()`.
    func start() {
        guard loop == nil else { return }
        loop = Task { await self.run() }
    }

    /// Stops polling, cancels the alarm and finishes the event stream. Safe to call more than once.
    func stop() async {
        loop?.cancel()
        await loop?.value
        loop = nil
        await scheduler.cancel()
        continuation.finish()
    }

    private func run() async {
        await apply(monitor.start(snapshot: snapshot, now: clock.now()))
        while !state.finished && !Task.isCancelled {
            do {
                try await clock.sleep(for: monitor.plan.config.pollInterval)
            } catch {
                break  // cancelled while sleeping: not an outage
            }
            await tick()
        }
        continuation.finish()
    }

    private func tick() async {
        let fix = locationSource.latestFix()
        let result: PollResult
        do {
            result = .service(try await provider.serviceDetails(serviceId: snapshot.id, date: clock.now()))
        } catch is CancellationError {
            return  // stop() was called, or the request was cancelled: not an outage
        } catch let error as ProviderError {
            result = .failure(Self.failureKind(of: error))
        } catch {
            result = .failure(.other)
        }
        guard !Task.isCancelled else { return }
        await apply(monitor.step(state, Observation(result: result, now: clock.now(), fix: fix)))
    }

    private static func failureKind(of error: ProviderError) -> FailureKind {
        switch error {
        case .network: return .network
        case .http(let code): return .http(code)
        case .malformed: return .malformed
        case .notImplemented: return .other
        }
    }

    private func apply(_ step: MonitorStep) async {
        state = step.state
        var pending = step.events
        switch step.action {
        case .none:
            break
        case .schedule(let fireAt):
            do {
                try await scheduler.schedule(fireAt: fireAt)
                state = monitor.confirmScheduled(state, fireAt: fireAt)
            } catch {
                // A real cancellation (stop()) ends the step. A spurious CancellationError is a
                // refusal like any other, so the next tick asks again instead of losing the alarm.
                if error is CancellationError, Task.isCancelled { return }
                state = monitor.schedulingFailed(state)
                pending.append(.alarmSchedulingFailed(fireAt: fireAt, message: String(describing: error)))
            }
        case .cancel:
            await scheduler.cancel()
        }
        for event in pending { continuation.yield(event) }
    }
}
