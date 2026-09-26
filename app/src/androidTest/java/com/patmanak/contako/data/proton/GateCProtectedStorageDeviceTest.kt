package com.patmanak.contako.data.proton

import androidx.test.ext.junit.runners.AndroidJUnit4
import me.proton.core.crypto.android.context.AndroidCryptoContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GateCProtectedStorageDeviceTest {
    @Test
    fun androidKeystoreIsAvailableAndNeverFallsBackToPlaintext() {
        val crypto = AndroidCryptoContext().keyStoreCrypto
        assertTrue("ANDROID_KEYSTORE_REQUIRED", crypto.isUsingKeyStore())

        val plain = "synthetic-keystore-canary"
        val encrypted = crypto.encrypt(plain)

        assertNotEquals("PLAINTEXT_FALLBACK_FORBIDDEN", plain, encrypted)
        assertEquals(plain, crypto.decrypt(encrypted))
    }

    @Test
    fun tamperedCiphertextFailsClosed() {
        val crypto = AndroidCryptoContext().keyStoreCrypto
        assertTrue("ANDROID_KEYSTORE_REQUIRED", crypto.isUsingKeyStore())
        val encrypted = crypto.encrypt("synthetic-integrity-canary")
        val position = encrypted.length / 2
        val replacement = if (encrypted[position] == 'A') 'B' else 'A'
        val tampered = encrypted.replaceRange(position, position + 1, replacement.toString())

        val rejected = try {
            crypto.decrypt(tampered)
            false
        } catch (_: Exception) {
            true
        }
        assertTrue("TAMPERED_CIPHERTEXT_ACCEPTED", rejected)
    }
}
