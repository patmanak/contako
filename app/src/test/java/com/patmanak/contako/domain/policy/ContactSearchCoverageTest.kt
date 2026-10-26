package com.patmanak.contako.domain.policy

import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactSearchCoverageTest {
    @Test fun preparedContactRetainsOccurrenceBoundariesAndUnicodeWithoutIndexingPrivateFields() {
        val original = CanonicalContact("synthetic-account", "same-id", firstName = "Zoë", lastName = "Işık",
            values = listOf(value(ContactValueKind.EMAIL, "first@example.test"),
                value(ContactValueKind.EMAIL, "second@example.test").copy(id = "secondary", order = 1),
                value(ContactValueKind.ORGANIZATION, "E\u0301quipe Weiß"),
                value(ContactValueKind.NOTE, "private-canary"),
                value(ContactValueKind.PHOTO, "photo-canary")))
        val prepared = ContactSearch.prepareContact(original)
        val expected = mapOf("zoe" to true, "ISIK" to true, "équipe weiß" to true,
            "equipe weiss" to false, "second@" to true, "test second" to false,
            "private-canary" to false, "photo-canary" to false, "  " to true)
        expected.forEach { (query, match) ->
            assertEquals(match, ContactSearch.prepare(query).matches(prepared))
            assertEquals(ContactSearch.matches(original, query), ContactSearch.prepare(query).matches(prepared))
        }
        // A fresh snapshot with the same identity must not reuse stale normalized fields.
        val edited = original.copy(firstName = "Chloë", values = original.values.filterNot {
            it.id == "secondary"
        })
        val refreshed = ContactSearch.prepareContact(edited)
        assertTrue(ContactSearch.prepare("chloe").matches(refreshed))
        assertFalse(ContactSearch.prepare("zoe").matches(refreshed))
        assertFalse(ContactSearch.prepare("second@").matches(refreshed))
        assertTrue(ContactSearch.prepare("second@").matches(prepared))
        assertEquals(original.values.size, 5)
        assertEquals("PreparedContact(REDACTED)", prepared.toString())
    }

    @Test
    fun preparedQueriesRetainUnicodeFamilyAndEmptyQuerySemanticsAcrossContactsAndGroups() {
        val first = CanonicalContact(
            accountId = "synthetic-account", id = "first", displayName = "Zoë Weiß",
            values = listOf(value(ContactValueKind.EMAIL, "shared@example.test"),
                value(ContactValueKind.NOTE, "hidden-only")),
        )
        val second = first.copy(id = "second", displayName = "Işık", values = listOf(
            value(ContactValueKind.ORGANIZATION, "E\u0301quipe"),
            value(ContactValueKind.EMAIL, "shared@example.test"),
            value(ContactValueKind.UNKNOWN_VCARD_PROPERTY, "hidden-only"),
        ))
        val contacts = listOf(first, second)
        val expectedIds = linkedMapOf(
            "  ZOE  " to listOf("first"), "weiß" to listOf("first"),
            "weiss" to emptyList(), "ISIK" to listOf("second"),
            "équipe" to listOf("second"), "shared@" to listOf("first", "second"),
            "hidden-only" to emptyList(), "  " to listOf("first", "second"),
        )
        val prepared = expectedIds.keys.associateWith(ContactSearch::prepare)
        expectedIds.forEach { (query, ids) ->
            val matcher = prepared.getValue(query)
            assertEquals(ids, contacts.filter(matcher::matches).map { it.id })
            assertEquals(ids, contacts.filter { ContactSearch.matches(it, query) }.map { it.id })
        }
        val groupMatcher = ContactSearch.prepare(" EQUIPE ")
        assertTrue(groupMatcher.matchesGroupName("Équipe"))
        assertFalse(groupMatcher.matchesGroupName("Unrelated"))
        assertTrue(ContactSearch.prepare(" ").matchesGroupName(""))
        // Preparing another query cannot change a previously prepared matcher.
        ContactSearch.prepare("unrelated")
        assertTrue(groupMatcher.matchesGroupName("E\u0301quipe"))
    }

    @Test
    fun localSearchCoversNamesNicknameOrganizationEmailAndPhone() {
        val contact = CanonicalContact(
            accountId = "synthetic-account",
            id = "synthetic-contact",
            firstName = "Élodie",
            lastName = "Işık",
            displayName = "Dr E. Example",
            values = listOf(
                value(ContactValueKind.NICKNAME, "Lili"),
                value(ContactValueKind.ORGANIZATION, "Société Démo"),
                value(ContactValueKind.EMAIL, "elodie@example.test"),
                value(ContactValueKind.PHONE, "+33 6 12 34 56 78"),
                value(ContactValueKind.NOTE, "not-indexed-canary"),
                value(ContactValueKind.UNKNOWN_VCARD_PROPERTY, "not-indexed-extension"),
            ),
        )

        listOf("elodie", "ISIK", "dr e.", "lili", "societe demo", "example.test", "+33 6 12")
            .forEach { query -> assertTrue("Expected local match for $query", ContactSearch.matches(contact, query)) }
        assertFalse(ContactSearch.matches(contact, "remote-only-value"))
        assertFalse(ContactSearch.matches(contact, "not-indexed-canary"))
        assertFalse(ContactSearch.matches(contact, "not-indexed-extension"))
        assertTrue(ContactSearch.matchesGroupName("Équipe Démo", "equipe demo"))
    }

    @Test
    fun localSearchIsDeterministicAcrossDefaultLocalesAndAcceptsEmptyQuery() {
        val originalLocale = Locale.getDefault()
        val contact = CanonicalContact(
            accountId = "synthetic-account",
            id = "synthetic-contact",
            displayName = "İpek Ångström",
        )
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val turkishResult = ContactSearch.matches(contact, "ipek angstrom")
            Locale.setDefault(Locale.forLanguageTag("en-US"))
            val englishResult = ContactSearch.matches(contact, "ipek angstrom")

            assertTrue(turkishResult)
            assertTrue(englishResult)
            assertTrue(ContactSearch.matches(contact, "   "))
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    private fun value(kind: ContactValueKind, raw: String) = ContactValue(
        id = "value-${kind.name}",
        kind = kind,
        value = raw,
        order = 0,
    )
}
