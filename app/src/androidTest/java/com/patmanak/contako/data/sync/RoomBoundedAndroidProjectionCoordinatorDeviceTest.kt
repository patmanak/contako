package com.patmanak.contako.data.sync

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.data.android.AndroidAdoptionState
import com.patmanak.contako.data.android.AndroidIngestionState
import com.patmanak.contako.data.android.AndroidProjectionWriteState
import com.patmanak.contako.data.android.AndroidTombstoneState
import com.patmanak.contako.data.android.RoomAndroidProjectionLedger
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipAvailability
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipSnapshot
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipSnapshotBinaryCodec
import com.patmanak.contako.data.android.mapping.CanonicalAndroidContactMapper
import com.patmanak.contako.data.android.provider.productionAndroidProjectionCoordinator
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.local.AndroidProjectionLedgerEntity
import com.patmanak.contako.data.local.AndroidGroupMembershipBaselineEntity
import com.patmanak.contako.data.local.AndroidGroupMembershipProjectionLedgerEntity
import com.patmanak.contako.data.local.RoomContactRepository
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.data.local.toEntity
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomBoundedAndroidProjectionCoordinatorDeviceTest {
    private lateinit var context: Context
    private lateinit var database: ContakoDatabase

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DB)
        database = ContakoDatabase.create(context, DB)
    }

    @After fun tearDown() {
        if (database.isOpen) database.close()
        context.deleteDatabase(DB)
    }

    @Test fun cleanFlagWithoutCurrentFingerprintsDoesNotSuppressProjection() = runBlocking {
        seed(clean = true)
        var writes = 0
        val coordinator = RoomBoundedAndroidProjectionCoordinator(database, { _, _ ->
            writes++
            AndroidBoundedPageResult.Applied
        })

        val result = coordinator.projectPage(CONTEXT, null)

        assertEquals(AndroidBoundedPageResult.Applied, result.second)
        assertEquals(1, writes)
    }

    @Test fun acknowledgedContactsWithoutLedgersJoinBoundedTraversalWithoutReplacingExistingOwnership() = runBlocking {
        seed(clean = false)
        val existing = database.androidProjectionLedgerDao().get(ACCOUNT.value, CONTACT)
        listOf("missing-a", "missing-b", "missing-c").forEach { id ->
            database.contactDao().upsert(CanonicalContact(ACCOUNT.value, id, remoteContactId = "remote-$id").toEntity())
        }
        database.contactDao().upsert(CanonicalContact(ACCOUNT.value, "local-only").toEntity())
        database.contactDao().upsert(CanonicalContact(ACCOUNT.value, "deleted", remoteContactId = "remote-deleted", isDeleted = true).toEntity())
        database.contactDao().upsert(CanonicalContact("other-account", "foreign", remoteContactId = "remote-foreign").toEntity())
        val visited = mutableSetOf<String>()
        val coordinator = RoomBoundedAndroidProjectionCoordinator(database, { _, entry ->
            visited += entry.canonicalContactId
            AndroidBoundedPageResult.Applied
        }, pageSize = 2)
        assertEquals(AndroidBoundedPageResult.ReplanRequired,
            coordinator.projectPage(CONTEXT.copy(accountRevision = 9), null).second)
        assertEquals(1, database.androidProjectionLedgerDao().getAll(ACCOUNT.value).size)
        var after: String? = null
        do {
            val (page, outcome) = coordinator.projectPage(CONTEXT, after)
            assertEquals(AndroidBoundedPageResult.Applied, outcome)
            after = page.nextKey
        } while (after != null)
        assertEquals(setOf(CONTACT, "missing-a", "missing-b", "missing-c"), visited)
        assertEquals(existing, database.androidProjectionLedgerDao().get(ACCOUNT.value, CONTACT))
        val recovered = database.androidProjectionLedgerDao().getAll(ACCOUNT.value)
        assertEquals(4, recovered.size)
        recovered.filter { it.canonicalContactId.startsWith("missing-") }.forEach {
            assertEquals(AndroidAdoptionState.SOURCE_ID_PENDING.name, it.adoptionState)
            assertEquals(null, it.rawContactLocator)
        }
        coordinator.projectPage(CONTEXT, null)
        assertEquals(recovered, database.androidProjectionLedgerDao().getAll(ACCOUNT.value))
        assertEquals(0, database.androidProjectionLedgerDao().getAll("other-account").size)
    }

    @Test fun canonicalNicknameEditInvalidatesCleanProjectionWithoutInvalidatingUnchangedPass() = runBlocking {
        val canonical = seedCurrentCleanProjection()
        var writes = 0
        val coordinator = RoomBoundedAndroidProjectionCoordinator(database, { _, _ ->
            writes++
            AndroidBoundedPageResult.Applied
        }, presenceVerifier = AndroidCleanProjectionPresenceVerifier { _, candidates ->
            candidates.map { it.canonicalContactId }.toSet()
        })
        assertEquals(AndroidBoundedPageResult.Applied, coordinator.projectPage(CONTEXT, null).second)
        assertEquals(0, writes)
        val currentLedger = requireNotNull(database.androidProjectionLedgerDao().get(ACCOUNT.value, CONTACT))
        database.androidProjectionLedgerDao().update(currentLedger.copy(
            adoptionState = AndroidAdoptionState.SOURCE_ID_PENDING.name,
        ))
        assertEquals(AndroidBoundedPageResult.Applied, coordinator.projectPage(CONTEXT, null).second)
        assertEquals(1, writes)
        database.androidProjectionLedgerDao().update(currentLedger)
        RoomContactRepository(database).saveContact(canonical.copy(values = listOf(
            ContactValue("nickname", ContactValueKind.NICKNAME, "Changed", order = 0),
        )))
        assertEquals(AndroidBoundedPageResult.Applied, coordinator.projectPage(CONTEXT, null).second)
        assertEquals(2, writes)
    }

    @Test fun missingCleanOwnedCopyDelegatesWithoutChangingTheDurableBinding() = runBlocking {
        seedCurrentCleanProjection()
        val before = database.androidProjectionLedgerDao().get(ACCOUNT.value, CONTACT)
        var calls = 0
        val coordinator = RoomBoundedAndroidProjectionCoordinator(database, { _, _ ->
            calls++
            AndroidBoundedPageResult.RepairRequired
        }) // Missing verifier proof MUST fail closed.
        assertEquals(AndroidBoundedPageResult.PartiallyApplied, coordinator.projectPage(CONTEXT, null).second)
        assertEquals(1, calls)
        assertEquals(before, database.androidProjectionLedgerDao().get(ACCOUNT.value, CONTACT))
    }

    @Test fun convergedDetachedDeletionDoesNotRequireProviderPresence() = runBlocking {
        val canonical = seedCurrentCleanProjection()
        database.contactDao().upsert(canonical.copy(isDeleted = true).toEntity())
        val ledger = requireNotNull(database.androidProjectionLedgerDao().get(ACCOUNT.value, CONTACT))
        database.androidProjectionLedgerDao().update(ledger.copy(
            projectionState = AndroidProjectionWriteState.DETACHED.name,
            tombstoneState = AndroidTombstoneState.REMOTE_CONVERGED.name,
        ))
        val coordinator = RoomBoundedAndroidProjectionCoordinator(database, { _, _ ->
            error("A converged deletion must not project")
        }, presenceVerifier = AndroidCleanProjectionPresenceVerifier { _, _ ->
            error("A converged deletion requires no owned raw row")
        })
        assertEquals(AndroidBoundedPageResult.Applied, coordinator.projectPage(CONTEXT, null).second)
    }

    private suspend fun seedCurrentCleanProjection(): CanonicalContact {
        seed(clean = true)
        val canonical = CanonicalContact(ACCOUNT.value, CONTACT, remoteContactId = "source")
        database.contactDao().upsert(canonical.toEntity())
        val mapper = CanonicalAndroidContactMapper()
        val fingerprint = mapper.fingerprint(mapper.project(canonical)).sha256Hex
        val ledger = requireNotNull(database.androidProjectionLedgerDao().get(ACCOUNT.value, CONTACT))
        database.androidProjectionLedgerDao().update(ledger.copy(
            rawContactLocator = 1, canonicalProjectionFingerprint = fingerprint,
            androidBaselineFingerprint = fingerprint, adoptionState = AndroidAdoptionState.ADOPTED.name,
        ))
        val snapshot = AndroidGroupMembershipSnapshot.create(
            ACCOUNT.value, CONTACT, null, AndroidGroupMembershipAvailability.NO_EMAIL, emptyList(),
        )
        val encoded = AndroidGroupMembershipSnapshotBinaryCodec.encode(snapshot)
        val groupFingerprint = snapshot.semanticFingerprint().sha256Hex
        database.androidGroupProjectionDao().insertMembership(AndroidGroupMembershipProjectionLedgerEntity(
            ACCOUNT.value, CONTACT, 0, 0, 1, null, groupFingerprint, groupFingerprint, null,
            AndroidProjectionWriteState.CLEAN.name, AndroidIngestionState.NONE.name,
        ))
        database.androidGroupProjectionDao().upsertMembershipBaseline(AndroidGroupMembershipBaselineEntity(
            ACCOUNT.value, CONTACT,
            AndroidGroupMembershipSnapshotBinaryCodec.integrityFingerprint(encoded).sha256Hex, encoded,
        ))
        return canonical
    }

    @Test fun repairEntryDelegatesExactlyOnceAndStaleAccountNeverDelegates() = runBlocking {
        seed(clean = false)
        var writes = 0
        val coordinator = RoomBoundedAndroidProjectionCoordinator(database, { _, _ ->
            writes++
            AndroidBoundedPageResult.Applied
        })
        assertEquals(AndroidBoundedPageResult.Applied, coordinator.projectPage(CONTEXT, null).second)
        assertEquals(1, writes)
        assertEquals(
            AndroidBoundedPageResult.ReplanRequired,
            coordinator.projectPage(CONTEXT.copy(accountRevision = 9), null).second,
        )
        assertEquals(1, writes)
    }

    @Test fun dirtyPageUsesAtMostSixteenConcurrentContactExecutors() = runBlocking {
        seedMany(32)
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val writes = AtomicInteger()
        val coordinator = RoomBoundedAndroidProjectionCoordinator(database, { _, _ ->
            val now = active.incrementAndGet()
            maximum.updateAndGet { previous -> maxOf(previous, now) }
            delay(10)
            active.decrementAndGet()
            writes.incrementAndGet()
            AndroidBoundedPageResult.Applied
        })

        assertEquals(AndroidBoundedPageResult.Applied, coordinator.projectPage(CONTEXT, null).second)
        assertEquals(32, writes.get())
        assertEquals(16, maximum.get())
    }

    @Test fun completedSlotStartsNextContactWithoutWaitingForSlowestWorker() = runBlocking {
        seedMany(17)
        val releaseSlowContact = CompletableDeferred<Unit>()
        val seventeenthContactStarted = CompletableDeferred<Unit>()
        val coordinator = RoomBoundedAndroidProjectionCoordinator(database, { _, ledger ->
            when (ledger.canonicalContactId) {
                "contact-000" -> releaseSlowContact.await()
                "contact-016" -> seventeenthContactStarted.complete(Unit)
            }
            AndroidBoundedPageResult.Applied
        })

        val projection = async { coordinator.projectPage(CONTEXT, null) }
        try {
            withTimeout(5_000) { seventeenthContactStarted.await() }
        } finally {
            releaseSlowContact.complete(Unit)
        }

        assertEquals(AndroidBoundedPageResult.Applied, projection.await().second)
    }

    @Test fun terminalReplanStopsNewAdmissionsAndDoesNotAdvanceThePage() = runBlocking {
        seedMany(32)
        val initialWorkersStarted = CompletableDeferred<Unit>()
        val releaseSuccessfulWorkers = CompletableDeferred<Unit>()
        val started = AtomicInteger()
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val coordinator = RoomBoundedAndroidProjectionCoordinator(database, { _, ledger ->
            val now = active.incrementAndGet()
            maximum.updateAndGet { previous -> maxOf(previous, now) }
            if (started.incrementAndGet() == 16) initialWorkersStarted.complete(Unit)
            initialWorkersStarted.await()
            val result = if (ledger.canonicalContactId == "contact-000") {
                AndroidBoundedPageResult.ReplanRequired
            } else {
                releaseSuccessfulWorkers.await()
                AndroidBoundedPageResult.Applied
            }
            active.decrementAndGet()
            result
        })

        val projection = async { coordinator.projectPage(CONTEXT, null) }
        withTimeout(5_000) { initialWorkersStarted.await() }
        delay(50)
        releaseSuccessfulWorkers.complete(Unit)
        val (page, result) = projection.await()

        assertEquals(AndroidBoundedPageResult.ReplanRequired, result)
        assertEquals(null, page.nextKey)
        assertEquals(16, started.get())
        assertEquals(16, maximum.get())
    }

    @Test fun writePendingReplanRemainsAnObservedPartialSkip() = runBlocking {
        seedMany(2)
        val ledger = requireNotNull(
            database.androidProjectionLedgerDao().get(ACCOUNT.value, "contact-000"),
        )
        assertEquals(
            1,
            database.androidProjectionLedgerDao().update(
                ledger.copy(projectionState = AndroidProjectionWriteState.WRITE_PENDING.name),
            ),
        )
        var skipped = 0
        var continued = 0
        val coordinator = RoomBoundedAndroidProjectionCoordinator(
            database = database,
            itemExecutor = { _, entry ->
                if (entry.canonicalContactId == "contact-000") {
                    AndroidBoundedPageResult.ReplanRequired
                } else {
                    continued++
                    AndroidBoundedPageResult.Applied
                }
            },
            skipObserver = AndroidProjectionSkipObserver { _, _ -> skipped++ },
        )

        val (page, result) = coordinator.projectPage(CONTEXT, null)

        assertEquals(AndroidBoundedPageResult.PartiallyApplied, result)
        assertEquals(2, page.itemCount)
        assertEquals(1, skipped)
        assertEquals(1, continued)
    }

    @Test fun returnedLocalPersistenceFailureClosesAdmissionAtSixteenWorkers() = runBlocking {
        seedMany(32)
        val initialWorkersStarted = CompletableDeferred<Unit>()
        val releaseSuccessfulWorkers = CompletableDeferred<Unit>()
        val started = AtomicInteger()
        val coordinator = RoomBoundedAndroidProjectionCoordinator(database, { _, ledger ->
            if (started.incrementAndGet() == 16) initialWorkersStarted.complete(Unit)
            initialWorkersStarted.await()
            if (ledger.canonicalContactId == "contact-000") {
                AndroidBoundedPageResult.LocalPersistenceFailure
            } else {
                releaseSuccessfulWorkers.await()
                AndroidBoundedPageResult.Applied
            }
        })

        val projection = async { coordinator.projectPage(CONTEXT, null) }
        withTimeout(5_000) { initialWorkersStarted.await() }
        delay(50)
        releaseSuccessfulWorkers.complete(Unit)
        val (page, result) = projection.await()

        assertEquals(AndroidBoundedPageResult.LocalPersistenceFailure, result)
        assertEquals(null, page.nextKey)
        assertEquals(16, started.get())
    }

    @Test fun staleEpochOnSeventeenthLedgerPreventsEveryExecutorCall() = runBlocking {
        seedMany(17)
        val stale = requireNotNull(
            database.androidProjectionLedgerDao().get(ACCOUNT.value, "contact-016"),
        )
        assertEquals(1, database.androidProjectionLedgerDao().update(stale.copy(providerEpoch = 1)))
        var writes = 0
        val coordinator = RoomBoundedAndroidProjectionCoordinator(database, { _, _ ->
            writes++
            AndroidBoundedPageResult.Applied
        })

        val (page, result) = coordinator.projectPage(CONTEXT, null)

        assertEquals(AndroidBoundedPageResult.ReplanRequired, result)
        assertEquals(null, page.nextKey)
        assertEquals(1, writes)
    }

    @Test fun sqliteFailureIsClosedAsLocalPersistenceFailureWithoutPageAdvance() = runBlocking {
        seed(clean = false)
        val coordinator = RoomBoundedAndroidProjectionCoordinator(database, { _, _ ->
            throw SQLiteException("synthetic")
        })

        val (page, result) = coordinator.projectPage(CONTEXT, null)

        assertEquals(AndroidBoundedPageResult.LocalPersistenceFailure, result)
        assertEquals(null, page.nextKey)
    }

    @Test fun cancellationPropagatesAndCancelsAllActiveWorkers() = runBlocking {
        seedMany(32)
        val initialWorkersStarted = CompletableDeferred<Unit>()
        val neverRelease = CompletableDeferred<Unit>()
        val started = AtomicInteger()
        val coordinator = RoomBoundedAndroidProjectionCoordinator(database, { _, _ ->
            if (started.incrementAndGet() == 16) initialWorkersStarted.complete(Unit)
            neverRelease.await()
            AndroidBoundedPageResult.Applied
        })

        val projection = async { coordinator.projectPage(CONTEXT, null) }
        withTimeout(5_000) { initialWorkersStarted.await() }
        projection.cancelAndJoin()

        assertEquals(true, projection.isCancelled)
        assertEquals(16, started.get())
    }

    @Test fun concurrentTerminalResultsAreEvaluatedInDeterministicPageOrder() = runBlocking {
        seedMany(2)
        val bothStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val started = AtomicInteger()
        val coordinator = RoomBoundedAndroidProjectionCoordinator(database, { _, ledger ->
            if (started.incrementAndGet() == 2) bothStarted.complete(Unit)
            bothStarted.await()
            release.await()
            if (ledger.canonicalContactId == "contact-000") {
                AndroidBoundedPageResult.ReplanRequired
            } else {
                AndroidBoundedPageResult.LocalPersistenceFailure
            }
        })

        val projection = async { coordinator.projectPage(CONTEXT, null) }
        withTimeout(5_000) { bothStarted.await() }
        release.complete(Unit)
        val (page, result) = projection.await()

        assertEquals(AndroidBoundedPageResult.ReplanRequired, result)
        assertEquals(null, page.nextKey)
    }

    @Test fun canonicalGroupsProjectBeforeAnyContactPage() = runBlocking {
        seed(clean = false)
        var contactWrites = 0
        var groupPasses = 0
        val coordinator = RoomBoundedAndroidProjectionCoordinator(
            database = database,
            itemExecutor = {
                _, _ ->
                contactWrites++
                AndroidBoundedPageResult.Applied
            },
            groupProjectionCoordinator = AndroidCanonicalGroupProjectionCoordinator {
                groupPasses++
                AndroidBoundedPageResult.ReplanRequired
            },
        )

        val result = coordinator.projectPage(CONTEXT, null)

        assertEquals(AndroidBoundedPageResult.ReplanRequired, result.second)
        assertEquals(1, groupPasses)
        assertEquals(0, contactWrites)
    }

    @Test fun productionProjectionEmitsOnlyTheBoundedAggregateCategory() = runBlocking {
        seed(clean = false)
        val aggregate = AndroidProjectionRepairAggregate()
        val coordinator = productionAndroidProjectionCoordinator(
            database = database,
            contentResolver = context.contentResolver,
            repairObserver = aggregate,
        )

        assertEquals(
            AndroidBoundedPageResult.PartiallyApplied,
            coordinator.projectPage(CONTEXT, null).second,
        )
        assertEquals(
            mapOf(AndroidProjectionRepairCategory.CANONICAL_CONTACT_STATE to 1),
            aggregate.snapshotAndReset(),
        )
        assertEquals(emptyMap<AndroidProjectionRepairCategory, Int>(), aggregate.snapshotAndReset())
    }

    private suspend fun seed(clean: Boolean) {
        database.contactDao().upsert(CanonicalContact(ACCOUNT.value, CONTACT).toEntity())
        val service = RoomAndroidProjectionLedger(database)
        val account = service.ensureAccount(ACCOUNT)
        service.bindAndroidAccountName(ACCOUNT, account.revision, ANDROID_ACCOUNT)
        database.androidProjectionLedgerDao().insert(AndroidProjectionLedgerEntity(
            accountId = ACCOUNT.value,
            canonicalContactId = CONTACT,
            revision = 0,
            providerEpoch = 0,
            rawContactLocator = null,
            sourceIdentity = "source",
            canonicalProjectionFingerprint = null,
            androidBaselineFingerprint = null,
            pendingProjectionFingerprint = null,
            observedAndroidFingerprint = null,
            projectionState = if (clean) AndroidProjectionWriteState.CLEAN.name else AndroidProjectionWriteState.REPAIR_REQUIRED.name,
            ingestionState = AndroidIngestionState.NONE.name,
            tombstoneState = AndroidTombstoneState.NONE.name,
            adoptionState = AndroidAdoptionState.SOURCE_ID_PENDING.name,
        ))
    }

    private suspend fun seedMany(count: Int) {
        val service = RoomAndroidProjectionLedger(database)
        val account = service.ensureAccount(ACCOUNT)
        service.bindAndroidAccountName(ACCOUNT, account.revision, ANDROID_ACCOUNT)
        repeat(count) { index ->
            val id = "contact-${index.toString().padStart(3, '0')}"
            database.contactDao().upsert(CanonicalContact(ACCOUNT.value, id).toEntity())
            database.androidProjectionLedgerDao().insert(AndroidProjectionLedgerEntity(
                accountId = ACCOUNT.value,
                canonicalContactId = id,
                revision = 0,
                providerEpoch = 0,
                rawContactLocator = null,
                sourceIdentity = "source-$index",
                canonicalProjectionFingerprint = null,
                androidBaselineFingerprint = null,
                pendingProjectionFingerprint = null,
                observedAndroidFingerprint = null,
                projectionState = AndroidProjectionWriteState.REPAIR_REQUIRED.name,
                ingestionState = AndroidIngestionState.NONE.name,
                tombstoneState = AndroidTombstoneState.NONE.name,
                adoptionState = AndroidAdoptionState.SOURCE_ID_PENDING.name,
            ))
        }
    }

    private companion object {
        const val DB = "bounded-projection.db"
        val ACCOUNT = AccountScope("account")
        const val CONTACT = "contact"
        const val ANDROID_ACCOUNT = "android-account"
        val CONTEXT = AndroidInteroperabilityContext(ACCOUNT, ANDROID_ACCOUNT, 1, 0)
    }
}
