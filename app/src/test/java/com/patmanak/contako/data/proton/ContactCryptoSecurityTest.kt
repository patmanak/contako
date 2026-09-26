package com.patmanak.contako.data.proton

import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import java.util.Base64
import kotlin.random.Random
import kotlin.system.measureTimeMillis
import me.proton.core.contact.domain.entity.Contact
import me.proton.core.contact.domain.entity.ContactCardType
import me.proton.core.contact.domain.entity.ContactId
import me.proton.core.domain.entity.UserId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactCryptoSecurityTest {
    @Test
    fun `08-T05 deterministic malformed corpus and seeded property cases fail closed within bounds`() {
        val valid = card("UID:fixture\r\nFN:Fixture")
        val invalid = listOf(
            "",
            valid.substringBefore("END:VCARD"),
            valid + "TRAILING",
            valid + valid,
            valid.replace("END:VCARD", "BEGIN:VCARD\r\nEND:VCARD\r\nEND:VCARD"),
            valid.replace("UID:fixture", "VERSION:4.0\r\nUID:fixture"),
            valid.replace("FN:Fixture", "FN:${"x".repeat(10 * 1_024 * 1_024)}"),
        )
        val seeded = Random(FUZZ_SEED)
        val elapsed = measureTimeMillis {
            repeat(FUZZ_CASES) {
                val bytes = ByteArray(seeded.nextInt(0, 2_048)).also { seeded.nextBytes(it) }
                val candidate = bytes.toString(Charsets.UTF_8)
                assertTrue(runCatching { parseSingleCompleteVCard(candidate, 4_096) }.isFailure)
            }
            invalid.forEachIndexed { index, candidate ->
                assertTrue("MALFORMED_CORPUS_ACCEPTED_$index", runCatching {
                    parseSingleCompleteVCard(candidate)
                }.isFailure)
            }
        }
        assertTrue("BOUNDED_CORPUS_TIME", elapsed < MAX_CORPUS_MILLIS)
        assertTrue(parseSingleCompleteVCard(valid).write().contains("UID:fixture"))
    }

    @Test
    fun `08-T21 public KEY encodings pass and private malformed oversized variants never reach sinks`() {
        val public = "-----BEGIN PGP PUBLIC KEY BLOCK-----\nfixture-public\n-----END PGP PUBLIC KEY BLOCK-----"
        val validator = ProtonContactFieldValidator(PublicKeyMaterialInspector { it == public })
        val publicData = "data:application/pgp-keys;base64," +
            Base64.getEncoder().encodeToString(public.toByteArray())
        val positives = listOf(public, publicData, "https://keys.example.test/public.asc")
        positives.forEach { validator.validate(contactWithKey(it)) }

        val negatives = listOf(
            public.replace("PUBLIC", "PRIVATE"),
            "-----BEGIN ENCRYPTED PRIVATE KEY-----",
            "data:application/pgp-keys;base64,not-canonical===",
            "data:text/plain;base64," + Base64.getEncoder().encodeToString(public.toByteArray()),
            "data:application/pgp-keys;base64," +
                Base64.getEncoder().encodeToString(ByteArray(1 * 1_024 * 1_024 + 1)),
        )
        val sinks = MutationSinks()
        negatives.forEach { candidate ->
            if (runCatching { validator.validate(contactWithKey(candidate)) }.isSuccess) sinks.commitAll()
        }
        assertEquals(MutationSinks(), sinks)
    }

    @Test
    fun `08-T03 T05 T21 malformed multicard duplicate recursive and KEY payloads have zero mutation oracle`() {
        val codec = ProtonContactVCardCodec(
            ProtonContactFieldValidator(PublicKeyMaterialInspector { true }),
        )
        val valid = ProtonPlainContactCard(ContactCardType.Signed, card("UID:fixture\r\nFN:Fixture"))
        val corpus = listOf(
            listOf(valid, valid),
            List(17) { valid.copy(vCard = card("UID:fixture-$it")) },
            listOf(valid.copy(vCard = card("UID:fixture\r\nBEGIN:VCARD\r\nEND:VCARD"))),
            listOf(valid.copy(vCard = card("UID:fixture\r\nKEY:-----BEGIN PGP PRIVATE KEY BLOCK-----"))),
            listOf(valid.copy(vCard = card("UID:one")), valid.copy(vCard = card("UID:two"))),
        )
        val sinks = MutationSinks()
        corpus.forEach { cards ->
            if (runCatching { codec.decode("primary", remoteContact(), cards) }.isSuccess) sinks.commitAll()
        }
        assertEquals(MutationSinks(), sinks)

        val accepted = codec.decode(
            "primary",
            remoteContact(),
            listOf(valid.copy(vCard = card("UID:fixture\r\nKEY:$publicData"))),
        )
        assertEquals(1, accepted.valuesOf(ContactValueKind.PUBLIC_KEY).size)
    }

    private data class MutationSinks(
        var canonicalCommits: Int = 0,
        var providerProjections: Int = 0,
        var outboxUploads: Int = 0,
    ) {
        fun commitAll() {
            canonicalCommits++
            providerProjections++
            outboxUploads++
        }
    }

    private fun contactWithKey(value: String) = CanonicalContact(
        accountId = "primary",
        id = "fixture",
        values = listOf(ContactValue("key", ContactValueKind.PUBLIC_KEY, value, order = 0)),
    )

    private fun remoteContact() = Contact(
        userId = UserId("fixture-user"),
        id = ContactId("fixture-contact"),
        name = "Fixture",
        contactEmails = emptyList(),
    )

    private fun card(body: String) = "BEGIN:VCARD\r\nVERSION:4.0\r\n$body\r\nEND:VCARD\r\n"

    private val publicData: String
        get() = "data:application/pgp-keys;base64," + Base64.getEncoder().encodeToString(
            "-----BEGIN PGP PUBLIC KEY BLOCK-----\nfixture-public\n-----END PGP PUBLIC KEY BLOCK-----".toByteArray(),
        )

    private companion object {
        const val FUZZ_SEED = 0x08C0FFEE
        const val FUZZ_CASES = 256
        const val MAX_CORPUS_MILLIS = 5_000L
    }
}
