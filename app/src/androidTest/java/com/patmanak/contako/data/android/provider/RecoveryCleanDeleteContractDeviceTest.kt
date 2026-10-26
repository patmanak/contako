package com.patmanak.contako.data.android.provider

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Controlled resolver only: no Android account or system contact is read or created. */
@RunWith(AndroidJUnit4::class)
class RecoveryCleanDeleteContractDeviceTest {
    @Test fun dirtySourceAtItsCurrentVersionIsNeverDeleted() {
        val provider = OwnedFixtureProvider(dirty = true)
        assertEquals(AndroidDeleteRawContactResult.Stale, execute(provider))
        assertEquals(0, provider.deleteCalls)
        assertTrue(provider.present)
    }

    @Test fun dirtyTransitionBetweenReadAndDeleteIsRejectedByAtomicPredicate() {
        val provider = OwnedFixtureProvider(dirty = false, dirtyBeforeDelete = true)
        assertEquals(AndroidDeleteRawContactResult.Stale, execute(provider))
        assertEquals(1, provider.deleteCalls)
        assertTrue(provider.present)
        assertTrue(provider.dirty)
    }

    @Test fun exactCleanOwnedSourceCanCompleteThePreparedDelete() {
        val provider = OwnedFixtureProvider(dirty = false)
        assertEquals(AndroidDeleteRawContactResult.Deleted, execute(provider))
        assertFalse(provider.present)
        assertEquals(1, provider.deleteCalls)
    }

    private fun execute(provider: OwnedFixtureProvider) = AndroidRawContactLifecycleGateway(ContentResolver.wrap(provider))
        .deleteOwnedRawContact(AndroidProviderAccountName("fixture-account"), "fixture-contact", 41, 7,
            AndroidExpectedSourceIdentity.Present("fixture-source"), requireClean = true)

    private class OwnedFixtureProvider(var dirty: Boolean, private val dirtyBeforeDelete: Boolean = false) : ContentProvider() {
        var present = true
        var deleteCalls = 0
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor = MatrixCursor(projection ?: emptyArray()).apply {
            if (present) addRow((projection ?: emptyArray()).map { column -> when (column) {
                ContactsContract.RawContacts._ID -> 41L
                ContactsContract.RawContacts.SOURCE_ID -> "fixture-source"
                ContactsContract.RawContacts.VERSION -> 7L
                ContactsContract.RawContacts.DIRTY -> if (dirty) 1 else 0
                else -> error("Unexpected fixture query")
            } })
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            deleteCalls++
            assertTrue(selection.orEmpty().contains("${ContactsContract.RawContacts.DIRTY} = 0"))
            assertTrue(selection.orEmpty().contains("${ContactsContract.RawContacts.VERSION} = ?"))
            assertTrue(selection.orEmpty().contains("${ContactsContract.RawContacts.ACCOUNT_NAME} = ?"))
            assertTrue(selection.orEmpty().contains("${ContactsContract.RawContacts.ACCOUNT_TYPE} = ?"))
            assertTrue(selection.orEmpty().contains("${ContactsContract.RawContacts.SYNC1} = ?"))
            assertTrue(selection.orEmpty().contains("${ContactsContract.RawContacts.SOURCE_ID} = ?"))
            assertEquals(listOf("41", "fixture-account", com.patmanak.contako.android.account.ContakoAndroidAccountContract.ACCOUNT_TYPE,
                "7", "fixture-contact", "fixture-source"), selectionArgs?.toList())
            if (dirtyBeforeDelete) dirty = true
            if (!present || dirty) return 0
            present = false
            return 1
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("No insert permitted")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("No update permitted")
    }
}
