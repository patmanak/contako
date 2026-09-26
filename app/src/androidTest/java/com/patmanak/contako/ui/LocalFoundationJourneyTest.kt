package com.patmanak.contako.ui

import android.content.pm.ActivityInfo
import android.content.Context
import android.content.res.Configuration
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.contako.R
import com.patmanak.contako.android.account.SignOutChoice
import com.patmanak.contako.android.account.SignOutResult
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactGroup
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.policy.CanonicalPrimaryValuePolicy
import com.patmanak.contako.domain.model.GroupMembership
import com.patmanak.contako.data.local.RoomContactRepository
import com.patmanak.contako.ui.theme.ContakoTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class LocalFoundationJourneyTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var context: Context
    private lateinit var database: ContakoDatabase
    private var showUi by mutableStateOf(true)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
        database = ContakoDatabase.create(context, DATABASE_NAME)
        showUi = true
    }

    @After
    fun tearDown() {
        showUi = false
        compose.waitForIdle()
        database.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun providerOnlyPendingSignOutOffersSyncAndSeparatelyConfirmedDiscard() {
        val viewModel = ContactsViewModel(RoomContactRepository(database))
        val choices = mutableListOf<SignOutChoice>()
        compose.setContent {
            if (showUi) ContakoTheme {
                ContakoApp(viewModel, onSignOut = { choice ->
                    choices += choice
                    if (choice == SignOutChoice.DISCARD) SignOutResult.SignedOut else SignOutResult.PendingChanges
                })
            }
        }
        compose.runOnIdle { assertEquals(0, viewModel.uiState.value.pendingMutationCount) }
        compose.onNodeWithContentDescription(string(R.string.account_menu)).performClick()
        compose.onNodeWithText(string(R.string.account_sign_out)).performClick()
        compose.onNodeWithText(string(R.string.account_sign_out)).performClick()
        compose.onNodeWithText(string(R.string.dialog_signout_pending_unverified)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.dialog_sync_before_signout)).performClick()
        compose.runOnIdle { assertEquals(listOf(SignOutChoice.CONFIRM, SignOutChoice.SYNC_NOW), choices) }
        compose.onNodeWithText(string(R.string.dialog_discard_signout)).performClick()
        compose.onNodeWithText(string(R.string.dialog_discard_signout_title)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, choices.size) }
        compose.onNodeWithText(string(R.string.dialog_discard_signout_confirm)).performClick()
        compose.runOnIdle { assertEquals(SignOutChoice.DISCARD, choices.last()) }
    }

    @Test
    fun newSignOutAttemptRechecksProviderIntentAfterCancelAndConvergence() {
        val viewModel = ContactsViewModel(RoomContactRepository(database))
        val choices = mutableListOf<SignOutChoice>()
        var providerPending = true
        compose.setContent {
            if (showUi) ContakoTheme {
                ContakoApp(viewModel, onSignOut = { choice ->
                    choices += choice
                    assertEquals(SignOutChoice.CONFIRM, choice)
                    if (providerPending) SignOutResult.PendingChanges else SignOutResult.SignedOut
                })
            }
        }
        fun openFreshAttempt() {
            compose.onNodeWithContentDescription(string(R.string.account_menu)).performClick()
            compose.onNodeWithText(string(R.string.account_sign_out)).performClick()
            compose.onNodeWithText(string(R.string.dialog_signout_body)).assertIsDisplayed()
            compose.onNodeWithText(string(R.string.dialog_sync_before_signout)).assertDoesNotExist()
            compose.onNodeWithText(string(R.string.dialog_discard_signout)).assertDoesNotExist()
            compose.onNodeWithText(string(R.string.account_sign_out)).performClick()
        }
        // Resetting a stale UI warning must not bypass the live provider guard.
        repeat(2) {
            openFreshAttempt()
            compose.onNodeWithText(string(R.string.dialog_signout_pending_unverified)).assertIsDisplayed()
            compose.onNodeWithText(string(R.string.action_cancel)).performClick()
        }
        // Model a completed sync outside the dialog, keeping the Room outbox empty.
        compose.runOnIdle {
            providerPending = false
            assertEquals(0, viewModel.uiState.value.pendingMutationCount)
        }
        openFreshAttempt()
        compose.onNodeWithText(string(R.string.dialog_signout_title)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(List(3) { SignOutChoice.CONFIRM }, choices) }
    }

    @Test
    fun emptyCreateSearchRecreateGroupDeleteAndDraftCorrectionStayLocal() {
        var activeViewModel by mutableStateOf(
            ContactsViewModel(RoomContactRepository(database, idFactory = { "journey-contact" })),
        )
        compose.setContent {
            if (showUi) {
                ContakoTheme { ContakoApp(activeViewModel) }
            }
        }

        compose.onNodeWithText(string(R.string.contacts_empty_title)).assertIsDisplayed()

        compose.onNodeWithContentDescription(string(R.string.contacts_new)).performClick()
        compose.onNodeWithText(string(R.string.field_first_name)).performTextInput("Alpha Journey")
        compose.runOnIdle { activeViewModel.addContactValue(ContactValueKind.EMAIL) }
        compose.onNode(hasScrollAction()).performScrollToIndex(1)
        compose.onNode(hasSetTextAction() and androidx.compose.ui.test.hasText(string(R.string.field_email)))
            .performTextInput("synthetic.journey@example.test")
        compose.onNodeWithTag(CONTACT_EDITOR_TOP_SAVE_TAG).performClick()
        compose.waitUntilNodeExists("Alpha Journey")

        compose.onNodeWithText(string(R.string.directory_search)).performTextInput("journey")
        compose.onNodeWithText("Alpha Journey").assertIsDisplayed()
        compose.onNodeWithContentDescription(string(R.string.directory_search_clear)).performClick()
        compose.onNodeWithContentDescription(string(R.string.directory_search_clear)).assertDoesNotExist()

        // Recreate the application-facing repository and ViewModel over the same durable store.
        activeViewModel = ContactsViewModel(RoomContactRepository(database))
        compose.waitUntilNodeExists("Alpha Journey")

        compose.onNodeWithText(string(R.string.nav_groups)).performClick()
        compose.onNodeWithContentDescription(string(R.string.groups_new)).performClick()
        compose.onNodeWithText(string(R.string.groups_name)).performTextInput("Synthetic Journey Group")
        compose.onNodeWithContentDescription(string(R.string.groups_color_choice, 2, 2)).performClick()
        compose.runOnIdle {
            assert(activeViewModel.uiState.value.groupEditor?.color == "#5252CC")
        }
        compose.onNode(
            androidx.compose.ui.test.hasClickAction() and
                androidx.compose.ui.test.hasAnyDescendant(
                    androidx.compose.ui.test.hasText("synthetic.journey@example.test"),
                ),
            useUnmergedTree = true,
        ).performClick()
        pauseForVisualInspectionIfRequested()
        compose.onNodeWithTag(GROUP_EDITOR_TOP_SAVE_TAG).performClick()
        compose.waitUntil(timeoutMillis = 5_000) { activeViewModel.uiState.value.groupEditor == null }
        compose.waitUntilNodeExists("Synthetic Journey Group")
        compose.onNodeWithText(plural(R.plurals.groups_member_count, 1, 1)).assertIsDisplayed()
        pauseForVisualInspectionIfRequested()

        compose.onNodeWithText(string(R.string.nav_contacts)).performClick()
        compose.onNodeWithText(string(R.string.directory_search)).performTextInput("synthetic journey group")
        compose.onNodeWithText("Alpha Journey").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNodeWithText(string(R.string.nav_groups)).performClick()

        compose.onNodeWithText("Synthetic Journey Group").performClick()
        compose.onNodeWithContentDescription(string(R.string.groups_edit)).assertIsDisplayed()
        compose.onNodeWithContentDescription(string(R.string.groups_edit)).performClick()
        compose.onNodeWithText(string(R.string.nav_contacts)).assertDoesNotExist()
        compose.onNodeWithContentDescription(string(R.string.groups_color_choice, 2, 2))
            .assertIsSelected()
        compose.onNodeWithContentDescription(string(R.string.action_cancel)).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            activeViewModel.uiState.value.groupEditor == null ||
                activeViewModel.uiState.value.showUnsavedConfirmation
        }
        if (activeViewModel.uiState.value.showUnsavedConfirmation) {
            compose.onNodeWithText(string(R.string.dialog_discard_changes)).performClick()
        }
        compose.waitUntil(timeoutMillis = 5_000) { activeViewModel.uiState.value.groupEditor == null }
        compose.onNodeWithContentDescription(string(R.string.group_more_actions)).performClick()
        compose.onNodeWithText(string(R.string.action_delete_group)).performClick()
        compose.onNodeWithText(string(R.string.action_delete_group)).performClick()
        compose.waitUntilNodeDoesNotExist("Synthetic Journey Group")
        compose.onNodeWithText(string(R.string.nav_contacts)).performClick()
        compose.runOnIdle { assert(activeViewModel.uiState.value.contacts.any { it.resolvedDisplayName == "Alpha Journey" }) }

        compose.onNodeWithContentDescription(string(R.string.contacts_new)).performClick()
        compose.runOnIdle { activeViewModel.addContactValue(ContactValueKind.EMAIL) }
        compose.onNode(hasScrollAction()).performScrollToIndex(1)
        compose.onNode(hasSetTextAction() and androidx.compose.ui.test.hasText(string(R.string.field_email)))
            .performTextInput("draft@example.test")
        compose.onNodeWithTag(CONTACT_EDITOR_TOP_SAVE_TAG).performClick()
        compose.waitUntilNodeExists("Unnamed contact")
        compose.onNodeWithText(string(R.string.action_required_missing_name)).assertIsDisplayed()

        compose.onNodeWithText("Unnamed contact").performClick()
        compose.onNodeWithContentDescription(string(R.string.contact_edit_action)).performClick()
        compose.onNodeWithText(string(R.string.field_first_name)).performTextInput("Corrected Draft")
        compose.onNodeWithTag(CONTACT_EDITOR_TOP_SAVE_TAG).performClick()
        compose.waitUntilNodeExists("Corrected Draft")
        compose.waitUntilNodeDoesNotExist(string(R.string.action_required_missing_name))
        compose.runOnIdle { assert(activeViewModel.uiState.value.contacts.any { it.resolvedDisplayName == "Alpha Journey" }) }
    }

    @Test
    fun responsiveNavigationDetailBackAndAccountSurfacesRemainReachable() {
        val viewModel = ContactsViewModel(RoomContactRepository(database, idFactory = { "navigation-contact" }))
        compose.setContent {
            if (showUi) ContakoTheme {
                ContakoApp(viewModel, accountAddress = "ui-test@example.test")
            }
        }

        compose.onNodeWithContentDescription(string(R.string.contacts_new)).performClick()
        compose.onNodeWithText(string(R.string.field_first_name)).performTextInput("Navigation Fixture")
        compose.onNodeWithTag(CONTACT_EDITOR_TOP_SAVE_TAG).performClick()
        compose.waitUntilNodeExists("Navigation Fixture")
        compose.onNodeWithText("Navigation Fixture").performClick()
        compose.onNodeWithContentDescription(string(R.string.contact_edit_action)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.nav_groups)).assertDoesNotExist()
        compose.onNodeWithContentDescription(string(R.string.action_back)).assertIsDisplayed().performClick()
        compose.onNodeWithText(string(R.string.directory_search)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.nav_groups)).assertIsDisplayed()

        compose.onNodeWithContentDescription(string(R.string.account_menu)).performClick()
        compose.onNodeWithText("ui-test@example.test").assertIsDisplayed()
        compose.onNodeWithText(string(R.string.nav_account)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.nav_help)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.nav_settings)).performClick()
        compose.onNodeWithText(string(R.string.settings_appearance)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.settings_language)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.settings_android)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.action_back)).performClick()
        compose.onNodeWithText(string(R.string.directory_search)).assertIsDisplayed()
        compose.onNodeWithContentDescription(string(R.string.account_menu)).performClick()
        compose.onNodeWithText(string(R.string.nav_about)).performClick()
        compose.waitUntilNodeExists(string(R.string.nav_about))
        compose.onNodeWithText(string(R.string.nav_about)).assertIsDisplayed()
        compose.onNodeWithTag("information_content").performScrollToNode(
            androidx.compose.ui.test.hasText(string(R.string.about_licenses)))
        compose.onNodeWithText(string(R.string.about_licenses)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun compactDetailWithLongValuesRemainsScrollableInDarkThemeAtTwoHundredPercentFontScale() {
        val repository = RoomContactRepository(database)
        val longName = "Synthetic Alexandra-Maximilienne de la Qualification Accessible"
        val longOrganization = "Synthetic International Accessibility and Interoperability Laboratory"
        val longEmail = "synthetic.accessibility.qualification.with.long.values@example.test"
        runBlocking {
            check(
                repository.saveContact(
                    CanonicalContact(
                        accountId = LOCAL_ACCOUNT_ID,
                        id = "scaled-dark-contact",
                        displayName = longName,
                        values = listOf(
                            ContactValue(
                                id = "scaled-dark-organization",
                                kind = ContactValueKind.ORGANIZATION,
                                value = longOrganization,
                                order = 0,
                            ),
                            ContactValue(
                                id = "scaled-dark-email",
                                kind = ContactValueKind.EMAIL,
                                value = longEmail,
                                order = 0,
                                isPrimary = true,
                            ),
                            ContactValue(
                                id = "scaled-dark-phone",
                                kind = ContactValueKind.PHONE,
                                value = "+33123456789",
                                order = 0,
                                isPrimary = true,
                            ),
                        ),
                    ),
                ) is com.patmanak.contako.domain.repository.SaveResult.Saved,
            )
        }
        val viewModel = ContactsViewModel(repository)
        compose.setContent {
            if (showUi) {
                CompositionLocalProvider(
                    LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f),
                ) {
                    ContakoTheme(systemDarkTheme = true) { ContakoApp(viewModel) }
                }
            }
        }

        compose.waitUntilNodeExists(longName)
        compose.onNodeWithText(longName).performClick()
        compose.onNodeWithText(longName).assertIsDisplayed()
        compose.onNodeWithContentDescription(string(R.string.contact_edit_action)).assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToIndex(1)
        compose.onNodeWithText(string(R.string.contact_action_message))
            .assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToIndex(2)
        compose.onNodeWithText(longEmail).performScrollTo().assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToIndex(5)
        compose.onNodeWithText(string(R.string.contact_status_section)).assertIsDisplayed()
        compose.onNodeWithContentDescription(string(R.string.contact_more_actions)).performClick()
        compose.onNodeWithText(string(R.string.action_delete)).performClick()
        compose.onNodeWithText(string(R.string.dialog_delete_title, longName)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.action_cancel)).performClick()
        compose.onNodeWithContentDescription(string(R.string.action_back)).performClick()
        compose.onNodeWithText(string(R.string.directory_search)).assertIsDisplayed()
        compose.onNodeWithText(longName).assertIsDisplayed()
    }

    @Test
    fun detailUsesQuickActionChoiceAndColoredEmailGroupChipsWithDeleteInOverflow() {
        val repository = RoomContactRepository(database)
        val name = "Synthetic Multiple Action Contact"
        val primaryEmail = "primary.multiple@example.test"
        val alternateEmail = "alternate.multiple@example.test"
        runBlocking {
            check(
                repository.saveContact(
                    CanonicalContact(
                        accountId = LOCAL_ACCOUNT_ID,
                        id = "multiple-action-contact",
                        displayName = name,
                        values = listOf(
                            ContactValue("phone-a", ContactValueKind.PHONE, "+33100000001", "Home", 0),
                            ContactValue("phone-b", ContactValueKind.PHONE, "+33100000002", "Mobile", 1, true),
                            ContactValue("email-a", ContactValueKind.EMAIL, primaryEmail, "Work", 0, true),
                            ContactValue("email-b", ContactValueKind.EMAIL, alternateEmail, "Home", 1),
                            ContactValue("address", ContactValueKind.POSTAL_ADDRESS, "1 Synthetic Street", "Work", 0),
                        ),
                    ),
                ) is com.patmanak.contako.domain.repository.SaveResult.Saved,
            )
            repository.saveGroup(
                ContactGroup(
                    accountId = LOCAL_ACCOUNT_ID,
                    id = "purple-group",
                    name = "Synthetic Purple",
                    color = "#8080FF",
                    memberships = listOf(GroupMembership("multiple-action-contact", "email-a")),
                ),
            )
            repository.saveGroup(
                ContactGroup(
                    accountId = LOCAL_ACCOUNT_ID,
                    id = "green-group",
                    name = "Synthetic Green",
                    color = "#2C974B",
                    memberships = listOf(GroupMembership("multiple-action-contact", "email-a")),
                ),
            )
        }
        val viewModel = ContactsViewModel(repository)
        compose.setContent { if (showUi) ContakoTheme { ContakoApp(viewModel) } }

        compose.waitUntilNodeExists(name)
        compose.onNodeWithText(name).performClick()
        listOf(
            R.string.contact_action_call,
            R.string.contact_action_message,
            R.string.contact_action_email,
            R.string.contact_action_map,
        ).forEach { compose.onNodeWithText(string(it)).assertIsDisplayed() }
        pauseForVisualInspectionIfRequested()

        compose.onNodeWithText(string(R.string.contact_action_email)).performClick()
        compose.onAllNodesWithText(primaryEmail)[1].assertIsDisplayed()
        compose.onAllNodesWithText(alternateEmail)[1].assertIsDisplayed()
        compose.onNodeWithText(string(R.string.action_cancel)).performClick()

        compose.onNodeWithText("Synthetic Purple").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Synthetic Green").assertIsDisplayed()
        compose.onNodeWithContentDescription(string(R.string.contact_more_actions)).performClick()
        compose.onNodeWithText(string(R.string.action_delete)).performClick()
        compose.onNodeWithText(string(R.string.dialog_delete_title, name)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.action_delete)).performClick()
        compose.waitUntilNodeDoesNotExist(name)
        compose.onNodeWithText(string(R.string.directory_search)).assertIsDisplayed()
    }

    @Test
    fun landscapeDirectoryAndLongDetailRemainUsableAtTwoHundredPercentFontScale() {
        val repository = RoomContactRepository(database)
        val longName = "Synthetic Alexandra-Maximilienne Expanded Qualification"
        val otherName = "Synthetic Bertrand Expanded Qualification"
        val longEmail = "synthetic.expanded.accessibility.with.long.values@example.test"
        runBlocking {
            listOf(
                CanonicalContact(
                    accountId = LOCAL_ACCOUNT_ID,
                    id = "expanded-long-contact",
                    displayName = longName,
                    values = listOf(
                        ContactValue(
                            id = "expanded-long-email",
                            kind = ContactValueKind.EMAIL,
                            value = longEmail,
                            order = 0,
                            isPrimary = true,
                        ),
                    ),
                ),
                CanonicalContact(
                    accountId = LOCAL_ACCOUNT_ID,
                    id = "expanded-other-contact",
                    displayName = otherName,
                ),
            ).forEach { contact ->
                check(repository.saveContact(contact) is com.patmanak.contako.domain.repository.SaveResult.Saved)
            }
        }

        try {
            compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            }
            val viewModel = ContactsViewModel(repository)
            compose.setContent {
                if (showUi) {
                    CompositionLocalProvider(
                        LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f),
                    ) {
                        ContakoTheme(systemDarkTheme = visualDarkThemeOverride() ?: true) { ContakoApp(viewModel) }
                    }
                }
            }

            compose.waitUntilNodeExists(longName)
            compose.onNodeWithText(string(R.string.directory_search)).assertIsDisplayed()
            compose.onNodeWithText(string(R.string.nav_groups)).assertIsDisplayed()
            compose.onNodeWithText(longName).performClick()
            compose.onNodeWithContentDescription(string(R.string.contact_edit_action)).assertIsDisplayed()
            compose.onNodeWithContentDescription(string(R.string.action_back)).assertIsDisplayed()
            compose.onNode(hasScrollAction()).performScrollToIndex(2)
            compose.onNodeWithText(longEmail).assertIsDisplayed()
            pauseForVisualInspectionIfRequested()

            compose.onNodeWithContentDescription(string(R.string.action_back)).performClick()
            compose.onNodeWithText(string(R.string.directory_search)).assertIsDisplayed()
            compose.onNode(hasScrollAction()).performScrollToIndex(1)
            compose.onNodeWithText(otherName)
                .assertIsDisplayed()
                .assertHasClickAction()
                .performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil(timeoutMillis = 5_000) {
                viewModel.uiState.value.navigation.current.selectedId == "expanded-other-contact"
            }
            compose.onNodeWithText(string(R.string.directory_search)).assertDoesNotExist()
            compose.onNodeWithContentDescription(string(R.string.action_back)).assertIsDisplayed()
            compose.onNodeWithContentDescription(string(R.string.contact_edit_action)).assertIsDisplayed()
        } finally {
            compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
            }
            compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    @Test
    fun organizationIsDirectoryFallbackWhenEmailAndPhoneAreAbsent() {
        val repository = RoomContactRepository(database)
        val name = "Synthetic Organization-only Contact"
        val organization = "Synthetic Directory Organization"
        runBlocking {
            check(
                repository.saveContact(
                    CanonicalContact(
                        accountId = LOCAL_ACCOUNT_ID,
                        id = "organization-only-contact",
                        displayName = name,
                        values = listOf(
                            ContactValue(
                                id = "organization-only-value",
                                kind = ContactValueKind.ORGANIZATION,
                                value = organization,
                                order = 0,
                            ),
                        ),
                    ),
                ) is com.patmanak.contako.domain.repository.SaveResult.Saved,
            )
        }
        val viewModel = ContactsViewModel(repository)
        compose.setContent { if (showUi) ContakoTheme { ContakoApp(viewModel) } }

        compose.waitUntilNodeExists(name)
        compose.onNodeWithText(name).assertIsDisplayed()
        compose.onNodeWithText(organization).assertIsDisplayed()
        compose.onNodeWithText(name).performClick()
        compose.onNodeWithContentDescription(string(R.string.contact_edit_action)).assertIsDisplayed()
    }

    @Test
    fun gallerySystemBackClosesPreviewBeforeLeavingContactDetail() {
        val repository = RoomContactRepository(database)
        val name = "Synthetic Gallery Contact"
        val photoLabel = "Synthetic Gallery Photo"
        runBlocking {
            check(
                repository.saveContact(
                    CanonicalContact(
                        accountId = LOCAL_ACCOUNT_ID,
                        id = "gallery-back-contact",
                        displayName = name,
                        values = listOf(
                            ContactValue(
                                id = "gallery-back-photo",
                                kind = ContactValueKind.PHOTO,
                                value = "content://synthetic.gallery/photo",
                                label = photoLabel,
                                order = 0,
                                isPrimary = true,
                            ),
                        ),
                    ),
                ) is com.patmanak.contako.domain.repository.SaveResult.Saved,
            )
        }
        val viewModel = ContactsViewModel(repository)
        compose.setContent { if (showUi) ContakoTheme { ContakoApp(viewModel) } }

        compose.waitUntilNodeExists(name)
        compose.onNodeWithText(name).performClick()
        compose.onNodeWithText(string(R.string.contact_identity_gallery_section)).assertDoesNotExist()
        compose.onNodeWithContentDescription(string(R.string.field_photo)).performClick()
        compose.onNodeWithText(string(R.string.gallery_preview_unavailable)).assertIsDisplayed()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntilNodeDoesNotExist(string(R.string.gallery_preview_unavailable))
        compose.onNodeWithContentDescription(string(R.string.contact_edit_action)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.directory_search)).assertDoesNotExist()
    }

    @Test
    fun syncRecoveryAndPersistentPermissionBannerRemainActionable() {
        val recovery = DeviceSyncRecoveryDataSource()
        val permission = DevicePermissionBoundary()
        val viewModel = ContactsViewModel(
            RoomContactRepository(database),
            syncRecoveryDataSource = recovery,
            contactsPermissionBoundary = permission,
        )
        compose.setContent { if (showUi) ContakoTheme { ContakoApp(viewModel) } }

        compose.onNodeWithText(string(R.string.contacts_permission_banner))
            .assertIsDisplayed()
        compose.onNodeWithText(string(R.string.contacts_permission_action)).performClick()
        compose.runOnIdle { assert(permission.requested) }
        pauseForVisualInspectionIfRequested()
        compose.onNodeWithContentDescription(
            plural(R.plurals.sync_action_required_count, 1, 1),
            useUnmergedTree = true,
        ).fetchSemanticsNode()
        compose.onNodeWithText(string(R.string.nav_groups)).performClick()
        compose.onNodeWithText(string(R.string.contacts_permission_banner))
            .assertIsDisplayed()
        compose.onNodeWithText(string(R.string.nav_sync)).performClick()
        compose.onNodeWithText(string(R.string.sync_state_blocked)).assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToIndex(3)
        compose.onNodeWithText(string(R.string.repair_start)).performClick()
        compose.onNodeWithText(string(R.string.repair_confirm_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.repair_confirm_action)).performClick()
        compose.runOnIdle { recovery.repair.value = RepairProgress(RepairPhase.ANDROID_PROJECTION, 1, 2, false) }
        compose.onNodeWithText(string(R.string.repair_phase_android)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.repair_cancel)).performClick()
        compose.runOnIdle { assert(recovery.cancelled) }
    }

    @Test
    fun advancedFamiliesStayVisibleCategoriesStayHiddenAndPrivateKeyDraftIsRejectedOnDevice() {
        val viewModel = ContactsViewModel(RoomContactRepository(database, idFactory = { "advanced-contact" }))
        compose.setContent {
            if (showUi) ContakoTheme { ContakoApp(viewModel) }
        }

        compose.onNodeWithContentDescription(string(R.string.contacts_new)).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(CONTACT_EDITOR_TOP_SAVE_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(string(R.string.editor_add_field)).performClick()
        compose.onNodeWithTag("contact_editor_field_picker").performScrollToNode(
            androidx.compose.ui.test.hasText(string(R.string.field_public_key)))
        compose.onNodeWithText(string(R.string.field_public_key)).performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and androidx.compose.ui.test.hasText(string(R.string.field_public_key)))
            .performScrollTo().performTextInput("-----BEGIN PRIVATE KEY-----")
        compose.onNodeWithTag(CONTACT_EDITOR_TOP_SAVE_TAG).performClick()
        compose.onNodeWithText(string(R.string.error_public_key))
            .assertIsDisplayed()
        compose.onNode(hasSetTextAction() and androidx.compose.ui.test.hasText(string(R.string.field_public_key))).assertIsDisplayed()
    }

    @Test
    fun secondarySurfacesRemainStructuredInLightAndDarkAtTwoHundredPercentFontScale() {
        val repository = RoomContactRepository(database)
        runBlocking {
            check(
                repository.saveContact(
                    CanonicalContact(
                        accountId = LOCAL_ACCOUNT_ID,
                        id = "secondary-action-contact",
                        values = listOf(
                            ContactValue(
                                id = "secondary-action-email",
                                kind = ContactValueKind.EMAIL,
                                value = "secondary.action@example.test",
                                order = 0,
                            ),
                        ),
                    ),
                ) is com.patmanak.contako.domain.repository.SaveResult.Saved,
            )
        }
        val recovery = DeviceSyncRecoveryDataSource()
        val permission = DevicePermissionBoundary()
        val viewModel = ContactsViewModel(
            repository,
            syncRecoveryDataSource = recovery,
            contactsPermissionBoundary = permission,
        )
        var darkTheme by mutableStateOf(false)
        var selectedTheme by mutableStateOf(com.patmanak.contako.ui.theme.ThemeMode.SYSTEM)
        var selectedLanguage by mutableStateOf(com.patmanak.contako.ui.locale.AppLanguage.SYSTEM)
        compose.setContent {
            if (showUi) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    ContakoTheme(systemDarkTheme = darkTheme) {
                        ContakoApp(
                            viewModel = viewModel,
                            accountAddress = "ui-test@example.test",
                            themeMode = selectedTheme,
                            onThemeModeChange = { selectedTheme = it },
                            language = selectedLanguage,
                            onLanguageChange = { selectedLanguage = it },
                        )
                    }
                }
            }
        }

        listOf(false, true).forEach { useDarkTheme ->
            compose.runOnIdle {
                darkTheme = useDarkTheme
                selectedTheme = com.patmanak.contako.ui.theme.ThemeMode.SYSTEM
                selectedLanguage = com.patmanak.contako.ui.locale.AppLanguage.SYSTEM
            }
            compose.onNode(
                androidx.compose.ui.test.hasText(string(R.string.nav_sync)) and
                    androidx.compose.ui.test.hasClickAction(),
            ).performClick()
            compose.onNode(hasScrollAction()).performScrollToIndex(2)
            pauseForVisualInspectionIfRequested()
            compose.onNodeWithText(string(R.string.settings_android)).assertIsDisplayed()
            compose.onNode(hasScrollAction()).performScrollToIndex(3)
            compose.onNodeWithText(string(R.string.sync_now)).assertIsDisplayed()
            compose.onNodeWithText(string(R.string.repair_start)).assertIsDisplayed()
            pauseForVisualInspectionIfRequested()

            compose.onNode(hasScrollAction()).performScrollToIndex(1)
            compose.onNode(
                androidx.compose.ui.test.hasClickAction() and
                    androidx.compose.ui.test.hasAnyDescendant(
                        androidx.compose.ui.test.hasText(plural(R.plurals.sync_action_required_count, 1, 1)),
                    ),
                useUnmergedTree = true,
            ).performClick()
            compose.onNodeWithText("Unnamed contact")
                .assertIsDisplayed().assertHasClickAction().performClick()
            compose.onNodeWithText(string(R.string.contacts_edit)).assertIsDisplayed()
            compose.onNode(hasScrollAction()).performScrollToIndex(1)
            compose.onNodeWithText("secondary.action@example.test").assertIsDisplayed()
            compose.onNodeWithContentDescription(string(R.string.action_cancel)).performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                viewModel.uiState.value.contactEditor == null ||
                    viewModel.uiState.value.showUnsavedConfirmation
            }
            if (viewModel.uiState.value.showUnsavedConfirmation) {
                compose.onNodeWithText(string(R.string.dialog_discard_changes)).performClick()
            }
            compose.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.contactEditor == null }
            compose.onNodeWithText(string(R.string.actions_missing_name_body)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(string(R.string.action_back)).performClick()
            compose.onNodeWithContentDescription(string(R.string.account_menu)).performClick()
            compose.onNodeWithText("ui-test@example.test").assertIsDisplayed()
            compose.onNodeWithText(string(R.string.nav_account)).assertDoesNotExist()
            compose.onNodeWithText(string(R.string.nav_help)).assertDoesNotExist()
            compose.onNodeWithText(string(R.string.nav_settings)).performClick()
            compose.onNodeWithText(string(R.string.settings_appearance)).assertIsDisplayed()
            compose.onNodeWithText(string(R.string.settings_language)).assertIsDisplayed()
            compose.onNodeWithText(string(R.string.settings_android)).assertDoesNotExist()
            compose.onNodeWithText(string(R.string.settings_notifications)).assertDoesNotExist()
            compose.onNodeWithText(string(R.string.settings_account)).assertDoesNotExist()
            compose.onNodeWithContentDescription(
                "${string(R.string.settings_appearance)}, ${string(R.string.settings_theme_system)}",
            ).performClick()
            compose.onNodeWithText(string(R.string.settings_theme_system)).assertIsDisplayed()
            compose.onNodeWithText(string(R.string.settings_theme_dark)).performClick()
            compose.runOnIdle {
                assert(selectedTheme == com.patmanak.contako.ui.theme.ThemeMode.DARK)
            }
            compose.onNodeWithContentDescription(
                "${string(R.string.settings_appearance)}, ${string(R.string.settings_theme_dark)}",
            ).assertIsDisplayed()
            compose.onNodeWithContentDescription(
                "${string(R.string.settings_language)}, ${string(R.string.settings_language_system)}",
            ).performClick()
            compose.onNodeWithText(string(R.string.language_portuguese)).performClick()
            compose.runOnIdle {
                assert(selectedLanguage == com.patmanak.contako.ui.locale.AppLanguage.PORTUGUESE)
            }
            compose.onNodeWithContentDescription(
                "${string(R.string.settings_language)}, ${string(R.string.language_portuguese)}",
            ).assertIsDisplayed()
            compose.onNodeWithText(string(R.string.action_back)).performClick()
            compose.onNodeWithContentDescription(string(R.string.account_menu)).performClick()
            compose.onNodeWithText(string(R.string.account_sign_out)).performClick()
            compose.onNodeWithText(string(R.string.dialog_signout_title)).assertIsDisplayed()
            compose.onNodeWithText(string(R.string.action_cancel)).performClick()
            compose.onNodeWithContentDescription(string(R.string.account_menu)).performClick()
            compose.onNodeWithText(string(R.string.nav_about)).performClick()
            compose.onNodeWithTag("information_content").performScrollToNode(
                androidx.compose.ui.test.hasText(string(R.string.about_unofficial)))
            compose.onNodeWithText(string(R.string.about_unofficial)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithContentDescription(string(R.string.account_menu)).performClick()
            compose.onNodeWithText(string(R.string.nav_usage)).performScrollTo().performClick()
            compose.onNodeWithTag("information_content").performScrollToNode(
                androidx.compose.ui.test.hasText(string(R.string.about_limits_summary)))
            compose.onNodeWithText(string(R.string.about_limits_summary)).performScrollTo().assertIsDisplayed()
            pauseForVisualInspectionIfRequested()
            compose.onNodeWithText(string(R.string.action_back)).performClick()
        }
    }

    @Test
    fun contactEditorKeepsMediaAndPerEmailGroupsVisibleInTheProgressiveFlow() {
        val repository = RoomContactRepository(database)
        runBlocking {
            repository.saveContact(
                CanonicalContact(
                    accountId = LOCAL_ACCOUNT_ID,
                    id = "d069-contact",
                    displayName = "D069 Contact",
                    values = listOf(
                        ContactValue("nickname", ContactValueKind.NICKNAME, "D069", order = 0),
                        ContactValue("email-a", ContactValueKind.EMAIL, "d069.a@example.test", order = 0),
                        ContactValue("email-b", ContactValueKind.EMAIL, "d069.b@example.test", order = 1),
                    ),
                ),
            )
            repository.saveGroup(
                ContactGroup(
                    accountId = LOCAL_ACCOUNT_ID,
                    id = "friends",
                    name = "D069 Friends",
                    memberships = listOf(GroupMembership("d069-contact", "email-a")),
                ),
            )
            repository.saveGroup(
                ContactGroup(accountId = LOCAL_ACCOUNT_ID, id = "work", name = "D069 Work"),
            )
        }
        val viewModel = ContactsViewModel(repository)
        compose.setContent { if (showUi) ContakoTheme { ContakoApp(viewModel) } }

        compose.waitUntilNodeExists("D069 Contact")
        compose.onNodeWithText("D069 Contact").performClick()
        compose.onNodeWithContentDescription(string(R.string.contact_edit_action)).performClick()
        pauseForVisualInspectionIfRequested()

        compose.onNodeWithText(string(R.string.field_add, string(R.string.field_nickname)))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(string(R.string.field_add_photo)).performScrollTo().assertHasClickAction()
        compose.onNode(hasScrollAction()).performScrollToIndex(2)
        compose.onNodeWithTag("contact_value_options_email-b").performScrollTo().performClick()
        compose.onNodeWithContentDescription(
            string(
                R.string.field_make_preferred,
                string(R.string.field_value_occurrence, string(R.string.field_email), 2, 2),
            ),
        ).performScrollTo().assertHasClickAction()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.runOnIdle {
            val current = requireNotNull(viewModel.uiState.value.contactEditor)
            val emails = current.values.filter { it.kind == ContactValueKind.EMAIL }
            assertEquals(
                emails.joinToString { "${it.id}:${it.metadata[CanonicalPrimaryValuePolicy.VCARD_PREF_METADATA]}" },
                "email-b",
                CanonicalPrimaryValuePolicy.select(emails)?.id,
            )
        }
        compose.onNodeWithText(string(R.string.editor_email_groups)).performScrollTo().performClick()
        compose.onAllNodesWithText("D069 Work")[1].performScrollTo().performClick()
        compose.onNode(hasScrollAction()).performScrollToIndex(10)
        compose.onNodeWithTag(CONTACT_EDITOR_BOTTOM_SAVE_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(CONTACT_EDITOR_BOTTOM_SAVE_TAG).performClick()
        compose.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.contactEditor == null }
        compose.onNode(
            androidx.compose.ui.test.hasClickAction() and
                androidx.compose.ui.test.hasText(string(R.string.contact_action_email)),
        ).performClick()
        val preferredTop = compose.onAllNodesWithText("d069.b@example.test")
            .fetchSemanticsNodes().minOf { it.boundsInRoot.top }
        val otherTop = compose.onAllNodesWithText("d069.a@example.test")
            .fetchSemanticsNodes().minOf { it.boundsInRoot.top }
        assert(preferredTop < otherTop)
        compose.onNodeWithText(string(R.string.action_cancel)).performClick()

        val assignments = runBlocking {
            repository.observeGroups(LOCAL_ACCOUNT_ID).first().associate { group ->
                group.id to group.memberships
            }
        }
        assertEquals(
            listOf(GroupMembership("d069-contact", "email-a")),
            assignments.getValue("friends"),
        )
        assertEquals(
            listOf(GroupMembership("d069-contact", "email-b")),
            assignments.getValue("work"),
        )
        val savedContact = runBlocking { repository.getContact(LOCAL_ACCOUNT_ID, "d069-contact") }
        assertEquals(
            "email-b",
            CanonicalPrimaryValuePolicy.preferredEmail(requireNotNull(savedContact))?.id,
        )
    }

    @Test
    fun localOnlyDateAndAccessibleImageGalleryRenderInLightAndDarkThemes() {
        val viewModel = ContactsViewModel(RoomContactRepository(database, idFactory = { "gallery-contact" }))
        var darkTheme by mutableStateOf(false)
        compose.setContent {
            if (showUi) ContakoTheme(systemDarkTheme = darkTheme) { ContakoApp(viewModel) }
        }

        compose.onNodeWithContentDescription(string(R.string.contacts_new)).performClick()
        compose.onNode(hasScrollAction()).performScrollToIndex(4)
        compose.onNodeWithContentDescription(string(R.string.field_custom_date_local_only))
            .performScrollTo().assertIsDisplayed()

        compose.runOnIdle {
            viewModel.addImage(ContactValueKind.PHOTO)
            viewModel.addImage(ContactValueKind.PHOTO)
            viewModel.addImage(ContactValueKind.LOGO)
        }
        compose.onNode(hasScrollAction()).performScrollToIndex(0)
        val firstPhoto = string(R.string.field_value_occurrence, string(R.string.field_photo), 1, 2)
        compose.onNodeWithContentDescription(string(R.string.field_preferred, firstPhoto))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(string(R.string.field_move_down, firstPhoto)).assertIsEnabled()
        compose.onNodeWithContentDescription(string(R.string.field_delete, firstPhoto)).assertIsDisplayed()
        compose.onNodeWithContentDescription(string(R.string.field_preferred, string(R.string.field_logo)))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(string(R.string.field_delete, string(R.string.field_logo))).assertIsDisplayed()

        compose.runOnIdle { darkTheme = true }
        compose.onNode(hasScrollAction()).performScrollToIndex(0)
        compose.onNodeWithContentDescription(string(R.string.field_preferred, firstPhoto))
            .performScrollTo().assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToIndex(4)
        compose.onNodeWithContentDescription(string(R.string.field_custom_date_local_only))
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun contactAndGroupEditorsRemainReachableAtTwoHundredPercentFontScale() {
        val repository = RoomContactRepository(database)
        runBlocking {
            repository.saveContact(
                CanonicalContact(
                    accountId = LOCAL_ACCOUNT_ID,
                    id = "large-editor-contact",
                    displayName = "Large editor contact",
                    values = listOf(
                        ContactValue("large-email-a", ContactValueKind.EMAIL, "large.a@example.test", order = 0),
                        ContactValue("large-email-b", ContactValueKind.EMAIL, "large.b@example.test", order = 1),
                    ),
                ),
            )
            repository.saveGroup(
                ContactGroup(
                    accountId = LOCAL_ACCOUNT_ID,
                    id = "large-editor-group",
                    name = "Large editor group",
                    color = "#8080FF",
                ),
            )
        }
        val viewModel = ContactsViewModel(repository)
        var darkTheme by mutableStateOf(false)
        compose.setContent {
            if (showUi) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    ContakoTheme(systemDarkTheme = darkTheme) { ContakoApp(viewModel) }
                }
            }
        }

        compose.waitUntilNodeExists("Large editor contact")
        compose.onNodeWithText("Large editor contact").performClick()
        listOf(false, true).forEach { useDarkTheme ->
            compose.runOnIdle { darkTheme = useDarkTheme }
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodes(
                    androidx.compose.ui.test.hasContentDescription(string(R.string.contact_edit_action)),
                ).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription(string(R.string.contact_edit_action)).performClick()
            compose.onNodeWithText(string(R.string.nav_groups)).assertDoesNotExist()
            compose.onNodeWithContentDescription(string(R.string.action_cancel)).assertIsDisplayed()
            compose.onNodeWithTag(CONTACT_EDITOR_TOP_SAVE_TAG).assertIsDisplayed()
            compose.onNodeWithText(string(R.string.field_first_name)).assertIsDisplayed()
            compose.onNode(hasScrollAction()).performScrollToIndex(2)
            compose.onNodeWithTag("contact_value_options_large-email-b").performScrollTo().performClick()
            compose.onNodeWithContentDescription(
                string(
                    R.string.field_make_preferred,
                    string(R.string.field_value_occurrence, string(R.string.field_email), 2, 2),
                ),
            ).performScrollTo().assertHasClickAction()
            compose.onNodeWithContentDescription(
                string(
                    R.string.field_move_up,
                    string(R.string.field_value_occurrence, string(R.string.field_email), 2, 2),
                ),
            ).performScrollTo().assertHasClickAction()
            compose.onNodeWithContentDescription(
                string(
                    R.string.field_delete,
                    string(R.string.field_value_occurrence, string(R.string.field_email), 2, 2),
                ),
            ).performScrollTo().assertHasClickAction()
            compose.onNodeWithText(string(R.string.editor_email_groups)).performScrollTo().performClick()
            compose.onAllNodesWithText("Large editor group")[0].performScrollTo().assertIsDisplayed()
            compose.onNode(hasScrollAction()).performScrollToIndex(10)
            compose.onNodeWithTag(CONTACT_EDITOR_BOTTOM_SAVE_TAG).performScrollTo().assertIsDisplayed()
            compose.onNodeWithContentDescription(string(R.string.action_cancel)).performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                viewModel.uiState.value.contactEditor == null || viewModel.uiState.value.showUnsavedConfirmation
            }
            if (viewModel.uiState.value.showUnsavedConfirmation) {
                compose.onNodeWithText(string(R.string.dialog_discard_changes)).performClick()
            }
            compose.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.contactEditor == null }
        }

        compose.onNodeWithContentDescription(string(R.string.action_back)).performClick()
        compose.onNodeWithText(string(R.string.nav_groups)).performClick()
        compose.waitUntilNodeExists("Large editor group")
        compose.onNodeWithText("Large editor group").performClick()
        listOf(false, true).forEach { useDarkTheme ->
            compose.runOnIdle { darkTheme = useDarkTheme }
            compose.onNodeWithContentDescription(string(R.string.groups_edit)).performClick()
            compose.onNodeWithText(string(R.string.nav_contacts)).assertDoesNotExist()
            compose.onNodeWithContentDescription(string(R.string.action_cancel)).assertIsDisplayed()
            compose.onNodeWithTag(GROUP_EDITOR_TOP_SAVE_TAG).assertIsDisplayed()
            compose.onNodeWithText(string(R.string.groups_name)).assertIsDisplayed()
            val selectedColor = EditorPresentationPolicy.groupColorPalette.indexOf("#8080FF") + 1
            compose.onNodeWithContentDescription(string(R.string.groups_color_choice,
                selectedColor, EditorPresentationPolicy.groupColorPalette.size))
                .performScrollTo().assertIsDisplayed().assertIsSelected()
            compose.onNode(hasScrollAction()).performScrollToNode(androidx.compose.ui.test.hasText("large.a@example.test"))
            compose.onNodeWithText("large.a@example.test").assertIsDisplayed()
            compose.onNode(hasScrollAction()).performScrollToNode(androidx.compose.ui.test.hasText("large.b@example.test"))
            compose.onNodeWithText("large.b@example.test").assertIsDisplayed()
            compose.onNode(hasScrollAction()).performScrollToIndex(4)
            compose.onNodeWithTag(GROUP_EDITOR_BOTTOM_SAVE_TAG).performScrollTo().assertIsDisplayed()
            compose.onNodeWithContentDescription(string(R.string.action_cancel)).performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                viewModel.uiState.value.groupEditor == null || viewModel.uiState.value.showUnsavedConfirmation
            }
            if (viewModel.uiState.value.showUnsavedConfirmation) {
                compose.onNodeWithText(string(R.string.dialog_discard_changes)).performClick()
            }
            compose.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.groupEditor == null }
        }
    }

    @Test
    fun groupEditorKeepsThreeHundredEmailMembershipsLazyAndReachable() {
        val repository = RoomContactRepository(database)
        runBlocking {
            repeat(300) { index ->
                val ordinal = index.toString().padStart(3, '0')
                check(
                    repository.saveContact(
                        CanonicalContact(
                            accountId = LOCAL_ACCOUNT_ID,
                            id = "volume-contact-$ordinal",
                            displayName = "Volume contact $ordinal",
                            values = listOf(
                                ContactValue(
                                    id = "volume-email-$ordinal",
                                    kind = ContactValueKind.EMAIL,
                                    value = "volume.$ordinal@example.test",
                                    order = 0,
                                ),
                            ),
                        ),
                    ) is com.patmanak.contako.domain.repository.SaveResult.Saved,
                )
            }
        }
        val viewModel = ContactsViewModel(repository)
        compose.setContent { if (showUi) ContakoTheme { ContakoApp(viewModel) } }

        compose.waitUntilNodeExists("Volume contact 000")
        compose.onNodeWithText(string(R.string.nav_groups)).performClick()
        compose.onNodeWithContentDescription(string(R.string.groups_new)).performClick()
        compose.onNodeWithText(string(R.string.nav_contacts)).assertDoesNotExist()
        compose.onNodeWithTag(GROUP_EDITOR_TOP_SAVE_TAG).performClick()
        compose.onNodeWithText(string(R.string.error_enter_group_name)).assertIsDisplayed()
        compose.onNodeWithText("volume.000@example.test").performScrollTo().assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToIndex(301)
        compose.onNodeWithText("volume.299@example.test").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToIndex(302)
        compose.onNodeWithTag(GROUP_EDITOR_BOTTOM_SAVE_TAG).assertIsDisplayed()
    }

    @Test
    fun longAccountWrapsAtLargeFontAndInfoExposesProjectLink() {
        val viewModel = ContactsViewModel(RoomContactRepository(database))
        val address = "a.long.account.name.for.readable.menu.layout@example.test"
        compose.setContent {
            if (showUi) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    ContakoTheme { ContakoApp(viewModel, accountAddress = address) }
                }
            }
        }
        compose.onNodeWithContentDescription(string(R.string.account_menu)).performClick()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText(address).assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.single().lineCount > 1)
        assertTrue(!layouts.single().hasVisualOverflow)
        val accountLeft = compose.onNodeWithText(address).fetchSemanticsNode().boundsInRoot.left
        val settingsLeft = compose.onNodeWithText(string(R.string.nav_settings), useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.left
        assertEquals(accountLeft, settingsLeft, 1f)
        compose.onNodeWithText(string(R.string.nav_about)).performScrollTo().performClick()
        compose.onNodeWithText(string(R.string.about_github)).performScrollTo().assertHasClickAction()
        compose.onNodeWithTag("information_content").performScrollToNode(
            androidx.compose.ui.test.hasText(string(R.string.about_license_details)))
        compose.onNodeWithText(string(R.string.about_license_details)).performScrollTo()
            .assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText(string(R.string.nav_usage)).assertDoesNotExist()
        compose.onNodeWithContentDescription(string(R.string.account_menu)).performClick()
        compose.onNodeWithText(string(R.string.nav_usage)).performScrollTo().performClick()
        compose.onNodeWithText(string(R.string.nav_usage)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.nav_about)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.about_features)).assertIsDisplayed()
        compose.onNodeWithTag("information_content").performScrollToNode(
            androidx.compose.ui.test.hasText(string(R.string.about_limits_summary)))
        compose.onNodeWithText(string(R.string.about_limits_summary)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(string(R.string.action_back)).performClick()
        compose.onNodeWithContentDescription(string(R.string.directory_search)).assertIsDisplayed()
        compose.onNodeWithContentDescription(string(R.string.account_menu)).performClick()
        compose.onNodeWithText(string(R.string.nav_about)).performScrollTo().performClick()
        compose.onNodeWithText(string(R.string.about_title)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun preservedFieldsShowTheirContentWithoutCountingWholeSourceCards() {
        val repository = RoomContactRepository(database)
        val contact = CanonicalContact(accountId = LOCAL_ACCOUNT_ID, id = "preserved-ui",
            displayName = "Preserved fixture", values = listOf(
                ContactValue("extra", ContactValueKind.UNKNOWN_VCARD_PROPERTY, "Équipe azur", "X-TEAM", 0)),
            preservationEnvelope = com.patmanak.contako.domain.model.PreservationEnvelope(
                rawProperties = mapOf("proton-card-2-0" to "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Preserved fixture\r\nEND:VCARD")))
        runBlocking { repository.saveContact(contact) }
        val viewModel = ContactsViewModel(repository)
        compose.setContent { if (showUi) ContakoTheme { ContakoApp(viewModel) } }
        compose.waitUntilNodeExists(contact.displayName)
        compose.onNodeWithText(contact.displayName).performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(androidx.compose.ui.test.hasText("X-TEAM"))
        compose.onNodeWithText("X-TEAM").assertIsDisplayed()
        compose.onNodeWithText("Équipe azur").assertIsDisplayed()
        compose.onNodeWithText(string(R.string.status_preserved_read_only)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.status_android_compatible)).assertDoesNotExist()
        compose.runOnIdle { viewModel.handleBack() }
        runBlocking { repository.saveContact(contact.copy(values = emptyList())) }
        compose.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.contacts.single().values.isEmpty() }
        compose.onNodeWithText(contact.displayName).performClick()
        compose.onNode(hasScrollAction()).performScrollToIndex(1)
        compose.onNodeWithText(string(R.string.contact_preserved_section)).assertDoesNotExist()
    }

    private fun string(@StringRes resource: Int, vararg arguments: Any): String =
        compose.activity.getString(resource, *arguments)

    private fun plural(@PluralsRes resource: Int, quantity: Int, vararg arguments: Any): String =
        compose.activity.resources.getQuantityString(resource, quantity, *arguments)

    private fun pauseForVisualInspectionIfRequested() {
        val requested = InstrumentationRegistry.getArguments()
            .getString("visualPauseMillis")
            ?.toLongOrNull()
            ?.coerceIn(0L, 60_000L)
            ?: 0L
        if (requested > 0L) SystemClock.sleep(requested)
    }

    private fun visualDarkThemeOverride(): Boolean? = InstrumentationRegistry.getArguments()
        .getString("visualDarkTheme")
        ?.toBooleanStrictOrNull()

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.waitUntilNodeExists(text: String) {
        waitUntil(timeoutMillis = 5_000) {
            onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.waitUntilNodeDoesNotExist(text: String) {
        waitUntil(timeoutMillis = 5_000) {
            onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().isEmpty()
        }
    }

    private companion object {
        const val DATABASE_NAME = "contako-v01-ui-journey.db"
    }

    private class DevicePermissionBoundary : ContactsPermissionBoundary {
        override val granted = MutableStateFlow(false)
        override val action = MutableStateFlow(ContactsPermissionAction.REQUEST)
        var requested = false
        override fun requestPermission() { requested = true }
    }

    private class DeviceSyncRecoveryDataSource : SyncRecoveryDataSource {
        val repair = MutableStateFlow<RepairProgress?>(null)
        val activity = MutableStateFlow(SyncActivity.IDLE)
        var cancelled = false
        var syncRequested = false
        override fun observeStatus(accountId: String): Flow<SyncDashboardSnapshot?> = MutableStateFlow(
            SyncDashboardSnapshot(SyncDashboardState.BLOCKED, actionRequiredCount = 1),
        )
        override fun observeRepair(accountId: String): Flow<RepairProgress?> = repair
        override fun observeActivity(accountId: String): Flow<SyncActivity> = activity
        override suspend fun beginRepair(accountId: String, mobileDataConfirmed: Boolean) =
            if (mobileDataConfirmed) RepairStartResult.STARTED else RepairStartResult.CONFIRMATION_REQUIRED
        override suspend fun cancelRepair(accountId: String): Boolean {
            cancelled = true
            return true
        }

        override suspend fun requestSync(accountId: String) {
            syncRequested = true
        }
    }
}
