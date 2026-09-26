package com.patmanak.contako.data.android.provider

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.provider.ContactsContract
import com.patmanak.contako.android.account.ContakoAndroidAccountContract
import java.nio.charset.StandardCharsets

internal enum class AndroidRawContactLifecycleFailure {
    PERMISSION_DENIED,
    PROVIDER_UNAVAILABLE,
    MALFORMED_PROVIDER_DATA,
    BOUND_EXCEEDED,
    AMBIGUOUS_CLAIM,
    SOURCE_IDENTITY_MISMATCH,
}

/** Category-only diagnostics: account, canonical, remote, and provider identifiers stay redacted. */
internal class AndroidRawContactLifecycleException(
    val category: AndroidRawContactLifecycleFailure,
) : IllegalStateException("Android raw-contact lifecycle failure: ${category.name}")

/** Replaceable provider locator. This value MUST NOT be used as canonical or remote identity. */
internal data class AndroidOwnedRawContactHandle(
    val rawContactId: Long,
    val version: Long,
    val hasSourceIdentity: Boolean,
) {
    init {
        require(rawContactId > 0)
        require(version >= 0)
    }

    override fun toString(): String =
        "AndroidOwnedRawContactHandle(REDACTED, version=$version, hasSourceIdentity=$hasSourceIdentity)"
}

internal sealed interface AndroidEnsureRawContactResult {
    val handle: AndroidOwnedRawContactHandle

    data class Existing(override val handle: AndroidOwnedRawContactHandle) : AndroidEnsureRawContactResult
    data class Created(override val handle: AndroidOwnedRawContactHandle) : AndroidEnsureRawContactResult
    data class RecoveredAfterLostAcknowledgement(
        override val handle: AndroidOwnedRawContactHandle,
    ) : AndroidEnsureRawContactResult
}

internal sealed interface AndroidDeleteRawContactResult {
    data object Deleted : AndroidDeleteRawContactResult
    data object RecoveredAfterLostAcknowledgement : AndroidDeleteRawContactResult
    /** Callers MUST require a matching durable delete intent before treating absence as convergence. */
    data object AbsentRequiresDurableIntent : AndroidDeleteRawContactResult
    data object Stale : AndroidDeleteRawContactResult
}

/**
 * Account-scoped lifecycle boundary for one Contako-owned RawContact.
 *
 * [ContactsContract.RawContacts.SYNC1] is only a discovery/recovery claim. Callers MUST validate
 * the returned locator against the durable Android projection ledger before using it to ingest or
 * mutate canonical data. Aggregate contact IDs are deliberately never queried or returned.
 */
internal class AndroidRawContactLifecycleGateway(
    private val contentResolver: ContentResolver,
    private val afterInsertCommitted: () -> Unit = {},
    private val afterDeleteCommitted: () -> Unit = {},
) {
    fun ensureOwnedRawContact(
        accountName: AndroidProviderAccountName,
        canonicalContactId: String,
        sourceIdentity: String?,
    ): AndroidEnsureRawContactResult = synchronized(CREATION_LOCK) {
        ensureOwnedRawContactSerial(accountName, canonicalContactId, sourceIdentity)
    }

    private fun ensureOwnedRawContactSerial(
        accountName: AndroidProviderAccountName,
        canonicalContactId: String,
        sourceIdentity: String?,
    ): AndroidEnsureRawContactResult {
        validateInputs(accountName, canonicalContactId, sourceIdentity)
        findByClaim(accountName, canonicalContactId)?.let { candidate ->
            validateSourceIdentity(candidate.sourceIdentity, sourceIdentity)
            return AndroidEnsureRawContactResult.Existing(candidate.toHandle())
        }

        val values = ContentValues().apply {
            put(ContactsContract.RawContacts.ACCOUNT_NAME, accountName.value)
            put(ContactsContract.RawContacts.ACCOUNT_TYPE, ContakoAndroidAccountContract.ACCOUNT_TYPE)
            put(ContactsContract.RawContacts.SYNC1, canonicalContactId)
            put(ContactsContract.RawContacts.DIRTY, 0)
            if (sourceIdentity == null) {
                putNull(ContactsContract.RawContacts.SOURCE_ID)
            } else {
                put(ContactsContract.RawContacts.SOURCE_ID, sourceIdentity)
            }
        }

        val insertedUri = try {
            contentResolver.insert(syncAdapterUri(ContactsContract.RawContacts.CONTENT_URI, accountName), values)
                .also { afterInsertCommitted() }
        } catch (_: SecurityException) {
            throw failure(AndroidRawContactLifecycleFailure.PERMISSION_DENIED)
        } catch (_: RuntimeException) {
            return recoverAfterLostAcknowledgement(accountName, canonicalContactId, sourceIdentity)
        }

        val candidate = findByClaim(accountName, canonicalContactId)
            ?: throw failure(AndroidRawContactLifecycleFailure.PROVIDER_UNAVAILABLE)
        validateSourceIdentity(candidate.sourceIdentity, sourceIdentity)
        val returnedId = insertedUri?.let(::safeContentId)
            ?: return AndroidEnsureRawContactResult.RecoveredAfterLostAcknowledgement(candidate.toHandle())
        if (returnedId != candidate.rawContactId) {
            throw failure(AndroidRawContactLifecycleFailure.AMBIGUOUS_CLAIM)
        }
        return AndroidEnsureRawContactResult.Created(candidate.toHandle())
    }

    fun deleteOwnedRawContact(
        accountName: AndroidProviderAccountName,
        canonicalContactId: String,
        rawContactId: Long,
        expectedRawContactVersion: Long,
        expectedSourceIdentity: AndroidExpectedSourceIdentity,
    ): AndroidDeleteRawContactResult {
        validateInputs(
            accountName,
            canonicalContactId,
            (expectedSourceIdentity as? AndroidExpectedSourceIdentity.Present)?.value,
        )
        require(rawContactId > 0)
        require(expectedRawContactVersion >= 0)

        val candidate = findByClaim(accountName, canonicalContactId, includeDeleted = true)
            ?: return AndroidDeleteRawContactResult.AbsentRequiresDurableIntent
        if (
            candidate.rawContactId != rawContactId ||
            candidate.version != expectedRawContactVersion ||
            !candidate.matches(expectedSourceIdentity)
        ) {
            return AndroidDeleteRawContactResult.Stale
        }

        val sourceClause = when (expectedSourceIdentity) {
            AndroidExpectedSourceIdentity.Missing -> "${ContactsContract.RawContacts.SOURCE_ID} IS NULL"
            is AndroidExpectedSourceIdentity.Present -> "${ContactsContract.RawContacts.SOURCE_ID} = ?"
        }
        val args = buildList {
            add(rawContactId.toString())
            add(accountName.value)
            add(ContakoAndroidAccountContract.ACCOUNT_TYPE)
            add(expectedRawContactVersion.toString())
            add(canonicalContactId)
            if (expectedSourceIdentity is AndroidExpectedSourceIdentity.Present) {
                add(expectedSourceIdentity.value)
            }
        }.toTypedArray()
        val selection = "${ContactsContract.RawContacts._ID} = ? AND " +
            "${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND " +
            "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND " +
            "${ContactsContract.RawContacts.VERSION} = ? AND " +
            "${ContactsContract.RawContacts.SYNC1} = ? AND $sourceClause"
        var deleteCommitted = false
        val deleted = try {
            contentResolver.delete(
                syncAdapterUri(ContactsContract.RawContacts.CONTENT_URI, accountName),
                selection,
                args,
            ).also { count ->
                if (count == 1) {
                    deleteCommitted = true
                    afterDeleteCommitted()
                }
            }
        } catch (_: SecurityException) {
            throw failure(AndroidRawContactLifecycleFailure.PERMISSION_DENIED)
        } catch (_: RuntimeException) {
            if (deleteCommitted && findByClaim(accountName, canonicalContactId, includeDeleted = true) == null) {
                return AndroidDeleteRawContactResult.RecoveredAfterLostAcknowledgement
            }
            throw failure(AndroidRawContactLifecycleFailure.MALFORMED_PROVIDER_DATA)
        }
        return when (deleted) {
            0 -> if (findByClaim(accountName, canonicalContactId, includeDeleted = true) == null) {
                AndroidDeleteRawContactResult.AbsentRequiresDurableIntent
            } else {
                AndroidDeleteRawContactResult.Stale
            }
            1 -> AndroidDeleteRawContactResult.Deleted
            else -> throw failure(AndroidRawContactLifecycleFailure.AMBIGUOUS_CLAIM)
        }
    }

    /** Read-only deletion discovery, including Android DELETED rows; never creates a contact. */
    fun findOwnedRawContactForDeletion(
        accountName: AndroidProviderAccountName,
        canonicalContactId: String,
        expectedSourceIdentity: AndroidExpectedSourceIdentity,
    ): AndroidOwnedRawContactHandle? {
        validateInputs(
            accountName, canonicalContactId,
            (expectedSourceIdentity as? AndroidExpectedSourceIdentity.Present)?.value,
        )
        val candidate = findByClaim(accountName, canonicalContactId, includeDeleted = true) ?: return null
        if (!candidate.matches(expectedSourceIdentity)) {
            throw failure(AndroidRawContactLifecycleFailure.SOURCE_IDENTITY_MISMATCH)
        }
        return candidate.toHandle()
    }

    private fun recoverAfterLostAcknowledgement(
        accountName: AndroidProviderAccountName,
        canonicalContactId: String,
        sourceIdentity: String?,
    ): AndroidEnsureRawContactResult {
        val recovered = findByClaim(accountName, canonicalContactId)
            ?: throw failure(AndroidRawContactLifecycleFailure.PROVIDER_UNAVAILABLE)
        validateSourceIdentity(recovered.sourceIdentity, sourceIdentity)
        return AndroidEnsureRawContactResult.RecoveredAfterLostAcknowledgement(recovered.toHandle())
    }

    private fun findByClaim(
        accountName: AndroidProviderAccountName,
        canonicalContactId: String,
        includeDeleted: Boolean = false,
    ): RawContactCandidate? {
        val uri = ContactsContract.RawContacts.CONTENT_URI.buildUpon()
            .appendQueryParameter(ContactsContract.LIMIT_PARAM_KEY, MAX_CLAIM_MATCHES.toString())
            .build()
        val candidates = try {
            contentResolver.query(
                uri,
                RAW_CONTACT_PROJECTION,
                "${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND " +
                    "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND " +
                    "${ContactsContract.RawContacts.SYNC1} = ?" +
                    if (includeDeleted) "" else " AND ${ContactsContract.RawContacts.DELETED} = 0",
                arrayOf(accountName.value, ContakoAndroidAccountContract.ACCOUNT_TYPE, canonicalContactId),
                "${ContactsContract.RawContacts._ID} ASC",
            )?.use { cursor ->
                buildList<RawContactCandidate> {
                    while (cursor.moveToNext() && size < MAX_CLAIM_MATCHES) {
                        val rawContactId = cursor.getLong(0)
                        val sourceIdentity = if (cursor.isNull(1)) null else cursor.getString(1)
                        val version = cursor.getLong(2)
                        if (rawContactId <= 0 || version < 0) {
                            throw failure(AndroidRawContactLifecycleFailure.MALFORMED_PROVIDER_DATA)
                        }
                        sourceIdentity?.let {
                            if (it.isBlank()) throw failure(AndroidRawContactLifecycleFailure.MALFORMED_PROVIDER_DATA)
                            it.requireBoundedUtf8(MAX_ID_UTF8_BYTES)
                        }
                        add(RawContactCandidate(rawContactId, sourceIdentity, version))
                    }
                }
            } ?: throw failure(AndroidRawContactLifecycleFailure.PROVIDER_UNAVAILABLE)
        } catch (_: SecurityException) {
            throw failure(AndroidRawContactLifecycleFailure.PERMISSION_DENIED)
        } catch (error: AndroidRawContactLifecycleException) {
            throw error
        } catch (_: RuntimeException) {
            throw failure(AndroidRawContactLifecycleFailure.MALFORMED_PROVIDER_DATA)
        }
        if (candidates.size > 1) throw failure(AndroidRawContactLifecycleFailure.AMBIGUOUS_CLAIM)
        return candidates.singleOrNull()
    }

    private fun validateInputs(
        accountName: AndroidProviderAccountName,
        canonicalContactId: String,
        sourceIdentity: String?,
    ) {
        accountName.value.requireBoundedUtf8(MAX_ACCOUNT_NAME_UTF8_BYTES)
        if (canonicalContactId.isBlank()) throw failure(AndroidRawContactLifecycleFailure.MALFORMED_PROVIDER_DATA)
        canonicalContactId.requireBoundedUtf8(MAX_ID_UTF8_BYTES)
        if (sourceIdentity != null) {
            if (sourceIdentity.isBlank()) throw failure(AndroidRawContactLifecycleFailure.MALFORMED_PROVIDER_DATA)
            sourceIdentity.requireBoundedUtf8(MAX_ID_UTF8_BYTES)
        }
    }

    private fun validateSourceIdentity(actual: String?, expected: String?) {
        if (actual != expected) throw failure(AndroidRawContactLifecycleFailure.SOURCE_IDENTITY_MISMATCH)
    }

    private fun String.requireBoundedUtf8(maximumBytes: Int) {
        if (length > maximumBytes || toByteArray(StandardCharsets.UTF_8).size > maximumBytes) {
            throw failure(AndroidRawContactLifecycleFailure.BOUND_EXCEEDED)
        }
    }

    private fun syncAdapterUri(base: Uri, accountName: AndroidProviderAccountName): Uri = base.buildUpon()
        .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_NAME, accountName.value)
        .appendQueryParameter(
            ContactsContract.RawContacts.ACCOUNT_TYPE,
            ContakoAndroidAccountContract.ACCOUNT_TYPE,
        )
        .build()

    private fun safeContentId(uri: Uri): Long? = try {
        ContentUris.parseId(uri).takeIf { it > 0 }
    } catch (_: RuntimeException) {
        null
    }

    private data class RawContactCandidate(
        val rawContactId: Long,
        val sourceIdentity: String?,
        val version: Long,
    ) {
        fun toHandle() = AndroidOwnedRawContactHandle(rawContactId, version, sourceIdentity != null)

        fun matches(expected: AndroidExpectedSourceIdentity): Boolean = when (expected) {
            AndroidExpectedSourceIdentity.Missing -> sourceIdentity == null
            is AndroidExpectedSourceIdentity.Present -> sourceIdentity == expected.value
        }
    }

    private companion object {
        const val MAX_CLAIM_MATCHES = 2
        const val MAX_ACCOUNT_NAME_UTF8_BYTES = 512
        const val MAX_ID_UTF8_BYTES = 4_096
        val CREATION_LOCK = Any()
        val RAW_CONTACT_PROJECTION = arrayOf(
            ContactsContract.RawContacts._ID,
            ContactsContract.RawContacts.SOURCE_ID,
            ContactsContract.RawContacts.VERSION,
        )

        fun failure(category: AndroidRawContactLifecycleFailure) =
            AndroidRawContactLifecycleException(category)
    }
}
