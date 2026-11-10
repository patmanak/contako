package com.patmanak.contako.data.local

import androidx.room.withTransaction
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.AvailableContactGroups
import com.patmanak.contako.data.gateway.RemoteContactGroup
import com.patmanak.contako.data.gateway.RemoteGroupId
import com.patmanak.contako.data.sync.RemoteGroupReconciliationStore
import com.patmanak.contako.domain.model.ContactGroup
import com.patmanak.contako.domain.model.ContactGroupDefaults
import java.security.MessageDigest

/** Group reconciliation uses the D-024 local fallback because Proton exposes no comparable time. */
internal class RoomRemoteGroupReconciliationStore(
    private val database: ContakoDatabase,
) : RemoteGroupReconciliationStore {
    override suspend fun knownRemoteIds(account: AccountScope): Set<RemoteGroupId> =
        database.contactGroupDao().knownActiveRemoteIds(account.value).mapTo(mutableSetOf(), ::RemoteGroupId)

    override suspend fun commit(account: AccountScope, groups: AvailableContactGroups) {
        database.withTransaction {
            val remoteById = groups.groups.associateBy { it.id.value }
            val stored = database.contactGroupDao().getAll(account.value)
            val storedByRemoteId = stored.mapNotNull { row ->
                row.group.remoteLabelId?.let { it to row }
            }.toMap()

            groups.groups.forEach { remote ->
                val local = storedByRemoteId[remote.id.value]
                if (local == null) importRemote(account, remote) else reconcilePresent(local, remote)
            }
            stored.filter { it.group.remoteLabelId != null && it.group.remoteLabelId !in remoteById }
                .forEach { reconcileAbsent(it) }
        }
    }

    override suspend fun commitDelta(
        account: AccountScope,
        upserts: AvailableContactGroups,
        deletedIds: Set<RemoteGroupId>,
    ) {
        require(upserts.groups.none { it.id in deletedIds })
        database.withTransaction {
            val stored = database.contactGroupDao().getAll(account.value)
            val storedByRemoteId = stored.mapNotNull { row ->
                row.group.remoteLabelId?.let { it to row }
            }.toMap()
            upserts.groups.forEach { remote ->
                val local = storedByRemoteId[remote.id.value]
                if (local == null) importRemote(account, remote) else reconcilePresent(local, remote)
            }
            val deleted = deletedIds.mapTo(mutableSetOf()) { it.value }
            stored.filter { it.group.remoteLabelId in deleted }.forEach { reconcileAbsent(it) }
        }
    }

    private suspend fun importRemote(account: AccountScope, remote: RemoteContactGroup) {
        val domain = ContactGroup(
            accountId = account.value,
            id = remote.id.value,
            name = remote.name,
            color = remote.color ?: DEFAULT_COLOR,
            revision = 1,
            remoteLabelId = remote.id.value,
            remoteVersion = remote.fingerprint(),
        )
        database.contactGroupDao().upsert(domain.toEntity())
    }

    private suspend fun reconcilePresent(local: ContactGroupWithMemberships, remote: RemoteContactGroup) {
        val group = local.toDomain()
        val fingerprint = remote.fingerprint()
        val pendingRevision = group.pendingMutationRevision
        val outbox = pendingRevision?.let {
            database.outboxDao().get(group.accountId, AggregateType.GROUP.name, group.id)
        }
        if (pendingRevision == null) {
            if (group.remoteVersion != fingerprint || group.name != remote.name || group.color != (remote.color ?: DEFAULT_COLOR)) {
                database.contactGroupDao().upsert(
                    group.copy(
                        name = remote.name,
                        color = remote.color ?: DEFAULT_COLOR,
                        revision = group.revision + 1,
                        remoteVersion = fingerprint,
                        conflictState = null,
                        isDeleted = false,
                    ).toEntity(),
                )
            }
            return
        }
        check(outbox != null && outbox.revision == pendingRevision)
        val partial = RoomPartialGroupMutationStore(database)
        partial.ensureScoped(local)
        // Qualification can park an unverifiable legacy intention for explicit review.
        // Continue only from the resulting row, never the previously captured PARTIAL state.
        val currentIntent = requireNotNull(database.outboxDao().get(group.accountId, AggregateType.GROUP.name, group.id))
        check(currentIntent.revision == pendingRevision)
        if (partial.isDeferredOnly(group.accountId, group.id, group.revision) && !group.isDeleted && group.conflictState == null) {
            // Only deferred links remain local intent. Delivered metadata follows Proton again.
            check(database.outboxDao().updateDeferredGroupRemoteBaseline(group.accountId, group.id,
                currentIntent.revision, currentIntent.state, currentIntent.remoteVersion, remote.id.value, fingerprint) == 1)
            database.contactGroupDao().upsert(group.copy(name = remote.name,
                color = remote.color ?: DEFAULT_COLOR, remoteVersion = fingerprint).toEntity())
            return
        }
        if (currentIntent.state !in setOf(DurableMutationState.PENDING.name, DurableMutationState.PARTIAL.name)) return
        if (currentIntent.remoteVersion == fingerprint) return
        if (currentIntent.operation == MutationOperation.DELETE.name) {
            block(group, currentIntent, "GROUP_EDIT_DELETE_RECOVERY_REQUIRED")
        } else {
            // No remote group timestamp is comparable, so D-024 deterministically preserves local.
            check(
                database.outboxDao().updateCheckedRemoteBaseline(
                    group.accountId,
                    AggregateType.GROUP.name,
                    group.id,
                    currentIntent.revision,
                    remote.id.value,
                    fingerprint,
                ) == 1,
            )
            database.contactGroupDao().upsert(group.copy(remoteVersion = fingerprint).toEntity())
        }
    }

    private suspend fun reconcileAbsent(local: ContactGroupWithMemberships) {
        val group = local.toDomain()
        // Replayed tombstones must not create fresh revisions after an already durable deletion.
        // A newly queued local intent still needs the normal absence/conflict resolution below.
        if (group.isDeleted && group.pendingMutationRevision == null) return
        val pendingRevision = group.pendingMutationRevision
        val outbox = pendingRevision?.let {
            database.outboxDao().get(group.accountId, AggregateType.GROUP.name, group.id)
        }
        if (pendingRevision != null) {
            check(outbox != null && outbox.revision == pendingRevision)
            if (outbox.operation == MutationOperation.DELETE.name) {
                database.contactGroupDao().upsert(
                    group.copy(pendingMutationRevision = null, conflictState = null, isDeleted = true).toEntity(),
                )
                check(
                    database.outboxDao().deleteRevision(
                        group.accountId,
                        AggregateType.GROUP.name,
                        group.id,
                        outbox.revision,
                    ) == 1,
                )
            } else {
                block(group, outbox, "REMOTE_GROUP_DELETION_RECOVERY_REQUIRED")
            }
            return
        }
        database.contactGroupDao().upsert(
            group.copy(
                revision = group.revision + 1,
                pendingMutationRevision = null,
                conflictState = null,
                isDeleted = true,
            ).toEntity(),
        )
        database.contactGroupDao().deleteMemberships(group.accountId, group.id)
    }

    private suspend fun block(group: ContactGroup, outbox: OutboxMutationEntity, reason: String) {
        database.contactGroupDao().upsert(group.copy(conflictState = reason).toEntity())
        if (outbox.state == DurableMutationState.ACTION_REQUIRED.name) {
            // A newly verified group deletion can refine an already blocked intention;
            // it must not require or manufacture a transport retry first.
            check(database.outboxDao().get(group.accountId, AggregateType.GROUP.name, group.id) == outbox)
            database.outboxDao().upsert(outbox.copy(errorCategory = "CONFLICT", blockedReason = reason))
            return
        }
        check(
            database.outboxDao().blockConflict(
                group.accountId,
                AggregateType.GROUP.name,
                group.id,
                outbox.revision,
                reason,
            ) == 1,
        )
    }

    companion object {
        const val DEFAULT_COLOR = ContactGroupDefaults.CREATE_COLOR
    }
}

internal fun RemoteContactGroup.fingerprint(): String {
    val bytes = listOf(id.value, name, color.orEmpty()).joinToString("\u0000").toByteArray(Charsets.UTF_8)
    return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
