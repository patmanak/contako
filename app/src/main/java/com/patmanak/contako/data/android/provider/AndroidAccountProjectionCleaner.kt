package com.patmanak.contako.data.android.provider

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.provider.ContactsContract

internal fun interface AndroidAccountProjectionCleaner {
    fun delete(accountName: String, accountType: String): Boolean

    // Implementations without an atomic provider guard MUST refuse non-discard cleanup.
    fun deleteIfClean(accountName: String, accountType: String): Boolean = false
    fun hasPendingChanges(accountName: String, accountType: String): Boolean = true
}

internal class FrameworkAndroidAccountProjectionCleaner(
    private val contentResolver: ContentResolver,
) : AndroidAccountProjectionCleaner {
    override fun delete(accountName: String, accountType: String): Boolean =
        deleteProjection(accountName, accountType, requireClean = false)

    override fun deleteIfClean(accountName: String, accountType: String): Boolean =
        deleteProjection(accountName, accountType, requireClean = true)

    override fun hasPendingChanges(accountName: String, accountType: String): Boolean =
        pendingChanges(accountName, accountType) != 0

    /** Counts contact/group intent, including tombstones, without reading any contact values. */
    fun pendingChanges(accountName: String, accountType: String): Int =
        listOf(ContactsContract.RawContacts.CONTENT_URI, ContactsContract.Groups.CONTENT_URI).sumOf { uri ->
            requireNotNull(contentResolver.query(
                uri.buildUpon().appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build(),
                arrayOf("_id"),
                "account_name = ? AND account_type = ? AND dirty = 1",
                arrayOf(accountName, accountType),
                null,
            )) { "Android pending changes unavailable" }.use { it.count }
        }

    private fun deleteProjection(accountName: String, accountType: String, requireClean: Boolean): Boolean = try {
        val selection = "${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND " +
            "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ?"
        val args = arrayOf(accountName, accountType)
        val uris = arrayOf(
            ContactsContract.RawContacts.CONTENT_URI,
            ContactsContract.Groups.CONTENT_URI,
            ContactsContract.SyncState.CONTENT_URI,
        ).map { uri ->
            uri.buildUpon()
                .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_NAME, accountName)
                .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_TYPE, accountType)
                .build()
        }
        val operations = arrayListOf<ContentProviderOperation>()
        if (requireClean) {
            // Assertions and deletion share one non-yielding ContactsProvider transaction.
            // A native write racing the UI's last check causes the whole batch to roll back.
            uris.take(2).forEach { uri ->
                operations += ContentProviderOperation.newAssertQuery(uri)
                    .withSelection("$selection AND dirty = 1", args)
                    .withExpectedCount(0)
                    .build()
            }
        }
        operations += uris.map { uri ->
            ContentProviderOperation.newDelete(uri).withSelection(selection, args).build()
        }
        contentResolver.applyBatch(
            ContactsContract.AUTHORITY,
            operations,
        )
        true
    } catch (_: Exception) {
        false
    }
}
