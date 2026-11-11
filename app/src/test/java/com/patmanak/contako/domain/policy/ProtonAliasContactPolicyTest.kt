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
            assertFalse(domain, matches(contact("synthetic@$domain").copy(displayName = "Generated name")))
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

    @Test fun namesDoNotKeepAnOtherwiseAliasOnlyContactVisible() {
        val alias = contact("first@passmail.net")
        listOf(alias.copy(displayName = "Zoë ß"), alias.copy(firstName = "Ana"),
            alias.copy(lastName = "Durand"), alias.copy(firstName = "first@passmail.net"),
            alias.copy(firstName = "First", lastName = "Alias", displayName = "Generated name")
        ).forEach { assertFalse(matches(it)) }
        listOf(ContactValueKind.STRUCTURED_NAME, ContactValueKind.PHONETIC_NAME, ContactValueKind.NICKNAME)
            .forEach { kind ->
                assertFalse(kind.name, matches(alias.copy(values = alias.values + value(kind, "Zoë Straße"))))
                assertFalse(kind.name, matches(alias.copy(values = alias.values +
                    value(kind, "").copy(components = mapOf("given" to "Élodie", "family" to "Straße", "suffix" to "Jr")))))
            }
    }

    @Test fun usefulFieldsAndOpaqueDataKeepEvenNamedAliasesVisible() {
        val alias = contact("first@passmail.net").copy(firstName = "Zoë", displayName = "Named alias")
        val nonEnriching = setOf(ContactValueKind.EMAIL, ContactValueKind.STRUCTURED_NAME,
            ContactValueKind.PHONETIC_NAME, ContactValueKind.NICKNAME, ContactValueKind.CATEGORY)
        ContactValueKind.entries.filter { it !in nonEnriching }.forEach { kind ->
            assertTrue(kind.name, matches(alias.copy(values = alias.values + value(kind, "Useful field"))))
            assertTrue(kind.name, matches(alias.copy(values = alias.values +
                value(kind, "").copy(components = mapOf("synthetic" to "Useful component")))))
            assertTrue(kind.name, matches(alias.copy(values = alias.values +
                value(kind, "").copy(binaryReference = "synthetic-media-reference"))))
        }
    }

    @Test fun groupsOnlyOrganizeAliasesButDoNotEnrichThem() {
        val alias = contact("first@passmail.net", "second@simplelogin.io").copy(firstName = "Generated")
        val grouped = alias.copy(values = alias.values + value(ContactValueKind.CATEGORY, "Family, Friends"))
        assertFalse(matches(grouped))
        assertTrue(matches(grouped.copy(values = grouped.values + value(ContactValueKind.NOTE, "Call on Friday"))))
        assertEquals("Family, Friends", grouped.values.last().value)
    }

    @Test fun onlyKnownTechnicalPropertiesAreIgnoredWithoutDiscardingThem() {
        val alias = contact("first@passmail.net")
        listOf("X-PM-SCHEME", "X-PM-SIGN", "X-PM-ENCRYPT", "X-PM-ENCRYPT-UNTRUSTED", "X-PM-MIMETYPE",
            "X-CONTAKO-AUTO-FN", "X-CONTAKO-EXPLICIT-FN").forEach { property ->
            val setting = value(ContactValueKind.UNKNOWN_VCARD_PROPERTY, "retained setting").copy(label = property.lowercase(java.util.Locale.ROOT))
            val withSetting = alias.copy(values = alias.values + setting)
            assertFalse(property, matches(withSetting))
            assertEquals(setting, withSetting.values.last())
            assertTrue(property, matches(withSetting.copy(values = withSetting.values + value(ContactValueKind.PHOTO, "").copy(binaryReference = "synthetic-photo"))))
            assertTrue(property, matches(alias.copy(values = alias.values + setting.copy(components = mapOf("extra" to "Useful data")))))
            assertTrue(property, matches(alias.copy(values = alias.values + setting.copy(binaryReference = "synthetic-extra-data"))))
        }
        listOf(null, "X-PM-USER-NOTE", "X-CONTAKO-CUSTOM", "X-PM-SIGN-EXTRA", "X-USEFUL").forEach { property ->
            assertTrue(property, matches(alias.copy(values = alias.values +
                value(ContactValueKind.UNKNOWN_VCARD_PROPERTY, "Useful data").copy(label = property))))
        }
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
        val enriched = alias.copy(displayName = "Useful name", values = alias.values + value(ContactValueKind.NOTE, "Useful note"))
        assertTrue(matches(enriched, filter.copy(withoutPhone = true)))
        assertFalse(matches(enriched, filter.copy(withoutEmail = true)))
        assertEquals(setOf("INVALID_EMAIL"), alias.actionRequiredReasons)
        assertEquals(2L, alias.pendingMutationRevision)
    }

    private fun matches(contact: CanonicalContact, criteria: DirectoryFilter = filter) =
        ContactDirectoryPolicy.matches(contact, criteria, null, 0)
    private fun contact(vararg emails: String) = CanonicalContact("synthetic", "alias-fixture",
        values = emails.mapIndexed { index, email -> value(ContactValueKind.EMAIL, email).copy(id = "email-$index", order = index) })
    private fun value(kind: ContactValueKind, text: String) = ContactValue("synthetic-field", kind, text, order = 0)
}
