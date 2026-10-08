import XCTest
@testable import TrainAlarm

final class JourneyMonitorTests: XCTestCase {
    private func t(_ hhmmss: String) -> Date { ServiceBuilder.time(hhmmss) }
    private func happy(_ estimate: String? = "08:52:00") -> Service { ServiceBuilder.happyPath(destinationEstimate: estimate) }

    // MARK: Scenarios 1-5: ETA, threshold, drift

    func testSteadyLiveDataSchedulesOnceThenStaysQuiet() {
        let h = MonitorHarness()
        XCTAssertEqual(h.start(happy()), [.etaChanged(oldEta: nil, newEta: t("08:52:00"), fireAt: t("08:42:00"))])
        XCTAssertEqual(h.poll(happy()), [])
        XCTAssertEqual(h.poll(happy()), [])
        XCTAssertEqual(h.scheduled, [t("08:42:00")])
    }

    func testThirtySecondShiftDoesNotReschedule() {
        let h = MonitorHarness()
        h.start(happy())
        XCTAssertEqual(h.poll(happy("08:52:30")), [])
        XCTAssertEqual(h.scheduled, [t("08:42:00")])
    }

    func testExactlySixtySecondShiftReschedules() {
        let h = MonitorHarness()
        h.start(happy())
        XCTAssertEqual(h.poll(happy("08:53:00")),
                       [.etaChanged(oldEta: t("08:52:00"), newEta: t("08:53:00"), fireAt: t("08:43:00"))])
        XCTAssertEqual(h.scheduled, [t("08:42:00"), t("08:43:00")])
    }

    func testSlowDriftIsComparedAgainstTheScheduledTimeNotThePreviousPoll() {
        let h = MonitorHarness()
        h.start(happy())
        XCTAssertEqual(h.poll(happy("08:52:30")), [])
        XCTAssertEqual(h.poll(happy("08:53:00")),
                       [.etaChanged(oldEta: t("08:52:30"), newEta: t("08:53:00"), fireAt: t("08:43:00"))])
    }

    func testDelayThenBackToOnTimeReschedulesTwice() {
        let h = MonitorHarness()
        h.start(happy())
        XCTAssertEqual(h.poll(happy("08:57:00")),
                       [.etaChanged(oldEta: t("08:52:00"), newEta: t("08:57:00"), fireAt: t("08:47:00"))])
        XCTAssertEqual(h.poll(happy("08:52:00")),
                       [.etaChanged(oldEta: t("08:57:00"), newEta: t("08:52:00"), fireAt: t("08:42:00"))])
        XCTAssertEqual(h.scheduled, [t("08:42:00"), t("08:47:00"), t("08:42:00")])
    }

    // MARK: Scenarios 6-10: cancelled, dropped, displayAs

    func testWholeServiceCancelledEmitsServiceCancelledOnceAndLeavesTheAlarm() throws {
        let h = MonitorHarness()
        h.start(happy())
        let cancelled = try ServiceBuilder.loadFixture("gb-nr-service-cancelled")

        XCTAssertEqual(h.poll(cancelled), [.serviceCancelled(at: h.now)])
        XCTAssertEqual(h.poll(cancelled), [])
        XCTAssertEqual(h.scheduled, [t("08:42:00")])
        XCTAssertEqual(h.cancelCount, 0)
    }

    func testDestinationAbsentEmitsDestinationLostOnce() throws {
        let h = MonitorHarness()
        h.start(happy())
        let dropped = try ServiceBuilder.loadFixture("gb-nr-service-destination-dropped")

        XCTAssertEqual(h.poll(dropped), [.destinationLost(at: h.now)])
        XCTAssertEqual(h.poll(dropped), [])
        XCTAssertEqual(h.scheduled, [t("08:42:00")])
    }

    func testDivertedCancelledAndPassDestinationsAreLost() throws {
        let fixture = try ServiceBuilder.loadFixture("gb-nr-service-display-as")
        for destination in ["SUR", "WOK", "CLJ"] {  // DIVERTED, CANCELLED, PASS
            let h = MonitorHarness(destination: destination, start: t("12:00:00"))
            XCTAssertEqual(h.start(fixture), [.destinationLost(at: t("12:00:00"))], destination)
            XCTAssertEqual(h.scheduled, [], destination)
        }
    }

    func testUnknownDisplayAsAndTerminatesDoNotRaiseALoss() throws {
        let fixture = try ServiceBuilder.loadFixture("gb-nr-service-display-as")

        let unknown = MonitorHarness(destination: "WIN", start: t("12:00:00"))  // displayAs "FUTURE_VALUE"
        XCTAssertEqual(unknown.start(fixture), [.etaChanged(oldEta: nil, newEta: t("12:40:00"), fireAt: t("12:30:00"))])

        // TERMINATES means the train ends here: still a normal call. This stop is built, not taken
        // from the fixture: the fixture's BSK stop also has an actual arrival time, which ends
        // tracking once Task 3 lands.
        let terminatesStop = ServiceBuilder.stop(
            "BSK", scheduledArrival: "08:47:00", estimatedArrival: "08:52:00", displayAs: .terminates)
        let terminates = MonitorHarness()
        XCTAssertEqual(terminates.start(ServiceBuilder.replacing(happy(), stopAt: 2, with: terminatesStop)),
                       [.etaChanged(oldEta: nil, newEta: t("08:52:00"), fireAt: t("08:42:00"))])
    }

    func testOnlyTheDestinationsDepartureCancelledIsNotALoss() throws {
        let fixture = try ServiceBuilder.loadFixture("gb-nr-service-departure-cancelled")
        let h = MonitorHarness(destination: "WOK", start: t("11:00:00"))
        XCTAssertEqual(h.start(fixture), [.etaChanged(oldEta: nil, newEta: t("11:25:00"), fireAt: t("11:15:00"))])
    }

    func testDestinationArrivalCancelledWhileOtherStopsRunIsLost() {
        let h = MonitorHarness()
        h.start(happy())
        let arrivalCancelled = ServiceBuilder.replacing(
            happy(), stopAt: 2, with: ServiceBuilder.stop("BSK", scheduledArrival: "08:47:00", isArrivalCancelled: true))

        XCTAssertEqual(h.poll(arrivalCancelled), [.destinationLost(at: h.now)])
    }

    // MARK: Scenario 11: restored

    func testLostThenRestoredEmitsRestoredThenEtaChanged() throws {
        let h = MonitorHarness()
        h.start(happy())
        h.poll(try ServiceBuilder.loadFixture("gb-nr-service-destination-dropped"))

        XCTAssertEqual(h.poll(happy("08:58:00")), [
            .destinationRestored(newEta: t("08:58:00")),
            .etaChanged(oldEta: t("08:52:00"), newEta: t("08:58:00"), fireAt: t("08:48:00")),
        ])
        XCTAssertEqual(h.scheduled, [t("08:42:00"), t("08:48:00")])
    }

    // MARK: Scenarios 12-13: missing or vanished times

    func testNilBestArrivalHoldsTheLastEtaAndEmitsNothing() {
        let h = MonitorHarness()
        h.start(happy())
        let noTimes = ServiceBuilder.replacing(happy(), stopAt: 2, with: ServiceBuilder.stop("BSK"))

        XCTAssertEqual(h.poll(noTimes), [])
        XCTAssertEqual(h.scheduled, [t("08:42:00")])
    }

    func testVanishedEstimateHoldsTheKnownDelayInsteadOfRevertingToTheTimetable() {
        let h = MonitorHarness()
        h.start(happy())  // live ETA 08:52:00
        let scheduledOnly = ServiceBuilder.replacing(
            happy(), stopAt: 2, with: ServiceBuilder.stop("BSK", scheduledArrival: "08:47:00"))

        XCTAssertEqual(h.poll(scheduledOnly), [])  // reverting to 08:47 would reschedule
        XCTAssertEqual(h.scheduled, [t("08:42:00")])
    }

    func testFirstObservationWithNoTimesAtAllSchedulesNothing() {
        let h = MonitorHarness()
        let noTimes = ServiceBuilder.replacing(happy(), stopAt: 2, with: ServiceBuilder.stop("BSK"))

        XCTAssertEqual(h.start(noTimes), [])
        XCTAssertEqual(h.scheduled, [])
    }

    // MARK: Scenarios 21-22 and the clamp

    func testFireTimeAlreadyPastAtStartSchedulesNowOnceAndNeverAgain() {
        let h = MonitorHarness(start: t("08:45:00"))
        XCTAssertEqual(h.start(happy()), [.etaChanged(oldEta: nil, newEta: t("08:52:00"), fireAt: t("08:45:00"))])
        XCTAssertTrue(h.state.alarmDue)

        XCTAssertEqual(h.poll(happy()), [])
        XCTAssertEqual(h.poll(happy("09:30:00")), [])  // a big slip after the alarm is due is not re-armed
        XCTAssertEqual(h.scheduled, [t("08:45:00")])
    }

    func testEtaCloserThanTheLeadTimeAtStartSchedulesImmediately() {
        let h = MonitorHarness(start: t("08:50:00"))  // ETA 08:52 is only 2 minutes away, lead time is 10
        h.start(happy())
        XCTAssertEqual(h.scheduled, [t("08:50:00")])
    }

    func testFireTimeExactlyEqualToNowCountsAsPast() {
        let h = MonitorHarness(start: t("08:42:00"))  // desired fire time is exactly 08:42:00
        h.start(happy())
        XCTAssertEqual(h.scheduled, [t("08:42:00")])
        XCTAssertTrue(h.state.alarmDue)
    }

    func testASlipAfterTheScheduledAlarmTimeHasPassedDoesNotRearmIt() {
        let h = MonitorHarness()
        h.start(happy())  // alarm confirmed for 08:42:00

        XCTAssertEqual(h.poll(happy("08:58:00"), advancing: 2580), [])  // now is 08:43:00
        XCTAssertEqual(h.scheduled, [t("08:42:00")])
        XCTAssertTrue(h.state.alarmDue)
    }

    // MARK: Destination resolution

    func testDestinationOnlyBeforeTheBoardingStopIsLost() {
        let service = ServiceBuilder.service(stops: [
            ServiceBuilder.stop("BSK", scheduledArrival: "08:10:00"),
            ServiceBuilder.stop("WAT"),
            ServiceBuilder.stop("WOK", scheduledArrival: "08:23:00"),
        ])
        let h = MonitorHarness()
        XCTAssertEqual(h.start(service), [.destinationLost(at: h.now)])
    }

    func testDestinationEqualToBoardingIsLostNotACrash() {
        let h = MonitorHarness(boarding: "BSK", destination: "BSK")
        XCTAssertEqual(h.start(happy()), [.destinationLost(at: h.now)])
    }

    func testBoardingStationMissingFromTheCallingPatternSearchesTheWholeList() {
        let h = MonitorHarness(boarding: "XXX")
        XCTAssertEqual(h.start(happy()), [.etaChanged(oldEta: nil, newEta: t("08:52:00"), fireAt: t("08:42:00"))])
    }
}
