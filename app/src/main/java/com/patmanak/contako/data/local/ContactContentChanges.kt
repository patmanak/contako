package com.patmanak.contako.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Embedded
import androidx.room.ForeignKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Upsert
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.policy.CanonicalPrimaryValuePolicy
import com.patmanak.contako.domain.policy.PostalAddressPolicy
import java.util.Locale
import kotlinx.coroutines.flow.Flow

/** Local observation time of a field change, never remote chronology or conflict evidence. */
@Entity(
    tableName = "contact_content_changes",
    primaryKeys = ["account_id", "contact_id"],
    foreignKeys = [ForeignKey(
        entity = ContactEntity::class,
        parentColumns = ["account_id", "id"],
        childColumns = ["account_id", "contact_id"],
        onDelete = ForeignKey.CASCADE,
    )],
)
internal data class ContactContentChangeEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "contact_id") val contactId: String,
    @ColumnInfo(name = "observed_at") val observedAtEpochMillis: Long,
) {
    override fun toString(): String = "ContactContentChangeEntity(REDACTED)"
}

@Dao
internal interface ContactContentChangesDao {
    @Upsert
    suspend fun upsert(change: ContactContentChangeEntity)

    @Query("SELECT * FROM contact_content_changes WHERE account_id=:accountId AND contact_id=:contactId")
    suspend fun get(accountId: String, contactId: String): ContactContentChangeEntity?

    @Query("""
        SELECT changes.* FROM contact_content_changes AS changes
        INNER JOIN contacts ON contacts.account_id=changes.account_id AND contacts.id=changes.contact_id
        WHERE changes.account_id=:accountId AND contacts.is_deleted=0
    """)
    fun observeActive(accountId: String): Flow<List<ContactContentChangeEntity>>

    @Transaction
    @Query("""
        SELECT contacts.*, changes.observed_at AS content_observed_at FROM contacts
        LEFT JOIN contact_content_changes AS changes
            ON contacts.account_id=changes.account_id AND contacts.id=changes.contact_id
        WHERE contacts.account_id=:accountId AND contacts.is_deleted=0
        ORDER BY contacts.sort_name COLLATE NOCASE, contacts.id
    """)
    fun observeDirectory(accountId: String): Flow<List<DirectoryContactRow>>

    /**
     * Bound wide value cursors by owned contacts instead of sorting the entire account's values.
     * Headers and every value batch stay in one SQLite transaction, including observation dates.
     * No field, media reference or imported metadata is projected away or truncated.
     */
    @Transaction
    suspend fun readDirectory(accountId: String): List<DirectoryContactRow> {
        val contacts = readDirectoryHeaders(accountId)
        if (contacts.isEmpty()) return emptyList()
        val headersByOwner = contacts.associateBy { it.contact.ownerKey }
        val valuesByOwner = contacts.chunked(DIRECTORY_OWNER_BATCH_SIZE).flatMap { batch ->
            readDirectoryValues(accountId, batch.map { it.contact.ownerKey })
        }.filter { value ->
            // The owner-key FK alone does not prove account/contact identity. Validate against
            // the immutable scoped headers in this same transaction before exposing any value.
            val header = headersByOwner[value.ownerKey]?.contact
            header != null && value.accountId == accountId && value.accountId == header.accountId &&
                value.contactId == header.id && value.ownerKey == header.ownerKey
        }.groupBy(ContactValueEntity::ownerKey)
        return contacts.map { header ->
            DirectoryContactRow(header.contact, valuesByOwner[header.contact.ownerKey].orEmpty(), header.contentObservedAt)
        }
    }

    @Query("""
        SELECT contacts.*, changes.observed_at AS content_observed_at FROM contacts
        LEFT JOIN contact_content_changes AS changes
            ON contacts.account_id=changes.account_id AND contacts.id=changes.contact_id
        WHERE contacts.account_id=:accountId AND contacts.is_deleted=0
        ORDER BY contacts.sort_name COLLATE NOCASE, contacts.id
    """)
    suspend fun readDirectoryHeaders(accountId: String): List<DirectoryContactHeader>

    @Query("""
        SELECT v.* FROM contact_values AS v INDEXED BY index_contact_values_owner_key
        WHERE v.account_id=:accountId AND v.owner_key IN (:ownerKeys)
        ORDER BY v.owner_key, v.rowid
    """)
    suspend fun readDirectoryValues(accountId: String, ownerKeys: List<String>): List<ContactValueEntity>

    companion object {
        // Leave ample room below SQLite's bind limit and avoid a single account-wide value cursor.
        // INDEXED BY is intentional: the measured account-id plan sorted large DTOs in a temp
        // B-tree. Headers scope every batch; row identities are checked against those headers in
        // memory in the same transaction, avoiding a contact lookup for every value. The owner-key
        // index supplies the explicit owner/rowid ordering.
        const val DIRECTORY_OWNER_BATCH_SIZE = 250
    }
}

internal data class DirectoryContactHeader(
    @Embedded val contact: ContactEntity,
    @ColumnInfo(name = "content_observed_at") val contentObservedAt: Long?,
) {
    override fun toString(): String = "DirectoryContactHeader(REDACTED)"
}

internal data class DirectoryContactRow(
    @Embedded val contact: ContactEntity,
    @Relation(parentColumn = "owner_key", entityColumn = "owner_key") val values: List<ContactValueEntity>,
    @ColumnInfo(name = "content_observed_at") val contentObservedAt: Long?,
) {
    override fun toString(): String = "DirectoryContactRow(REDACTED)"
}

/**
 * Canonical field equality with occurrence multiplicity. IDs, preservation envelopes,
 * transport bindings and group assignments do not turn an unchanged card into a field edit.
 */
internal fun sameContactContent(left: CanonicalContact, right: CanonicalContact): Boolean =
    left.firstName == right.firstName && left.lastName == right.lastName &&
        left.displayName == right.displayName && contentOccurrences(left) == contentOccurrences(right)

private fun contentOccurrences(contact: CanonicalContact): Map<ContentOccurrence, Int> = contact.values
    .groupBy(ContactValue::kind).values.flatMap { family ->
        // Follow canonical preference ordering, but exclude the ID tie-breaker: a wire rebind
        // cannot be a field edit. Numeric PREF changes matter only when their effective rank changes.
        val ranks = family.withIndex().sortedWith(compareBy<IndexedValue<ContactValue>> {
            it.value.metadata[CanonicalPrimaryValuePolicy.VCARD_PREF_METADATA]
                ?.toIntOrNull()?.takeIf { preference -> preference > 0 } ?: Int.MAX_VALUE
        }.thenBy { it.value.order }.thenBy { it.index })
            .mapIndexed { rank, indexed -> indexed.index to rank }.toMap()
        family.mapIndexed { index, value ->
            val normalized = PostalAddressPolicy.normalize(value)
            val tokens = normalized.metadata["vcardTypeTokens"].orEmpty().split('\u001f')
                .map(String::trim).filter(String::isNotEmpty)
            val label = normalized.label?.trim()?.takeIf(String::isNotEmpty) ?: tokens.firstOrNull()
            val standardTypes = when (normalized.kind) {
                ContactValueKind.EMAIL, ContactValueKind.POSTAL_ADDRESS -> setOf("HOME", "WORK", "OTHER")
                ContactValueKind.PHONE -> setOf("HOME", "WORK", "OTHER", "CELL", "MAIN", "FAX", "PAGER")
                else -> emptySet()
            }
            val semanticLabel = label?.let { if (it.uppercase(Locale.ROOT) in standardTypes)
                it.lowercase(Locale.ROOT) else it }
            val redundantType = label?.lowercase(Locale.ROOT)
            val customType = label?.let(::customLabelTypeToken)
            val semanticTypes = tokens.map { it.lowercase(Locale.ROOT) }
                .filterNot { it == redundantType || it == customType }.toSet()
            ContentOccurrence(normalized.kind, normalized.value, semanticLabel, normalized.order,
                ranks.getValue(index), normalized.components, semanticTypes,
                normalized.binaryReference)
        }
    }.groupingBy { it }.eachCount()

/** The generated x-name is a transport spelling of the custom label already compared above. */
private fun customLabelTypeToken(label: String): String = "x-" + label.lowercase(Locale.ROOT)
    .removePrefix("x-").replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "custom" }

private data class ContentOccurrence(
    val kind: ContactValueKind,
    val value: String,
    val label: String?,
    val order: Int,
    val preferenceRank: Int,
    val components: Map<String, String>,
    val additionalTypes: Set<String>,
    val binaryReference: String?,
) {
    override fun toString(): String = "ContentOccurrence(REDACTED)"
}
