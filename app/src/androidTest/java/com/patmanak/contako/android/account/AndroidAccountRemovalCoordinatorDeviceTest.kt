package com.patmanak.contako.android.account

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.contako.data.android.RoomAndroidProjectionLedger
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.GatewayOutcome
import com.patmanak.contako.data.gateway.ProtonLocalSessionCleanupGateway
import com.patmanak.contako.data.android.provider.FrameworkAndroidAccountProjectionCleaner
import com.patmanak.contako.data.android.provider.AndroidAccountProjectionCleaner
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.domain.sync.AccountSyncRunner
import com.patmanak.contako.domain.sync.SyncPassOutcome
import com.patmanak.contako.domain.sync.SyncRequestDisposition
import com.patmanak.contako.domain.sync.SyncTrigger
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidAccountRemovalCoordinatorDeviceTest {
    private lateinit var context: Context
    private lateinit var database: ContakoDatabase
    private lateinit var scope: CoroutineScope
    private val created = mutableListOf<Pair<Long, Account>>()

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "pm grant ${context.packageName} android.permission.READ_CONTACTS",
        ).close()
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "pm grant ${context.packageName} android.permission.WRITE_CONTACTS",
        ).close()
        context.deleteDatabase(DATABASE_NAME)
        database = ContakoDatabase.create(context, DATABASE_NAME)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After fun tearDown() {
        created.forEach { (id, account) ->
            context.contentResolver.delete(
                syncAdapterUri(ContactsContract.RawContacts.CONTENT_URI, account),
                "${ContactsContract.RawContacts._ID} = ? AND " +
                    "${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND " +
                    "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ?",
                arrayOf(id.toString(), account.name, account.type),
            )
        }
        scope.cancel()
        database.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test fun removalDeletesOnlyExactRoomAndProviderScopeWithoutRemoteCall() = runBlocking {
        val targetScope = AccountScope("room-target")
        val otherScope = AccountScope("room-other")
        val target = Account("target-removal@example.test", ContakoAndroidAccountContract.ACCOUNT_TYPE)
        val other = Account("other-removal@example.test", ContakoAndroidAccountContract.ACCOUNT_TYPE)
        val foreign = Account(target.name, "foreign.account.type")
        val ledger = RoomAndroidProjectionLedger(database)
        ledger.ensureAccount(targetScope).also { ledger.bindAndroidAccountName(targetScope, it.revision, target.name) }
        ledger.ensureAccount(otherScope).also { ledger.bindAndroidAccountName(otherScope, it.revision, other.name) }
        seedAccountRoots(targetScope.value)
        seedAccountRoots(otherScope.value)
        val targetRaw = insertRawContact(target)
        val otherRaw = insertRawContact(other)
        val foreignRaw = insertRawContact(foreign)
        val calls = mutableListOf<String>()
        val session = Proxy.newProxyInstance(
            ProtonLocalSessionCleanupGateway::class.java.classLoader,
            arrayOf(ProtonLocalSessionCleanupGateway::class.java),
        ) { _, method, _ ->
            calls += method.name
            GatewayOutcome.Success(Unit)
        } as ProtonLocalSessionCleanupGateway
        val runner = AccountSyncRunner(scope) { SyncPassOutcome.SUCCESS }
        var ownerReleased = false
        val coordinator = AndroidAccountRemovalCoordinator(
            FrameworkAndroidAccountProjectionCleaner(context.contentResolver),
            database,
            targetScope,
            session,
            runner,
            releaseOwnerAfterLocalDataCleared = {
                assertFalse("Owner release must follow the Room commit", database.inTransaction())
                accountScopedTables().forEach { table -> assertEquals(0L, countRows(table, targetScope.value)) }
                ownerReleased = true
            },
        )

        assertTrue(coordinator.remove(target))
        assertTrue(ownerReleased)

        assertNull(database.androidProjectionLedgerDao().getAccount(targetScope.value))
        assertEquals(other.name, database.androidProjectionLedgerDao().getAccount(otherScope.value)?.androidAccountName)
        accountScopedTables().forEach { table ->
            assertEquals("target rows remained in $table", 0L, countRows(table, targetScope.value))
        }
        listOf(
            "contacts",
            "contact_groups",
            "outbox_mutations",
            "contact_inventory_checkpoints",
            "sync_account_status",
            "full_repair_progress",
            "android_projection_accounts",
        ).forEach { table -> assertTrue("foreign rows lost from $table", countRows(table, otherScope.value) > 0L) }
        assertFalse(rawContactExists(targetRaw))
        assertTrue(rawContactExists(otherRaw))
        assertTrue(rawContactExists(foreignRaw))
        assertEquals(listOf("clearLocal"), calls)
        assertEquals(SyncRequestDisposition.ACCOUNT_INVALIDATED, runner.request(SyncTrigger.RETRY))
    }

    @Test fun cleanSignOutReleasesOwnerOnlyAfterGuardedRoomTransactionCommits() = runBlocking {
        val targetScope = AccountScope("clean-owner-release")
        val target = Account("clean-owner@example.test", ContakoAndroidAccountContract.ACCOUNT_TYPE)
        val ledger = RoomAndroidProjectionLedger(database)
        ledger.ensureAccount(targetScope).also { ledger.bindAndroidAccountName(targetScope, it.revision, target.name) }
        var releases = 0
        val cleaner = object : AndroidAccountProjectionCleaner {
            override fun delete(accountName: String, accountType: String) = true
            override fun deleteIfClean(accountName: String, accountType: String) = true
            override fun hasPendingChanges(accountName: String, accountType: String) = false
        }
        val coordinator = AndroidAccountRemovalCoordinator(cleaner, database, targetScope,
            ProtonLocalSessionCleanupGateway { GatewayOutcome.Success(Unit) },
            AccountSyncRunner(scope) { SyncPassOutcome.SUCCESS },
            releaseOwnerAfterLocalDataCleared = {
                assertFalse(database.inTransaction())
                assertNull(database.androidProjectionLedgerDao().getAccount(targetScope.value))
                releases++
            })
        assertTrue(coordinator.removeFromApp(target))
        assertEquals(1, releases)
    }

    @Test fun cancellationAfterSessionCleanupCannotSplitEitherSignOutChoiceFromOwnerFinalization() = runBlocking {
        for (discard in listOf(false, true)) {
            val targetScope = AccountScope("cancel-cleanup-$discard")
            val target = Account("cancel-cleanup-$discard@example.test", ContakoAndroidAccountContract.ACCOUNT_TYPE)
            val ledger = RoomAndroidProjectionLedger(database)
            ledger.ensureAccount(targetScope).also { ledger.bindAndroidAccountName(targetScope, it.revision, target.name) }
            val entered = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            val checkpoints = AccountRemovalCheckpointStore.Memory()
            var ownerReleased = false
            var platformRemoved = false
            val runner = AccountSyncRunner(scope) { SyncPassOutcome.SUCCESS }
            val coordinator = AndroidAccountRemovalCoordinator(cleanCleaner(), database, targetScope,
                ProtonLocalSessionCleanupGateway {
                    entered.complete(Unit)
                    resume.await()
                    GatewayOutcome.Success(Unit)
                }, runner,
                platformCleanup = object : AccountPlatformCleanup by AccountPlatformCleanup.None {
                    override fun removeAndroidAccount(account: Account): Boolean { platformRemoved = true; return true }
                }, checkpoints = checkpoints,
                releaseOwnerAfterLocalDataCleared = {
                    assertFalse(database.inTransaction())
                    assertNull(database.androidProjectionLedgerDao().getAccount(targetScope.value))
                    assertTrue(platformRemoved)
                    ownerReleased = true
                })
            val operation = launch(Dispatchers.Default) { coordinator.removeFromApp(target, discardPendingChanges = discard) }
            entered.await()
            operation.cancel()
            resume.complete(Unit)
            operation.join()
            assertTrue(ownerReleased)
            assertNull(checkpoints.load(targetScope, target))
            assertEquals(SyncRequestDisposition.ACCOUNT_INVALIDATED, runner.request(SyncTrigger.RETRY))
        }
    }

    @Test fun ownerReleaseFailureAfterRoomPurgeCanResumeFromExactCheckpointThroughAppSignOut() = runBlocking {
        val targetScope = AccountScope("retry-owner-release")
        val target = Account("retry-owner@example.test", ContakoAndroidAccountContract.ACCOUNT_TYPE)
        val ledger = RoomAndroidProjectionLedger(database)
        ledger.ensureAccount(targetScope).also { ledger.bindAndroidAccountName(targetScope, it.revision, target.name) }
        val checkpoints = AccountRemovalCheckpointStore.Memory()
        var releases = 0
        var sessionCleanups = 0
        var platformRemovals = 0
        val runner = AccountSyncRunner(scope) { SyncPassOutcome.SUCCESS }
        val removal = AndroidAccountRemovalCoordinator(cleanCleaner(), database, targetScope,
            ProtonLocalSessionCleanupGateway { sessionCleanups++; GatewayOutcome.Success(Unit) }, runner,
            platformCleanup = object : AccountPlatformCleanup by AccountPlatformCleanup.None {
                override fun removeAndroidAccount(account: Account): Boolean { platformRemovals++; return true }
            }, checkpoints = checkpoints,
            releaseOwnerAfterLocalDataCleared = {
                assertNull(database.androidProjectionLedgerDao().getAccount(targetScope.value))
                releases++
                if (releases == 1) error("Synthetic owner storage failure")
            })
        val signOut = AccountSignOutCoordinator(targetScope, PendingMutationReader { 0 },
            BoundAndroidAccountReader { account ->
                database.androidProjectionLedgerDao().getAccount(account.value)?.androidAccountName
                    ?.let { Account(it, ContakoAndroidAccountContract.ACCOUNT_TYPE) }
                    ?: checkpoints.pendingAccount(account)
            }, runner, InAppAccountRemoval(removal::removeFromApp),
            noAndroidRemoval = { error("A removed Room binding must not lose the cleanup checkpoint") },
            hasIncompleteRemoval = { checkpoints.pendingAccount(targetScope) != null })
        assertEquals(SignOutResult.CleanupFailed, signOut.signOut(SignOutChoice.CONFIRM))
        assertNull(database.androidProjectionLedgerDao().getAccount(targetScope.value))
        assertEquals(AccountRemovalPhase.ACCOUNT_REMOVED, checkpoints.load(targetScope, target))
        assertTrue(signOut.requiresRecovery())
        assertEquals(SignOutResult.SignedOut, signOut.signOut(SignOutChoice.CONFIRM))
        assertFalse(signOut.requiresRecovery())
        assertEquals(2, releases)
        assertEquals(1, sessionCleanups)
        assertEquals(1, platformRemovals)
    }

    @Test fun persistedRemovalCheckpointRestoresOnlyItsExactAccountIdentity() {
        val targetScope = AccountScope("persisted-cleanup-fixture")
        val target = Account("persisted:cleanup@example.test", ContakoAndroidAccountContract.ACCOUNT_TYPE)
        val original = SharedPreferencesAccountRemovalCheckpointStore(context)
        try {
            original.save(targetScope, target, AccountRemovalPhase.LOCAL_CLEARED)
            val restored = SharedPreferencesAccountRemovalCheckpointStore(context)
            assertEquals(target, restored.pendingAccount(targetScope))
            assertNull(restored.pendingAccount(AccountScope("unrelated-scope")))
            assertEquals(AccountRemovalPhase.LOCAL_CLEARED, restored.load(targetScope, target))
        } finally { original.clear(targetScope, target) }
    }

    @Test fun unprovisionedCheckpointPersistsOwnerAndPhaseWithoutAnAndroidAccount() {
        val targetScope = AccountScope("unprovisioned-checkpoint-fixture")
        val store = SharedPreferencesUnprovisionedRemovalCheckpointStore(context)
        try {
            store.save(targetScope, UnprovisionedRemovalCheckpoint("synthetic-owner", UnprovisionedRemovalPhase.OWNER_PENDING))
            val restored = SharedPreferencesUnprovisionedRemovalCheckpointStore(context)
            assertEquals("synthetic-owner", restored.load(targetScope)?.ownerId)
            assertEquals(UnprovisionedRemovalPhase.OWNER_PENDING, restored.load(targetScope)?.phase)
            assertNull(restored.load(AccountScope("unrelated-unprovisioned-scope")))
            assertEquals("UnprovisionedRemovalCheckpoint(REDACTED)", restored.load(targetScope).toString())
        } finally { store.clear(targetScope) }
    }

    @Test fun restoredCleanupCheckpointStopsAndAwaitsANewlyConstructedActiveRunner() = runBlocking {
        val targetScope = AccountScope("restarted-cleanup-runner")
        val target = Account("restarted-cleanup@example.test", ContakoAndroidAccountContract.ACCOUNT_TYPE)
        val ledger = RoomAndroidProjectionLedger(database)
        ledger.ensureAccount(targetScope).also { ledger.bindAndroidAccountName(targetScope, it.revision, target.name) }
        val checkpoint = AccountRemovalCheckpointStore.Memory().apply {
            save(targetScope, target, AccountRemovalPhase.PROJECTION_CLEARED)
        }
        val passStarted = CompletableDeferred<Unit>()
        val cancellationStarted = CompletableDeferred<Unit>()
        val releasePass = CompletableDeferred<Unit>()
        val passExited = CompletableDeferred<Unit>()
        var deletionStarted = false
        val freshRunner = AccountSyncRunner(scope) { request ->
            passStarted.complete(Unit)
            // AccountSyncRunner cancels cooperatively through its generation, not Job.cancel.
            while (!request.isAccountCancellationRequested()) delay(1)
            cancellationStarted.complete(Unit)
            releasePass.await()
            passExited.complete(Unit)
            SyncPassOutcome.CANCELLED
        }
        assertEquals(SyncRequestDisposition.STARTED, freshRunner.request(SyncTrigger.MANUAL))
        passStarted.await()
        val removal = AndroidAccountRemovalCoordinator(cleanCleaner(), database, targetScope,
            ProtonLocalSessionCleanupGateway { error("Session cleanup was already checkpointed") }, freshRunner,
            platformCleanup = object : AccountPlatformCleanup by AccountPlatformCleanup.None {
                override fun deleteScopedFiles(account: AccountScope) {
                    assertTrue(passExited.isCompleted)
                    deletionStarted = true
                }
            }, checkpoints = checkpoint)
        val operation = launch(Dispatchers.Default) { assertTrue(removal.removeFromApp(target, discardPendingChanges = true)) }
        cancellationStarted.await()
        assertFalse(deletionStarted)
        releasePass.complete(Unit)
        operation.join()
        assertTrue(deletionStarted)
        assertNull(checkpoint.load(targetScope, target))
        assertEquals(SyncRequestDisposition.ACCOUNT_INVALIDATED, freshRunner.request(SyncTrigger.RETRY))
    }

    private fun cleanCleaner() = object : AndroidAccountProjectionCleaner {
        override fun delete(accountName: String, accountType: String) = true
        override fun deleteIfClean(accountName: String, accountType: String) = true
        override fun hasPendingChanges(accountName: String, accountType: String) = false
    }

    @Test fun mismatchedAndroidAccountFailsClosedAndPreservesEverything() = runBlocking {
        val targetScope = AccountScope("room-target")
        val bound = Account("bound-removal@example.test", ContakoAndroidAccountContract.ACCOUNT_TYPE)
        val requested = Account("wrong-removal@example.test", ContakoAndroidAccountContract.ACCOUNT_TYPE)
        val ledger = RoomAndroidProjectionLedger(database)
        ledger.ensureAccount(targetScope).also { ledger.bindAndroidAccountName(targetScope, it.revision, bound.name) }
        val raw = insertRawContact(requested)
        var sessionCalled = false
        val session = Proxy.newProxyInstance(
            ProtonLocalSessionCleanupGateway::class.java.classLoader,
            arrayOf(ProtonLocalSessionCleanupGateway::class.java),
        ) { _, _, _ ->
            sessionCalled = true
            GatewayOutcome.Success(Unit)
        } as ProtonLocalSessionCleanupGateway
        val coordinator = AndroidAccountRemovalCoordinator(
            FrameworkAndroidAccountProjectionCleaner(context.contentResolver),
            database,
            targetScope,
            session,
            AccountSyncRunner(scope) { SyncPassOutcome.SUCCESS },
        )

        assertFalse(coordinator.remove(requested))
        assertTrue(rawContactExists(raw))
        assertEquals(bound.name, database.androidProjectionLedgerDao().getAccount(targetScope.value)?.androidAccountName)
        assertFalse(sessionCalled)
    }

    @Test fun api35AppSignOutRemovesExactAccountContactsGroupsAndSyncState() = runBlocking {
        val targetScope = AccountScope("room-target")
        val target = Account("target-signout@example.test", ContakoAndroidAccountContract.ACCOUNT_TYPE)
        val other = Account("other-signout@example.test", ContakoAndroidAccountContract.ACCOUNT_TYPE)
        val accountManager = AccountManager.get(context)
        assertTrue(accountManager.addAccountExplicitly(target, null, null))
        assertTrue(accountManager.addAccountExplicitly(other, null, null))
        val ledger = RoomAndroidProjectionLedger(database)
        ledger.ensureAccount(targetScope).also { ledger.bindAndroidAccountName(targetScope, it.revision, target.name) }
        val targetRaw = insertRawContact(target)
        val otherRaw = insertRawContact(other)
        val session = ProtonLocalSessionCleanupGateway { GatewayOutcome.Success(Unit) }
        val coordinator = AndroidAccountRemovalCoordinator(
            FrameworkAndroidAccountProjectionCleaner(context.contentResolver),
            database,
            targetScope,
            session,
            AccountSyncRunner(scope) { SyncPassOutcome.SUCCESS },
            FrameworkAccountPlatformCleanup(context) {},
        )

        try {
            assertTrue(coordinator.removeFromApp(target))
            assertFalse(accountManager.accounts.contains(target))
            assertTrue(accountManager.accounts.contains(other))
            assertFalse(rawContactExists(targetRaw))
            assertTrue(rawContactExists(otherRaw))
        } finally {
            accountManager.removeAccountExplicitly(target)
            accountManager.removeAccountExplicitly(other)
        }
    }

    @Test fun dirtyProviderIntentAtCleanupBoundaryPreservesSessionAndRestoresRunner() = runBlocking {
        val targetScope = AccountScope("dirty-removal")
        val target = Account("dirty-removal@example.test", ContakoAndroidAccountContract.ACCOUNT_TYPE)
        val ledger = RoomAndroidProjectionLedger(database)
        ledger.ensureAccount(targetScope).also { ledger.bindAndroidAccountName(targetScope, it.revision, target.name) }
        val raw = insertRawContact(target)
        context.contentResolver.update(
            syncAdapterUri(ContactsContract.RawContacts.CONTENT_URI, target),
            ContentValues().apply { put(ContactsContract.RawContacts.DIRTY, 1) },
            "_id = ?", arrayOf(raw.toString()),
        )
        var sessionCleared = false
        val runner = AccountSyncRunner(scope) { SyncPassOutcome.SUCCESS }
        val cleaner = FrameworkAndroidAccountProjectionCleaner(context.contentResolver)
        val coordinator = AndroidAccountRemovalCoordinator(
            cleaner, database, targetScope,
            ProtonLocalSessionCleanupGateway { sessionCleared = true; GatewayOutcome.Success(Unit) }, runner,
        )
        assertEquals(1, cleaner.pendingChanges(target.name, target.type))
        assertFalse(coordinator.removeFromApp(target))
        assertTrue(rawContactExists(raw))
        assertFalse(sessionCleared)
        assertEquals(SyncRequestDisposition.STARTED, runner.request(SyncTrigger.RETRY))
        runner.awaitIdle()
        assertTrue(coordinator.removeFromApp(target, discardPendingChanges = true))
        assertTrue(sessionCleared)
        assertFalse(rawContactExists(raw))
    }

    @Test fun pendingRoomWorkAtCleanupBoundaryPreservesCleanProvider() = runBlocking {
        val targetScope = AccountScope("pending-room-removal")
        val target = Account("pending-room@example.test", ContakoAndroidAccountContract.ACCOUNT_TYPE)
        val ledger = RoomAndroidProjectionLedger(database)
        ledger.ensureAccount(targetScope).also { ledger.bindAndroidAccountName(targetScope, it.revision, target.name) }
        seedAccountRoots(targetScope.value)
        val raw = insertRawContact(target)
        var sessionCleared = false
        val coordinator = AndroidAccountRemovalCoordinator(
            FrameworkAndroidAccountProjectionCleaner(context.contentResolver), database, targetScope,
            ProtonLocalSessionCleanupGateway { sessionCleared = true; GatewayOutcome.Success(Unit) },
            AccountSyncRunner(scope) { SyncPassOutcome.SUCCESS },
        )
        assertFalse(coordinator.removeFromApp(target))
        assertFalse(sessionCleared)
        assertTrue(rawContactExists(raw))
        assertTrue(database.accountRemovalDao().countPending(targetScope.value) > 0)
    }

    @Test fun partialCleanupRetryRefusesNewNativeIntent() = runBlocking {
        val targetScope = AccountScope("retry-removal")
        val target = Account("retry-removal@example.test", ContakoAndroidAccountContract.ACCOUNT_TYPE)
        val ledger = RoomAndroidProjectionLedger(database)
        ledger.ensureAccount(targetScope).also { ledger.bindAndroidAccountName(targetScope, it.revision, target.name) }
        val original = insertRawContact(target)
        var sessionCalls = 0
        val coordinator = AndroidAccountRemovalCoordinator(
            FrameworkAndroidAccountProjectionCleaner(context.contentResolver), database, targetScope,
            ProtonLocalSessionCleanupGateway { sessionCalls++; error("Synthetic session cleanup failure") },
            AccountSyncRunner(scope) { SyncPassOutcome.SUCCESS },
        )
        assertFalse(coordinator.removeFromApp(target))
        assertFalse(rawContactExists(original))
        val newRaw = insertRawContact(target)
        context.contentResolver.update(
            syncAdapterUri(ContactsContract.RawContacts.CONTENT_URI, target),
            ContentValues().apply { put(ContactsContract.RawContacts.DIRTY, 1) },
            "_id = ?", arrayOf(newRaw.toString()),
        )
        assertFalse(coordinator.removeFromApp(target))
        assertTrue(rawContactExists(newRaw))
        assertEquals(1, sessionCalls)
    }

    @Test fun dirtyGroupTombstonePreventsDeletionOfOtherwiseCleanContacts() {
        val target = Account("dirty-group@example.test", ContakoAndroidAccountContract.ACCOUNT_TYPE)
        val raw = insertRawContact(target)
        val groupUri = requireNotNull(context.contentResolver.insert(
            syncAdapterUri(ContactsContract.Groups.CONTENT_URI, target), ContentValues().apply {
                put(ContactsContract.Groups.ACCOUNT_NAME, target.name)
                put(ContactsContract.Groups.ACCOUNT_TYPE, target.type)
                put(ContactsContract.Groups.TITLE, "Synthetic tombstone")
                put(ContactsContract.Groups.DIRTY, 1)
                put(ContactsContract.Groups.DELETED, 1)
            },
        ))
        try {
            val cleaner = FrameworkAndroidAccountProjectionCleaner(context.contentResolver)
            assertEquals(1, cleaner.pendingChanges(target.name, target.type))
            assertFalse(cleaner.deleteIfClean(target.name, target.type))
            assertTrue(rawContactExists(raw))
        } finally {
            context.contentResolver.delete(syncAdapterUri(groupUri, target), null, null)
        }
    }

    private fun insertRawContact(account: Account): Long {
        val uri = context.contentResolver.insert(
            syncAdapterUri(ContactsContract.RawContacts.CONTENT_URI, account),
            ContentValues().apply {
                put(ContactsContract.RawContacts.ACCOUNT_NAME, account.name)
                put(ContactsContract.RawContacts.ACCOUNT_TYPE, account.type)
            },
        ) ?: error("Synthetic raw-contact insert failed")
        return ContentUris.parseId(uri).also { created += it to account }
    }

    private fun rawContactExists(id: Long): Boolean = context.contentResolver.query(
        ContactsContract.RawContacts.CONTENT_URI,
        arrayOf(ContactsContract.RawContacts._ID),
        "${ContactsContract.RawContacts._ID} = ?",
        arrayOf(id.toString()),
        null,
    )?.use { it.moveToFirst() } == true

    private fun seedAccountRoots(accountId: String) {
        val db = database.openHelper.writableDatabase
        db.execSQL(
            "INSERT INTO contacts (account_id, id, owner_key, first_name, last_name, display_name, " +
                "sort_name, revision, updated_at_epoch_millis, remote_contact_id, remote_vcard_uid, " +
                "remote_version, action_required_reasons, pending_mutation_revision, conflict_state, is_deleted) " +
                "VALUES (?, 'contact', ?, '', '', '', '', 0, 0, NULL, NULL, NULL, '', NULL, NULL, 0)",
            arrayOf(accountId, "$accountId:contact"),
        )
        db.execSQL(
            "INSERT INTO contact_groups (account_id, id, owner_key, name, color, display_order, " +
                "is_visible, revision, updated_at_epoch_millis, remote_label_id, remote_version, " +
                "pending_mutation_revision, conflict_state, is_deleted) " +
                "VALUES (?, 'group', ?, 'group', '', 0, 1, 0, 0, NULL, NULL, NULL, NULL, 0)",
            arrayOf(accountId, "$accountId:group"),
        )
        db.execSQL(
            "INSERT INTO outbox_mutations (account_id, aggregate_type, aggregate_id, operation, revision, " +
                "created_at_epoch_millis, updated_at_epoch_millis, attempt_count, next_attempt_at_epoch_millis, " +
                "idempotency_key, state, requires_reconciliation, device_elapsed_realtime_millis, " +
                "clock_jump_detected) VALUES (?, 'CONTACT', 'contact', 'UPSERT', 0, 0, 0, 0, 0, ?, " +
                "'PENDING', 0, 0, 0)",
            arrayOf(accountId, "$accountId:key"),
        )
        db.execSQL("INSERT INTO contact_inventory_checkpoints VALUES (?, 0)", arrayOf(accountId))
        db.execSQL(
            "INSERT INTO sync_account_status VALUES (?, 'IDLE', 0, NULL, 0, 0, NULL, NULL, NULL)",
            arrayOf(accountId),
        )
        db.execSQL(
            "INSERT INTO full_repair_progress VALUES (?, 0, 'STARTING', 0, 0, NULL, 0, 0, 0, 0)",
            arrayOf(accountId),
        )
    }

    private fun accountScopedTables(): List<String> {
        val db = database.openHelper.readableDatabase
        return db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'")
            .use { tables ->
                buildList {
                    while (tables.moveToNext()) {
                        val table = tables.getString(0)
                        db.query("PRAGMA table_info(`$table`)").use { columns ->
                            while (columns.moveToNext()) {
                                if (columns.getString(1) == "account_id") {
                                    add(table)
                                    break
                                }
                            }
                        }
                    }
                }
            }
    }

    private fun countRows(table: String, accountId: String): Long =
        database.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM `$table` WHERE account_id = ?",
            arrayOf(accountId),
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun syncAdapterUri(base: android.net.Uri, account: Account): android.net.Uri = base.buildUpon()
        .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_NAME, account.name)
        .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_TYPE, account.type)
        .build()

    private companion object {
        const val DATABASE_NAME = "account-removal-device-test.db"
    }
}
