package com.patmanak.contako.data.sync

import com.patmanak.contako.data.gateway.*
import com.patmanak.contako.data.android.mapping.*
import com.patmanak.contako.data.android.provider.*
import com.patmanak.contako.data.local.DeletionRecoveryEntity
import com.patmanak.contako.domain.sync.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DeletedContactRecoveryTest {
    @Test fun boundedVerificationReachesLaterCandidatesAcrossRestartDespitePermanentEarlierFailures() = runTest {
        val rows = (0..100).map { index -> recovery().copy(contactId = "source-$index", remoteId = "remote-$index",
            state = DeletionRecoveryState.NEEDS_VERIFICATION.name, choice = null) }
        val turns = mutableMapOf<String, Long>()
        val readIds = mutableListOf<String>()
        val store = object : DeletedContactRecoveryExecutionStore {
            var nextTurn = 0L
            override suspend fun discover(account: String) = Unit
            override suspend fun eligible(account: String) = rows.sortedWith(
                compareBy<DeletionRecoveryEntity> { turns[it.contactId] ?: 0L }.thenBy { it.contactId })
            override suspend fun markExamined(row: DeletionRecoveryEntity): Boolean {
                turns[row.contactId] = ++nextTurn
                return true
            }
            override suspend fun verifyAbsence(row: DeletionRecoveryEntity): Boolean = error("No absence was proved")
            override suspend fun block(row: DeletionRecoveryEntity, reason: DeletionRecoveryBlocker) = Unit
        }
        val gateway = ProtonContactExistenceGateway { _, id ->
            assertNotNull("Rotation must be durable before reading", turns[rows.single { it.remoteId == id.value }.contactId])
            readIds += id.value
            if (readIds.size % 2 == 0) GatewayOutcome.Success(RemoteContactPresence.PRESENT)
            else GatewayOutcome.Failure(GatewayFailureCategory.NETWORK_UNAVAILABLE)
        }
        fun restartedPhase() = DeletedContactRecovery(store, gateway, DeletedContactNativeCleanup { _, _ ->
            error("Unproved absence cannot authorize cleanup")
        })
        assertEquals(SyncPassOutcome.ACTION_REQUIRED, restartedPhase().run(AccountScope("owner")) { false })
        assertEquals(100, readIds.size)
        assertEquals(100, readIds.toSet().size)
        assertEquals(SyncPassOutcome.ACTION_REQUIRED, restartedPhase().run(AccountScope("owner")) { false })
        assertEquals(200, readIds.size)
        assertEquals(101, readIds.toSet().size)
    }

    @Test fun staleRotationClaimDoesNotReadOrManufactureAbsenceEvidence() = runTest {
        val store = object : DeletedContactRecoveryExecutionStore {
            override suspend fun discover(account: String) = Unit
            override suspend fun eligible(account: String) = listOf(recovery())
            override suspend fun markExamined(row: DeletionRecoveryEntity) = false
            override suspend fun verifyAbsence(row: DeletionRecoveryEntity): Boolean = error("No proof")
            override suspend fun block(row: DeletionRecoveryEntity, reason: DeletionRecoveryBlocker) = Unit
        }
        val phase = DeletedContactRecovery(store, ProtonContactExistenceGateway { _, _ -> error("A changed generation must not be read") },
            DeletedContactNativeCleanup { _, _ -> error("No cleanup") })
        assertEquals(SyncPassOutcome.ACTION_REQUIRED, phase.run(AccountScope("owner")) { false })
    }

    @Test fun cleanProviderValuesMustStillEqualTheIngestedWholeBaseline() {
        val baseline = AndroidContactSnapshot("source", listOf(AndroidContactRow(
            AndroidValueIdentity("note", 7), AndroidRowKind.NOTE, value = "Saved draft")))
        val row = AndroidOwnedDataRow(7, 21, "vnd.android.cursor.item/note", "note", 0,
            linkedValueIdsEncoding = null, isPrimary = false, isSuperPrimary = false,
            stringSlots = List(14) { if (it == 0) "Saved draft" else null }, binarySlot = null)
        fun matches(candidate: AndroidOwnedDataRow) = recoveryObservationMatchesContactBaseline(
            AndroidProviderAccountName("synthetic-account"), "source", baseline, ContactRows(listOf(candidate))) { _, _ -> false }
        assertTrue(matches(row))
        assertFalse(matches(row.copy(stringSlots = List(14) { if (it == 0) "New clean native edit" else null })))
        assertFalse(matches(row.copy(dataRowId = 8)))
        assertFalse(matches(row.copy(canonicalValueId = "different-claim")))
    }

    @Test fun inventoryOrGenericNotFoundCannotAuthorizeNativeCleanup() = runTest {
        val store = MemoryDeletionRecoveryStore(recovery())
        var cleaned = false
        val phase = DeletedContactRecovery(store,
            ProtonContactExistenceGateway { account, id ->
                assertEquals("owner", account.value); assertEquals("old-remote", id.value)
                GatewayOutcome.Failure(GatewayFailureCategory.NOT_FOUND)
            }, DeletedContactNativeCleanup { _, _ -> cleaned = true; DeletedContactNativeResult.Complete })
        assertEquals(SyncPassOutcome.ACTION_REQUIRED, phase.run(AccountScope("owner")) { false })
        assertFalse(cleaned)
        assertFalse(store.verified)
        assertEquals(DeletionRecoveryBlocker.REMOTE_UNAVAILABLE, store.blocker)
    }

    @Test fun oldRemoteReappearanceInvalidatesConsentBeforeAnyLocalCleanup() = runTest {
        val store = MemoryDeletionRecoveryStore(recovery())
        val phase = DeletedContactRecovery(store,
            ProtonContactExistenceGateway { _, _ -> GatewayOutcome.Success(RemoteContactPresence.PRESENT) },
            DeletedContactNativeCleanup { _, _ -> error("A reappeared source cannot be cleaned") })
        assertEquals(SyncPassOutcome.ACTION_REQUIRED, phase.run(AccountScope("owner")) { false })
        assertEquals(DeletionRecoveryBlocker.REMOTE_PRESENT, store.blocker)
        assertFalse(store.verified)
    }

    @Test fun nativeRevisionChangeAfterRemoteReadRetainsTheChoiceWithoutExecutingIt() = runTest {
        val store = MemoryDeletionRecoveryStore(recovery(), current = false)
        val phase = DeletedContactRecovery(store, absent(),
            DeletedContactNativeCleanup { _, _ -> error("Stale local revision cannot be executed") })
        assertEquals(SyncPassOutcome.ACTION_REQUIRED, phase.run(AccountScope("owner")) { false })
        assertEquals(DeletionRecoveryBlocker.LOCAL_CHANGED, store.blocker)
    }

    @Test fun confirmedRemoteDeletionWithoutExplicitChoiceNeverRecreatesOrCleans() = runTest {
        val store = MemoryDeletionRecoveryStore(recovery().copy(choice = null, state = DeletionRecoveryState.READY.name))
        val phase = DeletedContactRecovery(store, absent(),
            DeletedContactNativeCleanup { _, _ -> error("Reading recovery does not choose an outcome") })
        assertEquals(SyncPassOutcome.SUCCESS, phase.run(AccountScope("owner")) { false })
        assertTrue(store.verified)
        assertNull(store.blocker)
    }

    @Test fun restartOfPreparedCleanupRequiresAnotherTargetedAbsenceRead() = runTest {
        val candidate = recovery().copy(state = DeletionRecoveryState.CLEANUP_PENDING.name,
            cleanupEpoch = 3, cleanupRaw = 21, cleanupVersion = 8, cleanupLedgerRevision = 5)
        val store = MemoryDeletionRecoveryStore(candidate)
        var reads = 0
        var executions = 0
        val phase = DeletedContactRecovery(store,
            ProtonContactExistenceGateway { _, _ -> reads++; GatewayOutcome.Success(RemoteContactPresence.CONFIRMED_ABSENT) },
            DeletedContactNativeCleanup { row, _ ->
                assertTrue(store.verified)
                assertEquals(candidate, row)
                assertEquals(++executions, reads)
                DeletedContactNativeResult.Complete
            })
        repeat(2) { assertEquals(SyncPassOutcome.SUCCESS, phase.run(AccountScope("owner")) { false }) }
        assertEquals(2, reads)
    }

    @Test fun nativeDirtyOrOpaqueDataKeepsRecoveryBlockedInsteadOfAcknowledgingCleanup() = runTest {
        for (reason in listOf(DeletionRecoveryBlocker.NATIVE_CHANGES, DeletionRecoveryBlocker.OPAQUE_ANDROID_DATA)) {
            val store = MemoryDeletionRecoveryStore(recovery())
            val phase = DeletedContactRecovery(store, absent(), DeletedContactNativeCleanup { _, _ ->
                DeletedContactNativeResult.Blocked(reason)
            })
            assertEquals(SyncPassOutcome.ACTION_REQUIRED, phase.run(AccountScope("owner")) { false })
            assertEquals(reason, store.blocker)
        }
    }

    @Test fun cancellationAfterTargetedReadDoesNotCommitProofOrCleanup() = runTest {
        val store = MemoryDeletionRecoveryStore(recovery())
        var cancelled = false
        val phase = DeletedContactRecovery(store, ProtonContactExistenceGateway { _, _ ->
            cancelled = true; GatewayOutcome.Success(RemoteContactPresence.CONFIRMED_ABSENT)
        }, DeletedContactNativeCleanup { _, _ -> error("Cancelled consent cannot execute") })
        assertEquals(SyncPassOutcome.CANCELLED, phase.run(AccountScope("owner")) { cancelled })
        assertFalse(store.verified)
        assertNull(store.blocker)
    }

    private fun recovery() = DeletionRecoveryEntity("owner", "source", "generation", 4, 0,
        "old-remote", "Synthetic draft", DeletionRecoveryState.QUEUED.name, DeletionRecoveryChoice.KEEP_DRAFT.name)
    private fun absent() = ProtonContactExistenceGateway { _, _ -> GatewayOutcome.Success(RemoteContactPresence.CONFIRMED_ABSENT) }
}

private class MemoryDeletionRecoveryStore(private val row: DeletionRecoveryEntity, private val current: Boolean = true) :
    DeletedContactRecoveryExecutionStore {
    var verified = false
    var blocker: DeletionRecoveryBlocker? = null
    override suspend fun discover(account: String) { assertEquals(row.accountId, account) }
    override suspend fun eligible(account: String) = listOf(row)
    override suspend fun verifyAbsence(row: DeletionRecoveryEntity): Boolean = current.also { verified = it }
    override suspend fun block(row: DeletionRecoveryEntity, reason: DeletionRecoveryBlocker) { blocker = reason }
}
