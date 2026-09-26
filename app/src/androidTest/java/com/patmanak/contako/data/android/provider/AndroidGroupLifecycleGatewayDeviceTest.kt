package com.patmanak.contako.data.android.provider

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.contako.android.account.ContakoAndroidAccountContract
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidGroupLifecycleGatewayDeviceTest {
    private lateinit var context: Context
    private lateinit var accountManager: AccountManager
    private lateinit var primaryAccount: Account
    private lateinit var foreignAccount: Account

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_CONTACTS)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.WRITE_CONTACTS)
        accountManager = AccountManager.get(context)
        val suffix = System.nanoTime().toString(36)
        primaryAccount = Account("contako-provider-primary-$suffix", ContakoAndroidAccountContract.ACCOUNT_TYPE)
        foreignAccount = Account("contako-provider-foreign-$suffix", ContakoAndroidAccountContract.ACCOUNT_TYPE)
        check(accountManager.addAccountExplicitly(primaryAccount, null, null))
        check(accountManager.addAccountExplicitly(foreignAccount, null, null))
    }

    @After
    fun tearDown() {
        if (::primaryAccount.isInitialized) runCatching { clearAccount(primaryAccount) }
        if (::foreignAccount.isInitialized) runCatching { clearAccount(foreignAccount) }
        if (::primaryAccount.isInitialized) runCatching { accountManager.removeAccountExplicitly(primaryAccount) }
        if (::foreignAccount.isInitialized) runCatching { accountManager.removeAccountExplicitly(foreignAccount) }
    }

    @Test
    fun createUpdateAndNoChangeKeepTheGroupEditableAndExactlyScoped() = runBlocking {
        val gateway = gateway()
        val created = gateway.ensureOwnedGroup(
            WRITE_CONTEXT,
            accountName(primaryAccount),
            GROUP_A,
            REMOTE_A,
            "Same title",
            isVisible = true,
        )
        assertTrue(created is AndroidEnsureGroupResult.Created)
        val createdHandle = created.handle
        val row = exactRow(primaryAccount, GROUP_A)
        assertEquals(createdHandle.groupRowId, row.groupRowId)
        assertEquals(REMOTE_A, row.sourceIdentity)
        assertEquals("Same title", row.title)
        assertTrue(row.visible)
        assertTrue(row.shouldSync)
        assertFalse(row.readOnly)
        assertFalse(row.dirty)

        val existing = gateway.ensureOwnedGroup(
            WRITE_CONTEXT,
            accountName(primaryAccount),
            GROUP_A,
            REMOTE_A,
            "Ignored until projection",
            isVisible = false,
        )
        assertTrue(existing is AndroidEnsureGroupResult.Existing)
        assertEquals(createdHandle.groupRowId, existing.handle.groupRowId)

        val updated = gateway.applyProjection(
            WRITE_CONTEXT,
            accountName(primaryAccount),
            GROUP_A,
            existing.handle.groupRowId,
            existing.handle.version,
            AndroidExpectedSourceIdentity.Present(REMOTE_A),
            sourceIdentityAfterWrite = REMOTE_A,
            title = "Renamed",
            isVisible = false,
        )
        assertTrue(updated is AndroidProjectGroupResult.Applied)
        val updatedHandle = (updated as AndroidProjectGroupResult.Applied).handle
        assertNotEquals(existing.handle.version, updatedHandle.version)
        val renamed = exactRow(primaryAccount, GROUP_A)
        assertEquals("Renamed", renamed.title)
        assertFalse(renamed.visible)
        assertFalse(renamed.readOnly)
        assertFalse(renamed.dirty)

        assertTrue(
            gateway.applyProjection(
                WRITE_CONTEXT,
                accountName(primaryAccount),
                GROUP_A,
                updatedHandle.groupRowId,
                updatedHandle.version,
                AndroidExpectedSourceIdentity.Present(REMOTE_A),
                sourceIdentityAfterWrite = REMOTE_A,
                title = "Renamed",
                isVisible = false,
            ) is AndroidProjectGroupResult.NoChangeValidated,
        )
        assertEquals(updatedHandle.version, exactRow(primaryAccount, GROUP_A).version)
    }

    @Test
    fun defaultDenyAndSuspendingAuthorizerPreventEveryProviderMutation() = runBlocking {
        val denied = AndroidGroupLifecycleGateway(context.contentResolver)
        val failure = requireNotNull(
            runCatching {
                denied.ensureOwnedGroup(
                    WRITE_CONTEXT,
                    accountName(primaryAccount),
                    GROUP_A,
                    REMOTE_A,
                    "Denied",
                    true,
                )
            }.exceptionOrNull() as? AndroidGroupLifecycleException,
        )
        assertEquals(AndroidGroupLifecycleFailure.AUTHORIZATION_STALE, failure.category)
        assertTrue(rows(primaryAccount).isEmpty())

        val cancellation = CancellationException("EXPECTED_TEST_CANCELLATION")
        val cancelling = gateway(authorizer = AndroidGroupWriteAuthorizer { throw cancellation })
        assertTrue(
            runCatching {
                cancelling.ensureOwnedGroup(
                    WRITE_CONTEXT,
                    accountName(primaryAccount),
                    GROUP_A,
                    REMOTE_A,
                    "Cancelled",
                    true,
                )
            }.exceptionOrNull() === cancellation,
        )
        assertTrue(rows(primaryAccount).isEmpty())
    }

    @Test
    fun authorizerReceivesExactClaimAndCanRejectAStaleUpdateWithoutMutation() = runBlocking {
        val authorizations = mutableListOf<AndroidGroupWriteAuthorization>()
        val gateway = gateway(
            authorizer = AndroidGroupWriteAuthorizer { authorization ->
                authorizations += authorization
                authorization.operation != AndroidGroupProviderOperation.UPDATE
            },
        )
        val created = gateway.ensureOwnedGroup(
            WRITE_CONTEXT,
            accountName(primaryAccount),
            GROUP_A,
            REMOTE_A,
            "Original",
            true,
        ).handle

        assertEquals(
            AndroidProjectGroupResult.ReplanRequired,
            gateway.applyProjection(
                WRITE_CONTEXT,
                accountName(primaryAccount),
                GROUP_A,
                created.groupRowId,
                created.version,
                AndroidExpectedSourceIdentity.Present(REMOTE_A),
                REMOTE_A,
                "Rejected",
                false,
            ),
        )
        assertEquals("Original", exactRow(primaryAccount, GROUP_A).title)
        val updateClaim = authorizations.single { it.operation == AndroidGroupProviderOperation.UPDATE }
        assertEquals(WRITE_CONTEXT, updateClaim.context)
        assertEquals(created.groupRowId, updateClaim.expectedGroupRowId)
        assertEquals(created.version, updateClaim.expectedProviderVersion)
        assertFalse(updateClaim.toString().contains(GROUP_A))
    }

    @Test
    fun unclaimedSystemGroupIsAdoptedInPlaceThenReceivesAnImmutableRemoteIdentity() = runBlocking {
        val rowId = insertGroup(primaryAccount, "System created", canonicalId = null, sourceId = null, sync = false)
        val observed = rows(primaryAccount).single { it.groupRowId == rowId }
        val gateway = gateway()

        val adopted = gateway.adoptUnclaimedGroup(
            WRITE_CONTEXT,
            accountName(primaryAccount),
            rowId,
            observed.version,
            GROUP_A,
        )
        assertTrue(adopted is AndroidAdoptGroupResult.Adopted)
        val adoptedHandle = (adopted as AndroidAdoptGroupResult.Adopted).handle
        assertEquals(rowId, adoptedHandle.groupRowId)
        assertFalse(adoptedHandle.hasSourceIdentity)
        val claimed = exactRow(primaryAccount, GROUP_A)
        assertFalse(claimed.readOnly)
        assertFalse(claimed.dirty)

        val remoteAdoption = gateway.applyProjection(
            WRITE_CONTEXT,
            accountName(primaryAccount),
            GROUP_A,
            adoptedHandle.groupRowId,
            adoptedHandle.version,
            AndroidExpectedSourceIdentity.Missing,
            sourceIdentityAfterWrite = REMOTE_A,
            title = "System created",
            isVisible = claimed.visible,
        )
        assertTrue(remoteAdoption is AndroidProjectGroupResult.Applied)
        val remoteHandle = (remoteAdoption as AndroidProjectGroupResult.Applied).handle
        assertTrue(remoteHandle.hasSourceIdentity)
        assertEquals(REMOTE_A, exactRow(primaryAccount, GROUP_A).sourceIdentity)

        val replacementFailure = requireNotNull(
            runCatching {
                gateway.applyProjection(
                    WRITE_CONTEXT,
                    accountName(primaryAccount),
                    GROUP_A,
                    remoteHandle.groupRowId,
                    remoteHandle.version,
                    AndroidExpectedSourceIdentity.Present(REMOTE_A),
                    sourceIdentityAfterWrite = "replacement",
                    title = "System created",
                    isVisible = claimed.visible,
                )
            }.exceptionOrNull() as? AndroidGroupLifecycleException,
        )
        assertEquals(AndroidGroupLifecycleFailure.SOURCE_IDENTITY_MISMATCH, replacementFailure.category)
        assertEquals(REMOTE_A, exactRow(primaryAccount, GROUP_A).sourceIdentity)
    }

    @Test
    fun lostReturnsRecoverCreateUpdateAndDeleteWithoutDuplicateRows() = runBlocking {
        val lostCreate = gateway(afterInsert = { error("LOST_CREATE_RETURN") })
        val created = lostCreate.ensureOwnedGroup(
            WRITE_CONTEXT,
            accountName(primaryAccount),
            GROUP_A,
            REMOTE_A,
            "Created",
            true,
        )
        assertTrue(created is AndroidEnsureGroupResult.RecoveredAfterLostAcknowledgement)
        assertEquals(1, rows(primaryAccount).count { it.canonicalGroupIdClaim == GROUP_A })

        val lostUpdate = gateway(afterUpdate = { error("LOST_UPDATE_RETURN") })
        val updated = lostUpdate.applyProjection(
            WRITE_CONTEXT,
            accountName(primaryAccount),
            GROUP_A,
            created.handle.groupRowId,
            created.handle.version,
            AndroidExpectedSourceIdentity.Present(REMOTE_A),
            REMOTE_A,
            "Updated",
            false,
        )
        assertTrue(updated is AndroidProjectGroupResult.RecoveredAfterLostAcknowledgement)
        val updatedHandle = (updated as AndroidProjectGroupResult.RecoveredAfterLostAcknowledgement).handle
        assertEquals("Updated", exactRow(primaryAccount, GROUP_A).title)

        val lostDelete = gateway(afterDelete = { error("LOST_DELETE_RETURN") })
        assertEquals(
            AndroidDeleteGroupResult.RecoveredAfterLostAcknowledgement,
            lostDelete.deleteOwnedGroup(
                WRITE_CONTEXT,
                accountName(primaryAccount),
                GROUP_A,
                updatedHandle.groupRowId,
                updatedHandle.version,
                AndroidExpectedSourceIdentity.Present(REMOTE_A),
                expectedDeleted = false,
            ),
        )
        assertTrue(rows(primaryAccount, includeDeleted = true).none { it.canonicalGroupIdClaim == GROUP_A })
    }

    @Test
    fun duplicateTitlesRemainIndependentAndStaleOrForeignClaimsNeverMutate() = runBlocking {
        val gateway = gateway()
        val first = gateway.ensureOwnedGroup(
            WRITE_CONTEXT,
            accountName(primaryAccount),
            GROUP_A,
            REMOTE_A,
            "Duplicate",
            true,
        ).handle
        val second = gateway.ensureOwnedGroup(
            WRITE_CONTEXT,
            accountName(primaryAccount),
            GROUP_B,
            REMOTE_B,
            "Duplicate",
            true,
        ).handle
        insertGroup(foreignAccount, "Duplicate", GROUP_A, REMOTE_A, sync = true)

        assertEquals(
            AndroidProjectGroupResult.ReplanRequired,
            gateway.applyProjection(
                WRITE_CONTEXT,
                accountName(primaryAccount),
                GROUP_A,
                first.groupRowId,
                first.version + 1,
                AndroidExpectedSourceIdentity.Present(REMOTE_A),
                REMOTE_A,
                "Stale",
                true,
            ),
        )
        assertEquals("Duplicate", exactRow(primaryAccount, GROUP_A).title)
        assertEquals("Duplicate", exactRow(primaryAccount, GROUP_B).title)
        assertEquals("Duplicate", exactRow(foreignAccount, GROUP_A).title)

        assertEquals(
            AndroidDeleteGroupResult.Deleted,
            gateway.deleteOwnedGroup(
                WRITE_CONTEXT,
                accountName(primaryAccount),
                GROUP_A,
                first.groupRowId,
                first.version,
                AndroidExpectedSourceIdentity.Present(REMOTE_A),
                expectedDeleted = false,
            ),
        )
        assertEquals(second.groupRowId, exactRow(primaryAccount, GROUP_B).groupRowId)
        assertNotNull(exactRow(foreignAccount, GROUP_A))
    }

    @Test
    fun ordinaryDeleteTombstoneAndSyncAdapterPurgeNeverDeleteTheRawContact() = runBlocking {
        val group = gateway().ensureOwnedGroup(
            WRITE_CONTEXT,
            accountName(primaryAccount),
            GROUP_A,
            REMOTE_A,
            "Populated",
            true,
        ).handle
        val rawContactId = insertRawContact(primaryAccount)
        insertMembership(rawContactId, group.groupRowId)

        assertEquals(
            1,
            context.contentResolver.delete(
                ContactsContract.Groups.CONTENT_URI,
                "${ContactsContract.Groups._ID} = ?",
                arrayOf(group.groupRowId.toString()),
            ),
        )
        val tombstone = rows(primaryAccount, includeDeleted = true).single()
        assertTrue(tombstone.deleted)
        assertEquals(1, rawContactCount(rawContactId))
        // ContactsProvider removes membership Data rows as soon as the ordinary delete creates
        // the group tombstone; only the RawContact survives for later reconciliation.
        assertEquals(0, membershipCount(rawContactId, group.groupRowId))

        assertEquals(
            AndroidDeleteGroupResult.Deleted,
            gateway().deleteOwnedGroup(
                WRITE_CONTEXT,
                accountName(primaryAccount),
                GROUP_A,
                tombstone.groupRowId,
                tombstone.version,
                AndroidExpectedSourceIdentity.Present(REMOTE_A),
                expectedDeleted = true,
            ),
        )
        assertTrue(rows(primaryAccount, includeDeleted = true).isEmpty())
        assertEquals(1, rawContactCount(rawContactId))
        assertEquals(0, membershipCount(rawContactId, group.groupRowId))
    }

    private fun gateway(
        afterInsert: () -> Unit = {},
        afterUpdate: () -> Unit = {},
        afterDelete: () -> Unit = {},
        authorizer: AndroidGroupWriteAuthorizer = AndroidGroupWriteAuthorizer { true },
    ) = AndroidGroupLifecycleGateway(
        context.contentResolver,
        afterInsertCommitted = afterInsert,
        afterUpdateCommitted = afterUpdate,
        afterDeleteCommitted = afterDelete,
        writeAuthorizer = authorizer,
    )

    private fun rows(account: Account, includeDeleted: Boolean = false): List<AndroidOwnedGroupRow> =
        AndroidGroupsProviderReader(context.contentResolver).readGroupPage(
            accountName(account),
            limit = 100,
            includeDeleted = includeDeleted,
        ).groups

    private fun exactRow(account: Account, canonicalId: String): AndroidOwnedGroupRow =
        rows(account, includeDeleted = true).single { it.canonicalGroupIdClaim == canonicalId }

    private fun accountName(account: Account) = AndroidProviderAccountName(account.name)

    private fun insertGroup(
        account: Account,
        title: String,
        canonicalId: String?,
        sourceId: String?,
        sync: Boolean,
    ): Long {
        val base = ContactsContract.Groups.CONTENT_URI
        val uri = requireNotNull(
            context.contentResolver.insert(
                if (sync) syncAdapterUri(base, account) else base,
                ContentValues().apply {
                    put(ContactsContract.Groups.ACCOUNT_NAME, account.name)
                    put(ContactsContract.Groups.ACCOUNT_TYPE, account.type)
                    put(ContactsContract.Groups.TITLE, title)
                    put(ContactsContract.Groups.GROUP_VISIBLE, 1)
                    put(ContactsContract.Groups.SHOULD_SYNC, 1)
                    put(ContactsContract.Groups.GROUP_IS_READ_ONLY, 0)
                    canonicalId?.let { put(ContactsContract.Groups.SYNC1, it) }
                    sourceId?.let { put(ContactsContract.Groups.SOURCE_ID, it) }
                },
            ),
        )
        return ContentUris.parseId(uri)
    }

    private fun insertRawContact(account: Account): Long {
        val uri = requireNotNull(
            context.contentResolver.insert(
                syncAdapterUri(ContactsContract.RawContacts.CONTENT_URI, account),
                ContentValues().apply {
                    put(ContactsContract.RawContacts.ACCOUNT_NAME, account.name)
                    put(ContactsContract.RawContacts.ACCOUNT_TYPE, account.type)
                    put(ContactsContract.RawContacts.SYNC1, "contact")
                    put(ContactsContract.RawContacts.SOURCE_ID, "remote-contact")
                },
            ),
        )
        return ContentUris.parseId(uri)
    }

    private fun insertMembership(rawContactId: Long, groupRowId: Long) {
        requireNotNull(
            context.contentResolver.insert(
                ContactsContract.Data.CONTENT_URI,
                ContentValues().apply {
                    put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                    put(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE,
                    )
                    put(ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID, groupRowId)
                },
            ),
        )
    }

    private fun rawContactCount(rawContactId: Long): Int = context.contentResolver.query(
        ContactsContract.RawContacts.CONTENT_URI,
        arrayOf(ContactsContract.RawContacts._ID),
        "${ContactsContract.RawContacts._ID} = ?",
        arrayOf(rawContactId.toString()),
        null,
    )?.use { it.count } ?: 0

    private fun membershipCount(rawContactId: Long, groupRowId: Long): Int = context.contentResolver.query(
        ContactsContract.Data.CONTENT_URI,
        arrayOf(ContactsContract.Data._ID),
        "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND " +
            "${ContactsContract.Data.MIMETYPE} = ? AND " +
            "${ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID} = ?",
        arrayOf(
            rawContactId.toString(),
            ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE,
            groupRowId.toString(),
        ),
        null,
    )?.use { it.count } ?: 0

    private fun clearAccount(account: Account) {
        context.contentResolver.delete(
            syncAdapterUri(ContactsContract.RawContacts.CONTENT_URI, account),
            "${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND ${ContactsContract.RawContacts.ACCOUNT_TYPE} = ?",
            arrayOf(account.name, account.type),
        )
        context.contentResolver.delete(
            syncAdapterUri(ContactsContract.Groups.CONTENT_URI, account),
            "${ContactsContract.Groups.ACCOUNT_NAME} = ? AND ${ContactsContract.Groups.ACCOUNT_TYPE} = ?",
            arrayOf(account.name, account.type),
        )
    }

    private fun syncAdapterUri(base: android.net.Uri, account: Account): android.net.Uri = base.buildUpon()
        .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(ContactsContract.Groups.ACCOUNT_NAME, account.name)
        .appendQueryParameter(ContactsContract.Groups.ACCOUNT_TYPE, account.type)
        .build()

    private companion object {
        const val GROUP_A = "group-a"
        const val GROUP_B = "group-b"
        const val REMOTE_A = "remote-a"
        const val REMOTE_B = "remote-b"
        val WRITE_CONTEXT = AndroidGroupWriteContext(
            accountId = "canonical-account",
            providerEpoch = 0,
            expectedAccountRevision = 0,
            expectedCanonicalGroupRevision = 1,
            expectedGroupLedgerRevision = 0,
        )
    }
}
