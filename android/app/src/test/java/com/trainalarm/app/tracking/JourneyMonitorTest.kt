package com.trainalarm.app.tracking

import com.trainalarm.app.model.Service
import com.trainalarm.app.model.StopDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JourneyMonitorTest {
    private fun t(hhmmss: String) = ServiceBuilder.time(hhmmss)
    private fun happy(estimate: String? = "08:52:00"): Service = ServiceBuilder.happyPath(estimate)
    private fun etaChanged(old: String?, new: String, fire: String) =
        TrackerEvent.EtaChanged(old?.let(::t), t(new), t(fire))

    // Scenarios 1-5: ETA, threshold, drift

    @Test
    fun steadyLiveDataSchedulesOnceThenStaysQuiet() {
        val h = MonitorHarness()
        assertEquals(listOf(etaChanged(null, "08:52:00", "08:42:00")), h.start(happy()))
        assertEquals(emptyList<TrackerEvent>(), h.poll(happy()))
        assertEquals(emptyList<TrackerEvent>(), h.poll(happy()))
        assertEquals(listOf(t("08:42:00")), h.scheduled)
    }

    @Test
    fun thirtySecondShiftDoesNotReschedule() {
        val h = MonitorHarness()
        h.start(happy())
        assertEquals(emptyList<TrackerEvent>(), h.poll(happy("08:52:30")))
        assertEquals(listOf(t("08:42:00")), h.scheduled)
    }

    @Test
    fun exactlySixtySecondShiftReschedules() {
        val h = MonitorHarness()
        h.start(happy())
        assertEquals(listOf(etaChanged("08:52:00", "08:53:00", "08:43:00")), h.poll(happy("08:53:00")))
        assertEquals(listOf(t("08:42:00"), t("08:43:00")), h.scheduled)
    }

    @Test
    fun slowDriftIsComparedAgainstTheScheduledTimeNotThePreviousPoll() {
        val h = MonitorHarness()
        h.start(happy())
        assertEquals(emptyList<TrackerEvent>(), h.poll(happy("08:52:30")))
        assertEquals(listOf(etaChanged("08:52:30", "08:53:00", "08:43:00")), h.poll(happy("08:53:00")))
    }

    @Test
    fun delayThenBackToOnTimeReschedulesTwice() {
        val h = MonitorHarness()
        h.start(happy())
        assertEquals(listOf(etaChanged("08:52:00", "08:57:00", "08:47:00")), h.poll(happy("08:57:00")))
        assertEquals(listOf(etaChanged("08:57:00", "08:52:00", "08:42:00")), h.poll(happy("08:52:00")))
        assertEquals(listOf(t("08:42:00"), t("08:47:00"), t("08:42:00")), h.scheduled)
    }

    // Scenarios 6-10: cancelled, dropped, displayAs

    @Test
    fun wholeServiceCancelledEmitsServiceCancelledOnceAndLeavesTheAlarm() {
        val h = MonitorHarness()
        h.start(happy())
        val cancelled = ServiceBuilder.loadFixture("gb-nr-service-cancelled")

        assertEquals(listOf<TrackerEvent>(TrackerEvent.ServiceCancelled(h.now.plusSeconds(45))), h.poll(cancelled))
        assertEquals(emptyList<TrackerEvent>(), h.poll(cancelled))
        assertEquals(listOf(t("08:42:00")), h.scheduled)
        assertEquals(0, h.cancelCount)
    }

    @Test
    fun destinationAbsentEmitsDestinationLostOnce() {
        val h = MonitorHarness()
        h.start(happy())
        val dropped = ServiceBuilder.loadFixture("gb-nr-service-destination-dropped")

        assertEquals(listOf<TrackerEvent>(TrackerEvent.DestinationLost(h.now.plusSeconds(45))), h.poll(dropped))
        assertEquals(emptyList<TrackerEvent>(), h.poll(dropped))
        assertEquals(listOf(t("08:42:00")), h.scheduled)
    }

    @Test
    fun divertedCancelledAndPassDestinationsAreLost() {
        val fixture = ServiceBuilder.loadFixture("gb-nr-service-display-as")
        for (destination in listOf("SUR", "WOK", "CLJ")) {  // DIVERTED, CANCELLED, PASS
            val h = MonitorHarness(destination = destination, start = t("12:00:00"))
            assertEquals(destination, listOf<TrackerEvent>(TrackerEvent.DestinationLost(t("12:00:00"))), h.start(fixture))
            assertEquals(destination, emptyList<java.time.Instant>(), h.scheduled)
        }
    }

    @Test
    fun unknownDisplayAsAndTerminatesDoNotRaiseALoss() {
        val fixture = ServiceBuilder.loadFixture("gb-nr-service-display-as")

        val unknown = MonitorHarness(destination = "WIN", start = t("12:00:00"))  // displayAs "FUTURE_VALUE"
        assertEquals(listOf(etaChanged(null, "12:40:00", "12:30:00")), unknown.start(fixture))

        // TERMINATES means the train ends here: still a normal call. This stop is built, not taken
        // from the fixture: the fixture's BSK stop also has an actual arrival time, which ends
        // tracking once Task 3 lands.
        val terminatesStop = ServiceBuilder.stop(
            "BSK", scheduledArrival = "08:47:00", estimatedArrival = "08:52:00", displayAs = StopDisplay.TERMINATES
        )
        val terminates = MonitorHarness()
        assertEquals(
            listOf(etaChanged(null, "08:52:00", "08:42:00")),
            terminates.start(ServiceBuilder.replacing(happy(), 2, terminatesStop))
        )
    }

    @Test
    fun onlyTheDestinationsDepartureCancelledIsNotALoss() {
        val fixture = ServiceBuilder.loadFixture("gb-nr-service-departure-cancelled")
        val h = MonitorHarness(destination = "WOK", start = t("11:00:00"))
        assertEquals(listOf(etaChanged(null, "11:25:00", "11:15:00")), h.start(fixture))
    }

    @Test
    fun destinationArrivalCancelledWhileOtherStopsRunIsLost() {
        val h = MonitorHarness()
        h.start(happy())
        val arrivalCancelled = ServiceBuilder.replacing(
            happy(), 2, ServiceBuilder.stop("BSK", scheduledArrival = "08:47:00", isArrivalCancelled = true)
        )

        assertEquals(listOf<TrackerEvent>(TrackerEvent.DestinationLost(h.now.plusSeconds(45))), h.poll(arrivalCancelled))
    }

    // Scenario 11: restored

    @Test
    fun lostThenRestoredEmitsRestoredThenEtaChanged() {
        val h = MonitorHarness()
        h.start(happy())
        h.poll(ServiceBuilder.loadFixture("gb-nr-service-destination-dropped"))

        assertEquals(
            listOf(
                TrackerEvent.DestinationRestored(t("08:58:00")),
                etaChanged("08:52:00", "08:58:00", "08:48:00")
            ),
            h.poll(happy("08:58:00"))
        )
        assertEquals(listOf(t("08:42:00"), t("08:48:00")), h.scheduled)
    }

    // Scenarios 12-13: missing or vanished times

    @Test
    fun nilBestArrivalHoldsTheLastEtaAndEmitsNothing() {
        val h = MonitorHarness()
        h.start(happy())
        val noTimes = ServiceBuilder.replacing(happy(), 2, ServiceBuilder.stop("BSK"))

        assertEquals(emptyList<TrackerEvent>(), h.poll(noTimes))
        assertEquals(listOf(t("08:42:00")), h.scheduled)
    }

    @Test
    fun vanishedEstimateHoldsTheKnownDelayInsteadOfRevertingToTheTimetable() {
        val h = MonitorHarness()
        h.start(happy())  // live ETA 08:52:00
        val scheduledOnly = ServiceBuilder.replacing(happy(), 2, ServiceBuilder.stop("BSK", scheduledArrival = "08:47:00"))

        assertEquals(emptyList<TrackerEvent>(), h.poll(scheduledOnly))  // reverting to 08:47 would reschedule
        assertEquals(listOf(t("08:42:00")), h.scheduled)
    }

    @Test
    fun firstObservationWithNoTimesAtAllSchedulesNothing() {
        val h = MonitorHarness()
        val noTimes = ServiceBuilder.replacing(happy(), 2, ServiceBuilder.stop("BSK"))

        assertEquals(emptyList<TrackerEvent>(), h.start(noTimes))
        assertEquals(emptyList<java.time.Instant>(), h.scheduled)
    }

    // Scenarios 21-22 and the clamp

    @Test
    fun fireTimeAlreadyPastAtStartSchedulesNowOnceAndNeverAgain() {
        val h = MonitorHarness(start = t("08:45:00"))
        assertEquals(listOf(etaChanged(null, "08:52:00", "08:45:00")), h.start(happy()))
        assertTrue(h.state.alarmDue)

        assertEquals(emptyList<TrackerEvent>(), h.poll(happy()))
        assertEquals(emptyList<TrackerEvent>(), h.poll(happy("09:30:00")))  // a big slip after the alarm is due is not re-armed
        assertEquals(listOf(t("08:45:00")), h.scheduled)
    }

    @Test
    fun etaCloserThanTheLeadTimeAtStartSchedulesImmediately() {
        val h = MonitorHarness(start = t("08:50:00"))  // ETA 08:52 is only 2 minutes away, lead time is 10
        h.start(happy())
        assertEquals(listOf(t("08:50:00")), h.scheduled)
    }

    @Test
    fun fireTimeExactlyEqualToNowCountsAsPast() {
        val h = MonitorHarness(start = t("08:42:00"))  // desired fire time is exactly 08:42:00
        h.start(happy())
        assertEquals(listOf(t("08:42:00")), h.scheduled)
        assertTrue(h.state.alarmDue)
    }

    @Test
    fun aSlipAfterTheScheduledAlarmTimeHasPassedDoesNotRearmIt() {
        val h = MonitorHarness()
        h.start(happy())  // alarm confirmed for 08:42:00

        assertEquals(emptyList<TrackerEvent>(), h.poll(happy("08:58:00"), advancing = 2580.0))  // now is 08:43:00
        assertEquals(listOf(t("08:42:00")), h.scheduled)
        assertTrue(h.state.alarmDue)
    }

    // Destination resolution

    @Test
    fun destinationOnlyBeforeTheBoardingStopIsLost() {
        val service = ServiceBuilder.service(
            listOf(
                ServiceBuilder.stop("BSK", scheduledArrival = "08:10:00"),
                ServiceBuilder.stop("WAT"),
                ServiceBuilder.stop("WOK", scheduledArrival = "08:23:00")
            )
        )
        val h = MonitorHarness()
        assertEquals(listOf<TrackerEvent>(TrackerEvent.DestinationLost(h.now)), h.start(service))
    }

    @Test
    fun destinationEqualToBoardingIsLostNotACrash() {
        val h = MonitorHarness(boarding = "BSK", destination = "BSK")
        assertEquals(listOf<TrackerEvent>(TrackerEvent.DestinationLost(h.now)), h.start(happy()))
    }

    @Test
    fun boardingStationMissingFromTheCallingPatternSearchesTheWholeList() {
        val h = MonitorHarness(boarding = "XXX")
        assertEquals(listOf(etaChanged(null, "08:52:00", "08:42:00")), h.start(happy()))
    }
}
