package com.patmanak.contako.ui.auth

import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.AuthenticationState
import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.data.gateway.GatewayOutcome
import com.patmanak.contako.data.gateway.OperationSecret
import com.patmanak.contako.data.gateway.ProtonAuthenticationGateway
import com.patmanak.contako.data.gateway.ProtonSessionGateway
import com.patmanak.contako.data.gateway.SessionState
import com.patmanak.contako.data.proton.ProtonAuthenticationFlowAdapter
import com.patmanak.contako.domain.auth.AuthenticationSecret
import com.patmanak.contako.domain.auth.AuthenticationFailure
import com.patmanak.contako.domain.auth.AuthenticationStep
import com.patmanak.contako.domain.auth.PasswordPurpose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.test.runTest

class ProtonAuthenticationFlowPortTest {
    private val account = AccountScope("primary")

    @Test
    fun `production adapter maps code and FIDO outcomes without downgrade`() = runTest {
        val authentication = FakeAuthenticationGateway()
        val port = ProtonAuthenticationFlowAdapter(account, authentication, FakeSessionGateway())
        authentication.nextSignIn = GatewayOutcome.Success(
            AuthenticationState.SecondFactorRequired.fromOfferedMethods(
                setOf(com.patmanak.contako.data.gateway.SecondFactorMethod.CODE),
            ),
        )

        assertSame(AuthenticationStep.CodeRequired, port.signIn(secret("user"), secret("password")))

        authentication.nextSignIn = GatewayOutcome.Success(AuthenticationState.SecurityKeyOnlyUnsupported)
        assertSame(
            AuthenticationStep.SecurityKeyOnlyUnsupported,
            port.signIn(secret("user"), secret("password")),
        )
    }

    @Test
    fun `restore cleans an unusable session and never reports ready`() = runTest {
        val authentication = FakeAuthenticationGateway()
        val session = FakeSessionGateway(SessionState.AUTHENTICATION_REQUIRED)
        val port = ProtonAuthenticationFlowAdapter(account, authentication, session)

        assertSame(AuthenticationStep.SignIn, port.restore())
        assertEquals(1, authentication.cancelCalls)
    }

    @Test
    fun `interactive key state requires a fresh operation scoped password`() = runTest {
        val authentication = FakeAuthenticationGateway()
        val session = FakeSessionGateway(SessionState.INTERACTIVE_KEY_UNLOCK_REQUIRED)
        val port = ProtonAuthenticationFlowAdapter(account, authentication, session)

        val restored = port.restore()
        assertTrue(restored is AuthenticationStep.PasswordRequired)
        assertEquals(PasswordPurpose.KEY_UNLOCK, (restored as AuthenticationStep.PasswordRequired).purpose)
    }

    @Test
    fun `interactive mailbox state survives restoration`() = runTest {
        val port = ProtonAuthenticationFlowAdapter(
            account,
            FakeAuthenticationGateway(),
            FakeSessionGateway(SessionState.INTERACTIVE_MAILBOX_PASSWORD_REQUIRED),
        )

        val restored = port.restore() as AuthenticationStep.PasswordRequired

        assertEquals(PasswordPurpose.MAILBOX, restored.purpose)
    }

    @Test
    fun `rate limit budget crosses the gateway and domain boundary`() = runTest {
        val authentication = FakeAuthenticationGateway().apply {
            nextSignIn = GatewayOutcome.Failure(GatewayFailureCategory.RATE_LIMITED, 12_345)
        }
        val port = ProtonAuthenticationFlowAdapter(account, authentication, FakeSessionGateway())

        val failure = port.signIn(secret("user"), secret("password")) as AuthenticationStep.Failure

        assertEquals(AuthenticationFailure.RATE_LIMITED, failure.category)
        assertEquals(12_345L, failure.retryAfterMillis)
    }

    @Test
    fun `second account rejection remains distinct across the domain boundary`() = runTest {
        val authentication = FakeAuthenticationGateway().apply {
            nextSignIn = GatewayOutcome.Failure(GatewayFailureCategory.ACCOUNT_ALREADY_CONNECTED)
        }
        val port = ProtonAuthenticationFlowAdapter(account, authentication, FakeSessionGateway())

        val failure = port.signIn(secret("user"), secret("password")) as AuthenticationStep.Failure

        assertEquals(AuthenticationFailure.ACCOUNT_ALREADY_CONNECTED, failure.category)
    }

    @Test
    fun `only the closed human verification category reaches the unavailable state`() = runTest {
        val authentication = FakeAuthenticationGateway()
        val port = ProtonAuthenticationFlowAdapter(account, authentication, FakeSessionGateway())
        authentication.nextSignIn = GatewayOutcome.Failure(GatewayFailureCategory.HUMAN_VERIFICATION_REQUIRED)
        assertSame(
            AuthenticationStep.HumanVerificationUnavailable,
            port.signIn(secret("user"), secret("password")),
        )

        authentication.nextSignIn = GatewayOutcome.Failure(GatewayFailureCategory.UNKNOWN)
        val unknown = port.signIn(secret("user"), secret("password"))
        assertTrue(unknown is AuthenticationStep.Failure)
    }

    private fun secret(value: String): AuthenticationSecret = AuthenticationSecret.takeAndClear(value.toCharArray())
}

private class FakeAuthenticationGateway : ProtonAuthenticationGateway {
    var nextSignIn: GatewayOutcome<AuthenticationState> = GatewayOutcome.Success(AuthenticationState.Ready)
    var nextSecondFactor: GatewayOutcome<AuthenticationState> =
        GatewayOutcome.Success(AuthenticationState.KeyUnlockRequired)
    var cancelCalls: Int = 0

    override suspend fun signIn(
        account: AccountScope,
        username: OperationSecret,
        password: OperationSecret,
    ): GatewayOutcome<AuthenticationState> {
        username.close()
        password.close()
        return nextSignIn
    }

    override suspend fun submitSecondFactor(
        account: AccountScope,
        code: OperationSecret,
    ): GatewayOutcome<AuthenticationState> {
        code.close()
        return nextSecondFactor
    }

    override suspend fun cancel(account: AccountScope) {
        cancelCalls += 1
    }
}

private class FakeSessionGateway(
    private val restored: SessionState = SessionState.READY,
) : ProtonSessionGateway {
    override suspend fun restore(account: AccountScope): GatewayOutcome<SessionState> =
        GatewayOutcome.Success(restored)

    override suspend fun refresh(account: AccountScope): GatewayOutcome<SessionState> =
        GatewayOutcome.Success(restored)

    override suspend fun unlockKeys(
        account: AccountScope,
        password: OperationSecret,
    ): GatewayOutcome<SessionState> {
        password.close()
        return GatewayOutcome.Success(SessionState.READY)
    }

    override suspend fun lockKeys(account: AccountScope): GatewayOutcome<Unit> = GatewayOutcome.Success(Unit)


    override suspend fun revokeAndClear(account: AccountScope): GatewayOutcome<Unit> = GatewayOutcome.Success(Unit)
}
