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
            assertTrue(wire.signed.contains("X-CONTAKO-AUTO-FN:"))
            val read = decode(wire.signed, wire.encryptedPrivate)
            assertEquals("", read.displayName)
            assertEquals("", read.firstName)
            assertEquals("", read.lastName)
            assertEquals(value, read.resolvedDisplayName)
            val second = codec.encode(read)
            assertEquals(1, second.signed.lineSequence().count { it.startsWith("X-CONTAKO-AUTO-FN:") })
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
        assertFalse(explicit.signed.contains("X-CONTAKO-AUTO-FN"))
        assertEquals("Chosen name ß", decode(explicit.signed, explicit.encryptedPrivate).displayName)
    }

    @Test fun unauthenticatedOrMismatchingMarkersRemainOpaqueAndCannotEraseAnExplicitName() {
        val protected = card("FN:Explicit name\r\nX-CONTAKO-AUTO-FN:Different name")
        val read = decode(protected, card("ORG:Café"))
        assertEquals("Explicit name", read.displayName)
        assertTrue(codec.encode(read).signed.contains("X-CONTAKO-AUTO-FN:Different name"))
        val coincident = codec.encode(read.copy(displayName = "Different name"))
        assertTrue(coincident.signed.contains("X-CONTAKO-AUTO-FN:Different name"))
        assertTrue(coincident.signed.contains("X-CONTAKO-EXPLICIT-FN:Different name"))
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

    private fun decode(signed: String, privateCard: String) = codec.decode("synthetic", remote(), listOf(
        ProtonPlainContactCard(ContactCardType.Signed, signed), ProtonPlainContactCard(ContactCardType.EncryptedAndSigned, privateCard)))
    private fun remote() = Contact(UserId("synthetic-user"), ContactId("fixture"), "Remote name", emptyList())
    private fun card(properties: String) = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:fixture\r\n$properties\r\nEND:VCARD\r\n"
}
