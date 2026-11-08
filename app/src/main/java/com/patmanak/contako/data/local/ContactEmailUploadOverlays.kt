package com.patmanak.contako.data.local

import androidx.room.*
import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.data.gateway.GatewayOutcome
import com.patmanak.contako.data.proton.PROTON_EMAIL_ID_KEY
import com.patmanak.contako.data.proton.PROTON_GROUP_IDS_KEY
import com.patmanak.contako.domain.model.*
import com.patmanak.contako.domain.policy.ContactValidation
import kotlinx.coroutines.flow.Flow

/** Local intent withheld from the wire; values and service identities never enter diagnostics. */
@Entity(tableName = "contact_email_upload_overlays", primaryKeys = ["account_id", "contact_id", "value_id"])
internal data class ContactEmailUploadOverlayEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "contact_id") val contactId: String,
    @ColumnInfo(name = "value_id") val valueId: String,
    @ColumnInfo(name = "local_value", typeAffinity = ColumnInfo.BLOB) val localValue: ByteArray,
    @ColumnInfo(name = "accepted_value", typeAffinity = ColumnInfo.BLOB) val acceptedValue: ByteArray?,
    @ColumnInfo(name = "source_email_id") val sourceEmailId: String?,
    @ColumnInfo(name = "canonical_revision") val canonicalRevision: Long,
    @ColumnInfo(name = "requires_remote_identity", defaultValue = "0") val requiresRemoteIdentity: Boolean = false,
) { override fun toString() = "ContactEmailUploadOverlayEntity(REDACTED)" }

@Dao
internal interface ContactEmailUploadOverlayDao {
    @Query("SELECT * FROM contact_email_upload_overlays WHERE account_id=:accountId AND contact_id=:contactId ORDER BY value_id")
    suspend fun getForContact(accountId: String, contactId: String): List<ContactEmailUploadOverlayEntity>
    @Query("SELECT * FROM contact_email_upload_overlays WHERE account_id=:accountId ORDER BY contact_id,value_id")
    suspend fun getAll(accountId: String): List<ContactEmailUploadOverlayEntity>
    @Query("SELECT DISTINCT contact_id FROM contact_email_upload_overlays WHERE account_id=:accountId ORDER BY contact_id")
    fun observeContactIds(accountId: String): Flow<List<String>>
    @Upsert suspend fun upsert(rows: List<ContactEmailUploadOverlayEntity>)
    @Query("DELETE FROM contact_email_upload_overlays WHERE account_id=:accountId AND contact_id=:contactId")
    suspend fun deleteContact(accountId: String, contactId: String)
    @Query("DELETE FROM contact_email_upload_overlays WHERE account_id=:accountId")
    suspend fun deleteAccount(accountId: String)
}

internal class RoomContactUploadOverlays(private val database: ContakoDatabase) {
    private val dao get() = database.contactEmailUploadOverlayDao()
    /** Caller owns the canonical mutation transaction. Each local occurrence is independently durable. */
    suspend fun saveLocal(previous: CanonicalContact?, committed: CanonicalContact) {
        val old = dao.getForContact(committed.accountId, committed.id).associateBy { it.valueId }
        val pending = previous?.pendingMutationRevision?.let {
            database.outboxDao().get(committed.accountId, AggregateType.CONTACT.name, committed.id)
        }
        val invalid = committed.valuesOf(ContactValueKind.EMAIL).filter { !ContactValidation.isValidEmail(it.value) }
        val rows = invalid.map { value ->
            val previousValue = previous?.values?.singleOrNull { it.id == value.id && it.kind == ContactValueKind.EMAIL }
            val unchangedImported = old[value.id] == null && previous?.remoteContactId != null && previousValue == value &&
                previous.actionRequiredReasons.none { it == "INVALID_EMAIL" }
            if (unchangedImported) return@map null
            val accepted = old[value.id]?.acceptedValue ?: previousValue?.takeIf {
                previous?.pendingMutationRevision == null && ContactValidation.isValidEmail(it.value) &&
                    !it.metadata[PROTON_EMAIL_ID_KEY].isNullOrBlank()
            }?.let { encode(committed, it) }
            ContactEmailUploadOverlayEntity(committed.accountId, committed.id, value.id, encode(committed, value), accepted,
                old[value.id]?.sourceEmailId ?: value.metadata[PROTON_EMAIL_ID_KEY], committed.revision,
                old[value.id]?.requiresRemoteIdentity ?: (previous?.remoteContactId != null && previousValue != null &&
                    (previous.pendingMutationRevision == null || previousValue.preservationKey != null || pending?.lastAttemptAtEpochMillis != null)))
        }.filterNotNull()
        dao.deleteContact(committed.accountId, committed.id)
        dao.upsert(rows)
    }

    /** Fresh verified identity binds a fallback, never equal e-mail text or positional vCard keys. */
    suspend fun refreshAccepted(local: CanonicalContact, remote: CanonicalContact): Boolean = database.withTransaction {
        val current = database.contactDao().getEntity(local.accountId, local.id) ?: return@withTransaction false
        if (current.revision != local.revision || current.remoteContactId != local.remoteContactId) return@withTransaction false
        refreshAcceptedRows(local, remote)
    }

    private suspend fun refreshAcceptedRows(local: CanonicalContact, remote: CanonicalContact): Boolean {
        val rows = dao.getForContact(local.accountId, local.id)
        if (!sourcesUnambiguous(local, rows)) return false
        val updates = rows.map { row ->
            if (row.canonicalRevision != local.revision) return false
            val token = row.sourceEmailId
            if (token == null) {
                if (row.acceptedValue != null || row.requiresRemoteIdentity) return false
                row
            } else {
                val match = remote.valuesOf(ContactValueKind.EMAIL).filter { it.metadata[PROTON_EMAIL_ID_KEY] == token }
                if (match.size != 1) return false
                if (!preservationIdentityMatches(match.single(), row.valueId)) return false
                row.copy(acceptedValue = encode(local, match.single().copy(id = row.valueId)))
            }
        }
        dao.upsert(updates)
        return true
    }

    suspend fun wireProjection(local: CanonicalContact): CanonicalContact? = database.withTransaction {
        val current = database.contactDao().getEntity(local.accountId, local.id) ?: return@withTransaction null
        if (current.revision != local.revision || current.remoteContactId != local.remoteContactId) return@withTransaction null
        projectCurrent(local)
    }

    private suspend fun projectCurrent(local: CanonicalContact): CanonicalContact? {
        val rows = dao.getForContact(local.accountId, local.id)
        if (rows.any { it.canonicalRevision != local.revision }) return null
        if (!sourcesUnambiguous(local, rows)) return null
        val overlays = rows.associateBy { it.valueId }
        val values = local.values.mapNotNull { value ->
            val row = overlays[value.id] ?: return@mapNotNull value
            if ((row.sourceEmailId != null || row.requiresRemoteIdentity) && row.acceptedValue == null) return null
            row.acceptedValue?.let {
                val accepted = decode(row, it)
                if (!preservationIdentityMatches(accepted, row.valueId)) return null
                accepted
            }
        }
        val reasons = if (rows.isEmpty()) local.actionRequiredReasons else local.actionRequiredReasons - "INVALID_EMAIL"
        return local.copy(values = values, actionRequiredReasons = reasons)
    }

    /** Restore only withheld occurrences after a verified remote adoption, retaining every other field. */
    suspend fun overlayRemote(local: CanonicalContact, remote: CanonicalContact): CanonicalContact {
        val rows = dao.getForContact(local.accountId, local.id)
        if (rows.isEmpty()) return remote
        com.patmanak.contako.data.sync.checkLocalReconciliationInvariant(rows.all { row ->
            row.canonicalRevision == local.revision && local.values.any { it.id == row.valueId && it.kind == ContactValueKind.EMAIL }
        }, com.patmanak.contako.data.sync.LocalReconciliationInvariant.INTENT_REVISION)
        val localByToken = local.valuesOf(ContactValueKind.EMAIL).filter { !it.metadata[PROTON_EMAIL_ID_KEY].isNullOrBlank() }
            .groupBy { it.metadata.getValue(PROTON_EMAIL_ID_KEY) }
        val reserved = rows.associateBy { it.valueId }
        val values = remote.values.map { value ->
            val token = value.metadata[PROTON_EMAIL_ID_KEY]
            val previous = if (value.kind == ContactValueKind.EMAIL && token != null) localByToken[token] else null
            com.patmanak.contako.data.sync.checkLocalReconciliationInvariant(previous == null || previous.size == 1,
                com.patmanak.contako.data.sync.LocalReconciliationInvariant.PENDING_GROUP_EMAIL_REMAP)
            com.patmanak.contako.data.sync.checkLocalReconciliationInvariant(previous == null ||
                preservationIdentityMatches(value, previous.single().id),
                com.patmanak.contako.data.sync.LocalReconciliationInvariant.PENDING_GROUP_EMAIL_REMAP)
            val bound = previous?.singleOrNull()?.let { value.copy(id = it.id) } ?: value
            // Parser line positions are not identities. An unbound collision cannot consume local input.
            val overlay = reserved[bound.id]
            com.patmanak.contako.data.sync.checkLocalReconciliationInvariant(overlay == null ||
                (bound.kind == ContactValueKind.EMAIL && overlay.sourceEmailId != null && overlay.sourceEmailId == token),
                com.patmanak.contako.data.sync.LocalReconciliationInvariant.PENDING_GROUP_EMAIL_REMAP)
            bound
        }.toMutableList()
        com.patmanak.contako.data.sync.checkLocalReconciliationInvariant(values.map { it.id }.distinct().size == values.size,
            com.patmanak.contako.data.sync.LocalReconciliationInvariant.PENDING_GROUP_EMAIL_REMAP)
        rows.forEach { row ->
            val invalid = decode(row, row.localValue)
            val matching = values.withIndex().filter { (_, value) -> value.kind == ContactValueKind.EMAIL &&
                row.sourceEmailId != null && value.metadata[PROTON_EMAIL_ID_KEY] == row.sourceEmailId }
            com.patmanak.contako.data.sync.checkLocalReconciliationInvariant(matching.size <= 1,
                com.patmanak.contako.data.sync.LocalReconciliationInvariant.PENDING_GROUP_EMAIL_REMAP)
            if (matching.isNotEmpty()) {
                val index = matching.single().index
                values[index] = invalid.copy(metadata = transportMetadata(invalid.metadata, values[index].metadata))
            } else {
                // A full verified card proves absence only for a previously identified occurrence.
                // Its unfinished local input survives, without resurrecting the removed remote email/groups.
                values += if (row.sourceEmailId != null) invalid.copy(metadata =
                    invalid.metadata - PROTON_EMAIL_ID_KEY - PROTON_GROUP_IDS_KEY) else invalid
            }
        }
        return remote.copy(values = compactOrders(values), actionRequiredReasons = remote.actionRequiredReasons + "INVALID_EMAIL")
    }

    suspend fun advanceRevision(account: String, id: String, revision: Long, adopted: CanonicalContact? = null) {
        dao.upsert(dao.getForContact(account, id).map { row ->
            val value = adopted?.values?.singleOrNull { it.id == row.valueId && it.kind == ContactValueKind.EMAIL }
            val detached = row.sourceEmailId != null && value != null && value.metadata[PROTON_EMAIL_ID_KEY] == null
            if (value == null) row.copy(canonicalRevision = revision) else row.copy(canonicalRevision = revision,
                localValue = encode(account, id, value), sourceEmailId = value.metadata[PROTON_EMAIL_ID_KEY],
                requiresRemoteIdentity = row.requiresRemoteIdentity && !detached,
                acceptedValue = if (detached) null else row.acceptedValue?.let { bytes ->
                    val accepted = decode(row, bytes)
                    encode(account, id, accepted.copy(metadata = transportMetadata(accepted.metadata, value.metadata)))
                })
        })
    }

    /** A superseded response proves occurrence identities, never the newer field contents. Caller holds Room's transaction. */
    suspend fun retainAcknowledgedIdentities(local: CanonicalContact, identities: Map<String, String>): Boolean {
        val current = database.contactDao().getEntity(local.accountId, local.id) ?: return false
        if (current.revision != local.revision || current.pendingMutationRevision != local.revision) return false
        val emails = local.valuesOf(ContactValueKind.EMAIL)
        val rows = dao.getForContact(local.accountId, local.id)
        if (rows.any { it.canonicalRevision != local.revision || emails.none { value -> value.id == it.valueId } }) return false
        val present = identities.filterKeys { id -> emails.any { it.id == id } }
        if (present.values.any(String::isBlank)) return false
        val updated = local.copy(values = local.values.map { value ->
            present[value.id]?.takeIf { value.kind == ContactValueKind.EMAIL }?.let { token ->
                value.copy(metadata = value.metadata + (PROTON_EMAIL_ID_KEY to token))
            } ?: value
        })
        val updatedRows = rows.map { row -> present[row.valueId]?.let { token -> row.copy(sourceEmailId = token) } ?: row }
        val tokens = updated.valuesOf(ContactValueKind.EMAIL).mapNotNull { it.metadata[PROTON_EMAIL_ID_KEY] }
        if (tokens.distinct().size != tokens.size || !sourcesUnambiguous(updated, updatedRows)) return false
        database.contactDao().upsertValues(updated.valuesOf(ContactValueKind.EMAIL).map { it.toEntity(local) })
        acknowledged(local.accountId, local.id, local.revision, present)
        return true
    }
    suspend fun acknowledged(account: String, id: String, revision: Long, identities: Map<String, String>) {
        dao.upsert(dao.getForContact(account, id).map { row ->
            if (row.canonicalRevision != revision) row else identities[row.valueId]?.let { token ->
                fun update(bytes: ByteArray) = encode(account, id, decode(row, bytes).let {
                    it.copy(metadata = it.metadata + (PROTON_EMAIL_ID_KEY to token))
                })
                row.copy(sourceEmailId = token, localValue = update(row.localValue), acceptedValue = row.acceptedValue?.let(::update))
            } ?: row
        })
    }
    suspend fun has(account: String, id: String) = dao.getForContact(account, id).isNotEmpty()
    suspend fun clear(account: String, id: String) = dao.deleteContact(account, id)

    private fun sourcesUnambiguous(local: CanonicalContact, rows: List<ContactEmailUploadOverlayEntity>): Boolean {
        if (rows.isEmpty()) return true
        val bound = rows.mapNotNull { row -> row.sourceEmailId?.let { it to row.valueId } } +
            local.valuesOf(ContactValueKind.EMAIL).mapNotNull { value -> value.metadata[PROTON_EMAIL_ID_KEY]?.let { it to value.id } }
        return bound.groupBy({ it.first }, { it.second }).values.none { it.distinct().size > 1 }
    }

    private fun transportMetadata(local: Map<String, String>, remote: Map<String, String>): Map<String, String> =
        (local - PROTON_EMAIL_ID_KEY - PROTON_GROUP_IDS_KEY) +
            remote.filterKeys { it == PROTON_EMAIL_ID_KEY || it == PROTON_GROUP_IDS_KEY }

    /** The codec binds decorations to their exact parsed occurrence; a token cannot relax that proof. */
    private fun preservationIdentityMatches(value: ContactValue, localId: String): Boolean =
        value.preservationKey?.let { localId == "${value.kind.name.lowercase(java.util.Locale.ROOT)}@$it" } ?: true

    private fun encode(contact: CanonicalContact, value: ContactValue) = encode(contact.accountId, contact.id, value)
    private fun encode(account: String, id: String, value: ContactValue): ByteArray {
        require(value.kind == ContactValueKind.EMAIL)
        val encoded = ContactConflictSnapshotCodec.encode(CanonicalContact(account, id, values = listOf(value)))
        require(encoded.size <= MAX_VALUE_BYTES)
        return encoded
    }
    private fun decode(row: ContactEmailUploadOverlayEntity, bytes: ByteArray): ContactValue {
        require(bytes.size <= MAX_VALUE_BYTES)
        val contact = ContactConflictSnapshotCodec.decode(bytes)
        require(contact.accountId == row.accountId && contact.id == row.contactId && contact.values.size == 1)
        return contact.values.single().also { require(it.id == row.valueId && it.kind == ContactValueKind.EMAIL) }
    }
    private fun compactOrders(values: List<ContactValue>) = values.groupBy { it.kind }.flatMap { (_, family) ->
        family.sortedWith(compareBy<ContactValue> { it.order }.thenBy { it.id }).mapIndexed { index, value -> value.copy(order = index) }
    }
    companion object { private const val MAX_VALUE_BYTES = 256 * 1024 }
}
