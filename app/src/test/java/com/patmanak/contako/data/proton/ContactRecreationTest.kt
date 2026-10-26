package com.patmanak.contako.data.proton

import com.patmanak.contako.domain.model.*
import com.patmanak.contako.domain.repository.ContactGroupAssignment
import me.proton.core.contact.domain.entity.*
import me.proton.core.domain.entity.UserId
import org.junit.Assert.*
import org.junit.Test

class ContactRecreationTest {
    private val codec = ProtonContactVCardCodec()
    private val photo = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    private fun card(body: String) = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:retired-uid\r\n$body\r\nEND:VCARD\r\n"
    private fun imported(vararg cards: ProtonPlainContactCard) = codec.decode("owner",
        Contact(userId = UserId("synthetic-user"), id = ContactId("retired-remote"), name = "Fixture", contactEmails = emptyList()), cards.toList())

    @Test fun newUidAndWireIdentityWithOpaqueOccurrencesAndPhotoPreserved() {
        val extension = "item9.X-SYNTHETIC;X-KEEP=fixture:repeat"
        val source = imported(ProtonPlainContactCard(ContactCardType.EncryptedAndSigned,
            card("FN:Fixture\r\nNOTE:Local edit\r\n$extension\r\n$extension\r\nPHOTO:$photo")))
        val destination = recontextualizeContactForRecreation(source, "new-local-id", emptyList(), emptySet())
        assertNull(destination.remoteContactId)
        assertNull(destination.remoteVersion)
        assertNull(destination.remoteVCardUid)
        assertEquals(source.preservationEnvelope, destination.preservationEnvelope)
        assertEquals(source.values.map { it.id }, destination.values.map { it.id })
        val encoded = codec.encode(destination)
        assertTrue(encoded.signed.contains("UID:new-local-id"))
        assertFalse((encoded.signed + encoded.clear + encoded.encryptedPrivate).contains("UID:retired-uid"))
        assertEquals(2, Regex("X-SYNTHETIC").findAll(encoded.encryptedPrivate).count())
        assertTrue(encoded.encryptedPrivate.contains("PHOTO"))
        assertEquals(source.valuesOf(ContactValueKind.PHOTO).single().value, destination.valuesOf(ContactValueKind.PHOTO).single().value)
    }

    @Test fun exactClearGroupIsDeferredAndOldEmailTokensCannotReachDestination() {
        val source = imported(
            ProtonPlainContactCard(ContactCardType.Signed, card("FN:Fixture\r\nITEM1.EMAIL:one@example.test")),
            ProtonPlainContactCard(ContactCardType.ClearText, card("ITEM1.CATEGORIES:Fixture group")))
        val email = source.valuesOf(ContactValueKind.EMAIL).single()
        val decorated = source.copy(values = source.values.map { if (it.id == email.id) it.copy(metadata = it.metadata +
            mapOf(PROTON_EMAIL_ID_KEY to "retired-email", PROTON_GROUP_IDS_KEY to "retired-group")) else it })
        val destination = recontextualizeContactForRecreation(decorated, "new", listOf(RecreationGroup("group", "Fixture group", "remote-group")),
            setOf(ContactGroupAssignment("group", email.id)))
        assertTrue(destination.valuesOf(ContactValueKind.CATEGORY).isEmpty())
        assertFalse(destination.valuesOf(ContactValueKind.EMAIL).single().metadata.containsKey(PROTON_EMAIL_ID_KEY))
        assertFalse(destination.valuesOf(ContactValueKind.EMAIL).single().metadata.containsKey(PROTON_GROUP_IDS_KEY))
        assertFalse(codec.encode(destination).clear.contains("CATEGORIES"))
        assertTrue(source.valuesOf(ContactValueKind.CATEGORY).isNotEmpty())
    }

    @Test fun unresolvedCategoriesAndDivergentRepeatedAddressGroupsBlockWithoutChangingSource() {
        val source = imported(ProtonPlainContactCard(ContactCardType.Signed, card("FN:Fixture\r\nITEM1.EMAIL:one@example.test")),
            ProtonPlainContactCard(ContactCardType.ClearText, card("ITEM9.CATEGORIES:Missing")))
        assertThrows(IllegalArgumentException::class.java) { recontextualizeContactForRecreation(source, "new", emptyList(), emptySet()) }
        val repeated = CanonicalContact("owner", "old", displayName = "Fixture", values = listOf(
            ContactValue("a", ContactValueKind.EMAIL, "same@example.test", order = 0),
            ContactValue("b", ContactValueKind.EMAIL, "same@example.test", order = 1)))
        assertThrows(IllegalArgumentException::class.java) { recontextualizeContactForRecreation(repeated, "new",
            listOf(RecreationGroup("group", "Fixture group", "remote-group")), setOf(ContactGroupAssignment("group", "a"))) }
        assertEquals(1, source.valuesOf(ContactValueKind.CATEGORY).size)
        assertEquals(2, repeated.values.size)
    }
}
