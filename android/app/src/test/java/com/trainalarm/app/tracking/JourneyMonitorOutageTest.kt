package com.trainalarm.app.tracking

import com.trainalarm.app.model.Service
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class JourneyMonitorOutageTest {
    private fun t(hhmmss: String) = ServiceBuilder.time(hhmmss)
    private fun happy(estimate: String? = "08:52:00"): Service = ServiceBuilder.happyPath(estimate)
    private val farDestination = Coordinate(0.0, 1.0)
    private val nearDestination = Coordinate(0.0, 0.1)

    private fun estimated(eta: String, source: EtaSource, staleness: Double, rescheduled: Boolean) =
        TrackerEvent.EtaEstimated(t(eta), source, staleness, rescheduled)

    private fun assertClose(expected: Instant, actual: Instant) {
        assertTrue("expected $expected but was $actual", Duration.between(expected, actual).abs().toMillis() <= 1)
    }

    /** start, one good poll at 08:09:00 with a fix at longitude 0, then a failed poll at 08:10:00 with a fix at 0.02. */
    private fun outageWithTwoFixes(
        h: MonitorHarness,
        firstFix: FixSpec = FixSpec(0.0, 0.0),
        secondFix: FixSpec = FixSpec(0.0, 0.02)
    ): List<TrackerEvent> {
        h.start(happy())
        h.poll(happy(), advancing = 540.0, fix = firstFix)  // now 08:09:00
        return h.fail(FailureKind.Network, advancing = 60.0, fix = secondFix)  // now 08:10:00
    }

    // Scenarios 14-17: the outage fallback

    @Test
    fun firstPollFailingReportsNoLastGoodTimeAndFallsBackToTheSnapshot() {
        val h = MonitorHarness()
        h.start(happy())
        assertEquals(
            listOf(
                TrackerEvent.FeedLost(null, FailureKind.Network),
                estimated("08:52:00", EtaSource.LAST_KNOWN_TRAJECTORY, 45.0, false)
            ),
            h.fail(FailureKind.Network)
        )
    }

    @Test
    fun outageWithoutGpsHoldsTheLastLiveEtaAndFeedLostFiresOnce() {
        val h = MonitorHarness()
        h.start(happy())
        h.poll(happy())  // good at 08:00:45

        assertEquals(
            listOf(
                TrackerEvent.FeedLost(t("08:00:45"), FailureKind.Http(503)),
                estimated("08:52:00", EtaSource.LAST_KNOWN_TRAJECTORY, 45.0, false)
            ),
            h.fail(FailureKind.Http(503))
        )
        assertEquals(
            listOf<TrackerEvent>(estimated("08:52:00", EtaSource.LAST_KNOWN_TRAJECTORY, 90.0, false)),
            h.fail(FailureKind.Http(503))
        )
        assertEquals(listOf(t("08:42:00")), h.scheduled)
    }

    @Test
    fun outageWithGpsApproachingUsesGpsPaceAndReschedules() {
        val h = MonitorHarness(coordinate = farDestination)
        val events = outageWithTwoFixes(h)

        assertEquals(2, events.size)
        assertEquals(TrackerEvent.FeedLost(t("08:09:00"), FailureKind.Network), events[0])
        val estimate = events[1] as TrackerEvent.EtaEstimated
        assertClose(t("08:59:00"), estimate.eta)
        assertEquals(EtaSource.GPS_PACE, estimate.source)
        assertEquals(60.0, estimate.staleness, 0.0)
        assertTrue(estimate.rescheduled)
        assertEquals(2, h.scheduled.size)
        assertClose(t("08:49:00"), h.scheduled[1])
    }

    @Test
    fun gpsFallbackIsNeverEarlierThanTheLastLiveEtaMinusTheGuard() {
        val h = MonitorHarness(coordinate = nearDestination)  // pace would put the ETA at 08:14:00
        val events = outageWithTwoFixes(h)

        assertEquals(
            listOf(
                TrackerEvent.FeedLost(t("08:09:00"), FailureKind.Network),
                estimated("08:51:00", EtaSource.GPS_PACE, 60.0, true)
            ),
            events
        )
        assertEquals(listOf(t("08:42:00"), t("08:41:00")), h.scheduled)
    }

    @Test
    fun withoutADestinationCoordinateTheFallbackStaysOnTheTrajectory() {
        val h = MonitorHarness(coordinate = null)
        val events = outageWithTwoFixes(h)
        assertEquals(estimated("08:52:00", EtaSource.LAST_KNOWN_TRAJECTORY, 60.0, false), events.last())
    }

    @Test
    fun inaccurateFixesAreIgnored() {
        val h = MonitorHarness(coordinate = farDestination)
        val events = outageWithTwoFixes(h, firstFix = FixSpec(0.0, 0.0, accuracy = 500.0))
        assertEquals(estimated("08:52:00", EtaSource.LAST_KNOWN_TRAJECTORY, 60.0, false), events.last())
    }

    @Test
    fun gpsEtaDiscardsFixesThatAgeOutWhenLaterTicksCarryNoNewFix() {
        val h = MonitorHarness(coordinate = farDestination)
        h.start(happy())
        h.poll(happy(), advancing = 45.0, fix = FixSpec(0.0, 0.0))
        h.poll(happy(), advancing = 45.0, fix = FixSpec(0.0, 0.02))  // two fresh fixes: GPS would apply
        // No new fix arrives, so the insertion-time prune never runs; only gpsEta's own age filter
        // can drop the now 245s and 200s old fixes (the limit is 135s).
        val events = h.fail(FailureKind.Network, advancing = 200.0, fix = null)

        assertEquals(estimated("08:52:00", EtaSource.LAST_KNOWN_TRAJECTORY, 200.0, false), events.last())
    }

    @Test
    fun staleFixesAreIgnored() {
        val h = MonitorHarness(coordinate = farDestination)
        h.start(happy())
        h.poll(happy(), advancing = 45.0, fix = FixSpec(0.0, 0.0))
        val events = h.fail(FailureKind.Network, advancing = 300.0, fix = FixSpec(0.0, 0.02))  // the first fix is now 300s old

        assertEquals(estimated("08:52:00", EtaSource.LAST_KNOWN_TRAJECTORY, 300.0, false), events.last())
    }

    @Test
    fun aStationaryTrainBelowMinimumPaceHoldsTheLastEta() {
        val h = MonitorHarness(coordinate = farDestination)
        val events = outageWithTwoFixes(h, secondFix = FixSpec(0.0, 0.0))
        assertEquals(estimated("08:52:00", EtaSource.LAST_KNOWN_TRAJECTORY, 60.0, false), events.last())
    }

    @Test
    fun anOutageWhileTheDestinationIsLostEmitsOnlyFeedLost() {
        val h = MonitorHarness()
        h.start(happy())
        h.poll(ServiceBuilder.loadFixture("gb-nr-service-destination-dropped"))

        assertEquals(listOf<TrackerEvent>(TrackerEvent.FeedLost(t("08:00:45"), FailureKind.Network)), h.fail())
    }

    // Scenarios 18-20: recovery and flapping

    @Test
    fun recoveryWithADelayReportsRecoveryThenTheChange() {
        val h = MonitorHarness()
        h.start(happy())
        h.fail()  // 08:00:45

        assertEquals(
            listOf(
                TrackerEvent.FeedRecovered(45.0, t("08:57:00"), 300.0),
                TrackerEvent.EtaChanged(t("08:52:00"), t("08:57:00"), t("08:47:00"))
            ),
            h.poll(happy("08:57:00"))  // 08:01:30
        )
        assertFalse(h.state.feedDown)
    }

    @Test
    fun recoveryThatRevealsADroppedDestinationReportsRecoveryFirst() {
        val h = MonitorHarness()
        h.start(happy())
        h.fail()

        val events = h.poll(ServiceBuilder.loadFixture("gb-nr-service-destination-dropped"))
        assertEquals(listOf(TrackerEvent.FeedRecovered(45.0, null, null), TrackerEvent.DestinationLost(h.now)), events)
    }

    @Test
    fun flappingYieldsTwoFeedLostAndOneFeedRecovered() {
        val h = MonitorHarness()
        h.start(happy())
        val all = h.fail() + h.poll(happy()) + h.fail()

        assertEquals(2, all.count { it is TrackerEvent.FeedLost })
        assertEquals(1, all.count { it is TrackerEvent.FeedRecovered })
    }

    // Scenario 23: scheduler refusal

    @Test
    fun aSchedulerRefusalIsReportedThenRetriedOnTheNextTickWithoutANewEtaChanged() {
        val h = MonitorHarness()
        h.start(happy())
        h.failNextSchedule = true

        assertEquals(
            listOf(
                TrackerEvent.EtaChanged(t("08:52:00"), t("08:58:00"), t("08:48:00")),
                TrackerEvent.AlarmSchedulingFailed(t("08:48:00"), "refused")
            ),
            h.poll(happy("08:58:00"))
        )
        assertEquals(listOf(t("08:42:00")), h.scheduled)

        assertEquals(emptyList<TrackerEvent>(), h.poll(happy("08:58:00")))
        assertEquals(listOf(t("08:42:00"), t("08:48:00")), h.scheduled)
        assertEquals(listOf(t("08:42:00"), t("08:48:00"), t("08:48:00")), h.attempts)
        assertFalse(h.state.retryPending)
    }

    @Test
    fun aRefusedClampedAlarmIsRetriedAtTheCurrentTimeAndMarkedDue() {
        val h = MonitorHarness()
        h.start(happy())
        h.failNextSchedule = true

        // 08:41:00 with ETA 08:50:00: the alarm time (08:40:00) has already passed, so it is clamped to now.
        assertEquals(
            listOf(
                TrackerEvent.EtaChanged(t("08:52:00"), t("08:50:00"), t("08:41:00")),
                TrackerEvent.AlarmSchedulingFailed(t("08:41:00"), "refused")
            ),
            h.poll(happy("08:50:00"), advancing = 2460.0)
        )
        assertTrue(h.state.retryPending)

        assertEquals(emptyList<TrackerEvent>(), h.poll(happy("08:50:00")))  // 08:41:45
        assertEquals(listOf(t("08:42:00"), t("08:41:00"), t("08:41:45")), h.attempts)
        assertEquals(listOf(t("08:42:00"), t("08:41:45")), h.scheduled)
        assertFalse(h.state.retryPending)
        assertTrue(h.state.alarmDue)
    }

    // Scenario 24: arrival

    @Test
    fun confirmedArrivalEmitsArrivedCancelsTheAlarmAndEndsTracking() {
        val h = MonitorHarness()
        h.start(happy())
        val arrived = ServiceBuilder.replacing(
            happy(), 2,
            ServiceBuilder.stop("BSK", scheduledArrival = "08:47:00", estimatedArrival = "08:50:00", hasArrived = true)
        )

        assertEquals(listOf<TrackerEvent>(TrackerEvent.Arrived(h.now.plusSeconds(45))), h.poll(arrived))
        assertEquals(1, h.cancelCount)
        assertTrue(h.state.finished)
        assertEquals(emptyList<TrackerEvent>(), h.poll(happy()))  // finished: ignored
        assertEquals(1, h.cancelCount)
        assertEquals(listOf(t("08:42:00")), h.scheduled)
    }

    @Test
    fun noArrivalReportEndsTrackingOnceTheGracePeriodHasPassed() {
        val h = MonitorHarness()
        h.start(happy())  // ETA 08:52:00, grace 10 minutes
        assertEquals(listOf<TrackerEvent>(TrackerEvent.Arrived(t("09:03:00"))), h.poll(happy(), advancing = 3780.0))
        assertEquals(1, h.cancelCount)
    }

    @Test
    fun aDestinationThatHasAlreadyArrivedEndsTrackingEvenWhenItIsMarkedTerminates() {
        // The fixture's BSK stop is TERMINATES and has an actual arrival time (12:48).
        val h = MonitorHarness(destination = "BSK", start = t("12:00:00"))
        assertEquals(
            listOf<TrackerEvent>(TrackerEvent.Arrived(t("12:00:00"))),
            h.start(ServiceBuilder.loadFixture("gb-nr-service-display-as"))
        )
        assertEquals(1, h.cancelCount)
    }
}
