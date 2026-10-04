package com.patmanak.contako.data.android.provider

import android.content.ContentResolver
import android.content.ContentUris
import android.database.Cursor
import android.provider.ContactsContract
import com.patmanak.contako.android.account.ContakoAndroidAccountContract
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.charset.StandardCharsets

internal class AndroidProviderAccountName(val value: String) {
    init {
        require(value.isNotBlank() && value.length <= MAX_ACCOUNT_NAME_LENGTH)
    }

    override fun equals(other: Any?): Boolean =
        other is AndroidProviderAccountName && other.value == value

    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = "AndroidProviderAccountName(REDACTED)"

    private companion object {
        const val MAX_ACCOUNT_NAME_LENGTH = 512
    }
}

internal data class AndroidOwnedRawContact(
    val rawContactId: Long,
    val canonicalContactIdClaim: String? = null,
    val sourceIdentity: String?,
    val dirty: Boolean,
    val deleted: Boolean,
    val version: Long,
) {
    init {
        require(rawContactId > 0)
        require(canonicalContactIdClaim == null || canonicalContactIdClaim.isNotBlank())
        require(canonicalContactIdClaim == null || canonicalContactIdClaim.toByteArray(StandardCharsets.UTF_8).size <= MAX_SOURCE_ID_BYTES)
        require(sourceIdentity == null || sourceIdentity.isNotBlank())
        require(sourceIdentity == null || sourceIdentity.toByteArray(StandardCharsets.UTF_8).size <= MAX_SOURCE_ID_BYTES)
        require(version >= 0)
    }

    override fun toString(): String =
        "AndroidOwnedRawContact(REDACTED, dirty=$dirty, deleted=$deleted, version=$version)"

    private companion object {
        const val MAX_SOURCE_ID_BYTES = 4_096
    }
}

internal data class AndroidOwnedRawContactPage(
    val contacts: List<AndroidOwnedRawContact>,
    val nextAfterRawContactId: Long?,
) {
    init {
        require(contacts.zipWithNext().all { (left, right) -> left.rawContactId < right.rawContactId })
        require(nextAfterRawContactId == null || nextAfterRawContactId == contacts.lastOrNull()?.rawContactId)
    }
}

internal data class AndroidStableRawContactObservation(
    val rawContact: AndroidOwnedRawContact,
    val dataRows: List<AndroidOwnedDataRow>,
) {
    init {
        require(dataRows.all { it.rawContactId == rawContact.rawContactId })
        require(dataRows.zipWithNext().all { (left, right) -> left.dataRowId < right.dataRowId })
        require(!rawContact.deleted || dataRows.isEmpty())
    }
}

internal data class AndroidStableRawContactObservationPage(
    val observations: List<AndroidStableRawContactObservation>,
    val nextAfterRawContactId: Long?,
) {
    init {
        require(
            observations.zipWithNext().all { (left, right) ->
                left.rawContact.rawContactId < right.rawContact.rawContactId
            },
        )
        require(
            nextAfterRawContactId == null ||
                nextAfterRawContactId == observations.lastOrNull()?.rawContact?.rawContactId,
        )
    }
}

internal sealed interface AndroidStableRawContactPageResult {
    data class Stable(val page: AndroidStableRawContactObservationPage) : AndroidStableRawContactPageResult
    data object ReplanRequired : AndroidStableRawContactPageResult
}

/** Raw provider payload. Diagnostics are deliberately redacted. */
internal data class AndroidOwnedDataRow(
    val dataRowId: Long,
    val rawContactId: Long,
    val mimeType: String,
    val canonicalValueId: String?,
    val canonicalOrder: Int?,
    val canonicalOrderMalformed: Boolean = false,
    val linkedValueIdsEncoding: String?,
    val isPrimary: Boolean,
    val isSuperPrimary: Boolean,
    val stringSlots: List<String?>,
    val binarySlot: ByteArray?,
) {
    init {
        require(dataRowId > 0)
        require(rawContactId > 0)
        require(mimeType.isNotBlank() && mimeType.length <= MAX_MIME_LENGTH)
        require(canonicalValueId == null || canonicalValueId.length <= MAX_VALUE_ID_LENGTH)
        require(stringSlots.size == STRING_SLOT_COUNT)
        require(binarySlot == null || binarySlot.size <= MAX_BINARY_BYTES)
    }

    /** Provider-neutral semantic exposed to synchronization planning. */
    val isStandardPhoto: Boolean
        get() = mimeType == ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE

    override fun equals(other: Any?): Boolean =
        other is AndroidOwnedDataRow &&
            dataRowId == other.dataRowId &&
            rawContactId == other.rawContactId &&
            mimeType == other.mimeType &&
            canonicalValueId == other.canonicalValueId &&
            canonicalOrder == other.canonicalOrder &&
            canonicalOrderMalformed == other.canonicalOrderMalformed &&
            linkedValueIdsEncoding == other.linkedValueIdsEncoding &&
            isPrimary == other.isPrimary &&
            isSuperPrimary == other.isSuperPrimary &&
            stringSlots == other.stringSlots &&
            binarySlot.contentEqualsNullable(other.binarySlot)

    override fun hashCode(): Int {
        var result = dataRowId.hashCode()
        result = 31 * result + rawContactId.hashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + (canonicalValueId?.hashCode() ?: 0)
        result = 31 * result + (canonicalOrder ?: 0)
        result = 31 * result + canonicalOrderMalformed.hashCode()
        result = 31 * result + (linkedValueIdsEncoding?.hashCode() ?: 0)
        result = 31 * result + isPrimary.hashCode()
        result = 31 * result + isSuperPrimary.hashCode()
        result = 31 * result + stringSlots.hashCode()
        result = 31 * result + (binarySlot?.contentHashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "AndroidOwnedDataRow(REDACTED, primary=$isPrimary, superPrimary=$isSuperPrimary)"

    private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean = when {
        this == null -> other == null
        other == null -> false
        else -> contentEquals(other)
    }

    private companion object {
        const val STRING_SLOT_COUNT = 14
        const val MAX_MIME_LENGTH = 1_024
        const val MAX_VALUE_ID_LENGTH = 4_096
        const val MAX_BINARY_BYTES = 10 * 1_024 * 1_024
    }
}

internal enum class AndroidProviderFailureCategory {
    PERMISSION_DENIED,
    PROVIDER_UNAVAILABLE,
    MALFORMED_PROVIDER_DATA,
    BOUND_EXCEEDED,
    ACCOUNT_SCOPE_MISMATCH,
}

internal class AndroidProviderBoundaryException(
    val category: AndroidProviderFailureCategory,
) : IllegalStateException("Android contacts provider failure: ${category.name}")

/**
 * Bounded, account-scoped read boundary for Android ContactsProvider.
 *
 * Callers MUST advance with [AndroidOwnedRawContactPage.nextAfterRawContactId]; this boundary never
 * exposes aggregate-contact identity and never queries another Android account.
 */
internal class AndroidContactsProviderReader(
    private val contentResolver: ContentResolver,
    private val afterDataRowsRead: () -> Unit = {},
) {
    /** Scheduling hint only: no contact payload/identity is read and no DIRTY flag is cleared. */
    fun hasDirtyRawContacts(accountName: AndroidProviderAccountName): Boolean = query(
        uri = ContactsContract.RawContacts.CONTENT_URI.buildUpon()
            .appendQueryParameter(ContactsContract.LIMIT_PARAM_KEY, "1").build(),
        projection = arrayOf(ContactsContract.RawContacts.DIRTY),
        selection = "${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND " +
            "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND ${ContactsContract.RawContacts.DIRTY} = 1",
        selectionArgs = arrayOf(accountName.value, ContakoAndroidAccountContract.ACCOUNT_TYPE),
        sortOrder = null,
        maximumRows = 1,
    ) { true }.isNotEmpty()

    /** Stable, bounded read of one exact owned RawContact. Absence is reported as a stale plan. */
    fun readStableRawContact(
        accountName: AndroidProviderAccountName,
        rawContactId: Long,
    ): AndroidStableRawContactPageResult {
        require(rawContactId > 0)
        val first = readExactRawContacts(accountName, setOf(rawContactId), includeDeleted = true)
        if (first.size != 1) return AndroidStableRawContactPageResult.ReplanRequired
        val raw = first.single()
        val rows = if (raw.deleted) emptyList() else readDataRows(accountName, setOf(rawContactId))
        afterDataRowsRead()
        val second = readExactRawContacts(accountName, setOf(rawContactId), includeDeleted = true)
        if (second != first) return AndroidStableRawContactPageResult.ReplanRequired
        return AndroidStableRawContactPageResult.Stable(
            AndroidStableRawContactObservationPage(
                listOf(AndroidStableRawContactObservation(raw, rows)),
                nextAfterRawContactId = null,
            ),
        )
    }

    fun readRawContactPage(
        accountName: AndroidProviderAccountName,
        afterRawContactId: Long = 0,
        limit: Int = DEFAULT_RAW_CONTACT_PAGE_SIZE,
        includeDeleted: Boolean = false,
    ): AndroidOwnedRawContactPage {
        require(afterRawContactId >= 0)
        require(limit in 1..MAX_RAW_CONTACT_PAGE_SIZE)
        val requested = Math.addExact(limit, 1)
        val selection = buildString {
            append("${ContactsContract.RawContacts.ACCOUNT_NAME} = ?")
            append(" AND ${ContactsContract.RawContacts.ACCOUNT_TYPE} = ?")
            append(" AND ${ContactsContract.RawContacts._ID} > ?")
            if (!includeDeleted) append(" AND ${ContactsContract.RawContacts.DELETED} = 0")
        }
        val args = arrayOf(
            accountName.value,
            ContakoAndroidAccountContract.ACCOUNT_TYPE,
            afterRawContactId.toString(),
        )
        val uri = ContactsContract.RawContacts.CONTENT_URI.buildUpon()
            .appendQueryParameter(ContactsContract.LIMIT_PARAM_KEY, requested.toString())
            .build()
        val rows = query(
            uri = uri,
            projection = RAW_CONTACT_PROJECTION,
            selection = selection,
            selectionArgs = args,
            sortOrder = "${ContactsContract.RawContacts._ID} ASC",
            maximumRows = requested,
        ) { cursor -> cursor.toOwnedRawContact() }
        val hasMore = rows.size > limit
        val retained = if (hasMore) rows.take(limit) else rows
        return AndroidOwnedRawContactPage(
            contacts = retained,
            nextAfterRawContactId = retained.lastOrNull()?.rawContactId?.takeIf { hasMore },
        )
    }

    fun readDataRows(
        accountName: AndroidProviderAccountName,
        rawContactIds: Set<Long>,
    ): List<AndroidOwnedDataRow> {
        require(rawContactIds.isNotEmpty())
        require(rawContactIds.size <= MAX_RAW_CONTACT_PAGE_SIZE)
        require(rawContactIds.all { it > 0 })
        assertExactOwnership(accountName, rawContactIds)
        val placeholders = List(rawContactIds.size) { "?" }.joinToString(",")
        val maximumRows = Math.multiplyExact(rawContactIds.size, MAX_DATA_ROWS_PER_CONTACT)
        val requested = Math.addExact(maximumRows, 1)
        val uri = ContactsContract.Data.CONTENT_URI.buildUpon()
            .appendQueryParameter(ContactsContract.LIMIT_PARAM_KEY, requested.toString())
            .build()
        val budget = AndroidProviderReadBudget(MAX_DATA_QUERY_BYTES)
        val rowsWithoutPhotos = query(
            uri = uri,
            projection = DATA_PROJECTION,
            selection = "${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND " +
                "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND " +
                "${ContactsContract.Data.RAW_CONTACT_ID} IN ($placeholders)",
            selectionArgs = buildList {
                add(accountName.value)
                add(ContakoAndroidAccountContract.ACCOUNT_TYPE)
                rawContactIds.sorted().mapTo(this, Long::toString)
            }.toTypedArray(),
            sortOrder = "${ContactsContract.Data.RAW_CONTACT_ID} ASC, ${ContactsContract.Data._ID} ASC",
            maximumRows = requested,
        ) { cursor -> cursor.toOwnedDataRow(budget) }
        if (rowsWithoutPhotos.size > maximumRows) {
            throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.BOUND_EXCEEDED)
        }
        return rowsWithoutPhotos.map { row ->
            if (row.mimeType == ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE) {
                row.copy(binarySlot = readBoundedPhoto(accountName, row.rawContactId, budget))
            } else {
                row
            }
        }
    }

    /**
     * Reads one bounded page as raw-contact v1 -> Data/photos -> raw-contact v2.
     *
     * A caller MUST discard the whole page on [AndroidStableRawContactPageResult.ReplanRequired].
     * This prevents a canonical commit or DIRTY acknowledgement from using a torn observation.
     */
    fun readStableObservationPage(
        accountName: AndroidProviderAccountName,
        afterRawContactId: Long = 0,
        limit: Int = DEFAULT_RAW_CONTACT_PAGE_SIZE,
        includeDeleted: Boolean = true,
    ): AndroidStableRawContactPageResult {
        val first = readRawContactPage(accountName, afterRawContactId, limit, includeDeleted)
        if (first.contacts.isEmpty()) {
            return AndroidStableRawContactPageResult.Stable(
                AndroidStableRawContactObservationPage(emptyList(), first.nextAfterRawContactId),
            )
        }
        val activeIds = first.contacts.asSequence()
            .filterNot(AndroidOwnedRawContact::deleted)
            .map(AndroidOwnedRawContact::rawContactId)
            .toSet()
        val rows = if (activeIds.isEmpty()) emptyList() else readDataRows(accountName, activeIds)
        afterDataRowsRead()
        val exactIds = first.contacts.map(AndroidOwnedRawContact::rawContactId).toSet()
        val second = readExactRawContacts(accountName, exactIds, includeDeleted = true)
        if (second != first.contacts) return AndroidStableRawContactPageResult.ReplanRequired
        val rowsByRawContact = rows.groupBy(AndroidOwnedDataRow::rawContactId)
        val observations = second.map { rawContact ->
            AndroidStableRawContactObservation(
                rawContact = rawContact,
                dataRows = rowsByRawContact[rawContact.rawContactId].orEmpty(),
            )
        }
        return AndroidStableRawContactPageResult.Stable(
            AndroidStableRawContactObservationPage(observations, first.nextAfterRawContactId),
        )
    }

    /** Bounded ownership metadata only: never loads Data rows or photo streams. */
    fun readOwnedRawContactMetadata(
        accountName: AndroidProviderAccountName,
        rawContactIds: Set<Long>,
    ): List<AndroidOwnedRawContact> =
        if (rawContactIds.isEmpty()) emptyList()
        else readExactRawContacts(accountName, rawContactIds, includeDeleted = true)

    private fun readExactRawContacts(
        accountName: AndroidProviderAccountName,
        rawContactIds: Set<Long>,
        includeDeleted: Boolean,
    ): List<AndroidOwnedRawContact> {
        require(rawContactIds.isNotEmpty())
        require(rawContactIds.size <= MAX_RAW_CONTACT_PAGE_SIZE)
        require(rawContactIds.all { it > 0 })
        val placeholders = List(rawContactIds.size) { "?" }.joinToString(",")
        val selection = buildString {
            append("${ContactsContract.RawContacts.ACCOUNT_NAME} = ?")
            append(" AND ${ContactsContract.RawContacts.ACCOUNT_TYPE} = ?")
            append(" AND ${ContactsContract.RawContacts._ID} IN ($placeholders)")
            if (!includeDeleted) append(" AND ${ContactsContract.RawContacts.DELETED} = 0")
        }
        val rows = query(
            uri = ContactsContract.RawContacts.CONTENT_URI,
            projection = RAW_CONTACT_PROJECTION,
            selection = selection,
            selectionArgs = buildList {
                add(accountName.value)
                add(ContakoAndroidAccountContract.ACCOUNT_TYPE)
                rawContactIds.sorted().mapTo(this, Long::toString)
            }.toTypedArray(),
            sortOrder = "${ContactsContract.RawContacts._ID} ASC",
            maximumRows = Math.addExact(rawContactIds.size, 1),
        ) { cursor -> cursor.toOwnedRawContact() }
        if (rows.size > rawContactIds.size) {
            throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.ACCOUNT_SCOPE_MISMATCH)
        }
        return rows
    }

    private fun assertExactOwnership(
        accountName: AndroidProviderAccountName,
        rawContactIds: Set<Long>,
    ) {
        val placeholders = List(rawContactIds.size) { "?" }.joinToString(",")
        val selection =
            "${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND " +
                "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND " +
                "${ContactsContract.RawContacts._ID} IN ($placeholders)"
        val args = buildList {
            add(accountName.value)
            add(ContakoAndroidAccountContract.ACCOUNT_TYPE)
            rawContactIds.sorted().mapTo(this, Long::toString)
        }.toTypedArray()
        val owned = query(
            uri = ContactsContract.RawContacts.CONTENT_URI,
            projection = arrayOf(ContactsContract.RawContacts._ID),
            selection = selection,
            selectionArgs = args,
            sortOrder = null,
            maximumRows = rawContactIds.size,
        ) { cursor -> cursor.getLong(0) }.toSet()
        if (owned != rawContactIds) {
            throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.ACCOUNT_SCOPE_MISMATCH)
        }
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
        require(maximumRows > 0)
        contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    if (size == maximumRows) break
                    add(transform(cursor))
                }
            }
        } ?: throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.PROVIDER_UNAVAILABLE)
    } catch (_: SecurityException) {
        throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.PERMISSION_DENIED)
    } catch (error: AndroidProviderBoundaryException) {
        throw error
    } catch (_: RuntimeException) {
        throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.MALFORMED_PROVIDER_DATA)
    }

    private fun Cursor.toOwnedRawContact() = AndroidOwnedRawContact(
        rawContactId = getLong(getColumnIndexOrThrow(ContactsContract.RawContacts._ID)),
        canonicalContactIdClaim = boundedString(ContactsContract.RawContacts.SYNC1, MAX_SOURCE_ID_BYTES)
            ?.takeUnless(String::isBlank),
        sourceIdentity = boundedString(ContactsContract.RawContacts.SOURCE_ID, MAX_SOURCE_ID_BYTES)
            ?.takeUnless(String::isBlank),
        dirty = getInt(getColumnIndexOrThrow(ContactsContract.RawContacts.DIRTY)) != 0,
        deleted = getInt(getColumnIndexOrThrow(ContactsContract.RawContacts.DELETED)) != 0,
        version = getLong(getColumnIndexOrThrow(ContactsContract.RawContacts.VERSION)),
    )

    private fun Cursor.toOwnedDataRow(budget: AndroidProviderReadBudget): AndroidOwnedDataRow {
        val mimeType = boundedString(ContactsContract.Data.MIMETYPE, MAX_MIME_BYTES)
            ?: throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.MALFORMED_PROVIDER_DATA)
        val rawCanonicalOrder = boundedString(DATA_SYNC2, MAX_ORDER_BYTES)
        return AndroidOwnedDataRow(
            dataRowId = getLong(getColumnIndexOrThrow(ContactsContract.Data._ID)),
            rawContactId = getLong(getColumnIndexOrThrow(ContactsContract.Data.RAW_CONTACT_ID)),
            mimeType = mimeType,
            canonicalValueId = boundedString(DATA_SYNC1, MAX_VALUE_ID_BYTES),
            canonicalOrder = rawCanonicalOrder?.toIntOrNull(),
            canonicalOrderMalformed = rawCanonicalOrder != null && rawCanonicalOrder.toIntOrNull() == null,
            linkedValueIdsEncoding = boundedString(DATA_SYNC3, MAX_LINKED_ENCODING_BYTES),
            isPrimary = getInt(getColumnIndexOrThrow(ContactsContract.Data.IS_PRIMARY)) != 0,
            isSuperPrimary = getInt(getColumnIndexOrThrow(ContactsContract.Data.IS_SUPER_PRIMARY)) != 0,
            stringSlots = DATA_STRING_COLUMNS.map { column -> boundedString(column, MAX_TEXT_BYTES) },
            binarySlot = null,
        ).also { row -> budget.consume(row.encodedTextBytes()) }
    }

    private fun Cursor.nullableString(column: String): String? =
        getColumnIndexOrThrow(column).let { index -> if (isNull(index)) null else getString(index) }

    private fun Cursor.boundedString(column: String, maximumBytes: Int): String? {
        val value = nullableString(column) ?: return null
        if (value.toByteArray(StandardCharsets.UTF_8).size > maximumBytes) {
            throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.BOUND_EXCEEDED)
        }
        return value
    }

    private fun AndroidOwnedDataRow.encodedTextBytes(): Int = buildList {
        add(mimeType)
        canonicalValueId?.let(::add)
        linkedValueIdsEncoding?.let(::add)
        stringSlots.filterNotNullTo(this)
    }.sumOf { it.toByteArray(StandardCharsets.UTF_8).size }

    private fun readBoundedPhoto(
        accountName: AndroidProviderAccountName,
        rawContactId: Long,
        budget: AndroidProviderReadBudget,
    ): ByteArray? {
        assertExactOwnership(accountName, setOf(rawContactId))
        val uri = ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, rawContactId)
            .buildUpon()
            .appendPath(ContactsContract.RawContacts.DisplayPhoto.CONTENT_DIRECTORY)
            .build()
        val displayBytes = try {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.createInputStream().use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(PHOTO_STREAM_BUFFER_BYTES)
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total = Math.addExact(total, read)
                        if (total > MAX_BINARY_BYTES) {
                            throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.BOUND_EXCEEDED)
                        }
                        budget.consume(read)
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                }
            }
        } catch (_: FileNotFoundException) {
            null
        } catch (_: IOException) {
            throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.PROVIDER_UNAVAILABLE)
        } catch (_: SecurityException) {
            throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.PERMISSION_DENIED)
        } catch (error: AndroidProviderBoundaryException) {
            throw error
        } catch (_: RuntimeException) {
            throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.MALFORMED_PROVIDER_DATA)
        }
        if (displayBytes != null && displayBytes.isNotEmpty()) return displayBytes
        return readBoundedInlinePhoto(rawContactId, budget)
    }

    /**
     * Some OEM providers retain a valid bounded Data/Photo thumbnail while declining to publish a
     * RawContacts/display_photo stream. Read that interoperability fallback only after the stream
     * is absent; full-size payloads never travel through this cursor.
     */
    private fun readBoundedInlinePhoto(rawContactId: Long, budget: AndroidProviderReadBudget): ByteArray? = try {
        contentResolver.query(
            ContactsContract.Data.CONTENT_URI.buildUpon()
                .appendQueryParameter(ContactsContract.LIMIT_PARAM_KEY, "2")
                .build(),
            arrayOf(ContactsContract.CommonDataKinds.Photo.PHOTO),
            "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(rawContactId.toString(), ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE),
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) return@use null
            val bytes = cursor.getBlob(0)
            if (cursor.moveToNext()) {
                throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.MALFORMED_PROVIDER_DATA)
            }
            if (bytes.isEmpty()) return@use null
            if (bytes.size > MAX_INLINE_BINARY_BYTES) {
                throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.BOUND_EXCEEDED)
            }
            budget.consume(bytes.size)
            bytes
        }
    } catch (_: SecurityException) {
        throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.PERMISSION_DENIED)
    } catch (error: AndroidProviderBoundaryException) {
        throw error
    } catch (_: RuntimeException) {
        throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.MALFORMED_PROVIDER_DATA)
    }

    private companion object {
        const val DEFAULT_RAW_CONTACT_PAGE_SIZE = 100
        const val MAX_RAW_CONTACT_PAGE_SIZE = 100
        const val MAX_DATA_ROWS_PER_CONTACT = 128
        const val MAX_DATA_QUERY_BYTES = 24 * 1_024 * 1_024
        const val MAX_MIME_BYTES = 1_024
        const val MAX_SOURCE_ID_BYTES = 4_096
        const val MAX_VALUE_ID_BYTES = 4_096
        const val MAX_ORDER_BYTES = 32
        const val MAX_LINKED_ENCODING_BYTES = 16 * 1_024
        const val MAX_TEXT_BYTES = 16 * 1_024
        const val MAX_BINARY_BYTES = 10 * 1_024 * 1_024
        const val MAX_INLINE_BINARY_BYTES = 256 * 1_024
        const val PHOTO_STREAM_BUFFER_BYTES = 8 * 1_024

        val RAW_CONTACT_PROJECTION = arrayOf(
            ContactsContract.RawContacts._ID,
            ContactsContract.RawContacts.SYNC1,
            ContactsContract.RawContacts.SOURCE_ID,
            ContactsContract.RawContacts.DIRTY,
            ContactsContract.RawContacts.DELETED,
            ContactsContract.RawContacts.VERSION,
        )
        val DATA_STRING_COLUMNS = listOf(
            ContactsContract.Data.DATA1,
            ContactsContract.Data.DATA2,
            ContactsContract.Data.DATA3,
            ContactsContract.Data.DATA4,
            ContactsContract.Data.DATA5,
            ContactsContract.Data.DATA6,
            ContactsContract.Data.DATA7,
            ContactsContract.Data.DATA8,
            ContactsContract.Data.DATA9,
            ContactsContract.Data.DATA10,
            ContactsContract.Data.DATA11,
            ContactsContract.Data.DATA12,
            ContactsContract.Data.DATA13,
            ContactsContract.Data.DATA14,
        )
        val DATA_PROJECTION = buildList {
            add(ContactsContract.Data._ID)
            add(ContactsContract.Data.RAW_CONTACT_ID)
            add(ContactsContract.Data.MIMETYPE)
            add(DATA_SYNC1)
            add(DATA_SYNC2)
            add(DATA_SYNC3)
            add(ContactsContract.Data.IS_PRIMARY)
            add(ContactsContract.Data.IS_SUPER_PRIMARY)
            addAll(DATA_STRING_COLUMNS)
        }.toTypedArray()
        // ContactsContract.DataColumns constants are public platform columns but are not exposed
        // by the Kotlin SDK stub as an accessible nested interface.
        const val DATA_SYNC1 = "data_sync1"
        const val DATA_SYNC2 = "data_sync2"
        const val DATA_SYNC3 = "data_sync3"
    }
}

private class AndroidProviderReadBudget(private val maximumBytes: Int) {
    private var consumedBytes = 0

    fun consume(bytes: Int) {
        if (bytes < 0 || consumedBytes > maximumBytes - bytes) {
            throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.BOUND_EXCEEDED)
        }
        consumedBytes += bytes
    }
}
