package com.patmanak.contako.domain.policy

import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import org.junit.Assert.*
import org.junit.Test

class ProtonAliasContactPolicyTest {
    private val filter = DirectoryFilter(hideAliasOnly = true)

    @Test fun knownPublicServiceDomainsCoverAliasAndLegacyReverseAliasAddresses() {
        listOf("pass2mail.com", "passfwd.com", "passinbox.com", "passmail.com", "passmail.net",
            "8alias.com", "8shield.net", "aleeas.com", "dralias.com", "silomails.com", "simplelogin.co",
            "simplelogin.com", "simplelogin.fr", "simplelogin.io", "slmail.me", "slmails.com").forEach { domain ->
            assertFalse(domain, matches(contact("synthetic@${domain.uppercase(java.util.Locale.ROOT)}")))
            assertTrue(domain, matches(contact("synthetic@$domain").copy(displayName = "Useful name")))
        }
        assertFalse(matches(contact("ra+synthetic@simplelogin.co")))
        assertFalse(matches(contact("reply+synthetic@simplelogin.io")))
    }

    @Test fun aliasOnlyIsOptInAndInspectsEveryEmailWithoutChangingData() {
        val alias = contact("first@passmail.net", "second@passmail.net", "first@passmail.net")
        val before = alias.copy(values = alias.values.map { it.copy() })
        assertTrue(matches(alias, DirectoryFilter()))
        assertFalse(matches(alias))
        assertFalse(matches(alias.copy(displayName = " FIRST@PASSMAIL.NET ")))
        assertFalse(matches(alias.copy(values = alias.values + value(ContactValueKind.PHONE, " \t"))))
        assertTrue(matches(contact("first@passmail.net", "person@example.test")))
        assertTrue(matches(contact()))
        assertEquals(before, alias)
    }

    @Test fun namesAndEveryNonEmailFieldFamilyKeepTheContactVisible() {
        val alias = contact("first@passmail.net")
        assertTrue(matches(alias.copy(displayName = "Zoë ß")))
        assertTrue(matches(alias.copy(firstName = "Ana")))
        assertTrue(matches(alias.copy(lastName = "Durand")))
        ContactValueKind.entries.filter { it != ContactValueKind.EMAIL }.forEach { kind ->
            assertTrue(kind.name, matches(alias.copy(values = alias.values + value(kind, "Useful field"))))
            assertTrue(kind.name, matches(alias.copy(values = alias.values +
                value(kind, "").copy(components = mapOf("synthetic" to "Useful component")))))
            assertTrue(kind.name, matches(alias.copy(values = alias.values +
                value(kind, "").copy(binaryReference = "synthetic-media-reference"))))
        }
    }

    @Test fun emptyImportedStructuredNameDoesNotEnrichTheCardButHiddenComponentsDo() {
        val alias = contact("first@passmail.net")
        val emptyName = value(ContactValueKind.STRUCTURED_NAME, ";;;;").copy(
            components = mapOf("given" to "", "family" to "", "additional" to "", "prefix" to "", "suffix" to ""))
        assertFalse(matches(alias.copy(values = alias.values + emptyName)))
        assertTrue(matches(alias.copy(values = alias.values + emptyName.copy(
            components = emptyName.components + ("suffix" to "Jr")))))
        // A malformed scalar without parsed components remains visible, rather than guessing.
        assertTrue(matches(alias.copy(values = alias.values + emptyName.copy(components = emptyMap()))))
    }

    @Test fun exactDomainsCaseWhitespaceAndMalformedValuesAreConservative() {
        assertFalse(matches(contact(" PERSON@PASSMAIL.NET ")))
        listOf("person@proton.me", "person@protonmail.com", "person@pm.me", "person@protonmail.ch",
            "person@passmail.net.example.test", "person@notpassmail.net", "person@custom.example.test",
            "person@sub.passmail.net", "@passmail.net", "person@@passmail.net", "per son@passmail.net",
            "Name <person@passmail.net>", "person@passmail.net.").forEach { address ->
            assertTrue(address, matches(contact(address)))
            assertTrue(address, matches(contact("first@passmail.net", address)))
        }
    }

    @Test fun hidingAliasesStillIntersectsOtherCriteriaAndKeepsPendingIssuesOutOfScope() {
        val alias = contact("first@passmail.net").copy(pendingMutationRevision = 2,
            actionRequiredReasons = setOf("INVALID_EMAIL"))
        assertFalse(matches(alias))
        val named = alias.copy(displayName = "Useful name")
        assertTrue(matches(named, filter.copy(withoutPhone = true)))
        assertFalse(matches(named, filter.copy(withoutEmail = true)))
        assertEquals(setOf("INVALID_EMAIL"), alias.actionRequiredReasons)
        assertEquals(2L, alias.pendingMutationRevision)
    }

    private fun matches(contact: CanonicalContact, criteria: DirectoryFilter = filter) =
        ContactDirectoryPolicy.matches(contact, criteria, null, 0)
    private fun contact(vararg emails: String) = CanonicalContact("synthetic", "alias-fixture",
        values = emails.mapIndexed { index, email -> value(ContactValueKind.EMAIL, email).copy(id = "email-$index", order = index) })
    private fun value(kind: ContactValueKind, text: String) = ContactValue("synthetic-field", kind, text, order = 0)
}
