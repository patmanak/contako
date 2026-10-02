package com.patmanak.contako.data.proton

import com.proton.gopenpgp.crypto.Crypto
import com.proton.gopenpgp.crypto.Key
import com.proton.gopenpgp.crypto.KeyRing
import com.proton.gopenpgp.crypto.PlainMessage
import com.proton.gopenpgp.helper.Go2AndroidReader
import com.proton.gopenpgp.helper.Mobile2GoReader
import com.proton.gopenpgp.helper.MobileReadResult
import com.proton.gopenpgp.helper.MobileReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import me.proton.core.crypto.common.context.CryptoContext
import me.proton.core.crypto.common.pgp.PGPCrypto
import me.proton.core.key.domain.decryptText
import me.proton.core.key.domain.entity.keyholder.KeyHolderContext

/** A closed rejection; no plaintext, keys or native error are retained. */
internal class ProtonPlaintextBoundsExceeded : IllegalArgumentException()

/** Uses Proton's streaming OpenPGP reader; a size limit MUST NOT masquerade as authenticated EOF. */
internal suspend fun decryptBoundedContactText(
    holder: KeyHolderContext,
    message: String,
    maxPlainBytes: Int,
): String {
    require(maxPlainBytes >= 0)
    val operation = coroutineContext
    operation.ensureActive()
    var terminalFailure: Throwable? = null
    val boundedPgp = object : PGPCrypto by holder.context.pgpCrypto {
        override fun decryptText(message: String, unlockedKey: ByteArray): String {
            terminalFailure?.let { throw it }
            return try {
                decryptStream(message, unlockedKey, maxPlainBytes) { operation.ensureActive() }
            } catch (cancelled: CancellationException) {
                terminalFailure = cancelled
                throw cancelled
            } catch (limit: ProtonPlaintextBoundsExceeded) {
                terminalFailure = limit
                throw limit
            }
        }
    }
    val boundedContext = object : CryptoContext by holder.context {
        override val pgpCrypto: PGPCrypto = boundedPgp
    }
    // Core's key fallback catches every failure in decryptTextOrNull. Re-emit terminal
    // limits/cancellation afterwards, and never try an unbounded alternate decryptor.
    return try {
        holder.privateKeyRing.unlockedKeys.decryptText(boundedContext, message).also {
            terminalFailure?.let { failure -> throw failure }
            operation.ensureActive()
        }
    } catch (error: Throwable) {
        terminalFailure?.let { throw it }
        operation.ensureActive()
        throw error
    }
}

private fun decryptStream(
    message: String,
    unlockedKey: ByteArray,
    maxPlainBytes: Int,
    checkCancellation: () -> Unit,
): String {
    var key: Key? = null
    var ring: KeyRing? = null
    var cipher: ByteArray? = null
    val buffer = ByteArray(4_096)
    val output = WipingOutput()
    var complete: ByteArray? = null
    try {
        checkCancellation()
        key = Crypto.newKey(unlockedKey)
        ring = Crypto.newKeyRing(key)
        cipher = Crypto.newPGPMessageFromArmored(message).binary
        val input = ByteArrayInputStream(cipher)
        val mobile = MobileReader { requested ->
            checkCancellation()
            require(requested >= 0)
            val bytes = ByteArray(minOf(requested, 4_096L).toInt())
            val read = input.read(bytes)
            MobileReadResult(maxOf(read, 0).toLong(), read < 0,
                if (read > 0) bytes.copyOf(read) else byteArrayOf())
        }
        val reader = Go2AndroidReader(ring.decryptStream(Mobile2GoReader(mobile), null, 0L))
        while (true) {
            checkCancellation()
            val read = reader.read(buffer)
            checkCancellation()
            // Go2AndroidReader returns -1 only for real io.EOF. Proton checks MDC
            // before that EOF; native read errors MUST reject the entire card.
            if (read == -1L) break
            check(read in 1..buffer.size.toLong())
            if (read > maxPlainBytes - output.size()) throw ProtonPlaintextBoundsExceeded()
            output.write(buffer, 0, read.toInt())
        }
        complete = output.toByteArray()
        // Preserve Proton's CRLF and invalid UTF-8 normalization after authentic EOF.
        return PlainMessage(complete).string
    } finally {
        buffer.fill(0)
        complete?.fill(0)
        output.wipe()
        cipher?.fill(0)
        try { ring?.clearPrivateParams() } finally { key?.clearPrivateParams() }
    }
}

private class WipingOutput : ByteArrayOutputStream(4_096) {
    fun wipe() { buf.fill(0); reset() }
}
