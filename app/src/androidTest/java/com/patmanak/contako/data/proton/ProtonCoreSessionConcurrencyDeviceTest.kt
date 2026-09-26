package com.patmanak.contako.data.proton

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.proton.core.account.data.repository.AccountRepositoryImpl
import me.proton.core.auth.domain.entity.SecondFactor
import me.proton.core.auth.domain.entity.SessionInfo
import me.proton.core.auth.domain.repository.AuthRepository
import me.proton.core.crypto.android.context.AndroidCryptoContext
import me.proton.core.domain.entity.Product
import me.proton.core.domain.entity.UserId
import me.proton.core.network.domain.ApiResult
import me.proton.core.network.domain.session.Session
import me.proton.core.network.domain.session.SessionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProtonCoreSessionConcurrencyDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun lateRefreshCannotRestoreClearedSessionOrReplaceFreshLogin() = runBlocking {
        withSeededCoordinator("gate-c-session-late-refresh-test.db") { coordinator, _ ->
            val original = requireNotNull(coordinator.getSession(SESSION_ID)) as Session.Authenticated
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            coordinator.bindAuthRepository(refreshRepository {
                entered.countDown()
                assertTrue("REFRESH_RELEASE_TIMEOUT", release.await(10, TimeUnit.SECONDS))
                ApiResult.Success(original.copy(accessToken = "synthetic-late-access"))
            })
            coroutineScope {
                val refresh = async(Dispatchers.Default) { coordinator.refreshSession(original) }
                try {
                    assertTrue("REFRESH_NOT_ENTERED", entered.await(10, TimeUnit.SECONDS))
                    coordinator.clearLocal(USER_ID)
                    assertNull(coordinator.getSession(SESSION_ID))
                    assertTrue(coordinator.getSessions().isEmpty())
                    val freshId = SessionId("synthetic-fresh-session")
                    assertTrue(coordinator.beginLoginAttempt())
                    coordinator.persistLogin(sessionInfo().copy(sessionId = freshId))
                    coordinator.endLoginAttempt()
                    coordinator.activate(USER_ID)
                    release.countDown()
                    assertFalse(refresh.await())
                    coordinator.onSessionTokenRefreshed(original)
                    coordinator.onSessionTokenCreated(USER_ID, original)
                    coordinator.onSessionScopesRefreshed(SESSION_ID, listOf("stale"))
                    coordinator.onSessionForceLogout(original, 401)
                    assertNull(coordinator.getSession(SESSION_ID))
                    assertEquals(freshId, coordinator.currentSessionId())
                    assertEquals(listOf(freshId), coordinator.getSessions().map { it.sessionId })
                    assertEquals(GateCStoredSessionState.READY, coordinator.state())
                } finally {
                    release.countDown()
                }
            }
        }
    }

    @Test
    fun currentPreAuthSessionCanRefreshButClearedOneCannotReturn() = runBlocking {
        withSeededCoordinator("gate-c-session-preauth-refresh-test.db") { coordinator, _ ->
            coordinator.clearLocal(USER_ID)
            val preAuth = Session.Unauthenticated(
                sessionId = SessionId("synthetic-preauth-session"),
                accessToken = "synthetic-preauth-access",
                refreshToken = "synthetic-preauth-refresh",
                scopes = emptyList(),
            )
            coordinator.onSessionTokenCreated(null, preAuth)
            coordinator.onSessionTokenRefreshed(preAuth.copy(scopes = listOf("preauth")))
            assertEquals(listOf("preauth"), coordinator.getSession(null)?.scopes)
            coordinator.clearLocal(null)
            coordinator.onSessionTokenRefreshed(preAuth)
            coordinator.onSessionTokenCreated(null, preAuth)
            assertNull(coordinator.getSession(null))
            assertNull(coordinator.getSession(preAuth.sessionId))
            assertTrue(coordinator.getSessions().isEmpty())
        }
    }

    @Test
    fun applicationAndCoreRefreshShareOneFlightAndPersistOneResult() = runBlocking {
        withSeededCoordinator("gate-c-session-concurrency-test.db") { coordinator, database ->
            val original = requireNotNull(coordinator.getSession(SESSION_ID)) as Session.Authenticated
            val calls = AtomicInteger(0)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val refreshed = original.copy(
                accessToken = "synthetic-refreshed-access",
                refreshToken = "synthetic-refreshed-refresh",
                scopes = listOf("full", "contacts"),
            )
            coordinator.bindAuthRepository(refreshRepository { session ->
                assertEquals(original.sessionId, session.sessionId)
                calls.incrementAndGet()
                entered.countDown()
                assertTrue("REFRESH_RELEASE_TIMEOUT", release.await(10, TimeUnit.SECONDS))
                ApiResult.Success(refreshed)
            })

            val start = CompletableDeferred<Unit>()
            val ready = CountDownLatch(CONCURRENT_CALLS)
            val results = coroutineScope {
                val jobs = (0 until CONCURRENT_CALLS).map { index ->
                    async(Dispatchers.Default) {
                        ready.countDown()
                        start.await()
                        if (index % 2 == 0) coordinator.refresh() else coordinator.refreshSession(original)
                    }
                }
                assertTrue("CONCURRENT_CALLERS_NOT_READY", ready.await(10, TimeUnit.SECONDS))
                start.complete(Unit)
                assertTrue("REFRESH_NOT_ENTERED", entered.await(10, TimeUnit.SECONDS))
                delay(250)
                release.countDown()
                jobs.awaitAll()
            }

            assertTrue(results.all { it })
            assertEquals(1, calls.get())
            val persisted = requireNotNull(coordinator.getSession(SESSION_ID)) as Session.Authenticated
            assertEquals(refreshed.accessToken, persisted.accessToken)
            assertEquals(refreshed.refreshToken, persisted.refreshToken)
            assertEquals(refreshed.scopes, persisted.scopes)
            database.close()
        }
    }

    @Test
    fun refreshedIdentityMismatchClearsPersistedAndTransientSession() = runBlocking {
        withSeededCoordinator("gate-c-session-identity-test.db") { coordinator, database ->
            val original = requireNotNull(coordinator.getSession(SESSION_ID)) as Session.Authenticated
            val cleanupCalls = AtomicInteger(0)
            coordinator.bindForcedLogoutCleanup { cleanupCalls.incrementAndGet() }
            coordinator.bindAuthRepository(refreshRepository {
                ApiResult.Success(
                    original.copy(
                        userId = UserId("synthetic-cross-account-user"),
                        accessToken = "synthetic-cross-account-access",
                        refreshToken = "synthetic-cross-account-refresh",
                    ),
                )
            })

            assertFalse(coordinator.refreshSession(original))
            assertEquals(1, cleanupCalls.get())
            assertEquals(GateCStoredSessionState.ABSENT, coordinator.state())
            assertNull(coordinator.currentUserId())
            assertNull(coordinator.currentSessionId())
            assertNull(coordinator.getSession(SESSION_ID))
            database.close()
        }
    }

    @Test
    fun persistedAccountRejectsSecondLoginWithoutChangingTheActiveIdentity() = runBlocking {
        withSeededCoordinator("gate-c-single-account-test.db") { coordinator, _ ->
            assertFalse(coordinator.beginLoginAttempt())
            assertEquals(USER_ID, coordinator.currentUserId())
            assertEquals(SESSION_ID, coordinator.currentSessionId())
            assertEquals(GateCStoredSessionState.READY, coordinator.state())
        }
    }

    private suspend fun withSeededCoordinator(
        databaseName: String,
        block: suspend (ProtonCoreSessionCoordinator, ProtonGateCDatabase) -> Unit,
    ) {
        context.deleteDatabase(databaseName)
        val crypto = AndroidCryptoContext().keyStoreCrypto
        val protectedStorage = GateCProtectedStorageGuard.requireAvailable(crypto)
        val database = ProtonGateCDatabase.build(context, protectedStorage, databaseName)
        try {
            val repository = AccountRepositoryImpl(Product.Mail, database, crypto)
            val coordinator = ProtonCoreSessionCoordinator(repository)
            coordinator.persistLogin(sessionInfo())
            coordinator.activate(USER_ID)
            block(coordinator, database)
        } finally {
            if (database.isOpen) database.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun refreshRepository(
        handler: (Session) -> ApiResult<Session>,
    ): AuthRepository = Proxy.newProxyInstance(
        AuthRepository::class.java.classLoader,
        arrayOf(AuthRepository::class.java),
    ) { _, method, arguments ->
        if (method.name == "refreshSession") {
            handler(requireNotNull(arguments).first() as Session)
        } else {
            throw UnsupportedOperationException("UNEXPECTED_AUTH_REPOSITORY_CALL:${method.name}")
        }
    } as AuthRepository

    private fun sessionInfo() = SessionInfo(
        username = "synthetic-concurrency-user",
        accessToken = "synthetic-concurrency-access",
        tokenType = "Bearer",
        scopes = listOf("full"),
        sessionId = SESSION_ID,
        userId = USER_ID,
        refreshToken = "synthetic-concurrency-refresh",
        eventId = "synthetic-concurrency-event",
        serverProof = null,
        localId = 1,
        passwordMode = 1,
        secondFactor = SecondFactor.Disabled,
        temporaryPassword = false,
    )

    private companion object {
        const val CONCURRENT_CALLS = 20
        val USER_ID = UserId("synthetic-concurrency-user-id")
        val SESSION_ID = SessionId("synthetic-concurrency-session-id")
    }
}
