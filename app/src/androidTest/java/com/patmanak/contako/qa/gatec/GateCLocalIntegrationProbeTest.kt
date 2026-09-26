package com.patmanak.contako.qa.gatec

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.contako.data.proton.GateCRequestAudit
import com.patmanak.contako.data.proton.GateCRequestClass
import com.patmanak.contako.data.proton.GateCProtectedStorageGuard
import com.patmanak.contako.data.proton.ProtonCoreSessionCoordinator
import com.patmanak.contako.data.proton.ProtonGateCDatabase
import com.patmanak.contako.data.proton.ProtonGateCNetworkFactory
import kotlinx.coroutines.runBlocking
import me.proton.core.account.data.repository.AccountRepositoryImpl
import me.proton.core.auth.data.api.AuthenticationApi
import me.proton.core.crypto.android.context.AndroidCryptoContext
import me.proton.core.crypto.android.srp.GOpenPGPSrpCrypto
import me.proton.core.challenge.data.ChallengeManagerImpl
import me.proton.core.challenge.data.repository.ChallengeRepositoryImpl
import me.proton.core.challenge.domain.useFlow
import me.proton.core.challenge.data.frame.ChallengeFrame
import me.proton.core.domain.entity.Product
import me.proton.core.network.domain.session.SessionId
import me.proton.core.util.kotlin.DefaultDispatcherProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Local-only probes: no API method is invoked and no credential fixture is used. */
@RunWith(AndroidJUnit4::class)
class GateCLocalIntegrationProbeTest {
    @Test
    fun emptyDatabaseLoginPreflightCanBeginAndEndWithoutNetwork() {
        withCoordinator { coordinator, audit, _ ->
            assertTrue(runBlocking { coordinator.beginLoginAttempt() })
            runBlocking { coordinator.endLoginAttempt() }
            assertEquals(0, audit.requests)
        }
    }

    @Test
    fun authenticationApiServiceConstructionDoesNotRequestNetworkOrSession() {
        withCoordinator { coordinator, audit, _ ->
            val graph = ProtonGateCNetworkFactory.build(
                context = InstrumentationRegistry.getInstrumentation().targetContext,
                sessionCoordinator = coordinator,
                requestAudit = audit,
            )

            val service = runBlocking {
                graph.apiProvider.get<AuthenticationApi>(sessionId = null as SessionId?)
            }

            assertNotNull(service)
            assertEquals(0, audit.requests)
        }
    }

    @Test
    fun srpPrimitiveInitializationIsLocalAndDoesNotRecurse() {
        val password = "synthetic-probe-only".encodeToByteArray()
        val outcome = try {
            runCatching {
                runBlocking {
                    GOpenPGPSrpCrypto(DefaultDispatcherProvider()).generateSrpProofs(
                        username = "synthetic-probe",
                        password = password,
                        version = 4,
                        salt = "AA==",
                        modulus = "AA==",
                        serverEphemeral = "AA==",
                    )
                }
            }
        } finally {
            password.fill(0)
        }

        assertFalse(outcome.exceptionOrNull() is StackOverflowError)
        assertFalse(outcome.exceptionOrNull() is LinkageError)
        assertTrue(password.all { it == 0.toByte() })
    }

    @Test
    fun challengeDatabaseFlowCanOpenAndResetWithoutNetwork() {
        withCoordinator { _, audit, database ->
            val manager = ChallengeManagerImpl(ChallengeRepositoryImpl(database))
            val (deviceFrame, frameCount) = runBlocking {
                val frame = ChallengeFrame.Device.build(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                )
                frame to manager.useFlow("synthetic-local-probe") { frames -> frames.size }
            }

            assertNotNull(deviceFrame)
            assertEquals(0, frameCount)
            assertEquals(0, audit.requests)
        }
    }

    private fun withCoordinator(
        block: (ProtonCoreSessionCoordinator, CountingAudit, ProtonGateCDatabase) -> Unit,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(PROBE_DATABASE)
        val crypto = AndroidCryptoContext().keyStoreCrypto
        val protectedStorage = GateCProtectedStorageGuard.requireAvailable(crypto)
        val database = ProtonGateCDatabase.build(context, protectedStorage, PROBE_DATABASE)
        try {
            val repository = AccountRepositoryImpl(Product.Mail, database, crypto)
            block(ProtonCoreSessionCoordinator(repository), CountingAudit(), database)
        } finally {
            database.close()
            context.deleteDatabase(PROBE_DATABASE)
        }
    }

    private class CountingAudit : GateCRequestAudit {
        var requests: Int = 0
            private set

        override fun onRequest(requestClass: GateCRequestClass) {
            requests += 1
        }
    }

    private companion object {
        const val PROBE_DATABASE = "gate-c-local-probe.db"
    }
}
