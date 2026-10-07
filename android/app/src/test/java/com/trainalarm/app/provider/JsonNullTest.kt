package com.trainalarm.app.provider

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsonNullTest {

    /** Simulates real Android org.json, whose optString(key, fallback) returns "null" for a JSON null. */
    private fun androidStyle(json: String): JSONObject = object : JSONObject(json) {
        override fun optString(key: String?, defaultValue: String?): String? =
            if (isNull(key)) "null" else super.optString(key, defaultValue)
    }

    @Test
    fun simulation_returnsTheStringNullForAJsonNull() {
        assertEquals("null", androidStyle("""{"displayAs": null}""").optString("displayAs", null))
    }

    @Test
    fun optStringOrNull_returnsNullForJsonNull_evenWithAndroidSemantics() {
        assertNull(androidStyle("""{"displayAs": null}""").optStringOrNull("displayAs"))
    }

    @Test
    fun optStringOrNull_returnsNullForMissingKey() {
        assertNull(androidStyle("""{}""").optStringOrNull("displayAs"))
    }

    @Test
    fun optStringOrNull_returnsTheValueWhenPresent() {
        assertEquals("CALL", androidStyle("""{"displayAs": "CALL"}""").optStringOrNull("displayAs"))
    }
}
