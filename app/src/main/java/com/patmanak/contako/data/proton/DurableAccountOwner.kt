package com.patmanak.contako.data.proton

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.proton.core.domain.entity.UserId

/** Private scope metadata. This is not a credential and MUST never enter diagnostics. */
internal interface AccountOwnerStorage {
    suspend fun read(): String?
    suspend fun write(record: String)
    suspend fun clear()
}

/** Stored outside Android backup, independently of revocable Core account/session records. */
internal class FileAccountOwnerStorage(private val file: File) : AccountOwnerStorage {
    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        if (!file.exists()) null else {
            require(file.length() in 1..MAX_OWNER_BYTES)
            file.readText(Charsets.UTF_8).also(::decodeOwner)
        }
    }

    override suspend fun write(record: String) = withContext(Dispatchers.IO) {
        decodeOwner(record)
        val bytes = record.toByteArray(Charsets.UTF_8)
        require(bytes.size.toLong() in 1..MAX_OWNER_BYTES)
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val temporary = File(file.parentFile, "${file.name}.pending")
        FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
        // No non-atomic fallback: failure MUST quarantine rather than forget ownership.
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        Unit
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        Files.deleteIfExists(file.toPath())
        Files.deleteIfExists(File(file.parentFile, "${file.name}.pending").toPath())
        Unit
    }

    private companion object { const val MAX_OWNER_BYTES = 4096L }
}

internal class DurableAccountOwner(
    private val storage: AccountOwnerStorage,
    private val hasLocalAccountData: suspend () -> Boolean,
) {
    private val lock = Mutex()

    /** Reserve an empty partition before persisting credentials, without establishing ownership. */
    suspend fun reserveForLogin(userId: UserId, existingSession: Boolean = false) = lock.withLock {
        val previous = storage.read()?.let(::decodeOwner)
        when {
            previous?.userId == userId.id -> Unit
            previous != null -> throw LocalAccountOwnerMismatch()
            else -> {
                if (!existingSession && hasLocalAccountData()) throw LocalAccountOwnerMismatch()
                storage.write(if (existingSession) userId.id else RESERVED_PREFIX + userId.id)
            }
        }
    }

    /** A pre-existing Core session may migrate legacy ownership; a fresh login cannot claim data. */
    suspend fun requireOwner(userId: UserId, existingSession: Boolean = false) = lock.withLock {
        requireOwnerLocked(userId, existingSession)
    }

    /** A legacy incomplete session may reserve an empty partition, but cannot establish its owner. */
    suspend fun requireStoredSessionOwner(userId: UserId, ready: Boolean) = lock.withLock {
        val previous = storage.read()?.let(::decodeOwner)
        when {
            previous != null && previous.userId != userId.id -> throw LocalAccountOwnerMismatch()
            previous == null -> {
                if (!ready && hasLocalAccountData()) throw LocalAccountOwnerMismatch()
                storage.write(if (ready) userId.id else RESERVED_PREFIX + userId.id)
            }
            ready && previous.reserved -> storage.write(userId.id)
            else -> Unit
        }
    }

    suspend fun identity(): String? = lock.withLock { storage.read()?.let(::decodeOwner)?.userId }

    /** Commit ownership before READY can admit any local or remote work. */
    suspend fun confirmActivation(userId: UserId) = lock.withLock {
        requireOwnerLocked(userId, existingSession = true)
        storage.write(userId.id)
    }

    private suspend fun requireOwnerLocked(userId: UserId, existingSession: Boolean) {
        when (storage.read()?.let(::decodeOwner)?.userId) {
            userId.id -> Unit
            null -> {
                if (!existingSession && hasLocalAccountData()) throw LocalAccountOwnerMismatch()
                storage.write(userId.id)
            }
            else -> throw LocalAccountOwnerMismatch()
        }
    }

    /** Caller MUST hold session admission closed and prove no Core account remains. */
    suspend fun releaseAbandonedReservation() = lock.withLock {
        val previous = storage.read()?.let(::decodeOwner) ?: return@withLock
        // An established owner is never released by revocation or by an aborted reauthentication.
        if (previous.reserved && !hasLocalAccountData()) storage.clear()
    }

    /** Only the serialized, completed explicit sign-out cleanup may release this partition. */
    suspend fun releaseAfterLocalDataCleared() = lock.withLock {
        check(!hasLocalAccountData())
        storage.clear()
    }

    /** Cleanup can resume after Core session removal, but never under a different active user. */
    suspend fun canClean(userId: UserId?): Boolean = lock.withLock {
        val bound = storage.read()?.let(::decodeOwner) ?: return@withLock false
        userId == null || bound.userId == userId.id
    }
}

// Legacy owner records prohibited newlines. The separator therefore cannot collide with a legacy
// identity, and one atomic file replacement persists both the identity and its activation state.
private const val RESERVED_PREFIX = "reserved-v1\n"
private class OwnerRecord(val userId: String, val reserved: Boolean)
private fun decodeOwner(record: String): OwnerRecord {
    val reserved = record.startsWith(RESERVED_PREFIX)
    val userId = if (reserved) record.removePrefix(RESERVED_PREFIX) else record
    require(userId.isNotBlank() && '\n' !in userId && '\r' !in userId)
    return OwnerRecord(userId, reserved)
}

internal class LocalAccountOwnerMismatch : IllegalStateException()
