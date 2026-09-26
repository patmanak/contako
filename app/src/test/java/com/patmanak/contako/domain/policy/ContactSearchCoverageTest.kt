package com.patmanak.contako.domain.policy

import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import java.util.Locale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactSearchCoverageTest {
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
