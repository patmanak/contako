package com.patmanak.contako.data.android.provider

import android.content.ContentResolver
import android.content.ContentProviderOperation
import android.content.OperationApplicationException
import android.content.ContentUris
import android.provider.ContactsContract
import androidx.room.withTransaction
import com.patmanak.contako.android.account.ContakoAndroidAccountContract
import com.patmanak.contako.data.android.mapping.AndroidContactRow
import com.patmanak.contako.data.android.mapping.AndroidRowKind
import com.patmanak.contako.data.local.AndroidPhotoProviderWriteJournalEntity
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.data.sync.AndroidInteroperabilityContext
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal sealed interface AndroidPhotoProviderWriteResult {
    data class Committed(val rawContactVersion: Long) : AndroidPhotoProviderWriteResult
    data class Stale(val category: AndroidPhotoProviderStaleCategory) : AndroidPhotoProviderWriteResult
    data class RepairRequired(val category: AndroidPhotoProviderRepairCategory) : AndroidPhotoProviderWriteResult
}

/** Closed photo-write failures; no payload, digest, locator or identity is exposed. */
internal enum class AndroidPhotoProviderRepairCategory {
    PAYLOAD_UNAVAILABLE, PAYLOAD_EMPTY, PAYLOAD_BOUND_EXCEEDED,
    POST_STREAM_IDENTITY, POST_BIND_IDENTITY, POST_BIND_DIRTY, POST_BIND_VALUE_IDENTITY,
}

internal enum class AndroidPhotoProviderStaleCategory {
    JOURNAL_PREPARATION,
    PRE_WRITE_OBSERVATION,
    PRE_WRITE_VERSION,
    PRE_WRITE_PHOTO_UPDATE,
    PRE_WRITE_INLINE_BLOB,
    PRE_WRITE_PHOTO_PAYLOAD,
    POST_STREAM_OBSERVATION,
    POST_STREAM_PHOTO_ROW,
    POST_STREAM_DISPLAY_PHOTO,
    POST_BIND_OBSERVATION,
    JOURNAL_COMMIT,
}

/** Journaled, bounded full-resolution RawContacts/display_photo writer. */
internal class RoomAndroidPhotoProviderWriteCoordinator(
    private val database: ContakoDatabase,
    private val contentResolver: ContentResolver,
    private val reader: AndroidContactsProviderReader,
    private val binaryLoader: AndroidProjectionBinaryLoader,
) {
    private val photoVerifier = AndroidPhotoReadbackVerifier(contentResolver)
    /** A committed photo write may recreate a previously used value on a new Data row. */
    suspend fun reconcileCommittedIdentity(
        context: AndroidInteroperabilityContext,
        canonicalContactId: String,
        canonicalRevision: Long,
        observation: AndroidStableRawContactObservation,
        desiredPhoto: AndroidContactRow?,
    ): Boolean = database.withTransaction {
        val photo = observation.photoRow() ?: return@withTransaction true
        val valueId = photo.canonicalValueId ?: return@withTransaction true
        val dao = database.androidProviderIdentityDao()
        val binding = dao.getBindingByCanonicalValueId(context.account.value, canonicalContactId, context.providerEpoch, valueId)
            ?: return@withTransaction true
        if (binding.state != "ATTACHED" || binding.dataRowLocator == photo.dataRowId) return@withTransaction true
        val journal = database.androidGroupProjectionDao().getPhotoProviderWriteJournal(context.account.value, canonicalContactId)
            ?: return@withTransaction false
        val account = database.androidProjectionLedgerDao().getAccount(context.account.value) ?: return@withTransaction false
        val ledger = database.androidProjectionLedgerDao().get(context.account.value, canonicalContactId) ?: return@withTransaction false
        val canonical = database.contactDao().get(context.account.value, canonicalContactId)?.contact ?: return@withTransaction false
        val raw = observation.rawContact
        val oldLocator = binding.dataRowLocator ?: return@withTransaction false
        if (account.revision != context.accountRevision || account.providerEpoch != context.providerEpoch ||
            account.androidAccountName != context.androidAccountName || ledger.providerEpoch != context.providerEpoch ||
            ledger.rawContactLocator != raw.rawContactId || ledger.tombstoneState != "NONE" ||
            canonical.revision != canonicalRevision || canonical.isDeleted || canonical.conflictState != null ||
            canonical.remoteContactId != ledger.sourceIdentity || raw.dirty || raw.deleted ||
            raw.canonicalContactIdClaim != canonicalContactId || raw.sourceIdentity != ledger.sourceIdentity ||
            journal.state != STATE_COMMITTED || journal.providerEpoch != context.providerEpoch ||
            journal.androidAccountName != context.androidAccountName || journal.rawContactLocator != raw.rawContactId ||
            journal.expectedSourceIdentity != raw.sourceIdentity || journal.resultRawContactVersion != raw.version ||
            journal.canonicalValueId != valueId || desiredPhoto?.identity?.canonicalValueId != valueId ||
            journal.binaryReference != desiredPhoto.binaryReference || binding.kind != AndroidRowKind.PHOTO.name ||
            binding.role != "PRIMARY" || binding.bindingPrimaryId != valueId ||
            binding.rawContactLocator != raw.rawContactId || binding.androidAccountName != context.androidAccountName ||
            observation.dataRows.any { it.dataRowId == oldLocator } ||
            dao.getAttachedBindingGroup(context.account.value, canonicalContactId, context.providerEpoch, raw.rawContactId, photo.dataRowId).isNotEmpty()
        ) return@withTransaction false
        // The old locator is absent from the complete scoped observation; the new row is
        // vouched for by the exact committed stream receipt, never by DATA_SYNC alone.
        dao.relocateCommittedPhotoBinding(
            context.account.value, canonicalContactId, context.providerEpoch, raw.rawContactId,
            valueId, oldLocator, photo.dataRowId,
        ) == 1
    }

    suspend fun write(
        context: AndroidInteroperabilityContext,
        canonicalContactId: String,
        canonicalRevision: Long,
        ledgerRevision: Long,
        accountName: AndroidProviderAccountName,
        rawContactId: Long,
        expectedRawContactVersion: Long,
        expectedSourceIdentity: String,
        photo: AndroidContactRow,
    ): AndroidPhotoProviderWriteResult {
        require(photo.kind == AndroidRowKind.PHOTO)
        val reference = requireNotNull(photo.binaryReference)
        val bytes = try {
            binaryLoader.load(reference)
        } catch (_: RuntimeException) {
            null
        } ?: return AndroidPhotoProviderWriteResult.RepairRequired(AndroidPhotoProviderRepairCategory.PAYLOAD_UNAVAILABLE)
        if (bytes.isEmpty()) return AndroidPhotoProviderWriteResult.RepairRequired(AndroidPhotoProviderRepairCategory.PAYLOAD_EMPTY)
        if (bytes.size > MAX_PHOTO_BYTES) {
            return AndroidPhotoProviderWriteResult.RepairRequired(AndroidPhotoProviderRepairCategory.PAYLOAD_BOUND_EXCEEDED)
        }
        val digest = bytes.sha256()
        val candidate = AndroidPhotoProviderWriteJournalEntity(
            context.account.value, canonicalContactId, accountName.value, context.providerEpoch,
            rawContactId, expectedRawContactVersion, expectedSourceIdentity, canonicalRevision,
            ledgerRevision, photo.identity.canonicalValueId, reference, bytes.size.toLong(), digest,
            STATE_PREPARED, null,
        )
        val prepared = database.withTransaction {
            val current = database.androidGroupProjectionDao()
                .getPhotoProviderWriteJournal(context.account.value, canonicalContactId)
            when {
                current?.sameCommand(candidate) == true && hasExactDurableClaims(candidate) -> current
                // A prepared journal for a *different* command describes an older desired photo:
                // the canonical value changed between that prepare and now. Refusing it left the
                // entry prepared forever, so the photo never reached the provider. The provider
                // write is idempotent, so replacing the stale command is safe.
                current != null && current.state == STATE_PREPARED && hasExactDurableClaims(candidate) ->
                    candidate.also {
                        database.androidGroupProjectionDao().upsertPhotoProviderWriteJournal(it)
                    }
                current != null && current.state == STATE_PREPARED -> null
                !hasExactDurableClaims(candidate) -> null
                else -> candidate.also {
                    database.androidGroupProjectionDao().upsertPhotoProviderWriteJournal(it)
                }
            }
        } ?: return AndroidPhotoProviderWriteResult.Stale(AndroidPhotoProviderStaleCategory.JOURNAL_PREPARATION)
        if (prepared.state == STATE_COMMITTED) {
            return AndroidPhotoProviderWriteResult.Committed(requireNotNull(prepared.resultRawContactVersion))
        }

        var before = readExact(accountName, rawContactId)
            ?: return AndroidPhotoProviderWriteResult.Stale(AndroidPhotoProviderStaleCategory.PRE_WRITE_OBSERVATION)
        if (!before.matches(candidate) || before.rawContact.dirty || before.rawContact.version != expectedRawContactVersion) {
            return AndroidPhotoProviderWriteResult.Stale(AndroidPhotoProviderStaleCategory.PRE_WRITE_VERSION)
        }
        val protectedRows = before.dataRows.filterNot { it.isStandardPhoto }
        var beforePhotoBytes = before.photoRow()?.binarySlot
        if (beforePhotoBytes == null || beforePhotoBytes.isEmpty()) {
            if (seedInlinePhoto(accountName, rawContactId, before, bytes) != 1) {
                return AndroidPhotoProviderWriteResult.Stale(
                    AndroidPhotoProviderStaleCategory.PRE_WRITE_PHOTO_UPDATE,
                )
            }
            if (!hasNonEmptyInlinePhoto(before.photoRow()!!.dataRowId, rawContactId)) {
                return AndroidPhotoProviderWriteResult.Stale(
                    AndroidPhotoProviderStaleCategory.PRE_WRITE_INLINE_BLOB,
                )
            }
            before = readExact(accountName, rawContactId)
                ?: return AndroidPhotoProviderWriteResult.Stale(
                    AndroidPhotoProviderStaleCategory.PRE_WRITE_PHOTO_PAYLOAD,
                )
            beforePhotoBytes = before.photoRow()?.binarySlot
            if (!before.matchesIdentity(candidate) || before.rawContact.dirty ||
                before.dataRows.filterNot { it.isStandardPhoto } != protectedRows ||
                beforePhotoBytes == null || beforePhotoBytes.isEmpty()) {
                return AndroidPhotoProviderWriteResult.Stale(
                    AndroidPhotoProviderStaleCategory.PRE_WRITE_PHOTO_PAYLOAD,
                )
            }
        }
        // A previous acknowledged/lost-return attempt may already have written these exact
        // Android bytes. Do not reopen the asynchronous pipe just to produce the same image.
        if (!photoVerifier.matches(accountName, before, bytes, requireDisplay = true)) stream(accountName, rawContactId, bytes)
        val streamed = when (val settled = readExactAfterProviderWrite(accountName, rawContactId, bytes)) {
            is AndroidPhotoSettleResult.Stable -> settled.observation
            is AndroidPhotoSettleResult.Stale -> return AndroidPhotoProviderWriteResult.Stale(settled.category)
        }
        if (!streamed.matchesIdentity(candidate)) {
            return AndroidPhotoProviderWriteResult.RepairRequired(AndroidPhotoProviderRepairCategory.POST_STREAM_IDENTITY)
        }
        // Photo processing is the only difference this writer owns. A concurrent native
        // field edit must be ingested, never acknowledged by a photo-only write.
        if (protectedRows != streamed.dataRows.filterNot { it.isStandardPhoto }) {
            return AndroidPhotoProviderWriteResult.Stale(AndroidPhotoProviderStaleCategory.POST_BIND_OBSERVATION)
        }
        if (!bindAndAcknowledgePhoto(accountName, candidate, streamed, photo)) {
            return AndroidPhotoProviderWriteResult.Stale(AndroidPhotoProviderStaleCategory.POST_BIND_OBSERVATION)
        }
        val after = when (val settled = readExactAfterProviderWrite(accountName, rawContactId, bytes)) {
            is AndroidPhotoSettleResult.Stable -> settled.observation
            is AndroidPhotoSettleResult.Stale -> return AndroidPhotoProviderWriteResult.Stale(
                AndroidPhotoProviderStaleCategory.POST_BIND_OBSERVATION,
            )
        }
        val postBindFailure = when {
            !after.matchesIdentity(candidate) -> AndroidPhotoProviderRepairCategory.POST_BIND_IDENTITY
            after.rawContact.dirty -> AndroidPhotoProviderRepairCategory.POST_BIND_DIRTY
            after.photoRow()?.canonicalValueId != candidate.canonicalValueId ->
                AndroidPhotoProviderRepairCategory.POST_BIND_VALUE_IDENTITY
            else -> null
        }
        if (postBindFailure != null) return AndroidPhotoProviderWriteResult.RepairRequired(postBindFailure)
        return database.withTransaction {
            val current = database.androidGroupProjectionDao()
                .getPhotoProviderWriteJournal(context.account.value, canonicalContactId)
                ?: return@withTransaction AndroidPhotoProviderWriteResult.Stale(
                    AndroidPhotoProviderStaleCategory.JOURNAL_COMMIT,
                )
            if (!current.sameCommand(candidate) || current.state != STATE_PREPARED || !hasExactDurableClaims(candidate)) {
                return@withTransaction AndroidPhotoProviderWriteResult.Stale(
                    AndroidPhotoProviderStaleCategory.JOURNAL_COMMIT,
                )
            }
            database.androidGroupProjectionDao().upsertPhotoProviderWriteJournal(
                current.copy(state = STATE_COMMITTED, resultRawContactVersion = after.rawContact.version),
            )
            AndroidPhotoProviderWriteResult.Committed(after.rawContact.version)
        }
    }

    suspend fun cleanupCommitted(accountId: String, canonicalContactId: String, contentSha256: String) {
        database.androidGroupProjectionDao()
            .deleteCommittedPhotoProviderWriteJournal(accountId, canonicalContactId, contentSha256)
    }

    private suspend fun stream(
        accountName: AndroidProviderAccountName,
        rawContactId: Long,
        bytes: ByteArray,
    ) {
        withContext(Dispatchers.IO) {
            val uri = displayPhotoUri(accountName, rawContactId)
            try {
                contentResolver.openAssetFileDescriptor(uri, "rw")?.use { descriptor ->
                    descriptor.createOutputStream().use { output ->
                        var offset = 0
                        while (offset < bytes.size) {
                            coroutineContext.ensureActive()
                            val count = minOf(STREAM_BUFFER_BYTES, bytes.size - offset)
                            output.write(bytes, offset, count)
                            offset += count
                        }
                        output.flush()
                    }
                } ?: throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.PROVIDER_UNAVAILABLE)
            } catch (_: FileNotFoundException) {
                throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.PROVIDER_UNAVAILABLE)
            } catch (_: SecurityException) {
                throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.PERMISSION_DENIED)
            } catch (_: IOException) {
                throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.PROVIDER_UNAVAILABLE)
            }
        }
    }

    private fun bindAndAcknowledgePhoto(
        accountName: AndroidProviderAccountName,
        command: AndroidPhotoProviderWriteJournalEntity,
        observation: AndroidStableRawContactObservation,
        photo: AndroidContactRow,
    ): Boolean {
        val row = observation.photoRow() ?: throw AndroidProviderBoundaryException(
            AndroidProviderFailureCategory.MALFORMED_PROVIDER_DATA,
        )
        val values = android.content.ContentValues().apply {
            put(DATA_SYNC1, command.canonicalValueId)
            put(DATA_SYNC2, photo.order.toString())
            put(ContactsContract.Data.IS_PRIMARY, if (photo.isPrimary) 1 else 0)
            put(ContactsContract.Data.IS_SUPER_PRIMARY, if (photo.isSuperPrimary) 1 else 0)
        }
        val rawUri = syncAdapterUri(ContactsContract.RawContacts.CONTENT_URI, accountName)
        val selection = "${ContactsContract.RawContacts._ID} = ? AND " +
            "${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND ${ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND " +
            "${ContactsContract.RawContacts.SOURCE_ID} = ? AND ${ContactsContract.RawContacts.VERSION} = ? AND " +
            "${ContactsContract.RawContacts.SYNC1} = ? AND ${ContactsContract.RawContacts.DELETED} = 0"
        val args = arrayOf(command.rawContactLocator.toString(), accountName.value,
            ContakoAndroidAccountContract.ACCOUNT_TYPE, command.expectedSourceIdentity, observation.rawContact.version.toString(),
            command.canonicalContactId)
        return try {
            val operations = arrayListOf(
                ContentProviderOperation.newAssertQuery(rawUri).withSelection(selection, args).withExpectedCount(1).build(),
                ContentProviderOperation.newUpdate(syncAdapterUri(ContactsContract.Data.CONTENT_URI, accountName))
                    .withSelection("${ContactsContract.Data._ID} = ? AND ${ContactsContract.Data.RAW_CONTACT_ID} = ?",
                        arrayOf(row.dataRowId.toString(), command.rawContactLocator.toString()))
                    .withValues(values).withExpectedCount(1).build(),
                ContentProviderOperation.newUpdate(rawUri)
                    .withSelection("${ContactsContract.RawContacts._ID} = ?", arrayOf(command.rawContactLocator.toString()))
                    .withValue(ContactsContract.RawContacts.DIRTY, 0).build(),
            )
            contentResolver.applyBatch(ContakoAndroidAccountContract.CONTACTS_AUTHORITY, operations)
            true
        } catch (_: OperationApplicationException) {
            false
        } catch (_: android.os.RemoteException) {
            throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.PROVIDER_UNAVAILABLE)
        } catch (_: SecurityException) {
            throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.PERMISSION_DENIED)
        } catch (_: RuntimeException) {
            throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.MALFORMED_PROVIDER_DATA)
        }
    }

    /**
     * Repairs a provider placeholder left without bytes before the separately journaled stream.
     * The command is already PREPARED and the stable observation has proved exact ownership. The
     * bounded inline update is idempotent and is verified by a fresh stable read before streaming.
     */
    private fun seedInlinePhoto(
        accountName: AndroidProviderAccountName,
        rawContactId: Long,
        observation: AndroidStableRawContactObservation,
        displayBytes: ByteArray,
    ): Int {
        val row = observation.photoRow() ?: throw AndroidProviderBoundaryException(
            AndroidProviderFailureCategory.MALFORMED_PROVIDER_DATA,
        )
        val inlineBytes = AndroidProjectionPhotoScaler.scaleForProvider(displayBytes)
            ?: throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.MALFORMED_PROVIDER_DATA)
        val updated = try {
            contentResolver.applyBatch(ContakoAndroidAccountContract.CONTACTS_AUTHORITY, arrayListOf(
                ContentProviderOperation.newAssertQuery(syncAdapterUri(ContactsContract.RawContacts.CONTENT_URI, accountName))
                    .withSelection("${ContactsContract.RawContacts._ID} = ? AND ${ContactsContract.RawContacts.VERSION} = ? AND " +
                        "${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND ${ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND " +
                        "${ContactsContract.RawContacts.SOURCE_ID} = ? AND ${ContactsContract.RawContacts.SYNC1} = ? AND " +
                        "${ContactsContract.RawContacts.DIRTY} = 0 AND ${ContactsContract.RawContacts.DELETED} = 0",
                        arrayOf(rawContactId.toString(), observation.rawContact.version.toString(), accountName.value,
                            ContakoAndroidAccountContract.ACCOUNT_TYPE, requireNotNull(observation.rawContact.sourceIdentity),
                            requireNotNull(observation.rawContact.canonicalContactIdClaim)))
                    .withExpectedCount(1).build(),
                ContentProviderOperation.newUpdate(syncAdapterUri(ContactsContract.Data.CONTENT_URI, accountName))
                    .withSelection("${ContactsContract.Data._ID} = ? AND ${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                        arrayOf(row.dataRowId.toString(), rawContactId.toString(), ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE))
                    .withValue(ContactsContract.CommonDataKinds.Photo.PHOTO, inlineBytes).withExpectedCount(1).build(),
            ))
            1
        } catch (_: OperationApplicationException) {
            0
        } catch (_: android.os.RemoteException) {
            throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.PROVIDER_UNAVAILABLE)
        } catch (_: SecurityException) {
            throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.PERMISSION_DENIED)
        } catch (_: RuntimeException) {
            throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.MALFORMED_PROVIDER_DATA)
        }
        if (updated > 1) throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.ACCOUNT_SCOPE_MISMATCH)
        return updated
    }

    private fun hasNonEmptyInlinePhoto(dataRowId: Long, rawContactId: Long): Boolean = try {
        contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Photo.PHOTO),
            "${ContactsContract.Data._ID} = ? AND ${ContactsContract.Data.RAW_CONTACT_ID} = ? AND " +
                "${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(
                dataRowId.toString(),
                rawContactId.toString(),
                ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE,
            ),
            null,
        )?.use { cursor ->
            cursor.moveToFirst() && !cursor.isNull(0) && cursor.getBlob(0).isNotEmpty()
        } ?: false
    } catch (_: SecurityException) {
        throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.PERMISSION_DENIED)
    } catch (_: RuntimeException) {
        false
    }


    private suspend fun hasExactDurableClaims(command: AndroidPhotoProviderWriteJournalEntity): Boolean {
        val account = database.androidProjectionLedgerDao().getAccount(command.accountId) ?: return false
        val ledger = database.androidProjectionLedgerDao().get(command.accountId, command.canonicalContactId) ?: return false
        val canonical = database.contactDao().get(command.accountId, command.canonicalContactId) ?: return false
        return account.providerEpoch == command.providerEpoch && account.androidAccountName == command.androidAccountName &&
            canonical.contact.revision == command.expectedCanonicalRevision && ledger.revision == command.expectedLedgerRevision &&
            ledger.providerEpoch == command.providerEpoch && ledger.rawContactLocator == command.rawContactLocator &&
            ledger.sourceIdentity == command.expectedSourceIdentity
    }

    private fun readExact(accountName: AndroidProviderAccountName, rawContactId: Long) =
        when (val result = reader.readStableRawContact(accountName, rawContactId)) {
            AndroidStableRawContactPageResult.ReplanRequired -> null
            is AndroidStableRawContactPageResult.Stable -> result.page.observations.singleOrNull()
        }

    /**
     * Display-photo writes are finalized asynchronously by some ContactsProvider builds. Their
     * raw-contact version can therefore change between the stable reader's two snapshots even
     * though this writer is the only actor. Retry only that transient observation window; the
     * caller still validates every durable identity and the final photo binding afterwards.
     */
    private suspend fun readExactAfterProviderWrite(
        accountName: AndroidProviderAccountName,
        rawContactId: Long,
        expectedBytes: ByteArray,
    ): AndroidPhotoSettleResult {
        var lastFailure = AndroidPhotoProviderStaleCategory.POST_STREAM_OBSERVATION
        repeat(PROVIDER_SETTLE_ATTEMPTS) { attempt ->
            val observation = readExact(accountName, rawContactId)
            val photo = observation?.photoRow()
            lastFailure = when {
                observation == null -> AndroidPhotoProviderStaleCategory.POST_STREAM_OBSERVATION
                photo == null -> AndroidPhotoProviderStaleCategory.POST_STREAM_PHOTO_ROW
                !photoVerifier.matches(accountName, observation, expectedBytes,
                    requireDisplay = attempt + 1 < PROVIDER_SETTLE_ATTEMPTS) ->
                    AndroidPhotoProviderStaleCategory.POST_STREAM_DISPLAY_PHOTO
                else -> return AndroidPhotoSettleResult.Stable(observation)
            }
            if (attempt + 1 < PROVIDER_SETTLE_ATTEMPTS) delay(PROVIDER_SETTLE_DELAY_MILLIS)
        }
        return AndroidPhotoSettleResult.Stale(lastFailure)
    }


    private fun com.patmanak.contako.data.android.provider.AndroidStableRawContactObservation.matches(
        command: AndroidPhotoProviderWriteJournalEntity,
    ): Boolean = matchesIdentity(command) && rawContact.version >= command.expectedRawContactVersion

    private fun com.patmanak.contako.data.android.provider.AndroidStableRawContactObservation.matchesIdentity(
        command: AndroidPhotoProviderWriteJournalEntity,
    ): Boolean = !rawContact.deleted && rawContact.rawContactId == command.rawContactLocator &&
        rawContact.canonicalContactIdClaim == command.canonicalContactId &&
        rawContact.sourceIdentity == command.expectedSourceIdentity

    private fun AndroidStableRawContactObservation.photoRow(): AndroidOwnedDataRow? =
        dataRows.singleOrNull { it.mimeType == ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE }

    private fun displayPhotoUri(
        accountName: AndroidProviderAccountName,
        rawContactId: Long,
    ): android.net.Uri =
        ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, rawContactId)
            .buildUpon()
            .appendPath(ContactsContract.RawContacts.DisplayPhoto.CONTENT_DIRECTORY)
            .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_NAME, accountName.value)
            .appendQueryParameter(
                ContactsContract.RawContacts.ACCOUNT_TYPE,
                ContakoAndroidAccountContract.ACCOUNT_TYPE,
            )
            .build()

    private fun syncAdapterUri(base: android.net.Uri, accountName: AndroidProviderAccountName): android.net.Uri =
        base.buildUpon()
            .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_NAME, accountName.value)
            .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_TYPE, ContakoAndroidAccountContract.ACCOUNT_TYPE)
            .build()

    private fun AndroidPhotoProviderWriteJournalEntity.sameCommand(other: AndroidPhotoProviderWriteJournalEntity) =
        copy(
            expectedRawContactVersion = 0,
            state = STATE_PREPARED,
            resultRawContactVersion = null,
        ) == other.copy(
            expectedRawContactVersion = 0,
            state = STATE_PREPARED,
            resultRawContactVersion = null,
        )

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_PHOTO_BYTES = 10 * 1_024 * 1_024
        const val STREAM_BUFFER_BYTES = 32 * 1_024
        // The reference ContactsProvider can publish the aggregate display-photo stream more than
        // 200 ms after the raw-contact pipe closes. This bounded three-second window applies only
        // after an actual photo write; converged no-change passes never enter it.
        const val PROVIDER_SETTLE_ATTEMPTS = 30
        const val PROVIDER_SETTLE_DELAY_MILLIS = 100L
        const val STATE_PREPARED = "PREPARED"
        const val STATE_COMMITTED = "COMMITTED"
        const val DATA_SYNC1 = "data_sync1"
        const val DATA_SYNC2 = "data_sync2"
    }
}

private sealed interface AndroidPhotoSettleResult {
    data class Stable(val observation: AndroidStableRawContactObservation) : AndroidPhotoSettleResult
    data class Stale(val category: AndroidPhotoProviderStaleCategory) : AndroidPhotoSettleResult
}
