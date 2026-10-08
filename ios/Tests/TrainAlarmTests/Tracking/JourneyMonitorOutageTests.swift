import XCTest
@testable import TrainAlarm

final class JourneyMonitorOutageTests: XCTestCase {
    private func t(_ hhmmss: String) -> Date { ServiceBuilder.time(hhmmss) }
    private func happy(_ estimate: String? = "08:52:00") -> Service { ServiceBuilder.happyPath(destinationEstimate: estimate) }
    private let farDestination = Coordinate(latitude: 0, longitude: 1.0)
    private let nearDestination = Coordinate(latitude: 0, longitude: 0.1)

    /// start, one good poll at 08:09:00 with a fix at longitude 0, then a failed poll at 08:10:00 with a fix at 0.02.
    private func outageWithTwoFixes(_ h: MonitorHarness, secondFix: FixSpec = FixSpec(latitude: 0, longitude: 0.02),
                                    firstFix: FixSpec = FixSpec(latitude: 0, longitude: 0.0)) -> [TrackerEvent] {
        h.start(happy())
        h.poll(happy(), advancing: 540, fix: firstFix)  // now 08:09:00
        return h.fail(.network, advancing: 60, fix: secondFix)  // now 08:10:00
    }

    // MARK: Scenarios 14-17: the outage fallback

    func testFirstPollFailingReportsNoLastGoodTimeAndFallsBackToTheSnapshot() {
        let h = MonitorHarness()
        h.start(happy())
        XCTAssertEqual(h.fail(.network), [
            .feedLost(lastGoodAt: nil, kind: .network),
            .etaEstimated(eta: t("08:52:00"), source: .lastKnownTrajectory, staleness: 45, rescheduled: false),
        ])
    }

    func testOutageWithoutGpsHoldsTheLastLiveEtaAndFeedLostFiresOnce() {
        let h = MonitorHarness()
        h.start(happy())
        h.poll(happy())  // good at 08:00:45

        XCTAssertEqual(h.fail(.http(503)), [
            .feedLost(lastGoodAt: t("08:00:45"), kind: .http(503)),
            .etaEstimated(eta: t("08:52:00"), source: .lastKnownTrajectory, staleness: 45, rescheduled: false),
        ])
        XCTAssertEqual(h.fail(.http(503)), [
            .etaEstimated(eta: t("08:52:00"), source: .lastKnownTrajectory, staleness: 90, rescheduled: false),
        ])
        XCTAssertEqual(h.scheduled, [t("08:42:00")])
    }

    func testOutageWithGpsApproachingUsesGpsPaceAndReschedules() {
        let h = MonitorHarness(coordinate: farDestination)
        let events = outageWithTwoFixes(h)

        guard events.count == 2 else { return XCTFail("expected 2 events, got \(events)") }
        XCTAssertEqual(events[0], .feedLost(lastGoodAt: t("08:09:00"), kind: .network))
        guard case .etaEstimated(let eta, let source, let staleness, let rescheduled) = events[1] else {
            return XCTFail("expected etaEstimated, got \(events[1])")
        }
        XCTAssertEqual(eta.timeIntervalSince(t("08:59:00")), 0, accuracy: 0.001)
        XCTAssertEqual(source, .gpsPace)
        XCTAssertEqual(staleness, 60)
        XCTAssertTrue(rescheduled)
        XCTAssertEqual(h.scheduled.count, 2)
        XCTAssertEqual(h.scheduled[1].timeIntervalSince(t("08:49:00")), 0, accuracy: 0.001)
    }

    func testGpsFallbackIsNeverEarlierThanTheLastLiveEtaMinusTheGuard() {
        let h = MonitorHarness(coordinate: nearDestination)  // pace would put the ETA at 08:14:00
        let events = outageWithTwoFixes(h)

        XCTAssertEqual(events, [
            .feedLost(lastGoodAt: t("08:09:00"), kind: .network),
            .etaEstimated(eta: t("08:51:00"), source: .gpsPace, staleness: 60, rescheduled: true),
        ])
        XCTAssertEqual(h.scheduled, [t("08:42:00"), t("08:41:00")])
    }

    func testWithoutADestinationCoordinateTheFallbackStaysOnTheTrajectory() {
        let h = MonitorHarness(coordinate: nil)
        let events = outageWithTwoFixes(h)
        XCTAssertEqual(events.last, .etaEstimated(eta: t("08:52:00"), source: .lastKnownTrajectory, staleness: 60, rescheduled: false))
    }

    func testInaccurateFixesAreIgnored() {
        let h = MonitorHarness(coordinate: farDestination)
        let events = outageWithTwoFixes(h, firstFix: FixSpec(latitude: 0, longitude: 0.0, accuracy: 500))
        XCTAssertEqual(events.last, .etaEstimated(eta: t("08:52:00"), source: .lastKnownTrajectory, staleness: 60, rescheduled: false))
    }

    func testGpsEtaDiscardsFixesThatAgeOutWhenLaterTicksCarryNoNewFix() {
        let h = MonitorHarness(coordinate: farDestination)
        h.start(happy())
        h.poll(happy(), advancing: 45, fix: FixSpec(latitude: 0, longitude: 0.0))
        h.poll(happy(), advancing: 45, fix: FixSpec(latitude: 0, longitude: 0.02))  // two fresh fixes: GPS would apply
        // No new fix arrives, so the insertion-time prune never runs; only gpsEta's own age filter
        // can drop the now 245s and 200s old fixes (the limit is 135s).
        let events = h.fail(.network, advancing: 200, fix: nil)

        XCTAssertEqual(events.last, .etaEstimated(eta: t("08:52:00"), source: .lastKnownTrajectory, staleness: 200, rescheduled: false))
    }

    func testStaleFixesAreIgnored() {
        let h = MonitorHarness(coordinate: farDestination)
        h.start(happy())
        h.poll(happy(), advancing: 45, fix: FixSpec(latitude: 0, longitude: 0.0))
        let events = h.fail(.network, advancing: 300, fix: FixSpec(latitude: 0, longitude: 0.02))  // the first fix is now 300s old

        XCTAssertEqual(events.last, .etaEstimated(eta: t("08:52:00"), source: .lastKnownTrajectory, staleness: 300, rescheduled: false))
    }

    func testAStationaryTrainBelowMinimumPaceHoldsTheLastEta() {
        let h = MonitorHarness(coordinate: farDestination)
        let events = outageWithTwoFixes(h, secondFix: FixSpec(latitude: 0, longitude: 0.0))
        XCTAssertEqual(events.last, .etaEstimated(eta: t("08:52:00"), source: .lastKnownTrajectory, staleness: 60, rescheduled: false))
    }

    func testAnOutageWhileTheDestinationIsLostEmitsOnlyFeedLost() throws {
        let h = MonitorHarness()
        h.start(happy())
        h.poll(try ServiceBuilder.loadFixture("gb-nr-service-destination-dropped"))

        XCTAssertEqual(h.fail(.network), [.feedLost(lastGoodAt: t("08:00:45"), kind: .network)])
    }

    // MARK: Scenarios 18-20: recovery and flapping

    func testRecoveryWithADelayReportsRecoveryThenTheChange() {
        let h = MonitorHarness()
        h.start(happy())
        h.fail()  // 08:00:45

        XCTAssertEqual(h.poll(happy("08:57:00")), [  // 08:01:30
            .feedRecovered(outage: 45, freshEta: t("08:57:00"), deltaFromFallback: 300),
            .etaChanged(oldEta: t("08:52:00"), newEta: t("08:57:00"), fireAt: t("08:47:00")),
        ])
        XCTAssertFalse(h.state.feedDown)
    }

    func testRecoveryThatRevealsADroppedDestinationReportsRecoveryFirst() throws {
        let h = MonitorHarness()
        h.start(happy())
        h.fail()

        XCTAssertEqual(h.poll(try ServiceBuilder.loadFixture("gb-nr-service-destination-dropped")), [
            .feedRecovered(outage: 45, freshEta: nil, deltaFromFallback: nil),
            .destinationLost(at: h.now),
        ])
    }

    func testFlappingYieldsTwoFeedLostAndOneFeedRecovered() {
        let h = MonitorHarness()
        h.start(happy())
        let all = h.fail() + h.poll(happy()) + h.fail()

        XCTAssertEqual(all.filter { if case .feedLost = $0 { return true } else { return false } }.count, 2)
        XCTAssertEqual(all.filter { if case .feedRecovered = $0 { return true } else { return false } }.count, 1)
    }

    // MARK: Scenario 23: scheduler refusal

    func testASchedulerRefusalIsReportedThenRetriedOnTheNextTickWithoutANewEtaChanged() {
        let h = MonitorHarness()
        h.start(happy())
        h.failNextSchedule = true

        XCTAssertEqual(h.poll(happy("08:58:00")), [
            .etaChanged(oldEta: t("08:52:00"), newEta: t("08:58:00"), fireAt: t("08:48:00")),
            .alarmSchedulingFailed(fireAt: t("08:48:00"), message: "refused"),
        ])
        XCTAssertEqual(h.scheduled, [t("08:42:00")])

        XCTAssertEqual(h.poll(happy("08:58:00")), [])
        XCTAssertEqual(h.scheduled, [t("08:42:00"), t("08:48:00")])
        XCTAssertEqual(h.attempts, [t("08:42:00"), t("08:48:00"), t("08:48:00")])
        XCTAssertFalse(h.state.retryPending)
    }

    func testARefusedClampedAlarmIsRetriedAtTheCurrentTimeAndMarkedDue() {
        let h = MonitorHarness()
        h.start(happy())
        h.failNextSchedule = true

        // 08:41:00 with ETA 08:50:00: the alarm time (08:40:00) has already passed, so it is clamped to now.
        XCTAssertEqual(h.poll(happy("08:50:00"), advancing: 2460), [
            .etaChanged(oldEta: t("08:52:00"), newEta: t("08:50:00"), fireAt: t("08:41:00")),
            .alarmSchedulingFailed(fireAt: t("08:41:00"), message: "refused"),
        ])
        XCTAssertTrue(h.state.retryPending)

        XCTAssertEqual(h.poll(happy("08:50:00")), [])  // 08:41:45
        XCTAssertEqual(h.attempts, [t("08:42:00"), t("08:41:00"), t("08:41:45")])
        XCTAssertEqual(h.scheduled, [t("08:42:00"), t("08:41:45")])
        XCTAssertFalse(h.state.retryPending)
        XCTAssertTrue(h.state.alarmDue)
    }

    // MARK: Scenario 24: arrival

    func testConfirmedArrivalEmitsArrivedCancelsTheAlarmAndEndsTracking() {
        let h = MonitorHarness()
        h.start(happy())
        let arrived = ServiceBuilder.replacing(happy(), stopAt: 2, with: ServiceBuilder.stop(
            "BSK", scheduledArrival: "08:47:00", estimatedArrival: "08:50:00", hasArrived: true))

        XCTAssertEqual(h.poll(arrived), [.arrived(at: h.now)])
        XCTAssertEqual(h.cancelCount, 1)
        XCTAssertTrue(h.state.finished)
        XCTAssertEqual(h.poll(happy()), [])  // finished: ignored
        XCTAssertEqual(h.cancelCount, 1)
        XCTAssertEqual(h.scheduled, [t("08:42:00")])
    }

    func testNoArrivalReportEndsTrackingOnceTheGracePeriodHasPassed() {
        let h = MonitorHarness()
        h.start(happy())  // ETA 08:52:00, grace 10 minutes
        XCTAssertEqual(h.poll(happy(), advancing: 3780), [.arrived(at: t("09:03:00"))])
        XCTAssertEqual(h.cancelCount, 1)
    }

    func testADestinationThatHasAlreadyArrivedEndsTrackingEvenWhenItIsMarkedTerminates() throws {
        // The fixture's BSK stop is TERMINATES and has an actual arrival time (12:48).
        let h = MonitorHarness(destination: "BSK", start: t("12:00:00"))
        XCTAssertEqual(h.start(try ServiceBuilder.loadFixture("gb-nr-service-display-as")), [.arrived(at: t("12:00:00"))])
        XCTAssertEqual(h.cancelCount, 1)
    }
}
