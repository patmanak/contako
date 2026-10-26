package com.patmanak.contako.data.sync

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.android.account.ContakoAndroidAccountContract
import com.patmanak.contako.data.android.RoomAndroidProjectionLedger
import com.patmanak.contako.data.android.mapping.*
import com.patmanak.contako.data.android.provider.*
import com.patmanak.contako.data.gateway.*
import com.patmanak.contako.data.local.*
import com.patmanak.contako.domain.model.*
import com.patmanak.contako.domain.repository.SaveResult
import com.patmanak.contako.domain.sync.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real recovery, observation and Room transactions with an in-process provider and remote reader.
 * No Android account, system contact, authenticated session or remote service is accessed.
 */
@RunWith(AndroidJUnit4::class)
class DeletedContactRecoveryCompositionDeviceTest {
    private val application: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: ContakoDatabase
    private lateinit var provider: RecoveryProvider
    private lateinit var scope: AndroidInteroperabilityContext
    private val account = AccountScope(OWNER)
    private var presence = RemoteContactPresence.CONFIRMED_ABSENT
    private var presenceReads = 0
    private var preflightReads = 0
    private var beforePreflight: suspend (Int) -> Unit = {}
    private var groupsUnavailable = false
    private val existence = ProtonContactExistenceGateway { owner, id ->
        assertEquals(account, owner)
        assertEquals(REMOTE, id.value)
        presenceReads++
        GatewayOutcome.Success(presence)
    }
    private fun store() = RoomDeletionRecoveryStore(database)

    @Before fun setup() = runBlocking<Unit> {
        application.deleteDatabase(DB)
        database = ContakoDatabase.create(application, DB)
        provider = RecoveryProvider()
        val service = RoomAndroidProjectionLedger(database)
        val initial = service.ensureAccount(account)
        service.bindAndroidAccountName(account, initial.revision, ANDROID)
        val binding = requireNotNull(database.androidProjectionLedgerDao().getAccount(OWNER))
        scope = AndroidInteroperabilityContext(account, ANDROID, binding.revision, binding.providerEpoch)
    }

    @After fun cleanup() {
        if (::database.isInitialized && database.isOpen) database.close()
        application.deleteDatabase(DB)
    }

    @Test fun interruptedDeleteReopensRoomAndResumesOnlyTheAllocatedDestination() = runBlocking {
        val original = seed()
        queueRecreation()
        val queued = requireNotNull(store().get(OWNER, SOURCE))
        val destination = requireNotNull(queued.destinationContactId)
        val intent = database.outboxDao().get(OWNER, "CONTACT", SOURCE)
        val foreign = provider.foreignSnapshot()
        val crash = SimulatedProcessInterruption()
        val failure = runCatching { phase(afterDelete = { throw crash }).run(account) { false } }.exceptionOrNull()
        assertSame(crash, failure)
        assertFalse(provider.present)
        assertEquals(1, provider.deleteCalls)
        assertEquals(foreign, provider.foreignSnapshot())

        reopen()
        val prepared = requireNotNull(store().get(OWNER, SOURCE))
        assertEquals(DeletionRecoveryState.CLEANUP_PENDING.name, prepared.state)
        assertTrue(prepared.hasDraft)
        assertFalse(prepared.sourceRetired)
        assertEquals(destination, prepared.destinationContactId)
        assertEquals(RAW, prepared.cleanupRaw)
        assertEquals(VERSION, prepared.cleanupVersion)
        assertEquals(scope.providerEpoch, prepared.cleanupEpoch)
        assertEquals(database.androidProjectionLedgerDao().get(OWNER, SOURCE)?.revision, prepared.cleanupLedgerRevision)
        assertEquals(original, store().detail(OWNER, SOURCE)?.contact)
        assertEquals(intent, database.outboxDao().get(OWNER, "CONTACT", SOURCE))
        assertNull(database.contactDao().getEntity(OWNER, destination))
        assertFalse(requireNotNull(database.contactDao().getEntity(OWNER, SOURCE)).isDeleted)

        val readsBeforeResume = presenceReads
        assertEquals(SyncPassOutcome.SUCCESS, phase().run(account) { false })
        assertEquals(readsBeforeResume + 1, presenceReads)
        assertEquals(1, provider.deleteCalls)
        val recovered = requireNotNull(store().get(OWNER, SOURCE))
        assertEquals(DeletionRecoveryState.RECREATED.name, recovered.state)
        assertTrue(recovered.sourceRetired)
        assertEquals(destination, recovered.destinationContactId)
        val recreated = requireNotNull(database.contactDao().get(OWNER, destination)).toDomain()
        assertEquals(original.displayName, recreated.displayName)
        assertEquals(original.values, recreated.values)
        assertNull(recreated.remoteContactId)
        assertNull(recreated.remoteVCardUid)
        assertNull(recreated.remoteVersion)
        assertTrue(requireNotNull(database.contactDao().getEntity(OWNER, SOURCE)).isDeleted)
        assertNull(database.outboxDao().get(OWNER, "CONTACT", SOURCE))
        val create = database.outboxDao().getAll(OWNER).single()
        assertEquals(destination, create.aggregateId)
        assertEquals(MutationOperation.UPSERT.name, create.operation)
        assertNull(create.remoteIdentity)
        assertEquals(SyncPassOutcome.SUCCESS, phase().run(account) { false })
        assertEquals(listOf(create), database.outboxDao().getAll(OWNER))
        assertEquals(foreign, provider.foreignSnapshot())
    }

    @Test fun oldRemoteReturningBeforeCreateSuspendsTheActualDestinationWithoutDelegating() = runBlocking {
        val original = seed()
        queueRecreation()
        assertEquals(SyncPassOutcome.SUCCESS, phase().run(account) { false })
        val recovery = requireNotNull(store().get(OWNER, SOURCE))
        val destination = requireNotNull(recovery.destinationContactId)
        val create = RoomMutationExecutionStore(database).eligible(OWNER, Long.MAX_VALUE, 10).single()
        assertEquals(RemoteMutationOperation.CREATE, create.operation)
        assertEquals(destination, create.aggregateId)
        var delegations = 0
        val guard = DeletedContactRecreationPreparationGuard(account, store(), existence,
            MutationPreparationGateway { delegations++; GatewayOutcome.Success(MutationPreparation.UploadAllowed) })
        assertEquals(MutationPreparation.UploadAllowed, (guard.prepare(create) as GatewayOutcome.Success).value)
        assertEquals(1, delegations)
        presence = RemoteContactPresence.PRESENT
        val result = (guard.prepare(create) as GatewayOutcome.Success).value as MutationPreparation.ActionRequired
        assertEquals(MutationPreparationActionRequiredReason.REMOTE_UPDATE_CONFLICT, result.reason)
        assertEquals(1, delegations)
        val suspended = requireNotNull(database.outboxDao().get(OWNER, "CONTACT", destination))
        assertEquals(DurableMutationState.ACTION_REQUIRED.name, suspended.state)
        assertEquals(RoomDeletionRecoveryStore.REQUIRED, suspended.blockedReason)
        assertEquals(DeletionRecoveryState.STALE.name, store().get(OWNER, SOURCE)?.state)
        assertEquals(DeletionRecoveryBlocker.REMOTE_PRESENT.name, store().get(OWNER, SOURCE)?.blocker)
        assertEquals(original.values, database.contactDao().get(OWNER, destination)?.toDomain()?.values)
        reopen()
        assertEquals(destination, store().get(OWNER, SOURCE)?.destinationContactId)
        assertEquals(listOf(suspended), database.outboxDao().getAll(OWNER))
        assertEquals(1, provider.deleteCalls)
        assertEquals(provider.initialForeign, provider.foreignSnapshot())
    }

    @Test fun opaqueProviderRowRetainsExactSourceAndCapturedDraft() = runBlocking {
        seed()
        queueRecreation()
        provider.rows += dataRow(104, "vnd.fixture/opaque", "Provider-only value", null)
        assertRefused(DeletionRecoveryBlocker.OPAQUE_ANDROID_DATA, draftCaptured = true)
    }

    @Test fun outstandingPhotoPublicationCannotAuthorizeSourceRemoval() = runBlocking {
        seed(withPhoto = true)
        queueRecreation()
        val ledger = requireNotNull(database.androidProjectionLedgerDao().get(OWNER, SOURCE))
        val revision = requireNotNull(database.contactDao().getEntity(OWNER, SOURCE)).revision
        val journal = AndroidPhotoProviderWriteJournalEntity(OWNER, SOURCE, ANDROID, scope.providerEpoch,
            RAW, VERSION, REMOTE, revision, ledger.revision, "photo", PHOTO, 1, "0".repeat(64), "PREPARED", null)
        database.androidGroupProjectionDao().upsertPhotoProviderWriteJournal(journal)
        assertRefused(DeletionRecoveryBlocker.NATIVE_CHANGES, draftCaptured = true)
        assertEquals(journal, database.androidGroupProjectionDao().getPhotoProviderWriteJournal(OWNER, SOURCE))
    }

    @Test fun unexpectedNativeGroupOccurrenceIsNeverDiscardedUsingAnEmptyMembershipBaseline() = runBlocking {
        seed()
        queueRecreation()
        provider.rows += dataRow(104, ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE, "901", null)
        assertRefused(DeletionRecoveryBlocker.NATIVE_CHANGES, draftCaptured = true)
    }

    @Test fun unavailableGroupCatalogStopsBeforeDraftPreparationAndProviderDelete() = runBlocking {
        seed()
        queueRecreation()
        groupsUnavailable = true
        assertRefused(DeletionRecoveryBlocker.NATIVE_CHANGES, draftCaptured = false)
    }

    @Test fun changedEpochBetweenIngestionAndCleanupCannotReuseTheOldConsent() = runBlocking {
        seed()
        queueRecreation()
        beforePreflight = { turn ->
            if (turn == 2) {
                assertEquals(1, database.androidProjectionLedgerDao().advanceProviderEpoch(OWNER, scope.accountRevision))
                scope = scope.copy(accountRevision = scope.accountRevision + 1, providerEpoch = scope.providerEpoch + 1)
            }
        }
        assertRefused(DeletionRecoveryBlocker.NATIVE_CHANGES, draftCaptured = false)
        assertEquals(2, preflightReads)
    }

    @Test fun nativeEditAfterFinalObservationSurvivesTheAtomicCleanDeletePredicate() = runBlocking {
        val original = seed()
        queueRecreation()
        provider.beforeDelete = {
            provider.dirty = true
            provider.rows = provider.rows.map { row ->
                if (row.canonicalValueId == "note") row.copy(stringSlots = slots("Concurrent native note")) else row
            }
        }
        val foreign = provider.foreignSnapshot()
        assertEquals(SyncPassOutcome.ACTION_REQUIRED, phase().run(account) { false })
        assertTrue(provider.present)
        assertTrue(provider.dirty)
        assertEquals("Concurrent native note", provider.rows.single { it.canonicalValueId == "note" }.stringSlots.first())
        assertEquals(original, store().detail(OWNER, SOURCE)?.contact)
        assertEquals(DeletionRecoveryBlocker.NATIVE_CHANGES.name, store().get(OWNER, SOURCE)?.blocker)
        assertNotNull(store().get(OWNER, SOURCE)?.cleanupRaw)
        assertFalse(requireNotNull(database.contactDao().getEntity(OWNER, SOURCE)).isDeleted)
        assertEquals(SOURCE, database.outboxDao().getAll(OWNER).single().aggregateId)
        assertEquals(1, provider.deleteCalls)
        assertEquals(foreign, provider.foreignSnapshot())
    }

    @Test fun missingSourceWithoutPreparedCleanupCannotBeTreatedAsAnInterruptedDelete() = runBlocking {
        seed()
        queueRecreation()
        provider.present = false
        val source = database.contactDao().get(OWNER, SOURCE)
        val intent = database.outboxDao().get(OWNER, "CONTACT", SOURCE)
        assertEquals(SyncPassOutcome.ACTION_REQUIRED, phase().run(account) { false })
        assertEquals(DeletionRecoveryBlocker.NATIVE_CHANGES.name, store().get(OWNER, SOURCE)?.blocker)
        assertNull(store().get(OWNER, SOURCE)?.cleanupRaw)
        assertEquals(source, database.contactDao().get(OWNER, SOURCE))
        assertEquals(intent, database.outboxDao().get(OWNER, "CONTACT", SOURCE))
        assertNull(database.contactDao().getEntity(OWNER, requireNotNull(store().get(OWNER, SOURCE)?.destinationContactId)))
        assertEquals(0, provider.deleteCalls)
        assertEquals(provider.initialForeign, provider.foreignSnapshot())
    }

    private suspend fun assertRefused(blocker: DeletionRecoveryBlocker, draftCaptured: Boolean) {
        val source = database.contactDao().get(OWNER, SOURCE)
        val draft = store().detail(OWNER, SOURCE)?.contact
        val intent = database.outboxDao().get(OWNER, "CONTACT", SOURCE)
        val rows = provider.rows.toList()
        val foreign = provider.foreignSnapshot()
        val destination = requireNotNull(store().get(OWNER, SOURCE)?.destinationContactId)
        assertEquals(SyncPassOutcome.ACTION_REQUIRED, phase().run(account) { false })
        assertEquals(blocker.name, store().get(OWNER, SOURCE)?.blocker)
        assertEquals(draftCaptured, store().get(OWNER, SOURCE)?.hasDraft)
        assertEquals(source, database.contactDao().get(OWNER, SOURCE))
        assertEquals(draft, store().detail(OWNER, SOURCE)?.contact)
        assertEquals(intent, database.outboxDao().get(OWNER, "CONTACT", SOURCE))
        assertNull(database.contactDao().getEntity(OWNER, destination))
        assertTrue(provider.present)
        assertEquals(rows, provider.rows)
        assertEquals(0, provider.deleteCalls)
        assertEquals(foreign, provider.foreignSnapshot())
    }

    private suspend fun seed(withPhoto: Boolean = false): CanonicalContact {
        val saved = RoomContactRepository(database).saveContact(CanonicalContact(OWNER, SOURCE,
            displayName = "Synthetic recovery", remoteContactId = REMOTE, remoteVCardUid = "retired-uid",
            remoteVersion = "baseline", values = listOf(
                ContactValue("note", ContactValueKind.NOTE, "Retained note", order = 0),
                ContactValue("email", ContactValueKind.EMAIL, "recovery@example.test", order = 0),
            ) + if (withPhoto) listOf(ContactValue("photo", ContactValueKind.PHOTO, PHOTO, order = 0,
                binaryReference = PHOTO)) else emptyList())) as SaveResult.Saved
        val intent = requireNotNull(database.outboxDao().get(OWNER, "CONTACT", SOURCE))
        database.outboxDao().upsert(intent.copy(state = "ACTION_REQUIRED", blockedReason = RoomDeletionRecoveryStore.REQUIRED))
        // The photo case retains the preceding photo-less baseline while its stream journal
        // is outstanding. Neither that baseline nor DIRTY=0 acknowledges photo publication.
        val mapper = CanonicalAndroidContactMapper(photoBytesAvailable = { false })
        val projected = mapper.project(saved.value)
        val snapshot = projected.copy(rows = projected.rows.mapIndexed { index, row ->
            row.copy(identity = row.identity.copy(providerRowId = 101L + index))
        })
        provider.rows = snapshot.rows.map { row ->
            val mime = when (row.kind) {
                AndroidRowKind.STRUCTURED_NAME -> ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
                AndroidRowKind.EMAIL -> ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE
                AndroidRowKind.NOTE -> ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE
                else -> error("Unexpected synthetic field")
            }
            dataRow(requireNotNull(row.identity.providerRowId), mime, row.value, row.identity.canonicalValueId)
                .copy(canonicalOrder = row.order, isPrimary = row.isPrimary, isSuperPrimary = row.isSuperPrimary)
        }
        val fingerprint = mapper.fingerprint(snapshot).sha256Hex
        val dao = database.androidProjectionLedgerDao()
        dao.insert(AndroidProjectionLedgerEntity(OWNER, SOURCE, 0, scope.providerEpoch, RAW, REMOTE,
            canonicalProjectionFingerprint = fingerprint, androidBaselineFingerprint = fingerprint,
            pendingProjectionFingerprint = null, observedAndroidFingerprint = fingerprint,
            projectionState = "CLEAN", ingestionState = "BASELINED", tombstoneState = "NONE", adoptionState = "ADOPTED"))
        dao.upsertBaseline(AndroidProjectionBaselineEntity(OWNER, SOURCE, fingerprint, AndroidContactSnapshotBinaryCodec.encode(snapshot)))
        val memberships = AndroidGroupMembershipSnapshot.create(OWNER, SOURCE, "email",
            AndroidGroupMembershipAvailability.AVAILABLE, emptyList())
        val encoded = AndroidGroupMembershipSnapshotBinaryCodec.encode(memberships)
        val groupDao = database.androidGroupProjectionDao()
        groupDao.insertMembership(AndroidGroupMembershipProjectionLedgerEntity(OWNER, SOURCE, 0, scope.providerEpoch, RAW,
            "email", memberships.semanticFingerprint().sha256Hex, memberships.semanticFingerprint().sha256Hex,
            null, "CLEAN", "BASELINED"))
        groupDao.upsertMembershipBaseline(AndroidGroupMembershipBaselineEntity(OWNER, SOURCE,
            AndroidGroupMembershipSnapshotBinaryCodec.integrityFingerprint(encoded).sha256Hex, encoded))
        store().discover(OWNER)
        return requireNotNull(store().detail(OWNER, SOURCE)).contact
    }

    private suspend fun queueRecreation() {
        assertEquals(SyncPassOutcome.SUCCESS, phase().run(account) { false })
        val ready = requireNotNull(store().detail(OWNER, SOURCE))
        assertEquals(DeletionRecoveryState.READY, ready.summary.state)
        assertTrue(store().queue(OWNER, ready.summary, DeletionRecoveryChoice.RECREATE))
    }

    private fun phase(afterDelete: () -> Unit = {}): DeletedContactRecovery {
        val resolver = ContentResolver.wrap(provider)
        val reader = AndroidContactsProviderReader(resolver)
        val contacts = ProductionAndroidContactObservationCoordinator(database, RoomContactRepository(database),
            AndroidContactObservationAcknowledger { _, _, _, _, _, _ -> error("Clean fixture must not be acknowledged") })
        val observer = GroupOnlyProductionAndroidBoundedObservationCoordinator.compose(database, RoomContactRepository(database),
            providerFinalizer = AndroidGroupObservationProviderFinalizer { _, _, _ -> error("No group writes expected") },
            contactCoordinator = contacts)
        val stage = BoundedAndroidInteroperabilityStage(
            AndroidStableContactPageReader { _, _ -> error("Only exact source reads are allowed") },
            AndroidGroupPageReader { name, after ->
                if (groupsUnavailable) throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.PROVIDER_UNAVAILABLE)
                AndroidOwnedGroupRowPage(name, after, emptyList(), null)
            }, observer, AndroidBoundedProjectionCoordinator { _, _ -> error("Recovery must not run general projection") },
            exactContactsReader = AndroidExactContactReader(reader::readStableRawContact))
        val preflight = AndroidInteroperabilityPreflight { owner, _ ->
            assertEquals(account, owner)
            beforePreflight(++preflightReads)
            AndroidInteroperabilityPreflightResult.Ready(scope)
        }
        return DeletedContactRecovery(RoomDeletedContactRecoveryExecutionStore(store()), existence,
            ProductionDeletedContactNativeCleanup(database, store(), preflight, stage, reader,
                AndroidRawContactLifecycleGateway(resolver, afterDeleteCommitted = afterDelete)))
    }

    private fun reopen() {
        database.close()
        database = ContakoDatabase.create(application, DB)
    }

    private class SimulatedProcessInterruption : Error("Synthetic process interruption")

    /** Provider-side state and predicates only; recovery and completion remain production code. */
    private class RecoveryProvider : ContentProvider() {
        private class RawFixture(val id: Long, val accountName: String, val source: String,
            var dirty: Boolean = false, var rows: List<AndroidOwnedDataRow> = emptyList())
        private val owned = RawFixture(RAW, ANDROID, REMOTE)
        // Same canonical claim, different account/source; both live in the provider's row set.
        private val foreign = RawFixture(81, "foreign-account", "foreign-remote", rows = listOf(
            dataRow(201, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE, "Foreign note", "foreign-note")
                .copy(rawContactId = 81),
            dataRow(202, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE, "foreign@example.test", "foreign-email")
                .copy(rawContactId = 81)))
        private val rawRows = mutableListOf(owned, foreign)
        var present: Boolean
            get() = owned in rawRows
            set(value) { if (value) { if (owned !in rawRows) rawRows += owned } else rawRows.remove(owned) }
        var dirty: Boolean
            get() = owned.dirty
            set(value) { owned.dirty = value }
        var rows: List<AndroidOwnedDataRow>
            get() = owned.rows
            set(value) { owned.rows = value }
        var deleteCalls = 0
        var beforeDelete: () -> Unit = {}
        val initialForeign = foreignSnapshot()
        fun foreignSnapshot() = listOf(foreign in rawRows, foreign.id, foreign.accountName, foreign.source,
            foreign.dirty, foreign.rows.toList())
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val columns = requireNotNull(projection)
            val args = requireNotNull(selectionArgs).toList()
            assertEquals(ANDROID, args.first())
            assertEquals(ContakoAndroidAccountContract.ACCOUNT_TYPE, args[1])
            return MatrixCursor(columns).apply {
                val matchingAccounts = rawRows.filter { it.accountName == args[0] }
                when (uri.path) {
                    "/raw_contacts" -> {
                        if (selection.orEmpty().contains("${ContactsContract.RawContacts.SYNC1} = ?")) assertEquals(SOURCE, args[2])
                        else assertEquals(listOf(RAW.toString()), args.drop(2))
                        matchingAccounts.filter { selection.orEmpty().contains("${ContactsContract.RawContacts.SYNC1} = ?") ||
                            it.id.toString() in args.drop(2) }.forEach { raw -> addRow(columns.map { column -> when (column) {
                            ContactsContract.RawContacts._ID -> raw.id
                            ContactsContract.RawContacts.SYNC1 -> SOURCE
                            ContactsContract.RawContacts.SOURCE_ID -> raw.source
                            ContactsContract.RawContacts.VERSION -> VERSION
                            ContactsContract.RawContacts.DIRTY -> if (raw.dirty) 1 else 0
                            ContactsContract.RawContacts.DELETED -> 0
                            else -> error("Unexpected raw column")
                        } }) }
                    }
                    "/data" -> {
                        val accountRows = matchingAccounts.flatMap { it.rows }
                        val selected = if (columns.size == 3) accountRows.filter { it.rawContactId.toString() in args.drop(2) }
                            else accountRows.filter { it.dataRowId.toString() in args.drop(2) }
                        selected.forEach { row -> addRow(columns.map { column -> when (column) {
                            ContactsContract.Data._ID -> row.dataRowId
                            ContactsContract.Data.RAW_CONTACT_ID -> row.rawContactId
                            ContactsContract.Data.MIMETYPE -> row.mimeType
                            "data_sync1" -> row.canonicalValueId
                            "data_sync2" -> row.canonicalOrder?.toString()
                            "data_sync3" -> null
                            ContactsContract.Data.IS_PRIMARY -> if (row.isPrimary) 1 else 0
                            ContactsContract.Data.IS_SUPER_PRIMARY -> if (row.isSuperPrimary) 1 else 0
                            else -> row.stringSlots[column.removePrefix("data").toInt() - 1]
                        } }) }
                    }
                    else -> error("Unexpected provider URI")
                }
            }
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            deleteCalls++
            assertEquals("true", uri.getQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER))
            assertEquals(ANDROID, uri.getQueryParameter(ContactsContract.RawContacts.ACCOUNT_NAME))
            assertEquals("${ContactsContract.RawContacts._ID} = ? AND ${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND " +
                "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND ${ContactsContract.RawContacts.VERSION} = ? AND " +
                "${ContactsContract.RawContacts.SYNC1} = ? AND ${ContactsContract.RawContacts.SOURCE_ID} = ? AND " +
                "${ContactsContract.RawContacts.DIRTY} = 0", selection)
            assertEquals(listOf(RAW.toString(), ANDROID, ContakoAndroidAccountContract.ACCOUNT_TYPE, VERSION.toString(), SOURCE, REMOTE),
                selectionArgs?.toList())
            beforeDelete()
            val args = requireNotNull(selectionArgs)
            val targets = rawRows.filter { it.id.toString() == args[0] && it.accountName == args[1] &&
                VERSION.toString() == args[3] && SOURCE == args[4] && it.source == args[5] && !it.dirty }
            rawRows.removeAll(targets.toSet())
            return targets.size
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("No provider insert permitted")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
            error("No provider update permitted")
    }

    private companion object {
        const val OWNER = "deletion-composition-owner"
        const val ANDROID = "deletion-composition-android"
        const val SOURCE = "source-contact"
        const val REMOTE = "retired-remote"
        const val DB = "deleted-contact-recovery-composition.db"
        const val RAW = 41L
        const val VERSION = 7L
        const val PHOTO = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
        fun slots(value: String) = List(14) { if (it == 0) value else null }
        fun dataRow(id: Long, mime: String, value: String, valueId: String?) = AndroidOwnedDataRow(
            id, RAW, mime, valueId, 0, linkedValueIdsEncoding = null, isPrimary = false, isSuperPrimary = false,
            stringSlots = slots(value), binarySlot = null)
    }
}
