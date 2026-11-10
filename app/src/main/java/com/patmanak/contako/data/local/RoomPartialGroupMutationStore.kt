package com.patmanak.contako.data.local

import androidx.room.withTransaction
import com.patmanak.contako.data.sync.DurableMutationCommand
import com.patmanak.contako.data.sync.RemoteMutationOperation
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.model.GroupMembership
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.data.gateway.RemoteEmailId
import com.patmanak.contako.data.proton.PROTON_EMAIL_ID_KEY
import com.patmanak.contako.data.proton.AuthoritativeEmailGroupMembership
import com.patmanak.contako.data.proton.valueSourceCards
import com.patmanak.contako.data.proton.unfoldBoundedVCard
import com.patmanak.contako.data.proton.ProtonContactVCardCodec
import com.patmanak.contako.domain.model.CanonicalContact

/** Retained group intent waiting for local input, not transport retry or remote acknowledgement. */
internal class RoomPartialGroupMutationStore(private val database: ContakoDatabase) {
    internal data class ContactWriteScope(val groupId: String, val revision: Long, val remoteLabelId: String, val name: String,
        val emailIdsByValueId: Map<String, RemoteEmailId>) {
        override fun toString() = "ContactWriteScope(REDACTED)"
    }

    /** Capture only the edited contact's currently desired links, never the old whole group. */
    suspend fun contactWriteScopes(command: DurableMutationCommand): List<ContactWriteScope>? = database.withTransaction {
        val contact = database.contactDao().get(command.accountId, command.aggregateId)?.toDomain()
            ?: return@withTransaction null
        val intent = database.outboxDao().get(command.accountId, AggregateType.CONTACT.name, command.aggregateId)
            ?: return@withTransaction null
        if (contact.revision != command.revision || contact.pendingMutationRevision != command.revision ||
            intent.revision != command.revision || intent.state != DurableMutationState.IN_FLIGHT.name)
            return@withTransaction null
        val wire = RoomContactUploadOverlays(database).wireProjection(contact) ?: return@withTransaction null
        val emailIds = wire.valuesOf(ContactValueKind.EMAIL).mapNotNull { value ->
            value.metadata[PROTON_EMAIL_ID_KEY]?.takeIf(String::isNotBlank)?.let { value.id to RemoteEmailId(it) }
        }.toMap()
        if (emailIds.values.distinct().size != emailIds.size) return@withTransaction null
        database.contactGroupDao().getAll(command.accountId).mapNotNull { stored ->
            val group = stored.group
            ensureScoped(stored)
            if (group.isDeleted || group.conflictState != null || group.remoteLabelId == null ||
                !isDeferredOnly(command.accountId, group.id, group.revision)) return@mapNotNull null
            val relevant = stored.memberships.any { it.contactId == contact.id } ||
                wire.valuesOf(ContactValueKind.CATEGORY).any { it.value == group.name }
            if (!relevant || emailIds.isEmpty()) null else
                ContactWriteScope(group.id, group.revision, group.remoteLabelId, group.name, emailIds)
        }
    }

    /** Persist a fresh positive membership witness before a PUT can replace clear categories. */
    suspend fun retainContactWriteMemberships(command: DurableMutationCommand, scope: ContactWriteScope,
        verified: AuthoritativeEmailGroupMembership): Boolean = database.withTransaction {
        if (verified.account.value != command.accountId || verified.groupId.value != scope.remoteLabelId)
            return@withTransaction false
        val current = contactWriteScopes(command)?.singleOrNull { it.groupId == scope.groupId }
            ?: return@withTransaction false
        if (current != scope) return@withTransaction false
        val dao = database.remoteGroupAssignmentDependencyDao()
        val retained = dao.edges(command.accountId, scope.groupId).filter { it.isDeferredIntent }
            .map { it.contactId to it.emailValueId }.toSet()
        val members = verified.emailIds.toSet()
        val positive = scope.emailIdsByValueId.filterValues { it in members }
        val group = requireNotNull(database.contactGroupDao().get(command.accountId, scope.groupId)).toDomain()
        database.contactGroupDao().insertMemberships(positive.keys.map {
            GroupMembership(command.aggregateId, it).toEntity(group)
        })
        dao.upsertEdges(positive.mapNotNull { (valueId, remoteId) ->
            if (remoteId !in members || (command.aggregateId to valueId) in retained) null
            else RemoteGroupDependencyEdgeEntity(command.accountId, scope.groupId, command.aggregateId,
                valueId, remoteId.value, command.revision, isDeferredIntent = true)
        })
        true
    }

    /** Clear categories are not part of the contact conflict fingerprint. A proved remote
     * removal must reach the pending payload as well as the later additive assignment step. */
    suspend fun reconcileContactWriteCategories(command: DurableMutationCommand, wire: CanonicalContact,
        scopes: List<ContactWriteScope>, memberships: Map<String, Set<RemoteEmailId>>): CanonicalContact? = database.withTransaction {
        if (scopes.isEmpty()) return@withTransaction wire
        val currentScopes = contactWriteScopes(command) ?: return@withTransaction null
        if (!currentScopes.containsAll(scopes)) return@withTransaction null
        val groups = database.contactGroupDao().getAll(command.accountId).filter { !it.group.isDeleted }
        val byName = scopes.groupBy { it.name }
        val removed = mutableSetOf<String>()
        for (category in wire.valuesOf(ContactValueKind.CATEGORY)) {
            val candidates = byName[category.value] ?: continue // Unknown and independent categories survive.
            val scope = candidates.singleOrNull() ?: return@withTransaction null
            if (groups.count { it.group.name == category.value } != 1) return@withTransaction null
            val categoryGroup = sourceGroup(wire, category, "CATEGORIES", setOf(0)) ?: return@withTransaction null
            val matching = wire.valuesOf(ContactValueKind.EMAIL).filter {
                sourceGroup(wire, it, "EMAIL", setOf(0, 2)).equals(categoryGroup, ignoreCase = true)
            }
            val email = matching.singleOrNull() ?: return@withTransaction null
            val identity = scope.emailIdsByValueId[email.id] ?: return@withTransaction null
            if (identity !in (memberships[scope.groupId] ?: return@withTransaction null)) {
                // A category is editable. Negative remote evidence cannot erase a new
                // user value merely because its old ITEMn provenance remains attached.
                if (!ProtonContactVCardCodec().hasUnchangedPreservedValue(wire, category)) return@withTransaction null
                removed += category.id
            }
        }
        // Adopt freshly observed positives for another occurrence of this same edited
        // contact. This is not authority over the other contacts in the group.
        for (scope in scopes) {
            val members = memberships[scope.groupId] ?: return@withTransaction null
            val group = requireNotNull(database.contactGroupDao().get(command.accountId, scope.groupId)).toDomain()
            database.contactGroupDao().insertMemberships(scope.emailIdsByValueId.filterValues { it in members }.keys
                .map { GroupMembership(command.aggregateId, it).toEntity(group) })
        }
        if (removed.isNotEmpty()) {
            val stored = database.contactDao().get(command.accountId, command.aggregateId) ?: return@withTransaction null
            if (stored.contact.revision != command.revision || stored.contact.pendingMutationRevision != command.revision)
                return@withTransaction null
            // Keep the user edit, withheld values, source envelope and outbox revision intact.
            // Only these unchanged source occurrences have a fresh authoritative negative proof.
            val retained = stored.values.map { it.id }.filterNot { it in removed }
            database.contactDao().deleteValuesNotIn(command.accountId, command.aggregateId, retained)
        }
        wire.copy(values = wire.values.filterNot { it.id in removed })
    }

    private fun sourceGroup(contact: CanonicalContact, value: ContactValue, property: String, cardTypes: Set<Int>): String? {
        val key = value.preservationKey ?: return null
        if (value.id != "${value.kind.name.lowercase(java.util.Locale.ROOT)}@$key") return null
        val cardKey = key.substringBeforeLast("#line=", "")
        if (cardTypes.none { cardKey.startsWith("proton-card-$it-") }) return null
        val index = key.substringAfterLast("#line=", "").substringBefore("#value=").toIntOrNull() ?: return null
        val raw = contact.preservationEnvelope?.valueSourceCards()?.get(cardKey) ?: return null
        val line = unfoldBoundedVCard(raw).lines.getOrNull(index) ?: return null
        val header = Regex("^([A-Za-z0-9-]+)\\.([A-Za-z0-9-]+)(?=[:;])").find(line) ?: return null
        if (!header.groupValues[2].equals(property, ignoreCase = true)) return null
        return header.groupValues[1]
    }

    /** An explicit remote choice cancels only reassertions of formerly delivered links. */
    suspend fun discardContactWriteMemberships(accountId: String, contactId: String) {
        val dao = database.remoteGroupAssignmentDependencyDao()
        for (stored in database.contactGroupDao().getAll(accountId)) {
            if (!isDeferredOnly(accountId, stored.group.id, stored.group.revision)) continue
            val edges = dao.edges(accountId, stored.group.id)
            val omitted = edges.filter { it.contactId == contactId && it.isDeferredIntent && it.remoteEmailId.isEmpty() }
                .map { it.emailValueId }.toSet()
            dao.upsertEdges(edges.map { edge ->
                if (edge.contactId == contactId && edge.isDeferredIntent && edge.remoteEmailId.isNotEmpty() &&
                    edge.emailValueId !in omitted) edge.copy(isDeferredIntent = false) else edge
            })
        }
    }
    suspend fun park(command: DurableMutationCommand): Boolean = database.withTransaction {
        if (command.aggregateType != AggregateType.GROUP.name || command.operation != RemoteMutationOperation.ASSIGNMENTS)
            return@withTransaction false
        val intent = database.outboxDao().get(command.accountId, command.aggregateType, command.aggregateId)
            ?: return@withTransaction false
        val group = database.contactGroupDao().get(command.accountId, command.aggregateId)?.group
            ?: return@withTransaction false
        if (intent.revision != command.revision || intent.state != DurableMutationState.IN_FLIGHT.name ||
            intent.operation != MutationOperation.ASSIGNMENTS.name || intent.blockedReason != null ||
            group.isDeleted || group.conflictState != null || group.revision != command.revision || group.pendingMutationRevision != command.revision ||
            group.remoteLabelId == null || group.remoteLabelId != intent.remoteIdentity ||
            group.remoteVersion != intent.remoteVersion ||
            intent.remoteIdentity != command.expectedRemoteIdentity || intent.remoteVersion != command.expectedRemoteVersion)
            return@withTransaction false
        if (!retainDeferredScope(intent, database.contactGroupDao().get(command.accountId, command.aggregateId)!!))
            return@withTransaction false
        database.outboxDao().upsert(intent.copy(state = DurableMutationState.PARTIAL.name, errorCategory = null))
        true
    }

    /** Called in the same canonical ACK/adoption transaction that may replace email identities. */
    suspend fun wakeForContact(accountId: String, contactId: String) = database.withTransaction {
        for (intent in database.outboxDao().getAll(accountId)) {
            if (intent.aggregateType != AggregateType.GROUP.name || intent.operation != MutationOperation.ASSIGNMENTS.name ||
                intent.state != DurableMutationState.PARTIAL.name || intent.blockedReason != null) continue
            val stored = database.contactGroupDao().get(accountId, intent.aggregateId) ?: continue
            val group = stored.group
            if (!ensureScoped(stored)) continue
            val deferred = database.remoteGroupAssignmentDependencyDao().edges(accountId, intent.aggregateId)
                .filter { it.isDeferredIntent }
            if (group.revision != intent.revision || group.pendingMutationRevision != intent.revision || group.isDeleted ||
                group.remoteLabelId == null || group.remoteLabelId != intent.remoteIdentity ||
                deferred.none { it.contactId == contactId }) continue
            database.outboxDao().upsert(intent.copy(state = DurableMutationState.PENDING.name, requiresReconciliation = true))
        }
    }

    /** The revision-scoped marker survives retry/error categories and process death. */
    suspend fun isDeferredOnly(accountId: String, groupId: String, revision: Long): Boolean {
        if (database.remoteGroupAssignmentDependencyDao().baseline(accountId, groupId)?.deferredOnlyRevision != revision)
            return false
        val group = database.contactGroupDao().get(accountId, groupId)?.group ?: return false
        val intent = database.outboxDao().get(accountId, AggregateType.GROUP.name, groupId) ?: return false
        return group.pendingMutationRevision == revision && intent.revision == revision &&
            intent.operation == MutationOperation.ASSIGNMENTS.name
    }

    suspend fun deferredMemberships(accountId: String, groupId: String,
        includeContactWriteMemberships: Boolean = true): Set<GroupMembership> =
        database.remoteGroupAssignmentDependencyDao().edges(accountId, groupId).filter { it.isDeferredIntent &&
            (includeContactWriteMemberships || it.remoteEmailId.isEmpty()) }
            .mapTo(mutableSetOf()) { GroupMembership(it.contactId, it.emailValueId) }

    /** Older PARTIAL rows also prove the accepted subset was delivered, never the whole snapshot. */
    suspend fun ensureScoped(stored: ContactGroupWithMemberships): Boolean = database.withTransaction {
        val group = stored.group
        if (isDeferredOnly(group.accountId, group.id, group.revision)) return@withTransaction true
        val current = database.contactGroupDao().get(group.accountId, group.id) ?: return@withTransaction false
        if (current != stored) return@withTransaction false
        val intent = database.outboxDao().get(group.accountId, AggregateType.GROUP.name, group.id) ?: return@withTransaction false
        if (intent.state != DurableMutationState.PARTIAL.name || intent.operation != MutationOperation.ASSIGNMENTS.name ||
            intent.blockedReason != null || intent.revision != group.revision ||
            group.pendingMutationRevision != intent.revision || group.isDeleted || group.conflictState != null ||
            group.remoteLabelId == null || intent.remoteIdentity != group.remoteLabelId) return@withTransaction false
        if (retainDeferredScope(intent, stored)) return@withTransaction true
        // A legacy marker without an omission proof cannot identify its original deferred
        // links. Preserve it for explicit review instead of replaying the whole old group.
        database.outboxDao().upsert(intent.copy(state = DurableMutationState.ACTION_REQUIRED.name,
            errorCategory = "RECONCILIATION_INCOMPLETE", blockedReason = "PARTIAL_GROUP_SCOPE_UNVERIFIED"))
        false
    }

    /** Automatic contact rewrites retain the restricted scope; explicit group edits clear it. */
    suspend fun advanceScope(accountId: String, groupId: String, before: Long, after: Long) {
        val dao = database.remoteGroupAssignmentDependencyDao()
        val baseline = dao.baseline(accountId, groupId) ?: return
        val intent = database.outboxDao().get(accountId, AggregateType.GROUP.name, groupId)
        if (baseline.deferredOnlyRevision == before && intent?.revision == before &&
            intent.operation == MutationOperation.ASSIGNMENTS.name)
            dao.upsertBaseline(baseline.copy(deferredOnlyRevision = after))
    }

    suspend fun remapMemberships(accountId: String, contactId: String, retained: List<GroupMembershipEntity>,
        previousEmails: List<ContactValue>, currentEmails: List<ContactValue>) {
        val dao = database.remoteGroupAssignmentDependencyDao()
        for ((groupId, memberships) in retained.groupBy { it.groupId }) {
            val group = database.contactGroupDao().get(accountId, groupId)?.group ?: continue
            if (!isDeferredOnly(accountId, groupId, group.revision)) continue
            val edges = dao.edges(accountId, groupId)
            dao.deleteEdges(accountId, groupId)
            val previousIds = previousEmails.mapNotNull { old ->
                repeatedEmailReplacement(old, previousEmails, currentEmails)?.id?.let { it to old.id }
            }.groupBy({ it.first }, { it.second })
            dao.upsertEdges(edges.filterNot { it.isDeferredIntent && it.contactId == contactId } + memberships.flatMap { edge ->
                val previousId = previousIds[edge.emailValueId]?.singleOrNull()
                    ?: error("DEFERRED_MEMBERSHIP_REMAP_UNPROVED")
                val witnesses = edges.filter { it.isDeferredIntent && it.contactId == contactId && it.emailValueId == previousId }
                check(witnesses.isNotEmpty()) { "DEFERRED_MEMBERSHIP_WITNESS_MISSING" }
                witnesses.map { witness -> witness.copy(emailValueId = edge.emailValueId) }
            })
        }
    }

    /** The same exact current omission proof used by the contact upload projection. */
    suspend fun isOmitted(accountId: String, membership: GroupMembership): Boolean {
        val contact = database.contactDao().get(accountId, membership.contactId)?.toDomain() ?: return false
        val value = contact.values.singleOrNull { it.id == membership.emailValueId && it.kind == ContactValueKind.EMAIL }
            ?: return false
        if (!value.metadata[com.patmanak.contako.data.proton.PROTON_EMAIL_ID_KEY].isNullOrBlank()) return false
        val overlay = database.contactEmailUploadOverlayDao().getForContact(accountId, membership.contactId)
            .singleOrNull { it.valueId == membership.emailValueId } ?: return false
        return overlay.canonicalRevision == contact.revision && overlay.sourceEmailId == null &&
            overlay.acceptedValue == null && !overlay.requiresRemoteIdentity &&
            RoomContactUploadOverlays(database).wireProjection(contact)?.values?.none { it.id == value.id } == true
    }

    private suspend fun retainDeferredScope(intent: OutboxMutationEntity, stored: ContactGroupWithMemberships): Boolean {
        val group = stored.group
        val dao = database.remoteGroupAssignmentDependencyDao()
        val previous = dao.baseline(group.accountId, group.id)
        val allowed = if (previous?.deferredOnlyRevision == group.revision)
            deferredMemberships(group.accountId, group.id) else null
        val deferred = stored.memberships.map { GroupMembership(it.contactId, it.emailValueId) }
            .filter { (allowed == null || it in allowed) && isOmitted(group.accountId, it) }
        // A legacy row with no remaining omission proof is ambiguous, not a new full-snapshot intent.
        if (deferred.isEmpty()) return false
        dao.upsertEdges(dao.edges(group.accountId, group.id).map { it.copy(isDeferredIntent = false) })
        dao.upsertEdges(deferred.map { edge ->
            val contact = requireNotNull(database.contactDao().getEntity(group.accountId, edge.contactId))
            RemoteGroupDependencyEdgeEntity(group.accountId, group.id, edge.contactId, edge.emailValueId,
                "", contact.revision, isDeferredIntent = true)
        })
        dao.upsertBaseline(RemoteGroupDependencyBaselineEntity(group.accountId, group.id, group.remoteLabelId,
            previous?.complete == true, deferredOnlyRevision = intent.revision))
        return true
    }
}
