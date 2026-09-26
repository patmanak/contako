package com.patmanak.contako.data.proton

import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.AuthenticationState
import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.data.gateway.GatewayOutcome
import com.patmanak.contako.data.gateway.OperationSecret
import com.patmanak.contako.data.gateway.ProtonAuthenticationGateway
import com.patmanak.contako.data.gateway.ProtonSessionGateway
import com.patmanak.contako.data.gateway.SessionState
import com.patmanak.contako.domain.auth.AuthenticationFailure
import com.patmanak.contako.domain.auth.AuthenticationFlowPort
import com.patmanak.contako.domain.auth.AuthenticationSecret
import com.patmanak.contako.domain.auth.AuthenticationStep
import com.patmanak.contako.domain.auth.PasswordPurpose
import kotlinx.coroutines.CancellationException

internal class ProtonAuthenticationFlowAdapter(
    private val account: AccountScope,
    private val authentication: ProtonAuthenticationGateway,
    private val session: ProtonSessionGateway,
) : AuthenticationFlowPort {
    override suspend fun restore(): AuthenticationStep = when (val outcome = session.restore(account)) {
        is GatewayOutcome.Failure -> outcome.toAuthenticationStep()
        is GatewayOutcome.Success -> when (outcome.value) {
            SessionState.READY -> AuthenticationStep.Ready
            SessionState.INTERACTIVE_KEY_UNLOCK_REQUIRED ->
                AuthenticationStep.PasswordRequired(PasswordPurpose.KEY_UNLOCK)
            SessionState.INTERACTIVE_MAILBOX_PASSWORD_REQUIRED ->
                AuthenticationStep.PasswordRequired(PasswordPurpose.MAILBOX)
            SessionState.AUTHENTICATION_REQUIRED,
            SessionState.REVOKED,
            -> cleanupPendingAndReturnToSignIn()
        }
    }

    override suspend fun signIn(
        username: AuthenticationSecret,
        password: AuthenticationSecret,
    ): AuthenticationStep {
        val gatewayUsername = username.toGatewaySecretOrFailure() ?: run {
            password.close()
            return AuthenticationStep.Failure(AuthenticationFailure.TRY_AGAIN)
        }
        val gatewayPassword = password.toGatewaySecretOrFailure() ?: run {
            gatewayUsername.close()
            return AuthenticationStep.Failure(AuthenticationFailure.TRY_AGAIN)
        }
        return try {
            authentication.signIn(account, gatewayUsername, gatewayPassword).toStep()
        } finally {
            gatewayUsername.close()
            gatewayPassword.close()
        }
    }

    override suspend fun submitCode(code: AuthenticationSecret): AuthenticationStep {
        val gatewayCode = code.toGatewaySecretOrFailure()
            ?: return AuthenticationStep.Failure(AuthenticationFailure.TRY_AGAIN)
        return try {
            authentication.submitSecondFactor(account, gatewayCode).toStep()
        } finally {
            gatewayCode.close()
        }
    }

    override suspend fun unlock(password: AuthenticationSecret): AuthenticationStep {
        val gatewayPassword = password.toGatewaySecretOrFailure()
            ?: return AuthenticationStep.Failure(AuthenticationFailure.TRY_AGAIN)
        return try {
            when (val outcome = session.unlockKeys(account, gatewayPassword)) {
                is GatewayOutcome.Failure -> outcome.toAuthenticationStep()
                is GatewayOutcome.Success -> when (outcome.value) {
                    SessionState.READY -> AuthenticationStep.Ready
                    SessionState.INTERACTIVE_KEY_UNLOCK_REQUIRED ->
                        AuthenticationStep.PasswordRequired(PasswordPurpose.KEY_UNLOCK)
                    SessionState.INTERACTIVE_MAILBOX_PASSWORD_REQUIRED ->
                        AuthenticationStep.PasswordRequired(PasswordPurpose.MAILBOX)
                    SessionState.AUTHENTICATION_REQUIRED,
                    SessionState.REVOKED,
                    -> AuthenticationStep.SignIn
                }
            }
        } finally {
            gatewayPassword.close()
        }
    }

    override suspend fun cancel() {
        authentication.cancel(account)
    }

    private suspend fun cleanupPendingAndReturnToSignIn(): AuthenticationStep = try {
        authentication.cancel(account)
        AuthenticationStep.SignIn
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        AuthenticationStep.Failure(AuthenticationFailure.TRY_AGAIN)
    }

    private fun GatewayOutcome<AuthenticationState>.toStep(): AuthenticationStep = when (this) {
        is GatewayOutcome.Failure -> toAuthenticationStep()
        is GatewayOutcome.Success -> when (value) {
            AuthenticationState.Ready -> AuthenticationStep.Ready
            is AuthenticationState.SecondFactorRequired -> AuthenticationStep.CodeRequired
            AuthenticationState.MailboxPasswordRequired ->
                AuthenticationStep.PasswordRequired(PasswordPurpose.MAILBOX)
            AuthenticationState.KeyUnlockRequired ->
                AuthenticationStep.PasswordRequired(PasswordPurpose.KEY_UNLOCK)
            AuthenticationState.SecurityKeyOnlyUnsupported ->
                AuthenticationStep.SecurityKeyOnlyUnsupported
        }
    }

    private fun AuthenticationSecret.toGatewaySecretOrFailure(): OperationSecret? = try {
        consume { OperationSecret.takeAndClear(it) }
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IllegalStateException) {
        null
    }
}

private fun GatewayOutcome.Failure.toAuthenticationStep(): AuthenticationStep =
    if (category == GatewayFailureCategory.HUMAN_VERIFICATION_REQUIRED) {
        AuthenticationStep.HumanVerificationUnavailable
    } else {
        AuthenticationStep.Failure(
            category = category.toAuthenticationFailure(),
            retryAfterMillis = retryAfterMillis,
        )
    }

private fun GatewayFailureCategory.toAuthenticationFailure(): AuthenticationFailure = when (this) {
    GatewayFailureCategory.AUTHENTICATION_REQUIRED,
    GatewayFailureCategory.VALIDATION_REJECTED,
    -> AuthenticationFailure.REJECTED
    GatewayFailureCategory.ACCOUNT_ALREADY_CONNECTED -> AuthenticationFailure.ACCOUNT_ALREADY_CONNECTED
    GatewayFailureCategory.NETWORK_UNAVAILABLE -> AuthenticationFailure.OFFLINE
    GatewayFailureCategory.TIMEOUT -> AuthenticationFailure.TIMED_OUT
    GatewayFailureCategory.RATE_LIMITED -> AuthenticationFailure.RATE_LIMITED
    GatewayFailureCategory.CRYPTOGRAPHIC_VERIFICATION_FAILED -> AuthenticationFailure.CRYPTOGRAPHIC_FAILURE
    GatewayFailureCategory.CLIENT_IDENTITY_REJECTED,
    GatewayFailureCategory.UNSUPPORTED_AUTHENTICATION,
    -> AuthenticationFailure.CLIENT_UNSUPPORTED
    GatewayFailureCategory.CANCELLED -> AuthenticationFailure.CANCELLED
    else -> AuthenticationFailure.TRY_AGAIN
}
