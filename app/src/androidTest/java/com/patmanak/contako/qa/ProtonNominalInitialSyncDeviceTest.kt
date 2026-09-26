package com.patmanak.contako.qa

import android.os.Bundle
import android.os.SystemClock
import android.Manifest
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.contako.ContakoApplication
import com.patmanak.contako.BuildConfig
import com.patmanak.contako.MainActivity
import com.patmanak.contako.R
import com.patmanak.contako.android.account.ContakoAndroidAccountContract
import com.patmanak.contako.data.gateway.GatewayOutcome
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.data.sync.RoomSyncStatusStore
import com.patmanak.contako.data.sync.SyncHealthState
import com.patmanak.contako.domain.sync.SyncActivity
import com.patmanak.contako.qa.gatec.GateCCredentialReceiver
import com.patmanak.contako.ui.auth.AuthenticationTestTags
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production-UI PN-02; normal startup owns provisioning and automatic initial sync. */
@RunWith(AndroidJUnit4::class)
class ProtonNominalInitialSyncDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun signInThroughProductionUiAndRetainSessionForNormalStartup() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString(ARG_MODE) == MODE_RUN)
        require(BuildConfig.APPLICATION_ID == ProtonNominalCandidateResidueCleaner.CANDIDATE_APPLICATION_ID) {
            "PN02_CANDIDATE_ISOLATION"
        }
        require(ContakoAndroidAccountContract.ACCOUNT_TYPE == BuildConfig.APPLICATION_ID) {
            "PN02_CANDIDATE_ACCOUNT_TYPE"
        }
        val runId = requireNotNull(arguments.getString(ARG_RUN_ID))
        require(runId.matches(Regex("^[a-f0-9]{32}$")))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as ContakoApplication
        val runtime = application.protonGateCRuntime
        var username: CharArray? = null
        var password: CharArray? = null
        var stage = "CREDENTIAL_CHANNEL"
        var result = "FAIL"
        var closedFailure = "NONE"
        var authentication = "NOT_REACHED"
        var firstReadyAt = -1L
        var initialImportMillis = -1L

        GateCCredentialReceiver(
            socketName = "contako.pn02.$runId",
            expectedRunId = runId,
            expectedPeerUid = GateCCredentialReceiver.DEFAULT_ADB_SHELL_UID,
            acceptTimeoutMillis = 30_000,
            readTimeoutMillis = 10_000,
        ).receiveOnce().use { brokerSession ->
            try {
                username = brokerSession.lease.withUsernameBytes(::decodeOwnedUtf8)
                password = brokerSession.lease.withPasswordBytes(::decodeOwnedUtf8)
                brokerSession.lease.close()

                stage = "PRODUCTION_UI_SIGN_IN"
                check(runBlocking {
                    application.contactRepository.observeContacts(runtime.accountScope.value).first().isEmpty() &&
                        application.contactRepository.observeGroups(runtime.accountScope.value).first().isEmpty()
                }) { "PN02_LOCAL_BASELINE_NOT_EMPTY" }
                compose.waitUntil(AUTH_UI_TIMEOUT_MILLIS) {
                    compose.onAllNodesWithTag(AuthenticationTestTags.USERNAME)
                        .fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag(AuthenticationTestTags.USERNAME).assertExists()
                compose.onNodeWithTag(AuthenticationTestTags.USERNAME)
                    .performTextInput(String(requireNotNull(username)))
                compose.onNodeWithTag(AuthenticationTestTags.SIGN_IN_PASSWORD)
                    .performTextInput(String(requireNotNull(password)))
                compose.onNodeWithTag(AuthenticationTestTags.SIGN_IN_SUBMIT).performClick()
                username?.fill('\u0000')
                password?.fill('\u0000')

                stage = "DURABLE_SESSION_HANDOFF"
                closedFailure = "AUTHENTICATION_TIMEOUT"
                var consecutiveIdentityObservations = 0
                var nextIdentityObservationAt = 0L
                // Compose's test clock must keep pumping frames while the real authentication
                // completes. Sleeping alone freezes the READY recomposition and its provisioning
                // effect, even though the network has already stored a durable session.
                compose.waitUntil(AUTH_READY_TIMEOUT_MILLIS) {
                    val now = SystemClock.elapsedRealtime()
                    if (now < nextIdentityObservationAt) return@waitUntil false
                    nextIdentityObservationAt = now + OBSERVATION_INTERVAL_MILLIS
                    ProtonNominalExternalPromptDismissal.dismissPasswordSavePrompt(instrumentation)
                    visibleAuthenticationFailure()?.let { category ->
                        closedFailure = category
                        error("PN02_VISIBLE_AUTHENTICATION_FAILURE")
                    }
                    val addressAvailable = runCatching {
                        runBlocking { runtime.currentAccountAddress() }
                    }.getOrNull() != null
                    if (addressAvailable && firstReadyAt < 0L) firstReadyAt = SystemClock.elapsedRealtime()
                    consecutiveIdentityObservations = if (addressAvailable) {
                        consecutiveIdentityObservations + 1
                    } else {
                        0
                    }
                    consecutiveIdentityObservations >= REQUIRED_IDENTITY_OBSERVATIONS
                }
                authentication = "READY"
                stage = "AUTOMATIC_INITIAL_IMPORT"
                closedFailure = "INITIAL_IMPORT_TIMEOUT"
                val database = ContakoDatabase.create(instrumentation.targetContext)
                try {
                    val statusStore = RoomSyncStatusStore(database)
                    var complete = false
                    var nextStatusObservationAt = 0L
                    var idleSince = -1L
                    val remainingMillis = INITIAL_IMPORT_TIMEOUT_MILLIS -
                        (SystemClock.elapsedRealtime() - firstReadyAt)
                    try {
                        compose.waitUntil(remainingMillis.coerceAtLeast(1L)) {
                            val now = SystemClock.elapsedRealtime()
                            if (now < nextStatusObservationAt) return@waitUntil false
                            nextStatusObservationAt = now + OBSERVATION_INTERVAL_MILLIS
                            val status = runBlocking { statusStore.load(runtime.accountScope.value) }
                            val activity = runBlocking {
                                application.syncRecoveryDataSource.observeActivity(runtime.accountScope.value).first()
                            }
                            val settled = status?.state == SyncHealthState.IDLE &&
                                status.lastSuccessAtEpochMillis != null &&
                                status.pendingMutationCount == 0 && status.actionRequiredCount == 0 &&
                                activity == SyncActivity.IDLE
                            // Android may submit its own coalesced follow-up after projection.
                            // A transient idle sample between passes is not settled completion.
                            idleSince = if (!settled) -1L else if (idleSince < 0L) now else idleSince
                            complete = idleSince >= 0L && now - idleSince >= SETTLED_IDLE_MILLIS
                            complete
                        }
                    } finally {
                        initialImportMillis = SystemClock.elapsedRealtime() - firstReadyAt
                        if (!complete) {
                            // Retain the real partial state as aggregate diagnostics before failing.
                            runCatching { ProtonNominalCandidateStateProbeDeviceTest().verifyCandidateState() }
                        }
                    }
                    check(complete) { "PN02_INITIAL_IMPORT_TIMEOUT" }
                    stage = "INITIAL_IMPORT_ORACLE"
                    closedFailure = "INITIAL_IMPORT_ORACLE"
                    ProtonNominalCandidateStateProbeDeviceTest().verifyCandidateState()
                    initialImportMillis = SystemClock.elapsedRealtime() - firstReadyAt
                    check(initialImportMillis <= INITIAL_IMPORT_TIMEOUT_MILLIS) { "PN02_INITIAL_IMPORT_BUDGET" }
                } finally {
                    database.close()
                }
                stage = "AUTHENTICATION_HANDOFF_COMPLETE"
                closedFailure = "CREDENTIAL_ACK"
                brokerSession.completePass()
                result = "PASS"
                closedFailure = "NONE"
            } catch (_: Throwable) {
                if (closedFailure == "NONE") closedFailure = "INVARIANT"
            } finally {
                username?.fill('\u0000')
                password?.fill('\u0000')
                brokerSession.lease.close()
                if (result != "PASS" && authentication != "READY") {
                    val revocationSucceeded = runCatching {
                        runBlocking {
                            runtime.session.revokeAndClear(runtime.accountScope)
                        } is GatewayOutcome.Success
                    }.getOrDefault(false)
                    val addressCleared = runCatching {
                        runBlocking { runtime.currentAccountAddress() == null }
                    }.getOrDefault(false)
                    val residueCleaned = runCatching {
                        instrumentation.uiAutomation.grantRuntimePermission(
                            BuildConfig.APPLICATION_ID,
                            Manifest.permission.READ_CONTACTS,
                        )
                        instrumentation.uiAutomation.grantRuntimePermission(
                            BuildConfig.APPLICATION_ID,
                            Manifest.permission.WRITE_CONTACTS,
                        )
                        ProtonNominalCandidateResidueCleaner.removeAndVerify(
                            instrumentation.targetContext,
                        )
                    }.isSuccess
                    if (!revocationSucceeded || !addressCleared || !residueCleaned) {
                        closedFailure = "CLEANUP_FAILED"
                    }
                }
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("pn02_result", result)
                    putString("pn02_stage", stage)
                    putString("pn02_failure", closedFailure)
                    putString("pn02_authentication", authentication)
                    putLong("pn02_initial_import_millis", initialImportMillis)
                    putString("pn02_session_retained", if (authentication == "READY") "YES" else "NO")
                    putString("pn02_handoff", if (result == "PASS") "PASS" else "FAIL")
                })
            }
        }

        assertEquals("PASS", result)
    }

    private fun decodeOwnedUtf8(source: ByteArray): CharArray =
        Pn02Utf8Decoder(source.size).use { it.decode(source) }

    private fun visibleAuthenticationFailure(): String? {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return listOf(
            "AUTH_HUMAN_VERIFICATION" to R.string.auth_human_title,
            "AUTH_RATE_LIMITED" to R.string.auth_rate_limited,
            "AUTH_REJECTED" to R.string.auth_invalid_retry,
            "AUTH_OFFLINE" to R.string.auth_offline,
            "AUTH_TIMEOUT" to R.string.auth_timed_out,
            "AUTH_CRYPTOGRAPHIC_FAILURE" to R.string.auth_cryptographic_failure,
            "AUTH_UNSUPPORTED" to R.string.auth_client_unsupported,
            "AUTH_GENERIC" to R.string.auth_try_again,
        ).firstOrNull { (_, message) ->
            compose.onAllNodesWithText(context.getString(message)).fetchSemanticsNodes().isNotEmpty()
        }?.first
    }

    private companion object {
        const val ARG_MODE = "pn02Mode"
        const val ARG_RUN_ID = "pn02Run"
        const val MODE_RUN = "production_ui_initial_sync"
        const val AUTH_UI_TIMEOUT_MILLIS = 15_000L
        const val AUTH_READY_TIMEOUT_MILLIS = 90_000L
        const val REQUIRED_IDENTITY_OBSERVATIONS = 8
        const val OBSERVATION_INTERVAL_MILLIS = 250L
        const val SETTLED_IDLE_MILLIS = 2_000L
        const val INITIAL_IMPORT_TIMEOUT_MILLIS = 90_000L
    }
}

private class Pn02Utf8Decoder(maximumChars: Int) : Closeable {
    private val scratch = CharArray(maximumChars)

    fun decode(source: ByteArray): CharArray {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val output = CharBuffer.wrap(scratch)
        val input = ByteBuffer.wrap(source)
        val decoded = decoder.decode(input, output, true)
        if (decoded.isError) decoded.throwException()
        check(decoded.isUnderflow) { "PN02_CREDENTIAL_ENCODING" }
        val flushed = decoder.flush(output)
        if (flushed.isError) flushed.throwException()
        check(flushed.isUnderflow) { "PN02_CREDENTIAL_ENCODING" }
        return scratch.copyOf(output.position())
    }

    override fun close() {
        scratch.fill('\u0000')
    }
}
