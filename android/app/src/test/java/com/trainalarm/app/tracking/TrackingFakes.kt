package com.trainalarm.app.tracking

import com.trainalarm.app.alarm.AlarmScheduler
import com.trainalarm.app.model.Service
import com.trainalarm.app.model.Station
import com.trainalarm.app.provider.ProviderError
import com.trainalarm.app.provider.TrainDataProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.time.Instant
import java.time.LocalDate

/** A clock the test drives. `sleep` suspends until [tick]; cancelling the sleeper throws CancellationException. */
class FakeClock(start: Instant) : TrackerClock {
    private var current = start
    private var gate = CompletableDeferred<Unit>()
    var sleepCount = 0
        private set

    override fun now(): Instant = current

    override suspend fun sleep(seconds: Double) {
        sleepCount += 1
        gate.await()
    }

    /** Advances the clock and wakes whoever is sleeping. */
    fun tick(advanceSeconds: Double) {
        current = current.plusMillis((advanceSeconds * 1000).toLong())
        val released = gate
        gate = CompletableDeferred()
        released.complete(Unit)
    }

    /** Yields until at least [count] sleeps have started (fails after 2 seconds). */
    suspend fun awaitSleepCount(count: Int) {
        withTimeout(2_000) { while (sleepCount < count) yield() }
    }
}

class FakeLocationSource : LocationSource {
    var fix: LocationFix? = null
    override fun latestFix(): LocationFix? = fix
}

/** Records every call. [failuresRemaining] makes the next N `schedule` calls throw. */
class FakeAlarmScheduler : AlarmScheduler {
    class Refused : Exception("refused")

    val attempts = mutableListOf<Instant>()
    val scheduled = mutableListOf<Instant>()
    var cancelCount = 0
    var failuresRemaining = 0

    override suspend fun schedule(fireAt: Instant) {
        attempts += fireAt
        if (failuresRemaining > 0) {
            failuresRemaining -= 1
            throw Refused()
        }
        scheduled += fireAt
    }

    override suspend fun cancel() {
        cancelCount += 1
    }
}

/** Serves [results] in order, repeating the last one forever. */
class FakeProvider : TrainDataProvider {
    val results = mutableListOf<Result<Service>>()
    var calls = 0
    val requestedIds = mutableListOf<String>()

    override suspend fun searchStations(query: String): List<Station> =
        throw ProviderError.NotImplemented("fake")

    override suspend fun departureBoard(station: Station, from: Instant): List<Service> =
        throw ProviderError.NotImplemented("fake")

    override suspend fun serviceDetails(serviceId: String, date: LocalDate): Service {
        calls += 1
        requestedIds += serviceId
        val result = if (results.size > 1) results.removeAt(0) else results.first()
        return result.getOrThrow()
    }
}
