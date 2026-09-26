package com.patmanak.contako.ui.locale

import android.app.LocaleManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.LocaleList
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class AppLanguage(val tag: String?) {
    SYSTEM(null),
    ENGLISH("en"),
    FRENCH("fr"),
    GERMAN("de"),
    SPANISH("es"),
    ITALIAN("it"),
    DUTCH("nl"),
    POLISH("pl"),
    PORTUGUESE("pt"),
}

interface LanguagePreferenceBoundary {
    val language: StateFlow<AppLanguage>
    fun setLanguage(language: AppLanguage)
}

class AndroidLanguagePreferenceStore(private val context: Context) : LanguagePreferenceBoundary,
    SharedPreferences.OnSharedPreferenceChangeListener {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val mutableLanguage = MutableStateFlow(readLanguage())
    override val language: StateFlow<AppLanguage> = mutableLanguage

    init { preferences.registerOnSharedPreferenceChangeListener(this) }

    override fun setLanguage(language: AppLanguage) {
        preferences.edit().putString(KEY_LANGUAGE, language.name).apply()
        mutableLanguage.value = language
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                language.tag?.let { LocaleList.forLanguageTags(it) } ?: LocaleList.getEmptyLocaleList()
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        if (key == KEY_LANGUAGE) mutableLanguage.value = readLanguage()
    }

    private fun readLanguage(): AppLanguage = preferences.getString(KEY_LANGUAGE, null)
        ?.let { stored -> AppLanguage.entries.firstOrNull { it.name == stored } }
        ?: AppLanguage.SYSTEM

    companion object {
        internal const val PREFERENCES = "appearance"
        internal const val KEY_LANGUAGE = "app_language"

        fun localizedContext(base: Context): Context {
            if (Build.VERSION.SDK_INT >= 33) return base
            val stored = base.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(KEY_LANGUAGE, null)
                ?.let { value -> AppLanguage.entries.firstOrNull { it.name == value } }
                ?: AppLanguage.SYSTEM
            val tag = stored.tag ?: return base
            val configuration = base.resources.configuration
            configuration.setLocales(LocaleList(Locale.forLanguageTag(tag)))
            return base.createConfigurationContext(configuration)
        }
    }
}
