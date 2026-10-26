package com.patmanak.contako.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.unit.Density
import com.patmanak.contako.R
import com.patmanak.contako.domain.policy.DirectoryFilter
import com.patmanak.contako.domain.policy.OrganizationFacet
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
                if (visible) DirectoryFilterSheet(draft, emptyList(),
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
        compose.onNodeWithText(text(R.string.action_cancel)).performClick()
        compose.onNodeWithTag("directory_filter_sheet").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(DirectoryFilter(), applied)
            draft = applied
            visible = true
        }
        compose.onNodeWithText(text(R.string.directory_without_email)).performClick()
        compose.onNodeWithText(text(R.string.directory_apply_filters)).performClick()
        compose.runOnIdle { assertEquals(DirectoryFilter(withoutEmail = true), applied) }
    }

    @Test fun fiveThousandFacetsRemainLazyAndApplyAccessibleAtDoubleFontScale() {
        var draft by mutableStateOf(DirectoryFilter())
        var applied = DirectoryFilter()
        val organizations = List(5_000) { OrganizationFacet("fixture-$it", "Fixture company $it") }
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                ContakoTheme {
                    DirectoryFilterSheet(draft, organizations, onDraftChange = { draft = it },
                        onApply = { applied = draft }, onDismiss = {})
                }
            }
        }
        compose.onNodeWithText(text(R.string.directory_apply_filters)).assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTag("directory_filter_option").fetchSemanticsNodes().size < 50)
        compose.onNodeWithTag("directory_filter_options").performScrollToKey("organization:fixture-4999")
        compose.onNodeWithText("Fixture company 4999").assertIsDisplayed().performClick()
        compose.onNodeWithText(text(R.string.directory_apply_filters)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(setOf("fixture-4999"), applied.organizations) }
    }

    @Test fun removingOneChipAndClearingSearchKeepOtherCriteriaSeparate() {
        var filter by mutableStateOf(DirectoryFilter(withoutPhone = true, withoutEmail = true))
        var clearFilters = 0
        var clearSearch = 0
        compose.setContent {
            ContakoTheme {
                Column {
                    DirectoryFilterSummary(filter, emptyList(), resultCount = 0, onFilterChange = { filter = it })
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

    private fun text(id: Int) = compose.activity.getString(id)
}
