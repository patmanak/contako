package com.patmanak.contako.data.sync

import com.patmanak.contako.data.local.AndroidGroupProjectionLedgerEntity
import com.patmanak.contako.data.local.ContactGroupEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidCompletedGroupDeletionTest {
    @Test
    fun onlyConfirmedDeletionReceiptsMayBeExcludedFromActiveBindings() {
        val group = ContactGroupEntity("account", "group", "owner", "Test", "#8080FF", 0, true,
            2, 0, "remote", "version", null, null, true)
        val receipt = AndroidGroupProjectionLedgerEntity("account", "group", 3, 1, null, null,
            "remote", null, null, null, "DETACHED", "NONE", "REMOTE_CONVERGED", "SOURCE_ID_PENDING")
        assertTrue(isCompletedAndroidGroupDeletion(receipt, group, 1))
        assertFalse(isCompletedAndroidGroupDeletion(receipt, group.copy(isDeleted = false), 1))
        assertFalse(isCompletedAndroidGroupDeletion(receipt, group.copy(pendingMutationRevision = 2), 1))
        assertFalse(isCompletedAndroidGroupDeletion(receipt, group.copy(conflictState = "CONFLICT"), 1))
        assertFalse(isCompletedAndroidGroupDeletion(receipt, group.copy(remoteLabelId = "other"), 1))
        assertFalse(isCompletedAndroidGroupDeletion(receipt, group.copy(accountId = "other"), 1))
        assertFalse(isCompletedAndroidGroupDeletion(receipt, null, 1))
        assertFalse(isCompletedAndroidGroupDeletion(receipt, group, 2))
        assertFalse(isCompletedAndroidGroupDeletion(receipt.copy(tombstoneState = "NONE"), group, 1))
    }
}
