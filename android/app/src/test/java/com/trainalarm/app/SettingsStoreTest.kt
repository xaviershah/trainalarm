package com.trainalarm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsStoreTest {
    private class MemoryKeyValues : KeyValues {
        val map = mutableMapOf<String, Any>()
        override fun getString(key: String) = map[key] as? String
        override fun putString(key: String, value: String) { map[key] = value }
        override fun getInt(key: String) = map[key] as? Int
        override fun putInt(key: String, value: Int) { map[key] = value }
    }

    @Test fun firstRun_hasSystemThemeAndNoLeadTime() {
        val store = SettingsStore(MemoryKeyValues())
        assertEquals(ThemeChoice.SYSTEM, store.theme.value)
        assertNull(store.defaultLeadMinutes.value)
    }

    @Test fun themeAndLeadTime_persistAcrossInstances() {
        val kv = MemoryKeyValues()
        SettingsStore(kv).apply { setTheme(ThemeChoice.LIGHT); setDefaultLeadMinutes(20) }
        val second = SettingsStore(kv)
        assertEquals(ThemeChoice.LIGHT, second.theme.value)
        assertEquals(20, second.defaultLeadMinutes.value)
    }

    @Test fun aLeadTimeOutsideTheChoices_isIgnored() {
        val store = SettingsStore(MemoryKeyValues())
        store.setDefaultLeadMinutes(17)
        assertNull(store.defaultLeadMinutes.value)
    }

    @Test fun anUnknownStoredTheme_fallsBackToSystem() {
        val kv = MemoryKeyValues().apply { putString("theme", "NEON") }
        assertEquals(ThemeChoice.SYSTEM, SettingsStore(kv).theme.value)
    }

    @Test fun anInvalidStoredLeadTime_readsAsUnset() {
        val kv = MemoryKeyValues().apply { putInt("defaultLeadMinutes", 99) }
        assertNull(SettingsStore(kv).defaultLeadMinutes.value)
    }
}
