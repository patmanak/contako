package com.patmanak.contako.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import com.patmanak.contako.R
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.sync.ContactConflictChoice
import com.patmanak.contako.domain.sync.ContactConflictDetail
import com.patmanak.contako.domain.sync.ContactConflictSummary
import com.patmanak.contako.ui.theme.ContakoTheme
import com.patmanak.contako.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Rendering and callback qualification only; no repository, account, provider or remote peer. */
class ContactConflictComparisonDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun noteDifferenceAndMatchingFieldToggleDoNotChooseOrChangeEitherSnapshot() {
        val original = detail()
        val choices = mutableListOf<ContactConflictChoice>()
        var closes = 0
        compose.setContent { ContakoTheme {
            ContactConflictContent(ConflictPanelState(original.summary.contactId, original),
                onClose = { closes++ }, onReload = {}, onChoose = { choices += it },
                onStatus = {}, onGroups = {}, onDeletion = {})
        } }
        scrollToText("Local synthetic note")
        compose.onNodeWithText("Local synthetic note").assertIsDisplayed()
        compose.onNodeWithText("Remote synthetic note").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.field_first_name)).assertDoesNotExist()
        scrollToTag("conflict-show-same")
        compose.onNodeWithTag("conflict-show-same").performClick()
        scrollToText(text(R.string.field_first_name))
        compose.onNodeWithText(text(R.string.field_first_name)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.action_close)).performClick()
        compose.runOnIdle {
            assertTrue(choices.isEmpty())
            assertEquals(1, closes)
            assertEquals("Local synthetic note", original.local.values.single().value)
            assertEquals("Remote synthetic note", original.proton.values.single().value)
        }
    }

    @Test fun eachWholeVersionRequiresItsOwnPreviewThenExplicitConfirmation() {
        var current by mutableStateOf(detail())
        val choices = mutableListOf<ContactConflictChoice>()
        compose.setContent { ContakoTheme {
            ContactConflictContent(ConflictPanelState(current.summary.contactId, current),
                onClose = {}, onReload = {}, onChoose = {
                    choices += it
                    current = current.copy(summary = current.summary.copy(choice = it))
                }, onStatus = {}, onGroups = {}, onDeletion = {})
        } }
        listOf(ContactConflictChoice.LOCAL, ContactConflictChoice.PROTON).forEachIndexed { index, choice ->
            if (index != 0) compose.runOnIdle { current = detail("next-generation") }
            val tag = if (choice == ContactConflictChoice.LOCAL) "conflict-review-local" else "conflict-review-proton"
            scrollToTag(tag)
            compose.onNodeWithTag(tag).performClick()
            compose.runOnIdle { assertEquals(index, choices.size) }
            scrollToText(text(R.string.conflict_choice_preview))
            compose.onNodeWithText(text(R.string.conflict_choice_preview)).assertIsDisplayed()
            scrollToTag("conflict-confirm")
            compose.onNodeWithTag("conflict-confirm").assertIsDisplayed().performClick()
            scrollToTag("conflict-choice-queued")
            compose.onNodeWithTag("conflict-choice-queued").assertIsDisplayed()
            compose.runOnIdle { assertEquals(choice, choices.last()); assertEquals(index + 1, choices.size) }
        }
    }

    @Test fun queuedChoiceHasNoSecondChoiceAndGroupBlockOffersSeparateReview() {
        var current by mutableStateOf(detail().let { it.copy(summary = it.summary.copy(choice = ContactConflictChoice.LOCAL)) })
        var groups = 0
        val choices = mutableListOf<ContactConflictChoice>()
        compose.setContent { ContakoTheme {
            ContactConflictContent(ConflictPanelState(current.summary.contactId, current),
                onClose = {}, onReload = {}, onChoose = { choices += it },
                onStatus = {}, onGroups = { groups++ }, onDeletion = {})
        } }
        scrollToTag("conflict-choice-queued")
        compose.onNodeWithTag("conflict-choice-queued").assertIsDisplayed()
        compose.onNodeWithTag("conflict-review-local").assertDoesNotExist()
        compose.onNodeWithTag("conflict-review-proton").assertDoesNotExist()
        compose.runOnIdle { current = detail("blocked-group").copy(protonChoiceAvailable = false) }
        scrollToText(text(R.string.conflict_review_groups))
        compose.onNodeWithText(text(R.string.conflict_review_groups)).performClick()
        scrollToTag("conflict-review-proton")
        compose.onNodeWithTag("conflict-review-proton").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, groups); assertTrue(choices.isEmpty()) }
    }

    @Test fun newerGenerationDropsAnUnconfirmedPreviewInsteadOfReusingItsConsent() {
        var current by mutableStateOf(detail())
        val choices = mutableListOf<ContactConflictChoice>()
        compose.setContent { ContakoTheme {
            ContactConflictContent(ConflictPanelState(current.summary.contactId, current),
                onClose = {}, onReload = {}, onChoose = { choices += it },
                onStatus = {}, onGroups = {}, onDeletion = {})
        } }
        scrollToTag("conflict-review-local")
        compose.onNodeWithTag("conflict-review-local").performClick()
        scrollToTag("conflict-confirm")
        compose.onNodeWithTag("conflict-confirm").assertIsDisplayed()
        compose.runOnIdle { current = detail("replaced-generation", remoteNote = "A newer remote note") }
        scrollToTag("conflict-review-local")
        compose.onNodeWithTag("conflict-review-local").assertIsDisplayed()
        compose.onNodeWithTag("conflict-confirm").assertDoesNotExist()
        scrollToText("A newer remote note")
        compose.onNodeWithText("A newer remote note").assertIsDisplayed()
        compose.runOnIdle { assertTrue(choices.isEmpty()) }
    }

    @Test fun longFieldsRemainScrollableToConfirmationAtDoubleFontScaleInDarkTheme() {
        val original = detail(localNote = "Synthetic long note — Straße 東京. ".repeat(120),
            remoteNote = "Different synthetic long note — Élodie. ".repeat(100))
        val choices = mutableListOf<ContactConflictChoice>()
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                ContakoTheme(themeMode = ThemeMode.DARK) {
                    ContactConflictContent(ConflictPanelState(original.summary.contactId, original),
                        onClose = {}, onReload = {}, onChoose = { choices += it },
                        onStatus = {}, onGroups = {}, onDeletion = {})
                }
            }
        }
        scrollToTag("conflict-review-proton")
        compose.onNodeWithTag("conflict-review-proton").assertIsDisplayed().performClick()
        scrollToTag("conflict-confirm")
        compose.onNodeWithTag("conflict-confirm").assertIsDisplayed().performClick()
        compose.onNodeWithText(text(R.string.action_close)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(ContactConflictChoice.PROTON), choices) }
    }

    @Test fun metadataOnlyPreferenceDifferenceAndStructuredAddressUseReadableLocalizedLabels() {
        val address = ContactValue("synthetic-address", ContactValueKind.POSTAL_ADDRESS, "12 Synthetic Lane", order = 0,
            components = mapOf("postal_code" to "12345"),
            metadata = mapOf("vcardPref" to "1", "vcardTypeTokens" to "HOME\u001fWORK"))
        val local = CanonicalContact("synthetic-owner", "synthetic-contact", displayName = "Synthetic address",
            values = listOf(address))
        val proton = local.copy(values = listOf(address.copy(metadata = address.metadata + ("vcardPref" to "2"))))
        val original = ContactConflictDetail(ContactConflictSummary(local.id, "metadata-generation", 7,
            "synthetic-version", null), local, proton, localPreview = local, protonPreview = proton)
        val choices = mutableListOf<ContactConflictChoice>()
        compose.setContent { ContakoTheme {
            ContactConflictContent(ConflictPanelState(original.summary.contactId, original),
                onClose = {}, onReload = {}, onChoose = { choices += it },
                onStatus = {}, onGroups = {}, onDeletion = {})
        } }
        val localPreference = compose.activity.getString(R.string.conflict_preference, "1")
        val remotePreference = compose.activity.getString(R.string.conflict_preference, "2")
        scrollToText(localPreference)
        compose.onNodeWithText(localPreference).assertIsDisplayed()
        scrollToText(remotePreference)
        compose.onNodeWithText(remotePreference).assertIsDisplayed()
        val postal = text(R.string.field_address_postal_code) + ": 12345"
        assertEquals(2, compose.onAllNodesWithText(postal).fetchSemanticsNodes().size)
        assertEquals(2, compose.onAllNodesWithText(compose.activity.getString(R.string.conflict_types, "HOME, WORK"))
            .fetchSemanticsNodes().size)
        compose.onNodeWithText("postal_code:", substring = true).assertDoesNotExist()
        compose.onNodeWithText("vcardPref", substring = true).assertDoesNotExist()
        compose.onNodeWithText("vcardTypeTokens", substring = true).assertDoesNotExist()
        compose.runOnIdle { assertTrue(choices.isEmpty()) }
    }

    private fun scrollToTag(tag: String) = compose.onNodeWithTag("conflict-comparison-list").performScrollToNode(hasTestTag(tag))
    private fun scrollToText(value: String) = compose.onNodeWithTag("conflict-comparison-list").performScrollToNode(hasText(value))
    private fun text(id: Int) = compose.activity.getString(id)

    private fun detail(generation: String = "synthetic-generation", localNote: String = "Local synthetic note",
        remoteNote: String = "Remote synthetic note"): ContactConflictDetail {
        val local = CanonicalContact("synthetic-owner", "synthetic-contact", firstName = "Élodie", lastName = "Straße",
            displayName = "Élodie Straße", values = listOf(ContactValue("local-note", ContactValueKind.NOTE, localNote, order = 0)))
        val proton = local.copy(values = listOf(ContactValue("remote-note", ContactValueKind.NOTE, remoteNote, order = 0)))
        return ContactConflictDetail(ContactConflictSummary(local.id, generation, 7, "synthetic-version", null),
            local, proton, localPreview = local, protonPreview = proton)
    }
}
