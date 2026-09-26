package com.patmanak.contako.data.sync

import com.patmanak.contako.data.local.AndroidGroupProjectionLedgerEntity
import com.patmanak.contako.data.local.ContactGroupEntity

/** A retained deletion receipt is not a missing active Android group binding. */
internal fun isCompletedAndroidGroupDeletion(
    ledger: AndroidGroupProjectionLedgerEntity,
    canonical: ContactGroupEntity?,
    providerEpoch: Long,
): Boolean = canonical != null && canonical.accountId == ledger.accountId &&
    canonical.id == ledger.canonicalGroupId && ledger.providerEpoch == providerEpoch &&
    ledger.tombstoneState == "REMOTE_CONVERGED" && ledger.projectionState == "DETACHED" &&
    ledger.groupRowLocator == null && canonical.isDeleted &&
    canonical.pendingMutationRevision == null && canonical.conflictState == null &&
    canonical.remoteLabelId == ledger.sourceIdentity
