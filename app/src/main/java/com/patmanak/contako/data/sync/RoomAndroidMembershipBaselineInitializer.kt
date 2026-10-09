package com.patmanak.contako.data.sync

import androidx.room.withTransaction
import com.patmanak.contako.data.android.AndroidIngestionState
import com.patmanak.contako.data.android.AndroidProjectionWriteState
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipAvailability
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipSnapshot
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipSnapshotBinaryCodec
import com.patmanak.contako.data.local.AndroidGroupMembershipBaselineEntity
import com.patmanak.contako.data.local.AndroidGroupMembershipProjectionLedgerEntity
import com.patmanak.contako.data.local.AndroidProjectionLedgerEntity
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.policy.CanonicalPrimaryValuePolicy

/** The exact historical initial marker, not evidence that Android already matches canonical data. */
internal class AndroidMembershipInitializationSeed(
    context: AndroidInteroperabilityContext,
    canonical: CanonicalContact,
    rawContactLocator: Long,
) {
    private val preferredEmail = CanonicalPrimaryValuePolicy.preferredEmail(canonical)?.id
    private val snapshot = AndroidGroupMembershipSnapshot.create(
        context.account.value, canonical.id, preferredEmail,
        if (preferredEmail == null) AndroidGroupMembershipAvailability.NO_EMAIL
        else AndroidGroupMembershipAvailability.AVAILABLE,
        emptyList(),
    )
    val ledger = AndroidGroupMembershipProjectionLedgerEntity(
        context.account.value, canonical.id, 0, context.providerEpoch, rawContactLocator,
        preferredEmail, null, snapshot.semanticFingerprint().sha256Hex, null,
        AndroidProjectionWriteState.CLEAN.name, AndroidIngestionState.NONE.name,
    )
    private val encoded = AndroidGroupMembershipSnapshotBinaryCodec.encode(snapshot)
    val baseline = AndroidGroupMembershipBaselineEntity(
        context.account.value, canonical.id,
        AndroidGroupMembershipSnapshotBinaryCodec.integrityFingerprint(encoded).sha256Hex, encoded,
    )

    fun matchesInterruptedInitialization(existing: AndroidGroupMembershipProjectionLedgerEntity): Boolean =
        existing == ledger
}

/**
 * Called only after a stable, clean, source-owned provider observation. Creates the initial pair
 * atomically. An interrupted legacy seed can recover its exact empty snapshot from its retained
 * marker; a completed, pending, detached or unrecognized baseline MUST NOT be reconstructed here.
 * Subsequent provider planning and verified write completion still establish the actual baseline.
 */
internal class RoomAndroidMembershipBaselineInitializer(private val database: ContakoDatabase) {
    suspend fun ensure(
        context: AndroidInteroperabilityContext,
        expectedContactLedger: AndroidProjectionLedgerEntity,
        canonical: CanonicalContact,
    ): Boolean = database.withTransaction {
        val contactDao = database.androidProjectionLedgerDao()
        val account = contactDao.getAccount(context.account.value) ?: return@withTransaction false
        val currentContact = database.contactDao().get(context.account.value, canonical.id)?.contact
            ?: return@withTransaction false
        if (account.revision != context.accountRevision || account.providerEpoch != context.providerEpoch ||
            account.androidAccountName != context.androidAccountName || canonical.accountId != context.account.value ||
            currentContact.revision != canonical.revision || currentContact.isDeleted ||
            currentContact.remoteContactId != expectedContactLedger.sourceIdentity ||
            expectedContactLedger.accountId != context.account.value ||
            expectedContactLedger.canonicalContactId != canonical.id ||
            expectedContactLedger.providerEpoch != context.providerEpoch ||
            expectedContactLedger.tombstoneState != "NONE" ||
            expectedContactLedger.sourceIdentity == null ||
            contactDao.get(context.account.value, canonical.id) != expectedContactLedger
        ) return@withTransaction false
        val locator = expectedContactLedger.rawContactLocator ?: return@withTransaction false
        val groupDao = database.androidGroupProjectionDao()
        val existing = groupDao.getMembership(context.account.value, canonical.id)
        if (groupDao.getMembershipBaseline(context.account.value, canonical.id) != null) {
            return@withTransaction true
        }
        val seed = AndroidMembershipInitializationSeed(context, canonical, locator)
        if (groupDao.getMembershipCommitReceipt(context.account.value, canonical.id) != null) {
            return@withTransaction true // Missing observation proof remains a repair obligation.
        }
        if (existing != null && !seed.matchesInterruptedInitialization(existing)) {
            return@withTransaction true
        }
        if (existing == null) check(groupDao.insertMembership(seed.ledger) != -1L)
        groupDao.upsertMembershipBaseline(seed.baseline)
        true
    }
}
