package com.patmanak.contako.data.proton

import com.patmanak.contako.data.gateway.GatewayContactHydrationCategory
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import me.proton.core.contact.domain.entity.Contact
import me.proton.core.contact.domain.entity.ContactCardType
import me.proton.core.contact.domain.entity.ContactId
import me.proton.core.domain.entity.UserId
import org.junit.Assert.*
import org.junit.Test

class ProtonContactReplacementCharacterTest {
    private val codec = ProtonContactVCardCodec()
    private val remote = Contact(UserId("synthetic-user"), ContactId("synthetic-contact"), "Fixture", emptyList())
    private fun card(lines: String) = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:synthetic-unicode\r\n$lines\r\nEND:VCARD\r\n"

    @Test fun importedReplacementSharpSAndQuotesSurviveUnrelatedEditAndRoundTrip() {
        val display = "Straß\uFFFD \"Example\""
        val decoded = codec.decode("synthetic-account", remote, listOf(
            ProtonPlainContactCard(ContactCardType.Signed, card("FN:$display")),
            ProtonPlainContactCard(ContactCardType.EncryptedAndSigned, card(
                "N:Gro\uFFFD;Straße;;;\r\nADR;TYPE=WORK:;;Beispielstra\uFFFDe 7;Musterstadt;;12345;DE\r\nNOTE:Größe \uFFFD",
            )),
        ))
        assertEquals(display, decoded.displayName)
        assertEquals("Straße", decoded.firstName)
        assertEquals("Gro\uFFFD", decoded.lastName)
        val note = decoded.values.single { it.kind == ContactValueKind.NOTE }
        assertEquals("Größe \uFFFD", note.value)
        val edited = decoded.copy(values = decoded.values.map {
            if (it.id == note.id) it.copy(value = "Edited \uFFFD note") else it
        })
        val encoded = codec.encode(edited)
        assertTrue(encoded.encryptedPrivate.contains("Beispielstra\uFFFDe 7"))
        val reread = codec.decode("synthetic-account", remote, listOf(
            ProtonPlainContactCard(ContactCardType.Signed, encoded.signed),
            ProtonPlainContactCard(ContactCardType.EncryptedAndSigned, encoded.encryptedPrivate),
        ))
        assertEquals(display, reread.displayName)
        assertEquals(decoded.firstName, reread.firstName)
        assertEquals(decoded.lastName, reread.lastName)
        assertEquals("Edited \uFFFD note", reread.values.single { it.kind == ContactValueKind.NOTE }.value)
        assertEquals(decoded.values.single { it.kind == ContactValueKind.POSTAL_ADDRESS }.components,
            reread.values.single { it.kind == ContactValueKind.POSTAL_ADDRESS }.components)
    }

    @Test fun newlyEditedReplacementTextDoesNotDependOnImportedPreservationExemption() {
        val validator = ProtonContactFieldValidator()
        val value = ContactValue("synthetic-note", ContactValueKind.NOTE, "Größe \uFFFD", order = 0)
        assertNull(validator.editableValueError(value))
        validator.validate(CanonicalContact("synthetic-account", "synthetic-contact", displayName = "\uFFFD \"ß\"",
            firstName = "Gro\uFFFD", lastName = "Straße", values = listOf(value)))
    }

    @Test fun directionalControlsRemainRejectedForImportAndNewEdits() {
        val failure = runCatching { codec.decode("synthetic-account", remote, listOf(
            ProtonPlainContactCard(ContactCardType.Signed, card("FN:Fixture\u202E")),
        )) }.exceptionOrNull()
        assertTrue(failure is ProtonVCardParseFailure)
        assertEquals(GatewayContactHydrationCategory.VCARD_PARSE_CHARACTERS, (failure as ProtonVCardParseFailure).category)
        assertNotNull(ProtonContactFieldValidator().editableValueError(
            ContactValue("synthetic-note", ContactValueKind.NOTE, "Fixture\u202E", order = 0),
        ))
    }
}
