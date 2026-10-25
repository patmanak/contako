package com.patmanak.contako.data.sync

import com.patmanak.contako.data.local.AndroidProjectionLedgerEntity
import com.patmanak.contako.data.local.ContactEntity

/** The retained locator is historical evidence after this guarded deletion receipt. */
internal fun hasCompletedAndroidContactDeletion(
    contact: ContactEntity,
    ledger: AndroidProjectionLedgerEntity?,
    providerEpoch: Long?,
): Boolean = ledger != null && providerEpoch != null &&
    ledger.accountId == contact.accountId && ledger.canonicalContactId == contact.id &&
    ledger.providerEpoch == providerEpoch && ledger.tombstoneState == "REMOTE_CONVERGED" &&
    ledger.projectionState == "DETACHED" && contact.isDeleted &&
    contact.pendingMutationRevision == null && contact.conflictState == null &&
    contact.remoteContactId == ledger.sourceIdentity
