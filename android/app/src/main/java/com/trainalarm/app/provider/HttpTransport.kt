package com.trainalarm.app.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/** A status code and body. Also returned for 4xx/5xx, so the caller can report `Http(code)`. */
data class HttpResult(val status: Int, val body: String)

/** The one place [RttProvider] touches the network, so tests can fake it. */
interface HttpTransport {
    /** Throws IOException for transport failures (including timeouts); never wraps cancellation. */
    suspend fun get(url: String, headers: Map<String, String>): HttpResult
}

class HttpUrlConnectionTransport : HttpTransport {
    override suspend fun get(url: String, headers: Map<String, String>): HttpResult =
        withContext(Dispatchers.IO) {
            val connection = URL(url).openConnection() as HttpURLConnection
            configure(connection, headers)
            try {
                val status = connection.responseCode
                // inputStream throws on 4xx/5xx; errorStream carries that body.
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                HttpResult(status, stream?.bufferedReader()?.use(BufferedReader::readText) ?: "")
            } finally {
                connection.disconnect()
            }
        }

    companion object {
        const val TIMEOUT_MS = 10_000

        internal fun configure(connection: HttpURLConnection, headers: Map<String, String>) {
            connection.requestMethod = "GET"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        }
    }
}
