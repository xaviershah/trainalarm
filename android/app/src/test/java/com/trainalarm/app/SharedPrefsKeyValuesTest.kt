package com.trainalarm.app

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedPrefsKeyValuesTest {
    /** Only the reads the store uses; a wrong-typed value throws ClassCastException, like the real thing. */
    private class FakePrefs(private val map: MutableMap<String, Any>) : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String?, defValue: String?): String? = when (val v = map[key]) {
            null -> defValue
            is String -> v
            else -> throw ClassCastException()
        }
        override fun getInt(key: String?, defValue: Int): Int = when (val v = map[key]) {
            null -> defValue
            is Int -> v
            else -> throw ClassCastException()
        }
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = throw UnsupportedOperationException()
        override fun getLong(key: String?, defValue: Long): Long = throw UnsupportedOperationException()
        override fun getFloat(key: String?, defValue: Float): Float = throw UnsupportedOperationException()
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = throw UnsupportedOperationException()
        override fun edit(): SharedPreferences.Editor = throw UnsupportedOperationException()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    @Test fun aStoredInt_isRead() {
        val kv = SharedPrefsKeyValues(FakePrefs(mutableMapOf("defaultLeadMinutes" to 12)))
        assertEquals(12, kv.getInt("defaultLeadMinutes"))
    }

    @Test fun aMissingKey_readsAsNull() {
        assertNull(SharedPrefsKeyValues(FakePrefs(mutableMapOf())).getInt("defaultLeadMinutes"))
    }

    @Test fun aWrongTypedValue_readsAsNullInsteadOfThrowing() {
        val kv = SharedPrefsKeyValues(FakePrefs(mutableMapOf("defaultLeadMinutes" to "oops")))
        assertNull(kv.getInt("defaultLeadMinutes"))
    }

    @Test fun aWrongTypedLeadTime_leavesTheSettingsStoreUnsetAtLaunch() {
        val store = SettingsStore(SharedPrefsKeyValues(FakePrefs(mutableMapOf("defaultLeadMinutes" to "oops"))))
        assertNull(store.defaultLeadMinutes.value)
    }
}
