package com.patmanak.contako.data.local

import androidx.room.withTransaction
import com.patmanak.contako.data.gateway.VerifiedContactCard
import com.patmanak.contako.data.proton.mergePendingProtonPreservation
import com.patmanak.contako.data.sync.MutationPreparation
import com.patmanak.contako.data.sync.MutationPreparationActionRequiredReason
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.sync.*
import kotlinx.coroutines.flow.map
import java.util.UUID

internal class RoomContactConflictStore(private val database: ContakoDatabase) {
    private val dao = database.contactConflictDao()
    fun observe(account: String) = dao.observe(account).map { rows -> rows.map { it.summary() } }

    suspend fun detail(account: String, id: String): ContactConflictDetail? = database.withTransaction {
        val row = dao.get(account, id) ?: return@withTransaction null
        fun decode(chunks: List<ContactConflictChunk>): CanonicalContact {
            require(chunks.isNotEmpty() && chunks.size <= 384)
            require(chunks.map { it.position } == chunks.indices.toList())
            val output = java.io.ByteArrayOutputStream()
            chunks.forEach { require(it.bytes.size <= CHUNK_BYTES); output.write(it.bytes) }
            return ContactConflictSnapshotCodec.decode(output.toByteArray())
        }
        val remote = decode(dao.chunks(row.ownerKey, "PROTON")).copy(isDeleted = row.remoteDeleted)
        ContactConflictDetail(row.summary(), decode(dao.chunks(row.ownerKey, "LOCAL")), remote,
            RoomRemoteCanonicalReconciliationStore(database).canAdoptRemote(remote.copy(accountId = account, id = id)))
    }

    suspend fun choose(account: String, expected: ContactConflictSummary, choice: ContactConflictChoice): Boolean = database.withTransaction {
        val row = dao.get(account, expected.contactId) ?: return@withTransaction false
        val local = database.contactDao().get(account, row.contactId)?.contact ?: return@withTransaction false
        val intent = database.outboxDao().get(account, AggregateType.CONTACT.name, row.contactId) ?: return@withTransaction false
        if (row.remoteDeleted || row.choice != null || intent.state != DurableMutationState.ACTION_REQUIRED.name ||
            row.summary() != expected || local.revision != row.localRevision || intent.revision != row.localRevision ||
            local.remoteContactId != row.remoteId) return@withTransaction false
        dao.upsert(row.copy(choice = choice.name))
        database.outboxDao().upsert(intent.copy(state = DurableMutationState.PENDING.name,
            blockedReason = null, errorCategory = null, nextAttemptAtEpochMillis = 0))
        true
    }

    /** Called within the caller's canonical transaction; never advances an upload baseline. */
    suspend fun capture(local: CanonicalContact, intent: OutboxMutationEntity, remote: VerifiedContactCard) {
        require(remote.id.value == local.remoteContactId && remote.version != null)
        val existing = dao.get(local.accountId, local.id)
        if (existing?.localRevision == local.revision && !existing.remoteDeleted && existing.remoteVersion == remote.version.value &&
            existing.remoteId == remote.id.value && existing.choice != null) return
        if (existing?.localRevision != local.revision || existing.remoteDeleted || existing.remoteVersion != remote.version.value || existing.remoteId != remote.id.value) {
            val row = ContactConflictEntity(local.toEntity().ownerKey, local.accountId, local.id,
                UUID.randomUUID().toString(), local.revision, remote.id.value, remote.version.value, null)
            val snapshots = listOf("LOCAL" to local, "PROTON" to remote.contact)
                .flatMap { (side, snapshot) ->
                    val bytes = ContactConflictSnapshotCodec.encode(snapshot)
                    (bytes.indices step CHUNK_BYTES).mapIndexed { position, offset ->
                        ContactConflictChunk(row.ownerKey, side, position, bytes.copyOfRange(offset, minOf(offset + CHUNK_BYTES, bytes.size)))
                    }
                }
            dao.delete(local.accountId, local.id)
            dao.upsert(row)
            dao.insert(snapshots)
        }
        database.contactDao().upsert(local.copy(conflictState = CONFLICT).toEntity())
        database.outboxDao().upsert(intent.copy(state = DurableMutationState.ACTION_REQUIRED.name,
            blockedReason = CONFLICT, errorCategory = "CONFLICT"))
    }

    /** Fresh verified GET supplied by the serialized engine, then revision-checked local commit. */
    suspend fun prepare(account: String, id: String, revision: Long, remote: VerifiedContactCard): MutationPreparation = database.withTransaction {
        val stored = database.contactDao().get(account, id) ?: return@withTransaction blocked()
        val local = stored.toDomain().copy(preservationEnvelope = database.contactPayloadDao().get(stored.contact.ownerKey)?.toDomain())
        val intent = database.outboxDao().get(account, AggregateType.CONTACT.name, id) ?: return@withTransaction blocked()
        if (local.revision != revision || intent.revision != revision || local.pendingMutationRevision != revision ||
            local.remoteContactId != remote.id.value || remote.version == null) return@withTransaction blocked()
        val conflict = dao.get(account, id)
        if (conflict == null && remote.matchesBaseline(intent.remoteVersion)) return@withTransaction MutationPreparation.UploadAllowed
        if (conflict?.localRevision != revision || conflict.remoteDeleted || conflict.remoteVersion != remote.version.value ||
            conflict.remoteId != remote.id.value || conflict.choice == null) {
            capture(local, intent, remote)
            return@withTransaction blocked()
        }
        when (ContactConflictChoice.valueOf(conflict.choice)) {
            ContactConflictChoice.PROTON -> {
                if (!RoomRemoteCanonicalReconciliationStore(database).canAdoptRemote(remote.contact.copy(accountId = account, id = id))) {
                    dao.upsert(conflict.copy(choice = null))
                    capture(local, intent, remote)
                    return@withTransaction blocked()
                }
                // Reuses value/membership/projection reconciliation; transaction rolls back on an unsafe remap.
                RoomRemoteCanonicalReconciliationStore(database).adoptRemote(local, remote.contact, remote, null)
                check(database.outboxDao().deleteRevision(account, AggregateType.CONTACT.name, id, revision) == 1)
                dao.delete(account, id)
                MutationPreparation.ResolvedWithoutUpload
            }
            ContactConflictChoice.LOCAL -> {
                val envelope = mergePendingProtonPreservation(local.preservationEnvelope, remote.contact.preservationEnvelope)
                database.contactDao().upsert(local.copy(remoteVersion = remote.version.value,
                    preservationEnvelope = envelope).toEntity())
                envelope?.let { database.contactPayloadDao().upsert(it.toEntity(account, id)) }
                database.outboxDao().upsert(intent.copy(remoteVersion = remote.version.value))
                // Keep snapshots and the exact choice until acknowledgement, including across retries.
                MutationPreparation.UploadAllowed
            }
        }
    }

    private fun blocked() = MutationPreparation.ActionRequired(MutationPreparationActionRequiredReason.REMOTE_UPDATE_CONFLICT)
    private fun ContactConflictEntity.summary() = ContactConflictSummary(contactId, generation, localRevision, remoteVersion,
        choice?.let(ContactConflictChoice::valueOf), remoteDeleted)

    companion object {
        const val CONFLICT = "REMOTE_UPDATE_CONFLICT"
        private const val CHUNK_BYTES = 128 * 1024
    }
}
