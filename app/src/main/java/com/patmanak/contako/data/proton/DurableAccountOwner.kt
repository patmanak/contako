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
    suspend fun write(userId: String)
    suspend fun clear()
}

/** Stored outside Android backup, independently of revocable Core account/session records. */
internal class FileAccountOwnerStorage(private val file: File) : AccountOwnerStorage {
    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        if (!file.exists()) null else {
            require(file.length() in 1..MAX_OWNER_BYTES)
            file.readText(Charsets.UTF_8).also { require(it.isNotBlank() && '\n' !in it && '\r' !in it) }
        }
    }

    override suspend fun write(userId: String) = withContext(Dispatchers.IO) {
        val bytes = userId.toByteArray(Charsets.UTF_8)
        require(bytes.size.toLong() in 1..MAX_OWNER_BYTES && userId.isNotBlank() && '\n' !in userId && '\r' !in userId)
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

    /** A pre-existing Core session may migrate legacy ownership; a fresh login cannot claim data. */
    suspend fun requireOwner(userId: UserId, existingSession: Boolean = false) = lock.withLock {
        when (storage.read()) {
            userId.id -> Unit
            null -> {
                if (!existingSession && hasLocalAccountData()) throw LocalAccountOwnerMismatch()
                storage.write(userId.id)
            }
            else -> throw LocalAccountOwnerMismatch()
        }
    }

    /** Only the serialized, completed explicit sign-out cleanup may release this partition. */
    suspend fun releaseAfterLocalDataCleared() = lock.withLock {
        check(!hasLocalAccountData())
        storage.clear()
    }

    /** Cleanup can resume after Core session removal, but never under a different active user. */
    suspend fun canClean(userId: UserId?): Boolean = lock.withLock {
        val bound = storage.read() ?: return@withLock false
        userId == null || bound == userId.id
    }
}

internal class LocalAccountOwnerMismatch : IllegalStateException()
