package com.patmanak.contako.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.withTransaction
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.RemoteEmailId
import com.patmanak.contako.data.gateway.RemoteGroupId
import com.patmanak.contako.data.proton.PROTON_EMAIL_ID_KEY
import com.patmanak.contako.data.proton.PROTON_GROUP_IDS_KEY
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.model.GroupMembership

/** A verified remote edge catalog is not interchangeable with an Android membership baseline. */
@Entity(tableName = "remote_group_dependency_baselines", primaryKeys = ["account_id", "group_id"])
internal data class RemoteGroupDependencyBaselineEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "group_id") val groupId: String,
    @ColumnInfo(name = "remote_label_id") val remoteLabelId: String?,
    val complete: Boolean,
) { override fun toString() = "RemoteGroupDependencyBaselineEntity(REDACTED)" }

/** No value FK: removed email occurrences must retain their remote binding until verified acknowledgement. */
@Entity(tableName = "remote_group_dependency_edges",
    primaryKeys = ["account_id", "group_id", "contact_id", "email_value_id", "remote_email_id"])
internal data class RemoteGroupDependencyEdgeEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "group_id") val groupId: String,
    @ColumnInfo(name = "contact_id") val contactId: String,
    @ColumnInfo(name = "email_value_id") val emailValueId: String,
    /** Empty means a durable local dependency with no acknowledged remote binding yet. */
    @ColumnInfo(name = "remote_email_id") val remoteEmailId: String,
    @ColumnInfo(name = "captured_revision") val capturedRevision: Long,
) { override fun toString() = "RemoteGroupDependencyEdgeEntity(REDACTED)" }

internal data class RemoteGroupEmailBindingRecord(
    @ColumnInfo(name = "contact_id") val contactId: String,
    @ColumnInfo(name = "value_id") val valueId: String,
    @ColumnInfo(name = "metadata_encoding") val metadataEncoding: String,
    @ColumnInfo(name = "contact_revision") val contactRevision: Long,
) { override fun toString() = "RemoteGroupEmailBindingRecord(REDACTED)" }

@Dao
internal interface RemoteGroupAssignmentDependencyDao {
    @Query("SELECT * FROM remote_group_dependency_baselines WHERE account_id=:accountId AND group_id=:groupId")
    suspend fun baseline(accountId: String, groupId: String): RemoteGroupDependencyBaselineEntity?
    @Query("SELECT * FROM remote_group_dependency_edges WHERE account_id=:accountId AND group_id=:groupId")
    suspend fun edges(accountId: String, groupId: String): List<RemoteGroupDependencyEdgeEntity>
    @Upsert suspend fun upsertBaseline(value: RemoteGroupDependencyBaselineEntity)
    @Upsert suspend fun upsertEdges(values: List<RemoteGroupDependencyEdgeEntity>)
    @Query("DELETE FROM remote_group_dependency_edges WHERE account_id=:accountId AND group_id=:groupId")
    suspend fun deleteEdges(accountId: String, groupId: String)
    @Query("DELETE FROM remote_group_dependency_baselines WHERE account_id=:accountId AND group_id=:groupId")
    suspend fun deleteBaseline(accountId: String, groupId: String)
    @Query("DELETE FROM remote_group_dependency_edges WHERE account_id=:accountId")
    suspend fun deleteAccountEdges(accountId: String)
    @Query("DELETE FROM remote_group_dependency_baselines WHERE account_id=:accountId")
    suspend fun deleteAccountBaselines(accountId: String)
    @Query("""SELECT v.contact_id, v.id AS value_id, v.metadata_encoding, c.revision AS contact_revision
        FROM contact_values v JOIN contacts c ON c.account_id=v.account_id AND c.id=v.contact_id
        WHERE v.account_id=:accountId AND v.kind='EMAIL'""")
    suspend fun emailBindings(accountId: String): List<RemoteGroupEmailBindingRecord>
    @Query("""SELECT contact.id FROM outbox_mutations intent JOIN contacts contact
        ON contact.account_id=intent.account_id AND contact.id=intent.aggregate_id
        WHERE intent.account_id=:accountId AND intent.aggregate_type='CONTACT'
        AND contact.pending_mutation_revision=intent.revision""")
    suspend fun pendingContactIds(accountId: String): List<String>
}

/** Account-owned dependency evidence; no text matching or provider aggregation is used as identity. */
internal class RoomRemoteGroupAssignmentDependencies(private val database: ContakoDatabase) {
    private val dao get() = database.remoteGroupAssignmentDependencyDao()

    /** Called before replacing canonical memberships, in the caller's Room transaction. */
    suspend fun captureBeforeGroupEdit(account: AccountScope, groupId: String, oldMemberships: List<GroupMembership>) {
        check(database.inTransaction())
        val group = database.contactGroupDao().get(account.value, groupId)?.group
        val existing = dao.baseline(account.value, groupId)
        if (existing == null || existing.remoteLabelId != group?.remoteLabelId) {
            // A legacy remote group remains unqualified until a verified membership read.
            dao.upsertBaseline(RemoteGroupDependencyBaselineEntity(account.value, groupId, group?.remoteLabelId,
                complete = group?.remoteLabelId == null))
        }
        val edges = oldMemberships.mapNotNull { membership ->
            val contact = database.contactDao().getEntity(account.value, membership.contactId) ?: return@mapNotNull null
            val value = database.contactDao().getValue(account.value, membership.contactId, membership.emailValueId)
            RemoteGroupDependencyEdgeEntity(account.value, groupId, membership.contactId, membership.emailValueId,
                value?.let { StringMapCodec.decode(it.metadataEncoding)[PROTON_EMAIL_ID_KEY] }.orEmpty(), contact.revision)
        }
        dao.upsertEdges(edges)
    }

    /** Must precede deleting/replacing values and the cascaded canonical membership edges. */
    suspend fun captureContactBeforeEdit(account: AccountScope, previous: CanonicalContact) {
        check(database.inTransaction())
        require(previous.accountId == account.value)
        val groups = database.contactGroupDao().getAll(account.value)
        val remoteGroups = groups.filter { it.group.remoteLabelId != null }
            .groupBy { requireNotNull(it.group.remoteLabelId) }
        val edges = mutableListOf<RemoteGroupDependencyEdgeEntity>()
        previous.valuesOf(ContactValueKind.EMAIL).forEach { value ->
            val linked = groups.filter { group -> group.memberships.any {
                it.contactId == previous.id && it.emailValueId == value.id
            } }.map { it.group.id }.toMutableSet()
            value.metadata[PROTON_GROUP_IDS_KEY].orEmpty().split(',').filter(String::isNotBlank).forEach { remote ->
                remoteGroups[remote]?.singleOrNull()?.let { linked += it.group.id }
            }
            linked.forEach { groupId -> edges += RemoteGroupDependencyEdgeEntity(account.value, groupId,
                previous.id, value.id, value.metadata[PROTON_EMAIL_ID_KEY].orEmpty(), previous.revision) }
        }
        dao.upsertEdges(edges)
    }

    suspend fun requiresVerifiedMembership(account: AccountScope, groupId: String): Boolean = database.withTransaction {
        val group = database.contactGroupDao().get(account.value, groupId)?.group ?: return@withTransaction true
        val baseline = dao.baseline(account.value, groupId)
        group.remoteLabelId != null && (baseline?.complete != true || baseline.remoteLabelId != group.remoteLabelId)
    }

    /** Records only a fresh account/group-scoped verified read, under the exact expected local revision. */
    suspend fun recordVerifiedMembership(account: AccountScope, groupId: String, expectedGroupRevision: Long,
        remoteLabelId: RemoteGroupId, remoteEmailIds: Set<RemoteEmailId>): Boolean = database.withTransaction {
        val group = database.contactGroupDao().get(account.value, groupId)?.group ?: return@withTransaction false
        if (group.revision != expectedGroupRevision || group.remoteLabelId != remoteLabelId.value) return@withTransaction false
        val mapped = mapRemoteEdges(account, groupId, remoteEmailIds)
        dao.upsertBaseline(RemoteGroupDependencyBaselineEntity(account.value, groupId, remoteLabelId.value, mapped != null))
        if (mapped != null) dao.upsertEdges(mapped)
        mapped != null
    }

    suspend fun hasPendingContactWriteForGroup(account: AccountScope, groupId: String): Boolean = database.withTransaction {
        val group = database.contactGroupDao().get(account.value, groupId) ?: return@withTransaction true
        val pending = dao.pendingContactIds(account.value).toSet()
        if (pending.isEmpty()) return@withTransaction false
        val baseline = dao.baseline(account.value, groupId)
        if (group.group.remoteLabelId != null &&
            (baseline?.complete != true || baseline.remoteLabelId != group.group.remoteLabelId)) return@withTransaction true
        val involved = group.memberships.map { it.contactId }.toSet() + dao.edges(account.value, groupId).map { it.contactId }
        pending.any { it in involved }
    }

    /** Clear old edges only after a verified exact desired-membership acknowledgement under the same revision. */
    suspend fun acknowledgeVerifiedMembership(account: AccountScope, groupId: String, expectedGroupRevision: Long,
        remoteLabelId: RemoteGroupId, remoteEmailIds: Set<RemoteEmailId>): Boolean = database.withTransaction {
        val group = database.contactGroupDao().get(account.value, groupId) ?: return@withTransaction false
        if (group.group.revision != expectedGroupRevision || group.group.remoteLabelId != remoteLabelId.value ||
            hasPendingContactWriteForGroup(account, groupId)) return@withTransaction false
        val desired = group.memberships.map { membership ->
            val contact = database.contactDao().getEntity(account.value, membership.contactId) ?: return@withTransaction false
            val value = database.contactDao().getValue(account.value, membership.contactId, membership.emailValueId)
                ?: return@withTransaction false
            val identity = StringMapCodec.decode(value.metadataEncoding)[PROTON_EMAIL_ID_KEY]?.takeIf(String::isNotBlank)
                ?: return@withTransaction false
            RemoteGroupDependencyEdgeEntity(account.value, groupId, membership.contactId, membership.emailValueId,
                identity, contact.revision)
        }
        if (desired.map { it.remoteEmailId }.toSet() != remoteEmailIds.map { it.value }.toSet() ||
            desired.groupBy { it.remoteEmailId }.any { (_, edges) -> edges.map { it.contactId to it.emailValueId }.distinct().size != 1 })
            return@withTransaction false
        dao.deleteEdges(account.value, groupId)
        dao.upsertEdges(desired)
        dao.upsertBaseline(RemoteGroupDependencyBaselineEntity(account.value, groupId, remoteLabelId.value, true))
        true
    }

    suspend fun deleteGroup(account: AccountScope, groupId: String) = database.withTransaction {
        dao.deleteEdges(account.value, groupId)
        dao.deleteBaseline(account.value, groupId)
    }

    private suspend fun mapRemoteEdges(account: AccountScope, groupId: String,
        remoteEmailIds: Set<RemoteEmailId>): List<RemoteGroupDependencyEdgeEntity>? {
        val current = dao.emailBindings(account.value).mapNotNull { value ->
            StringMapCodec.decode(value.metadataEncoding)[PROTON_EMAIL_ID_KEY]?.takeIf(String::isNotBlank)?.let { identity ->
                RemoteGroupDependencyEdgeEntity(account.value, groupId, value.contactId, value.valueId, identity, value.contactRevision)
            }
        }
        val byRemote = (current + dao.edges(account.value, groupId)).filter { it.remoteEmailId.isNotBlank() }
            .groupBy { it.remoteEmailId }
        return remoteEmailIds.map { identity ->
            val candidates = byRemote[identity.value].orEmpty().groupBy { it.contactId to it.emailValueId }
            if (candidates.size != 1) return null
            candidates.values.single().maxBy { it.capturedRevision }
        }
    }
}
