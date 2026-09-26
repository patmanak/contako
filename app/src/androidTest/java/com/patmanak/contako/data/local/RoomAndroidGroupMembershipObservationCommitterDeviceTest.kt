package com.patmanak.contako.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.data.android.AndroidAdoptionState
import com.patmanak.contako.data.android.AndroidIngestionState
import com.patmanak.contako.data.android.AndroidProjectionWriteState
import com.patmanak.contako.data.android.AndroidRawContactLocator
import com.patmanak.contako.data.android.AndroidTombstoneState
import com.patmanak.contako.data.android.RoomAndroidProjectionLedger
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipAvailability
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipLocatorMapping
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipSnapshot
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipSnapshotBinaryCodec
import com.patmanak.contako.data.android.mapping.AndroidGroupSnapshot
import com.patmanak.contako.data.android.mapping.AndroidGroupSnapshotBinaryCodec
import com.patmanak.contako.data.android.mapping.AndroidCanonicalDelta
import com.patmanak.contako.data.android.mapping.CanonicalAndroidContactMapper
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactGroup
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.model.GroupMembership
import com.patmanak.contako.domain.repository.SaveResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomAndroidGroupMembershipObservationCommitterDeviceTest {
    private lateinit var context: Context
    private lateinit var database: ContakoDatabase
    private lateinit var repository: RoomContactRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
        database = ContakoDatabase.create(context, DATABASE_NAME)
        repository = RoomContactRepository(
            database = database,
            clock = { FIXED_NOW },
            elapsedRealtimeClock = { FIXED_NOW },
        )
    }

    @After
    fun tearDown() {
        if (::database.isInitialized && database.isOpen) database.close()
        if (::context.isInitialized) context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun addAndRemoveCommitCanonicalOutboxLedgerAndBaselineAtomically() = runBlocking {
        val fixture = seed(canonicalGroupIds = setOf(GROUP_B))

        val result = committer().commit(fixture.command)

        assertEquals(
            RoomAndroidGroupMembershipObservationResult.Applied(
                RoomAndroidGroupMembershipObservationClassification.CANONICAL_DELTA_COMMITTED,
                changedGroupCount = 2,
                committedAccountRevision = 1,
                committedMembershipLedgerRevision = 1,
            ),
            result,
        )
        val groupA = requireNotNull(database.contactGroupDao().get(ACCOUNT.value, GROUP_A)).toDomain()
        val groupB = requireNotNull(database.contactGroupDao().get(ACCOUNT.value, GROUP_B)).toDomain()
        assertEquals(2L, groupA.revision)
        assertEquals(2L, groupB.revision)
        assertTrue(GroupMembership(CONTACT_ID, EMAIL_ID) in groupA.memberships)
        assertTrue(GroupMembership(CONTACT_ID, EMAIL_ID) !in groupB.memberships)
        assertTrue(GroupMembership(CONTACT_ID, SECONDARY_EMAIL_ID) in groupA.memberships)
        assertTrue(GroupMembership(OTHER_CONTACT_ID, OTHER_EMAIL_ID) in groupB.memberships)
        assertEquals(
            MutationOperation.UPSERT.name,
            requireNotNull(database.outboxDao().get(ACCOUNT.value, AggregateType.GROUP.name, GROUP_A)).operation,
        )
        assertEquals(
            MutationOperation.ASSIGNMENTS.name,
            requireNotNull(database.outboxDao().get(ACCOUNT.value, AggregateType.GROUP.name, GROUP_B)).operation,
        )
        assertEquals(1L, requireNotNull(database.androidProjectionLedgerDao().getAccount(ACCOUNT.value)).revision)
        val membershipLedger = requireNotNull(
            database.androidGroupProjectionDao().getMembership(ACCOUNT.value, CONTACT_ID),
        )
        assertEquals(1L, membershipLedger.revision)
        assertEquals(fixture.observed.semanticFingerprint().sha256Hex, membershipLedger.androidBaselineFingerprint)
        assertEquals(
            fixture.observed,
            AndroidGroupMembershipSnapshotBinaryCodec.decode(
                requireNotNull(
                    database.androidGroupProjectionDao().getMembershipBaseline(ACCOUNT.value, CONTACT_ID),
                ).encodedSnapshot,
            ),
        )
    }

    @Test
    fun unifiedContactAndMembershipCommitRollsBackContactWhenMembershipIsStale() = runBlocking {
        val fixture = seed(canonicalGroupIds = setOf(GROUP_B))
        val before = requireNotNull(database.contactDao().get(ACCOUNT.value, CONTACT_ID)).toDomain()
        val edited = before.copy(displayName = "Edited together")
        val mapper = CanonicalAndroidContactMapper()
        val observed = mapper.project(edited)
        val delta = AndroidCanonicalDelta(
            contact = edited,
            observedFingerprint = mapper.fingerprint(observed),
            resultingCanonicalFingerprint = mapper.fingerprint(observed),
            changedValueIds = setOf("display-name"),
        )
        val contactLedger = requireNotNull(
            RoomAndroidProjectionLedger(database).load(ACCOUNT, CONTACT_ID),
        )
        val unified = RoomAndroidUnifiedObservationCommitter(
            database,
            RoomAndroidObservationCommitter(
                ACCOUNT,
                RoomAndroidProjectionLedger(database),
                RoomAndroidCanonicalMutationStore(database, ACCOUNT, repository),
            ),
            RoomAndroidGroupMembershipObservationCommitter(database, repository),
        )

        val result = unified.commit(
            RoomAndroidUnifiedObservationCommand(
                contactLedger = contactLedger,
                locator = LOCATOR,
                rawContactVersion = 7,
                expectedCanonicalContactRevision = before.revision,
                contactDelta = delta,
                contactSnapshot = observed,
                membership = fixture.command.copy(expectedMembershipLedgerRevision = 99),
            ),
        )

        assertEquals(RoomAndroidUnifiedObservationResult.ReplanRequired, result)
        assertEquals(before, requireNotNull(database.contactDao().get(ACCOUNT.value, CONTACT_ID)).toDomain())
        assertEquals(contactLedger, RoomAndroidProjectionLedger(database).load(ACCOUNT, CONTACT_ID))
        val contactOutbox = database.outboxDao().get(ACCOUNT.value, AggregateType.CONTACT.name, CONTACT_ID)
        assertEquals(before.pendingMutationRevision, contactOutbox?.revision)
    }

    @Test
    fun unifiedContactAndMembershipCommitAppliesBothUnderOneAccountClaim() = runBlocking {
        val fixture = seed(canonicalGroupIds = setOf(GROUP_B))
        val before = requireNotNull(database.contactDao().get(ACCOUNT.value, CONTACT_ID)).toDomain()
        val edited = before.copy(displayName = "Edited together")
        val mapper = CanonicalAndroidContactMapper()
        val observed = mapper.project(edited)
        val delta = AndroidCanonicalDelta(
            contact = edited,
            observedFingerprint = mapper.fingerprint(observed),
            resultingCanonicalFingerprint = mapper.fingerprint(observed),
            changedValueIds = setOf("display-name"),
        )
        val contactLedger = requireNotNull(RoomAndroidProjectionLedger(database).load(ACCOUNT, CONTACT_ID))
        val result = RoomAndroidUnifiedObservationCommitter(
            database,
            RoomAndroidObservationCommitter(
                ACCOUNT,
                RoomAndroidProjectionLedger(database),
                RoomAndroidCanonicalMutationStore(database, ACCOUNT, repository),
            ),
            RoomAndroidGroupMembershipObservationCommitter(database, repository),
        ).commit(
            RoomAndroidUnifiedObservationCommand(
                contactLedger,
                LOCATOR,
                7,
                before.revision,
                delta,
                observed,
                fixture.command,
            ),
        )

        assertTrue(result is RoomAndroidUnifiedObservationResult.Applied)
        assertEquals("Edited together", requireNotNull(database.contactDao().get(ACCOUNT.value, CONTACT_ID)).contact.displayName)
        assertEquals(1L, requireNotNull(database.androidProjectionLedgerDao().getAccount(ACCOUNT.value)).revision)
        assertTrue(GroupMembership(CONTACT_ID, EMAIL_ID) in
            requireNotNull(database.contactGroupDao().get(ACCOUNT.value, GROUP_A)).toDomain().memberships)
    }

    @Test
    fun nativeNoteWithUnchangedMembershipsAcceptsOwnGroupRevisionsButRejectsAStalePlan() = runBlocking {
        for (concurrentGroupEdit in listOf(false, true)) {
            if (concurrentGroupEdit) resetDatabase()
            val fixture = seed(canonicalGroupIds = setOf(GROUP_A), previousGroupId = GROUP_A)
            val before = requireNotNull(database.contactDao().get(ACCOUNT.value, CONTACT_ID)).toDomain()
            val groupsBefore = database.contactGroupDao().getAll(ACCOUNT.value).associateBy { it.group.id }
            val edited = before.copy(values = before.values + ContactValue(
                id = "native-note", kind = ContactValueKind.NOTE, value = "Native note\nSecond line", order = 2,
            ))
            val mapper = CanonicalAndroidContactMapper()
            val observed = mapper.project(edited)
            val ledgerBefore = requireNotNull(RoomAndroidProjectionLedger(database).load(ACCOUNT, CONTACT_ID))
            val command = RoomAndroidUnifiedObservationCommand(
                ledgerBefore, LOCATOR, 7, before.revision,
                AndroidCanonicalDelta(edited, mapper.fingerprint(observed), mapper.fingerprint(observed),
                    changedValueIds = setOf("native-note")),
                observed, fixture.command,
            )
            if (concurrentGroupEdit) {
                repository.saveGroup(groupsBefore.getValue(GROUP_A).toDomain().copy(name = "Concurrent rename"))
                    .requireSaved()
            }
            val result = RoomAndroidUnifiedObservationCommitter(
                database,
                RoomAndroidObservationCommitter(ACCOUNT, RoomAndroidProjectionLedger(database),
                    RoomAndroidCanonicalMutationStore(database, ACCOUNT, repository)),
                committer(),
            ).commit(command)
            if (concurrentGroupEdit) {
                assertEquals(RoomAndroidUnifiedObservationResult.ReplanRequired, result)
                assertEquals(before, requireNotNull(database.contactDao().get(ACCOUNT.value, CONTACT_ID)).toDomain())
                assertEquals(ledgerBefore, RoomAndroidProjectionLedger(database).load(ACCOUNT, CONTACT_ID))
                assertEquals("Concurrent rename", requireNotNull(database.contactGroupDao().get(ACCOUNT.value, GROUP_A)).group.name)
            } else {
                assertTrue(result is RoomAndroidUnifiedObservationResult.Applied)
                assertEquals("Native note\nSecond line", requireNotNull(database.contactDao().get(ACCOUNT.value, CONTACT_ID))
                    .toDomain().values.single { it.id == "native-note" }.value)
                for (stored in database.contactGroupDao().getAll(ACCOUNT.value)) {
                    assertEquals(groupsBefore.getValue(stored.group.id).toDomain().memberships, stored.toDomain().memberships)
                }
                val changedGroup = requireNotNull(database.contactGroupDao().get(ACCOUNT.value, GROUP_A)).group
                assertEquals(groupsBefore.getValue(GROUP_A).group.revision + 1, changedGroup.revision)
                assertEquals(changedGroup.revision,
                    requireNotNull(database.outboxDao().get(ACCOUNT.value, AggregateType.GROUP.name, GROUP_A)).revision)
            }
        }
    }

    @Test
    fun providerCascadeCanRemoveMembershipAfterCanonicalGroupTombstone() = runBlocking {
        val fixture = seed(canonicalGroupIds = setOf(GROUP_B))
        repository.deleteGroup(ACCOUNT.value, GROUP_B)
        val groupDao = groupDao()
        val deletedLedger = requireNotNull(groupDao.getGroup(ACCOUNT.value, GROUP_B))
        check(
            groupDao.updateGroup(
                deletedLedger.copy(
                    revision = 1,
                    projectionState = AndroidProjectionWriteState.DETACHED.name,
                    tombstoneState = AndroidTombstoneState.CANONICAL_COMMITTED.name,
                ),
            ) == 1,
        )
        val command = fixture.command.copy(
            expectedCanonicalGroupStates = database.contactGroupDao().getAll(ACCOUNT.value).map { stored ->
                RoomExpectedContactGroupState(stored.group.id, stored.group.revision, stored.group.isDeleted)
            },
            expectedAndroidGroupBindings = fixture.command.expectedAndroidGroupBindings.map { binding ->
                if (binding.canonicalGroupId == GROUP_B) binding.copy(expectedLedgerRevision = 1) else binding
            },
        )

        val result = committer().commit(command)

        assertTrue(result is RoomAndroidGroupMembershipObservationResult.Applied)
        val baseline = AndroidGroupMembershipSnapshotBinaryCodec.decode(
            requireNotNull(groupDao.getMembershipBaseline(ACCOUNT.value, CONTACT_ID)).encodedSnapshot,
        )
        assertEquals(listOf(GROUP_A), baseline.canonicalGroupIds)
        assertTrue(requireNotNull(database.contactGroupDao().get(ACCOUNT.value, GROUP_B)).group.isDeleted)
    }

    @Test
    fun membershipAccountCasWaitsForTheSharedProviderMutationLock() = runBlocking {
        val fixture = seed(canonicalGroupIds = setOf(GROUP_B))
        coroutineScope {
            lateinit var pending: kotlinx.coroutines.Deferred<RoomAndroidGroupMembershipObservationResult>
            AndroidProviderAccountMutationLocks.withAccountLock(ACCOUNT.value) {
                pending = async { committer().commit(fixture.command) }
                repeat(10) { yield() }
                assertFalse(pending.isCompleted)
            }
            assertTrue(pending.await() is RoomAndroidGroupMembershipObservationResult.Applied)
        }
    }

    @Test
    fun selfWritePendingFingerprintReconcilesWithoutCanonicalEcho() = runBlocking {
        val fixture = seed(canonicalGroupIds = setOf(GROUP_A), pendingObservedFingerprint = true)
        val beforeGroups = database.contactGroupDao().getAll(ACCOUNT.value)
        val beforeOutbox = database.outboxDao().getAll(ACCOUNT.value)

        val result = committer().commit(fixture.command)

        assertEquals(
            RoomAndroidGroupMembershipObservationResult.Applied(
                RoomAndroidGroupMembershipObservationClassification.SELF_WRITE_RECONCILED,
                changedGroupCount = 0,
                committedAccountRevision = 1,
                committedMembershipLedgerRevision = 1,
            ),
            result,
        )
        assertEquals(beforeGroups, database.contactGroupDao().getAll(ACCOUNT.value))
        assertEquals(beforeOutbox, database.outboxDao().getAll(ACCOUNT.value))
        val membershipLedger = requireNotNull(
            database.androidGroupProjectionDao().getMembership(ACCOUNT.value, CONTACT_ID),
        )
        assertEquals(null, membershipLedger.pendingProjectionFingerprint)
        assertEquals(AndroidProjectionWriteState.CLEAN.name, membershipLedger.projectionState)
    }

    @Test
    fun pendingWithoutBaselineWinsOverInitialBaselining() = runBlocking {
        val fixture = seed(
            canonicalGroupIds = setOf(GROUP_A),
            pendingObservedFingerprint = true,
            baselinePresent = false,
        )

        assertEquals(
            RoomAndroidGroupMembershipObservationResult.Applied(
                RoomAndroidGroupMembershipObservationClassification.SELF_WRITE_RECONCILED,
                changedGroupCount = 0,
                committedAccountRevision = 1,
                committedMembershipLedgerRevision = 1,
            ),
            committer().commit(fixture.command),
        )
    }

    @Test
    fun firstMatchingObservationEstablishesABaselineWithoutCanonicalMutation() = runBlocking {
        val fixture = seed(canonicalGroupIds = setOf(GROUP_A), baselinePresent = false)

        assertEquals(
            RoomAndroidGroupMembershipObservationResult.Applied(
                RoomAndroidGroupMembershipObservationClassification.BASELINED,
                changedGroupCount = 0,
                committedAccountRevision = 1,
                committedMembershipLedgerRevision = 1,
            ),
            committer().commit(fixture.command),
        )
    }

    @Test
    fun exactRepeatedObservationIsAnExplicitNoChange() = runBlocking {
        val fixture = seed(canonicalGroupIds = setOf(GROUP_A), previousGroupId = GROUP_A)

        assertEquals(
            RoomAndroidGroupMembershipObservationResult.Applied(
                RoomAndroidGroupMembershipObservationClassification.NO_CHANGE,
                changedGroupCount = 0,
                committedAccountRevision = 1,
                committedMembershipLedgerRevision = 1,
            ),
            committer().commit(fixture.command),
        )
    }

    @Test
    fun observationAlreadyCanonicalIsNotReportedAsACanonicalDelta() = runBlocking {
        val fixture = seed(canonicalGroupIds = setOf(GROUP_A))

        assertEquals(
            RoomAndroidGroupMembershipObservationResult.Applied(
                RoomAndroidGroupMembershipObservationClassification.CANONICAL_ALREADY_CONVERGED,
                changedGroupCount = 0,
                committedAccountRevision = 1,
                committedMembershipLedgerRevision = 1,
            ),
            committer().commit(fixture.command),
        )
        val ledger = requireNotNull(groupDao().getMembership(ACCOUNT.value, CONTACT_ID))
        assertEquals(AndroidIngestionState.BASELINED.name, ledger.ingestionState)
    }

    @Test
    fun staleGroupLedgerOrCorruptedBaselineFailsBeforeTheAccountClaim() = runBlocking {
        val staleFixture = seed(canonicalGroupIds = setOf(GROUP_B))
        val groupDao = database.androidGroupProjectionDao()
        val staleGroup = requireNotNull(groupDao.getGroup(ACCOUNT.value, GROUP_A))
        groupDao.updateGroup(staleGroup.copy(revision = 1))

        assertEquals(
            RoomAndroidGroupMembershipObservationResult.StaleGroupLedger,
            committer().commit(staleFixture.command),
        )
        assertEquals(0L, requireNotNull(database.androidProjectionLedgerDao().getAccount(ACCOUNT.value)).revision)

        resetDatabase()
        val corruptFixture = seed(canonicalGroupIds = setOf(GROUP_B))
        val baseline = requireNotNull(groupDao().getMembershipBaseline(ACCOUNT.value, CONTACT_ID))
        groupDao().upsertMembershipBaseline(
            baseline.copy(encodedSnapshot = baseline.encodedSnapshot.copyOf().also { it[it.lastIndex] = 0 }),
        )
        assertEquals(
            RoomAndroidGroupMembershipObservationResult.RepairRequired,
            committer().commit(corruptFixture.command),
        )
        assertEquals(0L, requireNotNull(database.androidProjectionLedgerDao().getAccount(ACCOUNT.value)).revision)
    }

    @Test
    fun unknownBaselineVersionWithMatchingIntegrityRequiresRepairInsteadOfCrashing() = runBlocking {
        val fixture = seed(canonicalGroupIds = setOf(GROUP_B))
        val baseline = requireNotNull(groupDao().getMembershipBaseline(ACCOUNT.value, CONTACT_ID))
        val unsupported = baseline.encodedSnapshot.copyOf().also { encoded -> encoded[7] = 2 }
        groupDao().upsertMembershipBaseline(
            baseline.copy(
                fingerprint = AndroidGroupMembershipSnapshotBinaryCodec
                    .integrityFingerprint(unsupported).sha256Hex,
                encodedSnapshot = unsupported,
            ),
        )

        assertEquals(
            RoomAndroidGroupMembershipObservationResult.RepairRequired,
            committer().commit(fixture.command),
        )
        assertEquals(0L, requireNotNull(database.androidProjectionLedgerDao().getAccount(ACCOUNT.value)).revision)
    }

    @Test
    fun oversizedDurableMembershipOrGroupBaselineRequiresRepair() = runBlocking {
        val membershipFixture = seed(canonicalGroupIds = setOf(GROUP_B))
        groupDao().upsertMembershipBaseline(
            AndroidGroupMembershipBaselineEntity(
                accountId = ACCOUNT.value,
                canonicalContactId = CONTACT_ID,
                fingerprint = "0".repeat(64),
                encodedSnapshot = ByteArray(3 * 1_024 * 1_024 + 1),
            ),
        )
        assertEquals(
            RoomAndroidGroupMembershipObservationResult.RepairRequired,
            committer().commit(membershipFixture.command),
        )

        resetDatabase()
        val groupFixture = seed(canonicalGroupIds = setOf(GROUP_B))
        groupDao().upsertGroupBaseline(
            AndroidGroupProjectionBaselineEntity(
                accountId = ACCOUNT.value,
                canonicalGroupId = GROUP_A,
                fingerprint = "0".repeat(64),
                encodedSnapshot = ByteArray(32 * 1_024 + 1),
            ),
        )
        assertEquals(
            RoomAndroidGroupMembershipObservationResult.RepairRequired,
            committer().commit(groupFixture.command),
        )
    }

    @Test
    fun lostAcknowledgementReplayRecognizesTheExactCommittedPostState() = runBlocking {
        val fixture = seed(canonicalGroupIds = setOf(GROUP_B))
        assertTrue(committer().commit(fixture.command) is RoomAndroidGroupMembershipObservationResult.Applied)
        val groupsAfterCommit = database.contactGroupDao().getAll(ACCOUNT.value)
        val outboxAfterCommit = database.outboxDao().getAll(ACCOUNT.value)
        val ledgerAfterCommit = groupDao().getMembership(ACCOUNT.value, CONTACT_ID)

        assertEquals(
            RoomAndroidGroupMembershipObservationResult.AlreadyCommitted(1, 1),
            committer().commit(fixture.command),
        )
        assertEquals(groupsAfterCommit, database.contactGroupDao().getAll(ACCOUNT.value))
        assertEquals(outboxAfterCommit, database.outboxDao().getAll(ACCOUNT.value))
        assertEquals(ledgerAfterCommit, groupDao().getMembership(ACCOUNT.value, CONTACT_ID))
        assertEquals(1L, requireNotNull(database.androidProjectionLedgerDao().getAccount(ACCOUNT.value)).revision)

        val incompleteReplay = fixture.command.copy(
            expectedAndroidGroupBindings = fixture.command.expectedAndroidGroupBindings
                .filter { it.canonicalGroupId == GROUP_A },
        )
        assertEquals(
            RoomAndroidGroupMembershipObservationResult.StaleAccount,
            committer().commit(incompleteReplay),
        )
        val receipt = requireNotNull(groupDao().getMembershipCommitReceipt(ACCOUNT.value, CONTACT_ID))
        groupDao().upsertMembershipCommitReceipt(receipt.copy(commandFingerprint = "b".repeat(64)))
        assertEquals(
            RoomAndroidGroupMembershipObservationResult.StaleAccount,
            committer().commit(fixture.command),
        )
        groupDao().upsertMembershipCommitReceipt(receipt)
        val groupBOutbox = requireNotNull(
            database.outboxDao().get(ACCOUNT.value, AggregateType.GROUP.name, GROUP_B),
        )
        database.outboxDao().upsert(groupBOutbox.copy(remoteVersion = "tampered-version"))
        assertEquals(
            RoomAndroidGroupMembershipObservationResult.StaleAccount,
            committer().commit(fixture.command),
        )
    }

    @Test
    fun receiptPreservesPreviousOnlyBindingWhenNoCanonicalGroupRevisionAdvances() = runBlocking {
        val fixture = seed(
            canonicalGroupIds = setOf(GROUP_A),
            pendingObservedFingerprint = true,
            previousGroupId = GROUP_B,
        )
        assertEquals(
            RoomAndroidGroupMembershipObservationResult.Applied(
                RoomAndroidGroupMembershipObservationClassification.SELF_WRITE_RECONCILED,
                changedGroupCount = 0,
                committedAccountRevision = 1,
                committedMembershipLedgerRevision = 1,
            ),
            committer().commit(fixture.command),
        )
        assertEquals(
            RoomAndroidGroupMembershipObservationResult.AlreadyCommitted(1, 1),
            committer().commit(fixture.command),
        )
        assertEquals(
            RoomAndroidGroupMembershipObservationResult.StaleAccount,
            committer().commit(
                fixture.command.copy(
                    expectedAndroidGroupBindings = fixture.command.expectedAndroidGroupBindings
                        .filter { it.canonicalGroupId == GROUP_A },
                ),
            ),
        )
    }

    @Test
    fun malformedDurableReceiptRequiresRepairInsteadOfCrashing() = runBlocking {
        val invalidHashFixture = seed(canonicalGroupIds = setOf(GROUP_B))
        assertTrue(committer().commit(invalidHashFixture.command) is RoomAndroidGroupMembershipObservationResult.Applied)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE android_group_membership_commit_receipts SET command_fingerprint = 'x'",
        )
        assertEquals(
            RoomAndroidGroupMembershipObservationResult.RepairRequired,
            committer().commit(invalidHashFixture.command),
        )

        resetDatabase()
        val invalidRevisionFixture = seed(canonicalGroupIds = setOf(GROUP_B))
        assertTrue(
            committer().commit(invalidRevisionFixture.command) is
                RoomAndroidGroupMembershipObservationResult.Applied,
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE android_group_membership_commit_receipts SET expected_account_revision = -1",
        )
        assertEquals(
            RoomAndroidGroupMembershipObservationResult.RepairRequired,
            committer().commit(invalidRevisionFixture.command),
        )
    }

    @Test
    fun mutableCallerVectorsAreFrozenBeforeTheFirstSuspension() = runBlocking {
        val fixture = seed(canonicalGroupIds = setOf(GROUP_B))
        val mutableStates = fixture.command.expectedCanonicalGroupStates.toMutableList()
        val mutableBindings = fixture.command.expectedAndroidGroupBindings.toMutableList()
        val replay = fixture.command.copy(
            expectedCanonicalGroupStates = mutableStates.toList(),
            expectedAndroidGroupBindings = mutableBindings.toList(),
        )
        val mutableCommand = fixture.command.copy(
            expectedCanonicalGroupStates = mutableStates,
            expectedAndroidGroupBindings = mutableBindings,
        )
        val result = RoomAndroidGroupMembershipObservationCommitter(
            database,
            repository,
            RoomAndroidGroupMembershipCommitCheckpointHook { checkpoint ->
                if (checkpoint == RoomAndroidGroupMembershipCommitCheckpoint.AFTER_ACCOUNT_CAS) {
                    mutableStates.clear()
                    mutableBindings.clear()
                }
            },
        ).commit(mutableCommand)

        assertTrue(result is RoomAndroidGroupMembershipObservationResult.Applied)
        assertTrue(mutableStates.isEmpty())
        assertTrue(mutableBindings.isEmpty())
        assertEquals(
            RoomAndroidGroupMembershipObservationResult.AlreadyCommitted(1, 1),
            committer().commit(replay),
        )
    }

    @Test
    fun providerEpochAdvanceInvalidatesTheCommitReceipt() = runBlocking {
        val fixture = seed(canonicalGroupIds = setOf(GROUP_B))
        assertTrue(committer().commit(fixture.command) is RoomAndroidGroupMembershipObservationResult.Applied)
        assertNotNull(groupDao().getMembershipCommitReceipt(ACCOUNT.value, CONTACT_ID))

        RoomAndroidProjectionLedger(database).advanceProviderEpoch(ACCOUNT, expectedAccountRevision = 1)

        assertEquals(null, groupDao().getMembershipCommitReceipt(ACCOUNT.value, CONTACT_ID))
    }

    @Test
    fun everyInjectedTransactionCheckpointRollsBackEveryCasAndWrite() = runBlocking {
        RoomAndroidGroupMembershipCommitCheckpoint.entries.forEachIndexed { index, injectedCheckpoint ->
            if (index > 0) resetDatabase()
            val fixture = seed(canonicalGroupIds = setOf(GROUP_B))
            val beforeGroups = database.contactGroupDao().getAll(ACCOUNT.value)
            val beforeOutbox = database.outboxDao().getAll(ACCOUNT.value)
            val beforeMembership = groupDao().getMembership(ACCOUNT.value, CONTACT_ID)
            val beforeBaseline = groupDao().getMembershipBaseline(ACCOUNT.value, CONTACT_ID)
            val beforeReceipt = groupDao().getMembershipCommitReceipt(ACCOUNT.value, CONTACT_ID)
            val failing = RoomAndroidGroupMembershipObservationCommitter(
                database,
                repository,
                RoomAndroidGroupMembershipCommitCheckpointHook { checkpoint ->
                    if (checkpoint == injectedCheckpoint) error("INJECTED_PROCESS_DEATH")
                },
            )

            assertNotNull(runCatching { failing.commit(fixture.command) }.exceptionOrNull())
            assertEquals(0L, requireNotNull(database.androidProjectionLedgerDao().getAccount(ACCOUNT.value)).revision)
            assertEquals(beforeGroups, database.contactGroupDao().getAll(ACCOUNT.value))
            assertEquals(beforeOutbox, database.outboxDao().getAll(ACCOUNT.value))
            assertEquals(beforeMembership, groupDao().getMembership(ACCOUNT.value, CONTACT_ID))
            val afterBaseline = groupDao().getMembershipBaseline(ACCOUNT.value, CONTACT_ID)
            assertEquals(beforeReceipt, groupDao().getMembershipCommitReceipt(ACCOUNT.value, CONTACT_ID))
            assertEquals(beforeBaseline?.fingerprint, afterBaseline?.fingerprint)
            assertTrue(
                requireNotNull(beforeBaseline).encodedSnapshot.contentEquals(
                    requireNotNull(afterBaseline).encodedSnapshot,
                ),
            )
        }
    }

    private suspend fun seed(
        canonicalGroupIds: Set<String>,
        pendingObservedFingerprint: Boolean = false,
        baselinePresent: Boolean = true,
        previousGroupId: String = GROUP_B,
    ): Fixture {
        repository.saveContact(contact(CONTACT_ID, EMAIL_ID, SECONDARY_EMAIL_ID)).requireSaved()
        repository.saveContact(contact(OTHER_CONTACT_ID, OTHER_EMAIL_ID)).requireSaved()
        repository.saveGroup(
            group(
                GROUP_A,
                buildList {
                    add(GroupMembership(CONTACT_ID, SECONDARY_EMAIL_ID))
                    if (GROUP_A in canonicalGroupIds) add(GroupMembership(CONTACT_ID, EMAIL_ID))
                },
            ),
        ).requireSaved()
        repository.saveGroup(
            group(
                GROUP_B,
                buildList {
                    add(GroupMembership(OTHER_CONTACT_ID, OTHER_EMAIL_ID))
                    if (GROUP_B in canonicalGroupIds) add(GroupMembership(CONTACT_ID, EMAIL_ID))
                },
            ),
        ).requireSaved()
        forceAssignmentIntent(GROUP_B)

        val contactLedgerService = RoomAndroidProjectionLedger(database)
        contactLedgerService.ensureAccount(ACCOUNT)
        val attached = contactLedgerService.attachCanonicalContact(ACCOUNT, CONTACT_ID, CONTACT_SOURCE)
        val adopted = contactLedgerService.acknowledgeAdoption(
            ACCOUNT,
            CONTACT_ID,
            attached.revision,
            LOCATOR,
        ).let { result ->
            (result as com.patmanak.contako.data.android.AndroidLedgerCasResult.Updated).snapshot
        }
        val previous = membershipSnapshot(previousGroupId)
        val observed = membershipSnapshot(GROUP_A)
        val previousEncoded = AndroidGroupMembershipSnapshotBinaryCodec.encode(previous)
        val groupDao = groupDao()
        listOf(GROUP_A to GROUP_A_ROW, GROUP_B to GROUP_B_ROW).forEach { (groupId, row) ->
            val groupSnapshot = AndroidGroupSnapshot(ACCOUNT.value, groupId, "Synthetic", true)
            assertTrue(
                groupDao.insertGroup(
                    AndroidGroupProjectionLedgerEntity(
                        accountId = ACCOUNT.value,
                        canonicalGroupId = groupId,
                        revision = 0,
                        providerEpoch = LOCATOR.providerEpoch,
                        groupRowLocator = row,
                        providerVersion = row,
                        sourceIdentity = "source-$groupId",
                        canonicalProjectionFingerprint = groupSnapshot.semanticFingerprint().sha256Hex,
                        androidBaselineFingerprint = groupSnapshot.semanticFingerprint().sha256Hex,
                        pendingProjectionFingerprint = null,
                        projectionState = AndroidProjectionWriteState.CLEAN.name,
                        ingestionState = AndroidIngestionState.BASELINED.name,
                        tombstoneState = AndroidTombstoneState.NONE.name,
                        adoptionState = AndroidAdoptionState.ADOPTED.name,
                    ),
                ) != -1L,
            )
            val encodedGroup = AndroidGroupSnapshotBinaryCodec.encode(groupSnapshot)
            groupDao.upsertGroupBaseline(
                AndroidGroupProjectionBaselineEntity(
                    accountId = ACCOUNT.value,
                    canonicalGroupId = groupId,
                    fingerprint = AndroidGroupSnapshotBinaryCodec.integrityFingerprint(encodedGroup).sha256Hex,
                    encodedSnapshot = encodedGroup,
                ),
            )
        }
        assertTrue(
            groupDao.insertMembership(
                AndroidGroupMembershipProjectionLedgerEntity(
                    accountId = ACCOUNT.value,
                    canonicalContactId = CONTACT_ID,
                    revision = 0,
                    providerEpoch = LOCATOR.providerEpoch,
                    rawContactLocator = LOCATOR.localRowHandle,
                    preferredEmailValueId = EMAIL_ID,
                    canonicalProjectionFingerprint = if (baselinePresent) {
                        previous.semanticFingerprint().sha256Hex
                    } else {
                        observed.semanticFingerprint().sha256Hex
                    },
                    androidBaselineFingerprint = if (baselinePresent) {
                        previous.semanticFingerprint().sha256Hex
                    } else {
                        null
                    },
                    pendingProjectionFingerprint = if (pendingObservedFingerprint) {
                        observed.semanticFingerprint().sha256Hex
                    } else {
                        null
                    },
                    projectionState = if (pendingObservedFingerprint) {
                        AndroidProjectionWriteState.WRITE_PENDING.name
                    } else {
                        AndroidProjectionWriteState.CLEAN.name
                    },
                    ingestionState = AndroidIngestionState.BASELINED.name,
                ),
            ) != -1L,
        )
        if (baselinePresent) {
            groupDao.upsertMembershipBaseline(
                AndroidGroupMembershipBaselineEntity(
                    accountId = ACCOUNT.value,
                    canonicalContactId = CONTACT_ID,
                    fingerprint = AndroidGroupMembershipSnapshotBinaryCodec
                        .integrityFingerprint(previousEncoded).sha256Hex,
                    encodedSnapshot = previousEncoded,
                ),
            )
        }
        val expectedGroupStates = database.contactGroupDao().getAll(ACCOUNT.value).map { stored ->
            RoomExpectedContactGroupState(stored.group.id, stored.group.revision, stored.group.isDeleted)
        }
        return Fixture(
            observed = observed,
            command = RoomAndroidGroupMembershipObservationCommand(
                account = ACCOUNT,
                expectedAccountRevision = 0,
                locator = LOCATOR,
                canonicalContactId = CONTACT_ID,
                expectedCanonicalContactRevision = 1,
                expectedContactLedgerRevision = adopted.revision,
                expectedMembershipLedgerRevision = 0,
                expectedCanonicalGroupStates = expectedGroupStates,
                expectedAndroidGroupBindings = buildSet {
                    add(GROUP_A)
                    if (baselinePresent) add(previousGroupId)
                }.sorted().map { groupId ->
                    val row = if (groupId == GROUP_A) GROUP_A_ROW else GROUP_B_ROW
                    RoomExpectedAndroidGroupBinding(groupId, 0, row, row, "source-$groupId")
                },
                observedSnapshot = observed,
            ),
        )
    }

    private suspend fun forceAssignmentIntent(groupId: String) {
        val current = requireNotNull(database.outboxDao().get(ACCOUNT.value, AggregateType.GROUP.name, groupId))
        database.outboxDao().upsert(
            current.copy(
                operation = MutationOperation.ASSIGNMENTS.name,
                idempotencyKey = "assignment-intent",
            ),
        )
    }

    private fun membershipSnapshot(groupId: String): AndroidGroupMembershipSnapshot {
        val groupRow = if (groupId == GROUP_A) GROUP_A_ROW else GROUP_B_ROW
        val dataRow = if (groupId == GROUP_A) GROUP_A_DATA else GROUP_B_DATA
        return AndroidGroupMembershipSnapshot.create(
            accountId = ACCOUNT.value,
            canonicalContactId = CONTACT_ID,
            preferredEmailValueId = EMAIL_ID,
            membershipAvailability = AndroidGroupMembershipAvailability.AVAILABLE,
            locatorMappings = listOf(AndroidGroupMembershipLocatorMapping(groupId, groupRow, dataRow)),
        )
    }

    private fun contact(id: String, vararg emailIds: String) = CanonicalContact(
        accountId = ACCOUNT.value,
        id = id,
        displayName = "Synthetic",
        values = emailIds.mapIndexed { index, valueId ->
            ContactValue(
                id = valueId,
                kind = ContactValueKind.EMAIL,
                value = "$valueId@example.invalid",
                order = index,
                isPrimary = index == 0,
            )
        },
    )

    private fun group(id: String, memberships: List<GroupMembership>) = ContactGroup(
        accountId = ACCOUNT.value,
        id = id,
        name = "Synthetic",
        remoteLabelId = "remote-$id",
        memberships = memberships,
    )

    private fun committer() = RoomAndroidGroupMembershipObservationCommitter(database, repository)
    private fun groupDao() = database.androidGroupProjectionDao()

    private fun resetDatabase() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
        database = ContakoDatabase.create(context, DATABASE_NAME)
        repository = RoomContactRepository(
            database = database,
            clock = { FIXED_NOW },
            elapsedRealtimeClock = { FIXED_NOW },
        )
    }

    private fun <T> SaveResult<T>.requireSaved(): T {
        check(this is SaveResult.Saved)
        return value
    }

    private data class Fixture(
        val observed: AndroidGroupMembershipSnapshot,
        val command: RoomAndroidGroupMembershipObservationCommand,
    )

    private companion object {
        const val DATABASE_NAME = "android-group-membership-observation-committer.db"
        val ACCOUNT = AccountScope("account")
        const val CONTACT_ID = "contact"
        const val OTHER_CONTACT_ID = "other-contact"
        const val EMAIL_ID = "preferred-email"
        const val SECONDARY_EMAIL_ID = "secondary-email"
        const val OTHER_EMAIL_ID = "other-email"
        const val CONTACT_SOURCE = "contact-source"
        const val GROUP_A = "group-a"
        const val GROUP_B = "group-b"
        const val GROUP_A_ROW = 301L
        const val GROUP_B_ROW = 302L
        const val GROUP_A_DATA = 401L
        const val GROUP_B_DATA = 402L
        const val FIXED_NOW = 9_999L
        val LOCATOR = AndroidRawContactLocator(0, 201)
    }
}
