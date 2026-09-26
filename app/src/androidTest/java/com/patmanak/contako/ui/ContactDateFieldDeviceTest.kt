package com.patmanak.contako.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.R
import com.patmanak.contako.ui.theme.ContakoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated component only; no repository, account, provider or network access. */
@RunWith(AndroidJUnit4::class)
class ContactDateFieldDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun fieldTapOpensCalendarAndCancelPreservesImportedSpelling() {
        var committed = "20200930"
        compose.setContent { ContakoTheme { ContactDateField(committed, "Birthday", null) { committed = it } } }
        compose.onNodeWithTag("contact_date_field").performTouchInput { click() }
        compose.onNodeWithText("30/09/2020").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.action_cancel)).performClick()
        compose.runOnIdle { assertEquals("20200930", committed) }
    }

    @Test fun manualInputRejectsImpossibleDateAndCommitsCanonicalDate() {
        var committed = "2020-09-30"
        compose.setContent { ContakoTheme { ContactDateField(committed, "Birthday", null) { committed = it } } }
        compose.onNodeWithTag("contact_date_field").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.date_enter_manually)).performScrollTo().performClick()
        compose.onNodeWithTag("contact_date_input").performTextReplacement("31/02/2024")
        compose.onNodeWithTag("contact_date_confirm").assertIsNotEnabled()
        compose.onNodeWithTag("contact_date_input").performTextReplacement("29/02/2024")
        compose.onNodeWithTag("contact_date_confirm").performClick()
        compose.runOnIdle { assertEquals("2024-02-29", committed) }
    }

    @Test fun clearedManualInputCannotRestoreAnEarlierCalendarSelection() {
        compose.setContent { ContakoTheme { ContactDateField("2020-09-30", "Birthday", null) {} } }
        compose.onNodeWithTag("contact_date_field").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.date_enter_manually)).performScrollTo().performClick()
        compose.onNodeWithTag("contact_date_input").performTextClearance()
        compose.onNodeWithText(compose.activity.getString(R.string.date_use_calendar)).performScrollTo().performClick()
        compose.onNodeWithTag("contact_date_confirm").assertIsNotEnabled()
    }

    @Test fun restoringYearRequiresExplicitSelectionInsteadOfPersistingAnchor() {
        compose.setContent { ContakoTheme { ContactDateField("--0229", "Birthday", null) {} } }
        compose.onNodeWithTag("contact_date_field").performClick()
        compose.onNodeWithTag("contact_date_confirm").assertIsEnabled()
        compose.onNodeWithText(compose.activity.getString(R.string.date_without_year)).performScrollTo().performClick()
        compose.onNodeWithTag("contact_date_confirm").assertIsNotEnabled()
    }
}
