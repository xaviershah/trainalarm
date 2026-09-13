package com.trainalarm.app.model

/**
 * A specific train running on a specific day. [id] and [operatorName] are
 * provider-specific (e.g. an RTT service UID + TOC name for UK rail).
 */
data class Service(
    val id: String,
    val operatorName: String,
    val journey: Journey
)
