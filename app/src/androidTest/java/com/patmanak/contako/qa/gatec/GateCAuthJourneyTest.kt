package com.patmanak.contako.qa.gatec

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.contako.ContakoApplication
import com.patmanak.contako.MainActivity
import com.patmanak.contako.data.gateway.AuthenticationState
import com.patmanak.contako.data.gateway.GatewayOutcome
import com.patmanak.contako.data.gateway.OperationSecret
import com.patmanak.contako.data.gateway.SessionState
import com.patmanak.contako.data.proton.GateCRequestAudit
import com.patmanak.contako.data.proton.GateCRequestClass
import com.patmanak.contako.data.proton.GateCHumanVerificationClientIdState
import com.patmanak.contako.data.proton.GateCAuthDiagnostic
import com.patmanak.contako.data.proton.GateCAuthDiagnosticClass
import com.patmanak.contako.data.proton.GateCAuthDiagnosticEvent
import com.patmanak.contako.data.proton.GateCAuthPhase
import com.patmanak.contako.data.proton.ProtonGateCRuntime
import com.patmanak.contako.data.proton.classifyGateCRequest
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GateCAuthJourneyTest {
    @Test
    fun requestAuditReservesTheEightiethExchangeOnlyForOneSessionRevoke() {
        val audit = BoundedGateCRequestAudit(MAX_LIVE_REQUESTS)
        repeat(MAX_NORMAL_REQUESTS) { audit.onRequest(GateCRequestClass.OTHER) }

        val blockedNormal = runCatching { audit.onRequest(GateCRequestClass.CONTACT) }.exceptionOrNull()
        val afterBlockedNormal = audit.snapshot()
        audit.onRequest(GateCRequestClass.SESSION_REVOKE)
        val afterReservedRevoke = audit.snapshot()
        val blockedSecondRevoke = runCatching {
            audit.onRequest(GateCRequestClass.SESSION_REVOKE)
        }.exceptionOrNull()
        val blockedEightyFirst = runCatching { audit.onRequest(GateCRequestClass.OTHER) }.exceptionOrNull()
        val terminal = audit.snapshot()

        assertTrue("NORMAL_LIMIT_NOT_CLOSED", blockedNormal is IOException)
        assertEquals(MAX_NORMAL_REQUESTS, afterBlockedNormal.total)
        assertEquals(MAX_NORMAL_REQUESTS, afterBlockedNormal.normal)
        assertEquals(0, afterBlockedNormal.contact)
        assertEquals(0, afterBlockedNormal.sessionRevoke)

        assertEquals(MAX_LIVE_REQUESTS, afterReservedRevoke.total)
        assertEquals(MAX_NORMAL_REQUESTS, afterReservedRevoke.normal)
        assertEquals(1, afterReservedRevoke.sessionRevoke)

        assertTrue("SECOND_REVOKE_NOT_CLOSED", blockedSecondRevoke is IOException)
        assertTrue("EIGHTY_FIRST_NOT_CLOSED", blockedEightyFirst is IOException)
        assertEquals(MAX_LIVE_REQUESTS, terminal.total)
        assertEquals(1, terminal.sessionRevoke)
        assertEquals(3, terminal.blocked)
    }

    @Test
    fun onlyExactDeleteAuthV4IsClassifiedAsSessionRevoke() {
        assertEquals(
            GateCRequestClass.SESSION_REVOKE,
            classifyGateCRequest("DELETE", listOf("auth", "v4")),
        )
        assertEquals(
            GateCRequestClass.OTHER,
            classifyGateCRequest("POST", listOf("auth", "v4")),
        )
        assertEquals(
            GateCRequestClass.OTHER,
            classifyGateCRequest("DELETE", listOf("auth", "v4", "")),
        )
        assertEquals(
            GateCRequestClass.OTHER,
            classifyGateCRequest("DELETE", listOf("auth", "v4", "sessions")),
        )
        assertEquals(
            GateCRequestClass.OTHER,
            classifyGateCRequest("delete", listOf("auth", "v4")),
        )
        assertEquals(
            GateCRequestClass.OTHER,
            classifyGateCRequest("DELETE", listOf("AUTH", "V4")),
        )
        assertEquals(
            GateCRequestClass.CONTACT,
            classifyGateCRequest("GET", listOf("contacts", "v4")),
        )
        assertEquals(
            GateCRequestClass.GROUP,
            classifyGateCRequest("GET", listOf("core", "v4", "labels")),
        )
    }

    @Test
    fun sessionRevokeReserveAlsoWorksBeforeNormalTrafficCompletes() {
        val audit = BoundedGateCRequestAudit(MAX_LIVE_REQUESTS)
        audit.onRequest(GateCRequestClass.SESSION_REVOKE)
        repeat(MAX_NORMAL_REQUESTS) { audit.onRequest(GateCRequestClass.OTHER) }
        val failure = runCatching { audit.onRequest(GateCRequestClass.OTHER) }.exceptionOrNull()
        val snapshot = audit.snapshot()

        assertTrue("EIGHTY_FIRST_NOT_CLOSED", failure is IOException)
        assertEquals(MAX_LIVE_REQUESTS, snapshot.total)
        assertEquals(MAX_NORMAL_REQUESTS, snapshot.normal)
        assertEquals(1, snapshot.sessionRevoke)
        assertEquals(1, snapshot.blocked)
    }

    @Test
    fun authDiagnosticCollectorKeepsOnlyClosedAggregateFields() {
        val collector = GateCAuthDiagnosticCollector()
        collector.onPhase(GateCAuthPhase.AUTH_INFO)
        collector.onFailure(
            GateCAuthDiagnosticEvent(
                diagnosticClass = GateCAuthDiagnosticClass.API_HTTP,
                httpCode = 422,
                protonCode = 8002,
            ),
        )

        val snapshot = collector.snapshot()
        assertEquals("AUTH_INFO", snapshot.phase)
        assertEquals("API_HTTP", snapshot.diagnosticClass)
        assertEquals(422, snapshot.httpCode)
        assertEquals(8002, snapshot.protonCode)
    }

    @Test
    fun externalVaultAuthJourneyReachesReadyRestoresAndAlwaysRevokes() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString(ARG_MODE) == MODE_VAULT_LIVE)
        val runId = requireNonSecretRunId(arguments.getString(ARG_RUN_ID))
        val expectedUid = arguments.getString(ARG_EXPECTED_UID)?.toIntOrNull()
            ?: GateCCredentialReceiver.DEFAULT_ADB_SHELL_UID
        val requestLimit = requireRequestLimit(arguments.getString(ARG_REQUEST_LIMIT))
        val requestAudit = BoundedGateCRequestAudit(requestLimit)
        val authDiagnostic = GateCAuthDiagnosticCollector()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val interactiveHumanVerification =
            arguments.getString(ARG_HUMAN_VERIFICATION) == HUMAN_VERIFICATION_INTERACTIVE
        val application = instrumentation.targetContext.applicationContext as ContakoApplication
        var runtime: ProtonGateCRuntime? = null
        var activity: Activity? = null
        var usernameSecret: OperationSecret? = null
        var passwordSecret: OperationSecret? = null
        var authentication = AGGREGATE_NOT_REACHED
        var restore = AGGREGATE_NOT_REACHED
        var cleanup = AGGREGATE_NOT_REACHED

        GateCCredentialReceiver(
            socketName = socketName(runId),
            expectedRunId = runId,
            expectedPeerUid = expectedUid,
            acceptTimeoutMillis = LIVE_ACCEPT_TIMEOUT_MILLIS,
            readTimeoutMillis = LIVE_READ_TIMEOUT_MILLIS,
        ).receiveOnce().use { session ->
            try {
                usernameSecret = OperationSecret.takeAndClear(
                    session.lease.withUsernameBytes(::decodeOwnedUtf8),
                )
                passwordSecret = OperationSecret.takeAndClear(
                    session.lease.withPasswordBytes(::decodeOwnedUtf8),
                )
                // The transport copies are no longer needed once OperationSecret owns both inputs.
                session.lease.close()

                runtime = ProtonGateCRuntime.createForInstrumentedGateC(
                    instrumentation.targetContext,
                    requestAudit,
                    authDiagnostic,
                    humanVerification = if (interactiveHumanVerification) {
                        application.humanVerification.hooks
                    } else {
                        com.patmanak.contako.data.proton.GateCHumanVerificationHooks.FailClosed
                    },
                )
                if (interactiveHumanVerification) {
                    activity = instrumentation.startActivitySync(
                        Intent(instrumentation.targetContext, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    instrumentation.waitForIdleSync()
                }
                val signIn = runBlocking {
                    runtime!!.authentication.signIn(
                        runtime!!.accountScope,
                        usernameSecret!!,
                        passwordSecret!!,
                    )
                }
                authentication = aggregateAuthentication(signIn)
                if (authentication == AGGREGATE_READY) {
                    restore = try {
                        aggregateSession(
                            runBlocking { runtime!!.session.restore(runtime!!.accountScope) },
                        )
                    } catch (_: Exception) {
                        AGGREGATE_REDACTED_EXCEPTION
                    }
                }
            } catch (_: Exception) {
                authentication = AGGREGATE_REDACTED_EXCEPTION
            } finally {
                usernameSecret?.close()
                passwordSecret?.close()
                session.lease.close()
                runBlocking { application.humanVerification.clear() }
                activity?.let { opened ->
                    instrumentation.runOnMainSync { opened.finishAndRemoveTask() }
                    instrumentation.waitForIdleSync()
                }
                cleanup = runtime?.let { created ->
                    try {
                        aggregateCleanup(
                            runBlocking { created.session.revokeAndClear(created.accountScope) },
                        )
                    } catch (_: Exception) {
                        AGGREGATE_REDACTED_EXCEPTION
                    }
                } ?: AGGREGATE_RUNTIME_NOT_CREATED

                val snapshot = requestAudit.snapshot()
                val diagnosticSnapshot = authDiagnostic.snapshot()
                sendAggregateStatus(
                    instrumentation = instrumentation,
                    authentication = authentication,
                    restore = restore,
                    cleanup = cleanup,
                    snapshot = snapshot,
                    requestLimit = requestLimit,
                    authDiagnostic = diagnosticSnapshot,
                )
                val passed = authentication == AGGREGATE_READY &&
                    restore == AGGREGATE_READY &&
                    cleanup == AGGREGATE_SUCCESS &&
                    snapshot.contact == 0 &&
                    snapshot.group == 0 &&
                    snapshot.sessionRevoke == 1 &&
                    snapshot.total <= requestLimit &&
                    snapshot.blocked == 0
                if (passed) session.completePass()
            }

            val snapshot = requestAudit.snapshot()
            assertEquals(AGGREGATE_READY, authentication)
            assertEquals(AGGREGATE_READY, restore)
            assertEquals(AGGREGATE_SUCCESS, cleanup)
            assertEquals(0, snapshot.contact)
            assertEquals(0, snapshot.group)
            assertEquals(1, snapshot.sessionRevoke)
            assertTrue("REQUEST_BUDGET", snapshot.total <= requestLimit)
            assertEquals(0, snapshot.blocked)
            assertTrue("CREDENTIAL_LEASE_NOT_ZERO", session.lease.ownedBuffersAreZero())
        }
    }

    private fun decodeOwnedUtf8(source: ByteArray): CharArray =
        ErasableUtf8Decoder(source.size).use { decoder -> decoder.decode(source) }

    private fun aggregateAuthentication(outcome: GatewayOutcome<AuthenticationState>): String = when (outcome) {
        is GatewayOutcome.Success -> when (outcome.value) {
            AuthenticationState.Ready -> AGGREGATE_READY
            is AuthenticationState.SecondFactorRequired -> "SECOND_FACTOR_REQUIRED"
            AuthenticationState.MailboxPasswordRequired -> "MAILBOX_PASSWORD_REQUIRED"
            AuthenticationState.KeyUnlockRequired -> "KEY_UNLOCK_REQUIRED"
            AuthenticationState.SecurityKeyOnlyUnsupported -> "SECURITY_KEY_ONLY_UNSUPPORTED"
        }
        is GatewayOutcome.Failure -> "FAILURE_${outcome.category.name}"
    }

    private fun aggregateSession(outcome: GatewayOutcome<SessionState>): String = when (outcome) {
        is GatewayOutcome.Success -> outcome.value.name
        is GatewayOutcome.Failure -> "FAILURE_${outcome.category.name}"
    }

    private fun aggregateCleanup(outcome: GatewayOutcome<Unit>): String = when (outcome) {
        is GatewayOutcome.Success -> AGGREGATE_SUCCESS
        is GatewayOutcome.Failure -> "FAILURE_${outcome.category.name}"
    }

    private fun sendAggregateStatus(
        instrumentation: android.app.Instrumentation,
        authentication: String,
        restore: String,
        cleanup: String,
        snapshot: RequestAuditSnapshot,
        requestLimit: Int,
        authDiagnostic: GateCAuthDiagnosticSnapshot,
    ) {
        instrumentation.sendStatus(0, Bundle().apply {
            putString("gatec_authentication_state", authentication)
            putString("gatec_restore_state", restore)
            putString("gatec_cleanup_state", cleanup)
            putInt("gatec_requests_total", snapshot.total)
            putInt("gatec_requests_contact", snapshot.contact)
            putInt("gatec_requests_group", snapshot.group)
            putInt("gatec_requests_other", snapshot.other)
            putInt("gatec_requests_normal", snapshot.normal)
            putInt("gatec_requests_session_revoke", snapshot.sessionRevoke)
            putInt("gatec_requests_blocked", snapshot.blocked)
            putInt("gatec_request_limit", requestLimit)
            putString("gatec_request_budget", if (snapshot.blocked > 0) "EXCEEDED" else "WITHIN_LIMIT")
            putString("gatec_auth_diagnostic_class", authDiagnostic.diagnosticClass)
            putString("gatec_auth_diagnostic_phase", authDiagnostic.phase)
            putInt("gatec_auth_diagnostic_http_code", authDiagnostic.httpCode)
            putInt("gatec_auth_diagnostic_proton_code", authDiagnostic.protonCode)
            putString("gatec_human_verification_client_id", snapshot.humanVerificationClientId)
            putString("gatec_human_verification_listener", snapshot.humanVerificationListener)
        })
    }

    private fun requireRequestLimit(value: String?): Int {
        val parsed = value?.toIntOrNull()
        require(parsed == MAX_LIVE_REQUESTS) { "REQUEST_LIMIT_INVALID" }
        return parsed
    }

    private fun requireNonSecretRunId(value: String?): String {
        require(value != null && value.matches(Regex("^[a-f0-9]{32}$"))) { "RUN_ID_INVALID" }
        return value
    }

    private fun socketName(runId: String): String = "contako.gatec.$runId"

    private companion object {
        const val ARG_MODE = "gatec_mode"
        const val ARG_RUN_ID = "gatec_run"
        const val ARG_EXPECTED_UID = "gatec_expected_uid"
        const val ARG_REQUEST_LIMIT = "gatec_request_limit"
        const val ARG_HUMAN_VERIFICATION = "gatec_human_verification"
        const val MODE_VAULT_LIVE = "vault_live"
        const val HUMAN_VERIFICATION_INTERACTIVE = "interactive"
        const val MAX_LIVE_REQUESTS = 80
        const val MAX_NORMAL_REQUESTS = 79
        const val LIVE_ACCEPT_TIMEOUT_MILLIS = 30_000L
        const val LIVE_READ_TIMEOUT_MILLIS = 10_000
        const val AGGREGATE_NOT_REACHED = "NOT_REACHED"
        const val AGGREGATE_REDACTED_EXCEPTION = "REDACTED_EXCEPTION"
        const val AGGREGATE_RUNTIME_NOT_CREATED = "RUNTIME_NOT_CREATED"
        const val AGGREGATE_READY = "READY"
        const val AGGREGATE_SUCCESS = "SUCCESS"
    }
}

private data class GateCAuthDiagnosticSnapshot(
    val phase: String,
    val diagnosticClass: String,
    val httpCode: Int,
    val protonCode: Int,
)

private class GateCAuthDiagnosticCollector : GateCAuthDiagnostic {
    private val lock = Any()
    private var latestPhase: GateCAuthPhase? = null
    private var latest: GateCAuthDiagnosticEvent? = null

    override fun onPhase(phase: GateCAuthPhase) = synchronized(lock) {
        latestPhase = phase
    }

    override fun onFailure(event: GateCAuthDiagnosticEvent) = synchronized(lock) {
        latest = event
    }

    fun snapshot(): GateCAuthDiagnosticSnapshot = synchronized(lock) {
        GateCAuthDiagnosticSnapshot(
            phase = latestPhase?.name ?: "NONE",
            diagnosticClass = latest?.diagnosticClass?.name ?: "NONE",
            httpCode = latest?.httpCode ?: NO_NUMERIC_CODE,
            protonCode = latest?.protonCode ?: NO_NUMERIC_CODE,
        )
    }

    private companion object {
        const val NO_NUMERIC_CODE = -1
    }
}

private class ErasableUtf8Decoder(maximumChars: Int) : Closeable {
    private val scratch = CharArray(maximumChars)
    private var closed = false

    fun decode(source: ByteArray): CharArray {
        check(!closed) { "DECODER_CLOSED" }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val output = CharBuffer.wrap(scratch)
        val input = ByteBuffer.wrap(source)
        val decoded = decoder.decode(input, output, true)
        if (decoded.isError) decoded.throwException()
        check(decoded.isUnderflow) { "CREDENTIAL_ENCODING" }
        val flushed = decoder.flush(output)
        if (flushed.isError) flushed.throwException()
        check(flushed.isUnderflow) { "CREDENTIAL_ENCODING" }
        return scratch.copyOf(output.position())
    }

    override fun close() {
        scratch.fill('\u0000')
        closed = true
    }
}

private data class RequestAuditSnapshot(
    val total: Int,
    val normal: Int,
    val contact: Int,
    val group: Int,
    val other: Int,
    val sessionRevoke: Int,
    val blocked: Int,
    val humanVerificationClientId: String,
    val humanVerificationListener: String,
)

private class BoundedGateCRequestAudit(
    private val maximumRequests: Int,
) : GateCRequestAudit {
    private val lock = Any()
    private var total = 0
    private var normal = 0
    private var contact = 0
    private var group = 0
    private var other = 0
    private var sessionRevoke = 0
    private var blocked = 0
    private var humanVerificationClientId: GateCHumanVerificationClientIdState? = null
    private var humanVerificationListenerInvoked = false

    init {
        require(maximumRequests in 1..80)
    }

    override fun onRequest(requestClass: GateCRequestClass) = synchronized(lock) {
        val admitted = when (requestClass) {
            GateCRequestClass.SESSION_REVOKE -> sessionRevoke == 0 && total < maximumRequests
            else -> normal < MAXIMUM_NORMAL_REQUESTS && total < maximumRequests
        }
        if (!admitted) {
            blocked += 1
            throw IOException("GATE_C_REQUEST_BUDGET_EXCEEDED")
        }
        total += 1
        when (requestClass) {
            GateCRequestClass.CONTACT -> {
                normal += 1
                contact += 1
            }
            GateCRequestClass.GROUP -> {
                normal += 1
                group += 1
            }
            GateCRequestClass.OTHER -> {
                normal += 1
                other += 1
            }
            GateCRequestClass.SESSION_REVOKE -> sessionRevoke += 1
        }
    }

    override fun onHumanVerificationClientIdState(state: GateCHumanVerificationClientIdState) =
        synchronized(lock) {
            humanVerificationClientId = state
        }

    override fun onHumanVerificationListenerInvoked() = synchronized(lock) {
        humanVerificationListenerInvoked = true
    }

    fun snapshot(): RequestAuditSnapshot = synchronized(lock) {
        RequestAuditSnapshot(
            total = total,
            normal = normal,
            contact = contact,
            group = group,
            other = other,
            sessionRevoke = sessionRevoke,
            blocked = blocked,
            humanVerificationClientId = humanVerificationClientId?.name ?: "NOT_CHECKED",
            humanVerificationListener = if (humanVerificationListenerInvoked) "INVOKED" else "NOT_INVOKED",
        )
    }

    private companion object {
        const val MAXIMUM_NORMAL_REQUESTS = 79
    }
}
