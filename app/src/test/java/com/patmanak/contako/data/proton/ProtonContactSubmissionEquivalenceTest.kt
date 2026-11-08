package com.patmanak.contako.data.proton

import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValueKind
import me.proton.core.contact.domain.entity.Contact
import me.proton.core.contact.domain.entity.ContactCardType
import me.proton.core.contact.domain.entity.ContactEmail
import me.proton.core.contact.domain.entity.ContactEmailId
import me.proton.core.contact.domain.entity.ContactId
import me.proton.core.domain.entity.UserId
import org.junit.Assert.*
import org.junit.Test

/** Real codec roundtrips, not equality of a copied canonical fixture. No crypto/network is simulated as verified. */
class ProtonContactSubmissionEquivalenceTest {
    private val codec = ProtonContactVCardCodec()

    @Test fun encodedThenDecodedSubmissionRetainsOpaquePrivateDataRepeatedEmailsAndPreferences() {
        val local = localFixture()
        val encoded = codec.encode(local)
        val readback = decode(encoded)
        assertNotEquals(local.id, readback.id)
        assertEquals(UID, readback.remoteVCardUid)
        assertEquals(listOf(EMAIL, EMAIL), readback.valuesOf(ContactValueKind.EMAIL).map { it.value })
        assertEquals(2, readback.valuesOf(ContactValueKind.EMAIL).map { it.metadata[PROTON_EMAIL_ID_KEY] }.distinct().size)
        assertEquals(local.valuesOf(ContactValueKind.EMAIL).map { it.isPrimary },
            readback.valuesOf(ContactValueKind.EMAIL).map { it.isPrimary })
        assertEquals(NOTE, readback.valuesOf(ContactValueKind.NOTE).single().value)
        assertTrue(local.preservationEnvelope!!.rawProperties.keys.any { it.startsWith("proton-card-") })
        assertTrue(readback.preservationEnvelope!!.rawProperties.keys.any { it.startsWith("proton-card-") })
        assertTrue(encoded.encryptedPrivate.contains(OPAQUE))
        assertTrue(codec.encode(readback).encryptedPrivate.contains(OPAQUE))
        assertTrue("A real readback of the same submitted content must not create a conflict",
            equivalentContactSubmission(local, readback))
    }

    @Test fun aPrivateOnlyNoteChangeIsNotAnAcknowledgementOfTheLocalSubmission() {
        val local = localFixture()
        val encoded = codec.encode(local)
        assertTrue(encoded.encryptedPrivate.contains(NOTE))
        val changed = decode(encoded.copy(encryptedPrivate = encoded.encryptedPrivate.replace(NOTE, "Different private note ë ß 東京")))
        assertEquals(local.displayName, changed.displayName)
        assertFalse(equivalentContactSubmission(local, changed))
    }

    @Test fun losingAnOpaquePropertyDoesNotConvergeEvenWhenAllVisibleStandardFieldsMatch() {
        val local = localFixture()
        val encoded = codec.encode(local)
        val withoutOpaque = encoded.encryptedPrivate.lineSequence()
            .filterNot { it.startsWith("X-SYNTHETIC-OPAQUE;") }.joinToString("\r\n")
        assertFalse(withoutOpaque.contains(OPAQUE))
        val changed = decode(encoded.copy(encryptedPrivate = withoutOpaque))
        assertEquals(NOTE, changed.valuesOf(ContactValueKind.NOTE).single().value)
        assertEquals(local.displayName, changed.displayName)
        assertFalse(equivalentContactSubmission(local, changed))
    }

    @Test fun sameUidAndTextDoNotHideARemovedRepeatedEmailOccurrence() {
        val local = localFixture()
        val encoded = codec.encode(local)
        var emails = 0
        val fewer = encoded.signed.lineSequence().filterNot { line ->
            if (line.substringBefore(':').contains("EMAIL", ignoreCase = true)) { emails++; emails == 2 } else false
        }.joinToString("\r\n")
        assertEquals(2, emails)
        val changed = decode(encoded.copy(signed = fewer), emailCount = 1)
        assertEquals(1, changed.valuesOf(ContactValueKind.EMAIL).size)
        assertFalse(equivalentContactSubmission(local, changed))
    }

    @Test fun uidOwnerDeletionAndPreferenceChangesRemainSignificant() {
        val local = localFixture()
        val remote = decode(codec.encode(local))
        assertFalse(equivalentContactSubmission(local, remote.copy(remoteVCardUid = "other-uid")))
        assertFalse(equivalentContactSubmission(local, remote.copy(accountId = "foreign-owner")))
        assertFalse(equivalentContactSubmission(local, remote.copy(isDeleted = true)))
        val preferred = remote.valuesOf(ContactValueKind.EMAIL).first()
        val other = remote.valuesOf(ContactValueKind.EMAIL).last()
        val changed = remote.copy(values = remote.values.map { value -> when (value.id) {
            preferred.id -> value.copy(isPrimary = false, metadata = value.metadata + ("vcardPref" to "2"))
            other.id -> value.copy(isPrimary = true, metadata = value.metadata + ("vcardPref" to "1"))
            else -> value
        } })
        assertFalse(equivalentContactSubmission(local, changed))
    }

    private fun localFixture(): CanonicalContact = codec.decode(ACCOUNT, dto("seed", "seed-email"), listOf(
        ProtonPlainContactCard(ContactCardType.Signed, card("FN:Zoë Straße\r\n" +
            "ITEM1.EMAIL;TYPE=home;PREF=1:$EMAIL\r\nITEM2.EMAIL;TYPE=work;PREF=2:$EMAIL")),
        ProtonPlainContactCard(ContactCardType.EncryptedAndSigned, card("N:Straße;Zoë;;;\r\n" +
            "TEL;TYPE=cell:+12025550124\r\nNOTE:$NOTE\r\n$OPAQUE")),
    )).copy(id = "local-submission", remoteContactId = null, remoteVersion = null,
        revision = 3, pendingMutationRevision = 3)

    private fun decode(encoded: ProtonPreparedVCard, emailCount: Int = 2) = codec.decode(ACCOUNT,
        dto("delivered-contact", "delivered-email", emailCount), listOf(
            ProtonPlainContactCard(ContactCardType.EncryptedAndSigned, encoded.encryptedPrivate),
            ProtonPlainContactCard(ContactCardType.Signed, encoded.signed),
            ProtonPlainContactCard(ContactCardType.ClearText, encoded.clear),
        ))

    private fun dto(id: String, emailPrefix: String, count: Int = 2): Contact {
        val contactId = ContactId(id)
        return Contact(USER, contactId, "Zoë Straße", (0 until count).map { index -> ContactEmail(
            userId = USER, id = ContactEmailId("$emailPrefix-$index"), name = "Zoë Straße", email = EMAIL,
            defaults = 1, order = index, contactId = contactId, canonicalEmail = null,
            labelIds = emptyList(), isProton = false, lastUsedTime = 0,
        ) })
    }

    private fun card(properties: String) = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:$UID\r\n$properties\r\nEND:VCARD\r\n"

    companion object {
        private const val ACCOUNT = "synthetic-submission-owner"
        private val USER = UserId("synthetic-submission-user")
        private const val UID = "synthetic-stable-submission-uid"
        private const val EMAIL = "same-synthetic@example.test"
        private const val NOTE = "Private note ë ß 東京"
        private const val OPAQUE = "X-SYNTHETIC-OPAQUE;X-KEEP=one:preserved ë ß"
    }
}
