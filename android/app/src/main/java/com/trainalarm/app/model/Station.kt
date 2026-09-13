package com.trainalarm.app.model

/**
 * A physical station. [id] is provider-specific (e.g. a CRS code for UK
 * rail) - callers should treat it as an opaque identifier, not assume a
 * format, since a future non-UK provider will use a different scheme.
 */
data class Station(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double
)
