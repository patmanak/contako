package com.patmanak.contako.qa.gatec

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataOutputStream
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class GateCCredentialSocketTest {
    @Test
    fun externalSyntheticRoundTripUsesShellPeerAndZerosOwnedBuffers() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString(ARG_MODE) == MODE_EXTERNAL)
        val runId = requireNonSecretRunId(arguments.getString(ARG_RUN_ID))
        val expectedUid = arguments.getString(ARG_EXPECTED_UID)?.toIntOrNull()
            ?: GateCCredentialReceiver.DEFAULT_ADB_SHELL_UID
        val receiver = GateCCredentialReceiver(
            socketName = socketName(runId),
            expectedRunId = runId,
            expectedPeerUid = expectedUid,
        )

        receiver.receiveOnce().use { session ->
            var usernameWasNonZero = false
            var passwordWasNonZero = false
            session.lease.withUsernameBytes { usernameWasNonZero = it.any { value -> value != 0.toByte() } }
            session.lease.withPasswordBytes { passwordWasNonZero = it.any { value -> value != 0.toByte() } }
            assertTrue("SYNTHETIC_USERNAME_EMPTY", usernameWasNonZero)
            assertTrue("SYNTHETIC_PASSWORD_EMPTY", passwordWasNonZero)
            session.lease.close()
            assertTrue("OWNED_BUFFERS_NOT_ZERO", session.lease.ownedBuffersAreZero())
            session.completePass()
        }
    }

    @Test
    fun externalTimeoutFailsClosedWithoutCredentialMaterial() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString(ARG_MODE) == MODE_TIMEOUT)
        val runId = requireNonSecretRunId(arguments.getString(ARG_RUN_ID))
        val timeoutMillis = arguments.getString(ARG_TIMEOUT_MILLIS)?.toLongOrNull() ?: 1_500L
        val started = System.nanoTime()
        val failure = runCatching {
            GateCCredentialReceiver(
                socketName = socketName(runId),
                expectedRunId = runId,
                acceptTimeoutMillis = timeoutMillis,
            ).receiveOnce()
        }.exceptionOrNull()
        assertTrue("TIMEOUT_NOT_REDACTED", failure is GateCProtocolException)
        assertEquals(GateCFailureCode.ACCEPT_TIMEOUT, (failure as GateCProtocolException).code)
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue("TIMEOUT_TOO_EARLY", elapsedMillis >= timeoutMillis - 200L)
        assertTrue("TIMEOUT_UNBOUNDED", elapsedMillis < timeoutMillis + 5_000L)
    }

    @Test
    fun leaseAlwaysZerosUsernameAndPasswordBuffers() {
        val username = generatedBytes(23, 0x31)
        val password = generatedBytes(47, 0x51)
        val lease = GateCCredentialLease(username, password)
        lease.close()
        assertTrue("LEASE_NOT_ZERO", lease.ownedBuffersAreZero())
        assertTrue("USERNAME_ALIAS_NOT_ZERO", username.all { it == 0.toByte() })
        assertTrue("PASSWORD_ALIAS_NOT_ZERO", password.all { it == 0.toByte() })
    }

    @Test
    fun wrongRunIdAndExpiredFrameFailClosed() {
        assertProtocolFailure(GateCFailureCode.RUN_ID) { runId, output ->
            writeFrame(output, differentRunId(runId), System.currentTimeMillis() + 10_000L)
        }
        assertProtocolFailure(GateCFailureCode.EXPIRATION) { runId, output ->
            writeFrame(output, runId, System.currentTimeMillis() - 1L)
        }
    }

    @Test
    fun immediateCredentialConnectionAfterReceiverSubmissionDoesNotRaceSocketRegistration() {
        repeat(20) {
            assertProtocolFailure(GateCFailureCode.RUN_ID) { runId, output ->
                writeFrame(output, differentRunId(runId), System.currentTimeMillis() + 10_000L)
            }
        }
    }

    @Test
    fun oversizedPasswordIsRejectedBeforePayloadRead() {
        assertProtocolFailure(GateCFailureCode.LENGTH) { runId, output ->
            output.write(GATE_C_MAGIC)
            output.writeInt(GATE_C_PROTOCOL_VERSION)
            val runIdBytes = runId.encodeToByteArray()
            try {
                output.writeInt(runIdBytes.size)
                output.write(runIdBytes)
            } finally {
                runIdBytes.fill(0)
            }
            output.writeLong(System.currentTimeMillis() + 10_000L)
            output.writeInt(8)
            output.writeInt(GateCCredentialReceiver.MAX_PASSWORD_BYTES + 1)
            output.flush()
        }
    }

    private fun assertProtocolFailure(
        expected: GateCFailureCode,
        writer: (String, DataOutputStream) -> Unit,
    ) {
        val runId = UUID.randomUUID().toString().replace("-", "")
        val name = socketName(runId)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit(Callable {
                runCatching {
                    GateCCredentialReceiver(
                        socketName = name,
                        expectedRunId = runId,
                        expectedPeerUid = Process.myUid(),
                        acceptTimeoutMillis = 5_000L,
                    ).receiveOnce()
                }.exceptionOrNull()
            })
            connectWithRetry(name).use { socket ->
                DataOutputStream(socket.outputStream).use { output -> writer(runId, output) }
            }
            val failure = result.get(5, TimeUnit.SECONDS)
            assertTrue("FAILURE_NOT_REDACTED", failure is GateCProtocolException)
            assertEquals(expected, (failure as GateCProtocolException).code)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun writeFrame(output: DataOutputStream, runId: String, expiresAtMillis: Long) {
        val runIdBytes = runId.encodeToByteArray()
        val username = generatedBytes(16, 0x41)
        val password = generatedBytes(32, 0x61)
        try {
            output.write(GATE_C_MAGIC)
            output.writeInt(GATE_C_PROTOCOL_VERSION)
            output.writeInt(runIdBytes.size)
            output.write(runIdBytes)
            output.writeLong(expiresAtMillis)
            output.writeInt(username.size)
            output.writeInt(password.size)
            output.write(username)
            output.write(password)
            output.flush()
        } finally {
            runIdBytes.fill(0)
            username.fill(0)
            password.fill(0)
        }
    }

    private fun connectWithRetry(socketName: String): LocalSocket {
        repeat(50) {
            val socket = LocalSocket()
            try {
                socket.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
                return socket
            } catch (_: Exception) {
                runCatching { socket.close() }
                Thread.sleep(20L)
            }
        }
        throw AssertionError("SOCKET_NOT_READY")
    }

    private fun generatedBytes(size: Int, base: Int): ByteArray =
        ByteArray(size) { index -> (base + (index % 23)).toByte() }

    private fun differentRunId(runId: String): String =
        runId.dropLast(1) + if (runId.last() == '0') '1' else '0'

    private fun requireNonSecretRunId(value: String?): String {
        require(value != null && value.matches(Regex("^[a-f0-9]{32}$"))) { "RUN_ID_INVALID" }
        return value
    }

    private fun socketName(runId: String): String = "contako.gatec.$runId"

    private companion object {
        const val ARG_MODE = "gatec_mode"
        const val ARG_RUN_ID = "gatec_run"
        const val ARG_EXPECTED_UID = "gatec_expected_uid"
        const val ARG_TIMEOUT_MILLIS = "gatec_timeout_ms"
        const val MODE_EXTERNAL = "external"
        const val MODE_TIMEOUT = "timeout"
    }
}
