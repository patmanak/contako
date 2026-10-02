package com.patmanak.contako.data.proton

import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.AuthenticationState
import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.data.gateway.GatewayOutcome
import com.patmanak.contako.data.gateway.OperationSecret
import com.patmanak.contako.data.gateway.SecondFactorMethod
import com.patmanak.contako.data.gateway.SessionState
import java.io.IOException
import java.lang.reflect.Proxy
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import me.proton.core.auth.domain.entity.Fido2Info
import me.proton.core.auth.domain.entity.ScopeInfo
import me.proton.core.auth.domain.entity.SecondFactor
import me.proton.core.auth.domain.entity.SessionInfo
import me.proton.core.auth.domain.repository.AuthRepository
import me.proton.core.auth.domain.usecase.PerformSecondFactor
import me.proton.core.domain.entity.UserId
import me.proton.core.network.domain.session.SessionId
import me.proton.core.network.domain.ApiException
import me.proton.core.network.domain.ApiResult
import me.proton.core.network.domain.client.ClientId
import me.proton.core.network.domain.humanverification.HumanVerificationAvailableMethods
import me.proton.core.user.domain.UserManager
import me.proton.core.user.domain.repository.PassphraseRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProtonGateCAdaptersTest {
    private val account = AccountScope("primary")

    @Test
    fun coreOperationsDelegatesLoginExactlyOnceAndZerosPassword() = runTest {
        var calls = 0
        var observedUsername: String? = null
        var observedPassword: ByteArray? = null
        val expected = sessionInfo()
        val login = GateCLoginOperation { username, password ->
            calls++
            observedUsername = username
            observedPassword = password
            expected
        }
        val authRepository = unusedProxy<AuthRepository>()
        val operations = ProtonCoreGateCOperations(
            erasableLogin = login,
            performSecondFactor = PerformSecondFactor(authRepository),
            authRepository = authRepository,
            userManager = unusedProxy(),
            passphraseRepository = unusedProxy(),
        )
        val password = "synthetic-password".encodeToByteArray()

        val result = operations.login("test-user", password)

        assertSame(expected, result)
        assertEquals(1, calls)
        assertEquals("test-user", observedUsername)
        assertSame(password, observedPassword)
        assertTrue(password.all { it == 0.toByte() })
    }

    @Test
    fun passwordLoginPersistsUnlocksActivatesAndZerosAllCallerBuffers() = runTest {
        val core = FakeCore()
        val sessions = FakeSessions()
        val gateway = ProtonCoreAuthenticationAdapter(account, core, sessions)
        val usernameSource = "test-user".toCharArray()
        val passwordSource = "synthetic-password".toCharArray()

        val outcome = gateway.signIn(
            account,
            OperationSecret.takeAndClear(usernameSource),
            OperationSecret.takeAndClear(passwordSource),
        )

        assertEquals(GatewayOutcome.Success(AuthenticationState.Ready), outcome)
        assertTrue(usernameSource.all { it == '\u0000' })
        assertTrue(passwordSource.all { it == '\u0000' })
        assertEquals("test-user", core.loginUsername)
        assertTrue(core.loginPassword!!.all { it == 0.toByte() })
        assertTrue(core.unlockPassword!!.all { it == 0.toByte() })
        assertEquals(listOf(USER_ID), sessions.activatedUsers)
        assertEquals(1, sessions.persisted.size)
    }

    @Test
    fun totpAndSecurityKeyOfferKeepsOnlyCodePathExecutable() = runTest {
        val core = FakeCore().apply {
            loginSession = sessionInfo(
                secondFactor = SecondFactor.Enabled(
                    supportedMethods = setOf(
                        me.proton.core.auth.domain.entity.SecondFactorMethod.Totp,
                        me.proton.core.auth.domain.entity.SecondFactorMethod.Authenticator,
                    ),
                    fido2 = Fido2Info(null, emptyList()),
                ),
            )
        }
        val sessions = FakeSessions()
        val gateway = ProtonCoreAuthenticationAdapter(account, core, sessions)

        val login = gateway.signIn(account, secret("user"), secret("password"))
        assertTrue(login is GatewayOutcome.Success)
        val state = (login as GatewayOutcome.Success).value
        assertTrue(state is AuthenticationState.SecondFactorRequired)
        assertEquals(
            setOf(SecondFactorMethod.CODE, SecondFactorMethod.SECURITY_KEY),
            (state as AuthenticationState.SecondFactorRequired).methods,
        )
        assertEquals(0, core.unlockCalls)

        val codeSource = " 012345 ".toCharArray()
        val secondFactor = gateway.submitSecondFactor(account, OperationSecret.takeAndClear(codeSource))
        assertEquals(GatewayOutcome.Success(AuthenticationState.Ready), secondFactor)
        assertEquals(1, core.unlockCalls)
        assertTrue(core.unlockPassword!!.all { it == 0.toByte() })
        assertEquals(listOf(USER_ID), sessions.activatedUsers)
        assertEquals(" 012345 ", core.secondFactorCode)
        assertTrue(codeSource.all { it == '\u0000' })
        assertEquals(listOf("full", "mail"), sessions.completedScopes)
    }

    @Test
    fun twoPassRequirementSurvivesSecondFactorAndRestoresAsMailboxPassword() = runTest {
        val core = FakeCore().apply {
            loginSession = sessionInfo(
                secondFactor = SecondFactor.Enabled(
                    supportedMethods = setOf(me.proton.core.auth.domain.entity.SecondFactorMethod.Totp),
                    fido2 = Fido2Info(null, emptyList()),
                ),
                passwordMode = 2,
            )
        }
        val sessions = FakeSessions().apply {
            postSecondFactorState = GateCStoredSessionState.MAILBOX_PASSWORD_REQUIRED
        }
        val authentication = ProtonCoreAuthenticationAdapter(account, core, sessions)

        val login = authentication.signIn(account, secret("user"), secret("password"))
        assertTrue((login as GatewayOutcome.Success).value is AuthenticationState.SecondFactorRequired)

        val afterCode = authentication.submitSecondFactor(account, secret("012345"))
        assertEquals(GatewayOutcome.Success(AuthenticationState.MailboxPasswordRequired), afterCode)
        assertEquals(GateCStoredSessionState.MAILBOX_PASSWORD_REQUIRED, sessions.storedState)

        val restored = ProtonCoreSessionAdapter(account, core, sessions).restore(account)
        assertEquals(GatewayOutcome.Success(SessionState.INTERACTIVE_MAILBOX_PASSWORD_REQUIRED), restored)
    }

    @Test
    fun failClosedHumanVerificationSignalHasAClosedGatewayCategory() = runTest {
        val core = FakeCore().apply { loginFailure = GateCHumanVerificationRequired() }

        val outcome = ProtonCoreAuthenticationAdapter(account, core, FakeSessions())
            .signIn(account, secret("user"), secret("password"))

        assertEquals(GatewayOutcome.Failure(GatewayFailureCategory.HUMAN_VERIFICATION_REQUIRED), outcome)
    }

    @Test
    fun rejectingHumanVerificationListenerEmitsOnlyTheClosedSignal() = runTest {
        val thrown = try {
            GateCHumanVerificationHooks.FailClosed.listener.onHumanVerificationNeeded(
                clientId = ClientId.AccountSession(SESSION_ID),
                methods = HumanVerificationAvailableMethods(
                    verificationMethods = listOf("captcha"),
                    verificationToken = "synthetic-token",
                ),
            )
            null
        } catch (error: GateCHumanVerificationRequired) {
            error
        }

        assertTrue(thrown is GateCHumanVerificationRequired)
    }

    @Test
    fun securityKeyOnlyLoginFailsClosedWithoutAttemptingUnsupportedUi() = runTest {
        val core = FakeCore().apply {
            loginSession = sessionInfo(
                secondFactor = SecondFactor.Enabled(
                    supportedMethods = setOf(me.proton.core.auth.domain.entity.SecondFactorMethod.Authenticator),
                    fido2 = Fido2Info(null, emptyList()),
                ),
            )
        }
        val outcome = ProtonCoreAuthenticationAdapter(account, core, FakeSessions())
            .signIn(account, secret("user"), secret("password"))

        assertEquals(GatewayOutcome.Success(AuthenticationState.SecurityKeyOnlyUnsupported), outcome)
        assertEquals(0, core.unlockCalls)
        assertEquals(listOf(SESSION_ID), core.revokedSessions)
        assertEquals(listOf(USER_ID), core.lockedUsers)
    }

    @Test
    fun preflightRefusesLoginWithoutTouchingPreexistingSession() = runTest {
        val core = FakeCore()
        val sessions = FakeSessions().apply {
            loginAdmitted = false
            currentUser = UserId("preexisting-user")
            currentSession = SessionId("preexisting-session")
            storedState = GateCStoredSessionState.READY
        }

        val outcome = ProtonCoreAuthenticationAdapter(account, core, sessions)
            .signIn(account, secret("other-user"), secret("password"))

        assertEquals(GatewayOutcome.Failure(GatewayFailureCategory.ACCOUNT_ALREADY_CONNECTED), outcome)
        assertEquals(0, core.loginCalls)
        assertTrue(core.revokedSessions.isEmpty())
        assertTrue(core.lockedUsers.isEmpty())
        assertEquals(0, sessions.clearCalls)
        assertEquals(UserId("preexisting-user"), sessions.currentUser)
    }

    @Test
    fun loginNetworkFailureBeforeSessionCreationIsSanitizedWithoutDeletingLocalState() = runTest {
        val core = FakeCore().apply { loginFailure = IOException("must never cross boundary") }
        val sessions = FakeSessions()
        val outcome = ProtonCoreAuthenticationAdapter(account, core, sessions)
            .signIn(account, secret("user"), secret("password"))

        assertEquals(GatewayOutcome.Failure(GatewayFailureCategory.NETWORK_UNAVAILABLE), outcome)
        assertTrue(core.loginPassword!!.all { it == 0.toByte() })
        assertEquals(0, sessions.clearCalls)
    }

    @Test
    fun rateLimitPreservesProtonCoreRetryAfterBudget() = runTest {
        val core = FakeCore().apply {
            loginFailure = ApiException(
                ApiResult.Error.Http(
                    httpCode = 429,
                    message = "fixture message",
                    retryAfter = 7.seconds,
                ),
            )
        }

        val outcome = ProtonCoreAuthenticationAdapter(account, core, FakeSessions())
            .signIn(account, secret("user"), secret("password"))

        assertEquals(GatewayOutcome.Failure(GatewayFailureCategory.RATE_LIMITED, 7_000), outcome)
    }

    @Test
    fun authDiagnosticEmitsOnlyClosedAggregateBeforeGatewayClassification() = runTest {
        val raw = ApiException(
            ApiResult.Error.Http(
                httpCode = 422,
                message = "fixture message that must not cross",
                proton = ApiResult.Error.ProtonData(
                    code = 8002,
                    error = "fixture proton text that must not cross",
                ),
                body = kotlinx.serialization.json.JsonObject(mapOf(
                    "private_fixture" to kotlinx.serialization.json.JsonPrimitive("must not cross"),
                )),
            ),
        )
        val observed = mutableListOf<GateCAuthDiagnosticEvent>()
        val phases = mutableListOf<GateCAuthPhase>()
        val core = FakeCore().apply { loginFailure = raw }
        val outcome = ProtonCoreAuthenticationAdapter(
            account,
            core,
            FakeSessions(),
            object : GateCAuthDiagnostic {
                override fun onPhase(phase: GateCAuthPhase) {
                    phases += phase
                }

                override fun onFailure(event: GateCAuthDiagnosticEvent) {
                    observed += event
                }
            },
        ).signIn(account, secret("user"), secret("password"))

        assertEquals(GatewayOutcome.Failure(GatewayFailureCategory.VALIDATION_REJECTED), outcome)
        assertEquals(
            listOf(
                GateCAuthDiagnosticEvent(
                    GateCAuthDiagnosticClass.API_HTTP,
                    httpCode = 422,
                    protonCode = 8002,
                ),
            ),
            observed,
        )
        assertEquals(listOf(GateCAuthPhase.BEGIN_LOGIN, GateCAuthPhase.SECRET_ENCODING), phases)
    }

    @Test
    fun authDiagnosticMappingIsClosedAndDropsOutOfRangeCodes() {
        assertEquals(
            GateCAuthDiagnosticEvent(GateCAuthDiagnosticClass.API_PARSE),
            ApiException(ApiResult.Error.Parse(IOException())).toGateCAuthDiagnostic(),
        )
        assertEquals(
            GateCAuthDiagnosticEvent(GateCAuthDiagnosticClass.API_CONNECTION),
            ApiException(ApiResult.Error.NoInternet()).toGateCAuthDiagnostic(),
        )
        assertEquals(
            GateCAuthDiagnosticEvent(GateCAuthDiagnosticClass.ILLEGAL_STATE),
            IllegalStateException("not exposed").toGateCAuthDiagnostic(),
        )
        assertEquals(
            GateCAuthDiagnosticEvent(GateCAuthDiagnosticClass.ILLEGAL_ARGUMENT),
            IllegalArgumentException("not exposed").toGateCAuthDiagnostic(),
        )
        assertEquals(
            GateCAuthDiagnosticEvent(GateCAuthDiagnosticClass.OTHER),
            IOException("not exposed").toGateCAuthDiagnostic(),
        )
        assertEquals(
            GateCAuthDiagnosticEvent(GateCAuthDiagnosticClass.STACK_OVERFLOW),
            StackOverflowError().toGateCAuthDiagnostic(),
        )
        assertEquals(
            GateCAuthDiagnosticEvent(GateCAuthDiagnosticClass.LINKAGE),
            NoClassDefFoundError().toGateCAuthDiagnostic(),
        )
        assertEquals(
            GateCAuthDiagnosticEvent(GateCAuthDiagnosticClass.SECURITY),
            SecurityException().toGateCAuthDiagnostic(),
        )
        assertEquals(
            GateCAuthDiagnosticEvent(GateCAuthDiagnosticClass.SECURITY),
            RuntimeException("wrapper not exposed", SecurityException("cause not exposed"))
                .toGateCAuthDiagnostic(),
        )
        assertEquals(
            GateCAuthDiagnosticEvent(GateCAuthDiagnosticClass.NO_SUCH_ELEMENT),
            NoSuchElementException().toGateCAuthDiagnostic(),
        )
        assertEquals(
            GateCAuthDiagnosticEvent(GateCAuthDiagnosticClass.API_HTTP),
            ApiException(
                ApiResult.Error.Http(
                    httpCode = 999,
                    message = "not exposed",
                    proton = ApiResult.Error.ProtonData(-1, "not exposed"),
                ),
            ).toGateCAuthDiagnostic(),
        )
    }

    @Test
    fun fatalJvmErrorsPropagateAfterSecretsAndLoginAdmissionAreReleased() = runTest {
        val username = secret("user")
        val password = secret("password")
        val sessions = FakeSessions()
        val core = FakeCore().apply { loginFailure = NoClassDefFoundError("synthetic linkage failure") }

        val failure = runCatching {
            ProtonCoreAuthenticationAdapter(account, core, sessions).signIn(account, username, password)
        }.exceptionOrNull()

        assertTrue(failure is NoClassDefFoundError)
        assertTrue(username.isClosed)
        assertTrue(password.isClosed)
        assertTrue(!sessions.loginAttemptActive)
    }

    @Test
    fun cancellationDuringUnlockAfterPersistenceRevokesLocksClearsAndPropagates() = runTest {
        val core = FakeCore().apply { unlockFailure = CancellationException("test cancellation") }
        val sessions = FakeSessions()
        try {
            ProtonCoreAuthenticationAdapter(account, core, sessions)
                .signIn(account, secret("user"), secret("password"))
            fail("Cancellation must propagate")
        } catch (expected: CancellationException) {
            assertEquals("test cancellation", expected.message)
        }
        assertEquals(1, sessions.clearCalls)
        assertEquals(USER_ID, sessions.lastClearedUser)
        assertEquals(listOf(SESSION_ID), core.revokedSessions)
        assertEquals(listOf(USER_ID), core.lockedUsers)
        assertTrue(core.unlockPassword!!.all { it == 0.toByte() })
    }

    @Test
    fun unlockAndActivationFailuresAfterPersistenceRunCompleteCleanup() = runTest {
        val unlockCore = FakeCore().apply { unlockFailure = IOException("unlock failed") }
        val unlockSessions = FakeSessions()
        val unlockOutcome = ProtonCoreAuthenticationAdapter(account, unlockCore, unlockSessions)
            .signIn(account, secret("user"), secret("password"))
        assertEquals(GatewayOutcome.Failure(GatewayFailureCategory.NETWORK_UNAVAILABLE), unlockOutcome)
        assertEquals(listOf(SESSION_ID), unlockCore.revokedSessions)
        assertEquals(listOf(USER_ID), unlockCore.lockedUsers)
        assertEquals(1, unlockSessions.clearCalls)

        val activationCore = FakeCore()
        val activationSessions = FakeSessions().apply { activateFailure = IOException("activation failed") }
        val activationOutcome = ProtonCoreAuthenticationAdapter(account, activationCore, activationSessions)
            .signIn(account, secret("user"), secret("password"))
        assertEquals(GatewayOutcome.Failure(GatewayFailureCategory.NETWORK_UNAVAILABLE), activationOutcome)
        assertEquals(listOf(SESSION_ID), activationCore.revokedSessions)
        assertEquals(listOf(USER_ID), activationCore.lockedUsers)
        assertEquals(1, activationSessions.clearCalls)
    }

    @Test
    fun persistenceFailureAfterRemoteSessionCreationCleansOnlyAttemptIds() = runTest {
        val core = FakeCore()
        val sessions = FakeSessions().apply { persistFailure = IllegalStateException() }

        val outcome = ProtonCoreAuthenticationAdapter(account, core, sessions)
            .signIn(account, secret("user"), secret("password"))

        assertEquals(GatewayOutcome.Failure(GatewayFailureCategory.UNKNOWN), outcome)
        assertEquals(listOf(SESSION_ID), core.revokedSessions)
        assertEquals(listOf(USER_ID), core.lockedUsers)
        assertEquals(USER_ID, sessions.lastClearedUser)
    }

    @Test
    fun cancelRevokesPendingSessionThenLocksAndClears() = runTest {
        val core = FakeCore()
        val sessions = FakeSessions().apply {
            currentUser = USER_ID
            currentSession = SESSION_ID
            storedState = GateCStoredSessionState.SECOND_FACTOR_REQUIRED
        }

        ProtonCoreAuthenticationAdapter(account, core, sessions).cancel(account)

        assertEquals(listOf(SESSION_ID), core.revokedSessions)
        assertEquals(listOf(USER_ID), core.lockedUsers)
        assertEquals(USER_ID, sessions.lastClearedUser)
        assertEquals(GateCStoredSessionState.ABSENT, sessions.storedState)
    }

    @Test
    fun cancelStillClearsWhenPendingAndCurrentLookupsFail() = runTest {
        val core = FakeCore()
        val sessions = FakeSessions().apply {
            pendingFailure = IOException()
            currentUserFailure = IOException()
            currentSessionFailure = IOException()
        }

        ProtonCoreAuthenticationAdapter(account, core, sessions).cancel(account)

        assertTrue(core.revokedSessions.isEmpty())
        assertTrue(core.lockedUsers.isEmpty())
        assertEquals(1, sessions.clearCalls)
        assertEquals(null, sessions.lastClearedUser)
    }

    @Test
    fun accountMismatchConsumesSecretsWithoutCallingCore() = runTest {
        val core = FakeCore()
        val username = secret("user")
        val password = secret("password")
        val outcome = ProtonCoreAuthenticationAdapter(account, core, FakeSessions())
            .signIn(AccountScope("different"), username, password)

        assertEquals(GatewayOutcome.Failure(GatewayFailureCategory.AUTHENTICATION_REQUIRED), outcome)
        assertTrue(username.isClosed)
        assertTrue(password.isClosed)
        assertEquals(0, core.loginCalls)
    }

    @Test
    fun wrongAccountRejectsEveryAuthAndSessionOperationBeforeStateOrCoreAccess() = runTest {
        val other = AccountScope("different")
        val core = FakeCore()
        val sessions = FakeSessions().apply {
            storedState = GateCStoredSessionState.READY
            currentUser = USER_ID
            currentSession = SESSION_ID
        }
        val authentication = ProtonCoreAuthenticationAdapter(account, core, sessions)
        val code = secret("synthetic-code")
        assertEquals(
            GatewayOutcome.Failure(GatewayFailureCategory.AUTHENTICATION_REQUIRED),
            authentication.submitSecondFactor(other, code),
        )
        assertTrue(code.isClosed)
        assertEquals(null, core.secondFactorCode)

        val session = ProtonCoreSessionAdapter(account, core, sessions)
        assertEquals(
            GatewayOutcome.Failure(GatewayFailureCategory.AUTHENTICATION_REQUIRED),
            session.restore(other),
        )
        assertEquals(
            GatewayOutcome.Failure(GatewayFailureCategory.AUTHENTICATION_REQUIRED),
            session.refresh(other),
        )
        val password = secret("synthetic-password")
        assertEquals(
            GatewayOutcome.Failure(GatewayFailureCategory.AUTHENTICATION_REQUIRED),
            session.unlockKeys(other, password),
        )
        assertTrue(password.isClosed)
        assertEquals(
            GatewayOutcome.Failure(GatewayFailureCategory.AUTHENTICATION_REQUIRED),
            session.lockKeys(other),
        )
        assertEquals(
            GatewayOutcome.Failure(GatewayFailureCategory.AUTHENTICATION_REQUIRED),
            session.revokeAndClear(other),
        )

        assertEquals(0, sessions.refreshCalls)
        assertEquals(0, sessions.clearCalls)
        assertTrue(core.lockedUsers.isEmpty())
        assertTrue(core.revokedSessions.isEmpty())
        assertEquals(USER_ID, sessions.currentUser)
        assertEquals(SESSION_ID, sessions.currentSession)
    }

    @Test
    fun oversizedSecondFactorCodeIsRejectedBeforeImmutableStringCreation() = runTest {
        val core = FakeCore()
        val sessions = FakeSessions().apply { currentSession = SESSION_ID }
        val source = CharArray(129) { '1' }

        val outcome = ProtonCoreAuthenticationAdapter(account, core, sessions)
            .submitSecondFactor(account, OperationSecret.takeAndClear(source))

        assertEquals(GatewayOutcome.Failure(GatewayFailureCategory.VALIDATION_REJECTED), outcome)
        assertEquals(null, core.secondFactorCode)
        assertTrue(source.all { it == '\u0000' })
    }

    @Test
    fun secondFactorSecretClosesWhenSessionIsMissingOrLookupFailsBeforeConsumption() = runTest {
        val missingSessionSecret = secret("fixture-code")
        val missingOutcome = ProtonCoreAuthenticationAdapter(account, FakeCore(), FakeSessions())
            .submitSecondFactor(account, missingSessionSecret)
        assertEquals(GatewayOutcome.Failure(GatewayFailureCategory.AUTHENTICATION_REQUIRED), missingOutcome)
        assertTrue(missingSessionSecret.isClosed)

        val lookupFailureSecret = secret("fixture-code")
        val failingSessions = FakeSessions().apply {
            currentSessionFailure = IllegalStateException("synthetic lookup failure")
        }
        val failedOutcome = ProtonCoreAuthenticationAdapter(account, FakeCore(), failingSessions)
            .submitSecondFactor(account, lookupFailureSecret)
        assertEquals(GatewayOutcome.Failure(GatewayFailureCategory.UNKNOWN), failedOutcome)
        assertTrue(lookupFailureSecret.isClosed)
    }

    @Test
    fun secondFactorProductionFailuresPreserveRetryStateBut401CleansIncompleteSession() = runTest {
        val matrix = listOf(
            ApiException(ApiResult.Error.Http(httpCode = 422, message = "private invalid proof")) to
                GatewayOutcome.Failure(GatewayFailureCategory.VALIDATION_REJECTED),
            ApiException(ApiResult.Error.Http(httpCode = 429, message = "private rate limit", retryAfter = 3.seconds)) to
                GatewayOutcome.Failure(GatewayFailureCategory.RATE_LIMITED, 3_000),
            ApiException(ApiResult.Error.Timeout(isConnectedToNetwork = true)) to
                GatewayOutcome.Failure(GatewayFailureCategory.TIMEOUT),
        )
        matrix.forEach { (failure, expected) ->
            val core = FakeCore().apply { secondFactorFailure = failure }
            val sessions = FakeSessions().apply {
                storedState = GateCStoredSessionState.SECOND_FACTOR_REQUIRED
                currentUser = USER_ID
                currentSession = SESSION_ID
            }
            val source = " synthetic-proof ".toCharArray()
            val outcome = ProtonCoreAuthenticationAdapter(account, core, sessions)
                .submitSecondFactor(account, OperationSecret.takeAndClear(source))

            assertEquals(expected, outcome)
            assertEquals(0, sessions.clearCalls)
            assertEquals(GateCStoredSessionState.SECOND_FACTOR_REQUIRED, sessions.storedState)
            assertTrue(source.all { it == '\u0000' })
        }

        val core = FakeCore().apply {
            secondFactorFailure = ApiException(ApiResult.Error.Http(httpCode = 401, message = "private revoked"))
        }
        val sessions = FakeSessions().apply {
            storedState = GateCStoredSessionState.SECOND_FACTOR_REQUIRED
            currentUser = USER_ID
            currentSession = SESSION_ID
        }
        val outcome = ProtonCoreAuthenticationAdapter(account, core, sessions)
            .submitSecondFactor(account, secret("synthetic-proof"))

        assertEquals(GatewayOutcome.Failure(GatewayFailureCategory.AUTHENTICATION_REQUIRED), outcome)
        assertEquals(1, sessions.clearCalls)
        assertEquals(GateCStoredSessionState.ABSENT, sessions.storedState)
        assertTrue(core.revokedSessions.isEmpty())
        assertEquals(listOf(USER_ID), core.lockedUsers)
    }

    @Test
    fun restoreUnlockAndRevokeFollowFailClosedLifecycle() = runTest {
        val core = FakeCore()
        val sessions = FakeSessions().apply {
            storedState = GateCStoredSessionState.READY
            currentUser = USER_ID
            currentSession = SESSION_ID
        }
        val gateway = ProtonCoreSessionAdapter(account, core, sessions)

        assertEquals(GatewayOutcome.Success(SessionState.READY), gateway.restore(account))
        core.protectedUnlock = null
        assertEquals(
            GatewayOutcome.Success(SessionState.INTERACTIVE_KEY_UNLOCK_REQUIRED),
            gateway.restore(account),
        )
        core.protectedUnlockFailure = IllegalStateException("synthetic protected-state failure")
        assertEquals(
            GatewayOutcome.Success(SessionState.INTERACTIVE_KEY_UNLOCK_REQUIRED),
            gateway.restore(account),
        )
        assertEquals(listOf(USER_ID), core.lockedUsers)
        core.lockedUsers.clear()
        core.protectedUnlockFailure = null

        val unlockPassword = "mailbox-password".toCharArray()
        assertEquals(
            GatewayOutcome.Success(SessionState.READY),
            gateway.unlockKeys(account, OperationSecret.takeAndClear(unlockPassword)),
        )
        assertTrue(unlockPassword.all { it == '\u0000' })
        assertTrue(core.unlockPassword!!.all { it == 0.toByte() })

        core.revokeResult = false
        assertEquals(
            GatewayOutcome.Failure(GatewayFailureCategory.REMOTE_SERVICE_FAILURE),
            gateway.revokeAndClear(account),
        )
        assertEquals(listOf(USER_ID), core.lockedUsers)
        assertEquals(1, sessions.clearCalls)
    }

    @Test
    fun localClearLocksProtectedStateWithoutRemoteRevocation() = runTest {
        val core = FakeCore()
        val sessions = FakeSessions().apply {
            storedState = GateCStoredSessionState.READY
            currentUser = USER_ID
            currentSession = SESSION_ID
        }

        val outcome = ProtonCoreSessionAdapter(account, core, sessions).clearLocal(account)

        assertEquals(GatewayOutcome.Success(Unit), outcome)
        assertEquals(listOf(USER_ID), core.lockedUsers)
        assertTrue(core.revokedSessions.isEmpty())
        assertEquals(1, sessions.clearCalls)
        assertEquals(GateCStoredSessionState.ABSENT, sessions.storedState)
    }

    @Test
    fun restoreClearsPersistedSecondFactorStateBeforeOfferingSignIn() = runTest {
        val core = FakeCore()
        val sessions = FakeSessions().apply {
            storedState = GateCStoredSessionState.SECOND_FACTOR_REQUIRED
            currentUser = USER_ID
            currentSession = SESSION_ID
        }

        val outcome = ProtonCoreSessionAdapter(account, core, sessions).restore(account)

        assertEquals(GatewayOutcome.Success(SessionState.AUTHENTICATION_REQUIRED), outcome)
        assertEquals(1, sessions.clearCalls)
        assertEquals(USER_ID, sessions.lastClearedUser)
        assertEquals(GateCStoredSessionState.ABSENT, sessions.storedState)
        assertTrue(core.lockedUsers.isEmpty())
        assertTrue(core.revokedSessions.isEmpty())
    }

    @Test
    fun restoreFailsClosedWhenPersistedSecondFactorStateCannotBeCleared() = runTest {
        val sessions = FakeSessions().apply {
            storedState = GateCStoredSessionState.SECOND_FACTOR_REQUIRED
            currentUserFailure = IOException()
            clearFailure = IOException()
        }

        val outcome = ProtonCoreSessionAdapter(account, FakeCore(), sessions).restore(account)

        assertEquals(GatewayOutcome.Failure(GatewayFailureCategory.NETWORK_UNAVAILABLE), outcome)
        assertEquals(1, sessions.clearCalls)
        assertEquals(null, sessions.lastClearedUser)
        assertEquals(GateCStoredSessionState.SECOND_FACTOR_REQUIRED, sessions.storedState)
    }

    @Test
    fun unlockResultErrorLocksKeysButPreservesValidSession() = runTest {
        val core = FakeCore().apply {
            unlockResult = UserManager.UnlockResult.Error.PrimaryKeyInvalidPassphrase
        }
        val sessions = FakeSessions().apply {
            currentUser = USER_ID
            currentSession = SESSION_ID
        }

        val outcome = ProtonCoreSessionAdapter(account, core, sessions)
            .unlockKeys(account, secret("wrong-password"))

        assertEquals(GatewayOutcome.Failure(GatewayFailureCategory.VALIDATION_REJECTED), outcome)
        assertEquals(listOf(USER_ID), core.lockedUsers)
        assertTrue(core.revokedSessions.isEmpty())
        assertEquals(0, sessions.clearCalls)
        assertEquals(USER_ID, sessions.currentUser)
        assertEquals(SESSION_ID, sessions.currentSession)
    }

    @Test
    fun unlockExceptionAndActivationFailureLockKeysButPreserveSession() = runTest {
        val unlockCore = FakeCore().apply { unlockFailure = IOException() }
        val unlockSessions = FakeSessions().apply {
            currentUser = USER_ID
            currentSession = SESSION_ID
        }
        val unlockOutcome = ProtonCoreSessionAdapter(account, unlockCore, unlockSessions)
            .unlockKeys(account, secret("password"))
        assertEquals(GatewayOutcome.Failure(GatewayFailureCategory.NETWORK_UNAVAILABLE), unlockOutcome)
        assertEquals(listOf(USER_ID), unlockCore.lockedUsers)
        assertEquals(0, unlockSessions.clearCalls)
        assertEquals(SESSION_ID, unlockSessions.currentSession)

        val activationCore = FakeCore()
        val activationSessions = FakeSessions().apply {
            currentUser = USER_ID
            currentSession = SESSION_ID
            activateFailure = IOException()
        }
        val activationOutcome = ProtonCoreSessionAdapter(account, activationCore, activationSessions)
            .unlockKeys(account, secret("password"))
        assertEquals(GatewayOutcome.Failure(GatewayFailureCategory.NETWORK_UNAVAILABLE), activationOutcome)
        assertEquals(listOf(USER_ID), activationCore.lockedUsers)
        assertEquals(0, activationSessions.clearCalls)
        assertEquals(SESSION_ID, activationSessions.currentSession)
    }

    @Test
    fun unlockCancellationCleansThenPropagatesWhileSuccessRetainsPassphrase() = runTest {
        val cancelledCore = FakeCore().apply { unlockFailure = CancellationException("cancel unlock") }
        val cancelledSessions = FakeSessions().apply {
            currentUser = USER_ID
            currentSession = SESSION_ID
        }
        try {
            ProtonCoreSessionAdapter(account, cancelledCore, cancelledSessions)
                .unlockKeys(account, secret("password"))
            fail("Cancellation must propagate")
        } catch (expected: CancellationException) {
            assertEquals("cancel unlock", expected.message)
        }
        assertEquals(listOf(USER_ID), cancelledCore.lockedUsers)
        assertEquals(0, cancelledSessions.clearCalls)
        assertEquals(USER_ID, cancelledSessions.currentUser)
        assertEquals(SESSION_ID, cancelledSessions.currentSession)

        val successCore = FakeCore()
        val successSessions = FakeSessions().apply { currentUser = USER_ID }
        val success = ProtonCoreSessionAdapter(account, successCore, successSessions)
            .unlockKeys(account, secret("password"))
        assertEquals(GatewayOutcome.Success(SessionState.READY), success)
        assertTrue(successCore.lockedUsers.isEmpty())
        assertEquals(0, successSessions.clearCalls)
    }

    @Test
    fun unlockIdentityLookupFailureClearsCorruptLocalState() = runTest {
        val core = FakeCore()
        val sessions = FakeSessions().apply { currentUserFailure = IOException() }

        val outcome = ProtonCoreSessionAdapter(account, core, sessions)
            .unlockKeys(account, secret("password"))

        assertEquals(GatewayOutcome.Failure(GatewayFailureCategory.NETWORK_UNAVAILABLE), outcome)
        assertTrue(core.lockedUsers.isEmpty())
        assertEquals(1, sessions.clearCalls)
        assertEquals(null, sessions.lastClearedUser)
    }

    @Test
    fun cancellationDuringIdentityLookupOnlyClosesSecretAndPropagates() = runTest {
        val core = FakeCore()
        val sessions = FakeSessions().apply {
            currentUser = USER_ID
            currentSession = SESSION_ID
            currentUserFailure = CancellationException("cancel identity lookup")
        }
        val password = secret("password")

        try {
            ProtonCoreSessionAdapter(account, core, sessions).unlockKeys(account, password)
            fail("Cancellation must propagate")
        } catch (expected: CancellationException) {
            assertEquals("cancel identity lookup", expected.message)
        }

        assertTrue(password.isClosed)
        assertEquals(0, sessions.clearCalls)
        assertEquals(USER_ID, sessions.currentUser)
        assertEquals(SESSION_ID, sessions.currentSession)
        assertEquals(0, core.unlockCalls)
        assertTrue(core.lockedUsers.isEmpty())
        assertTrue(core.revokedSessions.isEmpty())
    }

    @Test
    fun revokeExceptionStillLocksKeysAndClearsLocalSession() = runTest {
        val core = FakeCore().apply { revokeFailure = IOException("redacted") }
        val sessions = FakeSessions().apply {
            currentUser = USER_ID
            currentSession = SESSION_ID
        }
        val outcome = ProtonCoreSessionAdapter(account, core, sessions).revokeAndClear(account)

        assertEquals(GatewayOutcome.Failure(GatewayFailureCategory.NETWORK_UNAVAILABLE), outcome)
        assertEquals(listOf(USER_ID), core.lockedUsers)
        assertEquals(USER_ID, sessions.lastClearedUser)
    }

    @Test fun appSignOutDoesNotFailLocalCleanupBecauseRevocationIsOffline() = runTest {
        val core = FakeCore().apply { revokeFailure = IOException("synthetic") }
        val sessions = FakeSessions().apply { currentUser = USER_ID; currentSession = SESSION_ID }
        assertTrue(ProtonCoreSessionAdapter(account, core, sessions).clearAfterBestEffortRevocation(account) is GatewayOutcome.Success)
        assertEquals(listOf(SESSION_ID), core.revokedSessions)
        assertEquals(listOf(USER_ID), core.lockedUsers)
        assertEquals(USER_ID, sessions.lastClearedUser)
        val failed = FakeSessions().apply { currentUser = USER_ID; currentSession = SESSION_ID; clearFailure = IllegalStateException() }
        assertTrue(ProtonCoreSessionAdapter(account, core, failed).clearAfterBestEffortRevocation(account) is GatewayOutcome.Failure)
    }

    @Test
    fun lockOrLocalClearFailureCannotSkipLocalClearAttempt() = runTest {
        val lockCore = FakeCore().apply { lockFailure = IllegalStateException() }
        val lockSessions = FakeSessions().apply {
            currentUser = USER_ID
            currentSession = SESSION_ID
        }
        val lockOutcome = ProtonCoreSessionAdapter(account, lockCore, lockSessions).revokeAndClear(account)
        assertEquals(GatewayOutcome.Failure(GatewayFailureCategory.UNKNOWN), lockOutcome)
        assertEquals(listOf(SESSION_ID), lockCore.revokedSessions)
        assertEquals(1, lockSessions.clearCalls)

        val clearCore = FakeCore()
        val clearSessions = FakeSessions().apply {
            currentUser = USER_ID
            currentSession = SESSION_ID
            clearFailure = IllegalStateException()
        }
        val clearOutcome = ProtonCoreSessionAdapter(account, clearCore, clearSessions).revokeAndClear(account)
        assertEquals(GatewayOutcome.Failure(GatewayFailureCategory.UNKNOWN), clearOutcome)
        assertEquals(listOf(USER_ID), clearCore.lockedUsers)
        assertEquals(1, clearSessions.clearCalls)
    }

    @Test
    fun logoutCleansWithPartialIdsAndWhenDiscoveryFails() = runTest {
        val sessionOnlyCore = FakeCore()
        val sessionOnly = FakeSessions().apply { currentSession = SESSION_ID }
        assertEquals(
            GatewayOutcome.Success(Unit),
            ProtonCoreSessionAdapter(account, sessionOnlyCore, sessionOnly).revokeAndClear(account),
        )
        assertEquals(listOf(SESSION_ID), sessionOnlyCore.revokedSessions)
        assertTrue(sessionOnlyCore.lockedUsers.isEmpty())
        assertEquals(1, sessionOnly.clearCalls)

        val userOnlyCore = FakeCore()
        val userOnly = FakeSessions().apply { currentUser = USER_ID }
        assertEquals(
            GatewayOutcome.Success(Unit),
            ProtonCoreSessionAdapter(account, userOnlyCore, userOnly).revokeAndClear(account),
        )
        assertTrue(userOnlyCore.revokedSessions.isEmpty())
        assertEquals(listOf(USER_ID), userOnlyCore.lockedUsers)
        assertEquals(USER_ID, userOnly.lastClearedUser)

        val failedDiscovery = FakeSessions().apply {
            currentUserFailure = IOException()
            currentSessionFailure = IOException()
        }
        val failedOutcome = ProtonCoreSessionAdapter(account, FakeCore(), failedDiscovery).revokeAndClear(account)
        assertEquals(GatewayOutcome.Failure(GatewayFailureCategory.NETWORK_UNAVAILABLE), failedOutcome)
        assertEquals(1, failedDiscovery.clearCalls)
        assertEquals(null, failedDiscovery.lastClearedUser)
    }

    @Test
    fun refreshDerivesTwoFactorAndKeyStatesInsteadOfTrustingTokenRefresh() = runTest {
        val core = FakeCore()
        val sessions = FakeSessions().apply {
            refreshResult = true
            currentUser = USER_ID
            currentSession = SESSION_ID
            storedState = GateCStoredSessionState.SECOND_FACTOR_REQUIRED
        }
        val gateway = ProtonCoreSessionAdapter(account, core, sessions)

        assertEquals(GatewayOutcome.Success(SessionState.AUTHENTICATION_REQUIRED), gateway.refresh(account))
        assertEquals(GateCStoredSessionState.ABSENT, sessions.storedState)
        sessions.currentUser = USER_ID
        sessions.currentSession = SESSION_ID
        sessions.storedState = GateCStoredSessionState.KEY_UNLOCK_REQUIRED
        assertEquals(
            GatewayOutcome.Success(SessionState.INTERACTIVE_KEY_UNLOCK_REQUIRED),
            gateway.refresh(account),
        )
        sessions.storedState = GateCStoredSessionState.READY
        core.protectedUnlock = null
        assertEquals(
            GatewayOutcome.Success(SessionState.INTERACTIVE_KEY_UNLOCK_REQUIRED),
            gateway.refresh(account),
        )
        assertEquals(3, sessions.refreshCalls)
    }

    @Test
    fun refreshPreservesNetworkFailureCategory() = runTest {
        val sessions = FakeSessions().apply { refreshFailure = IOException("not exposed") }
        val outcome = ProtonCoreSessionAdapter(account, FakeCore(), sessions).refresh(account)

        assertEquals(GatewayOutcome.Failure(GatewayFailureCategory.NETWORK_UNAVAILABLE), outcome)

        sessions.refreshFailure = ApiException(ApiResult.Error.Timeout(isConnectedToNetwork = true))
        val timeout = ProtonCoreSessionAdapter(account, FakeCore(), sessions).refresh(account)
        assertEquals(GatewayOutcome.Failure(GatewayFailureCategory.TIMEOUT), timeout)
    }

    @Test
    fun operationSecretSuspendConsumptionIsOneShotAndAlwaysZeroesOwnedValue() = runTest {
        val source = "temporary".toCharArray()
        val secret = OperationSecret.takeAndClear(source)
        var observed: CharArray? = null
        runCatching {
            secret.consumeSuspend { value ->
                observed = value
                throw IOException()
            }
        }

        assertTrue(secret.isClosed)
        assertTrue(source.all { it == '\u0000' })
        assertTrue(observed!!.all { it == '\u0000' })
        assertTrue(runCatching { secret.consumeSuspend { Unit } }.isFailure)
    }

    private fun secret(value: String): OperationSecret = OperationSecret.takeAndClear(value.toCharArray())

    private inline fun <reified T> unusedProxy(): T = Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { _, method, _ ->
        error("Unexpected ${T::class.java.simpleName}.${method.name} invocation")
    } as T

    private class FakeCore : GateCCoreOperations {
        var loginSession = sessionInfo()
        var loginFailure: Throwable? = null
        var loginCalls = 0
        var loginUsername: String? = null
        var loginPassword: ByteArray? = null
        var secondFactorCode: String? = null
        var secondFactorFailure: Throwable? = null
        var unlockResult: UserManager.UnlockResult = UserManager.UnlockResult.Success
        var unlockFailure: Throwable? = null
        var protectedUnlock: UserManager.UnlockResult? = UserManager.UnlockResult.Success
        var protectedUnlockFailure: Throwable? = null
        var unlockCalls = 0
        var unlockPassword: ByteArray? = null
        var revokeResult = true
        var revokeFailure: Throwable? = null
        val lockedUsers = mutableListOf<UserId>()
        var lockFailure: Throwable? = null
        val revokedSessions = mutableListOf<SessionId>()

        override suspend fun login(username: String, password: ByteArray): SessionInfo {
            loginCalls++
            loginUsername = username
            loginPassword = password
            loginFailure?.let { throw it }
            return loginSession
        }

        override suspend fun secondFactor(sessionId: SessionId, code: String): ScopeInfo {
            secondFactorCode = code
            secondFactorFailure?.let { throw it }
            return ScopeInfo(scopes = listOf("full", "mail"))
        }

        override suspend fun unlock(userId: UserId, password: ByteArray): UserManager.UnlockResult {
            unlockCalls++
            unlockPassword = password
            unlockFailure?.let { throw it }
            return unlockResult
        }

        override suspend fun restoreProtectedUnlock(userId: UserId): UserManager.UnlockResult? {
            protectedUnlockFailure?.let { throw it }
            return protectedUnlock
        }

        override suspend fun lockAndClear(userId: UserId) {
            lockedUsers += userId
            lockFailure?.let { throw it }
        }

        override suspend fun revoke(sessionId: SessionId): Boolean {
            revokedSessions += sessionId
            revokeFailure?.let { throw it }
            return revokeResult
        }
    }

    private class FakeSessions : GateCSessionLifecycle {
        var storedState = GateCStoredSessionState.ABSENT
        var currentUser: UserId? = null
        var currentAccountAddress: String? = null
        var currentSession: SessionId? = null
        var completedScopes: List<String>? = null
        var postSecondFactorState = GateCStoredSessionState.KEY_UNLOCK_REQUIRED
        var activateFailure: Throwable? = null
        var persistFailure: Throwable? = null
        var clearFailure: Throwable? = null
        var refreshResult = true
        var refreshFailure: Throwable? = null
        var refreshCalls = 0
        var loginAdmitted = true
        var loginAttemptActive = false
        var currentUserFailure: Throwable? = null
        var currentSessionFailure: Throwable? = null
        var pendingFailure: Throwable? = null
        var clearCalls = 0
        var lastClearedUser: UserId? = null
        val persisted = mutableListOf<SessionInfo>()
        val activatedUsers = mutableListOf<UserId>()

        override suspend fun beginLoginAttempt(): Boolean {
            if (!loginAdmitted) return false
            loginAttemptActive = true
            return true
        }

        override suspend fun endLoginAttempt() {
            loginAttemptActive = false
        }

        override suspend fun persistLogin(sessionInfo: SessionInfo) {
            persisted += sessionInfo
            currentUser = sessionInfo.userId
            currentSession = sessionInfo.sessionId
            persistFailure?.let { throw it }
        }

        override suspend fun completeSecondFactor(
            sessionId: SessionId,
            scopes: List<String>,
        ): GateCStoredSessionState {
            completedScopes = scopes
            storedState = postSecondFactorState
            return storedState
        }

        override suspend fun activate(userId: UserId) {
            activatedUsers += userId
            activateFailure?.let { throw it }
            storedState = GateCStoredSessionState.READY
        }

        override suspend fun state(): GateCStoredSessionState = storedState
        override suspend fun currentUserId(): UserId? {
            currentUserFailure?.let { throw it }
            return currentUser
        }

        override suspend fun currentAccountAddress(): String? = currentAccountAddress

        override suspend fun currentSessionId(): SessionId? {
            currentSessionFailure?.let { throw it }
            return currentSession
        }

        override suspend fun pendingSession(): GateCSessionRef? {
            pendingFailure?.let { throw it }
            return if (storedState in setOf(
                    GateCStoredSessionState.SECOND_FACTOR_REQUIRED,
                    GateCStoredSessionState.MAILBOX_PASSWORD_REQUIRED,
                    GateCStoredSessionState.KEY_UNLOCK_REQUIRED,
                ) && currentUser != null && currentSession != null
            ) {
                GateCSessionRef(currentUser!!, currentSession!!)
            } else {
                null
            }
        }
        override suspend fun refresh(): Boolean {
            refreshCalls++
            refreshFailure?.let { throw it }
            return refreshResult
        }

        override suspend fun clearLocal(userId: UserId?) {
            clearCalls++
            lastClearedUser = userId
            clearFailure?.let { throw it }
            currentUser = null
            currentSession = null
            storedState = GateCStoredSessionState.ABSENT
        }
    }

    private companion object {
        val USER_ID = UserId("test-user-id")
        val SESSION_ID = SessionId("test-session-id")

        fun sessionInfo(
            secondFactor: SecondFactor = SecondFactor.Disabled,
            passwordMode: Int = 1,
        ): SessionInfo = SessionInfo(
            username = "test-user",
            accessToken = "synthetic-access-token",
            tokenType = "Bearer",
            scopes = listOf("full"),
            sessionId = SESSION_ID,
            userId = USER_ID,
            refreshToken = "synthetic-refresh-token",
            eventId = "test-event-id",
            serverProof = null,
            localId = 1,
            passwordMode = passwordMode,
            secondFactor = secondFactor,
            temporaryPassword = false,
        )
    }
}
