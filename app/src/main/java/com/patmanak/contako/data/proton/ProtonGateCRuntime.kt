package com.patmanak.contako.data.proton

import android.content.Context
import android.database.SQLException
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.AuthenticationState
import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.data.gateway.GatewayOutcome
import com.patmanak.contako.data.gateway.OperationSecret
import com.patmanak.contako.data.gateway.ProtonAuthenticationGateway
import com.patmanak.contako.data.gateway.ProtonSessionGateway
import com.patmanak.contako.data.gateway.SecondFactorMethod
import com.patmanak.contako.data.gateway.SessionState
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.Optional
import kotlin.math.ceil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.proton.core.account.data.repository.AccountRepositoryImpl
import me.proton.core.accountrecovery.domain.repository.AccountRecoveryRepository
import me.proton.core.auth.data.repository.AuthRepositoryImpl
import me.proton.core.auth.domain.entity.ScopeInfo
import me.proton.core.auth.domain.entity.SecondFactor
import me.proton.core.auth.domain.entity.SessionInfo
import me.proton.core.auth.domain.repository.AuthRepository
import me.proton.core.auth.domain.usecase.LoginChallengeConfig
import me.proton.core.auth.domain.usecase.PerformSecondFactor
import me.proton.core.auth.domain.usecase.ValidateServerProof
import me.proton.core.auth.fido.domain.entity.SecondFactorProof
import me.proton.core.challenge.data.ChallengeManagerImpl
import me.proton.core.challenge.data.repository.ChallengeRepositoryImpl
import me.proton.core.challenge.domain.ChallengeManager
import me.proton.core.challenge.domain.useFlow
import me.proton.core.contact.data.api.ContactRemoteDataSourceImpl
import me.proton.core.crypto.android.context.AndroidCryptoContext
import me.proton.core.crypto.android.srp.GOpenPGPSrpCrypto
import me.proton.core.crypto.common.keystore.PlainByteArray
import me.proton.core.crypto.common.srp.SrpCrypto
import me.proton.core.domain.entity.Product
import me.proton.core.domain.entity.UserId
import me.proton.core.key.data.repository.KeySaltRepositoryImpl
import me.proton.core.key.data.repository.PrivateKeyRepositoryImpl
import me.proton.core.key.domain.entity.key.Key
import me.proton.core.label.data.remote.LabelRemoteDataSourceImpl
import me.proton.core.network.data.ApiProvider
import me.proton.core.network.domain.ApiException
import me.proton.core.network.domain.ApiResult
import me.proton.core.network.domain.session.SessionId
import me.proton.core.user.data.UserAddressKeySecretProvider
import me.proton.core.user.data.UserManagerImpl
import me.proton.core.user.data.repository.UserAddressRemoteDataSourceImpl
import me.proton.core.user.data.repository.UserAddressRepositoryImpl
import me.proton.core.user.data.repository.UserLocalDataSourceImpl
import me.proton.core.user.data.repository.UserRemoteDataSourceImpl
import me.proton.core.user.data.repository.UserRepositoryImpl
import me.proton.core.user.data.usecase.GenerateSignedKeyList
import me.proton.core.user.domain.SignedKeyListChangeListener
import me.proton.core.user.domain.UserManager
import me.proton.core.user.domain.repository.PassphraseRepository
import me.proton.core.auth.domain.usecase.sso.GetEncryptedSecret
import me.proton.core.util.kotlin.DefaultCoroutineScopeProvider
import me.proton.core.util.kotlin.DefaultDispatcherProvider

internal enum class GateCAuthDiagnosticClass {
    API_HTTP,
    API_PARSE,
    API_CONNECTION,
    ILLEGAL_STATE,
    ILLEGAL_ARGUMENT,
    STACK_OVERFLOW,
    LINKAGE,
    SECURITY,
    DB,
    NO_SUCH_ELEMENT,
    OTHER,
}

internal enum class GateCAuthPhase {
    BEGIN_LOGIN,
    SECRET_ENCODING,
    AUTH_INFO,
    SRP,
    PERFORM_LOGIN,
    PERSIST,
    UNLOCK,
}

internal data class GateCAuthDiagnosticEvent(
    val diagnosticClass: GateCAuthDiagnosticClass,
    val httpCode: Int? = null,
    val protonCode: Int? = null,
) {
    init {
        require(httpCode == null || httpCode in MIN_HTTP_CODE..MAX_HTTP_CODE)
        require(protonCode == null || protonCode in MIN_PROTON_CODE..MAX_PROTON_CODE)
    }

    private companion object {
        const val MIN_HTTP_CODE = 100
        const val MAX_HTTP_CODE = 599
        const val MIN_PROTON_CODE = 0
        const val MAX_PROTON_CODE = 999_999
    }
}

internal interface GateCAuthDiagnostic {
    fun onPhase(phase: GateCAuthPhase)
    fun onFailure(event: GateCAuthDiagnosticEvent)

    data object Disabled : GateCAuthDiagnostic {
        override fun onPhase(phase: GateCAuthPhase) = Unit
        override fun onFailure(event: GateCAuthDiagnosticEvent) = Unit
    }
}

internal interface GateCCoreOperations {
    suspend fun login(username: String, password: ByteArray): SessionInfo
    suspend fun secondFactor(sessionId: SessionId, code: String): ScopeInfo
    suspend fun unlock(userId: UserId, password: ByteArray): UserManager.UnlockResult
    suspend fun restoreProtectedUnlock(userId: UserId): UserManager.UnlockResult?
    suspend fun lockAndClear(userId: UserId)
    suspend fun revoke(sessionId: SessionId): Boolean
}

/**
 * SRP login equivalent to Proton Core PerformLogin, but accepts an erasable byte buffer.
 * All SRP and challenge operations remain maintained Proton Core primitives.
 */
internal fun interface GateCLoginOperation {
    suspend operator fun invoke(username: String, password: ByteArray): SessionInfo
}

internal class ErasableProtonLogin(
    private val authRepository: AuthRepository,
    private val srpCrypto: SrpCrypto,
    private val challengeManager: ChallengeManager,
    private val challengeConfig: LoginChallengeConfig,
    private val diagnostic: GateCAuthDiagnostic = GateCAuthDiagnostic.Disabled,
    private val clearHumanVerification: suspend () -> Unit = {},
) : GateCLoginOperation {
    override suspend operator fun invoke(username: String, password: ByteArray): SessionInfo {
        return try {
            emitGateCAuthPhase(diagnostic, GateCAuthPhase.AUTH_INFO)
            val info = authRepository.getAuthInfoSrp(sessionId = null, username = username)
            emitGateCAuthPhase(diagnostic, GateCAuthPhase.SRP)
            val proofs = srpCrypto.generateSrpProofs(
                username = username,
                password = password,
                version = info.version.toLong(),
                salt = info.salt,
                modulus = info.modulus,
                serverEphemeral = info.serverEphemeral,
            )
            emitGateCAuthPhase(diagnostic, GateCAuthPhase.PERFORM_LOGIN)
            challengeManager.useFlow(challengeConfig.flowName) { frames ->
                authRepository.performLogin(
                    frames = frames,
                    username = username,
                    srpProofs = proofs,
                    srpSession = info.srpSession,
                )
            }
        } finally {
            withContext(NonCancellable) { clearHumanVerification() }
        }
    }
}

internal class ProtonCoreGateCOperations(
    private val erasableLogin: GateCLoginOperation,
    private val performSecondFactor: PerformSecondFactor,
    private val authRepository: AuthRepository,
    private val userManager: UserManager,
    private val passphraseRepository: PassphraseRepository,
) : GateCCoreOperations {
    override suspend fun login(username: String, password: ByteArray): SessionInfo =
        try {
            erasableLogin.invoke(username, password)
        } finally {
            password.fill(0)
        }

    override suspend fun secondFactor(sessionId: SessionId, code: String): ScopeInfo =
        performSecondFactor(sessionId, SecondFactorProof.SecondFactorCode(code))

    override suspend fun unlock(userId: UserId, password: ByteArray): UserManager.UnlockResult {
        return try {
            userManager.getUser(userId, refresh = true)
            userManager.getAddresses(userId, refresh = true)
            PlainByteArray(password).use { plain ->
                userManager.unlockWithPassword(userId, plain, refreshKeySalts = true)
            }
        } finally {
            password.fill(0)
        }
    }

    override suspend fun restoreProtectedUnlock(userId: UserId): UserManager.UnlockResult? =
        passphraseRepository.getPassphrase(userId)?.let { userManager.unlockWithPassphrase(userId, it) }

    override suspend fun lockAndClear(userId: UserId) {
        try {
            userManager.lock(userId)
        } finally {
            passphraseRepository.clearPassphrase(userId)
        }
    }

    override suspend fun revoke(sessionId: SessionId): Boolean =
        authRepository.revokeSession(sessionId, revokeAuthDevice = false)
}

internal class ProtonCoreAuthenticationAdapter(
    private val expectedAccount: AccountScope,
    private val core: GateCCoreOperations,
    private val sessions: GateCSessionLifecycle,
    private val diagnostic: GateCAuthDiagnostic = GateCAuthDiagnostic.Disabled,
    private val pendingLoginPassword: PendingLoginPassword = PendingLoginPassword(),
) : ProtonAuthenticationGateway {
    override suspend fun signIn(
        account: AccountScope,
        username: OperationSecret,
        password: OperationSecret,
    ): GatewayOutcome<AuthenticationState> {
        if (account != expectedAccount) {
            username.close()
            password.close()
            return GatewayOutcome.Failure(GatewayFailureCategory.AUTHENTICATION_REQUIRED)
        }
        emitGateCAuthPhase(diagnostic, GateCAuthPhase.BEGIN_LOGIN)
        val admitted = try {
            sessions.beginLoginAttempt()
        } catch (cancellation: CancellationException) {
            username.close()
            password.close()
            throw cancellation
        } catch (error: Exception) {
            username.close()
            password.close()
            emitGateCAuthDiagnostic(diagnostic, error)
            return GatewayOutcome.Failure(classifyGateCFailure(error))
        } catch (fatal: Error) {
            username.close()
            password.close()
            throw fatal
        }
        if (!admitted) {
            username.close()
            password.close()
            return GatewayOutcome.Failure(GatewayFailureCategory.ACCOUNT_ALREADY_CONNECTED)
        }

        var attempt: GateCSessionRef? = null
        pendingLoginPassword.clear()
        return try {
            val result = try {
                executeSanitized(diagnostic) {
                    username.consumeSuspend { usernameChars ->
                        password.consumeSuspend { passwordChars ->
                            val usernameValue = String(usernameChars)
                            emitGateCAuthPhase(diagnostic, GateCAuthPhase.SECRET_ENCODING)
                            val loginPassword = passwordChars.toUtf8Bytes()
                            val sessionInfo = try {
                                core.login(usernameValue, loginPassword)
                            } finally {
                                loginPassword.fill(0)
                            }
                            attempt = GateCSessionRef(sessionInfo.userId, sessionInfo.sessionId)
                            emitGateCAuthPhase(diagnostic, GateCAuthPhase.PERSIST)
                            sessions.persistLogin(sessionInfo)
                            authenticationStateAfterLogin(sessionInfo, passwordChars)
                        }
                    }
                }
            } catch (cancellation: CancellationException) {
                pendingLoginPassword.clear()
                attempt?.let {
                    cleanupGateCSession(core, sessions, it.userId, it.sessionId, revokeRemote = true)
                }
                throw cancellation
            } catch (fatal: Error) {
                pendingLoginPassword.clear()
                attempt?.let {
                    cleanupGateCSession(core, sessions, it.userId, it.sessionId, revokeRemote = true)
                }
                throw fatal
            }
            val terminalUnsupported =
                (result as? GatewayOutcome.Success)?.value == AuthenticationState.SecurityKeyOnlyUnsupported
            if (result is GatewayOutcome.Failure) {
                pendingLoginPassword.clear()
                attempt?.let {
                    cleanupGateCSession(core, sessions, it.userId, it.sessionId, revokeRemote = true)
                }
            }
            if (terminalUnsupported) {
                val cleanupFailure = attempt?.let {
                    cleanupGateCSession(core, sessions, it.userId, it.sessionId, revokeRemote = true)
                }
                if (cleanupFailure != null) GatewayOutcome.Failure(cleanupFailure) else result
            } else {
                result
            }
        } finally {
            withContext(NonCancellable) { sessions.endLoginAttempt() }
        }
    }

    override suspend fun submitSecondFactor(
        account: AccountScope,
        code: OperationSecret,
    ): GatewayOutcome<AuthenticationState> {
        if (account != expectedAccount) {
            code.close()
            return GatewayOutcome.Failure(GatewayFailureCategory.AUTHENTICATION_REQUIRED)
        }
        return try {
            var attemptedSessionId: SessionId? = null
            val outcome = executeSanitized {
                val sessionId = sessions.currentSessionId()
                    ?: return@executeSanitized GatewayOutcome.Failure(GatewayFailureCategory.AUTHENTICATION_REQUIRED)
                attemptedSessionId = sessionId
                val storedState = code.consumeSuspend { chars ->
                    require(chars.size <= MAX_SECOND_FACTOR_CODE_CHARS)
                    val scopeInfo = core.secondFactor(sessionId, String(chars))
                    sessions.completeSecondFactor(sessionId, scopeInfo.scopes)
                }
                when (storedState) {
                    GateCStoredSessionState.MAILBOX_PASSWORD_REQUIRED -> {
                        pendingLoginPassword.clear()
                        GatewayOutcome.Success(AuthenticationState.MailboxPasswordRequired)
                    }
                    GateCStoredSessionState.KEY_UNLOCK_REQUIRED -> {
                        val userId = sessions.currentUserId()
                        val retained = pendingLoginPassword.take(sessionId)
                        if (retained == null || userId == null) {
                            retained?.close()
                            GatewayOutcome.Success(AuthenticationState.KeyUnlockRequired)
                        } else retained.consumeSuspend { chars -> unlockAfterLogin(userId, chars) }
                    }
                    GateCStoredSessionState.ABSENT,
                    GateCStoredSessionState.SECOND_FACTOR_REQUIRED,
                    GateCStoredSessionState.READY,
                    -> error("Unexpected post-second-factor session state")
                }
            }
            if (outcome is GatewayOutcome.Failure &&
                outcome.category == GatewayFailureCategory.AUTHENTICATION_REQUIRED &&
                attemptedSessionId != null
            ) {
                pendingLoginPassword.clear()
                val userId = tryOrNull { sessions.currentUserId() }
                val cleanupFailure = cleanupGateCSession(
                    core,
                    sessions,
                    userId,
                    attemptedSessionId,
                    revokeRemote = false,
                )
                if (cleanupFailure != null) GatewayOutcome.Failure(cleanupFailure) else outcome
            } else {
                outcome
            }
        } catch (cancellation: CancellationException) {
            pendingLoginPassword.clear()
            throw cancellation
        } finally {
            code.close()
        }
    }

    override suspend fun cancel(account: AccountScope) {
        if (account == expectedAccount) {
            pendingLoginPassword.clear()
            val target = withContext(NonCancellable) {
                var userId: UserId? = null
                var sessionId: SessionId? = null
                try {
                    sessions.pendingSession()?.also {
                        userId = it.userId
                        sessionId = it.sessionId
                    }
                } catch (_: Exception) {
                    // Fall through to the current account lookup; local clear still remains mandatory.
                }
                if (userId == null) userId = tryOrNull { sessions.currentUserId() }
                if (sessionId == null) sessionId = tryOrNull { sessions.currentSessionId() }
                userId to sessionId
            }
            cleanupGateCSession(
                core,
                sessions,
                target.first,
                target.second,
                revokeRemote = true,
            )
        }
    }

    private suspend fun authenticationStateAfterLogin(
        sessionInfo: SessionInfo,
        passwordChars: CharArray,
    ): GatewayOutcome<AuthenticationState> {
        val enabled = sessionInfo.secondFactor as? SecondFactor.Enabled
        if (enabled != null) {
            val methods = buildSet {
                if (me.proton.core.auth.domain.entity.SecondFactorMethod.Totp in enabled.supportedMethods) {
                    add(SecondFactorMethod.CODE)
                }
                if (me.proton.core.auth.domain.entity.SecondFactorMethod.Authenticator in enabled.supportedMethods) {
                    add(SecondFactorMethod.SECURITY_KEY)
                }
            }
            if (!sessionInfo.isTwoPassModeNeeded && SecondFactorMethod.CODE in methods) {
                pendingLoginPassword.retain(sessionInfo.sessionId, passwordChars)
            }
            return GatewayOutcome.Success(AuthenticationState.SecondFactorRequired.fromOfferedMethods(methods))
        }
        if (sessionInfo.isTwoPassModeNeeded) {
            return GatewayOutcome.Success(AuthenticationState.MailboxPasswordRequired)
        }
        return unlockAfterLogin(sessionInfo.userId, passwordChars)
    }

    private suspend fun unlockAfterLogin(userId: UserId, passwordChars: CharArray): GatewayOutcome<AuthenticationState> {
        val unlockPassword = passwordChars.toUtf8Bytes()
        val unlockResult = try {
            emitGateCAuthPhase(diagnostic, GateCAuthPhase.UNLOCK)
            core.unlock(userId, unlockPassword)
        } finally {
            unlockPassword.fill(0)
        }
        return when (unlockResult) {
            UserManager.UnlockResult.Success -> {
                sessions.activate(userId)
                GatewayOutcome.Success(AuthenticationState.Ready)
            }
            is UserManager.UnlockResult.Error -> GatewayOutcome.Success(AuthenticationState.KeyUnlockRequired)
        }
    }

    private companion object {
        const val MAX_SECOND_FACTOR_CODE_CHARS = 128
    }
}

internal class ProtonCoreSessionAdapter(
    private val expectedAccount: AccountScope,
    private val core: GateCCoreOperations,
    private val sessions: GateCSessionLifecycle,
) : ProtonSessionGateway, com.patmanak.contako.data.gateway.ProtonLocalSessionCleanupGateway {
    override suspend fun restore(account: AccountScope): GatewayOutcome<SessionState> = scoped(account) {
        restoredState()
    }

    override suspend fun refresh(account: AccountScope): GatewayOutcome<SessionState> = scoped(account) {
        if (!sessions.refresh()) {
            SessionState.AUTHENTICATION_REQUIRED
        } else {
            restoredState()
        }
    }

    private suspend fun restoredState(): SessionState {
        return when (sessions.state()) {
            GateCStoredSessionState.ABSENT -> SessionState.AUTHENTICATION_REQUIRED
            GateCStoredSessionState.SECOND_FACTOR_REQUIRED -> {
                // A second-factor proof is deliberately operation-scoped and cannot survive
                // process death. Remove the incomplete persisted login before exposing sign-in
                // again, otherwise the one-account admission guard would reject every retry.
                val userId = tryOrNull { sessions.currentUserId() }
                sessions.clearLocal(userId)
                SessionState.AUTHENTICATION_REQUIRED
            }
            GateCStoredSessionState.MAILBOX_PASSWORD_REQUIRED ->
                SessionState.INTERACTIVE_MAILBOX_PASSWORD_REQUIRED
            GateCStoredSessionState.KEY_UNLOCK_REQUIRED -> SessionState.INTERACTIVE_KEY_UNLOCK_REQUIRED
            GateCStoredSessionState.READY -> {
                val userId = sessions.currentUserId() ?: return SessionState.AUTHENTICATION_REQUIRED
                val restored = try {
                    core.restoreProtectedUnlock(userId)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    lockAndClearGateCUser(core, userId)
                    null
                }
                when (restored) {
                    UserManager.UnlockResult.Success -> SessionState.READY
                    else -> SessionState.INTERACTIVE_KEY_UNLOCK_REQUIRED
                }
            }
        }
    }

    override suspend fun unlockKeys(
        account: AccountScope,
        password: OperationSecret,
    ): GatewayOutcome<SessionState> {
        if (account != expectedAccount) {
            password.close()
            return GatewayOutcome.Failure(GatewayFailureCategory.AUTHENTICATION_REQUIRED)
        }
        val userId = try {
            sessions.currentUserId()
        } catch (cancellation: CancellationException) {
            password.close()
            throw cancellation
        } catch (error: Exception) {
            password.close()
            cleanupGateCSession(core, sessions, null, null, revokeRemote = false)
            return GatewayOutcome.Failure(classifyGateCFailure(error))
        } catch (fatal: Error) {
            password.close()
            throw fatal
        }
        if (userId == null) {
            password.close()
            cleanupGateCSession(core, sessions, null, null, revokeRemote = false)
            return GatewayOutcome.Failure(GatewayFailureCategory.AUTHENTICATION_REQUIRED)
        }

        return try {
            val outcome = executeSanitized {
                val result = password.consumeSuspend { chars ->
                    val bytes = chars.toUtf8Bytes()
                    try {
                        core.unlock(userId, bytes)
                    } finally {
                        bytes.fill(0)
                    }
                }
                when (result) {
                    UserManager.UnlockResult.Success -> {
                        sessions.activate(userId)
                        GatewayOutcome.Success(SessionState.READY)
                    }
                    is UserManager.UnlockResult.Error ->
                        GatewayOutcome.Failure(GatewayFailureCategory.VALIDATION_REJECTED)
                }
            }
            if (outcome is GatewayOutcome.Failure) {
                lockAndClearGateCUser(core, userId)
            }
            outcome
        } catch (cancellation: CancellationException) {
            lockAndClearGateCUser(core, userId)
            throw cancellation
        } catch (fatal: Error) {
            lockAndClearGateCUser(core, userId)
            throw fatal
        }
    }

    override suspend fun lockKeys(account: AccountScope): GatewayOutcome<Unit> {
        if (account != expectedAccount) return GatewayOutcome.Failure(GatewayFailureCategory.AUTHENTICATION_REQUIRED)
        return executeSanitized {
            sessions.currentUserId()?.let { core.lockAndClear(it) }
            GatewayOutcome.Success(Unit)
        }
    }

    override suspend fun clearLocal(account: AccountScope): GatewayOutcome<Unit> {
        if (account != expectedAccount) return GatewayOutcome.Failure(GatewayFailureCategory.AUTHENTICATION_REQUIRED)
        var discoveryFailure: GatewayFailureCategory? = null
        val target = withContext(NonCancellable) {
            val userId = try {
                sessions.currentUserId()
            } catch (error: Exception) {
                discoveryFailure = classifyGateCFailure(error)
                null
            }
            val sessionId = try {
                sessions.currentSessionId()
            } catch (error: Exception) {
                discoveryFailure = classifyGateCFailure(error)
                null
            }
            userId to sessionId
        }
        val cleanup = cleanupGateCSession(
            core,
            sessions,
            target.first,
            target.second,
            revokeRemote = false,
        ) ?: discoveryFailure
        return cleanup?.let { GatewayOutcome.Failure(it) } ?: GatewayOutcome.Success(Unit)
    }

    override suspend fun clearAfterBestEffortRevocation(account: AccountScope): GatewayOutcome<Unit> =
        revokeAndClear(account, bestEffort = true)

    override suspend fun revokeAndClear(account: AccountScope): GatewayOutcome<Unit> =
        revokeAndClear(account, bestEffort = false)

    private suspend fun revokeAndClear(account: AccountScope, bestEffort: Boolean): GatewayOutcome<Unit> {
        if (account != expectedAccount) return GatewayOutcome.Failure(GatewayFailureCategory.AUTHENTICATION_REQUIRED)
        var discoveryFailure: GatewayFailureCategory? = null
        val target = withContext(NonCancellable) {
            val userId = try {
                sessions.currentUserId()
            } catch (error: Exception) {
                discoveryFailure = classifyGateCFailure(error)
                null
            }
            val sessionId = try {
                sessions.currentSessionId()
            } catch (error: Exception) {
                discoveryFailure = classifyGateCFailure(error)
                null
            }
            userId to sessionId
        }
        val cleanup = cleanupGateCSession(
            core,
            sessions,
            target.first,
            target.second,
            revokeRemote = true,
            bestEffortRevocation = bestEffort,
        ) ?: discoveryFailure
        return when (cleanup) {
            null ->
                GatewayOutcome.Success(Unit)
            else -> GatewayOutcome.Failure(cleanup)
        }
    }

    private suspend fun scoped(
        account: AccountScope,
        block: suspend () -> SessionState,
    ): GatewayOutcome<SessionState> = if (account != expectedAccount) {
        GatewayOutcome.Failure(GatewayFailureCategory.AUTHENTICATION_REQUIRED)
    } else {
        executeSanitized { GatewayOutcome.Success(block()) }
    }
}

internal class ProtonGateCRuntime private constructor(
    val apiProvider: ApiProvider,
    val accountScope: AccountScope,
    val authentication: ProtonAuthenticationGateway,
    val session: ProtonSessionGateway,
    val gateD: ProtonGateDComposition,
    /**
     * Address of the connected Proton account, or `null` when none is connected.
     *
     * Exposed here rather than on [ProtonSessionGateway] so the gateway keeps its narrow
     * session-lifecycle surface. Used as the Android account name under `D-081`.
     */
    val currentAccountAddress: suspend () -> String?,
) {
    companion object {
        @Volatile
        private var instance: ProtonGateCRuntime? = null

        /** Explicit maintained-Core product identity used at every runtime composition point. */
        internal val PRODUCT_IDENTITY: Product = Product.Mail

        fun create(
            context: Context,
            humanVerification: GateCHumanVerificationHooks = GateCHumanVerificationHooks.FailClosed,
            authDiagnostic: GateCAuthDiagnostic = GateCAuthDiagnostic.Disabled,
            updateFailureObserver: ProtonContactUpdateFailureObserver = ProtonContactUpdateFailureObserver { _, _, _, _ -> },
        ): ProtonGateCRuntime = instance ?: synchronized(this) {
            instance ?: build(
                context.applicationContext,
                humanVerification,
                GateCRequestAudit.Disabled,
                authDiagnostic,
                updateFailureObserver,
            ).also { instance = it }
        }

        /**
         * Instrumented Gate C entry point. It deliberately fails if production code already built
         * the singleton, because silently losing the bounded request audit would invalidate the
         * live qualification.
         */
        internal fun createForInstrumentedGateC(
            context: Context,
            requestAudit: GateCRequestAudit,
            authDiagnostic: GateCAuthDiagnostic,
            humanVerification: GateCHumanVerificationHooks = GateCHumanVerificationHooks.FailClosed,
        ): ProtonGateCRuntime = synchronized(this) {
            check(instance == null) { "GATE_C_RUNTIME_ALREADY_INITIALIZED" }
            check(requestAudit !== GateCRequestAudit.Disabled) { "GATE_C_REQUEST_AUDIT_REQUIRED" }
            check(authDiagnostic !== GateCAuthDiagnostic.Disabled) { "GATE_C_AUTH_DIAGNOSTIC_REQUIRED" }
            build(
                context.applicationContext,
                humanVerification,
                requestAudit,
                authDiagnostic,
            ).also { instance = it }
        }

        private fun build(
            context: Context,
            humanVerification: GateCHumanVerificationHooks,
            requestAudit: GateCRequestAudit,
            authDiagnostic: GateCAuthDiagnostic = GateCAuthDiagnostic.Disabled,
            updateFailureObserver: ProtonContactUpdateFailureObserver = ProtonContactUpdateFailureObserver { _, _, _, _ -> },
        ): ProtonGateCRuntime {
            val cryptoContext = AndroidCryptoContext()
            val protectedStorage = GateCProtectedStorageGuard.requireAvailable(cryptoContext.keyStoreCrypto)
            val database = ProtonGateCDatabase.build(context, protectedStorage)
            val accountRepository = AccountRepositoryImpl(PRODUCT_IDENTITY, database, cryptoContext.keyStoreCrypto)
            val sessionCoordinator = ProtonCoreSessionCoordinator(
                accountRepository = accountRepository,
                addressDao = database.addressDao(),
            )
            val network = ProtonGateCNetworkFactory.build(
                context,
                sessionCoordinator,
                humanVerification,
                requestAudit,
            )
            val authRepository = AuthRepositoryImpl(
                provider = network.apiProvider,
                context = context,
                product = PRODUCT_IDENTITY,
                validateServerProof = ValidateServerProof(),
            )
            sessionCoordinator.bindAuthRepository(authRepository)
            val scopeProvider = DefaultCoroutineScopeProvider(DefaultDispatcherProvider())
            val userLocal = UserLocalDataSourceImpl(cryptoContext, database)
            val userRepository = UserRepositoryImpl(
                provider = network.apiProvider,
                context = context,
                product = PRODUCT_IDENTITY,
                validateServerProof = ValidateServerProof(),
                scopeProvider = scopeProvider,
                userLocalDataSource = userLocal,
                userRemoteDataSource = UserRemoteDataSourceImpl(network.apiProvider, userLocal),
            )
            val secretProvider = UserAddressKeySecretProvider(userRepository, cryptoContext)
            val userAddressRepository = UserAddressRepositoryImpl(
                db = database,
                userRepository = userRepository,
                userAddressRemoteDataSource = UserAddressRemoteDataSourceImpl(network.apiProvider, userLocal),
                userAddressKeySecretProvider = secretProvider,
                context = cryptoContext,
                scopeProvider = scopeProvider,
            )
            val keySaltRepository = KeySaltRepositoryImpl(database, network.apiProvider, scopeProvider)
            val userManager = UserManagerImpl(
                userRepository = userRepository,
                userAddressRepository = userAddressRepository,
                passphraseRepository = userRepository,
                keySaltRepository = keySaltRepository,
                privateKeyRepository = PrivateKeyRepositoryImpl(network.apiProvider, ValidateServerProof()),
                accountRecoveryRepository = UnsupportedGateCAccountRecovery,
                userAddressKeySecretProvider = secretProvider,
                cryptoContext = cryptoContext,
                generateSignedKeyList = GenerateSignedKeyList(cryptoContext),
                signedKeyListChangeListener = Optional.empty<SignedKeyListChangeListener>(),
                getEncryptedSecret = GetEncryptedSecret(cryptoContext),
            )
            val challengeManager = ChallengeManagerImpl(ChallengeRepositoryImpl(database))
            val operations = ProtonCoreGateCOperations(
                erasableLogin = ErasableProtonLogin(
                    authRepository,
                    GOpenPGPSrpCrypto(DefaultDispatcherProvider()),
                    challengeManager,
                    LoginChallengeConfig(),
                    authDiagnostic,
                    humanVerification.clear,
                ),
                performSecondFactor = PerformSecondFactor(authRepository),
                authRepository = authRepository,
                userManager = userManager,
                passphraseRepository = userRepository,
            )
            sessionCoordinator.bindForcedLogoutCleanup { userId ->
                if (userId != null) operations.lockAndClear(userId)
            }
            val accountScope = AccountScope("primary")
            val readyUserProvider = ProtonReadyUserProvider {
                if (sessionCoordinator.state() == GateCStoredSessionState.READY) {
                    sessionCoordinator.currentUserId()
                } else {
                    null
                }
            }
            val rawGateDWire = ProtonCoreGateDWireClient(
                expectedAccount = accountScope,
                userProvider = readyUserProvider,
                apiProvider = network.apiProvider,
            )
            val membershipReader = ProtonCoreAuthoritativeEmailGroupMembershipReader(
                expectedAccount = accountScope,
                transport = ProtonContactEmailPageWireTransport(rawGateDWire::getContactEmailPage),
            )
            val emailGroupAssignmentStages = ProtonEmailGroupAssignmentStageMonitor()
            val assignmentAdapter = ProtonEmailGroupAssignmentAdapter(
                accountScope,
                rawGateDWire,
                emailGroupAssignmentStages,
            )
            val vCardCodec = ProtonContactVCardCodec(
                ProtonContactFieldValidator(
                    ProtonPgpPublicKeyMaterialInspector(cryptoContext.pgpCrypto),
                ),
            )
            val contactCreateStages = ProtonContactCreateStageMonitor()
            val publicContactGateway = ProtonPublicContactGateway(
                expectedAccount = accountScope,
                userProvider = readyUserProvider,
                remote = ProtonCoreContactRemotePort(
                    ContactRemoteDataSourceImpl(network.apiProvider),
                    rawGateDWire,
                    contactCreateStages,
                    inventoryLoader = ProtonBoundedContactInventory(network.apiProvider)::read,
                ),
                cardCrypto = ProtonCoreContactCardCrypto(
                    ProtonCoreUnlockedKeyHolderContextProvider(userManager, cryptoContext),
                ),
                vCardCodec = vCardCodec,
                createStageObserver = contactCreateStages,
                updateFailureObserver = updateFailureObserver,
            )
            val richInventoryUnitStatus = ProtonRichInventoryUnitStatus.LIVE_VALIDATION_REQUIRED
            val groupCapabilities = AccountScopedContactGroupCapabilityCoordinator(
                expectedAccount = accountScope,
                delegate = ProtonPublicContactGroupGateway(accountScope, readyUserProvider, LabelRemoteDataSourceImpl(network.apiProvider)),
            )
            val gateD = ProtonGateDComposition(
                existence = publicContactGateway,
                inventory = publicContactGateway,
                verifiedCards = publicContactGateway,
                contactMutations = publicContactGateway,
                contactCreateStages = contactCreateStages,
                emailGroupAssignmentStages = emailGroupAssignmentStages,
                groups = groupCapabilities,
                emailLabels = groupCapabilities.assignments(ReconciledProtonEmailGroupAssignmentGateway(
                    membershipReader = membershipReader,
                    mutationGateway = assignmentAdapter,
                    stageObserver = emailGroupAssignmentStages,
                )),
                membershipReader = membershipReader,
                vCardCodec = vCardCodec,
                richInventoryUnitStatus = richInventoryUnitStatus,
            )
            return ProtonGateCRuntime(
                apiProvider = network.apiProvider,
                accountScope = accountScope,
                authentication = ProtonCoreAuthenticationAdapter(
                    accountScope,
                    operations,
                    sessionCoordinator,
                    authDiagnostic,
                ),
                session = ProtonCoreSessionAdapter(accountScope, operations, sessionCoordinator),
                gateD = gateD,
                currentAccountAddress = sessionCoordinator::currentAccountAddress,
            )
        }
    }
}

/**
 * Best-effort remote revocation followed by mandatory local key and session cleanup. Every stage
 * is attempted even if an earlier stage fails, and caller cancellation cannot interrupt cleanup.
 */
private suspend fun cleanupGateCSession(
    core: GateCCoreOperations,
    sessions: GateCSessionLifecycle,
    userId: UserId?,
    sessionId: SessionId?,
    revokeRemote: Boolean,
    bestEffortRevocation: Boolean = false,
): GatewayFailureCategory? = withContext(NonCancellable) {
    var failure: GatewayFailureCategory? = null
    if (revokeRemote && sessionId != null) {
        try {
            val revoked = if (bestEffortRevocation) {
                kotlinx.coroutines.withTimeoutOrNull(5_000L) { core.revoke(sessionId) } == true
            } else core.revoke(sessionId)
            if (!revoked) failure = GatewayFailureCategory.REMOTE_SERVICE_FAILURE
        } catch (error: Exception) {
            failure = classifyGateCFailure(error)
        }
    }
    if (bestEffortRevocation) failure = null
    if (userId != null) {
        try {
            core.lockAndClear(userId)
        } catch (error: Exception) {
            failure = classifyGateCFailure(error)
        }
    }
    try {
        sessions.clearLocal(userId)
    } catch (error: Exception) {
        failure = classifyGateCFailure(error)
    }
    failure
}

/** Clears only derived key material while preserving a still-valid Proton account/session. */
private suspend fun lockAndClearGateCUser(
    core: GateCCoreOperations,
    userId: UserId,
): GatewayFailureCategory? = withContext(NonCancellable) {
    try {
        core.lockAndClear(userId)
        null
    } catch (error: Exception) {
        classifyGateCFailure(error)
    }
}

private suspend fun <T> tryOrNull(block: suspend () -> T): T? = try {
    block()
} catch (_: Exception) {
    null
}

private suspend fun <T> executeSanitized(
    diagnostic: GateCAuthDiagnostic = GateCAuthDiagnostic.Disabled,
    block: suspend () -> GatewayOutcome<T>,
): GatewayOutcome<T> = try {
    block()
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (error: Exception) {
    emitGateCAuthDiagnostic(diagnostic, error)
    classifyGateCOutcomeFailure(error)
}

private fun emitGateCAuthDiagnostic(diagnostic: GateCAuthDiagnostic, error: Throwable) {
    try {
        diagnostic.onFailure(error.toGateCAuthDiagnostic())
    } catch (_: Exception) {
        Unit
    }
}

private fun emitGateCAuthPhase(diagnostic: GateCAuthDiagnostic, phase: GateCAuthPhase) {
    try {
        diagnostic.onPhase(phase)
    } catch (_: Exception) {
        Unit
    }
}

internal fun Throwable.toGateCAuthDiagnostic(): GateCAuthDiagnosticEvent {
    var current: Throwable? = this
    repeat(MAX_DIAGNOSTIC_CAUSE_DEPTH) {
        val inspected = current ?: return GateCAuthDiagnosticEvent(GateCAuthDiagnosticClass.OTHER)
        val aggregate = inspected.toDirectGateCAuthDiagnostic()
        if (aggregate.diagnosticClass != GateCAuthDiagnosticClass.OTHER) return aggregate
        val next = inspected.cause
        if (next == null || next === inspected) return aggregate
        current = next
    }
    return GateCAuthDiagnosticEvent(GateCAuthDiagnosticClass.OTHER)
}

private fun Throwable.toDirectGateCAuthDiagnostic(): GateCAuthDiagnosticEvent = when (this) {
    is ApiException -> when (val apiError = error) {
        is ApiResult.Error.Http -> GateCAuthDiagnosticEvent(
            diagnosticClass = GateCAuthDiagnosticClass.API_HTTP,
            httpCode = apiError.httpCode.takeIf { it in 100..599 },
            protonCode = apiError.proton?.code?.takeIf { it in 0..999_999 },
        )
        is ApiResult.Error.Parse -> GateCAuthDiagnosticEvent(GateCAuthDiagnosticClass.API_PARSE)
        is ApiResult.Error.Connection -> GateCAuthDiagnosticEvent(GateCAuthDiagnosticClass.API_CONNECTION)
    }
    is StackOverflowError -> GateCAuthDiagnosticEvent(GateCAuthDiagnosticClass.STACK_OVERFLOW)
    is LinkageError -> GateCAuthDiagnosticEvent(GateCAuthDiagnosticClass.LINKAGE)
    is SecurityException -> GateCAuthDiagnosticEvent(GateCAuthDiagnosticClass.SECURITY)
    is SQLException -> GateCAuthDiagnosticEvent(GateCAuthDiagnosticClass.DB)
    is NoSuchElementException -> GateCAuthDiagnosticEvent(GateCAuthDiagnosticClass.NO_SUCH_ELEMENT)
    is IllegalStateException -> GateCAuthDiagnosticEvent(GateCAuthDiagnosticClass.ILLEGAL_STATE)
    is IllegalArgumentException -> GateCAuthDiagnosticEvent(GateCAuthDiagnosticClass.ILLEGAL_ARGUMENT)
    else -> GateCAuthDiagnosticEvent(GateCAuthDiagnosticClass.OTHER)
}

private const val MAX_DIAGNOSTIC_CAUSE_DEPTH = 8

private fun classifyGateCOutcomeFailure(error: Throwable): GatewayOutcome.Failure {
    val retryAfterMillis = ((error as? ApiException)?.error as? ApiResult.Error.Http)
        ?.retryAfter
        ?.inWholeMilliseconds
        ?.coerceAtLeast(0)
    return GatewayOutcome.Failure(classifyGateCFailure(error), retryAfterMillis)
}

private fun classifyGateCFailure(error: Throwable): GatewayFailureCategory =
    if (error.containsHumanVerificationRequirement()) {
        GatewayFailureCategory.HUMAN_VERIFICATION_REQUIRED
    } else if (error.containsLocalRequestBudgetStop()) {
        GatewayFailureCategory.LOCAL_REQUEST_BUDGET_EXHAUSTED
    } else when (error) {
        is ApiException -> when (val apiError = error.error) {
            is ApiResult.Error.NoInternet -> GatewayFailureCategory.NETWORK_UNAVAILABLE
            is ApiResult.Error.Timeout -> GatewayFailureCategory.TIMEOUT
            is ApiResult.Error.Parse -> GatewayFailureCategory.MALFORMED_RESPONSE
            is ApiResult.Error.Connection -> GatewayFailureCategory.NETWORK_UNAVAILABLE
            is ApiResult.Error.Http -> when (apiError.httpCode) {
                401 -> GatewayFailureCategory.AUTHENTICATION_REQUIRED
                403 -> GatewayFailureCategory.PERMISSION_OR_PLAN_DENIED
                409 -> GatewayFailureCategory.CONFLICT
                422 -> GatewayFailureCategory.VALIDATION_REJECTED
                429 -> GatewayFailureCategory.RATE_LIMITED
                in 500..599 -> GatewayFailureCategory.REMOTE_SERVICE_FAILURE
                else -> GatewayFailureCategory.UNKNOWN
            }
        }
        is IOException -> GatewayFailureCategory.NETWORK_UNAVAILABLE
        is IllegalArgumentException -> GatewayFailureCategory.VALIDATION_REJECTED
        else -> GatewayFailureCategory.UNKNOWN
    }

private fun Throwable.containsLocalRequestBudgetStop(): Boolean {
    var current: Throwable? = this
    repeat(MAX_DIAGNOSTIC_CAUSE_DEPTH) {
        val inspected = current ?: return false
        if (inspected is GateCLocalRequestBudgetExceeded) return true
        current = inspected.cause?.takeUnless { it === inspected }
    }
    return false
}

private fun Throwable.containsHumanVerificationRequirement(): Boolean {
    var current: Throwable? = this
    repeat(MAX_DIAGNOSTIC_CAUSE_DEPTH) {
        val inspected = current ?: return false
        if (inspected is GateCHumanVerificationRequired) return true
        val next = inspected.cause
        if (next == null || next === inspected) return false
        current = next
    }
    return false
}

private fun CharArray.toUtf8Bytes(): ByteArray {
    val encoder = Charsets.UTF_8.newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    val owned = ByteArray(ceil(encoder.maxBytesPerChar() * size).toInt())
    return try {
        val output = ByteBuffer.wrap(owned)
        val encoded = encoder.encode(CharBuffer.wrap(this), output, true)
        if (encoded.isError) encoded.throwException()
        check(encoded.isUnderflow)
        val flushed = encoder.flush(output)
        if (flushed.isError) flushed.throwException()
        check(flushed.isUnderflow)
        owned.copyOf(output.position())
    } finally {
        owned.fill(0)
    }
}

private object UnsupportedGateCAccountRecovery : AccountRecoveryRepository {
    override suspend fun startRecovery(userId: UserId): Unit =
        throw UnsupportedOperationException()

    override suspend fun cancelRecoveryAttempt(
        srpProofs: me.proton.core.crypto.common.srp.SrpProofs,
        srpSession: String,
        userId: UserId,
    ): Unit = throw UnsupportedOperationException()

    override suspend fun resetPassword(
        sessionUserId: UserId,
        keySalt: String,
        userKeys: List<Key>?,
        auth: me.proton.core.crypto.common.srp.Auth?,
    ): Boolean = false
}
