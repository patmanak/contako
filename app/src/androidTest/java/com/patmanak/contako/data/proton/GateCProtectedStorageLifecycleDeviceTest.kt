package com.patmanak.contako.data.proton

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import me.proton.core.crypto.android.context.AndroidCryptoContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Host-sequenced Android Keystore lifecycle proof. Every test selector runs in a fresh
 * instrumentation process; no plaintext fixture is persisted.
 */
@RunWith(AndroidJUnit4::class)
class GateCProtectedStorageLifecycleDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun prepareCiphertextForProcessRestart() {
        assumeHostStep("prepareCiphertextForProcessRestart")
        storeEncryptedFixture(RESTART_CIPHERTEXT)
    }

    @Test
    fun ciphertextDecryptsAfterProcessRestart() {
        assumeHostStep("ciphertextDecryptsAfterProcessRestart")
        val encrypted = requireEncryptedFixture(RESTART_CIPHERTEXT)
        try {
            val crypto = AndroidCryptoContext().keyStoreCrypto
            assertTrue("ANDROID_KEYSTORE_REQUIRED", crypto.isUsingKeyStore())
            assertEquals("PROCESS_RESTART_DECRYPTION_FAILED", SYNTHETIC_PLAINTEXT, crypto.decrypt(encrypted))
        } finally {
            clearFixtures()
        }
    }

    @Test
    fun prepareCiphertextThenDeleteMasterKey() {
        assumeHostStep("prepareCiphertextThenDeleteMasterKey")
        storeEncryptedFixture(INVALIDATED_CIPHERTEXT)
        val keyStore = androidKeyStore()
        assertTrue("MASTER_KEY_MISSING_BEFORE_INVALIDATION", keyStore.containsAlias(MASTER_KEY_ALIAS))
        keyStore.deleteEntry(MASTER_KEY_ALIAS)
        assertFalse("MASTER_KEY_INVALIDATION_FAILED", androidKeyStore().containsAlias(MASTER_KEY_ALIAS))
    }

    @Test
    fun invalidatedCiphertextFailsClosedAfterProcessRestart() {
        assumeHostStep("invalidatedCiphertextFailsClosedAfterProcessRestart")
        val encrypted = requireEncryptedFixture(INVALIDATED_CIPHERTEXT)
        try {
            val crypto = AndroidCryptoContext().keyStoreCrypto
            assertTrue("ANDROID_KEYSTORE_REPLACEMENT_FAILED", crypto.isUsingKeyStore())
            val rejected = try {
                crypto.decrypt(encrypted)
                false
            } catch (_: Exception) {
                true
            }
            assertTrue("INVALIDATED_CIPHERTEXT_ACCEPTED", rejected)
        } finally {
            clearFixtures()
        }
    }

    @Test
    fun cleanupEncryptedFixtures() {
        assumeHostStep("cleanupEncryptedFixtures")
        context.deleteSharedPreferences(TEST_PREFERENCES)
        assertTrue("ANDROID_KEYSTORE_CLEANUP_REPLACEMENT_FAILED", AndroidCryptoContext().keyStoreCrypto.isUsingKeyStore())
        assertTrue("ENCRYPTED_FIXTURE_CLEANUP_FAILED", preferences().all.isEmpty())
    }

    private fun assumeHostStep(expected: String) {
        val actual = InstrumentationRegistry.getArguments().getString(HOST_STEP_ARGUMENT)
        assumeTrue("HOST_SEQUENCED_ONLY", actual == expected)
    }

    private fun storeEncryptedFixture(key: String) {
        removeFixture(key)
        val crypto = AndroidCryptoContext().keyStoreCrypto
        assertTrue("ANDROID_KEYSTORE_REQUIRED", crypto.isUsingKeyStore())
        val encrypted = crypto.encrypt(SYNTHETIC_PLAINTEXT)
        assertNotEquals("PLAINTEXT_FALLBACK_FORBIDDEN", SYNTHETIC_PLAINTEXT, encrypted)
        assertTrue(preferences().edit().putString(key, encrypted).commit())
    }

    private fun requireEncryptedFixture(key: String): String =
        requireNotNull(preferences().getString(key, null)) { "ENCRYPTED_FIXTURE_MISSING" }

    private fun removeFixture(key: String) {
        assertTrue(preferences().edit().remove(key).commit())
    }

    private fun clearFixtures() {
        assertTrue("ENCRYPTED_FIXTURE_CLEANUP_FAILED", context.deleteSharedPreferences(TEST_PREFERENCES))
    }

    private fun preferences() = context.getSharedPreferences(TEST_PREFERENCES, Context.MODE_PRIVATE)

    private fun androidKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val HOST_STEP_ARGUMENT = "contako.keystoreLifecycleStep"
        const val MASTER_KEY_ALIAS = "_me_proton_core_data_crypto_master_key_"
        const val TEST_PREFERENCES = "gate_c_protected_storage_lifecycle_test"
        const val RESTART_CIPHERTEXT = "restart_ciphertext"
        const val INVALIDATED_CIPHERTEXT = "invalidated_ciphertext"
        const val SYNTHETIC_PLAINTEXT = "synthetic-process-lifecycle-canary"
    }
}
