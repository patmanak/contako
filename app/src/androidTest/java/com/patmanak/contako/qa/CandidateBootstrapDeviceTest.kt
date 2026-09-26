package com.patmanak.contako.qa

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.contako.ContakoApplication
import com.patmanak.contako.android.account.AccountProvisioningResult
import com.patmanak.contako.data.gateway.AuthenticationState
import com.patmanak.contako.data.gateway.GatewayOutcome
import com.patmanak.contako.data.gateway.OperationSecret
import com.patmanak.contako.qa.gatec.GateCCredentialReceiver
import com.patmanak.contako.ui.SyncActivity
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Owner-authorized one-shot bootstrap for a fresh candidate; retains the successful product session. */
@RunWith(AndroidJUnit4::class)
class CandidateBootstrapDeviceTest {
    @Test
    fun authenticateProvisionAndImportThroughProductionRuntime() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString(ARG_MODE) == MODE_BOOTSTRAP)
        val runId = requireNotNull(arguments.getString(ARG_RUN_ID)).also {
            require(it.matches(Regex("^[a-f0-9]{32}$"))) { "BOOTSTRAP_RUN_ID_INVALID" }
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as ContakoApplication
        var username: OperationSecret? = null
        var password: OperationSecret? = null
        var authentication = "NOT_REACHED"
        var provisioning = "NOT_REACHED"
        var sync = "NOT_REACHED"
        var contactCount = 0
        var groupCount = 0
        var retainSession = false

        GateCCredentialReceiver(
            socketName = "contako.bootstrap.$runId",
            expectedRunId = runId,
            expectedPeerUid = GateCCredentialReceiver.DEFAULT_ADB_SHELL_UID,
            acceptTimeoutMillis = 30_000,
            readTimeoutMillis = 10_000,
        ).receiveOnce().use { brokerSession ->
            try {
                username = OperationSecret.takeAndClear(
                    brokerSession.lease.withUsernameBytes(::decodeOwnedUtf8),
                )
                password = OperationSecret.takeAndClear(
                    brokerSession.lease.withPasswordBytes(::decodeOwnedUtf8),
                )
                brokerSession.lease.close()

                val runtime = application.protonGateCRuntime
                val auth = runBlocking {
                    runtime.authentication.signIn(runtime.accountScope, username!!, password!!)
                }
                authentication = when (auth) {
                    is GatewayOutcome.Success -> when (auth.value) {
                        AuthenticationState.Ready -> "READY"
                        is AuthenticationState.SecondFactorRequired -> "SECOND_FACTOR_REQUIRED"
                        AuthenticationState.MailboxPasswordRequired -> "MAILBOX_PASSWORD_REQUIRED"
                        AuthenticationState.KeyUnlockRequired -> "KEY_UNLOCK_REQUIRED"
                        AuthenticationState.SecurityKeyOnlyUnsupported -> "SECURITY_KEY_ONLY_UNSUPPORTED"
                    }
                    is GatewayOutcome.Failure -> "FAILURE_${auth.category.name}"
                }
                assertEquals("READY", authentication)

                val address = requireNotNull(runBlocking { runtime.currentAccountAddress() }) {
                    "ACCOUNT_ADDRESS_UNAVAILABLE"
                }
                provisioning = runBlocking { application.provisionAndroidAccount(address) }.name
                assertEquals(AccountProvisioningResult.READY.name, provisioning)

                runBlocking {
                    val completion = async {
                        var observedRunning = false
                        application.syncRecoveryDataSource.observeActivity(runtime.accountScope.value)
                            .onEach { if (it == SyncActivity.RUNNING) observedRunning = true }
                            .first { observedRunning && it == SyncActivity.IDLE }
                    }
                    application.syncRecoveryDataSource.requestSync(runtime.accountScope.value)
                    withTimeout(300_000) { completion.await() }
                    contactCount = application.contactRepository.observeContacts(runtime.accountScope.value).first().size
                    groupCount = application.contactRepository.observeGroups(runtime.accountScope.value).first().size
                }
                sync = "COMPLETE"
                assertEquals(EXPECTED_CONTACTS, contactCount)
                assertTrue("EXPECTED_REMOTE_GROUPS", groupCount > 0)
                retainSession = true
                brokerSession.completePass()
            } finally {
                username?.close()
                password?.close()
                brokerSession.lease.close()
                if (!retainSession) {
                    runBlocking {
                        application.protonGateCRuntime.session.revokeAndClear(
                            application.protonGateCRuntime.accountScope,
                        )
                    }
                }
                instrumentation.sendStatus(2, Bundle().apply {
                    putString(
                        "stream",
                        "CONTAKO_BOOTSTRAP authentication=$authentication provisioning=$provisioning " +
                            "sync=$sync contacts=$contactCount groups=$groupCount retained=$retainSession\n",
                    )
                })
            }
        }
    }

    private fun decodeOwnedUtf8(source: ByteArray): CharArray =
        BootstrapUtf8Decoder(source.size).use { it.decode(source) }

    private companion object {
        const val ARG_MODE = "candidateBootstrapMode"
        const val ARG_RUN_ID = "candidateBootstrapRun"
        const val MODE_BOOTSTRAP = "vault_bootstrap"
        const val EXPECTED_CONTACTS = 101
    }
}

private class BootstrapUtf8Decoder(maximumChars: Int) : Closeable {
    private val scratch = CharArray(maximumChars)

    fun decode(source: ByteArray): CharArray {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val output = CharBuffer.wrap(scratch)
        val decoded = decoder.decode(ByteBuffer.wrap(source), output, true)
        if (decoded.isError) decoded.throwException()
        check(decoded.isUnderflow) { "CREDENTIAL_ENCODING" }
        val flushed = decoder.flush(output)
        if (flushed.isError) flushed.throwException()
        check(flushed.isUnderflow) { "CREDENTIAL_ENCODING" }
        return scratch.copyOf(output.position())
    }

    override fun close() {
        scratch.fill('\u0000')
    }
}
