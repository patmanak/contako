package com.patmanak.contako.data.proton

import com.patmanak.contako.domain.policy.ContactDirectoryPolicy
import com.patmanak.contako.domain.policy.DirectoryFilter
import me.proton.core.contact.domain.entity.Contact
import me.proton.core.contact.domain.entity.ContactCardType
import me.proton.core.contact.domain.entity.ContactId
import me.proton.core.domain.entity.UserId
import org.junit.Assert.*
import org.junit.Test

class ProtonAliasDirectoryMappingTest {
    @Test fun namedGroupedAliasesWithMessagingSettingsCanBeHiddenWithoutChangingAnyCard() {
        val codec = ProtonContactVCardCodec()
        fun card(body: String) = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:fixture\r\n$body\r\nEND:VCARD\r\n"
        val signed = card("FN:Élodie Straße\r\nitem1.EMAIL:synthetic@passmail.net\r\n" +
            "item1.X-PM-SIGN:true\r\nitem1.X-PM-ENCRYPT:false\r\n" +
            "item1.X-PM-ENCRYPT-UNTRUSTED:false\r\nitem1.X-PM-SCHEME:pgpmime\r\nitem1.X-PM-MIMETYPE:text/html")
        val clear = card("item1.CATEGORIES:Family,Friends")
        val privateCard = card("N:Straße;Élodie;;;\r\nNICKNAME:Élo")
        val remote = Contact(UserId("synthetic"), ContactId("fixture"), "Élodie Straße", emptyList())
        fun decode(extra: String = "") = codec.decode("synthetic", remote, listOf(
            ProtonPlainContactCard(ContactCardType.Signed, signed),
            ProtonPlainContactCard(ContactCardType.ClearText, clear),
            ProtonPlainContactCard(ContactCardType.EncryptedAndSigned, if (extra.isEmpty()) privateCard else
                card("N:Straße;Élodie;;;\r\nNICKNAME:Élo\r\n$extra"))))
        val imported = decode()
        val before = try {
            codec.encode(imported)
        } catch (failure: ProtonContactEncodingException) {
            throw AssertionError("Fixture encoding failed at ${failure.stage}/${failure.field}")
        }
        assertFalse(ContactDirectoryPolicy.matches(imported, DirectoryFilter(hideAliasOnly = true), null, 0))
        assertEquals("Élodie", imported.firstName)
        assertEquals("Straße", imported.lastName)
        assertEquals("Élodie Straße", imported.displayName)
        val after = codec.encode(imported)
        assertEquals(before.signed, after.signed)
        assertEquals(before.encryptedPrivate, after.encryptedPrivate)
        assertEquals(before.clear, after.clear)
        val envelope = requireNotNull(imported.preservationEnvelope)
        assertTrue(envelope.rawProperties.values.contains(signed))
        assertTrue(envelope.rawProperties.values.contains(privateCard))
        assertTrue(envelope.rawProperties.values.contains(clear))
        listOf("NOTE:Call on Friday", "TEL:+12025550142", "X-PM-USER-NOTE:Useful data",
            "X-CONTAKO-CUSTOM:Useful data").forEach { extra ->
            assertTrue(extra.substringBefore(':'), ContactDirectoryPolicy.matches(
                decode(extra), DirectoryFilter(hideAliasOnly = true), null, 0))
        }
    }

    @Test fun importedEmailFnAndEmptyNCanBeHiddenWithoutChangingPreservedCards() {
        val codec = ProtonContactVCardCodec()
        fun card(body: String) = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:fixture\r\n$body\r\nEND:VCARD\r\n"
        val signed = card("FN:synthetic@passmail.net\r\nEMAIL:synthetic@passmail.net")
        val emptyName = card("N:;;;;")
        val remote = Contact(UserId("synthetic"), ContactId("fixture"), "synthetic@passmail.net", emptyList())
        val imported = codec.decode("synthetic", remote, listOf(
            ProtonPlainContactCard(ContactCardType.Signed, signed),
            ProtonPlainContactCard(ContactCardType.EncryptedAndSigned, emptyName)))
        val before = codec.encode(imported)
        assertFalse(ContactDirectoryPolicy.matches(imported, DirectoryFilter(hideAliasOnly = true), null, 0))
        val after = codec.encode(imported)
        assertEquals(before.signed, after.signed)
        assertEquals(before.encryptedPrivate, after.encryptedPrivate)
        assertTrue(imported.preservationEnvelope!!.rawProperties.values.contains(emptyName))
        val opaque = codec.decode("synthetic", remote, listOf(
            ProtonPlainContactCard(ContactCardType.Signed, signed),
            ProtonPlainContactCard(ContactCardType.EncryptedAndSigned, card("X-USEFUL:Retain this opaque field"))))
        assertTrue(ContactDirectoryPolicy.matches(opaque, DirectoryFilter(hideAliasOnly = true), null, 0))
    }
}
