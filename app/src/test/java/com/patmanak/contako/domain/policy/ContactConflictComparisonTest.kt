package com.patmanak.contako.domain.policy

import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactGroup
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.model.GroupMembership
import com.patmanak.contako.domain.model.PreservationEnvelope
import org.junit.Assert.*
import org.junit.Test

class ContactConflictComparisonTest {
    @Test fun noteOnlyChangeAppearsBeforeIdenticalNamesAndPreservesCompleteValues() {
        val local = contact(listOf(value(ContactValueKind.NOTE, "Une note Straße 東京")))
        val remote = local.copy(id = "remote-contact", values = listOf(local.values.single().copy(value = "Another note")))
        val rows = ContactConflictComparison.compare(local, remote)
        assertEquals(1, rows.count { it.difference == ConflictDifference.DIFFERENT })
        assertEquals(ContactValueKind.NOTE, rows.first().kind)
        assertEquals(local.values.single(), rows.first().localValue)
        assertEquals(remote.values.single(), rows.first().protonValue)
        assertEquals(4, rows.count { it.difference == ConflictDifference.SAME })
    }

    @Test fun labelsComponentsPreferencesMeaningfulOrderAndMetadataEachProduceDifferences() {
        val original = value(ContactValueKind.POSTAL_ADDRESS, "Straße 12", label = "Home").copy(
            components = mapOf("street" to "Straße 12", "locality" to "München"),
            metadata = mapOf("vcardPref" to "2", "vcardTypeTokens" to "HOME"))
        val changes = listOf(original.copy(label = "Work"), original.copy(order = 2),
            original.copy(isPrimary = true), original.copy(components = original.components + ("locality" to "Zürich")),
            original.copy(metadata = original.metadata + ("vcardPref" to "3")),
            original.copy(metadata = original.metadata + ("vcardTypeTokens" to "WORK")))
        changes.forEach { changed ->
            val row = valueRows(contact(listOf(original)), contact(listOf(changed))).single()
            assertEquals(ConflictDifference.DIFFERENT, row.difference)
            assertEquals(original, row.localValue)
            assertEquals(changed, row.protonValue)
        }
        val rebound = original.copy(id = "changed-positional-id", preservationKey = "different-source",
            metadata = original.metadata + ("contakoLocalOnlyLabel" to "true"))
        assertEquals(ConflictDifference.SAME, valueRows(contact(listOf(original)), contact(listOf(rebound))).single().difference)
    }

    @Test fun repeatedEmailServiceIdentitiesKeepGroupsSeparateAcrossReordering() {
        val first = email("local-a", "service-a", "label-a", 0)
        val second = email("local-b", "service-b", "label-b", 1)
        val local = contact(listOf(first, second))
        val remote = contact(listOf(second.copy(id = "position-1", order = 0), first.copy(id = "position-2", order = 1)))
        val groups = listOf(group("group-a", "label-a", "Friends", first.id), group("group-b", "label-b", "Work", second.id))
        val rows = valueRows(local, remote, groups)
        assertEquals(2, rows.size)
        assertEquals(listOf("service-a", "service-b"), rows.map { it.localValue?.metadata?.get("protonEmailId") })
        assertEquals(listOf("service-a", "service-b"), rows.map { it.protonValue?.metadata?.get("protonEmailId") })
        assertEquals(listOf(2, 1), rows.map { it.protonOrdinal })
        assertEquals(listOf(listOf("Friends"), listOf("Work")), rows.map { it.localGroups })
        assertEquals(rows.map { it.localGroups }, rows.map { it.protonGroups })
        assertTrue(rows.all { it.difference == ConflictDifference.DIFFERENT && !it.ambiguous })
    }

    @Test fun oneRepeatedEmailGroupChangeDoesNotAlterTheOtherOccurrence() {
        val first = email("local-a", "service-a", "label-a", 0)
        val second = email("local-b", "service-b", "label-b", 1)
        val groups = listOf(group("group-a", "label-a", "Friends", first.id), group("group-b", "label-b", "Work", second.id))
        val remote = listOf(first.copy(metadata = first.metadata + ("protonGroupIds" to "")), second)
        val rows = valueRows(contact(listOf(first, second)), contact(remote), groups)
        assertEquals(2, rows.size)
        assertEquals(1, rows.count { it.difference == ConflictDifference.DIFFERENT })
        assertEquals(1, rows.count { it.difference == ConflictDifference.SAME })
        assertEquals(listOf("Friends"), rows.first().localGroups)
        assertEquals(emptyList<String>(), rows.first().protonGroups)
    }

    @Test fun positionalKeysAndSharedServiceTokensNeverMergeRepeatedEmails() {
        val first = email("position-1", "shared", "", 0).copy(preservationKey = "card#line=1")
        val second = email("position-2", "shared", "", 1).copy(preservationKey = "card#line=2")
        listOf(
            listOf(first, second),
            listOf(first, second).map { it.copy(metadata = it.metadata - "protonEmailId") },
        ).forEach { values ->
            val rows = valueRows(contact(values), contact(values.reversed()))
            assertEquals(4, rows.size)
            assertEquals(2, rows.count { it.localValue != null })
            assertEquals(2, rows.count { it.protonValue != null })
            assertTrue(rows.all { it.difference == ConflictDifference.UNAVAILABLE && it.ambiguous })
            assertTrue(rows.all { it.localValue == null || it.protonValue == null })
        }
    }

    @Test fun repeatedNonEmailValuesStayUnpairedEvenWhenAllTextAndPositionKeysAgree() {
        val values = listOf(value(ContactValueKind.PHONE, "+49 123", id = "same-1"),
            value(ContactValueKind.PHONE, "+49 123", id = "same-2").copy(order = 1))
        val rows = valueRows(contact(values), contact(values))
        assertEquals(4, rows.size)
        assertTrue(rows.all { it.ambiguous && it.difference == ConflictDifference.UNAVAILABLE })
    }

    @Test fun unknownGroupMetadataCatalogAndDuplicateLabelsRemainUnknown() {
        val original = email("email", "service", "label", 0)
        val cases = listOf(
            original.copy(metadata = original.metadata - "protonGroupIds") to listOf(group("group", "label", "Group", "email")),
            original to emptyList(),
            original to listOf(group("a", "label", "One", "email"), group("b", "label", "Two", "email")),
            original to listOf(group("group", "label", "Other owner", "email").copy(accountId = "another-owner")),
        )
        cases.forEach { (remote, groups) ->
            val row = valueRows(contact(listOf(original)), contact(listOf(remote)), groups).single()
            assertEquals(ConflictDifference.UNAVAILABLE, row.difference)
            assertNull(row.protonGroups)
        }
    }

    @Test fun sameNamedDifferentGroupsAreStillDifferentAndEmptyKnownAssignmentsAreIdentical() {
        val local = email("email", "service", "label-a", 0)
        val remote = local.copy(metadata = local.metadata + ("protonGroupIds" to "label-b"))
        val groups = listOf(group("a", "label-a", "Same name", local.id), group("b", "label-b", "Same name", "unrelated"))
        val row = valueRows(contact(listOf(local)), contact(listOf(remote)), groups).single()
        assertEquals(row.localGroups, row.protonGroups)
        assertEquals(ConflictDifference.DIFFERENT, row.difference)
        val unassigned = local.copy(metadata = local.metadata + ("protonGroupIds" to ""))
        assertEquals(ConflictDifference.SAME, valueRows(contact(listOf(unassigned)), contact(listOf(unassigned))).single().difference)
    }

    @Test fun photosAndLogosCompareStoredInlineRepresentationsButNeverTrustRemoteReferences() {
        val photo = value(ContactValueKind.PHOTO, "data:image/png;base64,c3ludGhldGlj", id = "photo")
        val logo = value(ContactValueKind.LOGO, "data:image/png;base64,bG9nbw==", id = "logo")
        val local = contact(listOf(photo, logo))
        val same = valueRows(local, local)
        assertEquals(setOf(ContactValueKind.PHOTO, ContactValueKind.LOGO), same.map { it.kind }.toSet())
        assertTrue(same.all { it.difference == ConflictDifference.SAME })
        val changed = valueRows(local, contact(listOf(photo.copy(value = "data:image/png;base64,Y2hhbmdlZA=="), logo)))
        assertEquals(ConflictDifference.DIFFERENT, changed.single { it.kind == ContactValueKind.PHOTO }.difference)
        val external = photo.copy(value = "https://example.invalid/private-image")
        assertEquals(ConflictDifference.UNAVAILABLE, valueRows(contact(listOf(external)), contact(listOf(external))).single().difference)
    }

    @Test fun unknownValuesRemainReadableWhileSourceCardsAreOnlyAnUnavailableAggregate() {
        val unknown = value(ContactValueKind.UNKNOWN_VCARD_PROPERTY, "X-EXAMPLE:Straße 東京")
        val local = contact(listOf(unknown)).copy(preservationEnvelope = PreservationEnvelope(mapOf("proton-card-1" to "BEGIN:VCARD\nprivate source\nEND:VCARD")))
        val rows = ContactConflictComparison.compare(local, local)
        assertEquals(unknown, rows.single { it.kind == ContactValueKind.UNKNOWN_VCARD_PROPERTY }.localValue)
        val preservation = rows.single { it.field == ConflictField.PRESERVATION }
        assertEquals(ConflictDifference.UNAVAILABLE, preservation.difference)
        assertNull(preservation.localText)
        assertNull(preservation.protonText)
        assertNull(preservation.localValue)
        assertNull(preservation.protonValue)
        assertFalse(rows.toString().contains("private source"))
        assertFalse(rows.toString().contains("X-EXAMPLE"))
    }

    @Test fun singleSidedFamiliesAndEmptyScalarsHaveExplicitDirectionWithoutMutatingInputs() {
        val phone = value(ContactValueKind.PHONE, "+49 123")
        val note = value(ContactValueKind.NOTE, "remote note")
        val local = contact(listOf(phone)).copy(firstName = "", lastName = "Weiß")
        val remote = contact(listOf(note)).copy(firstName = "Zoë", lastName = "", isDeleted = true)
        val rows = ContactConflictComparison.compare(local, remote)
        assertEquals(ConflictDifference.LOCAL_ONLY, rows.single { it.kind == ContactValueKind.PHONE }.difference)
        assertEquals(ConflictDifference.PROTON_ONLY, rows.single { it.kind == ContactValueKind.NOTE }.difference)
        assertEquals(ConflictDifference.PROTON_ONLY, rows.single { it.field == ConflictField.FIRST_NAME }.difference)
        assertEquals(ConflictDifference.LOCAL_ONLY, rows.single { it.field == ConflictField.LAST_NAME }.difference)
        assertEquals(ConflictDifference.DIFFERENT, rows.single { it.field == ConflictField.DELETION }.difference)
        assertEquals(listOf(phone), local.values)
        assertEquals(listOf(note), remote.values)
    }

    @Test fun ownerMismatchIsRejectedWithoutEmbeddingPrivateFieldsInTheError() {
        val exception = runCatching { ContactConflictComparison.compare(contact(), contact().copy(accountId = "foreign-owner")) }.exceptionOrNull()
        assertTrue(exception is IllegalArgumentException)
        assertEquals("Comparison requires one owner", exception?.message)
    }

    private fun contact(values: List<ContactValue> = emptyList()) = CanonicalContact("synthetic-owner", "local-contact",
        firstName = "Élodie", lastName = "Weiß", displayName = "Élodie Weiß", values = values)

    private fun value(kind: ContactValueKind, text: String, id: String = "value", label: String? = null) =
        ContactValue(id, kind, text, label, order = 0)

    private fun email(id: String, service: String, groups: String, order: Int) =
        value(ContactValueKind.EMAIL, "repeated@example.test", id).copy(order = order,
            metadata = mapOf("protonEmailId" to service, "protonGroupIds" to groups))

    private fun group(id: String, remote: String, name: String, email: String) = ContactGroup("synthetic-owner", id, name,
        remoteLabelId = remote, memberships = listOf(GroupMembership("local-contact", email)))

    private fun valueRows(local: CanonicalContact, remote: CanonicalContact, groups: List<ContactGroup> = emptyList()) =
        ContactConflictComparison.compare(local, remote, groups).filter { it.field == ConflictField.VALUE }
}
