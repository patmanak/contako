package com.patmanak.contako.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.ui.components.PrimaryButton
import com.patmanak.contako.ui.theme.AndroidThemePreferenceStore
import com.patmanak.contako.ui.theme.ThemeMode
import com.patmanak.contako.ui.theme.ContakoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeFoundationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var context: Context

    @Before
    fun clearPreferences() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun preferencePersistsAcrossStoreRecreation() {
        val first = AndroidThemePreferenceStore(context)
        assertEquals(ThemeMode.SYSTEM, first.mode.value)
        first.setMode(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, AndroidThemePreferenceStore(context).mode.value)
        AndroidThemePreferenceStore(context).setMode(ThemeMode.LIGHT)
        assertEquals(ThemeMode.LIGHT, AndroidThemePreferenceStore(context).mode.value)
    }

    @Test
    fun systemThemeTransitionRecomposesAndButtonTargetIsAtLeast48Dp() {
        var systemDark by mutableStateOf(false)
        compose.setContent {
            ContakoTheme(themeMode = ThemeMode.SYSTEM, systemDarkTheme = systemDark) {
                Surface(Modifier.fillMaxSize()) {
                    Box { PrimaryButton("Theme target", {}, Modifier.testTag("theme-target")) }
                }
            }
        }
        val lightPixel = compose.onRoot().captureToImage().toPixelMap()[0, 0]
        compose.onNodeWithTag("theme-target").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        compose.runOnIdle { systemDark = true }
        compose.waitForIdle()
        val darkPixel = compose.onRoot().captureToImage().toPixelMap()[0, 0]
        assertTrue(lightPixel != darkPixel)
        assertTrue(darkPixel != Color.Unspecified)
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test
    fun primaryActionKeepsSemanticsKeyboardFocusAnd48DpAt200PercentFontScale() {
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f),
            ) {
                ContakoTheme(reducedMotion = true) {
                    PrimaryButton("Scaled keyboard action", {}, Modifier.testTag("scaled-action"))
                }
            }
        }
        compose.onNodeWithTag("scaled-action")
            .assert(isFocusable())
            .assertHasClickAction()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.Enter) }
    }
}
