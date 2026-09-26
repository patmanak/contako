package com.patmanak.contako.data.proton

import java.lang.reflect.Proxy
import java.security.ProviderException
import java.util.concurrent.atomic.AtomicInteger
import me.proton.core.crypto.common.keystore.KeyStoreCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GateCProtectedStorageGuardTest {
    @Test
    fun `available Android Keystore issues one database admission proof`() {
        val probes = AtomicInteger()
        val proof = GateCProtectedStorageGuard.requireAvailable(fakeCrypto {
            probes.incrementAndGet()
            true
        })

        assertNotNull(proof)
        assertEquals(1, probes.get())
    }

    @Test
    fun `unavailable Android Keystore fails closed without touching crypto operations`() {
        val failure = captureFailure {
            GateCProtectedStorageGuard.requireAvailable(fakeCrypto { false })
        }

        assertTrue(failure is GateCProtectedStorageUnavailable)
        assertEquals("PROTECTED_STORAGE_UNAVAILABLE", failure.message)
        assertEquals(null, failure.cause)
    }

    @Test
    fun `provider failure is reduced to the same sanitized closed state`() {
        val failure = captureFailure {
            GateCProtectedStorageGuard.requireAvailable(fakeCrypto {
                throw ProviderException("synthetic provider detail must not cross the boundary")
            })
        }

        assertTrue(failure is GateCProtectedStorageUnavailable)
        assertEquals("PROTECTED_STORAGE_UNAVAILABLE", failure.message)
        assertEquals(null, failure.cause)
        assertTrue(failure.toString().contains("synthetic provider detail").not())
    }

    private fun captureFailure(block: () -> Unit): Throwable = try {
        block()
        fail("PROTECTED_STORAGE_GUARD_MUST_FAIL")
        error("unreachable")
    } catch (failure: GateCProtectedStorageUnavailable) {
        failure
    }

    private fun fakeCrypto(probe: () -> Boolean): KeyStoreCrypto = Proxy.newProxyInstance(
        KeyStoreCrypto::class.java.classLoader,
        arrayOf(KeyStoreCrypto::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "isUsingKeyStore" -> probe()
            else -> error("CRYPTO_OPERATION_BEFORE_PROTECTED_STORAGE_ADMISSION:${method.name}")
        }
    } as KeyStoreCrypto
}
