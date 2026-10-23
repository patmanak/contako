package com.patmanak.contako.data.proton

import com.patmanak.contako.data.gateway.GatewayFailureCategory
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.proton.core.account.domain.entity.Account
import me.proton.core.account.domain.entity.AccountDetails
import me.proton.core.account.domain.entity.AccountMetadataDetails
import me.proton.core.account.domain.entity.AccountState
import me.proton.core.account.domain.entity.AccountType
import me.proton.core.account.domain.entity.SessionDetails
import me.proton.core.account.domain.entity.SessionState
import me.proton.core.account.domain.repository.AccountRepository
import me.proton.core.auth.domain.entity.SecondFactor
import me.proton.core.auth.domain.entity.SessionInfo
import me.proton.core.auth.domain.repository.AuthRepository
import me.proton.core.domain.entity.UserId
import me.proton.core.network.domain.session.Session
import me.proton.core.network.domain.session.SessionId
import me.proton.core.network.domain.session.SessionListener
import me.proton.core.network.domain.session.SessionProvider
import me.proton.core.user.data.db.dao.AddressDao

internal enum class GateCStoredSessionState {
    ABSENT,
    SECOND_FACTOR_REQUIRED,
    MAILBOX_PASSWORD_REQUIRED,
    KEY_UNLOCK_REQUIRED,
    READY,
}

internal data class GateCSessionRef(
    val userId: UserId,
    val sessionId: SessionId,
)

internal data class GateCDisplayAddress(
    val email: String,
    val enabled: Boolean,
    val order: Int,
)

internal fun selectCurrentAccountAddress(
    addresses: List<GateCDisplayAddress>,
    accountEmail: String?,
    username: String?,
): String? = addresses
    .asSequence()
    .filter(GateCDisplayAddress::enabled)
    .sortedBy(GateCDisplayAddress::order)
    .map(GateCDisplayAddress::email)
    .firstOrNull(::looksLikeEmailAddress)
    ?: accountEmail?.takeIf(::looksLikeEmailAddress)
    ?: username?.takeIf(::looksLikeEmailAddress)

private fun looksLikeEmailAddress(value: String): Boolean {
    val separator = value.indexOf('@')
    return separator > 0 && separator < value.lastIndex
}

internal interface GateCSessionLifecycle {
    suspend fun beginLoginAttempt(): Boolean
    suspend fun endLoginAttempt()
    suspend fun persistLogin(sessionInfo: SessionInfo)
    suspend fun completeSecondFactor(sessionId: SessionId, scopes: List<String>): GateCStoredSessionState
    suspend fun activate(userId: UserId)
    suspend fun state(): GateCStoredSessionState
    suspend fun currentUserId(): UserId?

    /**
     * Address of the connected Proton account, used as the Android account name under `D-081`.
     * Not a credential: it is the identity the user signed in with.
     */
    suspend fun currentAccountAddress(): String?
    suspend fun currentSessionId(): SessionId?
    suspend fun pendingSession(): GateCSessionRef?
    suspend fun refresh(): Boolean
    suspend fun clearLocal(userId: UserId?)
}

/** One-account Proton Core session bridge; token material never crosses this class's public API. */
internal class ProtonCoreSessionCoordinator(
    private val accountRepository: AccountRepository,
    private val addressDao: AddressDao? = null,
    private val owner: DurableAccountOwner? = null,
) : SessionProvider, SessionListener, GateCSessionLifecycle {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val transientSessions = ConcurrentHashMap<String, Session>()
    // Protect repository reads/writes and cache admission together. Never hold across HTTP.
    private val sessionStateLock = Mutex()
    private var sessionGeneration = 0L
    private val invalidatedSessionIds = mutableSetOf<String>()
    private val sessionCleanupGenerations = mutableSetOf<Long>()
    private val refreshFlightsLock = Mutex()
    private val refreshFlights = mutableMapOf<String, CompletableDeferred<Boolean>>()
    private val loginAttemptLock = Mutex()
    private val mutableSessionRevision = MutableStateFlow(0L)
    val sessionRevision = mutableSessionRevision.asStateFlow()
    private var sessionChanged: (() -> Unit)? = null

    fun bindSessionChanged(listener: () -> Unit) { check(sessionChanged == null); sessionChanged = listener }

    private fun publishSessionChanged() {
        mutableSessionRevision.value = Math.incrementExact(mutableSessionRevision.value)
        sessionChanged?.invoke()
    }

    @Volatile
    private var authRepository: AuthRepository? = null

    @Volatile
    private var forcedLogoutCleanup: (suspend (UserId?) -> Unit)? = null

    fun bindAuthRepository(repository: AuthRepository) {
        check(authRepository == null)
        authRepository = repository
    }

    fun bindForcedLogoutCleanup(cleanup: suspend (UserId?) -> Unit) {
        check(forcedLogoutCleanup == null)
        forcedLogoutCleanup = cleanup
    }

    override suspend fun beginLoginAttempt(): Boolean {
        if (!loginAttemptLock.tryLock()) return false
        return try {
            if (sessionStateLock.withLock {
                    if (sessionCleanupGenerations.isNotEmpty() || accountRepository.getAccounts().first().isNotEmpty()) {
                        false
                    } else {
                        // Recover a crash between the empty-partition reservation and Core persistence.
                        owner?.releaseAbandonedReservation()
                        true
                    }
                }) {
                true
            } else {
                loginAttemptLock.unlock()
                false
            }
        } catch (error: Throwable) {
            loginAttemptLock.unlock()
            throw error
        }
    }

    override suspend fun endLoginAttempt() {
        if (loginAttemptLock.isLocked) loginAttemptLock.unlock()
    }

    override suspend fun persistLogin(sessionInfo: SessionInfo) = sessionStateLock.withLock {
        check(sessionCleanupGenerations.isEmpty())
        require(sessionInfo.sessionId.id !in invalidatedSessionIds)
        val existing = accountRepository.getAccounts().first()
        require(existing.isEmpty() || existing.single().userId == sessionInfo.userId)
        owner?.reserveForLogin(sessionInfo.userId, existingSession = existing.singleOrNull()?.userId == sessionInfo.userId)
        val secondFactorRequired = sessionInfo.secondFactor is SecondFactor.Enabled
        val account = Account(
            userId = sessionInfo.userId,
            username = sessionInfo.username,
            email = sessionInfo.username?.takeIf { '@' in it },
            state = if (sessionInfo.isTwoPassModeNeeded) AccountState.TwoPassModeNeeded else AccountState.NotReady,
            sessionId = sessionInfo.sessionId,
            sessionState = if (secondFactorRequired) {
                SessionState.SecondFactorNeeded
            } else {
                SessionState.Authenticated
            },
            details = AccountDetails(
                account = AccountMetadataDetails(System.currentTimeMillis(), emptyList()),
                session = SessionDetails(
                    initialEventId = sessionInfo.eventId,
                    requiredAccountType = AccountType.Internal,
                    secondFactorEnabled = secondFactorRequired,
                    twoPassModeEnabled = sessionInfo.isTwoPassModeNeeded,
                    passphrase = null,
                    password = null,
                    fido2AuthenticationOptionsJson = null,
                ),
            ),
        )
        val session = Session.Authenticated(
            userId = sessionInfo.userId,
            sessionId = sessionInfo.sessionId,
            accessToken = sessionInfo.accessToken,
            refreshToken = sessionInfo.refreshToken,
            scopes = sessionInfo.scopes,
        )
        accountRepository.createOrUpdateAccountSession(account, session)
        transientSessions[session.sessionId.id] = session
        publishSessionChanged()
    }

    override suspend fun completeSecondFactor(
        sessionId: SessionId,
        scopes: List<String>,
    ): GateCStoredSessionState {
        sessionStateLock.withLock {
            if (sessionId.id in invalidatedSessionIds) return GateCStoredSessionState.ABSENT
            val session = accountRepository.getSessionOrNull(sessionId) ?: return GateCStoredSessionState.ABSENT
            accountRepository.updateSessionScopes(sessionId, scopes)
            accountRepository.updateSessionState(sessionId, SessionState.Authenticated)
            transientSessions[sessionId.id] = session.withScopes(scopes)
        }
        return state()
    }

    override suspend fun activate(userId: UserId) {
        sessionStateLock.withLock {
            val account = accountRepository.getAccounts().first().singleOrNull()
            check(account != null && account.userId == userId && account.sessionId?.id !in invalidatedSessionIds)
            owner?.confirmActivation(userId)
            accountRepository.updateSessionState(userId, SessionState.Authenticated)
            accountRepository.updateAccountState(userId, AccountState.Ready)
            publishSessionChanged()
        }
    }

    override suspend fun state(): GateCStoredSessionState = sessionStateLock.withLock {
        val account = accountRepository.getAccounts().first().singleOrNull()
            ?: return@withLock GateCStoredSessionState.ABSENT
        if (account.sessionId?.id in invalidatedSessionIds) return@withLock GateCStoredSessionState.ABSENT
        requireStoredOwner(account)
        when {
            account.sessionState == SessionState.SecondFactorNeeded -> GateCStoredSessionState.SECOND_FACTOR_REQUIRED
            account.state == AccountState.TwoPassModeNeeded && account.sessionState == SessionState.Authenticated ->
                GateCStoredSessionState.MAILBOX_PASSWORD_REQUIRED
            account.state == AccountState.Ready && account.sessionState == SessionState.Authenticated ->
                GateCStoredSessionState.READY
            else -> GateCStoredSessionState.KEY_UNLOCK_REQUIRED
        }
    }

    override suspend fun currentUserId(): UserId? = accountRepository.getAccounts().first().singleOrNull()?.let {
        requireStoredOwner(it)
        it.userId
    }

    suspend fun hasStoredAccount(): Boolean = sessionStateLock.withLock { accountRepository.getAccounts().first().isNotEmpty() }

    private suspend fun requireStoredOwner(account: Account) {
        owner?.requireStoredSessionOwner(account.userId,
            ready = account.state == AccountState.Ready && account.sessionState == SessionState.Authenticated)
    }

    override suspend fun currentAccountAddress(): String? {
        val account = accountRepository.getAccounts().first().singleOrNull() ?: return null
        requireStoredOwner(account)
        val addresses = addressDao
            ?.getByUserId(account.userId)
            ?.map { GateCDisplayAddress(it.email, it.enabled, it.order) }
            .orEmpty()
        return selectCurrentAccountAddress(addresses, account.email, account.username)
    }

    override suspend fun currentSessionId(): SessionId? =
        accountRepository.getAccounts().first().singleOrNull()?.sessionId

    override suspend fun pendingSession(): GateCSessionRef? {
        val account = accountRepository.getAccounts().first().singleOrNull() ?: return null
        if (account.state == AccountState.Ready && account.sessionState == SessionState.Authenticated) return null
        val sessionId = account.sessionId ?: return null
        return GateCSessionRef(account.userId, sessionId)
    }

    override suspend fun refresh(): Boolean {
        val sessionId = currentSessionId() ?: return false
        val session = getSession(sessionId) ?: return false
        return refreshSingleFlight(session)
    }

    private suspend fun refreshSingleFlight(session: Session): Boolean {
        val sessionId = session.sessionId
        var leader = false
        val flight = refreshFlightsLock.withLock {
            refreshFlights[sessionId.id] ?: CompletableDeferred<Boolean>().also {
                refreshFlights[sessionId.id] = it
                leader = true
            }
        }
        if (!leader) return flight.await()

        return try {
            val refreshed = withLock(sessionId) { refreshSessionStrict(session) }
            flight.complete(refreshed)
            refreshed
        } catch (error: Throwable) {
            if (error is Exception && error.toContactGatewayFailure() == GatewayFailureCategory.AUTHENTICATION_REQUIRED &&
                getSession(session.sessionId) != null) {
                try {
                    withContext(NonCancellable) { cleanupRejectedSession(session) }
                } catch (cleanupError: Throwable) {
                    if (cleanupError !== error) error.addSuppressed(cleanupError)
                }
            }
            flight.completeExceptionally(error)
            throw error
        } finally {
            withContext(NonCancellable) {
                refreshFlightsLock.withLock {
                    if (refreshFlights[sessionId.id] === flight) refreshFlights.remove(sessionId.id)
                }
            }
        }
    }

    private suspend fun refreshSessionStrict(session: Session): Boolean {
        val generation = sessionStateLock.withLock {
            if (session.sessionId.id in invalidatedSessionIds) return false
            sessionGeneration
        }
        val repository = checkNotNull(authRepository)
        val refreshed = repository.refreshSession(session).valueOrThrow
        validateSessionIdentity(session, refreshed)
        return refreshPersistedSession(refreshed, generation)
    }

    private suspend fun cleanupRejectedSession(session: Session) {
        invalidateAndCleanupSession(session)
    }

    override suspend fun clearLocal(userId: UserId?) = sessionStateLock.withLock {
        sessionGeneration++
        fun belongsToTarget(session: Session) = userId == null ||
            (session as? Session.Authenticated)?.userId == userId
        val transientTargets = transientSessions.values.filter(::belongsToTarget).map { it.sessionId.id }
        invalidatedSessionIds += transientTargets
        try {
            invalidatedSessionIds += accountRepository.getSessions().first().filter(::belongsToTarget).map { it.sessionId.id }
            if (userId != null) {
                accountRepository.deleteAccount(userId)
            } else {
                accountRepository.getAccounts().first().forEach { accountRepository.deleteAccount(it.userId) }
            }
            if (accountRepository.getAccounts().first().isEmpty()) owner?.releaseAbandonedReservation()
        } finally {
            transientTargets.forEach { transientSessions.remove(it) }
            publishSessionChanged()
        }
    }

    override suspend fun getSession(sessionId: SessionId?): Session? = sessionStateLock.withLock {
        when {
            sessionId == null -> transientSessions.values.firstOrNull { it is Session.Unauthenticated }
            sessionId.id in invalidatedSessionIds -> null
            else -> transientSessions[sessionId.id]
                ?: accountRepository.getSessionOrNull(sessionId)?.also { transientSessions[sessionId.id] = it }
        }
    }

    override suspend fun getSessions(): List<Session> = sessionStateLock.withLock {
        val persisted = accountRepository.getSessions().first().filterNot { it.sessionId.id in invalidatedSessionIds }
        persisted.forEach { transientSessions[it.sessionId.id] = it }
        (persisted + transientSessions.values.filterIsInstance<Session.Unauthenticated>())
            .distinctBy { it.sessionId }
    }

    override suspend fun getSessionId(userId: UserId?): SessionId? =
        accountRepository.getSessionIdOrNull(userId)

    override suspend fun getUserId(sessionId: SessionId): UserId? =
        (getSession(sessionId) as? Session.Authenticated)?.userId

    override suspend fun <T> withLock(sessionId: SessionId?, action: suspend () -> T): T =
        locks.getOrPut(sessionId?.id ?: GLOBAL_LOCK) { Mutex() }.withLock { action() }

    override suspend fun requestSession(): Boolean {
        val generation = sessionStateLock.withLock { sessionGeneration }
        val repository = authRepository ?: return false
        val session = repository.requestSession().valueOrNull ?: return false
        return sessionStateLock.withLock {
            if (generation != sessionGeneration || session.sessionId.id in invalidatedSessionIds) return false
            transientSessions[session.sessionId.id] = session
            true
        }
    }

    override suspend fun refreshSession(session: Session): Boolean {
        if (authRepository == null) return false
        return try {
            refreshSingleFlight(session)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun onSessionTokenCreated(userId: UserId?, session: Session) = sessionStateLock.withLock {
        if (session.sessionId.id in invalidatedSessionIds) return@withLock
        val normalized = if (userId != null && session !is Session.Authenticated) {
            Session.Authenticated(userId, session)
        } else {
            session
        }
        transientSessions[normalized.sessionId.id] = normalized
    }

    override suspend fun onSessionTokenRefreshed(session: Session) {
        refreshPersistedSession(session)
    }

    override suspend fun onSessionScopesRefreshed(sessionId: SessionId, scopes: List<String>) {
        val session = getSession(sessionId) ?: return
        refreshPersistedSession(session.withScopes(scopes))
    }

    override suspend fun onSessionForceLogout(session: Session, httpCode: Int) {
        invalidateAndCleanupSession(session)
    }

    /**
     * Claim the exact failing session before yielding to key cleanup. Login admission stays closed
     * until that cleanup finishes, even if an explicit reauthentication clears Core records first.
     * No repository/session mutex is held while the cleanup callback runs.
     */
    private suspend fun invalidateAndCleanupSession(failed: Session) = withContext(NonCancellable) {
        val cleanup = sessionStateLock.withLock {
            if (failed.sessionId.id in invalidatedSessionIds) return@withLock null
            val current = accountRepository.getSessionOrNull(failed.sessionId)
                ?: transientSessions[failed.sessionId.id] ?: return@withLock null
            // A delayed callback for an older token must not revoke a refreshed session.
            if (current.accessToken != failed.accessToken || current.refreshToken != failed.refreshToken) return@withLock null
            validateSessionIdentity(current, failed)
            sessionGeneration = Math.incrementExact(sessionGeneration)
            val generation = sessionGeneration
            sessionCleanupGenerations += generation
            invalidatedSessionIds += failed.sessionId.id
            transientSessions.remove(failed.sessionId.id)
            publishSessionChanged()
            generation to (current as? Session.Authenticated)?.userId
        } ?: return@withContext
        try {
            forcedLogoutCleanup?.invoke(cleanup.second)
        } finally {
            sessionStateLock.withLock {
                try {
                    // clearLocal/reauthentication may already have removed it. Never delete a
                    // replacement account session or invalidate its tokens from this callback.
                    val currentAccount = accountRepository.getAccounts().first().singleOrNull()
                    if (currentAccount?.sessionId == failed.sessionId && currentAccount.userId == cleanup.second) {
                        accountRepository.deleteAccount(currentAccount.userId)
                    }
                    if (accountRepository.getAccounts().first().isEmpty()) owner?.releaseAbandonedReservation()
                } finally {
                    sessionCleanupGenerations -= cleanup.first
                    transientSessions.remove(failed.sessionId.id)
                    publishSessionChanged()
                }
            }
        }
    }

    private suspend fun refreshPersistedSession(session: Session, generation: Long? = null): Boolean {
        var identityFailureTarget: Session? = null
        try {
            return sessionStateLock.withLock {
                if ((generation != null && generation != sessionGeneration) ||
                    session.sessionId.id in invalidatedSessionIds) return@withLock false
                val persisted = accountRepository.getSessionOrNull(session.sessionId)
                val previous = persisted ?: transientSessions[session.sessionId.id] ?: return@withLock false
                identityFailureTarget = previous
                validateSessionIdentity(previous, session)
                if (persisted != null) {
                    accountRepository.updateSessionToken(session.sessionId, session.accessToken, session.refreshToken)
                    accountRepository.updateSessionScopes(session.sessionId, session.scopes)
                }
                transientSessions[session.sessionId.id] = session
                publishSessionChanged()
                true
            }
        } catch (error: ProtonSessionIdentityMismatch) {
            // Cleanup may itself call session APIs; it must run outside sessionStateLock.
            identityFailureTarget?.let { invalidateAndCleanupSession(it) }
            throw error
        }
    }

    private fun validateSessionIdentity(expected: Session, actual: Session) {
        if (actual.sessionId != expected.sessionId) throw ProtonSessionIdentityMismatch()
        when (expected) {
            is Session.Authenticated -> if (
                actual !is Session.Authenticated || actual.userId != expected.userId
            ) throw ProtonSessionIdentityMismatch()
            is Session.Unauthenticated -> if (actual is Session.Authenticated) {
                throw ProtonSessionIdentityMismatch()
            }
        }
    }

    private fun Session.withScopes(scopes: List<String>): Session = when (this) {
        is Session.Authenticated -> copy(scopes = scopes)
        is Session.Unauthenticated -> copy(scopes = scopes)
    }

    private companion object {
        const val GLOBAL_LOCK = "global"
    }
}

internal class ProtonSessionIdentityMismatch : IllegalStateException()
