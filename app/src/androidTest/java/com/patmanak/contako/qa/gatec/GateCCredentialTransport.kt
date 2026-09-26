package com.patmanak.contako.qa.gatec

import android.net.LocalServerSocket
import android.net.LocalSocket
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal enum class GateCFailureCode {
    ACCEPT_TIMEOUT,
    PEER_UID,
    MAGIC,
    VERSION,
    RUN_ID,
    EXPIRATION,
    LENGTH,
    TRUNCATED,
    IO,
}

internal class GateCProtocolException(
    val code: GateCFailureCode,
) : IOException(code.name)

internal class GateCCredentialLease(
    private val usernameBytes: ByteArray,
    private val passwordBytes: ByteArray,
) : Closeable {
    private var closed = false

    fun <T> withUsernameBytes(block: (ByteArray) -> T): T {
        check(!closed) { "LEASE_CLOSED" }
        return block(usernameBytes)
    }

    fun <T> withPasswordBytes(block: (ByteArray) -> T): T {
        check(!closed) { "LEASE_CLOSED" }
        return block(passwordBytes)
    }

    override fun close() {
        usernameBytes.fill(0)
        passwordBytes.fill(0)
        closed = true
    }

    internal fun ownedBuffersAreZero(): Boolean =
        closed && usernameBytes.all { it == 0.toByte() } && passwordBytes.all { it == 0.toByte() }
}

internal class GateCCredentialSession(
    val lease: GateCCredentialLease,
    private val socket: LocalSocket,
) : Closeable {
    private var completed = false

    fun completePass() {
        check(lease.ownedBuffersAreZero()) { "LEASE_NOT_ZERO" }
        check(!completed) { "SESSION_COMPLETED" }
        socket.outputStream.write(GATE_C_PASS_ACK)
        socket.outputStream.flush()
        completed = true
    }

    override fun close() {
        lease.close()
        runCatching { socket.close() }
    }
}

internal class GateCCredentialReceiver(
    private val socketName: String,
    private val expectedRunId: String,
    private val expectedPeerUid: Int = DEFAULT_ADB_SHELL_UID,
    private val acceptTimeoutMillis: Long = DEFAULT_ACCEPT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    fun receiveOnce(): GateCCredentialSession {
        val server = try {
            LocalServerSocket(socketName)
        } catch (_: IOException) {
            throw GateCProtocolException(GateCFailureCode.IO)
        }
        val executor = Executors.newSingleThreadExecutor()
        var socket: LocalSocket? = null
        try {
            val accept = executor.submit<LocalSocket> { server.accept() }
            socket = try {
                accept.get(acceptTimeoutMillis, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                throw GateCProtocolException(GateCFailureCode.ACCEPT_TIMEOUT)
            } catch (_: ExecutionException) {
                throw GateCProtocolException(GateCFailureCode.IO)
            }

            // Close the listener immediately: this transport is deliberately one-shot.
            server.close()
            if (socket.peerCredentials.uid != expectedPeerUid) {
                throw GateCProtocolException(GateCFailureCode.PEER_UID)
            }
            socket.soTimeout = readTimeoutMillis
            val lease = readFrame(socket)
            return GateCCredentialSession(lease, socket).also { socket = null }
        } catch (error: GateCProtocolException) {
            throw error
        } catch (_: IOException) {
            throw GateCProtocolException(GateCFailureCode.IO)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw GateCProtocolException(GateCFailureCode.IO)
        } finally {
            runCatching { server.close() }
            runCatching { socket?.close() }
            executor.shutdownNow()
        }
    }

    private fun readFrame(socket: LocalSocket): GateCCredentialLease {
        val input = DataInputStream(socket.inputStream)
        var magic: ByteArray? = null
        var runIdBytes: ByteArray? = null
        var usernameBytes: ByteArray? = null
        var passwordBytes: ByteArray? = null
        try {
            magic = readBytes(input, GATE_C_MAGIC.size, GateCFailureCode.TRUNCATED)
            if (!magic.contentEquals(GATE_C_MAGIC)) {
                throw GateCProtocolException(GateCFailureCode.MAGIC)
            }
            if (readInt(input) != GATE_C_PROTOCOL_VERSION) {
                throw GateCProtocolException(GateCFailureCode.VERSION)
            }

            val runIdLength = checkedLength(readInt(input), 1, MAX_RUN_ID_BYTES)
            runIdBytes = readBytes(input, runIdLength, GateCFailureCode.TRUNCATED)
            val expectedRunIdBytes = expectedRunId.encodeToByteArray()
            try {
                if (!runIdBytes.contentEquals(expectedRunIdBytes)) {
                    throw GateCProtocolException(GateCFailureCode.RUN_ID)
                }
            } finally {
                expectedRunIdBytes.fill(0)
            }

            val expiresAtMillis = readLong(input)
            val now = nowMillis()
            if (expiresAtMillis < now || expiresAtMillis > now + MAX_FUTURE_EXPIRATION_MILLIS) {
                throw GateCProtocolException(GateCFailureCode.EXPIRATION)
            }

            val usernameLength = checkedLength(readInt(input), 1, MAX_USERNAME_BYTES)
            val passwordLength = checkedLength(readInt(input), 1, MAX_PASSWORD_BYTES)
            usernameBytes = readBytes(input, usernameLength, GateCFailureCode.TRUNCATED)
            passwordBytes = readBytes(input, passwordLength, GateCFailureCode.TRUNCATED)

            return GateCCredentialLease(usernameBytes, passwordBytes).also {
                usernameBytes = null
                passwordBytes = null
            }
        } finally {
            magic?.fill(0)
            runIdBytes?.fill(0)
            usernameBytes?.fill(0)
            passwordBytes?.fill(0)
        }
    }

    private fun checkedLength(value: Int, minimum: Int, maximum: Int): Int {
        if (value !in minimum..maximum) {
            throw GateCProtocolException(GateCFailureCode.LENGTH)
        }
        return value
    }

    private fun readInt(input: DataInputStream): Int = try {
        input.readInt()
    } catch (_: IOException) {
        throw GateCProtocolException(GateCFailureCode.TRUNCATED)
    }

    private fun readLong(input: DataInputStream): Long = try {
        input.readLong()
    } catch (_: IOException) {
        throw GateCProtocolException(GateCFailureCode.TRUNCATED)
    }

    private fun readBytes(input: DataInputStream, length: Int, code: GateCFailureCode): ByteArray {
        val bytes = ByteArray(length)
        try {
            input.readFully(bytes)
            return bytes
        } catch (_: IOException) {
            bytes.fill(0)
            throw GateCProtocolException(code)
        }
    }

    companion object {
        const val DEFAULT_ADB_SHELL_UID = 2000
        const val MAX_RUN_ID_BYTES = 64
        const val MAX_USERNAME_BYTES = 320
        const val MAX_PASSWORD_BYTES = 1024
        const val DEFAULT_ACCEPT_TIMEOUT_MILLIS = 15_000L
        const val DEFAULT_READ_TIMEOUT_MILLIS = 5_000
        const val MAX_FUTURE_EXPIRATION_MILLIS = 30_000L
    }
}

internal val GATE_C_MAGIC = byteArrayOf(0x43, 0x54, 0x4b, 0x47, 0x43, 0x30, 0x31, 0x00)
internal val GATE_C_PASS_ACK = byteArrayOf(0x43, 0x54, 0x4b, 0x5a, 0x50, 0x41, 0x53, 0x53)
internal const val GATE_C_PROTOCOL_VERSION = 1
