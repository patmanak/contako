package com.patmanak.contako.data.local

import androidx.room.withTransaction
import com.patmanak.contako.data.android.mapping.AndroidGroupProjectionPolicy
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactGroup
import com.patmanak.contako.domain.model.GroupMembership
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.model.PreservationEnvelope
import com.patmanak.contako.domain.policy.CanonicalPrimaryValuePolicy
import com.patmanak.contako.domain.policy.ContactValidation
import com.patmanak.contako.domain.policy.PostalAddressPolicy
import com.patmanak.contako.domain.repository.ContactGroupAssignment
import com.patmanak.contako.domain.repository.ContactEditBaseline
import com.patmanak.contako.domain.repository.ContactRepository
import com.patmanak.contako.domain.repository.SaveResult
import com.patmanak.contako.domain.repository.SaveValidationIssue
import com.patmanak.contako.domain.sync.LocalWriteEvidenceFactory
import com.patmanak.contako.domain.sync.ServerClockCalibration
import java.util.UUID
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

internal sealed interface RoomCanonicalContactMutationResult {
    data class Applied(val contact: CanonicalContact) : RoomCanonicalContactMutationResult
    data object Stale : RoomCanonicalContactMutationResult
    data class Rejected(
        val issues: Set<SaveValidationIssue>,
    ) : RoomCanonicalContactMutationResult
}

internal data class RoomExpectedContactGroupState(
    val groupId: String,
    val revision: Long,
    val isDeleted: Boolean,
) {
    init {
        require(groupId.isNotBlank())
        require(revision >= 0L)
    }

    override fun toString(): String =
        "RoomExpectedContactGroupState(REDACTED, revision=$revision, isDeleted=$isDeleted)"
}

internal sealed interface RoomObservedGroupMembershipMutationResult {
    data class Applied(val changedGroupIds: List<String>) : RoomObservedGroupMembershipMutationResult {
        override fun toString(): String =
            "RoomObservedGroupMembershipMutationResult.Applied(REDACTED, " +
                "changedGroupCount=${changedGroupIds.size})"
    }

    data object NoChange : RoomObservedGroupMembershipMutationResult
    data object StaleContact : RoomObservedGroupMembershipMutationResult
    data object StaleGroupContext : RoomObservedGroupMembershipMutationResult
    data object DeleteIntentConflict : RoomObservedGroupMembershipMutationResult
}

internal class RoomContactRepository(
    private val database: ContakoDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val elapsedRealtimeClock: () -> Long = android.os.SystemClock::elapsedRealtime,
    private val calibrationProvider: (accountId: String) -> ServerClockCalibration? = {
        com.patmanak.contako.data.sync.ProductionServerClock.calibration.current()
    },
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val checkpointHook: LocalMutationCheckpointHook = LocalMutationCheckpointHook.NONE,
) : ContactRepository {
    override fun observeContacts(accountId: String): Flow<List<CanonicalContact>> =
        database.contactDao().observeActive(accountId).map { contacts ->
            contacts.map(ContactWithValues::toDomain).sortedWith { left, right ->
                Collator.getInstance(Locale.getDefault()).compare(left.resolvedDisplayName, right.resolvedDisplayName)
            }
        }.flowOn(ioDispatcher)

    override fun observeGroups(accountId: String): Flow<List<ContactGroup>> =
        database.contactGroupDao().observeActive(accountId).map { groups ->
            groups.map(ContactGroupWithMemberships::toDomain).sortedWith { left, right ->
                Collator.getInstance(Locale.getDefault()).compare(left.name, right.name)
            }
        }.flowOn(ioDispatcher)

    override fun observePendingMutationCount(accountId: String): Flow<Int> =
        database.outboxDao().observePendingCount(accountId)

    override suspend fun getContact(accountId: String, contactId: String): CanonicalContact? =
        withContext(ioDispatcher) {
            database.contactDao().get(accountId, contactId)?.let { stored ->
                stored.toDomain().copy(
                    preservationEnvelope = database.contactPayloadDao()
                        .get(ownerKey(accountId, contactId))
                        ?.toDomain(),
                )
            }
        }

    override suspend fun saveContact(contact: CanonicalContact): SaveResult<CanonicalContact> =
        withContext(ioDispatcher) {
            when (val result = database.withTransaction {
                applyContactDeltaInCurrentTransaction(
                    expectedCanonicalRevision = null,
                    contact = contact,
                )
            }) {
                is RoomCanonicalContactMutationResult.Applied -> {
                    checkpoint(LocalMutationCheckpoint.CONTACT_SAVE_AFTER_COMMIT)
                    SaveResult.Saved(result.contact)
                }
                is RoomCanonicalContactMutationResult.Rejected -> SaveResult.Rejected(result.issues)
                RoomCanonicalContactMutationResult.Stale -> error("Unconditional contact save was stale")
            }
        }

    override suspend fun saveContactWithGroupAssignments(
        contact: CanonicalContact,
        assignments: Set<ContactGroupAssignment>,
        managedGroupIds: Set<String>,
        baseline: ContactEditBaseline?,
    ): SaveResult<CanonicalContact> = withContext(ioDispatcher) {
        var groupsChanged = false
        val result = database.withTransaction {
            val activeGroups = database.contactGroupDao().getAll(contact.accountId)
                .map(ContactGroupWithMemberships::toDomain)
                .filterNot(ContactGroup::isDeleted)
            val activeGroupIds = activeGroups.map(ContactGroup::id).toSet()
            if (baseline != null) {
                val original = baseline.contact
                val current = database.contactDao().get(contact.accountId, contact.id)?.contact
                val currentAssignments = activeGroups.filter { it.id in managedGroupIds }.flatMap { group ->
                    group.memberships.filter { it.contactId == contact.id }.map {
                        ContactGroupAssignment(group.id, it.emailValueId)
                    }
                }.toSet()
                if (original.accountId != contact.accountId || original.id != contact.id ||
                    current == null || current.isDeleted || current.revision != original.revision ||
                    current.remoteContactId != original.remoteContactId || current.remoteVersion != original.remoteVersion ||
                    currentAssignments != baseline.assignments
                ) return@withTransaction SaveResult.Rejected(setOf(SaveValidationIssue.STALE_CONTACT_EDIT))
            }
            val editableEmailIds = contact.values
                .filter { it.kind == ContactValueKind.EMAIL }
                .map(ContactValue::id)
                .toSet()
            val assignmentIssues = buildSet {
                if (managedGroupIds.any { it !in activeGroupIds } || assignments.any { it.groupId !in managedGroupIds }) {
                    add(SaveValidationIssue.UNKNOWN_GROUP_ASSIGNMENT)
                }
                if (assignments.any { it.emailValueId !in editableEmailIds }) {
                    add(SaveValidationIssue.MEMBERSHIP_NOT_EMAIL)
                }
            }
            if (assignmentIssues.isNotEmpty()) {
                return@withTransaction SaveResult.Rejected(assignmentIssues)
            }

            val committed = when (val contactResult = applyContactDeltaInCurrentTransaction(
                expectedCanonicalRevision = baseline?.contact?.revision,
                contact = contact,
            )) {
                is RoomCanonicalContactMutationResult.Applied -> contactResult.contact
                is RoomCanonicalContactMutationResult.Rejected -> {
                    return@withTransaction SaveResult.Rejected(contactResult.issues)
                }
                RoomCanonicalContactMutationResult.Stale ->
                    return@withTransaction SaveResult.Rejected(setOf(SaveValidationIssue.STALE_CONTACT_EDIT))
            }
            val replacedContactIds = buildSet {
                contact.id.takeIf(String::isNotBlank)?.let(::add)
                add(committed.id)
            }
            activeGroups.filter { it.id in managedGroupIds }.forEach { group ->
                val retained = group.memberships.filterNot { it.contactId in replacedContactIds }
                val selected = assignments.asSequence()
                    .filter { it.groupId == group.id }
                    .map { GroupMembership(committed.id, it.emailValueId) }
                    .toList()
                val updatedMemberships = (retained + selected).sortedWith(
                    compareBy(GroupMembership::contactId, GroupMembership::emailValueId),
                )
                if (updatedMemberships != group.memberships.sortedWith(
                        compareBy(GroupMembership::contactId, GroupMembership::emailValueId),
                    )
                ) {
                    check(saveGroupInCurrentTransaction(group.copy(memberships = updatedMemberships)) is SaveResult.Saved) {
                        "Validated contact group assignment was rejected"
                    }
                    groupsChanged = true
                }
            }
            SaveResult.Saved(committed)
        }
        if (result is SaveResult.Saved) {
            checkpoint(LocalMutationCheckpoint.CONTACT_SAVE_AFTER_COMMIT)
            if (groupsChanged) checkpoint(LocalMutationCheckpoint.GROUP_SAVE_AFTER_COMMIT)
        }
        result
    }

    override suspend fun deleteContact(accountId: String, contactId: String) {
        withContext(ioDispatcher) {
            val result = database.withTransaction {
                applyDeletionInCurrentTransaction(
                    expectedCanonicalRevision = null,
                    accountId = accountId,
                    contactId = contactId,
                )
            }
            if (result is RoomCanonicalContactMutationResult.Applied) {
                checkpoint(LocalMutationCheckpoint.CONTACT_DELETE_AFTER_COMMIT)
            }
        }
    }

    /**
     * Applies a complete canonical contact replacement to the caller's Room transaction.
     *
     * A non-null [expectedCanonicalRevision] enables compare-and-set semantics for Android
     * observations. This method deliberately does not dispatch an AFTER_COMMIT checkpoint: only
     * the owner of the outer transaction can know when that transaction has really committed.
     */
    internal suspend fun applyContactDeltaInCurrentTransaction(
        expectedCanonicalRevision: Long?,
        contact: CanonicalContact,
    ): RoomCanonicalContactMutationResult {
        check(database.inTransaction()) { "A caller-owned Room transaction is required" }
        val resolvedId = contact.id.ifBlank(idFactory)
        val issues = ContactValidation.canonicalValueIdentityIssues(contact).toMutableSet()
        if (resolvedId.isBlank()) issues += SaveValidationIssue.LOCAL_ID_GENERATION_FAILED
        if (issues.isNotEmpty()) return RoomCanonicalContactMutationResult.Rejected(issues)

        val requested = contact.copy(id = resolvedId)
        val dao = database.contactDao()
        val existing = dao.get(requested.accountId, requested.id)
        if (expectedCanonicalRevision != null && existing?.contact?.revision != expectedCanonicalRevision) {
            return RoomCanonicalContactMutationResult.Stale
        }

        checkpoint(LocalMutationCheckpoint.CONTACT_SAVE_BEFORE_MUTATION)
        if (
            expectedCanonicalRevision != null &&
            dao.compareAndSetRevision(
                requested.accountId,
                requested.id,
                expectedCanonicalRevision,
            ) != 1
        ) {
            return RoomCanonicalContactMutationResult.Stale
        }

        val requiresCreateValidation =
            requested.remoteContactId == null && existing?.contact?.remoteContactId == null
        val now = clock()
        val nextRevision = (existing?.contact?.revision ?: 0L) + 1L
        val committed = requested.copy(
            revision = nextRevision,
            updatedAtEpochMillis = now,
            actionRequiredReasons = ContactValidation.actionRequiredReasons(requested, requiresCreateValidation),
            pendingMutationRevision = nextRevision,
            isDeleted = false,
        )
        val previousValueIds = dao.getValueIds(committed.accountId, committed.id)
        val retainedValueIds = committed.values.map(ContactValue::id)
        // A contact PUT can replace the clear per-email categories even when the
        // editor only changed another address's groups. Durably reconcile every
        // existing assignment after this write, including unchanged secondary emails.
        val affectedGroupIds = if (previousValueIds.isEmpty()) {
            emptyList()
        } else {
            database.contactGroupDao().findGroupIdsForEmailValues(
                committed.accountId,
                committed.id,
                previousValueIds,
            )
        }
        dao.upsert(committed.toEntity())
        checkpoint(LocalMutationCheckpoint.CONTACT_SAVE_AFTER_AGGREGATE)
        val previousValuesById = existing?.values.orEmpty().associateBy(ContactValueEntity::id)
        val changedValues = committed.values.map { it.toEntity(committed) }.filter { value ->
            previousValuesById[value.id] != value
        }
        if (changedValues.isNotEmpty()) dao.upsertValues(changedValues)
        checkpoint(LocalMutationCheckpoint.CONTACT_SAVE_AFTER_VALUE_UPSERT)
        if (retainedValueIds.isEmpty()) {
            dao.deleteValues(committed.accountId, committed.id)
        } else {
            dao.deleteValuesNotIn(committed.accountId, committed.id, retainedValueIds)
        }
        checkpoint(LocalMutationCheckpoint.CONTACT_SAVE_AFTER_VALUES)
        val envelope = committed.preservationEnvelope
            ?: database.contactPayloadDao().get(ownerKey(committed.accountId, committed.id))?.toDomain()
        if (envelope != null) {
            database.contactPayloadDao().upsert(envelope.toEntity(committed.accountId, committed.id))
        }
        checkpoint(LocalMutationCheckpoint.CONTACT_SAVE_AFTER_PAYLOAD)
        checkpoint(LocalMutationCheckpoint.CONTACT_SAVE_BEFORE_OUTBOX)
        enqueue(
            accountId = committed.accountId,
            type = AggregateType.CONTACT,
            aggregateId = committed.id,
            operation = MutationOperation.UPSERT,
            revision = committed.revision,
            now = now,
            blockedReason = committed.actionRequiredReasons.sorted().joinToString(",").ifEmpty { null },
            remoteIdentity = committed.remoteContactId,
            remoteVersion = committed.remoteVersion,
        )
        enqueueAssignmentChanges(
            accountId = committed.accountId,
            groupIds = affectedGroupIds,
            now = now,
            checkpoints = AssignmentMutationCheckpoints(
                beforeGroup = LocalMutationCheckpoint.CONTACT_SAVE_ASSIGNMENT_BEFORE_GROUP,
                afterGroup = LocalMutationCheckpoint.CONTACT_SAVE_ASSIGNMENT_AFTER_GROUP,
                afterOutbox = LocalMutationCheckpoint.CONTACT_SAVE_ASSIGNMENT_AFTER_OUTBOX,
            ),
        )
        checkpoint(LocalMutationCheckpoint.CONTACT_SAVE_AFTER_OUTBOX)
        checkpoint(LocalMutationCheckpoint.CONTACT_SAVE_BEFORE_COMMIT)
        return RoomCanonicalContactMutationResult.Applied(committed)
    }

    /** See [applyContactDeltaInCurrentTransaction] for transaction ownership semantics. */
    internal suspend fun applyDeletionInCurrentTransaction(
        expectedCanonicalRevision: Long?,
        accountId: String,
        contactId: String,
    ): RoomCanonicalContactMutationResult {
        check(database.inTransaction()) { "A caller-owned Room transaction is required" }
        val dao = database.contactDao()
        val existing = dao.get(accountId, contactId) ?: return RoomCanonicalContactMutationResult.Stale
        if (expectedCanonicalRevision != null && existing.contact.revision != expectedCanonicalRevision) {
            return RoomCanonicalContactMutationResult.Stale
        }

        checkpoint(LocalMutationCheckpoint.CONTACT_DELETE_BEFORE_MUTATION)
        if (
            expectedCanonicalRevision != null &&
            dao.compareAndSetRevision(accountId, contactId, expectedCanonicalRevision) != 1
        ) {
            return RoomCanonicalContactMutationResult.Stale
        }

        val now = clock()
        val previousValueIds = dao.getValueIds(accountId, contactId)
        val affectedGroupIds = if (previousValueIds.isEmpty()) emptyList() else {
            database.contactGroupDao().findGroupIdsForEmailValues(accountId, contactId, previousValueIds)
        }
        val tombstone = existing.toDomain().copy(
            revision = existing.contact.revision + 1L,
            updatedAtEpochMillis = now,
            pendingMutationRevision = existing.contact.revision + 1L,
            isDeleted = true,
            conflictState = null,
        )
        dao.upsert(tombstone.toEntity())
        // Explicit deletion supersedes the previous edit/choice, not its remote
        // baseline. Keep the durable delete pending until fresh absence or ack.
        database.contactConflictDao().delete(accountId, contactId)
        checkpoint(LocalMutationCheckpoint.CONTACT_DELETE_AFTER_AGGREGATE)
        dao.deleteValues(accountId, contactId)
        checkpoint(LocalMutationCheckpoint.CONTACT_DELETE_AFTER_VALUES)
        checkpoint(LocalMutationCheckpoint.CONTACT_DELETE_BEFORE_OUTBOX)
        enqueue(
            accountId = accountId,
            type = AggregateType.CONTACT,
            aggregateId = contactId,
            operation = MutationOperation.DELETE,
            revision = tombstone.revision,
            now = now,
            remoteIdentity = tombstone.remoteContactId,
            remoteVersion = tombstone.remoteVersion,
        )
        enqueueAssignmentChanges(
            accountId = accountId,
            groupIds = affectedGroupIds,
            now = now,
            checkpoints = AssignmentMutationCheckpoints(
                beforeGroup = LocalMutationCheckpoint.CONTACT_DELETE_ASSIGNMENT_BEFORE_GROUP,
                afterGroup = LocalMutationCheckpoint.CONTACT_DELETE_ASSIGNMENT_AFTER_GROUP,
                afterOutbox = LocalMutationCheckpoint.CONTACT_DELETE_ASSIGNMENT_AFTER_OUTBOX,
            ),
        )
        checkpoint(LocalMutationCheckpoint.CONTACT_DELETE_AFTER_OUTBOX)
        checkpoint(LocalMutationCheckpoint.CONTACT_DELETE_BEFORE_COMMIT)
        return RoomCanonicalContactMutationResult.Applied(tombstone)
    }

    /**
     * Applies one complete Android group-membership observation to the caller's transaction.
     *
     * [expectedGroupStates] is the exact revision/deletion vector for every canonical group in
     * the account, including tombstones. Only the preferred-email pair may change. The method
     * performs no provider work and deliberately leaves transaction completion to its caller.
     */
    internal suspend fun applyObservedGroupMembershipsInCurrentTransaction(
        accountId: String,
        contactId: String,
        expectedCanonicalRevision: Long,
        expectedPreferredEmailValueId: String?,
        expectedGroupStates: List<RoomExpectedContactGroupState>,
        observedCanonicalGroupIds: Set<String>,
    ): RoomObservedGroupMembershipMutationResult {
        check(database.inTransaction()) { "A caller-owned Room transaction is required" }
        require(accountId.isNotBlank())
        require(contactId.isNotBlank())
        require(expectedCanonicalRevision >= 0L)
        require(expectedGroupStates.map(RoomExpectedContactGroupState::groupId).distinct().size ==
            expectedGroupStates.size)

        val storedContact = database.contactDao().get(accountId, contactId)
            ?: return RoomObservedGroupMembershipMutationResult.StaleContact
        if (storedContact.contact.revision != expectedCanonicalRevision || storedContact.contact.isDeleted) {
            return RoomObservedGroupMembershipMutationResult.StaleContact
        }
        val contact = storedContact.toDomain()
        if (CanonicalPrimaryValuePolicy.preferredEmail(contact)?.id != expectedPreferredEmailValueId) {
            return RoomObservedGroupMembershipMutationResult.StaleContact
        }

        val groupDao = database.contactGroupDao()
        val storedGroups = groupDao.getAll(accountId)
        val currentGroupStates = storedGroups.map { stored ->
            RoomExpectedContactGroupState(
                groupId = stored.group.id,
                revision = stored.group.revision,
                isDeleted = stored.group.isDeleted,
            )
        }.sortedBy(RoomExpectedContactGroupState::groupId)
        if (currentGroupStates != expectedGroupStates.sortedBy(RoomExpectedContactGroupState::groupId)) {
            return RoomObservedGroupMembershipMutationResult.StaleGroupContext
        }

        val groups = storedGroups.map(ContactGroupWithMemberships::toDomain)
        val delta = AndroidGroupProjectionPolicy().applyObservedMemberships(
            contact = contact,
            groups = groups,
            observedCanonicalGroupIds = observedCanonicalGroupIds,
        )
        val changedGroupIds = delta.changedGroupIds.sorted()
        if (changedGroupIds.isEmpty()) return RoomObservedGroupMembershipMutationResult.NoChange

        val currentGroupsById = groups.associateBy(ContactGroup::id)
        val updatedGroupsById = delta.updatedGroups.associateBy(ContactGroup::id)
        val operationByGroupId = buildMap {
            changedGroupIds.forEach { groupId ->
                val group = requireNotNull(currentGroupsById[groupId])
                val currentIntent = database.outboxDao().get(
                    accountId,
                    AggregateType.GROUP.name,
                    groupId,
                )
                if (currentIntent?.operation == MutationOperation.DELETE.name) {
                    return RoomObservedGroupMembershipMutationResult.DeleteIntentConflict
                }
                put(
                    groupId,
                    if (group.remoteLabelId == null || currentIntent?.operation == MutationOperation.UPSERT.name) {
                        MutationOperation.UPSERT
                    } else {
                        MutationOperation.ASSIGNMENTS
                    },
                )
            }
        }

        val now = clock()
        changedGroupIds.forEach { groupId ->
            val current = requireNotNull(currentGroupsById[groupId])
            check(groupDao.compareAndSetRevision(accountId, groupId, current.revision) == 1) {
                "Canonical group revision changed inside the caller-owned transaction"
            }
            val nextRevision = Math.incrementExact(current.revision)
            val updated = requireNotNull(updatedGroupsById[groupId]).copy(
                revision = nextRevision,
                updatedAtEpochMillis = now,
                pendingMutationRevision = nextRevision,
            )
            groupDao.upsert(updated.toEntity())

            val preferredEmailValueId = requireNotNull(delta.preferredEmailValueId)
            val previouslyAssigned = current.memberships.any { membership ->
                membership.contactId == contactId && membership.emailValueId == preferredEmailValueId
            }
            val nowAssigned = updated.memberships.any { membership ->
                membership.contactId == contactId && membership.emailValueId == preferredEmailValueId
            }
            check(previouslyAssigned != nowAssigned)
            if (nowAssigned) {
                groupDao.insertMemberships(
                    listOf(GroupMembership(contactId, preferredEmailValueId).toEntity(updated)),
                )
            } else {
                check(
                    groupDao.deleteMembership(
                        accountId = accountId,
                        groupId = groupId,
                        contactId = contactId,
                        emailValueId = preferredEmailValueId,
                    ) == 1,
                ) { "Preferred-email group membership changed inside the caller-owned transaction" }
            }

            enqueue(
                accountId = accountId,
                type = AggregateType.GROUP,
                aggregateId = groupId,
                operation = requireNotNull(operationByGroupId[groupId]),
                revision = nextRevision,
                now = now,
                remoteIdentity = updated.remoteLabelId,
                remoteVersion = updated.remoteVersion,
            )
        }
        return RoomObservedGroupMembershipMutationResult.Applied(changedGroupIds)
    }

    internal fun isBackedBy(database: ContakoDatabase): Boolean = this.database === database

    internal suspend fun applyObservedGroupRowInCurrentTransaction(
        accountId: String,
        canonicalGroupId: String,
        expectedCanonicalRevision: Long?,
        observedName: String?,
        observedVisibility: Boolean?,
        deleted: Boolean,
    ): RoomObservedGroupRowMutationResult {
        check(database.inTransaction()) { "A caller-owned Room transaction is required" }
        val dao = database.contactGroupDao()
        val existing = dao.get(accountId, canonicalGroupId)?.toDomain()
        if (existing?.revision != expectedCanonicalRevision) return RoomObservedGroupRowMutationResult.Stale
        if (!deleted && (observedName == null || observedVisibility == null)) {
            return RoomObservedGroupRowMutationResult.Stale
        }
        if (existing == null) {
            if (deleted || expectedCanonicalRevision != null) return RoomObservedGroupRowMutationResult.Stale
            val created = com.patmanak.contako.domain.model.ContactGroup(
                accountId = accountId,
                id = canonicalGroupId,
                name = requireNotNull(observedName),
                color = RoomRemoteGroupReconciliationStore.DEFAULT_COLOR,
                isVisible = requireNotNull(observedVisibility),
                revision = 1,
                updatedAtEpochMillis = clock(),
                pendingMutationRevision = 1,
            )
            dao.upsert(created.toEntity())
            enqueue(accountId, AggregateType.GROUP, canonicalGroupId, MutationOperation.UPSERT, 1, clock())
            return RoomObservedGroupRowMutationResult.Applied(1)
        }
        val noChange = if (deleted) existing.isDeleted else
            !existing.isDeleted && existing.name == observedName && existing.isVisible == observedVisibility
        if (noChange) return RoomObservedGroupRowMutationResult.NoChange(existing.revision)
        val currentIntent = database.outboxDao().get(accountId, AggregateType.GROUP.name, canonicalGroupId)
        if (!deleted && currentIntent?.operation == MutationOperation.DELETE.name) {
            return RoomObservedGroupRowMutationResult.DeleteIntentConflict
        }
        if (dao.compareAndSetRevision(accountId, canonicalGroupId, existing.revision) != 1) {
            return RoomObservedGroupRowMutationResult.Stale
        }
        val revision = Math.incrementExact(existing.revision)
        val updated = existing.copy(
            name = observedName ?: existing.name,
            isVisible = observedVisibility ?: existing.isVisible,
            revision = revision,
            updatedAtEpochMillis = clock(),
            pendingMutationRevision = revision,
            isDeleted = deleted,
        )
        dao.upsert(updated.toEntity())
        if (deleted) dao.deleteMemberships(accountId, canonicalGroupId)
        enqueue(
            accountId,
            AggregateType.GROUP,
            canonicalGroupId,
            if (deleted) MutationOperation.DELETE else MutationOperation.UPSERT,
            revision,
            clock(),
            remoteIdentity = updated.remoteLabelId,
            remoteVersion = updated.remoteVersion,
        )
        return RoomObservedGroupRowMutationResult.Applied(revision)
    }

    override suspend fun saveGroup(group: ContactGroup): SaveResult<ContactGroup> = withContext(ioDispatcher) {
        val result = database.withTransaction { saveGroupInCurrentTransaction(group) }
        if (result is SaveResult.Saved) checkpoint(LocalMutationCheckpoint.GROUP_SAVE_AFTER_COMMIT)
        result
    }

    private suspend fun saveGroupInCurrentTransaction(group: ContactGroup): SaveResult<ContactGroup> {
        check(database.inTransaction()) { "A caller-owned Room transaction is required" }
        val resolvedId = group.id.ifBlank(idFactory)
        val issues = buildSet {
            if (group.accountId.isBlank()) add(SaveValidationIssue.BLANK_ACCOUNT_ID)
            if (group.name.isBlank()) add(SaveValidationIssue.BLANK_GROUP_NAME)
            if (group.remoteLabelId != null && group.id.isBlank()) {
                add(SaveValidationIssue.REMOTE_GROUP_WITHOUT_LOCAL_ID)
            }
            if (resolvedId.isBlank()) add(SaveValidationIssue.LOCAL_ID_GENERATION_FAILED)
            if (group.memberships.distinct().size != group.memberships.size) {
                add(SaveValidationIssue.DUPLICATE_GROUP_MEMBERSHIP)
            }
        }.toMutableSet()
        group.memberships.forEach { membership ->
            val value = database.contactDao().getValue(
                group.accountId,
                membership.contactId,
                membership.emailValueId,
            )
            if (value?.kind != ContactValueKind.EMAIL.name) {
                issues += SaveValidationIssue.MEMBERSHIP_NOT_EMAIL
            }
        }
        if (issues.isNotEmpty()) return SaveResult.Rejected(issues)

        checkpoint(LocalMutationCheckpoint.GROUP_SAVE_BEFORE_MUTATION)
        val requested = group.copy(id = resolvedId)
        val existing = database.contactGroupDao().get(requested.accountId, requested.id)
        val now = clock()
        val committed = requested.copy(
            revision = (existing?.group?.revision ?: 0L) + 1L,
            remoteLabelId = existing?.group?.remoteLabelId ?: requested.remoteLabelId,
            remoteVersion = existing?.group?.remoteVersion ?: requested.remoteVersion,
            updatedAtEpochMillis = now,
            pendingMutationRevision = (existing?.group?.revision ?: 0L) + 1L,
            isDeleted = false,
        )
        val groupDao = database.contactGroupDao()
        groupDao.upsert(committed.toEntity())
        checkpoint(LocalMutationCheckpoint.GROUP_SAVE_AFTER_AGGREGATE)
        groupDao.deleteMemberships(committed.accountId, committed.id)
        checkpoint(LocalMutationCheckpoint.GROUP_SAVE_AFTER_MEMBERSHIP_CLEAR)
        groupDao.insertMemberships(committed.memberships.map { it.toEntity(committed) })
        checkpoint(LocalMutationCheckpoint.GROUP_SAVE_AFTER_MEMBERSHIPS)
        checkpoint(LocalMutationCheckpoint.GROUP_SAVE_BEFORE_OUTBOX)
        val existingIntent = database.outboxDao().get(
            committed.accountId,
            AggregateType.GROUP.name,
            committed.id,
        )
        val operation = when {
            committed.remoteLabelId == null -> MutationOperation.UPSERT
            existingIntent?.operation == MutationOperation.UPSERT.name -> MutationOperation.UPSERT
            existing != null &&
                existing.group.name == committed.name &&
                existing.group.color == committed.color -> MutationOperation.ASSIGNMENTS
            else -> MutationOperation.UPSERT
        }
        enqueue(
            accountId = committed.accountId,
            type = AggregateType.GROUP,
            aggregateId = committed.id,
            operation = operation,
            revision = committed.revision,
            now = now,
            remoteIdentity = committed.remoteLabelId,
            remoteVersion = committed.remoteVersion,
        )
        checkpoint(LocalMutationCheckpoint.GROUP_SAVE_AFTER_OUTBOX)
        checkpoint(LocalMutationCheckpoint.GROUP_SAVE_BEFORE_COMMIT)
        return SaveResult.Saved(committed)
    }

    override suspend fun deleteGroup(accountId: String, groupId: String) {
        withContext(ioDispatcher) {
            val deleted = database.withTransaction {
                val existing = database.contactGroupDao().get(accountId, groupId)
                    ?: return@withTransaction false
                checkpoint(LocalMutationCheckpoint.GROUP_DELETE_BEFORE_MUTATION)
                val now = clock()
                database.contactGroupDao().upsert(
                    existing.group.copy(
                        revision = existing.group.revision + 1L,
                        updatedAtEpochMillis = now,
                        pendingMutationRevision = existing.group.revision + 1L,
                        isDeleted = true,
                    ),
                )
                checkpoint(LocalMutationCheckpoint.GROUP_DELETE_AFTER_AGGREGATE)
                database.contactGroupDao().deleteMemberships(accountId, groupId)
                checkpoint(LocalMutationCheckpoint.GROUP_DELETE_AFTER_MEMBERSHIPS)
                checkpoint(LocalMutationCheckpoint.GROUP_DELETE_BEFORE_OUTBOX)
                enqueue(
                    accountId = accountId,
                    type = AggregateType.GROUP,
                    aggregateId = groupId,
                    operation = MutationOperation.DELETE,
                    revision = existing.group.revision + 1L,
                    now = now,
                    remoteIdentity = existing.group.remoteLabelId,
                    remoteVersion = existing.group.remoteVersion,
                )
                checkpoint(LocalMutationCheckpoint.GROUP_DELETE_AFTER_OUTBOX)
                checkpoint(LocalMutationCheckpoint.GROUP_DELETE_BEFORE_COMMIT)
                true
            }
            if (deleted) checkpoint(LocalMutationCheckpoint.GROUP_DELETE_AFTER_COMMIT)
        }
    }

    private suspend fun enqueue(
        accountId: String,
        type: AggregateType,
        aggregateId: String,
        operation: MutationOperation,
        revision: Long,
        now: Long,
        blockedReason: String? = null,
        remoteIdentity: String? = null,
        remoteVersion: String? = null,
    ) {
        val dao = database.outboxDao()
        val existing = dao.get(accountId, type.name, aggregateId)
        val ambiguousCreation = type == AggregateType.GROUP && remoteIdentity == null && existing != null &&
            (existing.requiresReconciliation ||
                (existing.remoteIdentity == null && existing.operation == MutationOperation.UPSERT.name &&
                    existing.state == DurableMutationState.IN_FLIGHT.name))
        val evidence = LocalWriteEvidenceFactory.capture(
            revision = revision,
            deviceWallClockEpochMillis = now,
            deviceElapsedRealtimeMillis = elapsedRealtimeClock(),
            calibration = calibrationProvider(accountId),
        )
        dao.upsert(
            OutboxMutationEntity(
                accountId = accountId,
                aggregateType = type.name,
                aggregateId = aggregateId,
                operation = operation.name,
                revision = revision,
                createdAtEpochMillis = existing?.createdAtEpochMillis ?: now,
                updatedAtEpochMillis = now,
                blockedReason = blockedReason,
                remoteIdentity = remoteIdentity,
                remoteVersion = remoteVersion,
                requiresReconciliation = ambiguousCreation,
                lastAttemptAtEpochMillis = existing?.lastAttemptAtEpochMillis.takeIf { ambiguousCreation },
                idempotencyKey = "$accountId:${type.name}:$aggregateId:$revision:${operation.name}",
                deviceElapsedRealtimeMillis = evidence.deviceElapsedRealtimeMillis,
                serverOffsetMillis = evidence.serverOffsetMillis,
                calibrationAgeMillis = evidence.calibrationAgeMillis,
                roundTripMillis = evidence.roundTripMillis,
                serverPrecisionMillis = evidence.serverPrecisionMillis,
                uncertaintyMillis = evidence.uncertaintyMillis,
                intervalEarliestEpochMillis = evidence.interval?.earliestEpochMillis,
                intervalLatestEpochMillis = evidence.interval?.latestEpochMillis,
                clockJumpDetected = evidence.clockJumpDetected,
            ),
        )
    }

    private suspend fun enqueueAssignmentChanges(
        accountId: String,
        groupIds: List<String>,
        now: Long,
        checkpoints: AssignmentMutationCheckpoints,
    ) {
        groupIds.distinct().forEach { groupId ->
            val stored = database.contactGroupDao().get(accountId, groupId) ?: return@forEach
            checkpoint(checkpoints.beforeGroup)
            val revised = stored.group.copy(
                revision = stored.group.revision + 1L,
                updatedAtEpochMillis = now,
                pendingMutationRevision = stored.group.revision + 1L,
            )
            database.contactGroupDao().upsert(revised)
            checkpoint(checkpoints.afterGroup)
            val currentIntent = database.outboxDao().get(accountId, AggregateType.GROUP.name, groupId)
            val operation = if (revised.remoteLabelId == null || currentIntent?.operation == MutationOperation.UPSERT.name) {
                MutationOperation.UPSERT
            } else {
                MutationOperation.ASSIGNMENTS
            }
            enqueue(
                accountId = accountId,
                type = AggregateType.GROUP,
                aggregateId = groupId,
                operation = operation,
                revision = revised.revision,
                now = now,
                remoteIdentity = revised.remoteLabelId,
                remoteVersion = revised.remoteVersion,
            )
            checkpoint(checkpoints.afterOutbox)
        }
    }

    private fun checkpoint(checkpoint: LocalMutationCheckpoint) {
        checkpointHook.onCheckpoint(checkpoint)
    }
}

internal sealed interface RoomObservedGroupRowMutationResult {
    data class Applied(val revision: Long) : RoomObservedGroupRowMutationResult
    data class NoChange(val revision: Long) : RoomObservedGroupRowMutationResult
    data object Stale : RoomObservedGroupRowMutationResult
    data object DeleteIntentConflict : RoomObservedGroupRowMutationResult
}

private data class AssignmentMutationCheckpoints(
    val beforeGroup: LocalMutationCheckpoint,
    val afterGroup: LocalMutationCheckpoint,
    val afterOutbox: LocalMutationCheckpoint,
)

internal fun ContactWithValues.toDomain(): CanonicalContact = CanonicalContact(
    accountId = contact.accountId,
    id = contact.id,
    firstName = contact.firstName,
    lastName = contact.lastName,
    displayName = contact.displayName,
    values = values.sortedBy(ContactValueEntity::position).map(ContactValueEntity::toDomain),
    revision = contact.revision,
    updatedAtEpochMillis = contact.updatedAtEpochMillis,
    remoteContactId = contact.remoteContactId,
    remoteVCardUid = contact.remoteVCardUid,
    remoteVersion = contact.remoteVersion,
    actionRequiredReasons = contact.actionRequiredReasonsEncoding
        .split(',').filter(String::isNotBlank).toSet(),
    pendingMutationRevision = contact.pendingMutationRevision,
    conflictState = contact.conflictState,
    isDeleted = contact.isDeleted,
)

internal fun CanonicalContact.toEntity() = ContactEntity(
    accountId = accountId,
    id = id,
    ownerKey = ownerKey(accountId, id),
    firstName = firstName,
    lastName = lastName,
    displayName = displayName,
    sortName = resolvedDisplayName,
    revision = revision,
    updatedAtEpochMillis = updatedAtEpochMillis,
    remoteContactId = remoteContactId,
    remoteVCardUid = remoteVCardUid,
    remoteVersion = remoteVersion,
    actionRequiredReasonsEncoding = actionRequiredReasons.sorted().joinToString(","),
    pendingMutationRevision = pendingMutationRevision,
    conflictState = conflictState,
    isDeleted = isDeleted,
)

internal fun ContactValue.toEntity(contact: CanonicalContact): ContactValueEntity =
    PostalAddressPolicy.normalize(this).let { normalized ->
        ContactValueEntity(
            accountId = contact.accountId,
            contactId = contact.id,
            ownerKey = ownerKey(contact.accountId, contact.id),
            id = normalized.id,
            kind = normalized.kind.name,
            value = normalized.value,
            label = normalized.label,
            position = normalized.order,
            isPrimary = normalized.isPrimary,
            componentsEncoding = StringMapCodec.encode(normalized.components),
            metadataEncoding = StringMapCodec.encode(normalized.metadata),
            binaryReference = normalized.binaryReference,
            preservationKey = normalized.preservationKey,
        )
    }

internal fun ContactValueEntity.toDomain() = PostalAddressPolicy.normalize(ContactValue(
    id = id,
    kind = ContactValueKind.valueOf(kind),
    value = value,
    label = label,
    order = position,
    isPrimary = isPrimary,
    components = StringMapCodec.decode(componentsEncoding),
    metadata = StringMapCodec.decode(metadataEncoding),
    binaryReference = binaryReference,
    preservationKey = preservationKey,
))

internal fun ContactGroupWithMemberships.toDomain() = ContactGroup(
    accountId = group.accountId,
    id = group.id,
    name = group.name,
    color = group.color,
    order = group.displayOrder,
    isVisible = group.isVisible,
    revision = group.revision,
    updatedAtEpochMillis = group.updatedAtEpochMillis,
    remoteLabelId = group.remoteLabelId,
    remoteVersion = group.remoteVersion,
    memberships = memberships.map { GroupMembership(it.contactId, it.emailValueId) },
    pendingMutationRevision = group.pendingMutationRevision,
    conflictState = group.conflictState,
    isDeleted = group.isDeleted,
)

internal fun ContactGroup.toEntity() = ContactGroupEntity(
    accountId = accountId,
    id = id,
    ownerKey = ownerKey(accountId, id),
    name = name,
    color = color,
    displayOrder = order,
    isVisible = isVisible,
    revision = revision,
    updatedAtEpochMillis = updatedAtEpochMillis,
    remoteLabelId = remoteLabelId,
    remoteVersion = remoteVersion,
    pendingMutationRevision = pendingMutationRevision,
    conflictState = conflictState,
    isDeleted = isDeleted,
)

internal fun GroupMembership.toEntity(group: ContactGroup) = GroupMembershipEntity(
    accountId = group.accountId,
    groupId = group.id,
    groupOwnerKey = ownerKey(group.accountId, group.id),
    contactId = contactId,
    emailValueId = emailValueId,
)

internal fun ownerKey(accountId: String, aggregateId: String): String = "$accountId\u0000$aggregateId"

internal fun PreservationEnvelope.toEntity(accountId: String, contactId: String) = ContactPayloadEntity(
    ownerKey = ownerKey(accountId, contactId),
    rawPropertiesEncoding = StringMapCodec.encode(rawProperties),
    remoteBaseline = remoteBaseline,
)

internal fun ContactPayloadEntity.toDomain() = PreservationEnvelope(
    rawProperties = StringMapCodec.decode(rawPropertiesEncoding),
    remoteBaseline = remoteBaseline,
)
