package com.patmanak.contako.data.local

import androidx.room.withTransaction
import com.patmanak.contako.data.android.mapping.AndroidContactSnapshotBinaryCodec
import com.patmanak.contako.data.android.mapping.AndroidRowKind
import com.patmanak.contako.domain.sync.ContactSyncEvidenceKind
import java.security.MessageDigest

/** Called only at a real acknowledgement/readback transaction, never from presentation reads. */
internal class RoomContactSyncEvidenceStore(private val database: ContakoDatabase) {
    private val dao get() = database.contactSyncEvidenceDao()

    suspend fun recordProton(accountId: String, contactId: String, canonicalRevision: Long,
        sourceIdentity: String, sourceVersion: String?, kind: ContactSyncEvidenceKind, confirmedAtEpochMillis: Long): Boolean =
        database.withTransaction {
            require(kind in setOf(ContactSyncEvidenceKind.VERIFIED_READ, ContactSyncEvidenceKind.WRITE_ACKNOWLEDGEMENT,
                ContactSyncEvidenceKind.CONFIRMED_ABSENCE))
            val contact = database.contactDao().getEntity(accountId, contactId) ?: return@withTransaction false
            if (contact.revision != canonicalRevision || contact.remoteContactId != sourceIdentity ||
                contact.remoteVersion != sourceVersion || confirmedAtEpochMillis < 0 ||
                (kind == ContactSyncEvidenceKind.CONFIRMED_ABSENCE) != contact.isDeleted) return@withTransaction false
            save(ContactSyncEvidenceEntity(accountId, contactId, "PROTON", canonicalRevision, confirmedAtEpochMillis,
                kind.name, sourceIdentity, sourceVersion))
        }

    suspend fun recordAndroidReadback(accountId: String, contactId: String, canonicalRevision: Long,
        providerEpoch: Long, sourceIdentity: String?, confirmedAtEpochMillis: Long,
        expectedAndroidAccountName: String, expectedRawContactLocator: Long?): Boolean = database.withTransaction {
        val contact = database.contactDao().getEntity(accountId, contactId) ?: return@withTransaction false
        val ledger = database.androidProjectionLedgerDao().get(accountId, contactId) ?: return@withTransaction false
        val account = database.androidProjectionLedgerDao().getAccount(accountId) ?: return@withTransaction false
        val membership = database.androidGroupProjectionDao().getMembership(accountId, contactId) ?: return@withTransaction false
        val baseline = database.androidProjectionLedgerDao().getBaseline(accountId, contactId) ?: return@withTransaction false
        val integrity = dao.membershipBaselineIntegrity(accountId, contactId) ?: return@withTransaction false
        if (confirmedAtEpochMillis < 0 || contact.revision != canonicalRevision || contact.isDeleted ||
            contact.remoteContactId != sourceIdentity || ledger.sourceIdentity != sourceIdentity ||
            account.providerEpoch != providerEpoch || ledger.providerEpoch != providerEpoch || membership.providerEpoch != providerEpoch ||
            account.androidAccountName != expectedAndroidAccountName || account.providerRepairPending ||
            expectedRawContactLocator == null || ledger.rawContactLocator != expectedRawContactLocator ||
            membership.rawContactLocator != expectedRawContactLocator || ledger.projectionState != "CLEAN" ||
            ledger.pendingProjectionFingerprint != null || ledger.tombstoneState != "NONE" ||
            ledger.canonicalProjectionFingerprint == null || ledger.androidBaselineFingerprint != baseline.fingerprint ||
            membership.projectionState != "CLEAN" || membership.pendingProjectionFingerprint != null ||
            membership.canonicalProjectionFingerprint == null || membership.androidBaselineFingerprint == null) return@withTransaction false
        val snapshot = try { AndroidContactSnapshotBinaryCodec.decode(baseline.encodedSnapshot) }
            catch (_: IllegalArgumentException) { return@withTransaction false }
        val photos = snapshot.rows.filter { it.kind == AndroidRowKind.PHOTO }
        // An unavailable canonical image may have been omitted by the projection mapper. It
        // cannot become a dated whole-projection confirmation merely because the other rows fit.
        if (photos.isEmpty() && dao.canonicalPhotoCount(accountId, contactId) > 0) return@withTransaction false
        val photoKey = if (photos.isEmpty()) null else {
            val photo = photos.singleOrNull() ?: return@withTransaction false
            val receipt = dao.photoReceiptMetadata(accountId, contactId) ?: return@withTransaction false
            // This is the real write boundary, where the immutable source reference is already
            // available. The UI reader intentionally selects receipt metadata without this field.
            val fullReceipt = database.androidGroupProjectionDao().getPhotoProjectionReceipt(accountId, contactId)
                ?: return@withTransaction false
            if (receipt.androidAccountName != expectedAndroidAccountName || receipt.providerEpoch != providerEpoch ||
                receipt.rawContactLocator != expectedRawContactLocator || receipt.canonicalValueId != photo.identity.canonicalValueId ||
                receipt.dataRowLocator != photo.identity.providerRowId || fullReceipt.binaryReference != photo.binaryReference)
                return@withTransaction false
            receipt.key()
        }
        save(ContactSyncEvidenceEntity(accountId, contactId, "ANDROID", canonicalRevision, confirmedAtEpochMillis,
            ContactSyncEvidenceKind.ANDROID_READBACK.name, sourceIdentity, providerEpoch = providerEpoch,
            androidAccountName = expectedAndroidAccountName, rawContactLocator = expectedRawContactLocator,
            ledgerRevision = ledger.revision, canonicalFingerprint = ledger.canonicalProjectionFingerprint,
            baselineFingerprint = baseline.fingerprint, membershipRevision = membership.revision,
            membershipFingerprint = membership.canonicalProjectionFingerprint,
            membershipBaselineFingerprint = membership.androidBaselineFingerprint, membershipBaselineIntegrity = integrity,
            preferredEmailId = membership.preferredEmailValueId, photoReceiptKey = photoKey))
    }

    suspend fun recordAndroidAbsence(accountId: String, contactId: String, canonicalRevision: Long,
        providerEpoch: Long, sourceIdentity: String?, confirmedAtEpochMillis: Long,
        expectedAndroidAccountName: String, expectedRawContactLocator: Long?): Boolean = database.withTransaction {
        val contact = database.contactDao().getEntity(accountId, contactId) ?: return@withTransaction false
        val ledger = database.androidProjectionLedgerDao().get(accountId, contactId) ?: return@withTransaction false
        val account = database.androidProjectionLedgerDao().getAccount(accountId) ?: return@withTransaction false
        if (confirmedAtEpochMillis < 0 || !contact.isDeleted || contact.revision != canonicalRevision ||
            contact.remoteContactId != sourceIdentity || ledger.sourceIdentity != sourceIdentity ||
            account.providerEpoch != providerEpoch || ledger.providerEpoch != providerEpoch ||
            account.androidAccountName != expectedAndroidAccountName || account.providerRepairPending ||
            ledger.projectionState != "DETACHED" || ledger.tombstoneState != "REMOTE_CONVERGED" ||
            ledger.pendingProjectionFingerprint != null ||
            (ledger.rawContactLocator != null && ledger.rawContactLocator != expectedRawContactLocator)) return@withTransaction false
        save(ContactSyncEvidenceEntity(accountId, contactId, "ANDROID", canonicalRevision, confirmedAtEpochMillis,
            ContactSyncEvidenceKind.ANDROID_ABSENCE.name, sourceIdentity, providerEpoch = providerEpoch,
            androidAccountName = expectedAndroidAccountName, rawContactLocator = expectedRawContactLocator,
            ledgerRevision = ledger.revision))
    }

    private suspend fun save(evidence: ContactSyncEvidenceEntity): Boolean {
        val previous = dao.get(evidence.accountId, evidence.contactId, evidence.peer)
        if (previous != null && previous.canonicalRevision > evidence.canonicalRevision) return false
        dao.upsert(evidence)
        return true
    }
}

internal fun ContactPhotoReceiptMetadata.key(): String {
    val text = listOf(androidAccountName, providerEpoch, rawContactLocator, dataRowLocator, canonicalValueId,
        sourceSha256, sourceSize, readbackSha256, readbackSize, rawContactVersion).joinToString("|")
    return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}
