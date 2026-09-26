package com.patmanak.contako.data.local

import androidx.room.withTransaction
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.AvailableContactGroups
import com.patmanak.contako.data.gateway.RemoteContactGroup
import com.patmanak.contako.data.sync.RemoteGroupReconciliationStore
import com.patmanak.contako.domain.model.ContactGroup
import com.patmanak.contako.domain.model.ContactGroupDefaults
import java.security.MessageDigest

/** Group reconciliation uses the D-024 local fallback because Proton exposes no comparable time. */
internal class RoomRemoteGroupReconciliationStore(
    private val database: ContakoDatabase,
) : RemoteGroupReconciliationStore {
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
        if (outbox.remoteVersion == fingerprint) return
        if (outbox.operation == MutationOperation.DELETE.name) {
            block(group, outbox, "GROUP_EDIT_DELETE_RECOVERY_REQUIRED")
        } else {
            // No remote group timestamp is comparable, so D-024 deterministically preserves local.
            check(
                database.outboxDao().updateCheckedRemoteBaseline(
                    group.accountId,
                    AggregateType.GROUP.name,
                    group.id,
                    outbox.revision,
                    remote.id.value,
                    fingerprint,
                ) == 1,
            )
            database.contactGroupDao().upsert(group.copy(remoteVersion = fingerprint).toEntity())
        }
    }

    private suspend fun reconcileAbsent(local: ContactGroupWithMemberships) {
        val group = local.toDomain()
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
