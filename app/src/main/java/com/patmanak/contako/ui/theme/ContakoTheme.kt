package com.patmanak.contako.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class ThemeMode { SYSTEM, LIGHT, DARK }

object ContakoPalette {
    val Brand900 = Color(0xFF372580)
    val Brand800 = Color(0xFF4D34B3)
    val Brand700 = Color(0xFF5C3FD9)
    val Brand600 = Color(0xFF6D4AFF)
    val Brand500 = Color(0xFF8A6EFF)
    val Brand400 = Color(0xFFA779FF)
    val Brand300 = Color(0xFFC4B7FF)
    val Brand100 = Color(0xFFEAE5FF)
    val Brand050 = Color(0xFFF5F2FF)
}

@Immutable
data class ContakoSemanticColors(
    val primaryPressed: Color,
    val surfaceSecondary: Color,
    val surfaceDeep: Color,
    val textWeak: Color,
    val textHint: Color,
    val textDisabled: Color,
    val icon: Color,
    val iconWeak: Color,
    val border: Color,
    val borderStrong: Color,
    val focus: Color,
    val success: Color,
    val successContainer: Color,
    val pending: Color,
    val pendingContainer: Color,
    val warning: Color,
    val warningContainer: Color,
)

val LightSemanticColors = ContakoSemanticColors(
    primaryPressed = Color(0xFF5C3FD9), surfaceSecondary = Color(0xFFEFEEF2),
    surfaceDeep = Color(0xFFE9EAEC), textWeak = Color(0xFF535964),
    textHint = Color(0xFF747088), textDisabled = Color(0xFF848993),
    icon = Color(0xFF31343A), iconWeak = Color(0xFF535964), border = Color(0xFFC8CBD0),
    borderStrong = Color(0xFF848993), focus = Color(0xFF5C3FD9), success = Color(0xFF0F735A),
    successContainer = Color(0xFFDBF3EE), pending = Color(0xFFA4512F),
    pendingContainer = Color(0xFFFFF0E8), warning = Color(0xFF8A3600),
    warningContainer = Color(0xFFFFD0B3),
)

val DarkSemanticColors = ContakoSemanticColors(
    primaryPressed = Color(0xFFC4B7FF), surfaceSecondary = Color(0xFF20202E),
    surfaceDeep = Color(0xFF222230), textWeak = Color(0xFFA4A4AB),
    textHint = Color(0xFF9292F9), textDisabled = Color(0xFF75757D),
    icon = Color(0xFFEDEDEE), iconWeak = Color(0xFFA4A4AB), border = Color(0xFF393945),
    borderStrong = Color(0xFF75757D), focus = Color(0xFFC4B7FF), success = Color(0xFF85C990),
    successContainer = Color(0xFF1E5C4F), pending = Color(0xFFFF9B62),
    pendingContainer = Color(0xFF8A3600), warning = Color(0xFFFFC978),
    warningContainer = Color(0xFFA4512F),
)

val LightColors = lightColorScheme(
    primary = Color(0xFF6D4AFF), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEAE5FF), onPrimaryContainer = Color(0xFF241C43),
    background = Color(0xFFF4F5F8), onBackground = Color(0xFF191927),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF191927),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF7F6FA),
    surfaceContainer = Color(0xFFF0EEF5), surfaceContainerHigh = Color(0xFFEAE7F0),
    surfaceContainerHighest = Color(0xFFE3E0EA),
    surfaceVariant = Color(0xFFEFEEF2), onSurfaceVariant = Color(0xFF535964),
    outline = Color(0xFFC8CBD0), outlineVariant = Color(0xFFE9EAEC),
    error = Color(0xFFBA1E55), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9CDD6), onErrorContainer = Color(0xFF191927),
    scrim = Color(0x7A191927),
)

val DarkColors = darkColorScheme(
    primary = Color(0xFFA779FF), onPrimary = Color(0xFF191927),
    primaryContainer = Color(0xFF35356A), onPrimaryContainer = Color(0xFFEAE5FF),
    background = Color(0xFF191927), onBackground = Color(0xFFEDEDEE),
    surface = Color(0xFF1E1E2B), onSurface = Color(0xFFEDEDEE),
    surfaceContainerLowest = Color(0xFF14141E), surfaceContainerLow = Color(0xFF22222F),
    surfaceContainer = Color(0xFF272735), surfaceContainerHigh = Color(0xFF30303F),
    surfaceContainerHighest = Color(0xFF393949),
    surfaceVariant = Color(0xFF20202E), onSurfaceVariant = Color(0xFFA4A4AB),
    outline = Color(0xFF393945), outlineVariant = Color(0xFF222230),
    error = Color(0xFFE15976), onError = Color(0xFF191927),
    errorContainer = Color(0xFF8A2E3F), onErrorContainer = Color(0xFFEDEDEE),
    scrim = Color(0x85000000),
)

val ContakoTypography = Typography(
    headlineLarge = textStyle(32, 40), headlineMedium = textStyle(28, 36),
    headlineSmall = textStyle(24, 32), titleLarge = textStyle(22, 28, FontWeight.SemiBold),
    titleMedium = textStyle(16, 24, FontWeight.Medium), titleSmall = textStyle(14, 20, FontWeight.Medium),
    bodyLarge = textStyle(16, 24), bodyMedium = textStyle(14, 20), bodySmall = textStyle(12, 16),
    labelLarge = textStyle(14, 20, FontWeight.Medium), labelMedium = textStyle(12, 16, FontWeight.Medium),
    labelSmall = textStyle(11, 16, FontWeight.Medium),
)

private fun textStyle(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.SansSerif, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp,
)

object ContakoSpacing {
    val None = 0.dp; val Hairline = 1.dp; val Focus = 2.dp; val Tight = 4.dp
    val Compact = 6.dp; val Standard = 8.dp; val Dense = 12.dp; val Gutter = 16.dp
    val Medium = 20.dp; val Section = 24.dp; val Major = 32.dp; val Illustration = 40.dp
    val Large = 48.dp
}

object ContakoElevation {
    val None = 0.dp; val Soft = 2.dp; val Raised = 4.dp; val Sheet = 8.dp
}

object ContakoMotion {
    const val Instant = 0; const val Fast = 100; const val Standard = 200
    const val Navigation = 300; const val Emphasized = 400
    fun duration(reducedMotion: Boolean, durationMillis: Int) = if (reducedMotion) Instant else durationMillis
}

val ContakoShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
)

val LocalContakoColors = staticCompositionLocalOf { LightSemanticColors }
val LocalReducedMotion = staticCompositionLocalOf { false }

fun resolveDarkTheme(themeMode: ThemeMode, systemDarkTheme: Boolean): Boolean =
    themeMode == ThemeMode.DARK || themeMode == ThemeMode.SYSTEM && systemDarkTheme

@Composable
fun ContakoTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    systemDarkTheme: Boolean = isSystemInDarkTheme(),
    reducedMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val darkTheme = resolveDarkTheme(themeMode, systemDarkTheme)
    androidx.compose.runtime.CompositionLocalProvider(
        LocalContakoColors provides if (darkTheme) DarkSemanticColors else LightSemanticColors,
        LocalReducedMotion provides reducedMotion,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = ContakoTypography,
            shapes = ContakoShapes,
            content = content,
        )
    }
}
