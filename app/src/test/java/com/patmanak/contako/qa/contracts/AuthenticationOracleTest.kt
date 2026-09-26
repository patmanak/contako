package com.patmanak.contako.qa.contracts

import com.patmanak.contako.data.gateway.AuthenticationState
import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.data.gateway.SecondFactorMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Executable authentication oracle. It deliberately has no Proton adapter dependency. */
class AuthenticationOracleTest {
    @Test
    fun fakeAuthenticationFailuresNeverReachReadyAndExposeOnlyClosedCategories() {
        val fixtures = listOf(
            FakeAuthFailure("bad-password", GatewayFailureCategory.AUTHENTICATION_REQUIRED),
            FakeAuthFailure("unknown-user", GatewayFailureCategory.AUTHENTICATION_REQUIRED),
            FakeAuthFailure("invalid-server-proof", GatewayFailureCategory.CRYPTOGRAPHIC_VERIFICATION_FAILED),
            FakeAuthFailure("timeout", GatewayFailureCategory.TIMEOUT),
            FakeAuthFailure("cancelled", GatewayFailureCategory.CANCELLED),
            FakeAuthFailure("offline", GatewayFailureCategory.NETWORK_UNAVAILABLE),
            FakeAuthFailure("http-422", GatewayFailureCategory.VALIDATION_REJECTED),
            FakeAuthFailure("http-503", GatewayFailureCategory.REMOTE_SERVICE_FAILURE),
        )

        fixtures.forEach { fixture ->
            val machine = ReferenceAuthMachine()
            machine.beginPassword()
            machine.fail(fixture.category)

            assertEquals(AuthPhase.ACTION_REQUIRED, machine.snapshot().phase)
            assertEquals(fixture.category, machine.snapshot().failure)
            assertFalse(machine.snapshot().toString().contains(fixture.fixtureName))
            assertFalse(machine.snapshot().toString().contains("password", ignoreCase = true))
            assertEquals(2, machine.snapshot().transitionCount)
        }
    }

    @Test
    fun repeatedSubmitCancellationAndRestartCannotManufactureSuccess() {
        val machine = ReferenceAuthMachine()
        machine.beginPassword()

        assertTrue(runCatching { machine.beginPassword() }.isFailure)
        machine.cancel()
        assertEquals(AuthPhase.ACTION_REQUIRED, machine.snapshot().phase)
        assertEquals(GatewayFailureCategory.CANCELLED, machine.snapshot().failure)

        val restored = ReferenceAuthMachine.restore(machine.snapshot())
        assertEquals(AuthPhase.IDLE, restored.snapshot().phase)
        assertEquals(null, restored.snapshot().failure)
        assertTrue(runCatching { restored.acceptPassword(emptySet()) }.isFailure)
    }

    @Test
    fun processDeathAtEveryTransientPhaseRestoresFailClosedWithoutSecretState() {
        val transientPhases = listOf(
            AuthPhase.PASSWORD,
            AuthPhase.SECOND_FACTOR,
            AuthPhase.KEY_UNLOCK,
        )

        transientPhases.forEach { phase ->
            val beforeDeath = ReferenceAuthMachine.forTransientPhase(phase).snapshot()
            val restored = ReferenceAuthMachine.restore(beforeDeath)

            assertEquals(AuthPhase.IDLE, restored.snapshot().phase)
            assertEquals(0, restored.snapshot().transitionCount)
            assertFalse(restored.snapshot().toString().contains("secret", ignoreCase = true))
            assertFalse(restored.snapshot().toString().contains("token", ignoreCase = true))
        }
    }

    @Test
    fun totpAndRecoveryProofsRemainOpaqueAndAreNeverNormalizedOrClassified() {
        val totp = " 012345 ".toCharArray()
        val recovery = "Ab- 9_Z".toCharArray()
        val observed = mutableListOf<String>()
        val proof = OpaqueCodeProof { chars -> observed += String(chars) }

        proof.submit(totp)
        proof.submit(recovery)

        assertEquals(listOf(" 012345 ", "Ab- 9_Z"), observed)
        assertTrue(totp.all { it == '\u0000' })
        assertTrue(recovery.all { it == '\u0000' })
        assertEquals("OpaqueCodeProof(submissionCount=2)", proof.toString())
    }

    @Test
    fun offeredFidoMethodSetsKeepCodeExecutableOrFailClosed() {
        val codeOnly = AuthenticationState.SecondFactorRequired.fromOfferedMethods(
            setOf(SecondFactorMethod.CODE),
        )
        val mixed = AuthenticationState.SecondFactorRequired.fromOfferedMethods(
            setOf(SecondFactorMethod.CODE, SecondFactorMethod.SECURITY_KEY),
        )
        val fidoOnly = AuthenticationState.SecondFactorRequired.fromOfferedMethods(
            setOf(SecondFactorMethod.SECURITY_KEY),
        )

        assertTrue(codeOnly is AuthenticationState.SecondFactorRequired)
        assertEquals(setOf(SecondFactorMethod.CODE), (codeOnly as AuthenticationState.SecondFactorRequired).methods)
        assertTrue(mixed is AuthenticationState.SecondFactorRequired)
        assertEquals(
            setOf(SecondFactorMethod.CODE, SecondFactorMethod.SECURITY_KEY),
            (mixed as AuthenticationState.SecondFactorRequired).methods,
        )
        assertSame(AuthenticationState.SecurityKeyOnlyUnsupported, fidoOnly)
        assertTrue(
            runCatching {
                AuthenticationState.SecondFactorRequired.fromOfferedMethods(emptySet())
            }.isFailure,
        )
    }
}

private data class FakeAuthFailure(
    val fixtureName: String,
    val category: GatewayFailureCategory,
)

private enum class AuthPhase {
    IDLE,
    PASSWORD,
    SECOND_FACTOR,
    KEY_UNLOCK,
    READY,
    ACTION_REQUIRED,
}

private data class AuthSnapshot(
    val phase: AuthPhase,
    val failure: GatewayFailureCategory?,
    val transitionCount: Int,
)

private class ReferenceAuthMachine private constructor(
    private var phase: AuthPhase,
    private var failure: GatewayFailureCategory?,
    private var transitionCount: Int,
) {
    constructor() : this(AuthPhase.IDLE, null, 0)

    fun beginPassword() {
        transition(AuthPhase.IDLE, AuthPhase.PASSWORD)
    }

    fun acceptPassword(methods: Set<SecondFactorMethod>) {
        check(phase == AuthPhase.PASSWORD)
        phase = if (methods.isEmpty()) AuthPhase.KEY_UNLOCK else AuthPhase.SECOND_FACTOR
        transitionCount++
    }

    fun fail(category: GatewayFailureCategory) {
        check(phase in setOf(AuthPhase.PASSWORD, AuthPhase.SECOND_FACTOR, AuthPhase.KEY_UNLOCK))
        failure = category
        phase = AuthPhase.ACTION_REQUIRED
        transitionCount++
    }

    fun cancel() = fail(GatewayFailureCategory.CANCELLED)

    fun snapshot(): AuthSnapshot = AuthSnapshot(phase, failure, transitionCount)

    private fun transition(expected: AuthPhase, next: AuthPhase) {
        check(phase == expected)
        phase = next
        transitionCount++
    }

    companion object {
        fun restore(@Suppress("UNUSED_PARAMETER") snapshot: AuthSnapshot): ReferenceAuthMachine =
            ReferenceAuthMachine()

        fun forTransientPhase(phase: AuthPhase): ReferenceAuthMachine {
            require(phase in setOf(AuthPhase.PASSWORD, AuthPhase.SECOND_FACTOR, AuthPhase.KEY_UNLOCK))
            return ReferenceAuthMachine(phase, null, 1)
        }
    }
}

private class OpaqueCodeProof(
    private val consumer: (CharArray) -> Unit,
) {
    private var submissionCount = 0

    fun submit(source: CharArray) {
        val owned = source.copyOf()
        source.fill('\u0000')
        try {
            consumer(owned)
            submissionCount++
        } finally {
            owned.fill('\u0000')
        }
    }

    override fun toString(): String = "OpaqueCodeProof(submissionCount=$submissionCount)"
}
