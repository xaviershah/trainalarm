package com.trainalarm.app.provider

import com.trainalarm.app.model.Station
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Instant
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException

private class FakeTransport(private val reply: () -> HttpResult) : HttpTransport {
    var lastHeaders: Map<String, String> = emptyMap()

    override suspend fun get(url: String, headers: Map<String, String>): HttpResult {
        lastHeaders = headers
        return reply()
    }
}

class RttProviderTest {
    private val waterloo = Station("WAT", "London Waterloo", 51.5031, -0.1132)

    private fun provider(reply: () -> HttpResult) = RttProvider("token", FakeTransport(reply))

    /** Runs [block] and returns whatever it threw (caught inside the coroutine), or null. */
    private fun thrown(block: suspend () -> Unit): Throwable? = runBlocking {
        try {
            block()
            null
        } catch (e: Throwable) {
            e
        }
    }

    private fun serviceDetailsError(reply: () -> HttpResult): Throwable? =
        thrown { provider(reply).serviceDetails("gb-nr:L01525:2026-09-13", LocalDate.of(2026, 9, 13)) }

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/fixtures/$name")!!.bufferedReader().readText()

    @Test(expected = ProviderError.NotImplemented::class)
    fun searchStations_isNotImplemented() {
        runBlocking { RttProvider("test").searchStations("Reading") }
    }

    @Test
    fun serviceDetails_parsesAGoodResponse() = runBlocking<Unit> {
        val service = provider { HttpResult(200, fixture("gb-nr-service.json")) }
            .serviceDetails("x", LocalDate.of(2026, 9, 13))
        assertEquals("gb-nr:L01525:2026-09-13", service.id)
    }

    @Test
    fun request_carriesTheBearerTokenAndAcceptHeader() = runBlocking<Unit> {
        val transport = FakeTransport { HttpResult(200, fixture("gb-nr-service.json")) }
        RttProvider("token", transport).serviceDetails("x", LocalDate.of(2026, 9, 13))
        assertEquals("Bearer token", transport.lastHeaders["Authorization"])
        assertEquals("application/json", transport.lastHeaders["Accept"])
    }

    @Test
    fun httpErrors_carryTheStatusCode() {
        for (status in listOf(401, 404, 429, 500)) {  // 401 bad token, 429 rate limit
            val error = serviceDetailsError { HttpResult(status, "") }
            assertTrue("status $status gave $error", error is ProviderError.Http)
            assertEquals(status, (error as ProviderError.Http).code)
        }
    }

    @Test
    fun ioFailure_isNetwork() {
        assertTrue(serviceDetailsError { throw IOException("boom") } is ProviderError.Network)
    }

    @Test
    fun socketTimeout_isNetworkSoThePollLoopCanRetry() {
        assertTrue(serviceDetailsError { throw SocketTimeoutException("read timed out") } is ProviderError.Network)
    }

    @Test
    fun cancellation_propagatesUnwrapped() {
        val error = serviceDetailsError { throw CancellationException("stop") }
        assertTrue("got $error", error is CancellationException)
    }

    @Test
    fun nonJsonBody_isMalformed() {
        assertTrue(serviceDetailsError { HttpResult(200, "<html>oops</html>") } is ProviderError.Malformed)
    }

    @Test
    fun emptyOkBody_isMalformed() {
        assertTrue(serviceDetailsError { HttpResult(200, "") } is ProviderError.Malformed)
    }

    @Test
    fun jsonArrayBody_isMalformed() {
        assertTrue(serviceDetailsError { HttpResult(200, "[]") } is ProviderError.Malformed)
    }

    @Test
    fun missingServiceObject_isMalformed() {
        val error = serviceDetailsError { HttpResult(200, "{}") }
        assertTrue("got $error", error is ProviderError.Malformed)
        assertEquals("missing 'service' object", (error as ProviderError.Malformed).reason)
    }

    @Test
    fun serviceDetails204_isMalformed() {
        val error = serviceDetailsError { HttpResult(204, "") }
        assertTrue("got $error", error is ProviderError.Malformed)
        assertEquals("empty (204) response", (error as ProviderError.Malformed).reason)
    }

    @Test
    fun departureBoard204_isAnEmptyBoardNotAnError() = runBlocking<Unit> {
        val board = provider { HttpResult(204, "") }.departureBoard(waterloo, Instant.parse("2026-09-13T08:00:00Z"))
        assertEquals(emptyList<Any>(), board)
    }

    @Test
    fun departureBoard_returnsEveryLineUp() = runBlocking<Unit> {
        val board = provider { HttpResult(200, fixture("gb-nr-location.json")) }
            .departureBoard(waterloo, Instant.parse("2026-09-13T14:00:00Z"))
        assertEquals(listOf("gb-nr:L02001:2026-09-13", "gb-nr:L02002:2026-09-13"), board.map { it.id })
    }

    @Test
    fun departureBoard_withNoServicesKey_isEmpty() = runBlocking<Unit> {
        val board = provider { HttpResult(200, "{}") }.departureBoard(waterloo, Instant.parse("2026-09-13T14:00:00Z"))
        assertEquals(emptyList<Any>(), board)
    }
}
