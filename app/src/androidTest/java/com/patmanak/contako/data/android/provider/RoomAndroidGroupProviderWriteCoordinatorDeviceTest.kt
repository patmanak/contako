package com.patmanak.contako.data.android.provider

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentUris
import android.content.ContentValues
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.contako.android.account.ContakoAndroidAccountContract
import com.patmanak.contako.data.android.AndroidAdoptionState
import com.patmanak.contako.data.android.AndroidIngestionState
import com.patmanak.contako.data.android.AndroidProjectionWriteState
import com.patmanak.contako.data.android.AndroidTombstoneState
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipAvailability
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipSnapshot
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipSnapshotBinaryCodec
import com.patmanak.contako.data.local.AndroidGroupMembershipBaselineEntity
import com.patmanak.contako.data.local.AndroidGroupMembershipProjectionLedgerEntity
import com.patmanak.contako.data.local.AndroidGroupProjectionLedgerEntity
import com.patmanak.contako.data.local.AndroidGroupProviderWriteCommitResult
import com.patmanak.contako.data.local.AndroidGroupProviderWritePreparation
import com.patmanak.contako.data.local.AndroidProjectionAccountEntity
import com.patmanak.contako.data.local.AndroidProjectionLedgerEntity
import com.patmanak.contako.data.local.ContactGroupEntity
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.data.local.RoomAndroidGroupProviderWriteJournal
import com.patmanak.contako.data.local.RoomContactRepository
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.repository.SaveResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class RoomAndroidGroupProviderWriteCoordinatorDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var account: Account
    private lateinit var accountManager: AccountManager
    private lateinit var database: ContakoDatabase
    private lateinit var databaseName: String

    @Before
    fun setUp() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_CONTACTS)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.WRITE_CONTACTS)
        val suffix = System.nanoTime().toString(36)
        account = Account("contako-group-journal-$suffix", ContakoAndroidAccountContract.ACCOUNT_TYPE)
        accountManager = AccountManager.get(context)
        check(accountManager.addAccountExplicitly(account, null, null))
        databaseName = "group-provider-journal-$suffix.db"
        database = ContakoDatabase.create(context, databaseName)
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) runCatching { database.close() }
        if (::account.isInitialized) runCatching { clearProviderAccount() }
        if (::account.isInitialized) runCatching { accountManager.removeAccountExplicitly(account) }
        if (::databaseName.isInitialized) context.deleteDatabase(databaseName)
    }

    @Test
    fun createSurvivesRestartRetainsCompletionProofAndDoesNotReplayProviderWrite() = runBlocking {
        seedCanonicalAndLedger(isDeleted = false, locator = null, tombstone = AndroidTombstoneState.NONE)
        val authorization = createAuthorization()
        val coordinator = RoomAndroidGroupProviderWriteCoordinator(database, context.contentResolver)
        val first = coordinator.execute(authorization) { gateway ->
            gateway.ensureOwnedGroup(
                authorization.context,
                authorization.accountName,
                GROUP_ID,
                REMOTE_ID,
                TITLE,
                true,
            )
        }
        assertEquals(AndroidGroupProviderWriteExecutionResult.Completed(1), first)
        val providerRow = exactProviderRow()
        assertFalse(providerRow.deleted)
        assertFalse(providerRow.readOnly)
        assertTrue(providerRow.shouldSync)

        val ledger = requireNotNull(database.androidGroupProjectionDao().getGroup(ACCOUNT_ID, GROUP_ID))
        assertEquals(1L, ledger.revision)
        assertEquals(providerRow.groupRowId, ledger.groupRowLocator)
        assertEquals(AndroidProjectionWriteState.CLEAN.name, ledger.projectionState)
        assertNotNull(database.androidGroupProjectionDao().getGroupBaseline(ACCOUNT_ID, GROUP_ID))
        val proof = requireNotNull(
            database.androidGroupProjectionDao().getGroupProviderWriteJournal(ACCOUNT_ID, GROUP_ID),
        )
        assertEquals("COMMITTED", proof.state)
        assertEquals(1L, proof.completedAccountRevision)
        assertEquals(1L, proof.completedGroupLedgerRevision)

        database.close()
        database = ContakoDatabase.create(context, databaseName)
        val afterRestart = RoomAndroidGroupProviderWriteCoordinator(database, context.contentResolver)
            .execute(authorization) { error("Provider mutation must not replay after durable completion") }
        assertEquals(AndroidGroupProviderWriteExecutionResult.AlreadyCompleted(1), afterRestart)
        assertEquals(1, providerRows().size)
    }

    @Test
    fun canonicalTombstoneCanDeleteAnActiveProviderRowBeforeAnyProviderDeletedFlagExists() = runBlocking {
        val rowId = insertProviderGroup()
        val observed = providerRows().single()
        assertEquals(rowId, observed.groupRowId)
        assertFalse(observed.deleted)
        seedCanonicalAndLedger(
            isDeleted = true,
            locator = rowId,
            tombstone = AndroidTombstoneState.CANONICAL_COMMITTED,
        )
        val authorization = deleteAuthorization(observed)
        val result = RoomAndroidGroupProviderWriteCoordinator(database, context.contentResolver)
            .execute(authorization) { gateway ->
                gateway.deleteOwnedGroup(
                    authorization.context,
                    authorization.accountName,
                    authorization.canonicalGroupId,
                    requireNotNull(authorization.expectedGroupRowId),
                    requireNotNull(authorization.expectedProviderVersion),
                    authorization.expectedSourceIdentity,
                    expectedDeleted = false,
                )
            }
        assertEquals(AndroidGroupProviderWriteExecutionResult.Completed(1), result)
        assertTrue(providerRows(includeDeleted = true).isEmpty())
        val ledger = requireNotNull(database.androidGroupProjectionDao().getGroup(ACCOUNT_ID, GROUP_ID))
        assertEquals(AndroidProjectionWriteState.DETACHED.name, ledger.projectionState)
        assertEquals(null, ledger.groupRowLocator)
    }

    @Test
    fun unverifiedOrOperationInconsistentProviderOutcomeCannotBecomeCommitted() = runBlocking {
        seedCanonicalAndLedger(isDeleted = false, locator = null, tombstone = AndroidTombstoneState.NONE)
        val authorization = createAuthorization()
        val journal = RoomAndroidGroupProviderWriteJournal(database)
        assertEquals(
            AndroidGroupProviderWritePreparation.Stale,
            journal.prepare(authorization.copy(accountName = AndroidProviderAccountName("wrong-account"))),
        )
        assertEquals(
            AndroidGroupProviderWritePreparation.Stale,
            journal.prepare(authorization.copy(sourceIdentityAfterWrite = "wrong-remote")),
        )
        assertTrue(journal.prepare(authorization) is AndroidGroupProviderWritePreparation.Prepared)
        val inconsistent = AndroidVerifiedGroupProviderPostState.Absent("a".repeat(64))
        assertEquals(
            AndroidGroupProviderWriteCommitResult.Stale,
            journal.markProviderCommitted(authorization, inconsistent),
        )
        assertEquals(
            "PREPARED",
            database.androidGroupProjectionDao()
                .getGroupProviderWriteJournal(ACCOUNT_ID, GROUP_ID)?.state,
        )
        assertTrue(journal.markRepairRequired(authorization))
        assertEquals(
            "REPAIR_REQUIRED",
            database.androidGroupProjectionDao()
                .getGroupProviderWriteJournal(ACCOUNT_ID, GROUP_ID)?.state,
        )
        assertTrue(journal.prepare(authorization) is AndroidGroupProviderWritePreparation.AlreadyPrepared)
        assertEquals(
            "PREPARED",
            database.androidGroupProjectionDao()
                .getGroupProviderWriteJournal(ACCOUNT_ID, GROUP_ID)?.state,
        )
    }

    @Test
    fun differentCommandCannotReplaceAnUnclassifiedPreparedProof() = runBlocking {
        seedCanonicalAndLedger(isDeleted = false, locator = null, tombstone = AndroidTombstoneState.NONE)
        val journal = RoomAndroidGroupProviderWriteJournal(database)
        assertTrue(journal.prepare(createAuthorization()) is AndroidGroupProviderWritePreparation.Prepared)

        val canonical = requireNotNull(database.contactGroupDao().get(ACCOUNT_ID, GROUP_ID)).group
        database.contactGroupDao().upsert(canonical.copy(revision = 2, name = UPDATED_TITLE))
        assertEquals(
            1,
            database.androidProjectionLedgerDao().compareAndSetAccountRevisionAtProviderEpoch(
                ACCOUNT_ID,
                expectedRevision = 0,
                expectedProviderEpoch = 0,
            ),
        )
        assertEquals(1, database.androidGroupProjectionDao().compareAndSetGroupRevision(ACCOUNT_ID, GROUP_ID, 0))

        val replacement = createAuthorization().copy(
            context = AndroidGroupWriteContext(
                accountId = ACCOUNT_ID,
                providerEpoch = 0,
                expectedAccountRevision = 1,
                expectedCanonicalGroupRevision = 2,
                expectedGroupLedgerRevision = 1,
            ),
            desiredTitle = UPDATED_TITLE,
        )
        assertEquals(AndroidGroupProviderWritePreparation.Busy, journal.prepare(replacement))
        assertEquals(
            0L,
            requireNotNull(
                database.androidGroupProjectionDao().getGroupProviderWriteJournal(ACCOUNT_ID, GROUP_ID),
            ).expectedAccountRevision,
        )
    }

    @Test
    fun deleteFailsClosedUntilProjectedContactHasVerifiedMembershipBaseline() = runBlocking {
        val rowId = insertProviderGroup()
        val observed = providerRows().single()
        seedCanonicalAndLedger(
            isDeleted = true,
            locator = rowId,
            tombstone = AndroidTombstoneState.CANONICAL_COMMITTED,
        )
        val repository = RoomContactRepository(database, clock = { 1L }, elapsedRealtimeClock = { 1L })
        check(repository.saveContact(CanonicalContact(ACCOUNT_ID, CONTACT_ID, displayName = "Synthetic")) is SaveResult.Saved)
        check(
            database.androidProjectionLedgerDao().insert(
                AndroidProjectionLedgerEntity(
                    accountId = ACCOUNT_ID,
                    canonicalContactId = CONTACT_ID,
                    revision = 0,
                    providerEpoch = 0,
                    rawContactLocator = CONTACT_RAW_ROW,
                    sourceIdentity = null,
                    canonicalProjectionFingerprint = null,
                    androidBaselineFingerprint = null,
                    pendingProjectionFingerprint = null,
                    observedAndroidFingerprint = null,
                    projectionState = AndroidProjectionWriteState.CLEAN.name,
                    ingestionState = AndroidIngestionState.BASELINED.name,
                    tombstoneState = AndroidTombstoneState.NONE.name,
                    adoptionState = AndroidAdoptionState.AWAITING_REMOTE_ID.name,
                ),
            ) != -1L,
        )
        val authorization = deleteAuthorization(observed)
        val journal = RoomAndroidGroupProviderWriteJournal(database)
        assertEquals(AndroidGroupProviderWritePreparation.Stale, journal.prepare(authorization))

        val snapshot = AndroidGroupMembershipSnapshot.create(
            accountId = ACCOUNT_ID,
            canonicalContactId = CONTACT_ID,
            preferredEmailValueId = null,
            membershipAvailability = AndroidGroupMembershipAvailability.NO_EMAIL,
            locatorMappings = emptyList(),
        )
        val semantic = snapshot.semanticFingerprint().sha256Hex
        check(
            database.androidGroupProjectionDao().insertMembership(
                AndroidGroupMembershipProjectionLedgerEntity(
                    accountId = ACCOUNT_ID,
                    canonicalContactId = CONTACT_ID,
                    revision = 0,
                    providerEpoch = 0,
                    rawContactLocator = CONTACT_RAW_ROW,
                    preferredEmailValueId = null,
                    canonicalProjectionFingerprint = semantic,
                    androidBaselineFingerprint = semantic,
                    pendingProjectionFingerprint = null,
                    projectionState = AndroidProjectionWriteState.CLEAN.name,
                    ingestionState = AndroidIngestionState.BASELINED.name,
                ),
            ) != -1L,
        )
        assertEquals(AndroidGroupProviderWritePreparation.Stale, journal.prepare(authorization))

        val encoded = AndroidGroupMembershipSnapshotBinaryCodec.encode(snapshot)
        database.androidGroupProjectionDao().upsertMembershipBaseline(
            AndroidGroupMembershipBaselineEntity(
                accountId = ACCOUNT_ID,
                canonicalContactId = CONTACT_ID,
                fingerprint = "0".repeat(64),
                encodedSnapshot = encoded,
            ),
        )
        assertEquals(AndroidGroupProviderWritePreparation.Stale, journal.prepare(authorization))

        database.androidGroupProjectionDao().upsertMembershipBaseline(
            AndroidGroupMembershipBaselineEntity(
                accountId = ACCOUNT_ID,
                canonicalContactId = CONTACT_ID,
                fingerprint = AndroidGroupMembershipSnapshotBinaryCodec.integrityFingerprint(encoded).sha256Hex,
                encodedSnapshot = encoded,
            ),
        )
        assertTrue(journal.prepare(authorization) is AndroidGroupProviderWritePreparation.Prepared)
    }

    private suspend fun seedCanonicalAndLedger(
        isDeleted: Boolean,
        locator: Long?,
        tombstone: AndroidTombstoneState,
    ) {
        database.contactGroupDao().upsert(
            ContactGroupEntity(
                accountId = ACCOUNT_ID,
                id = GROUP_ID,
                ownerKey = "$ACCOUNT_ID:$GROUP_ID",
                name = TITLE,
                color = "violet",
                displayOrder = 0,
                isVisible = true,
                revision = 1,
                updatedAtEpochMillis = 1,
                remoteLabelId = REMOTE_ID,
                remoteVersion = "1",
                pendingMutationRevision = null,
                conflictState = null,
                isDeleted = isDeleted,
            ),
        )
        check(
            database.androidProjectionLedgerDao().insertAccount(
                AndroidProjectionAccountEntity(
                    ACCOUNT_ID,
                    revision = 0,
                    providerEpoch = 0,
                    androidAccountName = account.name,
                ),
            ) != -1L,
        )
        check(
            database.androidGroupProjectionDao().insertGroup(
                AndroidGroupProjectionLedgerEntity(
                    accountId = ACCOUNT_ID,
                    canonicalGroupId = GROUP_ID,
                    revision = 0,
                    providerEpoch = 0,
                    groupRowLocator = locator,
                    providerVersion = locator?.let { providerRows(includeDeleted = true)
                        .singleOrNull { row -> row.groupRowId == it }?.version },
                    sourceIdentity = REMOTE_ID,
                    canonicalProjectionFingerprint = null,
                    androidBaselineFingerprint = null,
                    pendingProjectionFingerprint = null,
                    projectionState = if (tombstone == AndroidTombstoneState.NONE) {
                        AndroidProjectionWriteState.REPAIR_REQUIRED.name
                    } else {
                        AndroidProjectionWriteState.DETACHED.name
                    },
                    ingestionState = AndroidIngestionState.NONE.name,
                    tombstoneState = tombstone.name,
                    adoptionState = if (locator == null) {
                        AndroidAdoptionState.SOURCE_ID_PENDING.name
                    } else {
                        AndroidAdoptionState.ADOPTED.name
                    },
                ),
            ) != -1L,
        )
    }

    private fun createAuthorization() = AndroidGroupWriteAuthorization(
        context = writeContext(),
        accountName = AndroidProviderAccountName(account.name),
        canonicalGroupId = GROUP_ID,
        operation = AndroidGroupProviderOperation.CREATE,
        expectedGroupRowId = null,
        expectedProviderVersion = null,
        expectedSourceIdentity = AndroidExpectedSourceIdentity.Present(REMOTE_ID),
        sourceIdentityAfterWrite = REMOTE_ID,
        expectedDeleted = false,
        desiredTitle = TITLE,
        desiredVisibility = true,
    )

    private fun deleteAuthorization(row: AndroidOwnedGroupRow) = AndroidGroupWriteAuthorization(
        context = writeContext(),
        accountName = AndroidProviderAccountName(account.name),
        canonicalGroupId = GROUP_ID,
        operation = AndroidGroupProviderOperation.DELETE,
        expectedGroupRowId = row.groupRowId,
        expectedProviderVersion = row.version,
        expectedSourceIdentity = AndroidExpectedSourceIdentity.Present(REMOTE_ID),
        sourceIdentityAfterWrite = REMOTE_ID,
        expectedDeleted = false,
        desiredTitle = null,
        desiredVisibility = null,
    )

    private fun writeContext() = AndroidGroupWriteContext(
        accountId = ACCOUNT_ID,
        providerEpoch = 0,
        expectedAccountRevision = 0,
        expectedCanonicalGroupRevision = 1,
        expectedGroupLedgerRevision = 0,
    )

    private fun insertProviderGroup(): Long = ContentUris.parseId(
        requireNotNull(
            context.contentResolver.insert(
                syncAdapterUri(ContactsContract.Groups.CONTENT_URI),
                ContentValues().apply {
                    put(ContactsContract.Groups.ACCOUNT_NAME, account.name)
                    put(ContactsContract.Groups.ACCOUNT_TYPE, account.type)
                    put(ContactsContract.Groups.SYNC1, GROUP_ID)
                    put(ContactsContract.Groups.SOURCE_ID, REMOTE_ID)
                    put(ContactsContract.Groups.TITLE, TITLE)
                    put(ContactsContract.Groups.GROUP_VISIBLE, 1)
                    put(ContactsContract.Groups.SHOULD_SYNC, 1)
                    put(ContactsContract.Groups.GROUP_IS_READ_ONLY, 0)
                    put(ContactsContract.Groups.DIRTY, 0)
                },
            ),
        ),
    )

    private fun exactProviderRow(): AndroidOwnedGroupRow =
        providerRows(includeDeleted = true).single { it.canonicalGroupIdClaim == GROUP_ID }

    private fun providerRows(includeDeleted: Boolean = false): List<AndroidOwnedGroupRow> =
        AndroidGroupsProviderReader(context.contentResolver).readGroupPage(
            AndroidProviderAccountName(account.name),
            limit = 100,
            includeDeleted = includeDeleted,
        ).groups

    private fun clearProviderAccount() {
        context.contentResolver.delete(
            syncAdapterUri(ContactsContract.Groups.CONTENT_URI),
            "${ContactsContract.Groups.ACCOUNT_NAME} = ? AND ${ContactsContract.Groups.ACCOUNT_TYPE} = ?",
            arrayOf(account.name, account.type),
        )
    }

    private fun syncAdapterUri(base: android.net.Uri): android.net.Uri = base.buildUpon()
        .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(ContactsContract.Groups.ACCOUNT_NAME, account.name)
        .appendQueryParameter(ContactsContract.Groups.ACCOUNT_TYPE, account.type)
        .build()

    private companion object {
        const val ACCOUNT_ID = "canonical-account"
        const val GROUP_ID = "group-a"
        const val REMOTE_ID = "remote-a"
        const val TITLE = "Journalled group"
        const val UPDATED_TITLE = "Updated journalled group"
        const val CONTACT_ID = "contact-with-membership-ledger"
        const val CONTACT_RAW_ROW = 9_001L
    }
}
