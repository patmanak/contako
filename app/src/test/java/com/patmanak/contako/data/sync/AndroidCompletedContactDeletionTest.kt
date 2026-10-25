package com.patmanak.contako.data.sync

import com.patmanak.contako.data.local.AndroidProjectionLedgerEntity
import com.patmanak.contako.data.local.toEntity
import com.patmanak.contako.domain.model.CanonicalContact
import org.junit.Assert.*
import org.junit.Test

class AndroidCompletedContactDeletionTest {
    private val deleted = CanonicalContact("synthetic-owner", "contact", displayName = "Synthetic",
        revision = 3, remoteContactId = "remote", isDeleted = true).toEntity()
    private val receipt = AndroidProjectionLedgerEntity("synthetic-owner", "contact", 7, 2,
        42, "remote", null, null, null, null, "DETACHED", "BASELINED", "REMOTE_CONVERGED", "ADOPTED")

    @Test fun onlyTheCurrentConvergedDeletionReceiptQualifiesAndItsHistoricalLocatorIsNotALiveCopy() {
        assertTrue(hasCompletedAndroidContactDeletion(deleted, receipt, 2))
        assertFalse(hasCompletedAndroidContactDeletion(deleted, null, 2))
        assertFalse(hasCompletedAndroidContactDeletion(deleted, receipt, null))
        assertFalse(hasCompletedAndroidContactDeletion(deleted, receipt, 3))
        assertFalse(hasCompletedAndroidContactDeletion(deleted, receipt.copy(accountId = "foreign-owner"), 2))
        assertFalse(hasCompletedAndroidContactDeletion(deleted, receipt.copy(canonicalContactId = "other"), 2))
        assertFalse(hasCompletedAndroidContactDeletion(deleted, receipt.copy(sourceIdentity = "other"), 2))
        assertFalse(hasCompletedAndroidContactDeletion(deleted, receipt.copy(tombstoneState = "CANONICAL_COMMITTED"), 2))
        assertFalse(hasCompletedAndroidContactDeletion(deleted, receipt.copy(projectionState = "WRITE_PENDING"), 2))
        assertFalse(hasCompletedAndroidContactDeletion(deleted.copy(isDeleted = false), receipt, 2))
        assertFalse(hasCompletedAndroidContactDeletion(deleted.copy(pendingMutationRevision = 3), receipt, 2))
        assertFalse(hasCompletedAndroidContactDeletion(deleted.copy(conflictState = "REMOTE_DELETED"), receipt, 2))
    }
}
