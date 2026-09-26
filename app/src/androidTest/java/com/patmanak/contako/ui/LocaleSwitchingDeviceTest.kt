package com.patmanak.contako.ui

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.R
import com.patmanak.contako.ui.locale.AndroidLanguagePreferenceStore
import com.patmanak.contako.ui.locale.AppLanguage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocaleSwitchingDeviceTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().clear().commit()
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.getEmptyLocaleList()
        }
    }

    @After
    fun tearDown() {
        AndroidLanguagePreferenceStore(context).setLanguage(AppLanguage.SYSTEM)
    }

    @Test
    fun languagePersistsAndFrameworkResourcesSwitch() {
        val store = AndroidLanguagePreferenceStore(context)
        store.setLanguage(AppLanguage.FRENCH)
        assertEquals(AppLanguage.FRENCH, AndroidLanguagePreferenceStore(context).language.value)

        val localized = languageContext("fr")
        assertEquals("Mot de passe", localized.getString(R.string.auth_password))

        store.setLanguage(AppLanguage.GERMAN)
        val german = languageContext("de")
        assertEquals("Passwort", german.getString(R.string.auth_password))
    }

    private fun languageContext(tag: String): Context {
        val configuration = context.resources.configuration
        configuration.setLocales(LocaleList.forLanguageTags(tag))
        return context.createConfigurationContext(configuration)
    }
}
