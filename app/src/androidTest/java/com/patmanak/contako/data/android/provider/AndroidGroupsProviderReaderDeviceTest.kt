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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidGroupsProviderReaderDeviceTest {
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
    fun groupCatalogIsPagedByExactAccountAndPreservesNullableClaimsAndDuplicateTitles() {
        val firstId = insertGroup(primaryAccount, "Same", canonicalId = null, sourceId = null)
        val secondId = insertGroup(primaryAccount, "Same", canonicalId = "canonical-b", sourceId = "remote-b")
        insertGroup(foreignAccount, "Same", canonicalId = "foreign", sourceId = "foreign-remote")
        val reader = AndroidGroupsProviderReader(context.contentResolver)

        val firstPage = reader.readGroupPage(AndroidProviderAccountName(primaryAccount.name), limit = 1)
        val secondPage = reader.readGroupPage(
            AndroidProviderAccountName(primaryAccount.name),
            afterGroupRowId = requireNotNull(firstPage.nextAfterGroupRowId),
            limit = 1,
        )
        val observed = firstPage.groups + secondPage.groups

        assertEquals(listOf(firstId, secondId), observed.map(AndroidOwnedGroupRow::groupRowId))
        assertEquals(listOf("Same", "Same"), observed.map(AndroidOwnedGroupRow::title))
        assertNull(observed.first().canonicalGroupIdClaim)
        assertNull(observed.first().sourceIdentity)
        assertEquals("canonical-b", observed.last().canonicalGroupIdClaim)
        assertEquals("remote-b", observed.last().sourceIdentity)
        assertFalse(observed.first().toString().contains("Same"))
    }

    @Test
    fun membershipReadIsBoundedToTheExactOwnedRawContact() {
        val groupId = insertGroup(primaryAccount, "Primary", "canonical", "remote")
        val rawContactId = insertRawContact(primaryAccount, "contact-source")
        val foreignRawContactId = insertRawContact(foreignAccount, "foreign-source")
        val membershipId = insertMembership(rawContactId, groupId)
        val reader = AndroidGroupsProviderReader(context.contentResolver)

        assertEquals(
            listOf(AndroidOwnedGroupMembershipRow(membershipId, rawContactId, groupId)),
            reader.readMembershipRows(AndroidProviderAccountName(primaryAccount.name), rawContactId),
        )
        val failure = requireNotNull(
            runCatching {
                reader.readMembershipRows(AndroidProviderAccountName(primaryAccount.name), foreignRawContactId)
            }.exceptionOrNull() as? AndroidProviderBoundaryException,
        )
        assertEquals(AndroidProviderFailureCategory.ACCOUNT_SCOPE_MISMATCH, failure.category)
        assertTrue(failure.message.orEmpty().contains("ACCOUNT_SCOPE_MISMATCH"))
    }

    @Test
    fun blankPresentIdentityClaimFailsInsteadOfEnteringUnadoptedFlow() {
        insertGroup(primaryAccount, "Malformed", canonicalId = "", sourceId = null)
        val reader = AndroidGroupsProviderReader(context.contentResolver)

        val failure = requireNotNull(
            runCatching {
                reader.readGroupPage(AndroidProviderAccountName(primaryAccount.name))
            }.exceptionOrNull() as? AndroidProviderBoundaryException,
        )
        assertEquals(AndroidProviderFailureCategory.MALFORMED_PROVIDER_DATA, failure.category)
        assertFalse(failure.toString().contains("Malformed"))
    }

    @Test
    fun tombstoneIsExcludedByDefaultAndVisibleOnlyWhenExplicitlyRequested() {
        val groupId = insertGroup(primaryAccount, "Deleted", "canonical-deleted", "remote-deleted")
        assertEquals(
            1,
            context.contentResolver.delete(
                ContactsContract.Groups.CONTENT_URI,
                "${ContactsContract.Groups._ID} = ?",
                arrayOf(groupId.toString()),
            ),
        )
        val reader = AndroidGroupsProviderReader(context.contentResolver)

        assertTrue(reader.readGroupPage(AndroidProviderAccountName(primaryAccount.name)).groups.isEmpty())
        val tombstone = reader.readGroupPage(
            AndroidProviderAccountName(primaryAccount.name),
            includeDeleted = true,
        ).groups.single()
        assertEquals(groupId, tombstone.groupRowId)
        assertTrue(tombstone.deleted)
    }

    private fun insertGroup(
        account: Account,
        title: String,
        canonicalId: String?,
        sourceId: String?,
    ): Long {
        val uri = requireNotNull(
            context.contentResolver.insert(
                syncAdapterUri(ContactsContract.Groups.CONTENT_URI, account),
                ContentValues().apply {
                    put(ContactsContract.Groups.ACCOUNT_NAME, account.name)
                    put(ContactsContract.Groups.ACCOUNT_TYPE, account.type)
                    put(ContactsContract.Groups.TITLE, title)
                    put(ContactsContract.Groups.GROUP_VISIBLE, 1)
                    put(ContactsContract.Groups.SHOULD_SYNC, 1)
                    canonicalId?.let { put(ContactsContract.Groups.SYNC1, it) }
                    sourceId?.let { put(ContactsContract.Groups.SOURCE_ID, it) }
                },
            ),
        )
        return ContentUris.parseId(uri)
    }

    private fun insertRawContact(account: Account, sourceId: String): Long {
        val uri = requireNotNull(
            context.contentResolver.insert(
                syncAdapterUri(ContactsContract.RawContacts.CONTENT_URI, account),
                ContentValues().apply {
                    put(ContactsContract.RawContacts.ACCOUNT_NAME, account.name)
                    put(ContactsContract.RawContacts.ACCOUNT_TYPE, account.type)
                    put(ContactsContract.RawContacts.SOURCE_ID, sourceId)
                },
            ),
        )
        return ContentUris.parseId(uri)
    }

    private fun insertMembership(rawContactId: Long, groupRowId: Long): Long {
        val uri = requireNotNull(
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
        return ContentUris.parseId(uri)
    }

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
        .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_NAME, account.name)
        .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_TYPE, account.type)
        .build()
}
