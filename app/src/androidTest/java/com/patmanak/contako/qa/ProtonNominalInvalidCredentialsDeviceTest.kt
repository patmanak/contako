package com.patmanak.contako.qa

import android.accounts.AccountManager
import android.os.Bundle
import android.Manifest
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.contako.MainActivity
import com.patmanak.contako.R
import com.patmanak.contako.ContakoApplication
import com.patmanak.contako.BuildConfig
import com.patmanak.contako.android.account.ContakoAndroidAccountContract
import com.patmanak.contako.qa.gatec.GateCCredentialReceiver
import com.patmanak.contako.ui.auth.AuthenticationTestTags
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Guarded production-UI PN-01 check. The real password is received only to zero its lease. */
@RunWith(AndroidJUnit4::class)
class ProtonNominalInvalidCredentialsDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun validDedicatedIdentityAndSyntheticWrongPasswordStaySignedOutWithClearError() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString(ARG_MODE) == MODE_RUN)
        require(BuildConfig.APPLICATION_ID == CANDIDATE_APPLICATION_ID) {
            "PN01_CANDIDATE_ISOLATION"
        }
        val runId = requireNotNull(arguments.getString(ARG_RUN_ID))
        require(runId.matches(Regex("^[a-f0-9]{32}$")))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        var username: CharArray? = null
        val wrongPassword = SYNTHETIC_WRONG_PASSWORD.toCharArray()
        var result = "FAIL"
        var failure = "CREDENTIAL_CHANNEL"

        GateCCredentialReceiver(
            socketName = "contako.pn01.$runId",
            expectedRunId = runId,
            expectedPeerUid = GateCCredentialReceiver.DEFAULT_ADB_SHELL_UID,
            acceptTimeoutMillis = 30_000,
            readTimeoutMillis = 10_000,
        ).receiveOnce().use { brokerSession ->
            try {
                username = brokerSession.lease.withUsernameBytes(::decodeOwnedUtf8)
                // PN-01 never uses the real password. Closing the lease zeroes both transport copies.
                brokerSession.lease.close()
                failure = "SIGNED_OUT_UI"
                compose.waitUntil(UI_TIMEOUT_MILLIS) {
                    compose.onAllNodesWithTag(AuthenticationTestTags.USERNAME)
                        .fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag(AuthenticationTestTags.USERNAME)
                    .performTextInput(String(requireNotNull(username)))
                compose.onNodeWithTag(AuthenticationTestTags.SIGN_IN_PASSWORD)
                    .performTextInput(String(wrongPassword))
                compose.onNodeWithTag(AuthenticationTestTags.SIGN_IN_SUBMIT).performClick()
                username?.fill('\u0000')
                wrongPassword.fill('\u0000')

                failure = "VISIBLE_REJECTION"
                val rejection = context.getString(R.string.auth_invalid_retry)
                val classifiedFailures = listOf(
                    "INVALID" to rejection,
                    "RATE_LIMITED" to context.getString(R.string.auth_rate_limited),
                    "TIMEOUT" to context.getString(R.string.auth_timed_out),
                    "HUMAN_VERIFICATION" to context.getString(R.string.auth_human_title),
                    "UNSUPPORTED" to context.getString(R.string.auth_client_unsupported),
                    "GENERIC" to context.getString(R.string.auth_try_again),
                )
                var rejectionCategory = "NONE"
                compose.waitUntil(REJECTION_TIMEOUT_MILLIS) {
                    ProtonNominalExternalPromptDismissal.dismissPasswordSavePrompt(instrumentation)
                    rejectionCategory = classifiedFailures.firstOrNull { (_, message) ->
                        compose.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty()
                    }?.first ?: "NONE"
                    rejectionCategory != "NONE"
                }
                failure = "AUTH_$rejectionCategory"
                check(rejectionCategory == "INVALID") { "PN01_UNEXPECTED_AUTH_RESPONSE" }
                compose.onNodeWithTag(AuthenticationTestTags.USERNAME)
                    .assertTextEquals("", context.getString(R.string.auth_username))
                compose.onNodeWithTag(AuthenticationTestTags.SIGN_IN_PASSWORD)
                    .assertTextEquals("", context.getString(R.string.auth_password))

                failure = "SIGNED_OUT_STATE"
                val accountCount = AccountManager.get(context)
                    .getAccountsByType(ContakoAndroidAccountContract.ACCOUNT_TYPE)
                    .size
                assertEquals(0, accountCount)
                val application = context.applicationContext as ContakoApplication
                assertEquals(null, runBlocking { application.protonGateCRuntime.currentAccountAddress() })
                brokerSession.completePass()
                result = "PASS"
                failure = "NONE"
            } catch (_: Throwable) {
                result = "FAIL"
                if (failure == "NONE") failure = "CREDENTIAL_ACK"
            } finally {
                username?.fill('\u0000')
                wrongPassword.fill('\u0000')
                brokerSession.lease.close()
                if (result != "PASS") {
                    val application = context.applicationContext as ContakoApplication
                    val revocationSucceeded = runCatching {
                        runBlocking {
                            application.protonGateCRuntime.session.revokeAndClear(
                                application.protonGateCRuntime.accountScope,
                            ) is com.patmanak.contako.data.gateway.GatewayOutcome.Success
                        }
                    }.getOrDefault(false)
                    val addressCleared = runCatching {
                        runBlocking { application.protonGateCRuntime.currentAccountAddress() == null }
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
                        ProtonNominalCandidateResidueCleaner.removeAndVerify(context)
                    }.isSuccess
                    if (!revocationSucceeded || !addressCleared || !residueCleaned) {
                        failure = "CLEANUP_FAILED"
                    }
                }
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("pn01_result", result)
                    putString("pn01_failure", failure)
                })
            }
        }

        assertEquals("PASS", result)
        assertEquals("NONE", failure)
    }

    private fun decodeOwnedUtf8(source: ByteArray): CharArray =
        Pn01Utf8Decoder(source.size).use { it.decode(source) }

    private companion object {
        const val ARG_MODE = "pn01Mode"
        const val ARG_RUN_ID = "pn01Run"
        const val MODE_RUN = "invalid_credentials"
        const val UI_TIMEOUT_MILLIS = 15_000L
        const val REJECTION_TIMEOUT_MILLIS = 30_000L
        const val SYNTHETIC_WRONG_PASSWORD = "ctk-pn01-deliberately-wrong"
        const val CANDIDATE_APPLICATION_ID = "com.patmanak.contako.candidate"
    }
}

private class Pn01Utf8Decoder(maximumChars: Int) : Closeable {
    private val scratch = CharArray(maximumChars)

    fun decode(source: ByteArray): CharArray {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val output = CharBuffer.wrap(scratch)
        val input = ByteBuffer.wrap(source)
        val decoded = decoder.decode(input, output, true)
        if (decoded.isError) decoded.throwException()
        check(decoded.isUnderflow) { "PN01_CREDENTIAL_ENCODING" }
        val flushed = decoder.flush(output)
        if (flushed.isError) flushed.throwException()
        check(flushed.isUnderflow) { "PN01_CREDENTIAL_ENCODING" }
        return scratch.copyOf(output.position())
    }

    override fun close() {
        scratch.fill('\u0000')
    }
}
