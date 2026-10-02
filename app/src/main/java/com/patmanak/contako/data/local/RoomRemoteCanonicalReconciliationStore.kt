package com.patmanak.contako.data.local

import androidx.room.withTransaction
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.RemoteContactId
import com.patmanak.contako.data.gateway.VerifiedContactCard
import com.patmanak.contako.data.proton.ContactInventoryPlan
import com.patmanak.contako.data.proton.PROTON_GROUP_IDS_KEY
import com.patmanak.contako.data.sync.CanonicalReconciliationReceipt
import com.patmanak.contako.data.sync.RemoteCanonicalReconciliationStore
import com.patmanak.contako.data.android.AndroidAdoptionState
import com.patmanak.contako.data.android.AndroidProjectionWriteState
import com.patmanak.contako.data.android.AndroidTombstoneState
import com.patmanak.contako.data.android.RoomAndroidProjectionLedger
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.model.GroupMembership

/** Room transaction implementing remote adoption and the single D-024/D-006 conflict policy. */
internal class RoomRemoteCanonicalReconciliationStore(
    private val database: ContakoDatabase,
) : RemoteCanonicalReconciliationStore {
    override suspend fun commit(
        account: AccountScope,
        plan: ContactInventoryPlan,
        hydratedCards: List<VerifiedContactCard>,
        labelsToReconcile: Set<RemoteContactId>,
        deletionsToReconcile: Set<RemoteContactId>,
    ): CanonicalReconciliationReceipt {
        return database.withTransaction {
        val hydratedIds = hydratedCards.map(VerifiedContactCard::id).toSet()
        require(hydratedIds.size == hydratedCards.size && plan.hydrate.containsAll(hydratedIds))
        require(plan.labelOnly.containsAll(labelsToReconcile))
        require(plan.deleted.containsAll(deletionsToReconcile))
        val baselines = plan.nextCheckpoint.entries.associateBy { it.id }
        hydratedCards.forEach { verified ->
            val baseline = requireNotNull(baselines[verified.id])
            reconcileHydrated(account, verified, baseline.modifiedAtEpochSeconds)
        }
        deletionsToReconcile.forEach { remoteId -> reconcileRemoteDeletion(account, remoteId) }
        // Membership-only changes are durably classified here. The Android/group projection stage
        // performs the representation repair after the group inventory is available.
        CanonicalReconciliationReceipt(hydratedIds, labelsToReconcile, deletionsToReconcile)
        }
    }

    /**
     * @param remoteModifiedAtEpochSeconds server modification time, or `null` for a
     * public-directory inventory under `D-096`. Concurrent changes require an explicit user choice;
     * observation time MUST NOT be used to infer which version wins.
     */
    private suspend fun reconcileHydrated(
        account: AccountScope,
        verified: VerifiedContactCard,
        remoteModifiedAtEpochSeconds: Long?,
    ) {
        val dao = database.contactDao()
        val remote = verified.contact
        val stored = dao.getByRemoteContactId(account.value, verified.id.value)
            ?: remote.remoteVCardUid?.let { dao.getByRemoteVCardUid(account.value, it) }
            ?: remote.remoteVCardUid?.let { dao.get(account.value, it) }
            ?: dao.get(account.value, remote.id)
        if (stored == null) {
            replaceContact(
                remote.copy(
                    accountId = account.value,
                    revision = 1,
                    // No server time available: record observation time so the row is still ordered
                    // locally. It is never presented as server-attested evidence.
                    updatedAtEpochMillis = remoteModifiedAtEpochSeconds?.times(1_000L)
                        ?: System.currentTimeMillis(),
                    remoteContactId = verified.id.value,
                    remoteVersion = verified.version?.value,
                    pendingMutationRevision = null,
                    conflictState = null,
                    isDeleted = false,
                ),
            )
            return
        }

        val local = stored.toDomain().copy(
            preservationEnvelope = database.contactPayloadDao().get(stored.contact.ownerKey)?.toDomain(),
        )
        val outbox = local.pendingMutationRevision?.let {
            database.outboxDao().get(account.value, AggregateType.CONTACT.name, local.id)
        }
        if (local.pendingMutationRevision == null) {
            adoptRemote(local, remote, verified, remoteModifiedAtEpochSeconds)
            return
        }
        check(outbox != null && outbox.revision == local.pendingMutationRevision)
        if (local.remoteContactId == null) {
            // Recover only the stable vCard UID of an ambiguous creation, never a name match.
            check(remote.remoteVCardUid != null &&
                (remote.remoteVCardUid == local.remoteVCardUid || remote.remoteVCardUid == local.id))
            RoomContactConflictStore(database).capture(local.copy(remoteContactId = verified.id.value),
                outbox.copy(remoteIdentity = verified.id.value), verified)
            return
        }

        // Hydration (including post-write invalidation) is not evidence of a remote edit.
        // Compare against the durable intent's baseline, never against its local edited values.
        if (database.contactConflictDao().get(account.value, local.id) != null) {
            RoomContactConflictStore(database).capture(local, outbox, verified)
            return
        }
        if (verified.matchesBaseline(outbox.remoteVersion)) {
            if (outbox.state == DurableMutationState.ACTION_REQUIRED.name &&
                outbox.blockedReason == "EDIT_DELETE_RECOVERY_REQUIRED" &&
                database.outboxDao().resumeUnchangedDeleteConflict(account.value, local.id, outbox.revision) == 1
            ) {
                dao.upsert(local.copy(conflictState = null).toEntity())
            }
            return
        }

        RoomContactConflictStore(database).capture(local, outbox, verified)
    }

    private suspend fun reconcileRemoteDeletion(account: AccountScope, remoteId: RemoteContactId) {
        val stored = database.contactDao().getByRemoteContactId(account.value, remoteId.value) ?: return
        val local = stored.toDomain()
        val outbox = local.pendingMutationRevision?.let {
            database.outboxDao().get(account.value, AggregateType.CONTACT.name, local.id)
        }
        if (outbox != null && outbox.revision == local.pendingMutationRevision) {
            if (outbox.operation == MutationOperation.DELETE.name) {
                database.contactConflictDao().delete(account.value, local.id)
                database.contactDao().upsert(
                    stored.contact.copy(
                        pendingMutationRevision = null,
                        conflictState = null,
                        isDeleted = true,
                    ),
                )
                check(
                    database.outboxDao().deleteRevision(
                        account.value,
                        AggregateType.CONTACT.name,
                        local.id,
                        outbox.revision,
                    ) == 1,
                )
                return
            }
            database.contactConflictDao().get(account.value, local.id)?.let {
                database.contactConflictDao().upsert(it.copy(choice = null, remoteDeleted = true,
                    generation = java.util.UUID.randomUUID().toString()))
            }
            blockConflict(local, outbox, "REMOTE_DELETION_RECOVERY_REQUIRED")
            return
        }
        database.contactDao().upsert(
            stored.contact.copy(
                revision = stored.contact.revision + 1,
                pendingMutationRevision = null,
                conflictState = null,
                isDeleted = true,
            ),
        )
        database.contactDao().deleteValues(account.value, local.id)
    }

    /** @param modifiedAtEpochSeconds `null` for a public-directory inventory; see `D-096`. */
    internal suspend fun adoptRemote(
        local: CanonicalContact,
        remote: CanonicalContact,
        verified: VerifiedContactCard,
        modifiedAtEpochSeconds: Long?,
    ) {
        replaceContact(
            remote.copy(
                accountId = local.accountId,
                id = local.id,
                revision = local.revision + 1,
                updatedAtEpochMillis = modifiedAtEpochSeconds?.times(1_000L)
                    ?: System.currentTimeMillis(),
                remoteContactId = verified.id.value,
                remoteVersion = verified.version?.value,
                pendingMutationRevision = null,
                conflictState = null,
                isDeleted = false,
            ),
        )
    }

    internal suspend fun canAdoptRemote(contact: CanonicalContact): Boolean = pendingMembershipsFor(contact) != null

    private suspend fun pendingMembershipsFor(contact: CanonicalContact): List<GroupMembershipEntity>? {
        val oldEmails = database.contactDao().get(contact.accountId, contact.id)?.values.orEmpty()
            .filter { it.kind == ContactValueKind.EMAIL.name }.associateBy { it.id }
        val newEmails = contact.valuesOf(ContactValueKind.EMAIL)
        return database.contactGroupDao().getAll(contact.accountId)
            .filter { it.group.pendingMutationRevision != null }
            .flatMap { stored -> stored.memberships.filter { it.contactId == contact.id } }
            .map { membership ->
                val old = oldEmails[membership.emailValueId] ?: return null
                val replacement = newEmails.singleOrNull { it.id == old.id && it.value == old.value }
                    ?: newEmails.singleOrNull { it.value.trim().equals(old.value.trim(), ignoreCase = true) }
                if (replacement == null) return null
                membership.copy(emailValueId = replacement.id)
            }
    }

    private suspend fun replaceContact(contact: CanonicalContact) {
        val dao = database.contactDao()
        // An acknowledged contact write may need a fresh card while independent group intent
        // is still pending. Preserve those assignments across value-ID replacement/FK cleanup.
        val pendingMemberships = checkNotNull(pendingMembershipsFor(contact)) { "PENDING_GROUP_EMAIL_RECONCILIATION_REQUIRED" }
        dao.upsert(contact.toEntity())
        dao.upsertValues(contact.values.map { it.toEntity(contact) })
        val retained = contact.values.map { it.id }
        if (retained.isEmpty()) dao.deleteValues(contact.accountId, contact.id)
        else dao.deleteValuesNotIn(contact.accountId, contact.id, retained)
        contact.preservationEnvelope?.let {
            database.contactPayloadDao().upsert(it.toEntity(contact.accountId, contact.id))
        }
        reconcileGroupMemberships(contact)
        database.contactGroupDao().insertMemberships(pendingMemberships)
        attachToAndroidProjection(contact)
    }

    /**
     * Projects Proton's per-email group labels into canonical memberships.
     *
     * The vCard decoder already stores the label identifiers in each email value's metadata, but
     * nothing read them back, so every synchronized group stayed empty and the Groups screen showed
     * only zero-member entries. Per `D-007` membership is email-level on Proton, which is exactly
     * what the metadata carries.
     */
    private suspend fun reconcileGroupMemberships(contact: CanonicalContact) {
        val groupDao = database.contactGroupDao()
        // Proton labels are remote identifiers; canonical groups key on their own id.
        val byRemoteLabel = groupDao.getAll(contact.accountId)
            .filter { it.group.pendingMutationRevision == null }
            .mapNotNull { stored -> stored.group.remoteLabelId?.let { it to stored.toDomain() } }
            .toMap()
        if (byRemoteLabel.isEmpty()) return

        val desired = contact.values
            .filter { it.kind == ContactValueKind.EMAIL }
            .flatMap { email ->
                email.metadata[PROTON_GROUP_IDS_KEY]
                    .orEmpty()
                    .split(',')
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    // A label absent from the group inventory is skipped; the next pass picks it
                    // up once the group stage has imported it.
                    .mapNotNull { labelId -> byRemoteLabel[labelId] }
                    .map { group -> group to GroupMembership(contact.id, email.id) }
            }
            .distinct()

        byRemoteLabel.values.forEach { group ->
            group.memberships
                .filter { it.contactId == contact.id }
                .forEach { existing ->
                    groupDao.deleteMembership(
                        accountId = contact.accountId,
                        groupId = group.id,
                        contactId = existing.contactId,
                        emailValueId = existing.emailValueId,
                    )
                }
        }
        if (desired.isEmpty()) return
        groupDao.insertMemberships(desired.map { (group, membership) -> membership.toEntity(group) })
    }

    /**
     * Registers the contact with the Android projection ledger.
     *
     * Only `RoomAndroidCreatedContactCommitter` attached contacts, which covers the Android-created
     * path alone. A contact adopted from Proton therefore never entered the ledger, the projection
     * page was always empty, and the pass reported success while writing nothing to the provider.
     *
     * A hydrated remote card may replace canonical value identities even when its visible values
     * are unchanged. An existing clean Android ledger must therefore become projectable again;
     * otherwise the provider keeps the old row identity and membership preferred-email context,
     * and the next ingestion correctly rejects the stale ledger. Both contact and membership
     * projection intents are invalidated in this caller-owned Room transaction.
     *
     * Conflicting pre-existing Android ownership remains a no-op under `D-062`. A persistence
     * failure is not swallowed: the outer reconciliation transaction MUST roll back rather than
     * commit only one side of the projection state.
     */
    private suspend fun attachToAndroidProjection(contact: CanonicalContact) {
        val sourceIdentity = contact.remoteContactId ?: return
        val ledgerDao = database.androidProjectionLedgerDao()
        val current = ledgerDao.get(contact.accountId, contact.id)
        if (current == null) {
            // The source identity is already known here, unlike the Android-created path.
            RoomAndroidProjectionLedger(database).attachCanonicalContact(
                AccountScope(contact.accountId),
                contact.id,
                sourceIdentity,
            )
            return
        }
        if (current.tombstoneState != AndroidTombstoneState.NONE.name) return
        val adoptionState = when {
            current.sourceIdentity == sourceIdentity -> current.adoptionState
            current.sourceIdentity == null &&
                current.adoptionState == AndroidAdoptionState.AWAITING_REMOTE_ID.name ->
                AndroidAdoptionState.SOURCE_ID_PENDING.name
            else -> return
        }
        check(ledgerDao.update(current.copy(
            revision = Math.incrementExact(current.revision),
            sourceIdentity = sourceIdentity,
            pendingProjectionFingerprint = null,
            projectionState = AndroidProjectionWriteState.DETACHED.name,
            adoptionState = adoptionState,
        )) == 1)

        val groupDao = database.androidGroupProjectionDao()
        groupDao.getMembership(contact.accountId, contact.id)?.let { membership ->
            check(groupDao.updateMembership(membership.copy(
                revision = Math.incrementExact(membership.revision),
                pendingProjectionFingerprint = null,
                projectionState = AndroidProjectionWriteState.DETACHED.name,
            )) == 1)
        }
    }

    private suspend fun blockConflict(
        local: CanonicalContact,
        outbox: OutboxMutationEntity,
        reason: String,
    ) {
        database.contactDao().upsert(local.copy(conflictState = reason).toEntity())
        database.outboxDao().upsert(outbox.copy(state = DurableMutationState.ACTION_REQUIRED.name,
            blockedReason = reason, errorCategory = "CONFLICT"))
    }
}
