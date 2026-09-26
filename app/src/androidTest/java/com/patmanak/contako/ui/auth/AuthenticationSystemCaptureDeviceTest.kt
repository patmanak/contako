package com.patmanak.contako.ui.auth

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AuthenticationSystemCaptureDeviceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun systemCaptureAndRecentsExcludeSecretBearingSurface() {
        var destination by mutableStateOf(AuthenticationDestination.READY)
        var probeColor by mutableStateOf(PERMITTED_COLOR)
        composeRule.setContent {
            AuthenticationWindowProtection(destination)
            Box(Modifier.fillMaxSize().background(probeColor))
        }

        composeRule.waitForIdle()
        composeRule.waitUntil(WINDOW_FOCUS_TIMEOUT_MILLIS) {
            composeRule.activity.hasWindowFocus()
        }
        val permittedCount = countMatchingPixels(
            checkNotNull(capture()) { "PERMITTED_SYSTEM_SCREENSHOT_UNAVAILABLE" },
            PERMITTED_COLOR.toArgb(),
        )
        assertTrue("PERMITTED_SURFACE_NOT_CAPTURABLE", permittedCount > MIN_VISIBLE_PIXELS)

        composeRule.runOnUiThread {
            destination = AuthenticationDestination.CODE
            probeColor = SECRET_COLOR
        }
        composeRule.waitForIdle()
        val protectedCount = capture()?.let { countMatchingPixels(it, SECRET_COLOR.toArgb()) } ?: 0
        assertTrue("SECRET_SURFACE_VISIBLE_IN_SYSTEM_CAPTURE", protectedCount <= MAX_LEAK_PIXELS)

        executeShellInput("input keyevent KEYCODE_APP_SWITCH")
        SystemClock.sleep(RECENTS_SETTLE_MILLIS)
        // Quickstep-based launchers can keep the app window focused while a recents animation
        // input consumer owns the visible overview. Require either framework signal, then retain
        // the stronger pixel assertion below; focus loss alone is not portable on /e/OS API 35.
        assertTrue(
            "APP_SWITCH_DID_NOT_OPEN_SYSTEM_UI",
            !composeRule.activity.hasWindowFocus() || windowDump().contains(RECENTS_INPUT_CONSUMER),
        )
        val recentsCount = capture()?.let { countMatchingPixels(it, SECRET_COLOR.toArgb()) } ?: 0
        assertTrue("SECRET_SURFACE_VISIBLE_IN_RECENTS", recentsCount <= MAX_LEAK_PIXELS)
        executeShellInput("input keyevent KEYCODE_BACK")
    }

    private fun capture(): Bitmap? {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("screencap -p")
        return try {
            FileInputStream(descriptor.fileDescriptor).use(BitmapFactory::decodeStream)
        } finally {
            descriptor.close()
        }
    }

    private fun countMatchingPixels(bitmap: Bitmap, expected: Int): Int {
        return try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            pixels.count { pixel ->
                kotlin.math.abs(AndroidColor.red(pixel) - AndroidColor.red(expected)) <= COLOR_TOLERANCE &&
                    kotlin.math.abs(AndroidColor.green(pixel) - AndroidColor.green(expected)) <= COLOR_TOLERANCE &&
                    kotlin.math.abs(AndroidColor.blue(pixel) - AndroidColor.blue(expected)) <= COLOR_TOLERANCE
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun executeShellInput(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
            .close()
    }

    private fun windowDump(): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("dumpsys window")
        return try {
            FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
        } finally {
            descriptor.close()
        }
    }

    private companion object {
        val PERMITTED_COLOR = Color(0xFF00D9FF)
        val SECRET_COLOR = Color(0xFFFF00D4)
        const val COLOR_TOLERANCE = 8
        const val MIN_VISIBLE_PIXELS = 10_000
        const val MAX_LEAK_PIXELS = 64
        const val RECENTS_SETTLE_MILLIS = 1_500L
        const val WINDOW_FOCUS_TIMEOUT_MILLIS = 5_000L
        const val RECENTS_INPUT_CONSUMER = "recents_animation_input_consumer"
    }
}
