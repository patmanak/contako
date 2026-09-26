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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AndroidRawContactLifecycleGatewayDeviceTest {
    private lateinit var context: Context
    private lateinit var accountManager: AccountManager
    private lateinit var primaryAccount: Account
    private lateinit var foreignAccount: Account

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        InstrumentationRegistry.getInstrumentation().uiAutomation.apply {
            grantRuntimePermission(context.packageName, Manifest.permission.READ_CONTACTS)
            grantRuntimePermission(context.packageName, Manifest.permission.WRITE_CONTACTS)
        }
        accountManager = AccountManager.get(context)
        val suffix = System.nanoTime().toString(36)
        primaryAccount = Account("contako-lifecycle-primary-$suffix", ContakoAndroidAccountContract.ACCOUNT_TYPE)
        foreignAccount = Account("contako-lifecycle-foreign-$suffix", ContakoAndroidAccountContract.ACCOUNT_TYPE)
        check(accountManager.addAccountExplicitly(primaryAccount, null, null))
        check(accountManager.addAccountExplicitly(foreignAccount, null, null))
    }

    @After
    fun tearDown() {
        if (::primaryAccount.isInitialized) deleteSyntheticAccountRows(primaryAccount)
        if (::foreignAccount.isInitialized) deleteSyntheticAccountRows(foreignAccount)
        if (::primaryAccount.isInitialized) accountManager.removeAccountExplicitly(primaryAccount)
        if (::foreignAccount.isInitialized) accountManager.removeAccountExplicitly(foreignAccount)
    }

    @Test
    fun createThenEnsureRecoversTheSameRawContactWithoutAggregationIdentity() {
        val gateway = gateway()
        val created = gateway.ensureOwnedRawContact(
            AndroidProviderAccountName(primaryAccount.name),
            CLAIM_CREATE,
            SOURCE_CREATE,
        ) as AndroidEnsureRawContactResult.Created
        val existing = gateway.ensureOwnedRawContact(
            AndroidProviderAccountName(primaryAccount.name),
            CLAIM_CREATE,
            SOURCE_CREATE,
        ) as AndroidEnsureRawContactResult.Existing

        assertEquals(created.handle, existing.handle)
        assertTrue(created.handle.hasSourceIdentity)
        assertEquals(1, countClaim(primaryAccount, CLAIM_CREATE))
        assertExactRawContact(created.handle.rawContactId, primaryAccount, CLAIM_CREATE, SOURCE_CREATE)
        assertFalse(rawContactDirty(created.handle.rawContactId))
        assertTrue(created.handle.toString().contains("REDACTED"))
        assertFalse(created.handle.toString().contains("rawContactId"))
    }

    @Test
    fun insertLostAcknowledgementRecoversTheCommittedClaimWithoutDuplicate() {
        var acknowledgementLost = false
        val gateway = AndroidRawContactLifecycleGateway(
            contentResolver = context.contentResolver,
            afterInsertCommitted = {
                acknowledgementLost = true
                throw IllegalStateException("synthetic lost acknowledgement")
            },
        )

        val recovered = gateway.ensureOwnedRawContact(
            AndroidProviderAccountName(primaryAccount.name),
            CLAIM_LOST_ACK,
            null,
        ) as AndroidEnsureRawContactResult.RecoveredAfterLostAcknowledgement

        assertTrue(acknowledgementLost)
        assertFalse(recovered.handle.hasSourceIdentity)
        assertEquals(1, countClaim(primaryAccount, CLAIM_LOST_ACK))
        assertExactRawContact(recovered.handle.rawContactId, primaryAccount, CLAIM_LOST_ACK, null)
        assertFalse(rawContactDirty(recovered.handle.rawContactId))
    }

    @Test
    fun concurrentEnsureCallsCreateExactlyOneRawContact() {
        val gateway = gateway()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = List(2) {
                pool.submit<AndroidEnsureRawContactResult> {
                    check(start.await(5, TimeUnit.SECONDS))
                    gateway.ensureOwnedRawContact(
                        AndroidProviderAccountName(primaryAccount.name),
                        CLAIM_CONCURRENT,
                        SOURCE_CREATE,
                    )
                }
            }
            start.countDown()
            val completed = results.map { it.get(10, TimeUnit.SECONDS) }

            assertEquals(1, completed.count { it is AndroidEnsureRawContactResult.Created })
            assertEquals(1, completed.count { it is AndroidEnsureRawContactResult.Existing })
            assertEquals(1, completed.map { it.handle.rawContactId }.distinct().size)
            assertEquals(1, countClaim(primaryAccount, CLAIM_CONCURRENT))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun ambiguousClaimAndSourceMismatchFailClosedWithRedactedDiagnostics() {
        insertRawContact(primaryAccount, CLAIM_AMBIGUOUS, SOURCE_CREATE)
        insertRawContact(primaryAccount, CLAIM_AMBIGUOUS, SOURCE_CREATE)
        val ambiguous = runCatching {
            gateway().ensureOwnedRawContact(
                AndroidProviderAccountName(primaryAccount.name),
                CLAIM_AMBIGUOUS,
                SOURCE_CREATE,
            )
        }.exceptionOrNull() as AndroidRawContactLifecycleException
        assertEquals(AndroidRawContactLifecycleFailure.AMBIGUOUS_CLAIM, ambiguous.category)

        insertRawContact(primaryAccount, CLAIM_SOURCE_MISMATCH, "different-source")
        val mismatch = runCatching {
            gateway().ensureOwnedRawContact(
                AndroidProviderAccountName(primaryAccount.name),
                CLAIM_SOURCE_MISMATCH,
                SOURCE_CREATE,
            )
        }.exceptionOrNull() as AndroidRawContactLifecycleException
        assertEquals(AndroidRawContactLifecycleFailure.SOURCE_IDENTITY_MISMATCH, mismatch.category)
        listOf(ambiguous, mismatch).forEach { failure ->
            assertFalse(failure.message.orEmpty().contains(CLAIM_AMBIGUOUS))
            assertFalse(failure.message.orEmpty().contains(CLAIM_SOURCE_MISMATCH))
            assertFalse(failure.message.orEmpty().contains(SOURCE_CREATE))
        }
    }

    @Test
    fun exactDeleteRemovesOnlyTheVersionedOwnedRawContact() {
        val ensured = gateway().ensureOwnedRawContact(
            AndroidProviderAccountName(primaryAccount.name),
            CLAIM_DELETE,
            SOURCE_DELETE,
        )

        assertEquals(
            AndroidDeleteRawContactResult.Deleted,
            gateway().deleteOwnedRawContact(
                AndroidProviderAccountName(primaryAccount.name),
                CLAIM_DELETE,
                ensured.handle.rawContactId,
                ensured.handle.version,
                AndroidExpectedSourceIdentity.Present(SOURCE_DELETE),
            ),
        )
        assertEquals(0, countClaim(primaryAccount, CLAIM_DELETE))
        assertEquals(
            AndroidDeleteRawContactResult.AbsentRequiresDurableIntent,
            gateway().deleteOwnedRawContact(
                AndroidProviderAccountName(primaryAccount.name),
                CLAIM_DELETE,
                ensured.handle.rawContactId,
                ensured.handle.version,
                AndroidExpectedSourceIdentity.Present(SOURCE_DELETE),
            ),
        )
    }

    @Test
    fun deleteLostAcknowledgementRecoversWithoutRecreatingOrDeletingAnythingElse() {
        val ensured = gateway().ensureOwnedRawContact(
            AndroidProviderAccountName(primaryAccount.name),
            CLAIM_DELETE_LOST_ACK,
            SOURCE_DELETE,
        )
        val gateway = AndroidRawContactLifecycleGateway(
            contentResolver = context.contentResolver,
            afterDeleteCommitted = { throw IllegalStateException("synthetic lost acknowledgement") },
        )

        assertEquals(
            AndroidDeleteRawContactResult.RecoveredAfterLostAcknowledgement,
            gateway.deleteOwnedRawContact(
                AndroidProviderAccountName(primaryAccount.name),
                CLAIM_DELETE_LOST_ACK,
                ensured.handle.rawContactId,
                ensured.handle.version,
                AndroidExpectedSourceIdentity.Present(SOURCE_DELETE),
            ),
        )
        assertEquals(0, countClaim(primaryAccount, CLAIM_DELETE_LOST_ACK))
    }

    @Test
    fun staleVersionAndForeignAccountDeleteNeverMutateTheirRawContacts() {
        val stale = gateway().ensureOwnedRawContact(
            AndroidProviderAccountName(primaryAccount.name),
            CLAIM_STALE,
            SOURCE_CREATE,
        )
        assertEquals(
            AndroidDeleteRawContactResult.AbsentRequiresDurableIntent,
            gateway().deleteOwnedRawContact(
                AndroidProviderAccountName(primaryAccount.name),
                "wrong-claim",
                stale.handle.rawContactId,
                stale.handle.version,
                AndroidExpectedSourceIdentity.Present(SOURCE_CREATE),
            ),
        )
        assertEquals(
            AndroidDeleteRawContactResult.Stale,
            gateway().deleteOwnedRawContact(
                AndroidProviderAccountName(primaryAccount.name),
                CLAIM_STALE,
                stale.handle.rawContactId,
                stale.handle.version,
                AndroidExpectedSourceIdentity.Present("wrong-source"),
            ),
        )
        assertExactRawContact(stale.handle.rawContactId, primaryAccount, CLAIM_STALE, SOURCE_CREATE)
        val dataUri = context.contentResolver.insert(
            ContactsContract.Data.CONTENT_URI,
            ContentValues().apply {
                put(ContactsContract.Data.RAW_CONTACT_ID, stale.handle.rawContactId)
                put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE)
                put(ContactsContract.CommonDataKinds.Note.NOTE, "synthetic concurrent edit")
            },
        )
        checkNotNull(dataUri)
        val currentVersion = rawContactVersion(stale.handle.rawContactId)
        assertNotEquals(stale.handle.version, currentVersion)
        assertEquals(
            AndroidDeleteRawContactResult.Stale,
            gateway().deleteOwnedRawContact(
                AndroidProviderAccountName(primaryAccount.name),
                CLAIM_STALE,
                stale.handle.rawContactId,
                stale.handle.version,
                AndroidExpectedSourceIdentity.Present(SOURCE_CREATE),
            ),
        )
        assertExactRawContact(stale.handle.rawContactId, primaryAccount, CLAIM_STALE, SOURCE_CREATE)

        val foreign = gateway().ensureOwnedRawContact(
            AndroidProviderAccountName(foreignAccount.name),
            CLAIM_FOREIGN,
            SOURCE_FOREIGN,
        )
        assertEquals(
            AndroidDeleteRawContactResult.AbsentRequiresDurableIntent,
            gateway().deleteOwnedRawContact(
                AndroidProviderAccountName(primaryAccount.name),
                CLAIM_FOREIGN,
                foreign.handle.rawContactId,
                foreign.handle.version,
                AndroidExpectedSourceIdentity.Present(SOURCE_FOREIGN),
            ),
        )
        assertExactRawContact(foreign.handle.rawContactId, foreignAccount, CLAIM_FOREIGN, SOURCE_FOREIGN)
    }

    private fun gateway() = AndroidRawContactLifecycleGateway(context.contentResolver)

    private fun insertRawContact(account: Account, claim: String, sourceIdentity: String?): Long {
        val uri = context.contentResolver.insert(
            syncAdapterUri(account),
            ContentValues().apply {
                put(ContactsContract.RawContacts.ACCOUNT_NAME, account.name)
                put(ContactsContract.RawContacts.ACCOUNT_TYPE, account.type)
                put(ContactsContract.RawContacts.SYNC1, claim)
                put(ContactsContract.RawContacts.DIRTY, 0)
                if (sourceIdentity == null) putNull(ContactsContract.RawContacts.SOURCE_ID)
                else put(ContactsContract.RawContacts.SOURCE_ID, sourceIdentity)
            },
        ) ?: error("Synthetic raw-contact insert failed")
        return ContentUris.parseId(uri)
    }

    private fun countClaim(account: Account, claim: String): Int = context.contentResolver.query(
        ContactsContract.RawContacts.CONTENT_URI,
        arrayOf(ContactsContract.RawContacts._ID),
        "${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND " +
            "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND " +
            "${ContactsContract.RawContacts.SYNC1} = ? AND ${ContactsContract.RawContacts.DELETED} = 0",
        arrayOf(account.name, account.type, claim),
        null,
    )?.use { cursor ->
        var count = 0
        while (cursor.moveToNext()) count += 1
        count
    } ?: error("ContactsProvider unavailable")

    private fun assertExactRawContact(
        rawContactId: Long,
        account: Account,
        claim: String,
        sourceIdentity: String?,
    ) {
        val sourceClause = if (sourceIdentity == null) {
            "${ContactsContract.RawContacts.SOURCE_ID} IS NULL"
        } else {
            "${ContactsContract.RawContacts.SOURCE_ID} = ?"
        }
        val args = buildList {
            add(rawContactId.toString())
            add(account.name)
            add(account.type)
            add(claim)
            if (sourceIdentity != null) add(sourceIdentity)
        }.toTypedArray()
        val count = context.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID),
            "${ContactsContract.RawContacts._ID} = ? AND " +
                "${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND " +
                "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND " +
                "${ContactsContract.RawContacts.SYNC1} = ? AND $sourceClause",
            args,
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) 1 else 0 } ?: 0
        assertEquals(1, count)
    }

    private fun rawContactVersion(rawContactId: Long): Long = context.contentResolver.query(
        ContactsContract.RawContacts.CONTENT_URI,
        arrayOf(ContactsContract.RawContacts.VERSION),
        "${ContactsContract.RawContacts._ID} = ?",
        arrayOf(rawContactId.toString()),
        null,
    )?.use { cursor ->
        check(cursor.moveToFirst())
        cursor.getLong(0)
    } ?: error("ContactsProvider unavailable")

    private fun rawContactDirty(rawContactId: Long): Boolean = context.contentResolver.query(
        ContactsContract.RawContacts.CONTENT_URI,
        arrayOf(ContactsContract.RawContacts.DIRTY),
        "${ContactsContract.RawContacts._ID} = ?",
        arrayOf(rawContactId.toString()),
        null,
    )?.use { cursor ->
        check(cursor.moveToFirst())
        cursor.getInt(0) != 0
    } ?: error("ContactsProvider unavailable")

    private fun deleteSyntheticAccountRows(account: Account) {
        context.contentResolver.delete(
            syncAdapterUri(account),
            "${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND " +
                "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ?",
            arrayOf(account.name, account.type),
        )
    }

    private fun syncAdapterUri(account: Account) = ContactsContract.RawContacts.CONTENT_URI.buildUpon()
        .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_NAME, account.name)
        .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_TYPE, account.type)
        .build()

    private companion object {
        const val CLAIM_CREATE = "canonical-create"
        const val CLAIM_LOST_ACK = "canonical-lost-ack"
        const val CLAIM_CONCURRENT = "canonical-concurrent"
        const val CLAIM_AMBIGUOUS = "canonical-ambiguous"
        const val CLAIM_SOURCE_MISMATCH = "canonical-source-mismatch"
        const val CLAIM_DELETE = "canonical-delete"
        const val CLAIM_DELETE_LOST_ACK = "canonical-delete-lost-ack"
        const val CLAIM_STALE = "canonical-stale"
        const val CLAIM_FOREIGN = "canonical-foreign"
        const val SOURCE_CREATE = "remote-create"
        const val SOURCE_DELETE = "remote-delete"
        const val SOURCE_FOREIGN = "remote-foreign"
    }
}
