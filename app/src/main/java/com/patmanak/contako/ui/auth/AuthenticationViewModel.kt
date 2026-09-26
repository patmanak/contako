package com.patmanak.contako.ui.auth

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.patmanak.contako.domain.auth.AuthenticationFailure
import com.patmanak.contako.domain.auth.AuthenticationFlowPort
import com.patmanak.contako.domain.auth.AuthenticationSecret
import com.patmanak.contako.domain.auth.AuthenticationStep
import com.patmanak.contako.domain.auth.PasswordPurpose
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class AuthenticationDestination {
    RESTORING,
    SIGN_IN,
    CODE,
    PASSWORD,
    SECURITY_KEY_UNSUPPORTED,
    HUMAN_VERIFICATION_UNAVAILABLE,
    READY,
}

internal enum class AuthenticationMessage {
    INPUT_REQUIRED,
    REJECTED,
    ACCOUNT_ALREADY_CONNECTED,
    OFFLINE,
    TIMED_OUT,
    RATE_LIMITED,
    CRYPTOGRAPHIC_FAILURE,
    CLIENT_UNSUPPORTED,
    TRY_AGAIN,
}

internal data class AuthenticationUiState(
    val destination: AuthenticationDestination = AuthenticationDestination.RESTORING,
    val passwordPurpose: PasswordPurpose? = null,
    val isSubmitting: Boolean = false,
    val isCleanupInProgress: Boolean = false,
    val isRetryBlocked: Boolean = false,
    val retryAfterMillis: Long? = null,
    val retryBlockedUntilElapsedRealtimeMillis: Long? = null,
    val message: AuthenticationMessage? = null,
)

internal fun interface AuthenticationMonotonicClock {
    fun elapsedRealtimeMillis(): Long
}

private val SystemAuthenticationMonotonicClock =
    AuthenticationMonotonicClock(SystemClock::elapsedRealtime)

/**
 * Authentication state holder. Secret input is deliberately absent from this state. Compose text
 * fields necessarily hold immutable Strings temporarily: they are never saved and their references
 * are dropped on submission/disposal, but immutable String storage cannot be zeroed. Mutable arrays
 * crossing this boundary are cleared on every accepted, rejected, duplicate, and validation path.
 */
internal class AuthenticationViewModel(
    private val port: AuthenticationFlowPort,
    private val monotonicClock: AuthenticationMonotonicClock = SystemAuthenticationMonotonicClock,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AuthenticationUiState())
    val state: StateFlow<AuthenticationUiState> = mutableState.asStateFlow()

    private val operationAdmitted = AtomicBoolean(false)
    private var operationRevision = 0L
    private var activeOperation: Job? = null
    private var retryReleaseJob: Job? = null
    private var activeSecrets: List<AuthenticationSecret> = emptyList()

    init {
        launchOperation(AuthenticationDestination.RESTORING) { port.restore() }
    }

    fun submitSignIn(username: CharArray, password: CharArray) {
        val revision = admitOrClear(username, password) ?: return
        if (username.none { !it.isWhitespace() } || password.isEmpty()) {
            username.clearSecret()
            password.clearSecret()
            rejectLocal(revision, AuthenticationDestination.SIGN_IN)
            return
        }

        val ownedUsername = try {
            AuthenticationSecret.takeAndClear(username)
        } catch (_: IllegalArgumentException) {
            password.clearSecret()
            rejectLocal(revision, AuthenticationDestination.SIGN_IN)
            return
        }
        val ownedPassword = try {
            AuthenticationSecret.takeAndClear(password)
        } catch (_: IllegalArgumentException) {
            ownedUsername.close()
            rejectLocal(revision, AuthenticationDestination.SIGN_IN)
            return
        }
        launchAdmittedOperation(
            revision = revision,
            destination = AuthenticationDestination.SIGN_IN,
            secrets = listOf(ownedUsername, ownedPassword),
            rejectSilentSignInResult = true,
        ) {
            port.signIn(ownedUsername, ownedPassword)
        }
    }

    fun submitCode(code: CharArray) {
        val revision = admitOrClear(code) ?: return
        if (code.isEmpty() || code.size > MAX_CODE_LENGTH) {
            code.clearSecret()
            rejectLocal(revision, AuthenticationDestination.CODE)
            return
        }
        val owned = try {
            AuthenticationSecret.takeAndClear(code)
        } catch (_: IllegalArgumentException) {
            rejectLocal(revision, AuthenticationDestination.CODE)
            return
        }
        launchAdmittedOperation(
            revision = revision,
            destination = AuthenticationDestination.CODE,
            secrets = listOf(owned),
        ) {
            port.submitCode(owned)
        }
    }

    fun submitPassword(password: CharArray) {
        val revision = admitOrClear(password) ?: return
        val purpose = mutableState.value.passwordPurpose
        if (password.isEmpty() || purpose == null) {
            password.clearSecret()
            rejectLocal(revision, AuthenticationDestination.PASSWORD, purpose)
            return
        }
        val owned = try {
            AuthenticationSecret.takeAndClear(password)
        } catch (_: IllegalArgumentException) {
            rejectLocal(revision, AuthenticationDestination.PASSWORD, purpose)
            return
        }
        launchAdmittedOperation(
            revision = revision,
            destination = AuthenticationDestination.PASSWORD,
            passwordPurpose = purpose,
            secrets = listOf(owned),
        ) {
            port.unlock(owned)
        }
    }

    fun cancelAuthentication() {
        if (mutableState.value.isCleanupInProgress) return
        val preservedRetryDeadline = mutableState.value
            .takeIf(AuthenticationUiState::isRetryBlocked)
            ?.retryBlockedUntilElapsedRealtimeMillis
        operationRevision += 1
        val revision = operationRevision
        activeSecrets.forEach(AuthenticationSecret::close)
        activeSecrets = emptyList()
        activeOperation?.cancel()
        if (preservedRetryDeadline == null) {
            retryReleaseJob?.cancel()
            retryReleaseJob = null
        } else {
            scheduleRetryRelease(preservedRetryDeadline)
        }
        operationAdmitted.set(true)
        mutableState.value = mutableState.value.copy(
            destination = AuthenticationDestination.SIGN_IN,
            passwordPurpose = null,
            isSubmitting = true,
            isCleanupInProgress = true,
            message = null,
        )
        activeOperation = viewModelScope.launch {
            try {
                port.cancel()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Cleanup remains fail-closed; the next sign-in cannot be reported as ready here.
            } finally {
                if (revision == operationRevision) {
                    operationAdmitted.set(false)
                    mutableState.value = mutableState.value.copy(
                        destination = AuthenticationDestination.SIGN_IN,
                        passwordPurpose = null,
                        isSubmitting = false,
                        isCleanupInProgress = false,
                        message = null,
                    )
                }
            }
        }
    }

    fun signOut() = cancelAuthentication()

    fun dismissMessage() {
        mutableState.value = mutableState.value.copy(message = null)
    }

    private fun launchOperation(
        destination: AuthenticationDestination,
        block: suspend () -> AuthenticationStep,
    ) {
        val revision = admitOrClear() ?: return
        launchAdmittedOperation(revision, destination, block = block)
    }

    private fun launchAdmittedOperation(
        revision: Long,
        destination: AuthenticationDestination,
        passwordPurpose: PasswordPurpose? = null,
        secrets: List<AuthenticationSecret> = emptyList(),
        rejectSilentSignInResult: Boolean = false,
        block: suspend () -> AuthenticationStep,
    ) {
        mutableState.value = AuthenticationUiState(
            destination = destination,
            passwordPurpose = passwordPurpose,
            isSubmitting = true,
        )
        activeSecrets = secrets
        activeOperation = viewModelScope.launch {
            try {
                val returnedStep = block()
                val step = if (rejectSilentSignInResult && returnedStep == AuthenticationStep.SignIn) {
                    // An explicit credential submission MUST never return silently to the welcome
                    // screen. Restore legitimately uses SignIn to represent "no retained session",
                    // but the same result after submit is a closed authentication rejection.
                    AuthenticationStep.Failure(AuthenticationFailure.REJECTED)
                } else {
                    returnedStep
                }
                if (revision == operationRevision) applyStep(step)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                if (revision == operationRevision) {
                    applyStep(AuthenticationStep.Failure(AuthenticationFailure.TRY_AGAIN))
                }
            } finally {
                secrets.forEach(AuthenticationSecret::close)
                if (revision == operationRevision) {
                    activeSecrets = emptyList()
                    operationAdmitted.set(false)
                    mutableState.value = mutableState.value.copy(isSubmitting = false)
                }
            }
        }
    }

    private fun applyStep(step: AuthenticationStep) {
        if (step !is AuthenticationStep.Failure) clearRetryBlock()
        mutableState.value = when (step) {
            AuthenticationStep.SignIn -> AuthenticationUiState(AuthenticationDestination.SIGN_IN)
            AuthenticationStep.CodeRequired -> AuthenticationUiState(AuthenticationDestination.CODE)
            is AuthenticationStep.PasswordRequired -> AuthenticationUiState(
                destination = AuthenticationDestination.PASSWORD,
                passwordPurpose = step.purpose,
            )
            AuthenticationStep.SecurityKeyOnlyUnsupported ->
                AuthenticationUiState(AuthenticationDestination.SECURITY_KEY_UNSUPPORTED)
            AuthenticationStep.HumanVerificationUnavailable ->
                AuthenticationUiState(AuthenticationDestination.HUMAN_VERIFICATION_UNAVAILABLE)
            AuthenticationStep.Ready -> AuthenticationUiState(AuthenticationDestination.READY)
            is AuthenticationStep.Failure -> failureState(step)
        }
    }

    private fun failureState(step: AuthenticationStep.Failure): AuthenticationUiState {
        val current = mutableState.value
        if (step.category != AuthenticationFailure.RATE_LIMITED) {
            clearRetryBlock()
            return AuthenticationUiState(
                destination = failureDestination(current.destination),
                passwordPurpose = current.passwordPurpose,
                message = step.category.toMessage(),
            )
        }

        val retryAfterMillis = (step.retryAfterMillis ?: DEFAULT_RATE_LIMIT_DELAY_MILLIS)
            .coerceAtLeast(MIN_RATE_LIMIT_DELAY_MILLIS)
        val now = monotonicClock.elapsedRealtimeMillis()
        val deadline = if (Long.MAX_VALUE - now < retryAfterMillis) {
            Long.MAX_VALUE
        } else {
            now + retryAfterMillis
        }
        scheduleRetryRelease(deadline)
        return AuthenticationUiState(
            destination = failureDestination(current.destination),
            passwordPurpose = current.passwordPurpose,
            isRetryBlocked = true,
            retryAfterMillis = retryAfterMillis,
            retryBlockedUntilElapsedRealtimeMillis = deadline,
            message = step.category.toMessage(),
        )
    }

    private fun scheduleRetryRelease(deadline: Long) {
        retryReleaseJob?.cancel()
        val revision = operationRevision
        retryReleaseJob = viewModelScope.launch {
            var remaining = (deadline - monotonicClock.elapsedRealtimeMillis()).coerceAtLeast(0)
            while (remaining > 0) {
                delay(minOf(remaining, MAX_RATE_LIMIT_TIMER_SLICE_MILLIS))
                remaining = (deadline - monotonicClock.elapsedRealtimeMillis()).coerceAtLeast(0)
            }
            if (revision == operationRevision) {
                mutableState.value = mutableState.value.copy(
                    isRetryBlocked = false,
                    retryAfterMillis = null,
                    retryBlockedUntilElapsedRealtimeMillis = null,
                )
            }
        }
    }

    private fun clearRetryBlock() {
        retryReleaseJob?.cancel()
        retryReleaseJob = null
    }

    private fun failureDestination(current: AuthenticationDestination): AuthenticationDestination = when (current) {
        AuthenticationDestination.CODE -> AuthenticationDestination.CODE
        AuthenticationDestination.PASSWORD -> AuthenticationDestination.PASSWORD
        else -> AuthenticationDestination.SIGN_IN
    }

    private fun AuthenticationFailure.toMessage(): AuthenticationMessage = when (this) {
        AuthenticationFailure.REJECTED -> AuthenticationMessage.REJECTED
        AuthenticationFailure.ACCOUNT_ALREADY_CONNECTED -> AuthenticationMessage.ACCOUNT_ALREADY_CONNECTED
        AuthenticationFailure.OFFLINE -> AuthenticationMessage.OFFLINE
        AuthenticationFailure.TIMED_OUT -> AuthenticationMessage.TIMED_OUT
        AuthenticationFailure.RATE_LIMITED -> AuthenticationMessage.RATE_LIMITED
        AuthenticationFailure.CRYPTOGRAPHIC_FAILURE -> AuthenticationMessage.CRYPTOGRAPHIC_FAILURE
        AuthenticationFailure.CLIENT_UNSUPPORTED -> AuthenticationMessage.CLIENT_UNSUPPORTED
        AuthenticationFailure.CANCELLED,
        AuthenticationFailure.TRY_AGAIN,
        -> AuthenticationMessage.TRY_AGAIN
    }

    private fun admitOrClear(vararg sources: CharArray): Long? {
        if (mutableState.value.isRetryBlocked) {
            sources.forEach { it.clearSecret() }
            return null
        }
        if (!operationAdmitted.compareAndSet(false, true)) {
            sources.forEach { it.clearSecret() }
            return null
        }
        operationRevision += 1
        return operationRevision
    }

    private fun rejectLocal(
        revision: Long,
        destination: AuthenticationDestination,
        purpose: PasswordPurpose? = null,
    ) {
        if (revision == operationRevision) {
            operationAdmitted.set(false)
            mutableState.value = AuthenticationUiState(
                destination = destination,
                passwordPurpose = purpose,
                message = AuthenticationMessage.INPUT_REQUIRED,
            )
        }
    }

    private fun CharArray.clearSecret() = fill('\u0000')

    override fun onCleared() {
        retryReleaseJob?.cancel()
        retryReleaseJob = null
        activeSecrets.forEach(AuthenticationSecret::close)
        activeSecrets = emptyList()
        // ViewModel teardown cannot safely await suspend cleanup. On the next process lifecycle,
        // restore() clears unusable/pending-second-factor sessions and reconstructs only resumable
        // password states without restoring any UI secret input.
        super.onCleared()
    }

    companion object {
        private const val MAX_CODE_LENGTH = 128
        private const val MIN_RATE_LIMIT_DELAY_MILLIS = 1_000L
        private const val DEFAULT_RATE_LIMIT_DELAY_MILLIS = 30_000L
        private const val MAX_RATE_LIMIT_TIMER_SLICE_MILLIS = 15 * 60_000L

        fun factory(port: AuthenticationFlowPort): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AuthenticationViewModel(port) as T
            }
    }
}
