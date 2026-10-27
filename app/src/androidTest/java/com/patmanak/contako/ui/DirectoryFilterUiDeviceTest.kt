package com.patmanak.contako.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import com.patmanak.contako.R
import com.patmanak.contako.domain.policy.DirectoryFilter
import com.patmanak.contako.qa.saveSyntheticUiEvidence
import com.patmanak.contako.ui.theme.ContakoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Presentation only: no repository, Android account, provider or Proton access. */
class DirectoryFilterUiDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun draftIsSeparateUntilApplyAndCancelDoesNotApply() {
        var draft by mutableStateOf(DirectoryFilter())
        var applied = DirectoryFilter()
        var visible by mutableStateOf(true)
        compose.setContent {
            ContakoTheme {
                if (visible) DirectoryFilterSheet(draft,
                    onDraftChange = { draft = it },
                    onApply = { applied = draft; visible = false },
                    onDismiss = { visible = false })
            }
        }
        compose.onNodeWithText(text(R.string.directory_without_phone)).performClick()
        compose.runOnIdle {
            assertTrue(draft.withoutPhone)
            assertEquals(DirectoryFilter(), applied)
        }
        compose.onNodeWithText(text(R.string.directory_recent_changes)).performClick()
        compose.onNodeWithTag("directory_recent_days").performScrollTo().performTextReplacement("30")
        compose.onNodeWithText(text(R.string.action_cancel)).performClick()
        compose.onNodeWithTag("directory_filter_sheet").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(DirectoryFilter(), applied)
            draft = applied
            visible = true
        }
        compose.onNodeWithText(text(R.string.directory_recent_changes)).performClick()
        compose.onNodeWithTag("directory_recent_days").assertTextContains("7")
        compose.onNodeWithText(text(R.string.directory_recent_changes)).performClick()
        compose.onNodeWithText(text(R.string.directory_without_email)).performClick()
        compose.onNodeWithText(text(R.string.directory_apply_filters)).performClick()
        compose.runOnIdle { assertEquals(DirectoryFilter(withoutEmail = true), applied) }
    }

    @Test fun recentDayCountIsConfigurableAndRejectsInvalidValuesBeforeApply() {
        var draft by mutableStateOf(DirectoryFilter())
        var applied = DirectoryFilter()
        compose.setContent {
            ContakoTheme {
                DirectoryFilterSheet(draft, onDraftChange = { draft = it },
                    onApply = { applied = draft }, onDismiss = {})
            }
        }
        assertEquals(4, compose.onAllNodesWithTag("directory_filter_option").fetchSemanticsNodes().size)
        compose.onNodeWithTag("directory_recent_days").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.directory_recent_changes)).performClick()
        listOf("", "0", "2147483648").forEach { invalid ->
            compose.onNodeWithTag("directory_recent_days").performScrollTo().performTextReplacement(invalid)
            compose.onNodeWithText(text(R.string.directory_apply_filters)).assertIsNotEnabled()
            compose.runOnIdle { assertEquals(DirectoryFilter(), applied) }
        }
        compose.onNodeWithTag("directory_recent_days").performTextReplacement("30")
        compose.onNodeWithText(text(R.string.directory_apply_filters)).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(DirectoryFilter(recentChanges = true, recentDays = 30), applied) }
    }

    @Test fun draggingToTheTopAndEditingDaysKeepsTheSheetAndActionsReachable() {
        var draft by mutableStateOf(DirectoryFilter(recentChanges = true, recentDays = 30))
        var applied = DirectoryFilter()
        compose.setContent { ContakoTheme {
            DirectoryFilterSheet(draft, onDraftChange = { draft = it }, onApply = { applied = draft }, onDismiss = {})
        } }
        compose.waitForIdle()
        val before = compose.onNodeWithTag("directory_filter_sheet").fetchSemanticsNode().boundsInRoot
        repeat(3) {
            compose.onNodeWithTag("directory_filter_handle", useUnmergedTree = true).performTouchInput {
                swipe(start = center, end = Offset(center.x, center.y - 300f), durationMillis = 400)
            }
        }
        compose.waitForIdle()
        val after = compose.onNodeWithTag("directory_filter_sheet").fetchSemanticsNode().boundsInRoot
        assertEquals(before.top, after.top, 2f)
        assertEquals(before.bottom, after.bottom, 2f)
        compose.onNodeWithText(text(R.string.directory_filter)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.directory_apply_filters)).assertIsDisplayed()
        saveSyntheticUiEvidence("filters-expanded")
        compose.onNodeWithTag("directory_recent_days").performScrollTo().performClick().performTextReplacement("14")
        compose.onNodeWithText(text(R.string.directory_apply_filters)).assertIsDisplayed()
        saveSyntheticUiEvidence("filters-keyboard")
        compose.onNodeWithTag("directory_recent_days").performImeAction()
        compose.onNodeWithText(text(R.string.directory_apply_filters)).performClick()
        compose.runOnIdle { assertEquals(14, applied.recentDays) }
    }

    @Test fun removingOneChipAndClearingSearchKeepOtherCriteriaSeparate() {
        var filter by mutableStateOf(DirectoryFilter(withoutPhone = true, withoutEmail = true))
        var clearFilters = 0
        var clearSearch = 0
        compose.setContent {
            ContakoTheme {
                Column {
                    DirectoryFilterSummary(filter, resultCount = 0, onFilterChange = { filter = it })
                    FilteredDirectoryEmpty(true, true, onClearFilters = { clearFilters++ },
                        onClearQuery = { clearSearch++ })
                }
            }
        }
        compose.onNodeWithTag("directory_result_count").assertIsDisplayed()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.directory_remove_filter,
            text(R.string.directory_without_phone))).performClick()
        compose.runOnIdle { assertEquals(DirectoryFilter(withoutEmail = true), filter) }
        compose.onNodeWithText(text(R.string.directory_search_clear)).performClick()
        compose.runOnIdle { assertEquals(1, clearSearch); assertEquals(0, clearFilters) }
        compose.onNodeWithText(text(R.string.directory_clear_filters)).performClick()
        compose.runOnIdle { assertEquals(1, clearFilters); assertEquals(1, clearSearch) }
    }

    @Test fun localReadFailureOffersRetryWithoutShowingAnEmptyResult() {
        var retries = 0
        compose.setContent { ContakoTheme { DirectoryReadStatus(failed = true, onRetry = { retries++ }) } }
        compose.onNodeWithText(text(R.string.directory_read_failed)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.directory_filtered_empty)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.directory_retry_read)).performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun recentChipDisplaysTheChosenDurationAndCanBeRemoved() {
        var filter by mutableStateOf(DirectoryFilter(recentChanges = true, recentDays = 30))
        compose.setContent { ContakoTheme {
            DirectoryFilterSummary(filter, resultCount = 2, onFilterChange = { filter = it })
        } }
        val label = compose.activity.resources.getQuantityString(R.plurals.directory_recent_window, 30, 30)
        compose.onNodeWithText(label).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(DirectoryFilter(recentDays = 30), filter) }
        compose.onNodeWithTag("directory_active_filters").assertDoesNotExist()
    }

    private fun text(id: Int) = compose.activity.getString(id)
}
