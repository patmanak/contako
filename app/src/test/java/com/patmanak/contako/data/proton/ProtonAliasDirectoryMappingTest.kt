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
