package com.patmanak.contako.data.proton

import me.proton.core.crypto.common.keystore.KeyStoreCrypto

/**
 * Fail-closed admission for Proton Core's account, session, and derived-passphrase store.
 *
 * Proton Core's Android implementation intentionally falls back to copying plaintext when no
 * Android Keystore key can be obtained. Contako does not permit that compatibility fallback. A
 * proof returned here is therefore required before the Gate C database can be opened.
 */
internal object GateCProtectedStorageGuard {
    private data object Issuer

    internal class Proof private constructor() {
        internal companion object {
            fun issue(issuer: Any): Proof {
                check(issuer === Issuer) { "PROTECTED_STORAGE_PROOF_ISSUER_REJECTED" }
                return Proof()
            }
        }
    }

    fun requireAvailable(keyStoreCrypto: KeyStoreCrypto): Proof {
        val available = try {
            keyStoreCrypto.isUsingKeyStore()
        } catch (_: Exception) {
            false
        }
        if (!available) throw GateCProtectedStorageUnavailable()
        return Proof.issue(Issuer)
    }
}

/** Stable, sanitized failure: provider exceptions and device details never cross this boundary. */
internal class GateCProtectedStorageUnavailable : SecurityException("PROTECTED_STORAGE_UNAVAILABLE")
