package com.patmanak.contako.data.proton

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.GatewayOutcome
import com.patmanak.contako.data.gateway.SessionState as ContakoSessionState
import java.io.File
import kotlinx.coroutines.runBlocking
import me.proton.core.account.data.repository.AccountRepositoryImpl
import me.proton.core.auth.domain.entity.Fido2Info
import me.proton.core.auth.domain.entity.ScopeInfo
import me.proton.core.auth.domain.entity.SecondFactor
import me.proton.core.auth.domain.entity.SecondFactorMethod
import me.proton.core.auth.domain.entity.SessionInfo
import me.proton.core.crypto.android.context.AndroidCryptoContext
import me.proton.core.domain.entity.Product
import me.proton.core.domain.entity.UserId
import me.proton.core.network.domain.session.Session
import me.proton.core.network.domain.session.SessionId
import me.proton.core.user.domain.UserManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Host-sequenced proof for Proton Core's real encrypted account/session Room repository. */
@RunWith(AndroidJUnit4::class)
class GateCSessionStorageLifecycleDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun prepareSyntheticSessionForProcessRestart() = runBlocking {
        assumeHostStep("prepareSyntheticSessionForProcessRestart")
        deleteFixtureDatabase()
        val database = openProtectedDatabase()
        try {
            val coordinator = coordinator(database)
            coordinator.persistLogin(sessionInfo())
            coordinator.activate(USER_ID)
            assertEquals(GateCStoredSessionState.READY, coordinator.state())
        } finally {
            database.close()
        }
        assertNoPlaintextTokenOnDisk()
    }

    @Test
    fun syntheticSessionRestoresAfterProcessRestartAndCleans() = runBlocking {
        assumeHostStep("syntheticSessionRestoresAfterProcessRestartAndCleans")
        val database = openProtectedDatabase()
        try {
            val coordinator = coordinator(database)
            assertEquals(GateCStoredSessionState.READY, coordinator.state())
            assertEquals(USER_ID, coordinator.currentUserId())
            assertEquals(SESSION_ID, coordinator.currentSessionId())
            val session = coordinator.getSession(SESSION_ID)
            assertTrue("AUTHENTICATED_SESSION_NOT_RESTORED", session is Session.Authenticated)
            session as Session.Authenticated
            assertEquals(USER_ID, session.userId)
            assertEquals(SYNTHETIC_ACCESS_TOKEN, session.accessToken)
            assertEquals(SYNTHETIC_REFRESH_TOKEN, session.refreshToken)

            coordinator.clearLocal(USER_ID)
            assertEquals(GateCStoredSessionState.ABSENT, coordinator.state())
            assertNull(coordinator.getSession(SESSION_ID))
        } finally {
            database.close()
            deleteFixtureDatabase()
        }
    }

    @Test
    fun prepareIncompleteSecondFactorForProcessRestart() = runBlocking {
        assumeHostStep("prepareIncompleteSecondFactorForProcessRestart")
        deleteFixtureDatabase()
        val database = openProtectedDatabase()
        try {
            val coordinator = coordinator(database)
            coordinator.persistLogin(
                sessionInfo(
                    secondFactor = SecondFactor.Enabled(
                        supportedMethods = setOf(SecondFactorMethod.Totp),
                        fido2 = Fido2Info(null, emptyList()),
                    ),
                ),
            )
            assertEquals(GateCStoredSessionState.SECOND_FACTOR_REQUIRED, coordinator.state())
        } finally {
            database.close()
        }
        assertNoPlaintextTokenOnDisk()
    }

    @Test
    fun incompleteSecondFactorIsClearedOnRestoreAfterProcessRestart() = runBlocking {
        assumeHostStep("incompleteSecondFactorIsClearedOnRestoreAfterProcessRestart")
        val database = openProtectedDatabase()
        try {
            val coordinator = coordinator(database)
            assertEquals(GateCStoredSessionState.SECOND_FACTOR_REQUIRED, coordinator.state())
            val outcome = ProtonCoreSessionAdapter(ACCOUNT_SCOPE, noCallCoreOperations, coordinator)
                .restore(ACCOUNT_SCOPE)
            assertEquals(
                GatewayOutcome.Success(ContakoSessionState.AUTHENTICATION_REQUIRED),
                outcome,
            )
            assertEquals(GateCStoredSessionState.ABSENT, coordinator.state())
            assertNull(coordinator.currentUserId())
            assertNull(coordinator.currentSessionId())
            assertNull(coordinator.getSession(SESSION_ID))
        } finally {
            database.close()
            deleteFixtureDatabase()
        }
    }

    @Test
    fun cleanupSyntheticSessionFixture() {
        assumeHostStep("cleanupSyntheticSessionFixture")
        deleteFixtureDatabase()
        assertFalse("SESSION_FIXTURE_DATABASE_REMAINS", context.getDatabasePath(DATABASE_NAME).exists())
    }

    private fun coordinator(database: ProtonGateCDatabase): ProtonCoreSessionCoordinator {
        val crypto = AndroidCryptoContext().keyStoreCrypto
        GateCProtectedStorageGuard.requireAvailable(crypto)
        return ProtonCoreSessionCoordinator(AccountRepositoryImpl(Product.Mail, database, crypto))
    }

    private fun openProtectedDatabase(): ProtonGateCDatabase {
        val crypto = AndroidCryptoContext().keyStoreCrypto
        val proof = GateCProtectedStorageGuard.requireAvailable(crypto)
        return ProtonGateCDatabase.build(context, proof, DATABASE_NAME)
    }

    private fun sessionInfo(secondFactor: SecondFactor = SecondFactor.Disabled) = SessionInfo(
        username = "synthetic-test-user",
        accessToken = SYNTHETIC_ACCESS_TOKEN,
        tokenType = "Bearer",
        scopes = listOf("full"),
        sessionId = SESSION_ID,
        userId = USER_ID,
        refreshToken = SYNTHETIC_REFRESH_TOKEN,
        eventId = "synthetic-event-id",
        serverProof = null,
        localId = 1,
        passwordMode = 1,
        secondFactor = secondFactor,
        temporaryPassword = false,
    )

    private fun assertNoPlaintextTokenOnDisk() {
        databaseFiles().filter(File::exists).forEach { file ->
            val bytes = file.readBytes()
            assertFalse("PLAINTEXT_ACCESS_TOKEN_ON_DISK", bytes.contains(SYNTHETIC_ACCESS_TOKEN.encodeToByteArray()))
            assertFalse("PLAINTEXT_REFRESH_TOKEN_ON_DISK", bytes.contains(SYNTHETIC_REFRESH_TOKEN.encodeToByteArray()))
        }
    }

    private fun databaseFiles(): List<File> {
        val database = context.getDatabasePath(DATABASE_NAME)
        return listOf(
            database,
            File(database.parentFile, "$DATABASE_NAME-wal"),
            File(database.parentFile, "$DATABASE_NAME-shm"),
            File(database.parentFile, "$DATABASE_NAME-journal"),
        )
    }

    private fun deleteFixtureDatabase() {
        context.deleteDatabase(DATABASE_NAME)
        databaseFiles().forEach { file -> if (file.exists()) assertTrue(file.delete()) }
    }

    private fun assumeHostStep(expected: String) {
        val actual = InstrumentationRegistry.getArguments().getString(HOST_STEP_ARGUMENT)
        assumeTrue("HOST_SEQUENCED_ONLY", actual == expected)
    }

    private fun ByteArray.contains(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        return (0..size - needle.size).any { offset ->
            needle.indices.all { index -> this[offset + index] == needle[index] }
        }
    }

    private companion object {
        const val HOST_STEP_ARGUMENT = "contako.sessionStorageLifecycleStep"
        const val DATABASE_NAME = "gate-c-session-storage-lifecycle-test.db"
        const val SYNTHETIC_ACCESS_TOKEN = "synthetic-access-token-storage-canary"
        const val SYNTHETIC_REFRESH_TOKEN = "synthetic-refresh-token-storage-canary"
        val USER_ID = UserId("synthetic-storage-user-id")
        val SESSION_ID = SessionId("synthetic-storage-session-id")
        val ACCOUNT_SCOPE = AccountScope("synthetic-storage-account")

        val noCallCoreOperations = object : GateCCoreOperations {
            private fun unexpected(): Nothing = error("CORE_OPERATION_MUST_NOT_RUN_DURING_RESTORE_CLEANUP")

            override suspend fun login(username: String, password: ByteArray): SessionInfo = unexpected()
            override suspend fun secondFactor(sessionId: SessionId, code: String): ScopeInfo = unexpected()
            override suspend fun unlock(userId: UserId, password: ByteArray): UserManager.UnlockResult = unexpected()
            override suspend fun restoreProtectedUnlock(userId: UserId): UserManager.UnlockResult? = unexpected()
            override suspend fun lockAndClear(userId: UserId): Unit = unexpected()
            override suspend fun revoke(sessionId: SessionId): Boolean = unexpected()
        }
    }
}
