package com.trainalarm.app.provider

/**
 * How a [TrainDataProvider] call can fail. Identical on every provider and on
 * both platforms. Cancellation is NOT an error: CancellationException propagates unchanged.
 */
sealed class ProviderError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Transport failure: no connection, timeout, DNS, TLS, interrupted read. */
    class Network(message: String, cause: Throwable? = null) : ProviderError(message, cause)

    /** Non-2xx HTTP status. */
    class Http(val code: Int) : ProviderError("HTTP $code")

    /** Body was not valid JSON, was empty on a 200, or a required field was missing. */
    class Malformed(val reason: String, cause: Throwable? = null) : ProviderError(reason, cause)

    /** A capability the provider deliberately lacks (e.g. free-text station search). */
    class NotImplemented(message: String) : ProviderError(message)
}
