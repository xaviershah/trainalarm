package com.trainalarm.app

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeChoice { SYSTEM, DARK, LIGHT }

/** The few reads and writes the store needs, so tests can use memory instead of SharedPreferences. */
interface KeyValues {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun getInt(key: String): Int?
    fun putInt(key: String, value: Int)
}

class SharedPrefsKeyValues(private val prefs: SharedPreferences) : KeyValues {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()
    override fun getInt(key: String): Int? = if (prefs.contains(key)) prefs.getInt(key, 0) else null
    override fun putInt(key: String, value: Int) = prefs.edit().putInt(key, value).apply()
}

/** User settings. See spec sections 4 and 5. */
class SettingsStore(private val store: KeyValues) {
    private val _theme = MutableStateFlow(
        store.getString(THEME)?.let { name -> ThemeChoice.values().firstOrNull { it.name == name } } ?: ThemeChoice.SYSTEM
    )
    val theme: StateFlow<ThemeChoice> = _theme

    /** null until the user has chosen a lead time (first run). */
    private val _defaultLeadMinutes = MutableStateFlow(store.getInt(LEAD)?.takeIf { it in LEAD_CHOICES })
    val defaultLeadMinutes: StateFlow<Int?> = _defaultLeadMinutes

    fun setTheme(choice: ThemeChoice) {
        store.putString(THEME, choice.name)
        _theme.value = choice
    }

    /** Ignores a value that is not one of [LEAD_CHOICES]. */
    fun setDefaultLeadMinutes(minutes: Int) {
        if (minutes !in LEAD_CHOICES) return
        store.putInt(LEAD, minutes)
        _defaultLeadMinutes.value = minutes
    }

    companion object {
        val LEAD_CHOICES = listOf(5, 10, 15, 20, 25, 30)
        private const val THEME = "theme"
        private const val LEAD = "defaultLeadMinutes"
    }
}
