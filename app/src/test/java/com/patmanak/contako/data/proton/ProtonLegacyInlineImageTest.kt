package com.patmanak.contako.data.proton

import com.patmanak.contako.data.android.provider.CanonicalPhotoBinaryLoader
import com.patmanak.contako.domain.model.ContactValueKind
import me.proton.core.contact.domain.entity.Contact
import me.proton.core.contact.domain.entity.ContactCardType
import me.proton.core.contact.domain.entity.ContactId
import me.proton.core.domain.entity.UserId
import org.junit.Assert.*
import org.junit.Test

class ProtonLegacyInlineImageTest {
    private val codec = ProtonContactVCardCodec()
    private val remote = Contact(UserId("fixture-user"), ContactId("fixture-contact"), "Fixture", emptyList())
    private val png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    private fun decode(privateFields: String) = codec.decode("fixture-account", remote, listOf(
        ProtonPlainContactCard(ContactCardType.Signed, "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Fixture\r\nUID:fixture\r\nEND:VCARD"),
        ProtonPlainContactCard(ContactCardType.EncryptedAndSigned,
            "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:fixture\r\n$privateFields\r\nEND:VCARD"),
    ))

    @Test fun importedBinaryPhotosAndLogosBecomeOfflineImagesAndRoundTripWithoutLegacyEncoding() {
        val contact = decode("PHOTO;ENCODING=b;TYPE=PNG;VALUE=binary;PREF=1;X-KEEP=fixture:$png\r\n" +
            "LOGO;ENCODING=BASE64;MEDIATYPE=image/png:$png\r\nNOTE:before")
        val photo = contact.values.single { it.kind == ContactValueKind.PHOTO }
        val logo = contact.values.single { it.kind == ContactValueKind.LOGO }
        assertEquals("data:image/png;base64,$png", photo.value)
        assertEquals(photo.value, logo.value)
        assertArrayEquals(java.util.Base64.getDecoder().decode(png), CanonicalPhotoBinaryLoader.load(photo.value))
        val edited = contact.copy(values = contact.values.map {
            if (it.kind == ContactValueKind.NOTE) it.copy(value = "after") else it
        })
        val encoded = codec.encode(edited).encryptedPrivate
        assertTrue(encoded.contains("X-KEEP=fixture"))
        assertTrue(encoded.contains("NOTE:after"))
        assertFalse(encoded.contains("ENCODING="))
        assertFalse(encoded.contains("VALUE=binary"))
        assertFalse(encoded.contains("TYPE=PNG"))
        val reread = decode(encoded.lineSequence().filterNot {
            it.startsWith("BEGIN:") || it.startsWith("END:") || it.startsWith("VERSION:") || it.startsWith("UID:")
        }.joinToString("\r\n"))
        assertEquals(photo.value, reread.values.single { it.kind == ContactValueKind.PHOTO }.value)
    }

    @Test fun replacingImportedBinaryImageDoesNotRetainItsOldEncodingOrMediaType() {
        val contact = decode("PHOTO;ENCODING=\"b\";TYPE=PNG;MEDIATYPE=image/png;VALUE=binary:$png")
        val replacement = "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7"
        val encoded = codec.encode(contact.copy(values = contact.values.map {
            if (it.kind == ContactValueKind.PHOTO) it.copy(value = replacement) else it
        })).encryptedPrivate
        assertTrue(encoded.contains("VALUE=uri;MEDIATYPE=image/gif"))
        assertTrue(encoded.contains(replacement))
        assertFalse(encoded.contains("ENCODING="))
        assertFalse(encoded.contains("TYPE=PNG"))
    }

    @Test fun malformedUnknownConflictingAndRemoteFormsArePreservedWithoutGuessing() {
        listOf(
            "ENCODING=b;TYPE=PNG;VALUE=binary" to "invalid!",
            "ENCODING=b;TYPE=UNKNOWN;VALUE=binary" to png,
            "ENCODING=b;TYPE=PNG;MEDIATYPE=image/jpeg;VALUE=binary" to png,
            "ENCODING=quoted-printable;ENCODING=b;TYPE=PNG;VALUE=binary" to png,
            "ENCODING=b;TYPE=PNG;MEDIATYPE=image/jpeg;MEDIATYPE=image/png;VALUE=binary" to png,
            "TYPE=PNG;VALUE=binary" to png,
            "ENCODING=b;TYPE=PNG;VALUE=uri" to "https://example.test/photo.png",
        ).forEach { (parameters, payload) ->
            val contact = decode("PHOTO;$parameters:$payload")
            assertEquals(payload, contact.values.single { it.kind == ContactValueKind.PHOTO }.value)
            assertTrue(codec.encode(contact).encryptedPrivate.contains("PHOTO;$parameters"))
        }
    }
}
