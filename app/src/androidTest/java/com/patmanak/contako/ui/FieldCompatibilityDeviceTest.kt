package com.patmanak.contako.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.patmanak.contako.R
import com.patmanak.contako.qa.saveSyntheticUiEvidence
import com.patmanak.contako.data.android.mapping.ContactFieldCompatibility
import com.patmanak.contako.data.proton.ProtonContactVCardCodec
import com.patmanak.contako.data.proton.ProtonPlainContactCard
import com.patmanak.contako.domain.model.EmailGroupAssignmentAvailability
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactGroup
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.repository.*
import com.patmanak.contako.ui.theme.ContakoTheme
import com.patmanak.contako.ui.theme.ThemeMode
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import me.proton.core.contact.domain.entity.Contact
import me.proton.core.contact.domain.entity.ContactCardType
import me.proton.core.contact.domain.entity.ContactId
import me.proton.core.domain.entity.UserId

/** Local help and draft/navigation evidence only; no account, provider, image fetch or remote peer. */
class FieldCompatibilityDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun importedUrlLabelHelpFollowsUnsavedEditAndRevertWithoutSavingTheDraft() {
        val original = ProtonContactVCardCodec().decode("synthetic-help-owner",
            Contact(UserId("synthetic-user"), ContactId("synthetic-remote"), "Synthetic fixture", emptyList()),
            listOf(ProtonPlainContactCard(ContactCardType.EncryptedAndSigned,
                "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:synthetic-uid\r\nFN:Synthetic fixture\r\n" +
                    "URL;TYPE=HOME:https://example.test/profile\r\nEND:VCARD\r\n")))
        val url = original.valuesOf(ContactValueKind.URL).single()
        val repository = NoWrites(original)
        val owner = ViewModelStore()
        val model = ViewModelProvider(owner, ContactsViewModel.factory(repository, accountId = original.accountId,
            navigationState = SavedStateHandle()))[ContactsViewModel::class.java]
        try {
            compose.setContent { ContakoTheme { ContakoApp(model) } }
            compose.runOnIdle { model.editContact(original) }
            compose.waitUntil(10_000) { model.uiState.value.contactEditor != null }
            fun help(localLabel: Boolean) {
                openInformationValue(ContactValueKind.URL)
                awaitHelp()
                show(R.string.compatibility_proton_generated)
                if (localLabel) show(R.string.compatibility_local_label)
                else compose.onNodeWithText(text(R.string.compatibility_local_label)).assertDoesNotExist()
                compose.onNodeWithTag("field-help-close").performScrollTo().performClick()
                compose.onNodeWithTag("contact-information-back").performClick()
            }
            help(false)
            compose.onNodeWithTag("contact_editor_fields").performScrollToNode(hasTestTag("contact_value_options_${url.id}"))
            compose.onNodeWithTag("contact_value_options_${url.id}").performClick()
            compose.onNodeWithText(text(R.string.field_custom_label)).performScrollTo().performTextReplacement("Personal profile")
            val draft = model.uiState.value.contactEditor
            help(true)
            compose.runOnIdle {
                assertEquals(draft, model.uiState.value.contactEditor)
                assertEquals(original, model.uiState.value.contactEditor?.original)
                assertEquals(0, repository.writes)
                assertEquals(false, model.uiState.value.contactEditor?.values?.single { it.id == url.id }
                    ?.metadata?.containsKey("contakoLocalOnlyLabel"))
            }
            compose.onNodeWithTag("contact_editor_fields").performScrollToNode(hasTestTag("contact_value_options_${url.id}"))
            compose.onNodeWithText(text(R.string.field_custom_label)).performScrollTo().performTextReplacement(url.label.orEmpty())
            help(false)
            compose.runOnIdle {
                assertEquals(url, model.uiState.value.contactEditor?.values?.single { it.id == url.id })
                assertEquals(0, repository.writes)
            }
        } finally { compose.runOnIdle { owner.clear() } }
    }

    @Test fun transferableUrlAndLocalLabelKeepFourScopesAndMissingPermissionSeparate() {
        val contact = contact(listOf(ContactValue("url", ContactValueKind.URL, "https://example.test/profile", order = 0,
            label = "Synthetic label", metadata = mapOf("contakoLocalOnlyLabel" to "true"))))
        val facts = ContactFieldCompatibility.describe(contact, "url")
        var status = 0
        compose.setContent { ContakoTheme {
            FieldCompatibilityContent(facts, contactsPermissionGranted = false, groups = false,
                assignmentAvailability = EmailGroupAssignmentAvailability.UNKNOWN, onClose = {}, onStatus = { status++ })
        } }
        listOf(R.string.compatibility_local, R.string.compatibility_proton, R.string.compatibility_android,
            R.string.compatibility_editor).forEach { show(it) }
        show(R.string.compatibility_proton_generated)
        show(R.string.compatibility_local_label)
        show(R.string.compatibility_permission)
        compose.onNodeWithTag("field-help-status").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, status)
            assertEquals("https://example.test/profile", contact.values.single().value)
            assertEquals("Synthetic label", contact.values.single().label)
        }
    }

    @Test fun secondaryNoteIsRetainedWithoutClaimingItsAndroidRepresentationOrDelivery() {
        val contact = contact(listOf(
            ContactValue("first", ContactValueKind.NOTE, "Primary synthetic note", order = 0),
            ContactValue("second", ContactValueKind.NOTE, "Secondary synthetic note", order = 1),
        ))
        val facts = ContactFieldCompatibility.describe(contact, "second")
        compose.setContent { ContakoTheme {
            FieldCompatibilityContent(facts, true, false, EmailGroupAssignmentAvailability.UNKNOWN, onClose = {})
        } }
        show(R.string.compatibility_local_text)
        show(R.string.compatibility_proton_generated)
        show(R.string.compatibility_android_primary)
        show(R.string.compatibility_occurrence_omitted)
        show(R.string.compatibility_delivery)
        compose.runOnIdle {
            assertEquals(false, facts.represented)
            assertEquals(listOf("Primary synthetic note", "Secondary synthetic note"), contact.values.map { it.value })
        }
    }

    @Test fun groupCapabilityDistinguishesUnknownDeniedAndAvailableWithoutAnyProbeCallback() {
        var availability by mutableStateOf(EmailGroupAssignmentAvailability.UNKNOWN)
        val facts = ContactFieldCompatibility.describeFamily(ContactValueKind.EMAIL)
        var closes = 0
        compose.setContent { ContakoTheme {
            FieldCompatibilityContent(facts, true, true, availability, onClose = { closes++ })
        } }
        listOf(EmailGroupAssignmentAvailability.UNKNOWN to R.string.compatibility_groups_unknown,
            EmailGroupAssignmentAvailability.DENIED to R.string.compatibility_groups_denied,
            EmailGroupAssignmentAvailability.AVAILABLE to R.string.compatibility_groups_available).forEach { (next, label) ->
            compose.runOnIdle { availability = next }
            compose.onNodeWithTag("field-help-group-capability").performScrollTo().assertTextEquals(text(label))
        }
        compose.onNodeWithTag("field-help-close").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, closes) }
    }

    @Test fun pickerHelpPreservesDraftPickerAndScrollAcrossCompositionRestorationWithoutAddingAField() {
        val original = contact(listOf(ContactValue("phone", ContactValueKind.PHONE, "+33600000001", order = 0)))
        val repository = NoWrites(original)
        val owner = ViewModelStore()
        val model = ViewModelProvider(owner, ContactsViewModel.factory(repository, accountId = original.accountId,
            navigationState = SavedStateHandle()))[ContactsViewModel::class.java]
        val restoration = StateRestorationTester(compose)
        try {
            restoration.setContent { ContakoTheme { ContakoApp(model) } }
            compose.runOnIdle { model.editContact(original) }
            compose.waitUntil(10_000) { model.uiState.value.contactEditor != null }
            val draft = requireNotNull(model.uiState.value.contactEditor)
            compose.onNodeWithTag("field-help-PHONE").assertDoesNotExist()
            compose.onNodeWithText(text(R.string.editor_add_field)).performClick()
            compose.onNodeWithTag("contact_editor_field_picker").performScrollToNode(hasTestTag("field-help-URL"))
            compose.onNodeWithTag("field-help-URL").assertIsDisplayed().performClick()
            awaitHelp()
            compose.onNodeWithTag("field-help-close").performScrollTo().assertIsDisplayed()
            restoration.emulateSavedInstanceStateRestore()
            awaitHelp()
            compose.onNodeWithTag("field-help-close").performScrollTo().performClick()
            compose.onNodeWithTag("contact_editor_field_picker").assertIsDisplayed()
            compose.onNodeWithTag("field-help-URL").assertIsDisplayed()
            compose.onNodeWithTag("field-help-PHONE").assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(draft, model.uiState.value.contactEditor)
                assertNull(model.fieldCompatibility.value)
                assertEquals(0, repository.writes)
                assertEquals(listOf(ContactValueKind.PHONE), model.uiState.value.contactEditor?.values?.map { it.kind })
            }
        } finally { compose.runOnIdle { owner.clear() } }
    }

    @Test fun helpRemainsScrollableToStatusAndCloseAtDoubleFontScaleInDarkTheme() {
        val facts = ContactFieldCompatibility.describeFamily(ContactValueKind.LOGO)
        var status = 0
        var closes = 0
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                ContakoTheme(themeMode = ThemeMode.DARK) {
                    FieldCompatibilityContent(facts, false, false, EmailGroupAssignmentAvailability.UNKNOWN,
                        onClose = { closes++ }, onStatus = { status++ })
                }
            }
        }
        show(R.string.compatibility_android_none)
        show(R.string.compatibility_editor_text)
        compose.onNodeWithTag("field-help-status").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("field-help-close").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, status); assertEquals(1, closes) }
    }

    @Test fun unknownPropertiesRemainPreservedReadOnlyAndOpaqueAndroidDataIsNotPromoted() {
        val facts = ContactFieldCompatibility.describeFamily(ContactValueKind.UNKNOWN_VCARD_PROPERTY)
        compose.setContent { ContakoTheme {
            FieldCompatibilityContent(facts, true, false, EmailGroupAssignmentAvailability.UNKNOWN, onClose = {})
        } }
        show(R.string.compatibility_proton_preserved)
        show(R.string.compatibility_android_none)
        show(R.string.compatibility_opaque_android)
        compose.onNodeWithTag("field-help-status").assertDoesNotExist()
        compose.onNodeWithTag("field-help-close").performScrollTo().assertIsDisplayed()
    }

    @Test fun imageOccurrenceHelpIsReachableInTheRealEditorAtDoubleFontScaleWithoutChangingItsReference() {
        val image = ContactValue("synthetic-photo", ContactValueKind.PHOTO, "https://example.test/image.png", order = 0,
            binaryReference = "https://example.test/image.png")
        val original = contact(listOf(image))
        val repository = NoWrites(original)
        val owner = ViewModelStore()
        val model = ViewModelProvider(owner, ContactsViewModel.factory(repository, accountId = original.accountId,
            navigationState = SavedStateHandle()))[ContactsViewModel::class.java]
        try {
            compose.setContent {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                    ContakoTheme(themeMode = ThemeMode.DARK) { ContakoApp(model, themeMode = ThemeMode.DARK) }
                }
            }
            compose.runOnIdle { model.editContact(original) }
            compose.waitUntil(10_000) { model.uiState.value.contactEditor != null }
            val draft = requireNotNull(model.uiState.value.contactEditor)
            openInformationValue(ContactValueKind.PHOTO)
            awaitHelp()
            compose.onNodeWithTag("field-compatibility-help").assertIsDisplayed()
            show(R.string.compatibility_photo)
            compose.onNodeWithTag("field-help-close").performScrollTo().assertIsDisplayed().performClick()
            compose.onNodeWithTag("contact-information-back").performClick()
            compose.runOnIdle {
                assertEquals(draft, model.uiState.value.contactEditor)
                assertEquals(listOf(image), model.uiState.value.contactEditor?.images)
                assertNull(model.fieldCompatibility.value)
                assertEquals(0, repository.writes)
            }
        } finally { compose.runOnIdle { owner.clear() } }
    }

    @Test fun contactDetailKeepsTechnicalFieldsBehindOneEntryAndRestoresTheInformationPage() {
        val opaque = ContactValue("opaque", ContactValueKind.UNKNOWN_VCARD_PROPERTY,
            "X-SYNTHETIC:preserved", label = "X-SYNTHETIC", order = 1)
        val original = contact(listOf(ContactValue("phone", ContactValueKind.PHONE, "+33600000001", order = 0), opaque))
        val repository = NoWrites(original)
        val owner = ViewModelStore()
        val model = ViewModelProvider(owner, ContactsViewModel.factory(repository, accountId = original.accountId,
            navigationState = SavedStateHandle()))[ContactsViewModel::class.java]
        val restoration = StateRestorationTester(compose)
        try {
            restoration.setContent { ContakoTheme { ContakoApp(model) } }
            compose.runOnIdle { model.selectContact(original) }
            compose.onNodeWithText("+33600000001").assertIsDisplayed()
            compose.onNodeWithText(opaque.value).assertDoesNotExist()
            compose.onNodeWithText(text(R.string.contact_read_only_body)).assertDoesNotExist()
            compose.onNodeWithTag("field-help-PHONE").assertDoesNotExist()
            compose.waitForIdle()
            saveSyntheticUiEvidence("contact-detail")
            compose.onNodeWithTag("contact-information-entry").performScrollTo().performClick()
            awaitInformationProperties()
            compose.waitForIdle()
            saveSyntheticUiEvidence("contact-information")
            compose.onNodeWithTag("contact-information-fields").performScrollToNode(hasText(opaque.value))
            compose.onNodeWithText(opaque.value).assertIsDisplayed()
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithTag("contact-information-page").assertIsDisplayed()
            awaitInformationProperties()
            compose.onNodeWithTag("contact-information-fields").performScrollToNode(hasText(opaque.value))
            compose.onNodeWithText(opaque.value).assertIsDisplayed()
            compose.onNodeWithTag("contact-information-back").performClick()
            compose.onNodeWithText("+33600000001").assertIsDisplayed()
            compose.runOnIdle { assertEquals(0, repository.writes) }
        } finally { compose.runOnIdle { owner.clear() } }
    }

    private fun openInformationValue(kind: ContactValueKind) {
        compose.onNodeWithTag("contact_editor_fields").performScrollToNode(hasTestTag("contact-information-entry"))
        compose.onNodeWithTag("contact-information-entry").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("contact-information-SHARED").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("contact-information-SHARED").performClick()
        compose.onNodeWithTag("contact-information-fields").performScrollToNode(hasTestTag("contact-information-value-${kind.name}"))
        compose.waitForIdle()
        saveSyntheticUiEvidence("information-${kind.name.lowercase(java.util.Locale.ROOT)}")
        compose.onNodeWithTag("contact-information-value-${kind.name}").performClick()
    }

    private fun awaitInformationProperties() = compose.waitUntil(10_000) {
        compose.onAllNodesWithTag("contact-information-READ_ONLY").fetchSemanticsNodes().isNotEmpty()
    }

    private fun show(id: Int) = compose.onNodeWithText(text(id)).performScrollTo().assertIsDisplayed()
    private fun awaitHelp() = compose.waitUntil(10_000) {
        compose.onAllNodesWithTag("field-compatibility-help").fetchSemanticsNodes().isNotEmpty()
    }
    private fun text(id: Int) = compose.activity.getString(id)
    private fun contact(values: List<ContactValue>) = CanonicalContact("synthetic-help-owner", "synthetic-help-contact",
        displayName = "Synthetic compatibility", values = values)

    private class NoWrites(private val original: CanonicalContact) : ContactRepository {
        var writes = 0
        override fun observeContacts(accountId: String) = flowOf(listOf(original))
        override fun observeGroups(accountId: String) = flowOf(emptyList<ContactGroup>())
        override fun observePendingMutationCount(accountId: String) = flowOf(0)
        override suspend fun getContact(accountId: String, contactId: String) = original.takeIf { it.id == contactId }
        override suspend fun saveContact(contact: CanonicalContact): SaveResult<CanonicalContact> { writes++; error("No write from help") }
        override suspend fun saveContactWithGroupAssignments(contact: CanonicalContact, assignments: Set<ContactGroupAssignment>,
            managedGroupIds: Set<String>, baseline: ContactEditBaseline?): SaveResult<CanonicalContact> { writes++; error("No write from help") }
        override suspend fun deleteContact(accountId: String, contactId: String) { writes++; error("No delete from help") }
        override suspend fun saveGroup(group: ContactGroup): SaveResult<ContactGroup> { writes++; error("No group write from help") }
        override suspend fun deleteGroup(accountId: String, groupId: String) { writes++; error("No group delete from help") }
    }
}
