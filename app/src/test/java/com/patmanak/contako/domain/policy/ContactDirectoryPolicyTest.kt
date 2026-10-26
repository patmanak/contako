package com.patmanak.contako.domain.policy

import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.model.PreservationEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactDirectoryPolicyTest {
    private val now = 1_800_000_000_000L
    private val week = 7L * 24 * 60 * 60 * 1_000

    @Test fun defaultFilterIsInactiveAndEachCriterionActivatesIt() {
        assertFalse(DirectoryFilter().isActive)
        listOf(DirectoryFilter(withoutPhone = true), DirectoryFilter(withoutEmail = true),
            DirectoryFilter(organizations = setOf("company")), DirectoryFilter(localOnly = true),
            DirectoryFilter(recentChanges = true)).forEach { assertTrue(it.isActive) }
        assertTrue(matches(contact(), DirectoryFilter()))
    }

    @Test fun accentAndCaseEquivalentOrganizationFacetsAreDeterministicAndIntersectSearch() {
        val elodie = contact(value(ContactValueKind.ORGANIZATION, " Société Démo ")).copy(displayName = "Élodie")
        val other = contact(value(ContactValueKind.ORGANIZATION, "societe demo"),
            value(ContactValueKind.ORGANIZATION, "Second Company", order = 1)).copy(id = "other", displayName = "Alice")
        val facets = ContactDirectoryPolicy.organizationFacets(listOf(elodie, other))
        assertEquals(listOf(OrganizationFacet("second company", "Second Company"),
            OrganizationFacet("societe demo", "Société Démo")), facets)
        assertEquals(facets, ContactDirectoryPolicy.organizationFacets(listOf(other, elodie)))
        val filter = DirectoryFilter(organizations = setOf(ContactSearch.normalize("SOCIÉTÉ DÉMO")))
        val result = listOf(elodie, other).filter { matches(it, filter) && ContactSearch.matches(it, "ELODIE") }
        assertEquals(listOf(elodie), result)
        assertEquals(" Société Démo ", elodie.values.single().value)
        assertEquals("societe demo", other.values.first().value)
    }

    @Test fun accumulatedFacetsMatchGroupingReferenceForUnicodeDuplicatesAndLongDirectories() {
        val labels = listOf(" Société Démo ", "societe demo", "SOCIÉTÉ DÉMO", "E\u0301quipe", "Équipe",
            "İpek", "Ipek", "Işık", "ışık", "Straße", "STRASSE", "東京", "Δelta", "\u0301", "\u0300\u0301",
            "", " \t\n", "\u00a0\u202f", "Ångström", "A\u030angström", " ANGSTROM ")
        val contacts = (0 until 5_000).map { index ->
            val repeated = labels[index % labels.size]
            CanonicalContact("facet-reference-owner", "facet-$index", isDeleted = index % 23 == 0,
                values = listOf(
                    value(ContactValueKind.ORGANIZATION, repeated),
                    value(ContactValueKind.ORGANIZATION, labels[(index + 3) % labels.size], order = 1),
                    value(ContactValueKind.ORGANIZATION, repeated, order = 2),
                    value(ContactValueKind.ORGANIZATION, "Synthetic company ${index.toString().padStart(5, '0')}", order = 3),
                    value(ContactValueKind.NOTE, "Must never become a company facet"),
                ))
        }
        // Independent grouping/sorting reference keeps the previous observable rules: empty
        // normalized keys are valid, labels use natural minimum, and source multiplicity survives.
        fun reference(input: List<CanonicalContact>) = input.asSequence()
            .filterNot(CanonicalContact::isDeleted)
            .flatMap { it.values.asSequence() }
            .filter { it.kind == ContactValueKind.ORGANIZATION && it.value.isNotBlank() }
            .map { it.value.trim() }
            .groupBy(ContactSearch::normalize)
            .toSortedMap()
            .map { (key, group) -> OrganizationFacet(key, group.minOrNull()!!) }
        val expected = reference(contacts)
        val originals = contacts.map { it.copy(values = it.values.map { value -> value.copy() }) }
        assertEquals(expected, ContactDirectoryPolicy.organizationFacets(contacts))
        assertEquals(expected, ContactDirectoryPolicy.organizationFacets(contacts.reversed()))
        assertEquals(expected, ContactDirectoryPolicy.organizationFacets(contacts.shuffled(kotlin.random.Random(47))))
        assertTrue(expected.any { it.key.isEmpty() })
        assertEquals(originals, contacts)
    }

    @Test fun withoutEmailAndPhoneExamineEveryOccurrenceIncludingInvalidNonblankValues() {
        for ((kind, filter) in listOf(ContactValueKind.EMAIL to DirectoryFilter(withoutEmail = true),
                ContactValueKind.PHONE to DirectoryFilter(withoutPhone = true))) {
            val blankPreferred = value(kind, " \t", primary = true)
            val secondary = value(kind, "invalid-but-present", order = 1)
            assertFalse(matches(contact(blankPreferred, secondary), filter))
            assertTrue(matches(contact(blankPreferred), filter))
            assertTrue(matches(contact(), filter))
        }
    }

    @Test fun organizationsUseOrWhileOtherCriteriaUseAndWithoutDuplicatingContacts() {
        val filter = DirectoryFilter(withoutPhone = true, organizations = setOf("alpha", "beta"))
        val alpha = contact(value(ContactValueKind.ORGANIZATION, "Alpha"))
        val beta = contact(value(ContactValueKind.ORGANIZATION, "BÉTA"))
        val both = contact(value(ContactValueKind.ORGANIZATION, "Alpha"), value(ContactValueKind.ORGANIZATION, "Beta", order = 1))
        assertTrue(matches(alpha, filter))
        assertTrue(matches(beta, filter))
        assertEquals(listOf(both), listOf(both).filter { matches(it, filter) })
        assertFalse(matches(contact(value(ContactValueKind.ORGANIZATION, "Gamma")), filter))
        assertFalse(matches(contact(value(ContactValueKind.ORGANIZATION, "Alpha"), value(ContactValueKind.PHONE, "x")), filter))
        assertFalse(matches(contact(value(ContactValueKind.ORGANIZATION, " \t")), filter))
        assertTrue(matches(contact(), DirectoryFilter(organizations = emptySet())))
    }

    @Test fun localOnlyRequiresContentAndDoesNotInferRepresentationFromPendingOrUnknownFields() {
        val filter = DirectoryFilter(localOnly = true)
        val customDate = value(ContactValueKind.CUSTOM_DATE, "--10-25")
        assertTrue(matches(contact(customDate), filter))
        assertTrue(matches(contact(value(ContactValueKind.PHONETIC_NAME, "", components = mapOf("family" to "Phonetic"))), filter))
        assertFalse(matches(contact(value(ContactValueKind.CUSTOM_DATE, " \t"),
            value(ContactValueKind.PHONETIC_NAME, "", components = mapOf("family" to " "))), filter))
        assertFalse(matches(contact(value(ContactValueKind.EMAIL, "person@example.test")).copy(
            pendingMutationRevision = 2, actionRequiredReasons = setOf("NETWORK_ERROR")), filter))
        assertFalse(matches(contact(value(ContactValueKind.UNKNOWN_VCARD_PROPERTY, "opaque",
            metadata = mapOf("syncDisposition" to "LOCAL_ONLY"))).copy(
            preservationEnvelope = PreservationEnvelope(mapOf("opaque" to "private preserved data"))), filter))
        assertFalse(matches(contact(value(ContactValueKind.URL, "", label = "Profile")), filter))
        assertTrue(matches(contact(value(ContactValueKind.POSTAL_ADDRESS, "", label = "Cabin",
            components = mapOf("street" to "Example Street"))), filter))
    }

    @Test fun allCriteriaCanBeAppliedTogetherWithoutChangingCanonicalValues() {
        val values = listOf(value(ContactValueKind.ORGANIZATION, "Example"),
            value(ContactValueKind.CUSTOM_DATE, "--10-25", metadata = mapOf("fixture" to "preserve")))
        val original = CanonicalContact("synthetic-account", "synthetic-contact", values = values)
        val snapshot = original.copy(values = values.map { it.copy(components = it.components.toMap(), metadata = it.metadata.toMap()) })
        val filter = DirectoryFilter(withoutPhone = true, withoutEmail = true, organizations = setOf("example"),
            localOnly = true, recentChanges = true)
        assertTrue(matches(original, filter, now))
        ContactDirectoryPolicy.organizationFacets(listOf(original))
        assertEquals(snapshot, original)
        assertSame(values, original.values)
        assertSame(values.last(), original.values.last())
        assertFalse(matches(original, filter, null))
    }

    @Test fun recentChangesIncludeBothSevenDayBoundariesAndExcludeFutureUnknownAndStaleDates() {
        val filter = DirectoryFilter(recentChanges = true)
        val imported = contact().copy(updatedAtEpochMillis = now)
        assertTrue(matches(imported, filter, now))
        assertTrue(matches(imported, filter, now - week))
        assertFalse(matches(imported, filter, now - week - 1))
        assertFalse(matches(imported, filter, now + 1))
        assertFalse(matches(imported, filter, null))
        assertFalse(ContactDirectoryPolicy.matches(imported, filter, now, now - 1))
        assertTrue(matches(imported, DirectoryFilter(), null))
    }

    @Test fun extremeClockValuesDoNotWrapTheInclusiveWindow() {
        val filter = DirectoryFilter(recentChanges = true)
        assertTrue(ContactDirectoryPolicy.matches(contact(), filter, Long.MIN_VALUE, Long.MIN_VALUE))
        assertFalse(ContactDirectoryPolicy.matches(contact(), filter, Long.MAX_VALUE, Long.MIN_VALUE))
        assertTrue(ContactDirectoryPolicy.matches(contact(), filter, Long.MIN_VALUE, Long.MIN_VALUE + week))
        assertFalse(ContactDirectoryPolicy.matches(contact(), filter, Long.MIN_VALUE, Long.MIN_VALUE + week + 1))
        assertTrue(ContactDirectoryPolicy.matches(contact(), filter, Long.MAX_VALUE - week, Long.MAX_VALUE))
        assertFalse(ContactDirectoryPolicy.matches(contact(), filter, Long.MIN_VALUE, Long.MAX_VALUE))
    }

    @Test fun tombstonesDoNotMatchOrContributeOrganizationFacets() {
        val deleted = contact(value(ContactValueKind.ORGANIZATION, "Deleted Company")).copy(isDeleted = true)
        assertFalse(matches(deleted, DirectoryFilter()))
        assertTrue(ContactDirectoryPolicy.organizationFacets(listOf(deleted)).isEmpty())
    }

    @Test fun diagnosticStringsDoNotExposeOrganizationLabelsOrKeys() {
        val privateLabel = "Synthetic Organization Canary"
        assertFalse(OrganizationFacet(privateLabel.lowercase(), privateLabel).toString().contains("Canary", ignoreCase = true))
        assertFalse(DirectoryFilter(organizations = setOf(privateLabel)).toString().contains("Canary", ignoreCase = true))
    }

    private fun matches(contact: CanonicalContact, filter: DirectoryFilter, changedAt: Long? = null): Boolean =
        ContactDirectoryPolicy.matches(contact, filter, changedAt, now)

    private fun contact(vararg values: ContactValue) = CanonicalContact("synthetic-account", "synthetic-contact", values = values.toList())

    private fun value(kind: ContactValueKind, text: String, order: Int = 0, primary: Boolean = false,
        label: String? = null, components: Map<String, String> = emptyMap(), metadata: Map<String, String> = emptyMap()) =
        ContactValue("${kind.name}-$order", kind, text, label, order, primary, components, metadata)
}
