package com.trainalarm.app.provider

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class HttpUrlConnectionTransportTest {

    @Test
    fun configure_setsTenSecondTimeoutsMethodAndHeaders() {
        val connection = URL("https://example.invalid/").openConnection() as HttpURLConnection
        HttpUrlConnectionTransport.configure(connection, mapOf("Accept" to "application/json"))
        assertEquals(10_000, connection.connectTimeout)
        assertEquals(10_000, connection.readTimeout)
        assertEquals("GET", connection.requestMethod)
        assertEquals("application/json", connection.getRequestProperty("Accept"))
    }
}
