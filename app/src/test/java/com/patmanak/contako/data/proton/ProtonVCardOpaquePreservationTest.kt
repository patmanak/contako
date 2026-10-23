package com.patmanak.contako.data.proton

import com.patmanak.contako.domain.model.ContactValueKind
import me.proton.core.contact.domain.entity.Contact
import me.proton.core.contact.domain.entity.ContactCardType
import me.proton.core.contact.domain.entity.ContactId
import me.proton.core.domain.entity.UserId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtonVCardOpaquePreservationTest {
    private val codec = ProtonContactVCardCodec()
    private val remote = Contact(userId = UserId("fixture-user"), id = ContactId("opaque-fixture"),
        name = "Opaque fixture", contactEmails = emptyList())

    @Test
    fun phoneticExtensionAndItsParametersRemainInTheirSourceCardAfterNoteEdits() {
        val extension = "item8.X-PHONETIC-FIRST-NAME;LANGUAGE=de;X-KEEP=opaque:Änne\\, Chloë"
        val label = "item8.X-ABLABEL:Phonetic fixture"
        listOf(ContactCardType.ClearText, ContactCardType.Signed,
            ContactCardType.Encrypted, ContactCardType.EncryptedAndSigned).forEach { type ->
            val imported = codec.decode("fixture-account", remote, listOf(
                ProtonPlainContactCard(type, card("FN:Opaque fixture\r\n$extension\r\n$label")),
                ProtonPlainContactCard(ContactCardType.EncryptedAndSigned, card("NOTE:Before")),
            ))
            val phonetic = imported.valuesOf(ContactValueKind.UNKNOWN_VCARD_PROPERTY)
                .single { it.label == "X-PHONETIC-FIRST-NAME" }
            assertEquals("Änne, Chloë", phonetic.value)
            val edited = imported.copy(values = imported.values.map {
                if (it.kind == ContactValueKind.NOTE) it.copy(value = "After") else it
            })
            val encoded = codec.encode(edited)
            val sourceOutput = when (type) {
                ContactCardType.ClearText -> encoded.clear
                ContactCardType.Signed -> encoded.signed
                else -> encoded.encryptedPrivate
            }
            assertTrue("Extension source=$type", sourceOutput.contains(extension))
            assertTrue("Associated label source=$type", sourceOutput.contains(label))
            val reparsed = codec.decode("fixture-account", remote, listOf(
                ProtonPlainContactCard(ContactCardType.ClearText, encoded.clear),
                ProtonPlainContactCard(ContactCardType.Signed, encoded.signed),
                ProtonPlainContactCard(ContactCardType.EncryptedAndSigned, encoded.encryptedPrivate),
            ))
            assertEquals("After", reparsed.valuesOf(ContactValueKind.NOTE).single().value)
            assertEquals(phonetic.value, reparsed.valuesOf(ContactValueKind.UNKNOWN_VCARD_PROPERTY)
                .single { it.label == "X-PHONETIC-FIRST-NAME" }.value)
        }
    }

    @Test
    fun everyPropertyDecodedAsOpaqueSurvivesAnUnrelatedRebuildIncludingItsParameters() {
        // Includes known vendor extensions and unknown standard properties: the decoder's opaque
        // result, rather than a second list of property names, defines preservation acceptance.
        val properties = listOf(
            "X-PHONETIC-FIRST-NAME;X-KEEP=first:Chloë",
            "X-PHONETIC-LAST-NAME;X-KEEP=last:Groß",
            "X-PHONETIC-MIDDLE-NAME;X-KEEP=middle:Ö",
            "SORT-STRING;LANGUAGE=de:Groß, Chloë",
            "IMPP;X-KEEP=im:sip:fixture@example.test",
            "EXPERTISE;X-KEEP=skill:Debugging",
            "X-FIXTURE-UNKNOWN;X-KEEP=one:opaque",
        )
        val imported = codec.decode("fixture-account", remote, listOf(
            ProtonPlainContactCard(ContactCardType.Signed, card("FN:Opaque fixture")),
            ProtonPlainContactCard(ContactCardType.EncryptedAndSigned, card(properties.joinToString("\r\n"))),
        ))
        assertEquals(properties.size, imported.valuesOf(ContactValueKind.UNKNOWN_VCARD_PROPERTY).size)
        val encoded = codec.encode(imported.copy(displayName = "Changed fixture"))
        properties.forEach { assertTrue("Opaque property must survive: ${it.substringBefore(';')}",
            encoded.encryptedPrivate.contains(it)) }
    }

    private fun card(properties: String) =
        "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:opaque-fixture\r\n$properties\r\nEND:VCARD\r\n"
}
