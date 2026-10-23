package com.patmanak.contako.data.proton

import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import me.proton.core.account.domain.entity.Account
import me.proton.core.account.domain.entity.AccountState
import me.proton.core.account.domain.entity.SessionState
import me.proton.core.account.domain.repository.AccountRepository
import me.proton.core.auth.domain.entity.SecondFactor
import me.proton.core.auth.domain.entity.SessionInfo
import me.proton.core.domain.entity.UserId
import me.proton.core.network.domain.session.Session
import me.proton.core.network.domain.session.SessionId
import org.junit.Assert.*
import org.junit.Test

/** Uses the production session coordinator, retaining ownership across session-store destruction. */
class ProtonAccountOwnerIsolationTest {
    @Test fun ownerSurvivesMetadataStoreReconstructionAndCorruptionFailsClosed() = runTest {
        val directory = Files.createTempDirectory("contako-owner-fixture").toFile()
        val file = java.io.File(directory, "owner")
        try {
            DurableAccountOwner(FileAccountOwnerStorage(file)) { false }.requireOwner(UserId("owner-a"))
            val restored = DurableAccountOwner(FileAccountOwnerStorage(file)) { false }
            restored.requireOwner(UserId("owner-a"))
            try { restored.requireOwner(UserId("owner-b")); fail("Persisted owner must survive restart") }
            catch (_: LocalAccountOwnerMismatch) { }
            file.writeText("")
            try { restored.requireOwner(UserId("owner-b")); fail("Corrupt metadata must not permit rebinding") }
            catch (_: IllegalArgumentException) { }
        } finally {
            file.delete()
            java.io.File(directory, "owner.pending").delete()
            directory.delete()
        }
    }

    @Test fun revocationAndRestartCannotPresentOrUploadOldIntentUnderAnotherUser() = runTest {
        val metadata = MemoryOwnerStorage()
        val repository = FakeAccounts()
        val durableIntent = mutableListOf("CREATE", "UPDATE", "DELETE")
        var existingData = false
        fun coordinator() = ProtonCoreSessionCoordinator(repository.proxy, owner =
            DurableAccountOwner(metadata) { existingData })
        val first = coordinator()
        first.persistLogin(login("owner-a", "session-a"))
        first.activate(UserId("owner-a"))
        existingData = true
        first.onSessionForceLogout(first.getSession(SessionId("session-a"))!!, 401)
        assertEquals(GateCStoredSessionState.ABSENT, first.state())

        val restarted = coordinator()
        try {
            restarted.persistLogin(login("owner-b", "session-b"))
            fail("A foreign owner MUST NOT acquire the local partition")
        } catch (_: LocalAccountOwnerMismatch) { }
        assertNull(restarted.currentUserId())
        assertEquals(GateCStoredSessionState.ABSENT, restarted.state())
        assertEquals(listOf("CREATE", "UPDATE", "DELETE"), durableIntent)
        assertEquals("owner-a", metadata.value)

        restarted.persistLogin(login("owner-a", "session-a-new"))
        restarted.activate(UserId("owner-a"))
        assertEquals(UserId("owner-a"), restarted.currentUserId())
        assertEquals(GateCStoredSessionState.READY, restarted.state())
        assertEquals(listOf("CREATE", "UPDATE", "DELETE"), durableIntent)
    }

    @Test fun legacyDataWithoutRetainedIdentityCannotBeClaimedByFreshLogin() = runTest {
        val storage = MemoryOwnerStorage()
        val coordinator = ProtonCoreSessionCoordinator(FakeAccounts().proxy, owner = DurableAccountOwner(storage) { true })
        try {
            coordinator.persistLogin(login("new-owner", "new-session"))
            fail("Unowned legacy data MUST remain quarantined")
        } catch (_: LocalAccountOwnerMismatch) { }
        assertNull(storage.value)
        assertNull(coordinator.currentUserId())
    }

    @Test fun legacyRetainedSessionEstablishesOwnerAndExplicitEmptyCleanupCanReleaseIt() = runTest {
        val repository = FakeAccounts()
        ProtonCoreSessionCoordinator(repository.proxy).apply {
            persistLogin(login("legacy-owner", "legacy-session"))
            activate(UserId("legacy-owner"))
        }
        val storage = MemoryOwnerStorage()
        var data = true
        val owner = DurableAccountOwner(storage) { data }
        val coordinator = ProtonCoreSessionCoordinator(repository.proxy, owner = owner)
        assertEquals(GateCStoredSessionState.READY, coordinator.state())
        assertEquals("legacy-owner", storage.value)
        try { owner.releaseAfterLocalDataCleared(); fail("Must retain nonempty partition") } catch (_: IllegalStateException) { }
        assertEquals("legacy-owner", storage.value)
        coordinator.clearLocal(UserId("legacy-owner"))
        data = false
        owner.releaseAfterLocalDataCleared()
        coordinator.persistLogin(login("next-owner", "next-session"))
        coordinator.activate(UserId("next-owner"))
        assertEquals("next-owner", storage.value)
    }

    @Test fun revokedActivationCannotResurrectReadySessionAndRevisionIsObservable() = runTest {
        val repository = FakeAccounts()
        val coordinator = ProtonCoreSessionCoordinator(repository.proxy,
            owner = DurableAccountOwner(MemoryOwnerStorage()) { false })
        coordinator.persistLogin(login("owner", "session"))
        val before = coordinator.sessionRevision.value
        coordinator.clearLocal(UserId("owner"))
        assertTrue(coordinator.sessionRevision.value > before)
        try { coordinator.activate(UserId("owner")); fail("Late activation must fail") } catch (_: IllegalStateException) { }
        assertNull(coordinator.currentUserId())
    }

    @Test fun deferredForcedCleanupCannotRaceReauthenticationOrEraseTheReplacementSession() = runTest {
        val accounts = FakeAccounts()
        val coordinator = ProtonCoreSessionCoordinator(accounts.proxy,
            owner = DurableAccountOwner(MemoryOwnerStorage()) { true })
        // Seed a retained legacy session so ownership can be established from the actual session.
        ProtonCoreSessionCoordinator(accounts.proxy).persistLogin(login("owner", "old-session"))
        coordinator.activate(UserId("owner"))
        val old = requireNotNull(coordinator.getSession(SessionId("old-session")))
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var cleanups = 0
        coordinator.bindForcedLogoutCleanup { cleanups++; entered.complete(Unit); resume.await() }
        val cleanup = launch { coordinator.onSessionForceLogout(old, 401) }
        entered.await()
        assertEquals(GateCStoredSessionState.ABSENT, coordinator.state())
        // Explicit reauthentication may clear Core while the older callback is still suspended.
        coordinator.clearLocal(UserId("owner"))
        assertFalse(coordinator.beginLoginAttempt())
        try { coordinator.persistLogin(login("owner", "too-early")); fail("Key cleanup must finish before login persistence") }
        catch (_: IllegalStateException) { }
        resume.complete(Unit)
        cleanup.join()
        assertTrue(coordinator.beginLoginAttempt())
        coordinator.persistLogin(login("owner", "replacement-session"))
        coordinator.endLoginAttempt()
        coordinator.activate(UserId("owner"))
        coordinator.onSessionForceLogout(old, 401)
        assertEquals(1, cleanups)
        assertNotNull(coordinator.getSession(SessionId("replacement-session")))
        assertEquals(GateCStoredSessionState.READY, coordinator.state())
    }

    @Test fun cleanupOfAnOlderUserDoesNotInvalidateAnotherUsersSessionTokens() = runTest {
        val accounts = FakeAccounts()
        val coordinator = ProtonCoreSessionCoordinator(accounts.proxy)
        coordinator.persistLogin(login("owner-b", "session-b"))
        coordinator.activate(UserId("owner-b"))
        coordinator.clearLocal(UserId("owner-a"))
        assertNotNull(coordinator.getSession(SessionId("session-b")))
        assertEquals(GateCStoredSessionState.READY, coordinator.state())
    }

    @Test fun delayedForcedLogoutForAnOldAccessTokenDoesNotRevokeAFreshToken() = runTest {
        val accounts = FakeAccounts()
        val coordinator = ProtonCoreSessionCoordinator(accounts.proxy)
        coordinator.persistLogin(login("owner", "session"))
        coordinator.activate(UserId("owner"))
        val old = requireNotNull(coordinator.getSession(SessionId("session"))) as Session.Authenticated
        coordinator.onSessionTokenRefreshed(old.copy(accessToken = "synthetic-new-access", refreshToken = "synthetic-new-refresh"))
        coordinator.onSessionForceLogout(old, 401)
        assertEquals("synthetic-new-access", coordinator.getSession(SessionId("session"))?.accessToken)
        assertEquals(GateCStoredSessionState.READY, coordinator.state())
    }

    private class MemoryOwnerStorage : AccountOwnerStorage {
        var value: String? = null
        override suspend fun read() = value
        override suspend fun write(userId: String) { value = userId }
        override suspend fun clear() { value = null }
    }

    private class FakeAccounts {
        val accounts = MutableStateFlow<List<Account>>(emptyList())
        val sessions = MutableStateFlow<List<Session>>(emptyList())
        val proxy = Proxy.newProxyInstance(AccountRepository::class.java.classLoader,
            arrayOf(AccountRepository::class.java)) { _, method, args ->
            when {
                method.name == "getAccounts" -> accounts
                method.name == "getSessions" -> sessions
                method.name.startsWith("createOrUpdateAccountSession") -> {
                    accounts.value = listOf(args!![0] as Account)
                    sessions.value = listOf(args[1] as Session)
                    Unit
                }
                method.name.startsWith("getSessionOrNull") -> sessions.value.singleOrNull { it.sessionId == args!![0] }
                method.name.startsWith("updateSessionState") -> {
                    accounts.value = accounts.value.map { it.copy(sessionState = args!![1] as SessionState) }; Unit
                }
                method.name.startsWith("updateAccountState") -> {
                    accounts.value = accounts.value.map { it.copy(state = args!![1] as AccountState) }; Unit
                }
                method.name.startsWith("deleteAccount") -> {
                    accounts.value = accounts.value.filterNot { it.userId == args!![0] }
                    sessions.value = sessions.value.filterNot { (it as? Session.Authenticated)?.userId == args!![0] }; Unit
                }
                method.name.startsWith("updateSessionToken") -> {
                    sessions.value = sessions.value.map { session ->
                        if (session.sessionId != args!![0]) session else when (session) {
                            is Session.Authenticated -> session.copy(accessToken = args[1] as String, refreshToken = args[2] as String)
                            is Session.Unauthenticated -> session.copy(accessToken = args[1] as String, refreshToken = args[2] as String)
                        }
                    }; Unit
                }
                method.name.startsWith("updateSessionScopes") -> Unit
                else -> error("Unexpected repository operation: ${method.name}")
            }
        } as AccountRepository
    }

    private fun login(owner: String, session: String) = SessionInfo(
        username = "synthetic-user", accessToken = "synthetic-access", tokenType = "Bearer",
        scopes = listOf("full"), sessionId = SessionId(session), userId = UserId(owner),
        refreshToken = "synthetic-refresh", eventId = "synthetic-event", serverProof = null,
        localId = 1, passwordMode = 1, secondFactor = SecondFactor.Disabled, temporaryPassword = false,
    )
}
