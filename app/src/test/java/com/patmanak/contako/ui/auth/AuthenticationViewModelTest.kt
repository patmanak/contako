package com.patmanak.contako.ui.auth

import com.patmanak.contako.domain.auth.AuthenticationFlowPort
import com.patmanak.contako.domain.auth.AuthenticationFailure
import com.patmanak.contako.domain.auth.AuthenticationSecret
import com.patmanak.contako.domain.auth.AuthenticationStep
import com.patmanak.contako.domain.auth.PasswordPurpose
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthenticationViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `double submit is ignored and cancellation cannot be overwritten by stale success`() =
        runTest(dispatcher) {
            val release = CompletableDeferred<AuthenticationStep>()
            val port = FakeAuthenticationFlowPort(signInResult = {
                withContext(NonCancellable) { release.await() }
            })
            val viewModel = AuthenticationViewModel(port)
            advanceUntilIdle()

            val firstUsername = "first-user".toCharArray()
            val firstPassword = "first-password".toCharArray()
            viewModel.submitSignIn(firstUsername, firstPassword)
            val duplicateUsername = "duplicate-user".toCharArray()
            val duplicatePassword = "duplicate-password".toCharArray()
            viewModel.submitSignIn(duplicateUsername, duplicatePassword)

            assertTrue(firstUsername.isCleared())
            assertTrue(firstPassword.isCleared())
            assertTrue(duplicateUsername.isCleared())
            assertTrue(duplicatePassword.isCleared())
            advanceUntilIdle()
            assertEquals(1, port.signInCalls)
            assertTrue(viewModel.state.value.isSubmitting)

            viewModel.cancelAuthentication()
            release.complete(AuthenticationStep.Ready)
            advanceUntilIdle()

            assertEquals(1, port.cancelCalls)
            assertEquals(AuthenticationDestination.SIGN_IN, viewModel.state.value.destination)
            assertFalse(viewModel.state.value.isSubmitting)
        }

    @Test
    fun `recreated state restores fail closed without carrying code or password`() = runTest(dispatcher) {
        val firstPort = FakeAuthenticationFlowPort(signInResult = { AuthenticationStep.CodeRequired })
        val first = AuthenticationViewModel(firstPort)
        advanceUntilIdle()
        val username = "account".toCharArray()
        val password = "operation-secret".toCharArray()
        first.submitSignIn(username, password)
        advanceUntilIdle()
        assertEquals(AuthenticationDestination.CODE, first.state.value.destination)

        val recreated = AuthenticationViewModel(FakeAuthenticationFlowPort())
        advanceUntilIdle()

        assertEquals(AuthenticationDestination.SIGN_IN, recreated.state.value.destination)
        assertFalse(recreated.state.value.toString().contains("operation-secret"))
        assertFalse(recreated.state.value.toString().contains("account"))
        assertTrue(username.isCleared())
        assertTrue(password.isCleared())
    }

    @Test
    fun `TOTP and recovery input is submitted opaquely then cleared`() = runTest(dispatcher) {
        val observed = mutableListOf<String>()
        val port = FakeAuthenticationFlowPort(
            restoreResult = AuthenticationStep.CodeRequired,
            codeResult = { secret ->
                secret.consumeSuspend { chars -> observed += String(chars) }
                AuthenticationStep.PasswordRequired(PasswordPurpose.KEY_UNLOCK)
            },
        )
        val viewModel = AuthenticationViewModel(port)
        advanceUntilIdle()
        val code = " Ab- 09_Z ".toCharArray()

        viewModel.submitCode(code)
        advanceUntilIdle()

        assertEquals(listOf(" Ab- 09_Z "), observed)
        assertTrue(code.isCleared())
        assertEquals(AuthenticationDestination.PASSWORD, viewModel.state.value.destination)
    }

    @Test
    fun `FIDO only state fails closed and exposes no secure input state`() = runTest(dispatcher) {
        val port = FakeAuthenticationFlowPort(
            signInResult = { AuthenticationStep.SecurityKeyOnlyUnsupported },
        )
        val viewModel = AuthenticationViewModel(port)
        advanceUntilIdle()

        viewModel.submitSignIn("account".toCharArray(), "password".toCharArray())
        advanceUntilIdle()

        assertEquals(AuthenticationDestination.SECURITY_KEY_UNSUPPORTED, viewModel.state.value.destination)
        assertFalse(AuthenticationCapturePolicy.blocksCapture(viewModel.state.value.destination))
        viewModel.cancelAuthentication()
        advanceUntilIdle()
        assertEquals(AuthenticationDestination.SIGN_IN, viewModel.state.value.destination)
    }

    @Test
    fun `invalid and duplicate inputs are always zeroed before returning`() = runTest(dispatcher) {
        val port = FakeAuthenticationFlowPort()
        val viewModel = AuthenticationViewModel(port)
        advanceUntilIdle()
        val username = "   ".toCharArray()
        val password = CharArray(0)

        viewModel.submitSignIn(username, password)

        assertTrue(username.isCleared())
        assertEquals(AuthenticationMessage.INPUT_REQUIRED, viewModel.state.value.message)
        assertEquals(0, port.signInCalls)
    }

    @Test
    fun `explicit sign in cannot return silently when the gateway yields signed out state`() =
        runTest(dispatcher) {
            val viewModel = AuthenticationViewModel(
                FakeAuthenticationFlowPort(
                    restoreResult = AuthenticationStep.SignIn,
                    signInResult = { AuthenticationStep.SignIn },
                ),
            )
            advanceUntilIdle()
            assertEquals(AuthenticationDestination.SIGN_IN, viewModel.state.value.destination)
            assertEquals(null, viewModel.state.value.message)

            viewModel.submitSignIn("account".toCharArray(), "wrong-password".toCharArray())
            advanceUntilIdle()

            assertEquals(AuthenticationDestination.SIGN_IN, viewModel.state.value.destination)
            assertEquals(AuthenticationMessage.REJECTED, viewModel.state.value.message)
            assertFalse(viewModel.state.value.isSubmitting)
        }

    @Test
    fun `second account attempt shows the actionable single account limitation`() = runTest(dispatcher) {
        val viewModel = AuthenticationViewModel(
            FakeAuthenticationFlowPort(
                signInResult = {
                    AuthenticationStep.Failure(AuthenticationFailure.ACCOUNT_ALREADY_CONNECTED)
                },
            ),
        )
        advanceUntilIdle()

        viewModel.submitSignIn("other-account".toCharArray(), "password".toCharArray())
        advanceUntilIdle()

        assertEquals(AuthenticationDestination.SIGN_IN, viewModel.state.value.destination)
        assertEquals(AuthenticationMessage.ACCOUNT_ALREADY_CONNECTED, viewModel.state.value.message)
        assertFalse(viewModel.state.value.isSubmitting)
    }

    @Test
    fun `rate limit disables retry until bounded monotonic deadline and rejects double retry`() =
        runTest(dispatcher) {
            var attempts = 0
            val port = FakeAuthenticationFlowPort(
                signInResult = {
                    attempts += 1
                    if (attempts == 1) {
                        AuthenticationStep.Failure(AuthenticationFailure.RATE_LIMITED, 5_000)
                    } else {
                        AuthenticationStep.Ready
                    }
                },
            )
            val viewModel = AuthenticationViewModel(
                port = port,
                monotonicClock = AuthenticationMonotonicClock { testScheduler.currentTime },
            )
            advanceUntilIdle()

            viewModel.submitSignIn("account".toCharArray(), "password".toCharArray())
            runCurrent()

            assertTrue(viewModel.state.value.isRetryBlocked)
            assertEquals(5_000L, viewModel.state.value.retryAfterMillis)
            assertEquals(5_000L, viewModel.state.value.retryBlockedUntilElapsedRealtimeMillis)

            val duplicateUsername = "duplicate".toCharArray()
            val duplicatePassword = "duplicate-password".toCharArray()
            viewModel.submitSignIn(duplicateUsername, duplicatePassword)
            runCurrent()
            assertTrue(duplicateUsername.isCleared())
            assertTrue(duplicatePassword.isCleared())
            assertEquals(1, port.signInCalls)

            advanceTimeBy(4_999)
            runCurrent()
            assertTrue(viewModel.state.value.isRetryBlocked)

            advanceTimeBy(1)
            runCurrent()
            assertFalse(viewModel.state.value.isRetryBlocked)

            viewModel.submitSignIn("account".toCharArray(), "password".toCharArray())
            advanceUntilIdle()
            assertEquals(2, port.signInCalls)
            assertEquals(AuthenticationDestination.READY, viewModel.state.value.destination)
        }

    @Test
    fun `rate limit without server budget uses safe default delay`() = runTest(dispatcher) {
        val port = FakeAuthenticationFlowPort(
            signInResult = {
                AuthenticationStep.Failure(AuthenticationFailure.RATE_LIMITED)
            },
        )
        val viewModel = AuthenticationViewModel(
            port = port,
            monotonicClock = AuthenticationMonotonicClock { testScheduler.currentTime },
        )
        advanceUntilIdle()

        viewModel.submitSignIn("account".toCharArray(), "password".toCharArray())
        runCurrent()

        assertTrue(viewModel.state.value.isRetryBlocked)
        assertEquals(30_000L, viewModel.state.value.retryAfterMillis)
        advanceTimeBy(29_999)
        runCurrent()
        assertTrue(viewModel.state.value.isRetryBlocked)
        advanceTimeBy(1)
        runCurrent()
        assertFalse(viewModel.state.value.isRetryBlocked)
    }

    @Test
    fun `server retry budget longer than timer slice is never shortened`() = runTest(dispatcher) {
        val serverBudget = 60 * 60_000L
        val viewModel = AuthenticationViewModel(
            port = FakeAuthenticationFlowPort(
                signInResult = { AuthenticationStep.Failure(AuthenticationFailure.RATE_LIMITED, serverBudget) },
            ),
            monotonicClock = AuthenticationMonotonicClock { testScheduler.currentTime },
        )
        advanceUntilIdle()

        viewModel.submitSignIn("account".toCharArray(), "password".toCharArray())
        runCurrent()
        assertEquals(serverBudget, viewModel.state.value.retryAfterMillis)
        assertEquals(serverBudget, viewModel.state.value.retryBlockedUntilElapsedRealtimeMillis)

        advanceTimeBy(serverBudget - 1)
        runCurrent()
        assertTrue(viewModel.state.value.isRetryBlocked)
        advanceTimeBy(1)
        runCurrent()
        assertFalse(viewModel.state.value.isRetryBlocked)
    }

    @Test
    fun `retry deadline addition saturates at Long MAX without an overflowing early release`() = runTest(dispatcher) {
        var elapsed = Long.MAX_VALUE - 500
        val viewModel = AuthenticationViewModel(
            port = FakeAuthenticationFlowPort(
                signInResult = { AuthenticationStep.Failure(AuthenticationFailure.RATE_LIMITED, 1_000) },
            ),
            monotonicClock = AuthenticationMonotonicClock { elapsed },
        )
        advanceUntilIdle()

        viewModel.submitSignIn("account".toCharArray(), "password".toCharArray())
        runCurrent()
        assertTrue(viewModel.state.value.isRetryBlocked)
        assertEquals(Long.MAX_VALUE, viewModel.state.value.retryBlockedUntilElapsedRealtimeMillis)

        advanceTimeBy(499)
        runCurrent()
        assertTrue(viewModel.state.value.isRetryBlocked)
        elapsed = Long.MAX_VALUE
        advanceTimeBy(1)
        runCurrent()
        assertFalse(viewModel.state.value.isRetryBlocked)
    }

    @Test
    fun `challenge cancellation cannot bypass the monotonic retry deadline on sign in`() = runTest(dispatcher) {
        val port = FakeAuthenticationFlowPort(
            restoreResult = AuthenticationStep.CodeRequired,
            codeResult = {
                it.close()
                AuthenticationStep.Failure(AuthenticationFailure.RATE_LIMITED, 5_000)
            },
        )
        val viewModel = AuthenticationViewModel(
            port = port,
            monotonicClock = AuthenticationMonotonicClock { testScheduler.currentTime },
        )
        advanceUntilIdle()

        viewModel.submitCode("fixture-code".toCharArray())
        runCurrent()
        assertTrue(viewModel.state.value.isRetryBlocked)
        viewModel.cancelAuthentication()
        runCurrent()
        assertEquals(AuthenticationDestination.SIGN_IN, viewModel.state.value.destination)
        assertTrue(viewModel.state.value.isRetryBlocked)

        val username = "account".toCharArray()
        val password = "password".toCharArray()
        viewModel.submitSignIn(username, password)
        runCurrent()
        assertTrue(username.isCleared())
        assertTrue(password.isCleared())
        assertEquals(0, port.signInCalls)

        advanceTimeBy(5_000)
        runCurrent()
        assertFalse(viewModel.state.value.isRetryBlocked)
    }

    @Test
    fun `repeated back cannot cancel and restart cleanup already in progress`() = runTest(dispatcher) {
        val releaseCleanup = CompletableDeferred<Unit>()
        val port = FakeAuthenticationFlowPort(
            restoreResult = AuthenticationStep.CodeRequired,
            cancelResult = { releaseCleanup.await() },
        )
        val viewModel = AuthenticationViewModel(port)
        advanceUntilIdle()

        viewModel.cancelAuthentication()
        viewModel.cancelAuthentication()
        runCurrent()

        assertEquals(1, port.cancelCalls)
        assertTrue(viewModel.state.value.isCleanupInProgress)
        assertFalse(viewModel.state.value.requiresAuthenticationBackCleanup())

        releaseCleanup.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, port.cancelCalls)
        assertFalse(viewModel.state.value.isCleanupInProgress)
    }
}

private class FakeAuthenticationFlowPort(
    private val restoreResult: AuthenticationStep = AuthenticationStep.SignIn,
    private val signInResult: suspend () -> AuthenticationStep = { AuthenticationStep.Ready },
    private val codeResult: suspend (AuthenticationSecret) -> AuthenticationStep = {
        it.close()
        AuthenticationStep.Ready
    },
    private val cancelResult: suspend () -> Unit = {},
) : AuthenticationFlowPort {
    var signInCalls: Int = 0
    var cancelCalls: Int = 0

    override suspend fun restore(): AuthenticationStep = restoreResult

    override suspend fun signIn(
        username: AuthenticationSecret,
        password: AuthenticationSecret,
    ): AuthenticationStep {
        signInCalls += 1
        return username.consumeSuspend {
            password.consumeSuspend { signInResult() }
        }
    }

    override suspend fun submitCode(code: AuthenticationSecret): AuthenticationStep = codeResult(code)

    override suspend fun unlock(password: AuthenticationSecret): AuthenticationStep {
        password.close()
        return AuthenticationStep.Ready
    }

    override suspend fun cancel() {
        cancelCalls += 1
        cancelResult()
    }
}

private fun CharArray.isCleared(): Boolean = all { it == '\u0000' }
