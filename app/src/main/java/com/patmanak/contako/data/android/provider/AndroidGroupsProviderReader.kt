package com.patmanak.contako.data.android.provider

import android.content.ContentResolver
import android.database.Cursor
import android.provider.ContactsContract
import com.patmanak.contako.android.account.ContakoAndroidAccountContract
import java.nio.charset.StandardCharsets

/** Account-scoped provider row. Both stable claims are nullable before adoption. */
internal data class AndroidOwnedGroupRow(
    val groupRowId: Long,
    val canonicalGroupIdClaim: String?,
    val sourceIdentity: String?,
    val title: String,
    val dirty: Boolean,
    val deleted: Boolean,
    val visible: Boolean,
    val shouldSync: Boolean,
    val readOnly: Boolean = false,
    val version: Long,
) {
    init {
        require(groupRowId > 0)
        require(canonicalGroupIdClaim == null || canonicalGroupIdClaim.isNotBlank())
        require(sourceIdentity == null || sourceIdentity.isNotBlank())
        require(version >= 0)
    }

    override fun toString(): String =
        "AndroidOwnedGroupRow(REDACTED, dirty=$dirty, deleted=$deleted, " +
            "visible=$visible, shouldSync=$shouldSync, version=$version)"
}

internal data class AndroidOwnedGroupRowPage(
    val accountName: AndroidProviderAccountName,
    val requestedAfterGroupRowId: Long,
    val groups: List<AndroidOwnedGroupRow>,
    val nextAfterGroupRowId: Long?,
) {
    init {
        require(requestedAfterGroupRowId >= 0)
        require(groups.all { it.groupRowId > requestedAfterGroupRowId })
        require(groups.zipWithNext().all { (left, right) -> left.groupRowId < right.groupRowId })
        require(nextAfterGroupRowId == null || nextAfterGroupRowId == groups.lastOrNull()?.groupRowId)
    }
}

/** Raw membership locator. It has no canonical meaning until resolved through a complete catalog. */
internal data class AndroidOwnedGroupMembershipRow(
    val dataRowId: Long,
    val rawContactId: Long,
    val groupRowId: Long,
) {
    init {
        require(dataRowId > 0)
        require(rawContactId > 0)
        require(groupRowId > 0)
    }

    override fun toString(): String = "AndroidOwnedGroupMembershipRow(REDACTED)"
}

/**
 * Bounded read-only boundary for Android Groups and GroupMembership rows.
 *
 * This class deliberately cannot acknowledge or mutate provider state. Group membership writes
 * must join the contact writer's single raw-contact version-guarded batch.
 */
internal class AndroidGroupsProviderReader(
    private val contentResolver: ContentResolver,
) {
    fun readGroupPage(
        accountName: AndroidProviderAccountName,
        afterGroupRowId: Long = 0,
        limit: Int = DEFAULT_PAGE_SIZE,
        includeDeleted: Boolean = false,
    ): AndroidOwnedGroupRowPage {
        require(afterGroupRowId >= 0)
        require(limit in 1..MAX_PAGE_SIZE)
        val requested = Math.addExact(limit, 1)
        val selection = buildString {
            append("${ContactsContract.Groups.ACCOUNT_NAME} = ?")
            append(" AND ${ContactsContract.Groups.ACCOUNT_TYPE} = ?")
            append(" AND ${ContactsContract.Groups._ID} > ?")
            if (!includeDeleted) append(" AND ${ContactsContract.Groups.DELETED} = 0")
        }
        val groups = query(
            uri = ContactsContract.Groups.CONTENT_URI.buildUpon()
                .appendQueryParameter(ContactsContract.LIMIT_PARAM_KEY, requested.toString())
                .build(),
            projection = GROUP_PROJECTION,
            selection = selection,
            selectionArgs = arrayOf(
                accountName.value,
                ContakoAndroidAccountContract.ACCOUNT_TYPE,
                afterGroupRowId.toString(),
            ),
            sortOrder = "${ContactsContract.Groups._ID} ASC",
            maximumRows = requested,
        ) { cursor -> cursor.toOwnedGroup() }
        val hasMore = groups.size > limit
        val retained = if (hasMore) groups.take(limit) else groups
        return AndroidOwnedGroupRowPage(
            accountName = accountName,
            requestedAfterGroupRowId = afterGroupRowId,
            groups = retained,
            nextAfterGroupRowId = retained.lastOrNull()?.groupRowId?.takeIf { hasMore },
        )
    }

    /**
     * Diagnostic isolated read only. A synchronization coordinator MUST instead consume the
     * GroupMembership rows routed from the same version-guarded Data observation as contact rows,
     * so contact and membership state cannot be assembled from torn provider reads.
     */
    fun readMembershipRows(
        accountName: AndroidProviderAccountName,
        rawContactId: Long,
    ): List<AndroidOwnedGroupMembershipRow> {
        require(rawContactId > 0)
        assertOwnedRawContact(accountName, rawContactId)
        val rows = query(
            uri = ContactsContract.Data.CONTENT_URI.buildUpon()
                .appendQueryParameter(ContactsContract.LIMIT_PARAM_KEY, (MAX_ROWS_PER_CONTACT + 1).toString())
                .build(),
            projection = MEMBERSHIP_PROJECTION,
            selection = "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND " +
                "${ContactsContract.Data.MIMETYPE} = ?",
            selectionArgs = arrayOf(
                rawContactId.toString(),
                ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE,
            ),
            sortOrder = "${ContactsContract.Data._ID} ASC",
            maximumRows = MAX_ROWS_PER_CONTACT + 1,
        ) { cursor -> cursor.toOwnedMembership() }
        if (rows.size > MAX_ROWS_PER_CONTACT) fail(AndroidProviderFailureCategory.BOUND_EXCEEDED)
        if (rows.map(AndroidOwnedGroupMembershipRow::dataRowId).distinct().size != rows.size) {
            fail(AndroidProviderFailureCategory.MALFORMED_PROVIDER_DATA)
        }
        return rows
    }

    private fun assertOwnedRawContact(accountName: AndroidProviderAccountName, rawContactId: Long) {
        val count = query(
            uri = ContactsContract.RawContacts.CONTENT_URI,
            projection = arrayOf(ContactsContract.RawContacts._ID),
            selection = "${ContactsContract.RawContacts._ID} = ? AND " +
                "${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND " +
                "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ?",
            selectionArgs = arrayOf(
                rawContactId.toString(),
                accountName.value,
                ContakoAndroidAccountContract.ACCOUNT_TYPE,
            ),
            sortOrder = null,
            maximumRows = 2,
        ) { 1 }.sum()
        if (count != 1) fail(AndroidProviderFailureCategory.ACCOUNT_SCOPE_MISMATCH)
    }

    private fun Cursor.toOwnedGroup() = AndroidOwnedGroupRow(
        groupRowId = getLong(getColumnIndexOrThrow(ContactsContract.Groups._ID)),
        canonicalGroupIdClaim = boundedOptionalIdentity(ContactsContract.Groups.SYNC1),
        sourceIdentity = boundedOptionalIdentity(ContactsContract.Groups.SOURCE_ID),
        title = boundedString(ContactsContract.Groups.TITLE, MAX_TITLE_BYTES).orEmpty(),
        dirty = getInt(getColumnIndexOrThrow(ContactsContract.Groups.DIRTY)) != 0,
        deleted = getInt(getColumnIndexOrThrow(ContactsContract.Groups.DELETED)) != 0,
        visible = getInt(getColumnIndexOrThrow(ContactsContract.Groups.GROUP_VISIBLE)) != 0,
        shouldSync = getInt(getColumnIndexOrThrow(ContactsContract.Groups.SHOULD_SYNC)) != 0,
        readOnly = getInt(getColumnIndexOrThrow(ContactsContract.Groups.GROUP_IS_READ_ONLY)) != 0,
        version = getLong(getColumnIndexOrThrow(ContactsContract.Groups.VERSION)),
    )

    private fun Cursor.toOwnedMembership() = AndroidOwnedGroupMembershipRow(
        dataRowId = getLong(getColumnIndexOrThrow(ContactsContract.Data._ID)),
        rawContactId = getLong(getColumnIndexOrThrow(ContactsContract.Data.RAW_CONTACT_ID)),
        groupRowId = getLong(
            getColumnIndexOrThrow(ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID),
        ),
    )

    private fun Cursor.boundedString(column: String, maximumBytes: Int): String? {
        val index = getColumnIndexOrThrow(column)
        if (isNull(index)) return null
        val value = getString(index)
        if (value.toByteArray(StandardCharsets.UTF_8).size > maximumBytes) {
            fail(AndroidProviderFailureCategory.BOUND_EXCEEDED)
        }
        return value
    }

    private fun Cursor.boundedOptionalIdentity(column: String): String? {
        val value = boundedString(column, MAX_ID_BYTES) ?: return null
        if (value.isBlank()) fail(AndroidProviderFailureCategory.MALFORMED_PROVIDER_DATA)
        return value
    }

    private fun <T> query(
        uri: android.net.Uri,
        projection: Array<String>,
        selection: String,
        selectionArgs: Array<String>,
        sortOrder: String?,
        maximumRows: Int,
        transform: (Cursor) -> T,
    ): List<T> = try {
        contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    if (size == maximumRows) break
                    add(transform(cursor))
                }
            }
        } ?: fail(AndroidProviderFailureCategory.PROVIDER_UNAVAILABLE)
    } catch (_: SecurityException) {
        fail(AndroidProviderFailureCategory.PERMISSION_DENIED)
    } catch (error: AndroidProviderBoundaryException) {
        throw error
    } catch (_: RuntimeException) {
        fail(AndroidProviderFailureCategory.MALFORMED_PROVIDER_DATA)
    }

    private fun fail(category: AndroidProviderFailureCategory): Nothing =
        throw AndroidProviderBoundaryException(category)

    private companion object {
        const val DEFAULT_PAGE_SIZE = 100
        const val MAX_PAGE_SIZE = 100
        const val MAX_ROWS_PER_CONTACT = 128
        const val MAX_ID_BYTES = 4_096
        const val MAX_TITLE_BYTES = 16 * 1_024
        val GROUP_PROJECTION = arrayOf(
            ContactsContract.Groups._ID,
            ContactsContract.Groups.SYNC1,
            ContactsContract.Groups.SOURCE_ID,
            ContactsContract.Groups.TITLE,
            ContactsContract.Groups.DIRTY,
            ContactsContract.Groups.DELETED,
            ContactsContract.Groups.GROUP_VISIBLE,
            ContactsContract.Groups.SHOULD_SYNC,
            ContactsContract.Groups.GROUP_IS_READ_ONLY,
            ContactsContract.Groups.VERSION,
        )
        val MEMBERSHIP_PROJECTION = arrayOf(
            ContactsContract.Data._ID,
            ContactsContract.Data.RAW_CONTACT_ID,
            ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID,
        )
    }
}
