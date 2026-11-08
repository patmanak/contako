package com.patmanak.contako.data.proton

import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.domain.model.*
import com.patmanak.contako.domain.policy.ContactDisplayNamePolicy
import com.patmanak.contako.domain.policy.ContactValidation
import me.proton.core.contact.domain.entity.*
import me.proton.core.domain.entity.UserId
import org.junit.Assert.*
import org.junit.Test

class GeneratedDisplayNameTest {
    private val codec = ProtonContactVCardCodec()

    @Test fun companyPhoneAndEmailFallbacksRoundTripWithoutInventingGivenOrFamilyName() {
        for ((kind, value) in listOf(ContactValueKind.ORGANIZATION to "Straße Café 東京",
                ContactValueKind.PHONE to "+49 30 1234", ContactValueKind.EMAIL to "zoe@example.test")) {
            val local = CanonicalContact("synthetic", "fixture", values = listOf(ContactValue("value", kind, value, order = 0)))
            assertTrue(ContactValidation.canCreate(local))
            val wire = codec.encode(local)
            assertTrue(wire.encryptedPrivate.contains("X-CONTAKO-AUTO-FN:"))
            assertTrue(wire.encryptedPrivate.contains("UID:fixture"))
            assertPublicPartition(wire)
            assertFalse(wire.encryptedPrivate.lineSequence().any { it.startsWith("N:") || it.startsWith("FN:") })
            val read = decode(wire.signed, wire.encryptedPrivate)
            assertEquals("", read.displayName)
            assertEquals("", read.firstName)
            assertEquals("", read.lastName)
            assertEquals(value, read.resolvedDisplayName)
            val second = codec.encode(read)
            assertEquals(1, second.encryptedPrivate.lineSequence().count { it.startsWith("X-CONTAKO-AUTO-FN:") })
            assertPublicPartition(second)
        }
    }

    @Test fun changedCompanyUpdatesAutoNameAndExplicitUserNameRemovesTheMarker() {
        val local = CanonicalContact("synthetic", "fixture", values = listOf(
            ContactValue("org", ContactValueKind.ORGANIZATION, "Café before", order = 0)))
        val first = codec.encode(local)
        val read = decode(first.signed, first.encryptedPrivate)
        val changed = read.copy(values = read.values.map { if (it.kind == ContactValueKind.ORGANIZATION) it.copy(value = "Café après") else it })
        val second = codec.encode(changed)
        assertTrue(second.signed.contains("FN;PREF=1:Café après"))
        val explicit = codec.encode(changed.copy(displayName = "Chosen name ß"))
        assertFalse(explicit.encryptedPrivate.contains("X-CONTAKO-AUTO-FN"))
        assertEquals("Chosen name ß", decode(explicit.signed, explicit.encryptedPrivate).displayName)
    }

    @Test fun unauthenticatedOrMismatchingMarkersRemainOpaqueAndCannotEraseAnExplicitName() {
        val protected = card("FN:Explicit name\r\nX-CONTAKO-AUTO-FN:Different name")
        val read = decode(protected, card("ORG:Café"))
        assertEquals("Explicit name", read.displayName)
        assertTrue(codec.encode(read).signed.contains("X-CONTAKO-AUTO-FN:Different name"))
        val coincident = codec.encode(read.copy(displayName = "Different name"))
        assertTrue(coincident.signed.contains("X-CONTAKO-AUTO-FN:Different name"))
        assertTrue(coincident.encryptedPrivate.contains("X-CONTAKO-EXPLICIT-FN:Different name"))
        assertEquals("Different name", decode(coincident.signed, coincident.encryptedPrivate).displayName)
        val clear = codec.decode("synthetic", remote(), listOf(
            ProtonPlainContactCard(ContactCardType.Signed, card("FN:Explicit name")),
            ProtonPlainContactCard(ContactCardType.ClearText, card("X-CONTAKO-AUTO-FN:Explicit name")),
            ProtonPlainContactCard(ContactCardType.EncryptedAndSigned, card("ORG:Café")),
        ))
        assertEquals("Explicit name", clear.displayName)
        assertTrue(codec.encode(clear).clear.contains("X-CONTAKO-AUTO-FN:Explicit name"))
    }

    @Test fun fallbackPriorityPreservesExplicitStructuredAndPhoneticValues() {
        val values = listOf(ContactValue("nick", ContactValueKind.NICKNAME, "Nickname", order = 0),
            ContactValue("phonetic", ContactValueKind.PHONETIC_NAME, "Given", order = 0,
                components = mapOf("given" to "Given", "family" to "Family")),
            ContactValue("org", ContactValueKind.ORGANIZATION, "Company", order = 0),
            ContactValue("tel", ContactValueKind.PHONE, "+12025550123", order = 0))
        val contact = CanonicalContact("synthetic", "fixture", values = values)
        assertEquals("Given Family", ContactDisplayNamePolicy.resolve(contact))
        assertEquals("Named", ContactDisplayNamePolicy.resolve(contact.copy(firstName = "Named")))
        assertEquals("Explicit", ContactDisplayNamePolicy.resolve(contact.copy(displayName = "Explicit")))
    }

    @Test fun genericInternalArgumentErrorsDoNotAccuseContactValidation() {
        assertEquals(GatewayFailureCategory.UNKNOWN, IllegalArgumentException("private argument").toContactGatewayFailure())
        assertEquals(GatewayFailureCategory.VALIDATION_REJECTED,
            ProtonContactValueValidationException(ContactValueKind.NOTE).toContactGatewayFailure())
    }

    @Test fun legacyAuthenticatedMarkersMigratePrivatelyAndExplicitMarkersWinAcrossCards() {
        val legacy = decode(card("FN:Café\r\nX-CONTAKO-AUTO-FN:Café"), card("ORG:Café"))
        assertEquals("", legacy.displayName)
        val migrated = codec.encode(legacy)
        assertFalse(migrated.signed.contains("X-CONTAKO-"))
        assertTrue(migrated.encryptedPrivate.contains("X-CONTAKO-AUTO-FN:Café"))
        assertPublicPartition(migrated)
        val explicit = decode(card("FN:Café\r\nX-CONTAKO-EXPLICIT-FN:Café"),
            card("ORG:Café\r\nX-CONTAKO-AUTO-FN:Café"))
        assertEquals("Café", explicit.displayName)
        assertEquals("Café", decode(codec.encode(explicit).signed, codec.encode(explicit).encryptedPrivate).displayName)
    }

    @Test fun privateMarkersNeedAuthenticatedPublicNameAndMatchingUidAndCannotMaskAWebNameEdit() {
        val privateCard = card("ORG:Café\r\nX-CONTAKO-AUTO-FN:Café")
        assertEquals("", decode(card("FN:Café"), privateCard).displayName)
        assertEquals("Web chosen name", decode(card("FN:Web chosen name"), privateCard).displayName)
        val unsigned = codec.decode("synthetic", remote(), listOf(
            ProtonPlainContactCard(ContactCardType.Signed, card("FN:Café")),
            ProtonPlainContactCard(ContactCardType.Encrypted, privateCard)))
        assertEquals("Café", unsigned.displayName)
        assertTrue(codec.encode(unsigned).encryptedPrivate.contains("X-CONTAKO-AUTO-FN:Café"))
        val noSigned = codec.decode("synthetic", remote(), listOf(
            ProtonPlainContactCard(ContactCardType.ClearText, card("FN:Café")),
            ProtonPlainContactCard(ContactCardType.EncryptedAndSigned, privateCard)))
        assertEquals("Café", noSigned.displayName)
        val noPrivateUid = decode(card("FN:Café"), privateCard.replace("UID:fixture\r\n", ""))
        assertEquals("Café", noPrivateUid.displayName)
        val mismatched = runCatching { decode(card("FN:Café"), privateCard.replace("UID:fixture", "UID:other")) }
        assertTrue(mismatched.exceptionOrNull() is ProtonInconsistentContactIdentity)
        assertEquals("Café", decode(card("FN:Café"), card("ORG:Café\r\nX-CONTAKO-AUTO-FN;X-UNKNOWN=1:Café")).displayName)
    }

    @Test fun importedEmptyStructuredNameSurvivesWhileNewGeneratedNamesDoNotInventOne() {
        val imported = decode(card("FN:Explicit"), card("N:;;;;\r\nX-UNCHANGED:opaque"))
        val encoded = codec.encode(imported)
        assertTrue(encoded.encryptedPrivate.lineSequence().any { it.trimEnd('\r') == "N:;;;;" })
        assertTrue(encoded.encryptedPrivate.contains("X-UNCHANGED:opaque"))
        assertFalse(encoded.encryptedPrivate.contains("UID:"))
    }

    /** Independent Proton WebClients SIGNED_FIELDS contract, not an encode/decode self-oracle. */
    private fun assertPublicPartition(card: ProtonPreparedVCard) {
        val allowed = setOf(
            "BEGIN", "END", "VERSION", "PRODID", "FN", "UID", "EMAIL", "KEY",
            "X-PM-MIMETYPE", "X-PM-ENCRYPT", "X-PM-ENCRYPT-UNTRUSTED", "X-PM-SIGN",
            "X-PM-SCHEME", "X-PM-TLS",
        )
        card.signed.lineSequence().filter { it.isNotBlank() && !it.startsWith(' ') }.forEach { line ->
            val property = line.substringBefore(':').substringBefore(';').substringAfterLast('.').uppercase()
            assertTrue("SIGNED_PUBLIC_FIELD_WHITELIST", property in allowed)
        }
        assertFalse(card.signed.contains("X-CONTAKO-"))
        assertFalse(card.clear.contains("X-CONTAKO-"))
    }

    private fun decode(signed: String, privateCard: String) = codec.decode("synthetic", remote(), listOf(
        ProtonPlainContactCard(ContactCardType.Signed, signed), ProtonPlainContactCard(ContactCardType.EncryptedAndSigned, privateCard)))
    private fun remote() = Contact(UserId("synthetic-user"), ContactId("fixture"), "Remote name", emptyList())
    private fun card(properties: String) = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:fixture\r\n$properties\r\nEND:VCARD\r\n"
}
