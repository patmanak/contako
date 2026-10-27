package com.patmanak.contako.data.sync

import androidx.room.withTransaction
import com.patmanak.contako.data.android.AndroidAdoptionState
import com.patmanak.contako.data.android.AndroidProjectionWriteState
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipSnapshotBinaryCodec
import com.patmanak.contako.data.android.mapping.AndroidGroupProjectionPolicy
import com.patmanak.contako.data.android.mapping.CanonicalAndroidContactMapper
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.local.AndroidProjectionLedgerEntity
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.data.local.toDomain

internal const val MAX_ANDROID_RECEIPT_PAGE_SIZE = 100

/** Complete local receipt proof shared by projection and durable recovery settlement. */
internal suspend fun currentAndroidProjectionReceiptIds(
    database: ContakoDatabase,
    account: AccountScope,
    providerEpoch: Long,
    page: List<AndroidProjectionLedgerEntity>,
    canonicalMapper: CanonicalAndroidContactMapper = CanonicalAndroidContactMapper(),
): Set<String> = database.withTransaction {
    require(page.size <= MAX_ANDROID_RECEIPT_PAGE_SIZE)
    if (page.isEmpty()) return@withTransaction emptySet()
    val ids = page.map { it.canonicalContactId }
    val contacts = database.contactDao().getForProjectionPage(account.value, ids).associateBy { it.contact.id }
    val memberships = database.androidGroupProjectionDao()
        .getMembershipsForProjectionPage(account.value, ids).associateBy { it.canonicalContactId }
    val baselines = database.androidGroupProjectionDao()
        .getMembershipBaselinesForProjectionPage(account.value, ids).associateBy { it.canonicalContactId }
    val groups = database.contactGroupDao().getAll(account.value).map { it.toDomain() }
    val policy = AndroidGroupProjectionPolicy()
    page.mapNotNull { ledger ->
        val canonical = contacts[ledger.canonicalContactId]?.toDomain() ?: return@mapNotNull null
        if (hasCompletedAndroidContactDeletion(contacts.getValue(ledger.canonicalContactId).contact,
                ledger, providerEpoch)) return@mapNotNull ledger.canonicalContactId
        if (ledger.projectionState != AndroidProjectionWriteState.CLEAN.name ||
            ledger.adoptionState != AndroidAdoptionState.ADOPTED.name ||
            ledger.pendingProjectionFingerprint != null || ledger.rawContactLocator == null ||
            ledger.tombstoneState != "NONE"
        ) return@mapNotNull null
        if (canonical.isDeleted || canonical.remoteContactId != ledger.sourceIdentity) return@mapNotNull null
        try {
            if (canonicalMapper.fingerprint(canonicalMapper.project(canonical)).sha256Hex !=
                ledger.canonicalProjectionFingerprint) return@mapNotNull null
            val membership = memberships[ledger.canonicalContactId] ?: return@mapNotNull null
            if (membership.projectionState != AndroidProjectionWriteState.CLEAN.name ||
                membership.pendingProjectionFingerprint != null || membership.providerEpoch != providerEpoch ||
                membership.rawContactLocator != ledger.rawContactLocator) return@mapNotNull null
            val baseline = baselines[ledger.canonicalContactId] ?: return@mapNotNull null
            if (AndroidGroupMembershipSnapshotBinaryCodec.integrityFingerprint(baseline.encodedSnapshot)
                    .sha256Hex != baseline.fingerprint) return@mapNotNull null
            val observed = AndroidGroupMembershipSnapshotBinaryCodec.decode(baseline.encodedSnapshot)
            observed.requireCurrentCanonicalContext(canonical)
            val desired = policy.projectMemberships(canonical, groups)
            if (observed.semanticFingerprint().sha256Hex != membership.androidBaselineFingerprint ||
                observed.canonicalGroupIds.toSet() != desired.canonicalGroupIds ||
                observed.membershipAvailability != desired.availability) return@mapNotNull null
            ledger.canonicalContactId
        } catch (_: IllegalArgumentException) {
            null // The guarded executor supplies the bounded failure reason; never claim CLEAN.
        }
    }.toSet()
}
