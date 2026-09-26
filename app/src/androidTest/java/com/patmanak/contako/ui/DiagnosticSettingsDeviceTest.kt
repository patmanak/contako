package com.patmanak.contako.ui

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.R
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.data.local.RoomContactRepository
import com.patmanak.contako.ui.theme.ContakoTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiagnosticSettingsDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var database: ContakoDatabase

    @Before
    fun setUp() {
        compose.activity.deleteDatabase(DATABASE_NAME)
        database = ContakoDatabase.create(compose.activity, DATABASE_NAME)
    }

    @After
    fun tearDown() {
        database.close()
        compose.activity.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun reportRequiresExplicitActionAndCanBeDeleted() {
        val viewModel = ContactsViewModel(RoomContactRepository(database))
        compose.setContent { ContakoTheme { ContakoApp(viewModel) } }

        compose.onNodeWithText(compose.activity.getString(R.string.nav_sync)).performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(compose.activity.getString(R.string.diagnostics_generate)))
        assertEquals(0, compose.onAllNodesWithText("contako_diagnostic_schema=1", substring = true).fetchSemanticsNodes().size)
        compose.onNodeWithText(compose.activity.getString(R.string.diagnostics_generate)).performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("contako_diagnostic_schema=1", substring = true))
        compose.onNodeWithText("contako_diagnostic_schema=1", substring = true).assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(compose.activity.getString(R.string.diagnostics_delete)))
        compose.onNodeWithText(compose.activity.getString(R.string.diagnostics_delete)).performClick()
        compose.waitForIdle()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(compose.activity.getString(R.string.diagnostics_generate)))
        compose.onNodeWithText(compose.activity.getString(R.string.diagnostics_generate)).assertIsDisplayed()
    }

    @Test
    fun exportUsesSystemDocumentPickerWithoutAppComponent() {
        val intent = ActivityResultContracts.CreateDocument("text/plain")
            .createIntent(compose.activity, "contako-diagnostic.txt")

        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertEquals("text/plain", intent.type)
        assertNull(intent.component)
    }

    private companion object {
        const val DATABASE_NAME = "diagnostic-settings-device.db"
    }
}
