package com.trainalarm.app

/**
 * The RTT bearer token, set at build time for the developer's own devices only. RTT forbids a
 * token in a distributable app (spec section 7), so builds that contain one are never shared.
 */
object RttToken {
    /** null for nothing or whitespace. */
    fun parse(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

    val current: String? get() = parse(BuildConfig.RTT_ACCESS_TOKEN)
}
