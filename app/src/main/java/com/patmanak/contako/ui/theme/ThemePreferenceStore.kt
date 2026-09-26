package com.patmanak.contako.ui.theme

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface ThemePreferenceBoundary {
    val mode: StateFlow<ThemeMode>
    fun setMode(mode: ThemeMode)
}

class AndroidThemePreferenceStore(context: Context) : ThemePreferenceBoundary,
    SharedPreferences.OnSharedPreferenceChangeListener {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val mutableMode = MutableStateFlow(readMode())
    override val mode: StateFlow<ThemeMode> = mutableMode

    init { preferences.registerOnSharedPreferenceChangeListener(this) }

    override fun setMode(mode: ThemeMode) {
        preferences.edit().putString(KEY_MODE, mode.name).apply()
        mutableMode.value = mode
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        if (key == KEY_MODE) mutableMode.value = readMode()
    }

    private fun readMode(): ThemeMode = preferences.getString(KEY_MODE, null)
        ?.let { stored -> ThemeMode.entries.firstOrNull { it.name == stored } }
        ?: ThemeMode.SYSTEM

    companion object {
        internal const val PREFERENCES = "appearance"
        internal const val KEY_MODE = "theme_mode"
    }
}
