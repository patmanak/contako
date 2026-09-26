package com.patmanak.contako.data.proton

import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.model.PreservationEnvelope
import me.proton.core.contact.domain.entity.Contact
import me.proton.core.contact.domain.entity.ContactCardType
import me.proton.core.contact.domain.entity.ContactId
import me.proton.core.domain.entity.UserId
import org.junit.Assert.*
import org.junit.Test

class ProtonPendingPreservationTest {
    private val codec = ProtonContactVCardCodec()
    private val remote = Contact(UserId("test-user"), ContactId("test-contact"), "Fixture", emptyList())
    private fun card(body: String) = "BEGIN:VCARD\r\nVERSION:4.0\r\n$body\r\nEND:VCARD\r\n"
    private fun decode(signed: String, private: String) = codec.decode("test-account", remote, listOf(
        ProtonPlainContactCard(ContactCardType.Signed, card(signed)),
        ProtonPlainContactCard(ContactCardType.EncryptedAndSigned, card(private)),
    ))

    @Test fun pendingEmailRemovalKeepsOriginalDecorationsAndBothUnknownSourcesAcrossRetries() {
        val original = decode(
            "FN:Fixture\r\nUID:fixture\r\nITEM1.EMAIL;TYPE=home:first@example.test\r\nITEM2.EMAIL;TYPE=work;X-KEEP=second:second@example.test",
            "UID:fixture\r\nN:Fixture;;;;\r\nNOTE:preserved note\r\nX-LOCAL:canary\r\nX-REPEATED:twice\r\nX-REPEATED:twice",
        )
        val pending = original.copy(values = original.values.filterNot {
            it.kind == ContactValueKind.EMAIL && it.value == "first@example.test"
        }.map { if (it.kind == ContactValueKind.EMAIL) it.copy(value = "edited@example.test") else it })
        val received = decode(
            "UID:fixture\r\nITEM1.EMAIL;TYPE=work:edited@example.test\r\nFN:Fixture",
            "NOTE:remote earlier\r\nUID:fixture\r\nN:Fixture;;;;\r\nX-REMOTE:new\r\nX-REPEATED:twice",
        )
        // This is the actual failure mechanism: the same namespace now addresses other lines.
        val blind = PreservationEnvelope(original.preservationEnvelope!!.rawProperties + received.preservationEnvelope!!.rawProperties)
        assertThrows(IllegalArgumentException::class.java) { codec.encode(pending.copy(preservationEnvelope = blind)) }
        val merged = mergePendingProtonPreservation(original.preservationEnvelope, received.preservationEnvelope)!!
        val encoded = codec.encode(pending.copy(preservationEnvelope = merged))
        assertTrue(encoded.signed.contains("edited@example.test"))
        assertFalse(encoded.signed.contains("first@example.test"))
        assertTrue(encoded.signed.contains("X-KEEP=second"))
        assertTrue(encoded.encryptedPrivate.contains("NOTE:preserved note"))
        assertTrue(encoded.encryptedPrivate.contains("X-LOCAL:canary"))
        assertTrue(encoded.encryptedPrivate.contains("X-REMOTE:new"))
        assertEquals(2, encoded.encryptedPrivate.lineSequence().count { it == "X-REPEATED:twice" })
        var again = merged
        repeat(5) { again = mergePendingProtonPreservation(again, received.preservationEnvelope)!! }
        assertEquals(merged, again)
        assertEquals(encoded, codec.encode(pending.copy(preservationEnvelope = again)))
        assertFalse(encoded.signed.contains("pending-value-source"))
        assertFalse(encoded.encryptedPrivate.contains("pending-value-source"))
    }

    @Test fun pinnedSourcesDoNotPermitForgedValueReferences() {
        val contact = decode("FN:Fixture\r\nEMAIL:a@example.test", "N:Fixture;;;;\r\nNOTE:note")
        val merged = mergePendingProtonPreservation(contact.preservationEnvelope, contact.preservationEnvelope)
        val forged = contact.copy(preservationEnvelope = merged, values = contact.values.map {
            if (it.kind == ContactValueKind.EMAIL) it.copy(id = "forged-id") else it
        })
        assertThrows(IllegalArgumentException::class.java) { codec.encode(forged) }
    }

    @Test fun occurrenceUnionPreservesMultiplicityWithoutRetryGrowth() {
        assertEquals(listOf("a", "a", "b", "b"), mergePreservedPropertyOccurrences(listOf("a", "a", "b"), listOf("a", "b", "b")))
        val original = PreservationEnvelope(mapOf("proton-card-2-0" to "original"))
        assertEquals(original, mergePendingProtonPreservation(original, null))
        assertEquals(original, mergePendingProtonPreservation(null, original))
    }
}
