package com.patmanak.contako.data.local

import androidx.room.withTransaction
import com.patmanak.contako.data.proton.RecreationGroup
import com.patmanak.contako.data.proton.recontextualizeContactForRecreation
import com.patmanak.contako.domain.model.*
import com.patmanak.contako.domain.repository.ContactGroupAssignment
import com.patmanak.contako.domain.repository.SaveResult
import com.patmanak.contako.domain.sync.*
import kotlinx.coroutines.flow.map
import java.util.UUID

/** All consent and content changes share Room's transaction with the canonical/outbox guards. */
internal class RoomDeletionRecoveryStore(
    private val database: ContakoDatabase,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private val dao get() = database.deletionRecoveryDao()
    fun observe(account: String) = dao.observe(account).map { rows -> rows.filterNot {
        it.state in setOf(DeletionRecoveryState.RECREATED.name, DeletionRecoveryState.DISCARDED.name)
    }.map { it.summary() } }

    suspend fun get(account: String, id: String) = dao.get(account, id)
    suspend fun forDestination(account: String, destinationId: String) = dao.forDestination(account, destinationId)
    suspend fun eligible(account: String) = dao.getAll(account).filter { it.state !in TERMINAL &&
        !(it.state == DeletionRecoveryState.READY.name && it.absenceVerified && it.choice == null)
    }.sortedWith(compareBy<DeletionRecoveryEntity> {
        if (it.state in setOf(DeletionRecoveryState.QUEUED.name, DeletionRecoveryState.CLEANUP_PENDING.name)) 0 else 1
    }.thenBy { it.verificationOrder }.thenBy { it.contactId })

    suspend fun markExamined(account: String, id: String, generation: String): Boolean = database.withTransaction {
        val row = dao.get(account, id)?.takeIf { it.generation == generation } ?: return@withTransaction false
        val next = Math.incrementExact(dao.getAll(account).maxOfOrNull { it.verificationOrder } ?: 0)
        dao.upsert(row.copy(verificationOrder = next))
        true
    }

    suspend fun detail(account: String, id: String): DeletionRecoveryDetail? = database.withTransaction {
        val row = dao.get(account, id) ?: return@withTransaction null
        if (row.state == DeletionRecoveryState.DISCARDED.name) {
            return@withTransaction DeletionRecoveryDetail(row.summary(), CanonicalContact(account, id, displayName = row.displayName))
        }
        DeletionRecoveryDetail(row.summary(), snapshot(row), dao.groups(account, id).size)
    }

    /** Inventory absence only discovers a candidate. It cannot make a choice eligible. */
    suspend fun discover(account: String) = database.withTransaction {
        database.outboxDao().getAll(account).filter {
            it.aggregateType == AggregateType.CONTACT.name && it.operation == MutationOperation.UPSERT.name &&
                it.state == DurableMutationState.ACTION_REQUIRED.name && it.blockedReason == REQUIRED
        }.forEach { intent ->
            val stored = database.contactDao().get(account, intent.aggregateId) ?: return@forEach
            val local = stored.contact
            if (local.isDeleted || local.remoteContactId.isNullOrBlank() || local.revision != intent.revision ||
                local.pendingMutationRevision != intent.revision || intent.remoteIdentity != local.remoteContactId) return@forEach
            val existing = dao.get(account, local.id)
            if (existing?.sourceRetired == true && (existing.hasDraft || existing.destinationContactId?.let {
                    database.outboxDao().get(account, AggregateType.CONTACT.name, it)
                } != null)) {
                // This independent object still owns a draft or an engaged destination. A new
                // source incarnation cannot overwrite its provenance/CREATE guard lookup.
                return@forEach
            }
            if (existing != null && existing.localRevision == local.revision && existing.remoteId == local.remoteContactId && sourceMatches(existing)) return@forEach
            if (existing?.hasDraft == true) {
                // Two durable versions now exist: the saved draft and newer canonical/native
                // intent. Keep both and refuse cleanup; silently recapturing would lose the draft.
                dao.upsert(existing.copy(state = DeletionRecoveryState.STALE.name,
                    choice = null, blocker = DeletionRecoveryBlocker.LOCAL_CHANGED.name, absenceVerified = false))
                return@forEach
            }
            val row = DeletionRecoveryEntity(account, local.id, idFactory(), local.revision, 0,
                requireNotNull(local.remoteContactId), stored.toDomain().resolvedDisplayName,
                DeletionRecoveryState.NEEDS_VERIFICATION.name)
            dao.upsert(row)
            writeSnapshot(row, stored.toDomain().copy(preservationEnvelope = database.contactPayloadDao().get(local.ownerKey)?.toDomain()))
            dao.deleteGroups(account, local.id)
            val groups = database.contactGroupDao().getAll(account).flatMap { group ->
                group.memberships.filter { it.contactId == local.id }.map {
                    DeletionRecoveryGroup(account, local.id, group.group.id, it.emailValueId,
                        group.group.remoteLabelId, group.group.name)
                }
            }
            if (groups.isNotEmpty()) dao.insertGroups(groups)
        }
    }

    /** Only the serialized engine calls this after an exact owner/ID structured presence result. */
    suspend fun verifyAbsence(account: String, id: String, generation: String, revision: Long, remoteId: String): Boolean =
        database.withTransaction {
            val row = dao.get(account, id)?.takeIf { it.generation == generation && it.localRevision == revision && it.remoteId == remoteId }
                ?: return@withTransaction false
            if (row.state == DeletionRecoveryState.DISCARDED.name) return@withTransaction false
            if (!sourceMatches(row)) return@withTransaction false
            dao.upsert(row.copy(absenceVerified = true,
                state = when {
                    row.state == DeletionRecoveryState.BLOCKED.name && row.choice != null -> DeletionRecoveryState.QUEUED.name
                    row.state in setOf(DeletionRecoveryState.NEEDS_VERIFICATION.name, DeletionRecoveryState.STALE.name) &&
                        (row.blocker != DeletionRecoveryBlocker.LOCAL_CHANGED.name || row.sourceRetired) -> DeletionRecoveryState.READY.name
                    else -> row.state
                },
                blocker = if (row.blocker == DeletionRecoveryBlocker.REMOTE_PRESENT.name || row.blocker == DeletionRecoveryBlocker.REMOTE_UNAVAILABLE.name ||
                    (row.sourceRetired && row.blocker == DeletionRecoveryBlocker.LOCAL_CHANGED.name))
                    DeletionRecoveryBlocker.NONE.name else row.blocker))
            true
        }

    suspend fun block(account: String, id: String, generation: String, blocker: DeletionRecoveryBlocker): Boolean =
        database.withTransaction {
            val row = dao.get(account, id)?.takeIf { it.generation == generation } ?: return@withTransaction false
            val stale = blocker in setOf(DeletionRecoveryBlocker.LOCAL_CHANGED, DeletionRecoveryBlocker.REMOTE_PRESENT)
            dao.upsert(row.copy(state = if (stale) DeletionRecoveryState.STALE.name else DeletionRecoveryState.BLOCKED.name,
                blocker = blocker.name, choice = if (stale) null else row.choice,
                absenceVerified = row.absenceVerified && !stale && blocker != DeletionRecoveryBlocker.REMOTE_UNAVAILABLE))
            true
        }

    suspend fun queue(account: String, expected: DeletionRecoverySummary, choice: DeletionRecoveryChoice): Boolean =
        database.withTransaction {
            val row = dao.get(account, expected.contactId) ?: return@withTransaction false
            if (row.summary() != expected || !row.absenceVerified ||
                row.state !in setOf(DeletionRecoveryState.READY.name, DeletionRecoveryState.DRAFT.name, DeletionRecoveryState.BLOCKED.name) || !sourceMatches(row))
                return@withTransaction false
            if (row.blocker in setOf(DeletionRecoveryBlocker.LOCAL_CHANGED.name, DeletionRecoveryBlocker.REMOTE_PRESENT.name)) return@withTransaction false
            val engaged = row.destinationContactId?.let { database.contactDao().getEntity(account, it) }
            if (engaged != null) {
                // This CREATE was already committed before the old remote identity reappeared.
                // A fresh explicit choice resumes that exact command, never another allocation.
                if (choice != DeletionRecoveryChoice.RECREATE) return@withTransaction false
                val intent = pendingDestination(row) ?: return@withTransaction false
                if (intent.state != DurableMutationState.ACTION_REQUIRED.name || intent.blockedReason != REQUIRED) return@withTransaction false
                database.outboxDao().upsert(intent.copy(state = DurableMutationState.PENDING.name, blockedReason = null, errorCategory = null))
                dao.upsert(row.copy(state = DeletionRecoveryState.RECREATED.name, choice = null, hasDraft = false,
                    blocker = DeletionRecoveryBlocker.NONE.name))
                return@withTransaction true
            }
            if (choice == DeletionRecoveryChoice.RECREATE) {
                val destination = row.destinationContactId ?: idFactory().also { require(it.isNotBlank()) }
                if (destination == row.contactId || database.contactDao().getEntity(account, destination) != null) return@withTransaction false
                try { recreation(row, destination) } catch (_: IllegalArgumentException) {
                    dao.upsert(row.copy(state = DeletionRecoveryState.BLOCKED.name, blocker = DeletionRecoveryBlocker.CONTENT_NOT_TRANSFERABLE.name))
                    return@withTransaction false
                }
                dao.upsert(row.copy(choice = choice.name, state = DeletionRecoveryState.QUEUED.name,
                    blocker = DeletionRecoveryBlocker.NONE.name, destinationContactId = destination))
            } else dao.upsert(row.copy(choice = choice.name, state = DeletionRecoveryState.QUEUED.name, blocker = DeletionRecoveryBlocker.NONE.name))
            true
        }

    /** Draft Save changes only this independent snapshot and its consent revision. */
    suspend fun saveDraft(account: String, expected: DeletionRecoverySummary, contact: CanonicalContact): Boolean =
        database.withTransaction {
            val row = dao.get(account, expected.contactId) ?: return@withTransaction false
            if (row.summary() != expected || !row.hasDraft || row.choice != null || row.state in TERMINAL - DeletionRecoveryState.DRAFT.name ||
                contact.accountId != account || contact.id != row.contactId) return@withTransaction false
            val before = snapshot(row)
            // The editor owns fields only; provenance and source identity cannot be replaced by callers.
            if (contact.remoteContactId != before.remoteContactId || contact.remoteVCardUid != before.remoteVCardUid ||
                contact.remoteVersion != before.remoteVersion || contact.preservationEnvelope != before.preservationEnvelope ||
                contact.values.map { it.id }.distinct().size != contact.values.size) return@withTransaction false
            val previous = before.values.associateBy(ContactValue::id)
            if (contact.values.any { value -> previous[value.id]?.let { old ->
                    old.kind == value.kind && old.value == value.value && old.label == value.label &&
                        old.components == value.components && old.metadata == value.metadata && old.preservationKey == value.preservationKey
                } != true &&
                    com.patmanak.contako.data.proton.ProtonContactFieldValidator().editableValueError(value) != null }) return@withTransaction false
            val committed = contact.copy(revision = before.revision, pendingMutationRevision = before.pendingMutationRevision,
                conflictState = before.conflictState, isDeleted = before.isDeleted)
            writeSnapshot(row, committed)
            val retainedEmailIds = committed.valuesOf(ContactValueKind.EMAIL).map { it.id }.toSet()
            val removedEmailIds = before.valuesOf(ContactValueKind.EMAIL).map { it.id }.filterNot { it in retainedEmailIds }
            if (removedEmailIds.isNotEmpty()) dao.deleteGroupsForEmails(account, row.contactId, removedEmailIds)
            dao.upsert(row.copy(draftRevision = row.draftRevision + 1, displayName = committed.resolvedDisplayName,
                absenceVerified = false, destinationContactId = null, destinationRevision = null,
                state = DeletionRecoveryState.NEEDS_VERIFICATION.name, blocker = DeletionRecoveryBlocker.NONE.name))
            true
        }

    suspend fun stageDraft(account: String, id: String, generation: String): Boolean = database.withTransaction {
        val row = dao.get(account, id)?.takeIf { it.generation == generation } ?: return@withTransaction false
        if (row.choice == null || !row.absenceVerified || !sourceMatches(row) ||
            row.state !in setOf(DeletionRecoveryState.QUEUED.name, DeletionRecoveryState.CLEANUP_PENDING.name)) return@withTransaction false
        snapshot(row) // Validate complete durable chunks before permitting cleanup.
        dao.upsert(row.copy(state = DeletionRecoveryState.CLEANUP_PENDING.name,
            hasDraft = row.hasDraft || row.choice != DeletionRecoveryChoice.DISCARD.name))
        true
    }

    suspend fun markCleanup(account: String, id: String, generation: String, epoch: Long, raw: Long, version: Long, ledgerRevision: Long): Boolean =
        database.withTransaction {
            val row = dao.get(account, id)?.takeIf { it.generation == generation && it.state == DeletionRecoveryState.CLEANUP_PENDING.name }
                ?: return@withTransaction false
            if (row.sourceRetired) return@withTransaction false
            val ledger = database.androidProjectionLedgerDao().get(account, id) ?: return@withTransaction false
            if (!sourceMatches(row) || epoch < 0 || raw <= 0 || version < 0 || ledger.providerEpoch != epoch ||
                ledger.rawContactLocator != raw || ledger.revision != ledgerRevision) return@withTransaction false
            if (row.cleanupRaw != null && (row.cleanupEpoch != epoch || row.cleanupRaw != raw || row.cleanupVersion != version ||
                    row.cleanupLedgerRevision != ledgerRevision)) return@withTransaction false
            dao.upsert(row.copy(cleanupEpoch = epoch, cleanupRaw = raw, cleanupVersion = version, cleanupLedgerRevision = ledgerRevision))
            true
        }

    /** Caller has just proved the prepared provider deletion, or absence of any scoped raw row. */
    suspend fun complete(account: String, id: String, generation: String): Boolean = database.withTransaction {
        val row = dao.get(account, id)?.takeIf { it.generation == generation && it.state == DeletionRecoveryState.CLEANUP_PENDING.name }
            ?: return@withTransaction false
        if (!row.absenceVerified || !sourceMatches(row)) return@withTransaction false
        val ledgerDao = database.androidProjectionLedgerDao()
        val ledger = if (row.sourceRetired) null else ledgerDao.get(account, id)
        if (!row.sourceRetired && row.cleanupRaw != null) {
            if (ledger == null || ledger.providerEpoch != row.cleanupEpoch || ledger.rawContactLocator != row.cleanupRaw ||
                ledger.revision != row.cleanupLedgerRevision) return@withTransaction false
        } else if (!row.sourceRetired && ledger?.rawContactLocator != null) return@withTransaction false
        val choice = row.choice?.let(DeletionRecoveryChoice::valueOf) ?: return@withTransaction false
        var destinationRevision = row.destinationRevision
        if (choice == DeletionRecoveryChoice.RECREATE) {
            val destination = row.destinationContactId ?: return@withTransaction false
            if (database.contactDao().getEntity(account, destination) != null) return@withTransaction false
            val candidate = try { recreation(row, destination) } catch (_: IllegalArgumentException) { return@withTransaction false }
            val groups = dao.groups(account, id)
            val result = RoomContactRepository(database).saveContactWithGroupAssignments(candidate,
                groups.map { ContactGroupAssignment(it.groupId, it.emailValueId) }.toSet(), groups.map { it.groupId }.toSet(), null)
            if (result !is SaveResult.Saved) return@withTransaction false
            destinationRevision = result.value.revision
        }
        if (!row.sourceRetired) {
            val old = database.contactDao().getEntity(account, id) ?: return@withTransaction false
            database.contactDao().upsert(old.copy(isDeleted = true, pendingMutationRevision = null, conflictState = null))
            database.outboxDao().deleteRevision(account, AggregateType.CONTACT.name, id, row.localRevision)
            database.contactConflictDao().delete(account, id)
            // This retirement is performed once. Later independent draft actions cannot erase
            // another incarnation's canonical/native/group intent under the historical remote ID.
            database.contactGroupDao().getAll(account).filter { group -> group.memberships.any { it.contactId == id } }.forEach { group ->
                check(RoomContactRepository(database).saveGroup(group.toDomain().copy(
                    memberships = group.toDomain().memberships.filterNot { it.contactId == id })) is SaveResult.Saved)
            }
            if (ledger != null) ledgerDao.update(ledger.copy(revision = ledger.revision + 1, rawContactLocator = null,
                projectionState = "DETACHED", pendingProjectionFingerprint = null,
                adoptionState = if (ledger.sourceIdentity == null) "AWAITING_REMOTE_ID" else "SOURCE_ID_PENDING",
                tombstoneState = "REMOTE_CONVERGED"))
        }
        val state = when (choice) {
            DeletionRecoveryChoice.KEEP_DRAFT -> DeletionRecoveryState.DRAFT
            DeletionRecoveryChoice.RECREATE -> DeletionRecoveryState.RECREATED
            DeletionRecoveryChoice.DISCARD -> DeletionRecoveryState.DISCARDED
        }
        dao.upsert(row.copy(state = state.name, choice = null, hasDraft = choice == DeletionRecoveryChoice.KEEP_DRAFT,
            destinationRevision = destinationRevision,
            sourceRetired = true,
            blocker = DeletionRecoveryBlocker.NONE.name, cleanupEpoch = null, cleanupRaw = null, cleanupVersion = null, cleanupLedgerRevision = null))
        if (choice == DeletionRecoveryChoice.DISCARD) {
            dao.deleteChunks(account, id)
            dao.deleteGroups(account, id)
        }
        true
    }

    suspend fun suspendDestination(account: String, id: String, generation: String): Boolean = database.withTransaction {
        val row = dao.get(account, id)?.takeIf { it.generation == generation } ?: return@withTransaction false
        val destination = row.destinationContactId ?: return@withTransaction false
        val intent = database.outboxDao().get(account, AggregateType.CONTACT.name, destination) ?: return@withTransaction false
        database.outboxDao().upsert(intent.copy(state = DurableMutationState.ACTION_REQUIRED.name, blockedReason = REQUIRED, errorCategory = "CONFLICT"))
        dao.upsert(row.copy(state = DeletionRecoveryState.STALE.name, blocker = DeletionRecoveryBlocker.REMOTE_PRESENT.name, absenceVerified = false))
        true
    }

    private suspend fun sourceMatches(row: DeletionRecoveryEntity): Boolean {
        if (row.sourceRetired) {
            // The committed snapshot is now independent of future reuse of the old remote ID.
            // A resumed CREATE additionally belongs to the exact already allocated destination.
            if (snapshot(row).remoteContactId != row.remoteId) return false
            return row.hasDraft || (row.destinationRevision != null && pendingDestination(row) != null)
        }
        val source = database.contactDao().getEntity(row.accountId, row.contactId) ?: return false
        if (source.remoteContactId != row.remoteId) return false
        val intent = database.outboxDao().get(row.accountId, AggregateType.CONTACT.name, row.contactId)
        if (source.revision != row.localRevision) return false
        if (source.isDeleted) return false
        if (intent == null) return false
        if (source.pendingMutationRevision != row.localRevision || intent.revision != row.localRevision ||
            intent.remoteIdentity != row.remoteId || intent.operation != MutationOperation.UPSERT.name ||
            intent.state != DurableMutationState.ACTION_REQUIRED.name || intent.blockedReason != REQUIRED) return false
        val captured = dao.groups(row.accountId, row.contactId).toSet()
        val current = database.contactGroupDao().getAll(row.accountId).flatMap { group ->
            group.memberships.filter { it.contactId == row.contactId }.map {
                DeletionRecoveryGroup(row.accountId, row.contactId, group.group.id, it.emailValueId,
                    group.group.remoteLabelId, group.group.name)
            }
        }.toSet()
        return captured == current
    }

    private suspend fun recreation(row: DeletionRecoveryEntity, destination: String): CanonicalContact {
        val edges = dao.groups(row.accountId, row.contactId)
        val groups = edges.map { it.groupId }.distinct().map { id ->
            val group = requireNotNull(database.contactGroupDao().get(row.accountId, id)).group
            require(!group.isDeleted && edges.filter { it.groupId == id }.all { it.remoteLabelId == group.remoteLabelId })
            RecreationGroup(id, group.name, group.remoteLabelId)
        }
        return recontextualizeContactForRecreation(snapshot(row), destination, groups,
            edges.map { ContactGroupAssignment(it.groupId, it.emailValueId) }.toSet())
    }

    private suspend fun pendingDestination(row: DeletionRecoveryEntity): OutboxMutationEntity? {
        val id = row.destinationContactId ?: return null
        val expectedRevision = row.destinationRevision ?: return null
        val destination = database.contactDao().getEntity(row.accountId, id) ?: return null
        val intent = database.outboxDao().get(row.accountId, AggregateType.CONTACT.name, id) ?: return null
        return intent.takeIf {
            !destination.isDeleted && destination.remoteContactId == null && destination.revision == expectedRevision &&
                destination.pendingMutationRevision == expectedRevision && it.revision == expectedRevision &&
                it.operation == MutationOperation.UPSERT.name && it.remoteIdentity == null
        }
    }

    private suspend fun snapshot(row: DeletionRecoveryEntity): CanonicalContact {
        val chunks = dao.chunks(row.accountId, row.contactId)
        require(chunks.isNotEmpty() && chunks.size <= 384 && chunks.map { it.position } == chunks.indices.toList())
        val bytes = java.io.ByteArrayOutputStream()
        chunks.forEach { require(it.bytes.size <= CHUNK_BYTES); bytes.write(it.bytes) }
        return ContactConflictSnapshotCodec.decode(bytes.toByteArray()).also { require(it.accountId == row.accountId && it.id == row.contactId) }
    }

    private suspend fun writeSnapshot(row: DeletionRecoveryEntity, contact: CanonicalContact) {
        val bytes = ContactConflictSnapshotCodec.encode(contact)
        val chunks = (bytes.indices step CHUNK_BYTES).mapIndexed { position, offset ->
            DeletionRecoveryChunk(row.accountId, row.contactId, position, bytes.copyOfRange(offset, minOf(offset + CHUNK_BYTES, bytes.size)))
        }
        dao.deleteChunks(row.accountId, row.contactId)
        dao.insertChunks(chunks)
    }

    private fun DeletionRecoveryEntity.summary() = DeletionRecoverySummary(contactId, generation, localRevision, draftRevision,
        displayName, DeletionRecoveryState.valueOf(state), choice?.let(DeletionRecoveryChoice::valueOf),
        DeletionRecoveryBlocker.valueOf(blocker), hasDraft, absenceVerified, destinationContactId, destinationRevision)

    companion object {
        const val REQUIRED = "REMOTE_DELETION_RECOVERY_REQUIRED"
        private const val CHUNK_BYTES = 128 * 1024
        private val TERMINAL = setOf(DeletionRecoveryState.DRAFT.name, DeletionRecoveryState.RECREATED.name, DeletionRecoveryState.DISCARDED.name)
    }
}
