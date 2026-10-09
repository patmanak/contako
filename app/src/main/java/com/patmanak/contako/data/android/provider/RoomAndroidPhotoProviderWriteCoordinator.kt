package com.patmanak.contako.data.android.provider

import android.content.ContentResolver
import android.content.ContentProviderOperation
import android.content.OperationApplicationException
import android.provider.ContactsContract
import androidx.room.withTransaction
import com.patmanak.contako.android.account.ContakoAndroidAccountContract
import com.patmanak.contako.data.android.mapping.AndroidContactRow
import com.patmanak.contako.data.android.mapping.AndroidRowKind
import com.patmanak.contako.data.local.AndroidPhotoProjectionReceiptEntity
import com.patmanak.contako.data.local.AndroidPhotoProviderWriteJournalEntity
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.data.sync.AndroidInteroperabilityContext

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
    PRE_WRITE_PHOTO_IDENTITY,
    PRE_WRITE_PHOTO_ORDER,
    PRE_WRITE_PHOTO_PRIMARY,
    PRE_WRITE_INLINE_BLOB,
    PRE_WRITE_PHOTO_PAYLOAD,
    POST_STREAM_OBSERVATION,
    POST_STREAM_PHOTO_ROW,
    POST_STREAM_DISPLAY_PHOTO,
    POST_BIND_OBSERVATION,
    JOURNAL_COMMIT,
}


/**
 * Synchronous, version-guarded photo projection. Provider-produced bytes are the Android
 * representation receipt; the canonical source remains unchanged. A lost return without
 * a durable receipt is never evidence that arbitrary provider bytes came from this write.
 */
internal class RoomAndroidPhotoProviderWriteCoordinator(
    private val database: ContakoDatabase,
    private val contentResolver: ContentResolver,
    private val reader: AndroidContactsProviderReader,
    private val binaryLoader: AndroidProjectionBinaryLoader,
) {
    suspend fun reconcileCommittedIdentity(
        context: AndroidInteroperabilityContext,
        canonicalContactId: String,
        canonicalRevision: Long,
        observation: AndroidStableRawContactObservation,
        desiredPhoto: AndroidContactRow?,
    ): Boolean = database.withTransaction {
        val photo = observation.dataRows.singleOrNull { it.isStandardPhoto } ?: return@withTransaction true
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
        val source = try { binaryLoader.load(reference) } catch (_: RuntimeException) { null }
            ?: return repair(AndroidPhotoProviderRepairCategory.PAYLOAD_UNAVAILABLE)
        if (source.isEmpty()) return repair(AndroidPhotoProviderRepairCategory.PAYLOAD_EMPTY)
        if (source.size > MAX_PHOTO_BYTES) return repair(AndroidPhotoProviderRepairCategory.PAYLOAD_BOUND_EXCEEDED)
        val bytes = AndroidProjectionPhotoScaler.normalizeForAtomicPhoto(source)
            ?: return repair(AndroidPhotoProviderRepairCategory.PAYLOAD_BOUND_EXCEEDED)
        val command = AndroidPhotoProviderWriteJournalEntity(
            context.account.value, canonicalContactId, accountName.value, context.providerEpoch,
            rawContactId, expectedRawContactVersion, expectedSourceIdentity, canonicalRevision,
            ledgerRevision, photo.identity.canonicalValueId, reference, source.size.toLong(),
            androidPhotoSha256(source), "PREPARED", null,
        )
        val before = readExact(accountName, rawContactId)
            ?: return stale(AndroidPhotoProviderStaleCategory.PRE_WRITE_OBSERVATION)
        if (!before.matchesIdentity(command) || before.rawContact.dirty ||
            before.rawContact.version != expectedRawContactVersion ||
            context.androidAccountName != accountName.value) {
            return stale(AndroidPhotoProviderStaleCategory.PRE_WRITE_VERSION)
        }
        val row = before.dataRows.singleOrNull { it.isStandardPhoto }
            ?: return stale(AndroidPhotoProviderStaleCategory.POST_STREAM_PHOTO_ROW)
        if (row.canonicalValueId != photo.identity.canonicalValueId)
            return stale(AndroidPhotoProviderStaleCategory.PRE_WRITE_PHOTO_IDENTITY)
        if (row.canonicalOrder != photo.order)
            return stale(AndroidPhotoProviderStaleCategory.PRE_WRITE_PHOTO_ORDER)
        if (row.isPrimary != photo.isPrimary)
            return stale(AndroidPhotoProviderStaleCategory.PRE_WRITE_PHOTO_PRIMARY)
        // IS_SUPER_PRIMARY belongs to the aggregate contact across accounts. It is not
        // proof of payload delivery; identity, order, primary, version and byte receipt
        // guards still apply to this owned PHOTO row.
        val previous = database.androidGroupProjectionDao().getPhotoProjectionReceipt(
            context.account.value, canonicalContactId)
        if (previous != null && previous.binaryReference == reference &&
            previous.canonicalValueId == command.canonicalValueId && previous.sourceSha256 == command.contentSha256 &&
            previous.sourceSize == command.contentSize && previous.matchesPhoto(context, canonicalContactId, before)) {
            return database.withTransaction {
                if (!hasExactDurableClaims(command)) {
                    stale(AndroidPhotoProviderStaleCategory.PRE_WRITE_VERSION)
                } else AndroidPhotoProviderWriteResult.Committed(before.rawContact.version)
            }
        }
        val prepared = database.withTransaction {
            if (!hasExactDurableClaims(command)) return@withTransaction false
            database.androidGroupProjectionDao().upsertPhotoProviderWriteJournal(command)
            true
        }
        if (!prepared) return stale(AndroidPhotoProviderStaleCategory.JOURNAL_PREPARATION)
        // VERSION advances once for this transaction. Unexpected OEM behavior or any concurrent
        // mutation is rejected rather than classified as recompression.
        if (expectedRawContactVersion == Long.MAX_VALUE) return stale(AndroidPhotoProviderStaleCategory.PRE_WRITE_VERSION)
        if (!writeAtomic(accountName, command, row.dataRowId, bytes)) {
            return stale(AndroidPhotoProviderStaleCategory.PRE_WRITE_PHOTO_UPDATE)
        }
        val after = readExact(accountName, rawContactId)
            ?: return stale(AndroidPhotoProviderStaleCategory.POST_BIND_OBSERVATION)
        if (!after.matchesIdentity(command) || after.rawContact.dirty ||
            after.rawContact.version != expectedRawContactVersion + 1 ||
            before.dataRows.filterNot { it.isStandardPhoto } != after.dataRows.filterNot { it.isStandardPhoto }) {
            return stale(AndroidPhotoProviderStaleCategory.POST_BIND_OBSERVATION)
        }
        val output = after.dataRows.singleOrNull { it.isStandardPhoto }
            ?: return stale(AndroidPhotoProviderStaleCategory.POST_STREAM_PHOTO_ROW)
        val actual = output.binarySlot
            ?: return stale(AndroidPhotoProviderStaleCategory.POST_STREAM_DISPLAY_PHOTO)
        if (output.dataRowId != row.dataRowId || output.canonicalValueId != command.canonicalValueId ||
            actual.isEmpty() || actual.size > MAX_PHOTO_BYTES) {
            return repair(AndroidPhotoProviderRepairCategory.POST_BIND_VALUE_IDENTITY)
        }
        // Data.PHOTO_FILE_ID must identify a newly published immutable display file, not
        // the previous display stream. A provider that publishes it later remains unverified.
        val oldFile = row.stringSlots[13]?.toLongOrNull()
        val newFile = output.stringSlots[13]?.toLongOrNull()
        val published = if (newFile != null && newFile > 0) {
            newFile != oldFile && readDisplayFile(newFile).contentEquals(actual)
        } else readInlinePhoto(accountName, rawContactId, output.dataRowId).contentEquals(actual)
        if (!published) {
            return stale(AndroidPhotoProviderStaleCategory.POST_STREAM_DISPLAY_PHOTO)
        }
        val receipt = AndroidPhotoProjectionReceiptEntity(
            command.accountId, command.canonicalContactId, command.androidAccountName, command.providerEpoch,
            command.rawContactLocator, output.dataRowId, command.canonicalValueId, command.binaryReference,
            command.contentSha256, command.contentSize, androidPhotoSha256(actual), actual.size.toLong(),
            after.rawContact.version,
        )
        val confirmed = readExact(accountName, rawContactId)
        if (confirmed == null || confirmed.rawContact != after.rawContact ||
            !receipt.matchesPhoto(context, canonicalContactId, confirmed) ||
            confirmed.dataRows != after.dataRows) {
            return stale(AndroidPhotoProviderStaleCategory.JOURNAL_COMMIT)
        }
        return database.withTransaction {
            if (database.androidGroupProjectionDao().getPhotoProviderWriteJournal(
                    command.accountId, command.canonicalContactId) != command || !hasExactDurableClaims(command)) {
                return@withTransaction stale(AndroidPhotoProviderStaleCategory.JOURNAL_COMMIT)
            }
            database.androidGroupProjectionDao().upsertPhotoProjectionReceipt(receipt)
            database.androidGroupProjectionDao().upsertPhotoProviderWriteJournal(
                command.copy(state = "COMMITTED", resultRawContactVersion = after.rawContact.version))
            AndroidPhotoProviderWriteResult.Committed(after.rawContact.version)
        }
    }

    suspend fun cleanupCommitted(accountId: String, canonicalContactId: String, contentSha256: String) {
        // The representation receipt survives journal cleanup and subsequent native note edits.
        database.androidGroupProjectionDao().deleteCommittedPhotoProviderWriteJournal(
            accountId, canonicalContactId, contentSha256)
    }

    private fun writeAtomic(account: AndroidProviderAccountName, command: AndroidPhotoProviderWriteJournalEntity,
        rowId: Long, bytes: ByteArray): Boolean {
        val rawUri = syncAdapterUri(ContactsContract.RawContacts.CONTENT_URI, account)
        val selection = "${ContactsContract.RawContacts._ID} = ? AND " +
            "${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND ${ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND " +
            "${ContactsContract.RawContacts.SOURCE_ID} = ? AND ${ContactsContract.RawContacts.VERSION} = ? AND " +
            "${ContactsContract.RawContacts.SYNC1} = ? AND ${ContactsContract.RawContacts.DIRTY} = 0 AND " +
            "${ContactsContract.RawContacts.DELETED} = 0"
        val args = arrayOf(command.rawContactLocator.toString(), account.value,
            ContakoAndroidAccountContract.ACCOUNT_TYPE, command.expectedSourceIdentity,
            command.expectedRawContactVersion.toString(), command.canonicalContactId)
        val values = android.content.ContentValues().apply {
            put(ContactsContract.CommonDataKinds.Photo.PHOTO, bytes)
        }
        return try {
            contentResolver.applyBatch(ContakoAndroidAccountContract.CONTACTS_AUTHORITY, arrayListOf(
                ContentProviderOperation.newAssertQuery(rawUri).withSelection(selection, args)
                    .withExpectedCount(1).build(),
                ContentProviderOperation.newUpdate(syncAdapterUri(ContactsContract.Data.CONTENT_URI, account))
                    .withSelection("${ContactsContract.Data._ID} = ? AND ${ContactsContract.Data.RAW_CONTACT_ID} = ? AND " +
                        "${ContactsContract.Data.MIMETYPE} = ?", arrayOf(rowId.toString(),
                        command.rawContactLocator.toString(), ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE))
                    .withValues(values).withExpectedCount(1).build(),
            ))
            true
        } catch (_: OperationApplicationException) { false }
        catch (_: android.os.RemoteException) {
            throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.PROVIDER_UNAVAILABLE)
        } catch (_: SecurityException) {
            throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.PERMISSION_DENIED)
        } catch (_: RuntimeException) {
            throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.MALFORMED_PROVIDER_DATA)
        }
    }

    private suspend fun hasExactDurableClaims(command: AndroidPhotoProviderWriteJournalEntity): Boolean {
        val account = database.androidProjectionLedgerDao().getAccount(command.accountId) ?: return false
        val ledger = database.androidProjectionLedgerDao().get(command.accountId, command.canonicalContactId) ?: return false
        val canonical = database.contactDao().get(command.accountId, command.canonicalContactId)?.contact ?: return false
        return account.providerEpoch == command.providerEpoch && account.androidAccountName == command.androidAccountName &&
            canonical.revision == command.expectedCanonicalRevision && !canonical.isDeleted && canonical.conflictState == null &&
            ledger.revision == command.expectedLedgerRevision && ledger.tombstoneState == "NONE" &&
            ledger.providerEpoch == command.providerEpoch && ledger.rawContactLocator == command.rawContactLocator &&
            ledger.sourceIdentity == command.expectedSourceIdentity && canonical.remoteContactId == command.expectedSourceIdentity
    }

    private fun readExact(accountName: AndroidProviderAccountName, rawContactId: Long) =
        when (val result = reader.readStableRawContact(accountName, rawContactId)) {
            AndroidStableRawContactPageResult.ReplanRequired -> null
            is AndroidStableRawContactPageResult.Stable -> result.page.observations.singleOrNull()
        }

    private fun AndroidStableRawContactObservation.matchesIdentity(command: AndroidPhotoProviderWriteJournalEntity) =
        !rawContact.deleted && rawContact.rawContactId == command.rawContactLocator &&
            rawContact.canonicalContactIdClaim == command.canonicalContactId &&
            rawContact.sourceIdentity == command.expectedSourceIdentity

    private fun stale(category: AndroidPhotoProviderStaleCategory) = AndroidPhotoProviderWriteResult.Stale(category)
    private fun repair(category: AndroidPhotoProviderRepairCategory) = AndroidPhotoProviderWriteResult.RepairRequired(category)

    private fun readDisplayFile(fileId: Long): ByteArray? = try {
        val uri = android.content.ContentUris.withAppendedId(ContactsContract.DisplayPhoto.CONTENT_URI, fileId)
        contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            descriptor.createInputStream().use { input ->
                val result = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (result.size() + count > MAX_PHOTO_BYTES) return null
                    result.write(buffer, 0, count)
                }
                result.toByteArray()
            }
        }
    } catch (_: java.io.IOException) { null }

    private fun readInlinePhoto(account: AndroidProviderAccountName, rawId: Long, rowId: Long): ByteArray? =
        contentResolver.query(ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Photo.PHOTO),
            "${ContactsContract.Data._ID} = ? AND ${ContactsContract.Data.RAW_CONTACT_ID} = ? AND " +
                "${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND ${ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND " +
                "length(${ContactsContract.CommonDataKinds.Photo.PHOTO}) <= ?",
            arrayOf(rowId.toString(), rawId.toString(), account.value, ContakoAndroidAccountContract.ACCOUNT_TYPE,
                AndroidProjectionPhotoScaler.MAX_INLINE_PHOTO_BYTES.toString()), null)?.use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) null
            else cursor.getBlob(0).takeIf { it.isNotEmpty() && !cursor.moveToNext() }
        }

    private companion object {
        const val MAX_PHOTO_BYTES = 10 * 1_024 * 1_024
        const val STATE_COMMITTED = "COMMITTED"
    }
}
