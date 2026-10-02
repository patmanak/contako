package com.patmanak.contako.data.proton

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.Closeable
import kotlinx.coroutines.runBlocking
import me.proton.core.contact.domain.entity.ContactCard
import me.proton.core.contact.domain.entity.ContactCardType
import me.proton.core.crypto.android.context.AndroidCryptoContext
import me.proton.core.crypto.common.context.CryptoContext
import me.proton.core.crypto.common.keystore.EncryptedByteArray
import me.proton.core.crypto.common.keystore.PlainByteArray
import me.proton.core.crypto.common.keystore.use
import me.proton.core.domain.entity.UserId
import me.proton.core.key.domain.entity.key.PrivateKey
import me.proton.core.key.domain.entity.key.PrivateKeyRing
import me.proton.core.key.domain.entity.key.PublicKeyRing
import me.proton.core.key.domain.entity.keyholder.KeyHolderContext
import me.proton.core.key.domain.publicKey
import me.proton.core.key.domain.encryptText
import me.proton.core.key.domain.decryptText
import me.proton.core.key.domain.signData
import me.proton.core.key.domain.verifyData
import me.proton.core.key.domain.verifyText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProtonContactCardCryptoDeviceTest {
    @Test
    fun corruptedCompressedPacketNeverReturnsUnauthenticatedPlaintext() = runBlocking {
        val primary = EphemeralKeyMaterial.create("stream-integrity")
        try {
            val original = primary.compress(PREPARED_CARD.encryptedPrivate)
            val crypto = ProtonCoreContactCardCrypto(primary.provider())
            assertEquals(ContactCardType.Encrypted,
                crypto.decryptAndVerify(USER_ID, listOf(ContactCard.Encrypted(original, null))).single().type)
            val binary = com.proton.gopenpgp.crypto.Crypto.newPGPMessageFromArmored(original).binary
            try {
                binary[binary.lastIndex] = (binary.last().toInt() xor 1).toByte()
                val corrupted = com.proton.gopenpgp.crypto.Crypto.newPGPMessage(binary).armored
                assertVerificationFailure {
                    crypto.decryptAndVerify(USER_ID, listOf(ContactCard.Encrypted(corrupted, null)))
                }
            } finally { binary.fill(0) }
        } finally { primary.close() }
    }

    @Test
    fun boundedReaderKeepsCoreFallbackWhenTheFirstPrivateKeyCannotDecrypt() = runBlocking {
        val primary = EphemeralKeyMaterial.create("fallback-recipient")
        val unrelated = EphemeralKeyMaterial.create("fallback-first")
        try {
            val cards = ProtonCoreContactCardCrypto(primary.provider()).protect(USER_ID, PREPARED_CARD)
            val plain = ProtonCoreContactCardCrypto(primary.provider(firstKey = unrelated))
                .decryptAndVerify(USER_ID, cards)
            assertEquals(ContactCardType.EncryptedAndSigned, plain.first().type)
            assertTrue(plain.first().vCard.contains("+33102030405"))
        } finally { primary.close(); unrelated.close() }
    }

    @Test
    fun compressedPlaintextIsBoundedBeforeCanonicalParsingAndExactLimitRequiresRealEof() = runBlocking {
        val primary = EphemeralKeyMaterial.create("bounded-stream")
        try {
            val limit = 10 * 1_024 * 1_024
            val exact = primary.compress("A".repeat(limit))
            requireNotNull(primary.provider().acquire(USER_ID)).use { holder ->
                val text = decryptBoundedContactText(holder, exact, limit)
                assertEquals(limit, text.length)
                assertTrue(text.all { it == 'A' })
            }
            val excess = primary.compress("A".repeat(limit + 1))
            assertTrue(excess.toByteArray(Charsets.UTF_8).size < limit)
            requireNotNull(primary.provider().acquire(USER_ID)).use { holder ->
                try {
                    decryptBoundedContactText(holder, excess, limit)
                    fail("COMPRESSED_PLAINTEXT_LIMIT_REQUIRED")
                } catch (_: ProtonPlaintextBoundsExceeded) {
                    // No truncated plaintext is returned; Core fallback cannot bypass the bound.
                }
            }
            try {
                ProtonCoreContactCardCrypto(primary.provider()).decryptAndVerify(USER_ID,
                    listOf(ContactCard.Encrypted(excess, null)))
                fail("CANONICAL_PARTIAL_PLAINTEXT_FORBIDDEN")
            } catch (failure: ProtonHydrationMalformedResponse) {
                assertEquals(com.patmanak.contako.data.gateway.GatewayContactHydrationCategory.PLAINTEXT_BOUNDS,
                    failure.category)
            }
        } finally { primary.close() }
    }

    @Test
    fun outboundRichEncryptedCardSignatureVerifiesWithoutTrimming() = runBlocking {
        val material = EphemeralKeyMaterial.create("rich-folding")
        try {
            val prepared = PREPARED_CARD.copy(encryptedPrivate =
                PREPARED_CARD.encryptedPrivate.replace("END:VCARD",
                    "NOTE:${"Word ".repeat(60).trimEnd()}\nEND:VCARD"))
            val encrypted = ProtonCoreContactCardCrypto(material.provider())
                .protect(USER_ID, prepared).filterIsInstance<ContactCard.Encrypted>().single()
            requireNotNull(material.provider().acquire(USER_ID)).use { holder ->
                val plaintext = holder.decryptText(encrypted.data)
                assertTrue("VECTOR_MUST_INCLUDE_TRAILING_SPACE_BEFORE_FOLD",
                    plaintext.split('\n').zipWithNext().any { (line, next) ->
                        line.trimEnd('\r').endsWith(' ') && next.startsWith(' ')
                    })
                assertTrue("PRIVATE_SIGNATURE_MUST_VERIFY_EXACT_CONTENT",
                    holder.verifyData(plaintext.toByteArray(Charsets.UTF_8), requireNotNull(encrypted.signature)))
            }
        } finally {
            material.close()
        }
    }

    @Test
    fun exactContentSignatureSurvivesWhitespaceButRejectsChangedContent() = runBlocking {
        val primary = EphemeralKeyMaterial.create("exact-content")
        try {
            val crypto = ProtonCoreContactCardCrypto(primary.provider())
            val text = PREPARED_CARD.encryptedPrivate.replace("+33102030405", "+33102030405  ")
            val card = requireNotNull(primary.provider().acquire(USER_ID)).use { holder ->
                val signature = holder.signData(text.toByteArray(Charsets.UTF_8))
                assertTrue("VECTOR_MUST_EXPOSE_TEXT_NORMALIZATION", !holder.verifyText(text, signature))
                ContactCard.Encrypted(holder.encryptText(text), signature)
            }
            assertEquals(ContactCardType.EncryptedAndSigned,
                crypto.decryptAndVerify(USER_ID, listOf(card)).single().type)
            val changed = requireNotNull(primary.provider().acquire(USER_ID)).use { holder ->
                card.copy(data = holder.encryptText(text.replace("+33102030405", "+33102030406")))
            }
            assertVerificationFailure { crypto.decryptAndVerify(USER_ID, listOf(changed)) }
        } finally {
            primary.close()
        }
    }

    @Test
    fun maintainedOpenPgpRoundTripAndNegativeVectorsFailClosed() = runBlocking {
        val primary = EphemeralKeyMaterial.create("primary")
        val unrelated = EphemeralKeyMaterial.create("unrelated")
        try {
            val crypto = ProtonCoreContactCardCrypto(primary.provider())
            val cards = crypto.protect(USER_ID, PREPARED_CARD)

            assertEquals(3, cards.size)
            val encrypted = cards.filterIsInstance<ContactCard.Encrypted>().single()
            val signed = cards.filterIsInstance<ContactCard.Signed>().single()
            val clear = cards.filterIsInstance<ContactCard.ClearText>().single()
            assertNotEquals(PREPARED_CARD.encryptedPrivate, encrypted.data)
            assertTrue(encrypted.signature?.isNotBlank() == true)
            assertTrue(signed.signature.isNotBlank())
            assertTrue(clear.data.contains("VERSION:4.0"))

            val plain = crypto.decryptAndVerify(USER_ID, cards)
            assertEquals(
                listOf(
                    ContactCardType.EncryptedAndSigned,
                    ContactCardType.Signed,
                    ContactCardType.ClearText,
                ),
                plain.map(ProtonPlainContactCard::type),
            )
            assertTrue(plain[0].vCard.contains("TEL;TYPE=cell:+33102030405", ignoreCase = true))
            assertTrue(plain[1].vCard.contains("EMAIL:crypto@example.test", ignoreCase = true))
            assertTrue(plain[2].vCard.contains("UID:synthetic-crypto-contact", ignoreCase = true))

            val forgedSigned = signed.copy(data = signed.data.replace("Crypto Vector", "Forged Vector"))
            assertVerificationFailure { crypto.decryptAndVerify(USER_ID, listOf(forgedSigned)) }
            val secondSigned = crypto.protect(
                USER_ID,
                PREPARED_CARD.copy(signed = PREPARED_CARD.signed.replace("Crypto Vector", "Second Vector")),
            ).filterIsInstance<ContactCard.Signed>().single()
            assertVerificationFailure {
                crypto.decryptAndVerify(USER_ID, listOf(signed.copy(signature = secondSigned.signature)))
            }
            assertVerificationFailure {
                crypto.decryptAndVerify(USER_ID, listOf(signed.copy(signature = "not-a-pgp-signature")))
            }
            assertVerificationFailure {
                crypto.decryptAndVerify(USER_ID, listOf(encrypted.copy(data = "not-an-openpgp-message")))
            }
            val unsignedPlain = crypto.decryptAndVerify(USER_ID, listOf(encrypted.copy(signature = null))).single()
            assertEquals(ContactCardType.Encrypted, unsignedPlain.type)
            assertEquals(plain.first().vCard, unsignedPlain.vCard)
            assertVerificationFailure {
                crypto.decryptAndVerify(USER_ID, listOf(encrypted.copy(signature = "not-a-pgp-signature")))
            }
            assertMalformedFailure { crypto.decryptAndVerify(USER_ID, listOf(signed, signed)) }
            assertVerificationFailure {
                // Truncate the encrypted packet, not only the optional/tolerated armor footer.
                crypto.decryptAndVerify(USER_ID, listOf(encrypted.copy(data = encrypted.data.take(encrypted.data.length / 2))))
            }
            val otherEncrypted = crypto.protect(USER_ID, PREPARED_CARD.copy(
                encryptedPrivate = PREPARED_CARD.encryptedPrivate.replace("+33102030405", "+33102030406"),
            )).filterIsInstance<ContactCard.Encrypted>().single()
            assertVerificationFailure {
                crypto.decryptAndVerify(USER_ID, listOf(encrypted.copy(signature = otherEncrypted.signature)))
            }

            val wrongKeyCrypto = ProtonCoreContactCardCrypto(unrelated.provider())
            assertVerificationFailure { wrongKeyCrypto.decryptAndVerify(USER_ID, cards) }

            val wrongUser = ProtonCoreContactCardCrypto(primary.provider(expectedUser = USER_ID))
            try {
                wrongUser.protect(UserId("synthetic-wrong-user"), PREPARED_CARD)
                fail("CROSS_USER_KEY_CONTEXT_ACCEPTED")
            } catch (_: ProtonAuthenticationRequired) {
                // Expected: key acquisition is account/user scoped.
            }
        } finally {
            primary.close()
            unrelated.close()
        }
    }

    private suspend fun assertVerificationFailure(block: suspend () -> Unit) {
        try {
            block()
            fail("CRYPTOGRAPHIC_VERIFICATION_FAILURE_REQUIRED")
        } catch (_: ProtonContactVerificationFailure) {
            // Expected fail-closed outcome.
        }
    }

    private suspend fun assertMalformedFailure(block: suspend () -> Unit) {
        try {
            block()
            fail("MALFORMED_CRYPTO_INPUT_REJECTION_REQUIRED")
        } catch (_: ProtonMalformedContactResponse) {
            // Expected fail-closed outcome before canonical mapping.
        }
    }

    private class EphemeralKeyMaterial private constructor(
        private val context: CryptoContext,
        private val privateKey: PrivateKey,
        private val protectedPassphrase: EncryptedByteArray,
    ) : Closeable {
        fun compress(text: String): String {
            val key = com.proton.gopenpgp.crypto.Crypto.newKeyFromArmored(privateKey.publicKey(context).key)
            val ring = com.proton.gopenpgp.crypto.Crypto.newKeyRing(key)
            val bytes = text.toByteArray(Charsets.UTF_8)
            return try {
                ring.encryptWithCompression(com.proton.gopenpgp.crypto.PlainMessage(bytes), null).armored
            } finally {
                bytes.fill(0)
                try { ring.clearPrivateParams() } finally { key.clearPrivateParams() }
            }
        }

        fun provider(expectedUser: UserId = USER_ID, firstKey: EphemeralKeyMaterial? = null) = ProtonKeyHolderContextProvider { requestedUser ->
            if (requestedUser != expectedUser) {
                null
            } else {
                val keys = if (firstKey == null) listOf(privateKey) else
                    listOf(firstKey.privateKey.copy(isPrimary = true), privateKey.copy(isPrimary = false))
                val privateRing = PrivateKeyRing(context, keys)
                try {
                    KeyHolderContext(
                        context = context,
                        privateKeyRing = privateRing,
                        publicKeyRing = PublicKeyRing(keys.map { it.publicKey(context) }),
                    )
                } catch (error: Throwable) {
                    privateRing.close()
                    throw error
                }
            }
        }

        override fun close() {
            protectedPassphrase.array.fill(0)
        }

        companion object {
            fun create(label: String): EphemeralKeyMaterial {
                val context = AndroidCryptoContext()
                assertTrue("ANDROID_KEYSTORE_REQUIRED", context.keyStoreCrypto.isUsingKeyStore())
                val passphrase = context.pgpCrypto.generateNewToken()
                return try {
                    val armored = context.pgpCrypto.generateNewPrivateKey(
                        username = "contako-$label",
                        domain = "example.test",
                        passphrase = passphrase,
                    )
                    val protected = PlainByteArray(passphrase.copyOf()).use { plain ->
                        context.keyStoreCrypto.encrypt(plain)
                    }
                    EphemeralKeyMaterial(
                        context = context,
                        privateKey = PrivateKey(
                            key = armored,
                            isPrimary = true,
                            passphrase = protected,
                        ),
                        protectedPassphrase = protected,
                    )
                } finally {
                    passphrase.fill(0)
                }
            }
        }
    }

    private companion object {
        val USER_ID = UserId("synthetic-crypto-user")
        val PREPARED_CARD = ProtonPreparedVCard(
            encryptedPrivate = """
                BEGIN:VCARD
                VERSION:4.0
                UID:synthetic-crypto-contact
                TEL;TYPE=cell:+33102030405
                END:VCARD
            """.trimIndent(),
            signed = """
                BEGIN:VCARD
                VERSION:4.0
                UID:synthetic-crypto-contact
                FN:Crypto Vector
                EMAIL:crypto@example.test
                END:VCARD
            """.trimIndent(),
            clear = """
                BEGIN:VCARD
                VERSION:4.0
                UID:synthetic-crypto-contact
                END:VCARD
            """.trimIndent(),
        )
    }
}
