package com.patmanak.contako.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patmanak.contako.ui.components.MinimumTouchTarget
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeFoundationTest {
    @Test
    fun reducedMotionMakesEveryNamedDurationInstant() {
        listOf(ContakoMotion.Fast, ContakoMotion.Standard, ContakoMotion.Navigation, ContakoMotion.Emphasized)
            .forEach { assertEquals(ContakoMotion.Instant, ContakoMotion.duration(true, it)) }
    }
    @Test
    fun approvedTextAndControlPairsMeetWcagContrastNumerically() {
        val normalTextPairs = listOf(
            "light primary" to (LightColors.onPrimary to LightColors.primary),
            "light text" to (LightColors.onSurface to LightColors.surface),
            "light weak text" to (LightSemanticColors.textWeak to LightColors.surface),
            "light primary container" to (LightColors.onPrimaryContainer to LightColors.primaryContainer),
            "light success" to (LightSemanticColors.success to LightColors.surface),
            "light pending" to (LightSemanticColors.pending to LightSemanticColors.pendingContainer),
            "light warning" to (LightSemanticColors.warning to LightSemanticColors.warningContainer),
            "light error" to (LightColors.error to LightColors.surface),
            "dark primary" to (DarkColors.primary to DarkColors.background),
            "dark on primary" to (DarkColors.onPrimary to DarkColors.primary),
            "dark text" to (DarkColors.onSurface to DarkColors.surface),
            "dark weak text" to (DarkSemanticColors.textWeak to DarkColors.surface),
            "dark primary container" to (DarkColors.onPrimaryContainer to DarkColors.primaryContainer),
            "dark success" to (DarkSemanticColors.success to DarkColors.surface),
            "dark pending" to (DarkSemanticColors.pending to DarkColors.background),
            "dark warning" to (DarkSemanticColors.warning to DarkColors.background),
            "dark error" to (DarkColors.error to DarkColors.background),
        )
        normalTextPairs.forEach { (name, pair) ->
            assertTrue("$name contrast was ${contrast(pair.first, pair.second)}", contrast(pair.first, pair.second) >= 4.5)
        }

        listOf(
            "light focus" to (LightSemanticColors.focus to LightColors.background),
            "light border strong" to (LightSemanticColors.borderStrong to LightColors.surface),
            "dark focus" to (DarkSemanticColors.focus to DarkColors.background),
            "dark border strong" to (DarkSemanticColors.borderStrong to DarkColors.surface),
        ).forEach { (name, pair) ->
            assertTrue("$name contrast was ${contrast(pair.first, pair.second)}", contrast(pair.first, pair.second) >= 3.0)
        }
    }

    @Test
    fun typographyMatchesApprovedScaleAndUsesSystemSansSerif() {
        val expected = listOf(
            ContakoTypography.headlineLarge to (32 to 40), ContakoTypography.headlineMedium to (28 to 36),
            ContakoTypography.headlineSmall to (24 to 32), ContakoTypography.titleLarge to (22 to 28),
            ContakoTypography.titleMedium to (16 to 24), ContakoTypography.titleSmall to (14 to 20),
            ContakoTypography.bodyLarge to (16 to 24), ContakoTypography.bodyMedium to (14 to 20),
            ContakoTypography.bodySmall to (12 to 16), ContakoTypography.labelLarge to (14 to 20),
            ContakoTypography.labelMedium to (12 to 16), ContakoTypography.labelSmall to (11 to 16),
        )
        expected.forEach { (style, scale) ->
            assertEquals(scale.first.sp, style.fontSize)
            assertEquals(scale.second.sp, style.lineHeight)
            assertEquals(androidx.compose.ui.text.font.FontFamily.SansSerif, style.fontFamily)
        }
    }

    @Test
    fun touchMotionThemeResolutionAndNoDynamicColorAreFixed() {
        assertEquals(48.dp, MinimumTouchTarget)
        assertEquals(0, ContakoMotion.duration(reducedMotion = true, ContakoMotion.Emphasized))
        assertEquals(200, ContakoMotion.duration(reducedMotion = false, ContakoMotion.Standard))
        assertFalse(resolveDarkTheme(ThemeMode.SYSTEM, false))
        assertTrue(resolveDarkTheme(ThemeMode.SYSTEM, true))
        assertFalse(resolveDarkTheme(ThemeMode.LIGHT, true))
        assertTrue(resolveDarkTheme(ThemeMode.DARK, false))
        val source = File("src/main/java/com/patmanak/contako/ui/theme/ContakoTheme.kt").readText()
        assertFalse(source.contains("dynamicLightColorScheme"))
        assertFalse(source.contains("dynamicDarkColorScheme"))
    }

    private fun contrast(first: Color, second: Color): Double {
        val firstLuminance = luminance(first)
        val secondLuminance = luminance(second)
        return (max(firstLuminance, secondLuminance) + 0.05) /
            (min(firstLuminance, secondLuminance) + 0.05)
    }

    private fun luminance(color: Color): Double {
        fun channel(value: Float): Double {
            val component = value.toDouble()
            return if (component <= 0.04045) component / 12.92 else ((component + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }
}
