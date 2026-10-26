package com.patmanak.contako.data.android.mapping

import com.patmanak.contako.data.local.retainLocalContactComplements
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactGroup
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.model.GroupMembership
import com.patmanak.contako.domain.model.PreservationEnvelope
import com.patmanak.contako.domain.policy.ContactValueSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactFieldCompatibilityTest {
    private val mapper = CanonicalAndroidContactMapper()

    @Test fun everyFamilyAndOccurrenceAgreeWithProductionProjectionWithoutProviderFacts() {
        ContactValueKind.entries.forEach { kind ->
            val contact = contact((0..1).map { occurrence(kind, it) })
            val mappedIds = representedIds(mapper.project(contact))
            val expectedScope = when (contact.values.count { it.id in mappedIds }) {
                2 -> AndroidFieldScope.ALL_OCCURRENCES
                1 -> AndroidFieldScope.PRIMARY_ONLY
                else -> AndroidFieldScope.NONE
            }
            val family = ContactFieldCompatibility.describeFamily(kind)
            assertEquals(kind.name, expectedScope, family.android)
            assertNull(family.represented)
            assertNull(family.preferredEmail)
            val expectedProton = when (kind) {
                ContactValueKind.CUSTOM_DATE, ContactValueKind.PHONETIC_NAME -> ProtonFieldSupport.LOCAL_ONLY
                ContactValueKind.UNKNOWN_VCARD_PROPERTY -> ProtonFieldSupport.PRESERVED_READ_ONLY
                else -> ProtonFieldSupport.GENERATED
            }
            assertEquals(kind.name, expectedProton, family.proton)
            contact.values.forEach { value ->
                assertEquals("Actual occurrence differs for ${kind.name}", value.id in mappedIds,
                    ContactFieldCompatibility.describe(contact, value.id).represented)
            }
        }
    }

    @Test fun secondaryNotesPhotosNamesAndPhoneticsFollowRealPreferenceAndLinkedRows() {
        val singleFamilies = listOf(ContactValueKind.NOTE, ContactValueKind.PHOTO, ContactValueKind.STRUCTURED_NAME,
            ContactValueKind.PHONETIC_NAME, ContactValueKind.ORGANIZATION, ContactValueKind.TITLE, ContactValueKind.ROLE)
        val values = singleFamilies.flatMap { kind ->
            listOf(occurrence(kind, 0).copy(isPrimary = true), occurrence(kind, 1).copy(
                isPrimary = false, metadata = mapOf("vcardPref" to "1")))
        }
        val contact = contact(values)
        val rows = mapper.project(contact).rows
        singleFamilies.forEach { kind ->
            assertEquals(AndroidFieldScope.PRIMARY_ONLY, ContactFieldCompatibility.describeFamily(kind).android)
            assertEquals(false, ContactFieldCompatibility.describe(contact, id(kind, 0)).represented)
            assertEquals(true, ContactFieldCompatibility.describe(contact, id(kind, 1)).represented)
        }
        val name = rows.single { it.kind == AndroidRowKind.STRUCTURED_NAME }
        assertEquals(id(ContactValueKind.STRUCTURED_NAME, 1), name.identity.canonicalValueId)
        assertEquals(id(ContactValueKind.PHONETIC_NAME, 1), name.linkedCanonicalValueIds[AndroidLinkedValueRole.PHONETIC_NAME])
        val organization = rows.single { it.kind == AndroidRowKind.ORGANIZATION }
        assertEquals(id(ContactValueKind.TITLE, 1), organization.linkedCanonicalValueIds[AndroidLinkedValueRole.TITLE])
        assertEquals(id(ContactValueKind.ROLE, 1), organization.linkedCanonicalValueIds[AndroidLinkedValueRole.ROLE])
    }

    @Test fun emailRowsRemainRepresentableWhileOnlyPreferredOccurrenceSuppliesAndroidGroups() {
        val first = occurrence(ContactValueKind.EMAIL, 0).copy(value = "duplicate@example.test", isPrimary = true,
            metadata = mapOf("vcardPref" to "5"))
        val second = occurrence(ContactValueKind.EMAIL, 1).copy(value = first.value, isPrimary = false,
            metadata = mapOf("vcardPref" to "1"))
        val contact = contact(listOf(first, second))
        val groups = listOf(
            ContactGroup(contact.accountId, "first-group", "First", memberships = listOf(GroupMembership(contact.id, first.id))),
            ContactGroup(contact.accountId, "second-group", "Second", memberships = listOf(GroupMembership(contact.id, second.id))),
        )
        val before = groups.map { it.copy(memberships = it.memberships.toList()) }
        val one = ContactFieldCompatibility.describe(contact, first.id)
        val two = ContactFieldCompatibility.describe(contact, second.id)
        assertEquals(AndroidFieldScope.ALL_OCCURRENCES, one.android)
        assertEquals(true, one.represented)
        assertEquals(true, two.represented)
        assertEquals(false, one.preferredEmail)
        assertEquals(true, two.preferredEmail)
        val projection = AndroidGroupProjectionPolicy().projectMemberships(contact, groups)
        assertEquals(second.id, projection.preferredEmailValueId)
        assertEquals(setOf("second-group"), projection.canonicalGroupIds)
        assertEquals(before, groups)
    }

    @Test fun localLabelDoesNotTurnTheProtonUrlValueIntoALocalOnlyValue() {
        val url = occurrence(ContactValueKind.URL, 0).copy(label = "Profile",
            metadata = mapOf("androidSemanticType" to "PROFILE"))
        val date = occurrence(ContactValueKind.CUSTOM_DATE, 0)
        val phonetic = occurrence(ContactValueKind.PHONETIC_NAME, 0)
        val local = contact(listOf(url, date, phonetic))
        val description = ContactFieldCompatibility.describe(local, url.id)
        assertTrue(ContactValueSupport.isLocalOnly(url))
        assertFalse(ContactValueSupport.isLocalOnlyValue(url.kind))
        assertEquals(ProtonFieldSupport.GENERATED, description.proton)
        assertTrue(description.localLabelOnly)
        val changedRemoteUrl = url.copy(id = "remote-url", value = "https://example.test/changed", label = null, metadata = emptyMap())
        val adopted = retainLocalContactComplements(local, contact(listOf(changedRemoteUrl)))
        assertEquals(listOf(changedRemoteUrl, date, phonetic), adopted.values)
        assertEquals(ProtonFieldSupport.LOCAL_ONLY, ContactFieldCompatibility.describe(local, date.id).proton)
        assertEquals(ProtonFieldSupport.LOCAL_ONLY, ContactFieldCompatibility.describe(local, phonetic.id).proton)
    }

    @Test fun mediaAndUnknownFieldsStayDistinctAndDescriptionsContainNoContactData() {
        val secret = "private-reference-canary"
        val photo = occurrence(ContactValueKind.PHOTO, 0).copy(binaryReference = "https://example.test/$secret")
        val logo = occurrence(ContactValueKind.LOGO, 0).copy(binaryReference = "https://example.test/logo/$secret")
        val opaque = occurrence(ContactValueKind.UNKNOWN_VCARD_PROPERTY, 0).copy(value = "X-PRIVATE:$secret")
        val contact = contact(listOf(photo, logo, opaque)).copy(id = secret, accountId = secret, displayName = secret,
            preservationEnvelope = PreservationEnvelope(mapOf(secret to secret)))
        val before = contact.copy(values = contact.values.map { it.copy(components = it.components.toMap(), metadata = it.metadata.toMap()) },
            preservationEnvelope = contact.preservationEnvelope?.let { it.copy(rawProperties = it.rawProperties.toMap()) })
        repeat(3) {
            val photoDescription = ContactFieldCompatibility.describe(contact, photo.id)
            val logoDescription = ContactFieldCompatibility.describe(contact, logo.id)
            val unknownDescription = ContactFieldCompatibility.describe(contact, opaque.id)
            // Theoretical mapper representation: no remote image resolution or delivery claim.
            assertEquals(true, photoDescription.represented)
            assertEquals(false, logoDescription.represented)
            assertEquals(ProtonFieldSupport.PRESERVED_READ_ONLY, unknownDescription.proton)
            assertEquals(false, unknownDescription.represented)
            listOf(photoDescription, logoDescription, unknownDescription).forEach { description ->
                assertFalse(description.toString().contains(secret))
            }
        }
        assertEquals(before, contact)
    }

    @Test fun missingOrAmbiguousValueIdentityRemainsUnknownAndSimplePhoneHasNoExtraHelp() {
        val note = occurrence(ContactValueKind.NOTE, 0)
        listOf(contact(emptyList()), contact(listOf(note, note.copy(value = "Second"))))
            .forEach { contact ->
                val description = ContactFieldCompatibility.describe(contact, note.id)
                assertEquals(ProtonFieldSupport.UNKNOWN, description.proton)
                assertEquals(AndroidFieldScope.UNKNOWN, description.android)
                assertNull(description.represented)
                assertNull(description.preferredEmail)
            }
        assertFalse(ContactFieldCompatibility.needsExplanation(ContactValueKind.PHONE))
        listOf(ContactValueKind.CUSTOM_DATE, ContactValueKind.PHONETIC_NAME, ContactValueKind.NOTE,
            ContactValueKind.EMAIL, ContactValueKind.LOGO, ContactValueKind.UNKNOWN_VCARD_PROPERTY,
            ContactValueKind.URL, ContactValueKind.POSTAL_ADDRESS, ContactValueKind.PHOTO).forEach { kind ->
            assertTrue(kind.name, ContactFieldCompatibility.needsExplanation(kind))
        }
    }

    private fun representedIds(snapshot: AndroidContactSnapshot) = snapshot.rows.flatMap {
        listOf(it.identity.canonicalValueId) + it.linkedCanonicalValueIds.values
    }.toSet()

    private fun contact(values: List<ContactValue>) = CanonicalContact("synthetic-account", "synthetic-contact",
        displayName = "Synthetic Contact", values = values)

    private fun id(kind: ContactValueKind, index: Int) = "${kind.name}-$index"

    private fun occurrence(kind: ContactValueKind, index: Int): ContactValue {
        val text = when (kind) {
            ContactValueKind.EMAIL -> "occurrence-$index@example.test"
            ContactValueKind.PHONE -> "+3360000000${index + 1}"
            ContactValueKind.URL -> "https://example.test/$index"
            ContactValueKind.BIRTHDAY, ContactValueKind.ANNIVERSARY, ContactValueKind.CUSTOM_DATE -> "--10-2${index + 1}"
            ContactValueKind.PHOTO, ContactValueKind.LOGO -> INLINE_PROBE_IMAGE
            else -> "Synthetic occurrence $index"
        }
        val components = when (kind) {
            ContactValueKind.STRUCTURED_NAME, ContactValueKind.PHONETIC_NAME -> mapOf("given" to "Synthetic $index", "family" to "Example")
            ContactValueKind.ORGANIZATION -> mapOf("company" to "Synthetic Co", "department" to "Examples")
            ContactValueKind.POSTAL_ADDRESS -> mapOf("street" to "12 Synthetic Lane", "locality" to "Example City")
            else -> emptyMap()
        }
        return ContactValue(id(kind, index), kind, text, order = index, components = components,
            binaryReference = if (kind in setOf(ContactValueKind.PHOTO, ContactValueKind.LOGO)) text else null)
    }

    private companion object {
        const val INLINE_PROBE_IMAGE = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    }
}
