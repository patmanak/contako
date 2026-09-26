package com.patmanak.contako.domain.auth

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Mutable one-shot authentication input owned by the active operation.
 *
 * Ownership transfer clears the caller's array immediately. Consumption and close both clear the
 * owned array, and the value is never exposed through equality, hashing, or toString.
 */
internal class AuthenticationSecret private constructor(private val value: CharArray) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    internal fun <T> consume(block: (CharArray) -> T): T {
        check(closed.compareAndSet(false, true))
        return try {
            block(value)
        } finally {
            value.fill('\u0000')
        }
    }

    internal suspend fun <T> consumeSuspend(block: suspend (CharArray) -> T): T {
        check(closed.compareAndSet(false, true))
        return try {
            block(value)
        } finally {
            value.fill('\u0000')
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) value.fill('\u0000')
    }

    override fun toString(): String = "AuthenticationSecret(REDACTED)"

    companion object {
        fun takeAndClear(source: CharArray): AuthenticationSecret {
            val owned = try {
                require(source.isNotEmpty())
                require(source.size <= MAX_SECRET_LENGTH)
                source.copyOf()
            } finally {
                source.fill('\u0000')
            }
            return AuthenticationSecret(owned)
        }

        private const val MAX_SECRET_LENGTH = 16_384
    }
}

internal enum class AuthenticationFailure {
    REJECTED,
    ACCOUNT_ALREADY_CONNECTED,
    OFFLINE,
    TIMED_OUT,
    RATE_LIMITED,
    CRYPTOGRAPHIC_FAILURE,
    CLIENT_UNSUPPORTED,
    CANCELLED,
    TRY_AGAIN,
}

internal enum class PasswordPurpose {
    MAILBOX,
    KEY_UNLOCK,
}

internal sealed interface AuthenticationStep {
    data object SignIn : AuthenticationStep
    data object CodeRequired : AuthenticationStep
    data class PasswordRequired(val purpose: PasswordPurpose) : AuthenticationStep
    data object SecurityKeyOnlyUnsupported : AuthenticationStep

    /**
     * Fail-closed UI boundary for a future maintained HV presentation contract.
     * No implementation may infer this state from an unknown transport or server failure.
     */
    data object HumanVerificationUnavailable : AuthenticationStep

    data object Ready : AuthenticationStep
    data class Failure(
        val category: AuthenticationFailure,
        val retryAfterMillis: Long? = null,
    ) : AuthenticationStep {
        init {
            require(retryAfterMillis == null || retryAfterMillis >= 0)
        }
    }
}

/** Application-facing authentication use-case boundary; implementations consume secrets in-call. */
internal interface AuthenticationFlowPort {
    suspend fun restore(): AuthenticationStep
    suspend fun signIn(username: AuthenticationSecret, password: AuthenticationSecret): AuthenticationStep
    suspend fun submitCode(code: AuthenticationSecret): AuthenticationStep
    suspend fun unlock(password: AuthenticationSecret): AuthenticationStep
    suspend fun cancel()
}
