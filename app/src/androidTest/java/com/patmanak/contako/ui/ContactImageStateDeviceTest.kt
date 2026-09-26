package com.patmanak.contako.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.ui.components.rememberContactImage
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContactImageStateDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun replacingAnImageClearsTheOldBitmapBeforeTheNewDecodeCompletes() {
        val first = ImageBitmap(1, 1)
        val second = ImageBitmap(1, 1)
        val replacement = CompletableDeferred<ImageBitmap?>()
        var sources by mutableStateOf(listOf("first"))
        compose.setContent {
            val image = rememberContactImage(sources) { source ->
                if (source == "first") first else replacement.await()
            }
            Text(when (image) { first -> "first"; second -> "second"; else -> "empty" })
        }
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("first")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle { sources = listOf("second") }
        compose.onNodeWithText("empty").assertIsDisplayed()
        replacement.complete(second)
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("second")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle { sources = emptyList() }
        compose.onNodeWithText("empty").assertIsDisplayed()
    }

    @Test
    fun switchingSourcesWhileDecodingCannotPublishTheAbandonedImage() {
        val abandoned = CompletableDeferred<ImageBitmap?>()
        val current = ImageBitmap(1, 1)
        var sources by mutableStateOf(listOf("abandoned"))
        compose.setContent {
            val image = rememberContactImage(sources) { source ->
                if (source == "abandoned") abandoned.await() else current
            }
            Text(if (image === current) "current" else if (image == null) "empty" else "stale")
        }
        compose.onNodeWithText("empty").assertIsDisplayed()
        compose.runOnIdle { sources = listOf("current") }
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("current")).fetchSemanticsNodes().isNotEmpty()
        }
        abandoned.complete(ImageBitmap(1, 1))
        compose.onNodeWithText("current").assertIsDisplayed()
    }
}
