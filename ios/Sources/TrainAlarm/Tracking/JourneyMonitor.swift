import Foundation

/// The pure decision core of the tracker. It performs no I/O and never throws: the driver feeds
/// it one `Observation` per tick and applies the `AlarmAction` it returns, then reports back with
/// `confirmScheduled` or `schedulingFailed`. See the spec's "Monitor rules".
struct JourneyMonitor {
    let plan: TrackerPlan

    private var config: TrackerConfig { plan.config }

    /// Builds the first step from the selection-time snapshot, so the timetable-based fallback
    /// alarm is scheduled before any poll has happened.
    func start(snapshot: Service, now: Date) -> MonitorStep {
        var state = MonitorState()
        state.startedAt = now
        return observeService(snapshot, now: now, state: state)
    }

    func step(_ state: MonitorState, _ observation: Observation) -> MonitorStep {
        guard !state.finished else { return MonitorStep(state: state, events: [], action: .none) }
        var state = state
        if let fix = observation.fix {
            state.fixes = (state.fixes + [fix]).filter {
                observation.now.timeIntervalSince($0.timestamp) <= config.maxFixAge
            }
        }
        switch observation.result {
        case .service(let service):
            state.lastGoodAt = observation.now
            return observeService(service, now: observation.now, state: state)
        case .failure(let kind):
            return observeFailure(kind, now: observation.now, state: state)
        }
    }

    /// Call after the scheduler accepted a `.schedule` action.
    func confirmScheduled(_ state: MonitorState, fireAt: Date) -> MonitorState {
        var state = state
        state.scheduledFire = fireAt
        state.retryPending = false
        return state
    }

    /// Call after the scheduler refused a `.schedule` action: the next tick asks again.
    func schedulingFailed(_ state: MonitorState) -> MonitorState {
        var state = state
        state.retryPending = true
        return state
    }

    // MARK: Successful poll

    private func observeService(_ service: Service, now: Date, state input: MonitorState) -> MonitorStep {
        var state = input
        var events: [TrackerEvent] = []

        let destination = destinationStop(in: service)
        let newStanding = classify(destination, in: service)
        let previous = state.standing

        // The ETA is resolved first because feedRecovered reports it ahead of every other event.
        var resolved: Date? = nil
        if newStanding == .calling, let stop = destination {
            resolved = resolveEta(for: stop, state: &state)
        }

        if state.feedDown {
            let outage = now.timeIntervalSince(state.outageStartedAt ?? now)
            let delta = resolved.flatMap { fresh in state.lastFallbackEta.map { fresh.timeIntervalSince($0) } }
            events.append(.feedRecovered(outage: outage, freshEta: resolved, deltaFromFallback: delta))
            state.feedDown = false
            state.outageStartedAt = nil
            state.lastFallbackEta = nil
        }

        state.standing = newStanding
        if newStanding == .lost && previous != .lost { events.append(.destinationLost(at: now)) }
        if newStanding == .cancelled && previous != .cancelled { events.append(.serviceCancelled(at: now)) }
        guard newStanding == .calling, let stop = destination else {
            // Lost or cancelled: leave the scheduled alarm alone (cancelling it risks silence). A pending retry waits for restoration.
            return MonitorStep(state: state, events: events, action: .none)
        }

        if previous != .calling { events.append(.destinationRestored(newEta: resolved)) }
        guard let eta = resolved else {
            return MonitorStep(state: state, events: events, action: .none)
        }

        // Arrival ends tracking, before anything is scheduled.
        if stop.hasArrived || now > eta.addingTimeInterval(config.arrivalGrace) {
            events.append(.arrived(at: now))
            state.finished = true
            return MonitorStep(state: state, events: events, action: .cancel)
        }

        let previousEta = state.lastEta
        state.lastEta = eta
        let decision = decideSchedule(desired: eta.addingTimeInterval(-config.leadTime), now: now, state: state)
        state = decision.state
        if decision.announces, case .schedule(let fireAt) = decision.action {
            events.append(.etaChanged(oldEta: previousEta, newEta: eta, fireAt: fireAt))
        }
        return MonitorStep(state: state, events: events, action: decision.action)
    }

    /// The first stop with `destinationId` after the boarding stop (the whole list if the boarding
    /// station is not found). Never `Journey.destination`: that is the service's terminus.
    private func destinationStop(in service: Service) -> Stop? {
        let stops = service.journey.stops
        let start = stops.firstIndex { $0.station.id == plan.boardingId }.map { $0 + 1 } ?? 0
        return stops[start...].first { $0.station.id == plan.destinationId }
    }

    private func classify(_ stop: Stop?, in service: Service) -> Standing {
        guard let stop else { return .lost }
        if service.journey.stops.allSatisfy({ $0.isCancelled }) { return .cancelled }
        if notCalledAt(stop) { return .lost }
        return .calling
    }

    /// The destination's arrival is cancelled, or the provider says the train no longer calls
    /// there. A nil or unknown `displayAs` is not enough on its own: the schema reads a missing
    /// value as PASS, but that has not been checked against real data.
    private func notCalledAt(_ stop: Stop) -> Bool {
        if stop.isArrivalCancelled { return true }
        switch stop.displayAs {
        case .cancelled?, .diverted?, .pass?: return true
        default: return false
        }
    }

    private func resolveEta(for stop: Stop, state: inout MonitorState) -> Date? {
        if let live = stop.estimatedArrival {
            state.sawLive = true
            state.lastLiveEta = live
            return live
        }
        // A live estimate that has vanished must not silently drop a known delay.
        if state.sawLive && !stop.hasArrived { return state.lastEta }
        return stop.scheduledArrival ?? state.lastEta
    }

    // MARK: Scheduling

    /// Decides whether to (re)schedule for `desired`. `announces` says whether a live-data
    /// `etaChanged` should accompany the action (false for retries and, in Task 3, fallback ETAs).
    private func decideSchedule(
        desired: Date, now: Date, state input: MonitorState
    ) -> (state: MonitorState, action: AlarmAction, announces: Bool) {
        var state = input
        let clamped = desired <= now
        if state.retryPending {
            // A previous schedule call failed: ask again, without announcing the same change twice.
            if clamped { state.alarmDue = true }
            return (state, .schedule(clamped ? now : desired), false)
        }
        if state.alarmDue { return (state, .none, false) }
        if let scheduled = state.scheduledFire {
            if scheduled <= now {
                // The confirmed alarm time has passed: it has fired, so a later slip must not re-arm it.
                state.alarmDue = true
                return (state, .none, false)
            }
            if abs(desired.timeIntervalSince(scheduled)) < config.rescheduleThreshold {
                return (state, .none, false)
            }
        }
        if clamped { state.alarmDue = true }
        return (state, .schedule(clamped ? now : desired), true)
    }

    // MARK: Failed poll

    private func observeFailure(_ kind: FailureKind, now: Date, state input: MonitorState) -> MonitorStep {
        var state = input
        var events: [TrackerEvent] = []
        if !state.feedDown {
            state.feedDown = true
            state.outageStartedAt = now
            events.append(.feedLost(lastGoodAt: state.lastGoodAt, kind: kind))
        }
        // A lost or cancelled destination has no ETA to estimate. The anchor is the last live ETA
        // (or the snapshot's), which the fallback itself never overwrites, so the guard cannot creep.
        guard state.standing == .calling, let anchor = state.lastLiveEta ?? state.lastEta else {
            return MonitorStep(state: state, events: events, action: .none)
        }

        let gps = gpsEta(now: now, state: state)
        let eta = gps.map { max($0, anchor.addingTimeInterval(-config.guardMargin)) } ?? anchor
        state.lastFallbackEta = eta
        let decision = decideSchedule(desired: eta.addingTimeInterval(-config.leadTime), now: now, state: state)
        state = decision.state
        var rescheduled = false
        if case .schedule = decision.action { rescheduled = true }
        let staleness = now.timeIntervalSince(state.lastGoodAt ?? state.startedAt ?? now)
        events.append(.etaEstimated(
            eta: eta,
            source: gps == nil ? .lastKnownTrajectory : .gpsPace,
            staleness: staleness,
            rescheduled: rescheduled
        ))
        return MonitorStep(state: state, events: events, action: decision.action)
    }

    /// Approach speed over the usable fixes (fresh and accurate enough), extrapolated to the
    /// destination. Nil when there is no coordinate, fewer than two usable fixes, or the train is
    /// not closing in faster than `minPace`.
    private func gpsEta(now: Date, state: MonitorState) -> Date? {
        guard let destination = plan.destinationCoordinate else { return nil }
        let usable = state.fixes.filter {
            now.timeIntervalSince($0.timestamp) <= config.maxFixAge && $0.accuracy <= config.maxFixAccuracy
        }
        guard usable.count >= 2,
              let oldest = usable.min(by: { $0.timestamp < $1.timestamp }),
              let newest = usable.max(by: { $0.timestamp < $1.timestamp }) else { return nil }
        let elapsed = newest.timestamp.timeIntervalSince(oldest.timestamp)
        guard elapsed > 0 else { return nil }
        let before = Geo.distanceMetres(fromLatitude: oldest.latitude, longitude: oldest.longitude,
                                        toLatitude: destination.latitude, longitude: destination.longitude)
        let after = Geo.distanceMetres(fromLatitude: newest.latitude, longitude: newest.longitude,
                                       toLatitude: destination.latitude, longitude: destination.longitude)
        let pace = (before - after) / elapsed  // approach speed, metres per second
        guard pace >= config.minPace else { return nil }
        return now.addingTimeInterval(after / pace)
    }
}
