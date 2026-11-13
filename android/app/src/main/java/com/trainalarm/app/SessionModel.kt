package com.trainalarm.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.trainalarm.app.alarm.AlarmScheduler
import com.trainalarm.app.model.Service
import com.trainalarm.app.model.Station
import com.trainalarm.app.model.Stop
import com.trainalarm.app.provider.ProviderError
import com.trainalarm.app.provider.StationDirectory
import com.trainalarm.app.provider.TrainDataProvider
import com.trainalarm.app.provider.hasCoordinate
import com.trainalarm.app.provider.nearest
import com.trainalarm.app.tracking.Coordinate
import com.trainalarm.app.tracking.FailureKind
import com.trainalarm.app.tracking.JourneyTracker
import com.trainalarm.app.tracking.LocationSource
import com.trainalarm.app.tracking.TrackerClock
import com.trainalarm.app.tracking.TrackerConfig
import com.trainalarm.app.tracking.TrackerEvent
import com.trainalarm.app.tracking.TrackerPlan
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The one holder of session state. It owns the tracker and shows what the screens need; it holds
 * no tracking logic (that is JourneyMonitor). See spec section 6.
 */
class SessionModel(
    private val provider: TrainDataProvider,
    private val scheduler: AlarmScheduler,
    private val directory: StationDirectory,
    private val location: LocationSource,
    private val clock: TrackerClock,
    private val permissions: PermissionsGateway,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val onAlarmError: (String, Throwable) -> Unit = { _, _ -> },
    private val locationControl: LocationControl? = null
) {
    var phase: SessionPhase by mutableStateOf(SessionPhase.Selecting)
        private set
    var from: Station? by mutableStateOf(null)
        private set
    var to: Station? by mutableStateOf(null)
        private set
    var board: BoardState by mutableStateOf(BoardState.Idle)
        private set
    var confirm: ConfirmState? by mutableStateOf(null)
        private set
    var blockedPermission: PermissionKind? by mutableStateOf(null)
        private set
    var permissionWarnings: List<PermissionKind> by mutableStateOf(emptyList())
        private set
    var trackerConfig: TrackerConfig? by mutableStateOf(null)
        private set
    var display: TrackingDisplay by mutableStateOf(TrackingDisplay())
        private set
    var isFindingNearest: Boolean by mutableStateOf(false)
        private set
    var nearestFailure: NearestFailure? by mutableStateOf(null)
        private set

    private var lastBoardLoad: Instant? = null

    /** Bumped whenever the picked stations change or a newer load starts; a late board response is dropped. */
    private var boardGeneration = 0

    /** Bumped whenever the picked stations change, the user goes back, or a newer choose starts. */
    private var chooseGeneration = 0
    private var tracker: JourneyTracker? = null
    private var eventJob: Job? = null

    // Picking

    fun search(query: String): List<Station> = directory.search(query)

    val canLoadBoard: Boolean
        get() {
            val f = from
            val t = to
            return f != null && t != null && f.id != t.id
        }

    private val isEditable: Boolean get() = phase == SessionPhase.Selecting || phase == SessionPhase.Confirming

    fun selectFrom(station: Station?) { if (isEditable) { nearestGeneration += 1; from = station; resetBoard() } }
    fun selectTo(station: Station?) { if (isEditable) { to = station; resetBoard() } }

    private fun resetBoard() {
        boardGeneration += 1
        chooseGeneration += 1
        board = BoardState.Idle
        lastBoardLoad = null
        confirm = null
        phase = SessionPhase.Selecting
    }

    /**
     * Loads the departures from `from` that call at `to`. Ignored within [BOARD_COOLDOWN_SECONDS] of
     * the last load unless [force], to protect the request budget.
     */
    suspend fun loadBoard(force: Boolean = false) {
        val f = from ?: return
        val t = to ?: return
        if (!canLoadBoard) return
        val now = clock.now()
        val last = lastBoardLoad
        if (!force && last != null && Duration.between(last, now).seconds < BOARD_COOLDOWN_SECONDS) return
        lastBoardLoad = now
        boardGeneration += 1
        val generation = boardGeneration
        board = BoardState.Loading
        val result = try {
            val services = provider.departureBoard(f, now, t)
            if (services.isEmpty()) BoardState.Empty else BoardState.Loaded(services)
        } catch (e: CancellationException) {
            if (generation == boardGeneration) board = BoardState.Idle
            throw e
        } catch (e: ProviderError.Http) {
            if (e.code == 429) BoardState.RateLimited else BoardState.Failed(describe(e))
        } catch (e: ProviderError) {
            BoardState.Failed(describe(e))
        }
        if (generation == boardGeneration) board = result
    }

    // Confirming

    /** Fetches the tapped train's full stops once, to find the arrival at the destination. */
    suspend fun choose(service: Service) {
        if (phase != SessionPhase.Selecting) return
        val f = from ?: return
        val t = to ?: return
        chooseGeneration += 1
        val generation = chooseGeneration
        try {
            val details = provider.serviceDetails(service.id, java.time.LocalDate.now(TimeText.london))
            if (generation != chooseGeneration || phase != SessionPhase.Selecting) return
            confirm = ConfirmState(details, arrival(details, f.id, t.id), settings.defaultLeadMinutes.value)
            blockedPermission = null
            permissionWarnings = emptyList()
            phase = SessionPhase.Confirming
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProviderError) {
            if (generation == chooseGeneration) board = BoardState.Failed(describe(e))
        }
    }

    fun backToSelecting() {
        chooseGeneration += 1
        confirm = null
        blockedPermission = null
        permissionWarnings = emptyList()
        phase = SessionPhase.Selecting
    }

    fun setLead(minutes: Int) {
        if (minutes !in SettingsStore.LEAD_CHOICES) return
        confirm = confirm?.copy(leadMinutes = minutes)
    }

    val alarmAt: Instant?
        get() {
            val c = confirm ?: return null
            val arrival = c.arrival ?: return null
            val lead = c.leadMinutes ?: return null
            return arrival.minusSeconds(lead * 60L)
        }

    /** The scheduler fires at once when the alarm time has passed; the confirm sheet says so. */
    val ringsStraightAway: Boolean
        get() = alarmAt?.let { !it.isAfter(clock.now()) } ?: false

    /** True from the moment `start()` begins until it returns, so a second tap during a permission prompt does nothing. */
    private var isStarting: Boolean by mutableStateOf(false)

    /** Bumped by `selectFrom`, `start` and `returnHome`; a nearest lookup from before drops its result. */
    private var nearestGeneration = 0

    val canStart: Boolean get() = phase == SessionPhase.Confirming && alarmAt != null && !isStarting

    // Starting and ending

    suspend fun start() {
        if (!canStart) return
        endEventCollection()   // no collector from an earlier session may outlive into this one
        isStarting = true
        nearestGeneration += 1
        try { begin() } finally { isStarting = false }
    }

    private suspend fun begin() {
        val c = confirm ?: return
        val f = from ?: return
        val t = to ?: return
        val lead = c.leadMinutes ?: return
        blockedPermission = null
        for (kind in permissions.requiredKinds) {
            var status = permissions.status(kind)
            if (status == PermissionStatus.NOT_DETERMINED) status = permissions.request(kind)
            if (status != PermissionStatus.GRANTED) { blockedPermission = kind; return }
        }
        val warnings = mutableListOf<PermissionKind>()
        for (kind in permissions.optionalKinds) {
            var status = permissions.status(kind)
            if (status == PermissionStatus.NOT_DETERMINED) status = permissions.request(kind)
            if (status != PermissionStatus.GRANTED) warnings += kind
        }
        permissionWarnings = warnings
        if (permissions.status(PermissionKind.LOCATION) == PermissionStatus.GRANTED) locationControl?.start()
        val config = TrackerConfig(leadTime = lead * 60.0, pollInterval = POLL_INTERVAL_SECONDS)
        trackerConfig = config
        val plan = TrackerPlan(
            boardingId = f.id,
            destinationId = t.id,
            destinationCoordinate = if (t.hasCoordinate) Coordinate(t.latitude, t.longitude) else null,
            config = config
        )
        val newTracker = JourneyTracker(
            plan, c.service, provider, location, ReportingScheduler(scheduler) { alarmScheduled(it) }, clock, onAlarmError
        )
        tracker = newTracker
        settings.setDefaultLeadMinutes(lead)
        val destination = destinationStop(c.service, f.id, t.id)
        display = TrackingDisplay(
            eta = destination?.bestArrival,
            scheduledArrival = destination?.scheduledArrival,
            alarmAt = alarmAt
        )
        testAlarm = TestAlarmState.Idle
        phase = SessionPhase.Tracking
        eventJob = scope.launch { newTracker.events.collect { handle(it) } }
        newTracker.start(scope)
    }

    /** Stops the tracker, cancels the alarm and ends the session. */
    suspend fun imOff() {
        endEventCollection()
        tracker?.stop()
        tracker = null
        locationControl?.stop()
        phase = SessionPhase.Ended(EndReason.IM_OFF)
    }

    /** Back to the empty picker after the session has ended. */
    fun returnHome() {
        endEventCollection()
        nearestGeneration += 1
        boardGeneration += 1
        chooseGeneration += 1
        board = BoardState.Idle
        lastBoardLoad = null
        confirm = null
        blockedPermission = null
        permissionWarnings = emptyList()
        display = TrackingDisplay()
        phase = SessionPhase.Selecting
    }

    // Nearest station

    /**
     * The "Nearest" chip. Asks for location only now, never re-prompts a refusal, fills From with
     * the closest station, and stops the GPS again.
     */
    suspend fun useNearestStation() {
        if (!isEditable || isFindingNearest) return
        nearestGeneration += 1
        val generation = nearestGeneration
        isFindingNearest = true
        nearestFailure = null
        var startedGps = false
        try {
            var status = permissions.status(PermissionKind.LOCATION)
            if (status == PermissionStatus.NOT_DETERMINED) status = permissions.request(PermissionKind.LOCATION)
            if (generation != nearestGeneration) return
            if (status != PermissionStatus.GRANTED) {
                nearestFailure = NearestFailure.PERMISSION_DENIED
                return
            }
            locationControl?.start()
            startedGps = true
            val maxAge = TrackerConfig(leadTime = 0.0, pollInterval = POLL_INTERVAL_SECONDS).maxFixAge
            for (attempt in 0..FIX_WAIT_ATTEMPTS) {
                if (generation != nearestGeneration) return
                val fix = location.latestFix()
                val fresh = fix?.takeIf {
                    it.accuracy.isFinite() && Duration.between(it.timestamp, clock.now()).toMillis() / 1000.0 <= maxAge
                }
                val station = fresh?.let { directory.nearest(it.latitude, it.longitude) }
                if (station != null) {
                    selectFrom(station)
                    return
                }
                if (attempt == FIX_WAIT_ATTEMPTS) break
                clock.sleep(FIX_WAIT_INTERVAL_SECONDS)
            }
            if (generation != nearestGeneration) return
            nearestFailure = NearestFailure.NO_FIX
        } finally {
            isFindingNearest = false
            // Only stop a GPS this lookup started, and never one a starting or running session now owns.
            if (startedGps && isEditable && !isStarting) locationControl?.stop()
        }
    }

    // Tracker events

    /**
     * Applies one tracker event (spec section 6). Events are state transitions, so a repeat changes
     * nothing, and events after the session ended are ignored.
     */
    suspend fun handle(event: TrackerEvent) {
        if (phase != SessionPhase.Tracking && phase != SessionPhase.Ringing) return
        when (event) {
            is TrackerEvent.EtaChanged -> display = display.copy(
                eta = event.newEta, alarmAt = event.fireAt, isEstimated = false, estimateSource = null, alarmNotSet = false
            )
            is TrackerEvent.EtaEstimated -> display = display.copy(
                eta = event.eta, isEstimated = true, estimateSource = event.source
            )
            is TrackerEvent.DestinationLost -> display = display.copy(destinationDropped = true)
            is TrackerEvent.ServiceCancelled -> display = display.copy(cancelled = true)
            is TrackerEvent.DestinationRestored -> display = display.copy(
                destinationDropped = false, cancelled = false, eta = event.newEta ?: display.eta
            )
            is TrackerEvent.FeedLost -> display = display.copy(
                feedLost = true, lastGoodAt = event.lastGoodAt, rateLimited = event.kind == FailureKind.Http(429)
            )
            is TrackerEvent.FeedRecovered -> display = display.copy(
                feedLost = false,
                rateLimited = false,
                eta = event.freshEta ?: display.eta,
                isEstimated = if (event.freshEta != null) false else display.isEstimated,
                estimateSource = if (event.freshEta != null) null else display.estimateSource
            )
            is TrackerEvent.AlarmSchedulingFailed -> display = display.copy(alarmNotSet = true)
            is TrackerEvent.Arrived -> {
                tracker?.stop()
                tracker = null
                locationControl?.stop()
                phase = SessionPhase.Ended(EndReason.ARRIVED)
                endEventCollection()   // last: this runs inside the collector, so the end-of-session work above must finish first
            }
        }
    }

    /** Cancels and forgets the tracker event collector, so a stopped tracker's buffered events cannot reach a later session. */
    private fun endEventCollection() {
        eventJob?.cancel()
        eventJob = null
    }

    /** Called by the platform when the alarm starts ringing (Task 14). */
    fun alarmRang() {
        if (phase == SessionPhase.Tracking) phase = SessionPhase.Ringing
    }

    /**
     * The scheduler accepted [fireAt], so the alarm is armed whatever failed before. The tracker announces
     * nothing when a retry succeeds on an unchanged ETA, so the model learns it here.
     */
    private fun alarmScheduled(fireAt: Instant) {
        if (phase != SessionPhase.Tracking && phase != SessionPhase.Ringing) return
        display = display.copy(alarmAt = fireAt, alarmNotSet = false)
    }

    /** Passes every call through and reports each success to the model. */
    private class ReportingScheduler(private val base: AlarmScheduler, private val onScheduled: (Instant) -> Unit) : AlarmScheduler {
        override suspend fun schedule(fireAt: Instant) {
            base.schedule(fireAt)
            onScheduled(fireAt)
        }

        override suspend fun cancel() = base.cancel()
    }

    // Settings

    /** Bumped whenever the permission picture may have changed, so the Settings screen re-reads it. */
    var permissionRevision: Int by mutableStateOf(0)
        private set
    var testAlarm: TestAlarmState by mutableStateOf(TestAlarmState.Idle)
        private set

    /** Every permission this platform asks for: the required ones first. */
    val settingsPermissionKinds: List<PermissionKind> get() = permissions.requiredKinds + permissions.optionalKinds

    fun permissionStatus(kind: PermissionKind): PermissionStatus =
        permissionRevision.let { permissions.status(kind) }   // reading the revision re-renders a screen after a refresh

    fun refreshPermissions() { permissionRevision += 1 }

    suspend fun requestPermission(kind: PermissionKind) {
        permissions.request(kind)
        refreshPermissions()
    }

    /** The scheduler holds one alarm, so a test alarm is only allowed before a journey starts. */
    val canTestAlarm: Boolean get() = phase == SessionPhase.Selecting || phase == SessionPhase.Confirming

    /** Called when Settings appears: drops a note about a test alarm that has already rung, or one that failed. */
    fun refreshTestAlarm() {
        val state = testAlarm
        if (state is TestAlarmState.Failed || (state is TestAlarmState.Scheduled && !state.at.isAfter(clock.now()))) {
            testAlarm = TestAlarmState.Idle
        }
    }

    suspend fun scheduleTestAlarm() {
        if (!canTestAlarm) return
        val fireAt = clock.now().plusSeconds(TEST_ALARM_DELAY_SECONDS)
        testAlarm = try {
            scheduler.schedule(fireAt)
            TestAlarmState.Scheduled(fireAt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TestAlarmState.Failed
        }
    }

    companion object {
        const val FIX_WAIT_ATTEMPTS = 10
        const val FIX_WAIT_INTERVAL_SECONDS = 0.5
        const val BOARD_COOLDOWN_SECONDS = 20L

        /** Polling every 45 s would use about 80 of RTT's 100 requests an hour. Stopgap until a proxy exists. */
        const val POLL_INTERVAL_SECONDS = 60.0

        /** The stop at [toId] after the boarding stop [fromId]; null if the train does not call there. */
        fun destinationStop(service: Service, fromId: String, toId: String): Stop? {
            val stops = service.journey.stops
            val boardIndex = stops.indexOfFirst { it.station.id == fromId }
            if (boardIndex < 0) return null
            return stops.drop(boardIndex + 1).firstOrNull { it.station.id == toId }
        }

        fun arrival(service: Service, fromId: String, toId: String): Instant? =
            destinationStop(service, fromId, toId)?.bestArrival

        private fun describe(error: ProviderError): String = when (error) {
            is ProviderError.Network -> "Can't reach the train data. Check your connection and try again."
            is ProviderError.Http -> "The train data service returned an error (${error.code}). Try again in a moment."
            is ProviderError.Malformed -> "The train data was not in the expected form."
            is ProviderError.NotImplemented -> "Something went wrong loading trains. Try again."
        }
    }
}
