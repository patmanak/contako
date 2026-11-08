package com.patmanak.contako.data.sync

import androidx.room.withTransaction
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.ContactGroupMutation
import com.patmanak.contako.data.gateway.ContactMutation
import com.patmanak.contako.data.gateway.EmailLabelMutation
import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.data.gateway.GatewayOutcome
import com.patmanak.contako.data.gateway.ProtonContactEmailLabelGateway
import com.patmanak.contako.data.gateway.ProtonContactGroupGateway
import com.patmanak.contako.data.gateway.ProtonContactMutationGateway
import com.patmanak.contako.data.gateway.RemoteContactId
import com.patmanak.contako.data.gateway.RemoteEmailId
import com.patmanak.contako.data.gateway.RemoteGroupId
import com.patmanak.contako.data.local.AggregateType
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.data.local.StringMapCodec
import com.patmanak.contako.data.local.toDomain
import com.patmanak.contako.data.local.fingerprint
import com.patmanak.contako.data.proton.ProtonEmailGroupMembershipReader

/**
 * Local preflight after the ordered remote contact/group stages. Ambiguous creates without a
 * recovered remote identity are deliberately blocked; they are never replayed blindly.
 */
internal class RoomBackedMutationPreparationGateway(
    private val expectedAccount: AccountScope,
    private val database: ContakoDatabase,
    private val membershipReader: ProtonEmailGroupMembershipReader,
    private val verifiedCards: com.patmanak.contako.data.gateway.ProtonVerifiedContactCardGateway? = null,
    private val existence: com.patmanak.contako.data.gateway.ProtonContactExistenceGateway? = null,
    private val createRecovery: com.patmanak.contako.data.local.RoomAmbiguousContactCreateRecovery? = null,
) : MutationPreparationGateway {
    override suspend fun prepare(command: DurableMutationCommand): GatewayOutcome<MutationPreparation> {
        if (command.accountId != expectedAccount.value) {
            return GatewayOutcome.Failure(GatewayFailureCategory.AUTHENTICATION_REQUIRED)
        }
        return when (AggregateType.valueOf(command.aggregateType)) {
            AggregateType.CONTACT -> prepareContact(command)
            AggregateType.GROUP -> prepareGroup(command)
        }
    }

    private suspend fun prepareContact(command: DurableMutationCommand): GatewayOutcome<MutationPreparation> {
        val contact = database.contactDao().get(expectedAccount.value, command.aggregateId)?.toDomain()
            ?: return GatewayOutcome.Success(
                MutationPreparation.ActionRequired(MutationPreparationActionRequiredReason.AGGREGATE_MISSING),
            )
        if (contact.pendingMutationRevision != command.revision) {
            return GatewayOutcome.Success(
                MutationPreparation.ActionRequired(MutationPreparationActionRequiredReason.REVISION_MISMATCH),
            )
        }
        val currentIntent = database.outboxDao().get(command.accountId, command.aggregateType, command.aggregateId)
            ?.takeIf { it.revision == command.revision }
        // A durable explicit choice must reach the fresh-card conflict guard. Recovery scans
        // cannot decide that choice, and prepare() invalidates it if the remote card changed.
        val chosenConflict = database.contactConflictDao().get(command.accountId, command.aggregateId)?.let {
            it.choice != null && it.localRevision == command.revision &&
                it.remoteId == contact.remoteContactId && !it.remoteDeleted
        } == true
        if ((currentIntent?.requiresReconciliation ?: command.requiresReconciliation) && (contact.remoteContactId == null ||
                (contact.remoteVersion == null && contact.conflictState ==
                    com.patmanak.contako.data.local.RoomContactConflictStore.CONFLICT && !chosenConflict))) {
            return createRecovery?.prepare(command) ?: GatewayOutcome.Success(
                MutationPreparation.ActionRequired(
                    MutationPreparationActionRequiredReason.RECONCILIATION_REMOTE_IDENTITY_MISSING,
                ),
            )
        }
        if (command.operation == RemoteMutationOperation.DELETE && contact.remoteContactId == null) {
            return GatewayOutcome.Success(
                MutationPreparation.AlreadyApplied(RemoteMutationAcknowledgement(null, null)),
            )
        }
        val remoteId = contact.remoteContactId
        if (remoteId != null) {
            // A deletion can follow an already reconciled remote deletion. The directory
            // checkpoint no longer carries that ID; obtain fresh targeted absence proof.
            // Never infer convergence from a failed card read or an old conflict snapshot.
            if (command.operation == RemoteMutationOperation.DELETE && existence != null) {
                when (val presence = existence.check(expectedAccount, RemoteContactId(remoteId))) {
                    is GatewayOutcome.Failure -> return presence
                    is GatewayOutcome.Success -> if (presence.value ==
                        com.patmanak.contako.data.gateway.RemoteContactPresence.CONFIRMED_ABSENT) {
                        return GatewayOutcome.Success(MutationPreparation.AlreadyApplied(
                            RemoteMutationAcknowledgement(remoteId, contact.remoteVersion),
                        ))
                    }
                }
            }
            val reader = verifiedCards ?: return GatewayOutcome.Failure(GatewayFailureCategory.UNKNOWN)
            return when (val fresh = reader.fetch(expectedAccount, RemoteContactId(remoteId))) {
                // A generic full-card 404 is not the targeted structured absence proof.
                // Let the directory/existence stage establish deletion on a later pass.
                is GatewayOutcome.Failure -> if (fresh.category == GatewayFailureCategory.NOT_FOUND)
                    GatewayOutcome.Failure(GatewayFailureCategory.CONFLICT) else fresh
                is GatewayOutcome.Success -> {
                    if (fresh.value.id.value != remoteId) GatewayOutcome.Failure(GatewayFailureCategory.MALFORMED_RESPONSE)
                    else {
                        val decision = com.patmanak.contako.data.local.RoomContactConflictStore(database)
                            .prepare(expectedAccount.value, contact.id, command.revision, fresh.value)
                        if (decision == MutationPreparation.UploadAllowed &&
                            !com.patmanak.contako.data.local.RoomContactUploadOverlays(database).refreshAccepted(contact, fresh.value.contact))
                            GatewayOutcome.Failure(GatewayFailureCategory.RECONCILIATION_INCOMPLETE)
                        else GatewayOutcome.Success(decision)
                    }
                }
            }
        }
        return GatewayOutcome.Success(MutationPreparation.UploadAllowed)
    }

    private suspend fun prepareGroup(command: DurableMutationCommand): GatewayOutcome<MutationPreparation> {
        val group = database.contactGroupDao().get(expectedAccount.value, command.aggregateId)?.toDomain()
            ?: return GatewayOutcome.Success(
                MutationPreparation.ActionRequired(MutationPreparationActionRequiredReason.AGGREGATE_MISSING),
            )
        if (group.pendingMutationRevision != command.revision) {
            return GatewayOutcome.Success(
                MutationPreparation.ActionRequired(MutationPreparationActionRequiredReason.REVISION_MISMATCH),
            )
        }
        if (command.requiresReconciliation && group.remoteLabelId == null) {
            return GatewayOutcome.Success(
                MutationPreparation.ActionRequired(
                    MutationPreparationActionRequiredReason.RECONCILIATION_REMOTE_IDENTITY_MISSING,
                ),
            )
        }
        if (command.operation != RemoteMutationOperation.ASSIGNMENTS) {
            if (command.operation == RemoteMutationOperation.DELETE && group.remoteLabelId == null) {
                return GatewayOutcome.Success(MutationPreparation.AlreadyApplied(RemoteMutationAcknowledgement(null, null)))
            }
            if (
                command.operation == RemoteMutationOperation.UPDATE &&
                group.remoteLabelId != null &&
                group.remoteVersion == com.patmanak.contako.data.gateway.RemoteContactGroup(
                    RemoteGroupId(group.remoteLabelId), group.name, group.color,
                ).fingerprint()
            ) {
                return GatewayOutcome.Success(
                    MutationPreparation.AlreadyApplied(
                        RemoteMutationAcknowledgement(
                            group.remoteLabelId,
                            group.remoteVersion,
                            fullyConverged = false,
                            nextOperation = RemoteMutationOperation.ASSIGNMENTS,
                        ),
                    ),
                )
            }
            return GatewayOutcome.Success(MutationPreparation.UploadAllowed)
        }
        val groupId = group.remoteLabelId?.let(::RemoteGroupId)
            ?: return GatewayOutcome.Success(
                MutationPreparation.ActionRequired(
                    MutationPreparationActionRequiredReason.GROUP_REMOTE_IDENTITY_MISSING,
                ),
            )
        // Contact PUTs can replace email identities and restore labels from their vCard.
        // Retained edges qualify current/removed dependencies. An incomplete legacy read
        // falls back to all pending contact writes, including for an empty desired group.
        val dependencies = com.patmanak.contako.data.local.RoomRemoteGroupAssignmentDependencies(database)
        if (dependencies.requiresVerifiedMembership(expectedAccount, group.id)) {
            when (val members = membershipReader.members(expectedAccount, groupId)) {
                is GatewayOutcome.Failure -> return members
                is GatewayOutcome.Success -> {
                    if (members.value.account != expectedAccount || members.value.groupId != groupId)
                        return GatewayOutcome.Failure(GatewayFailureCategory.MALFORMED_RESPONSE)
                    if (dependencies.recordVerifiedMembershipForPreparation(expectedAccount, group.id, group.revision,
                            groupId, members.value.emailIds.toSet()) ==
                        com.patmanak.contako.data.local.VerifiedGroupMembershipRecord.STALE)
                        return GatewayOutcome.Failure(GatewayFailureCategory.CONFLICT)
                }
            }
        }
        if (dependencies.hasPendingContactWriteForGroup(expectedAccount, group.id)) {
            return GatewayOutcome.Success(MutationPreparation.WaitingForDependencies)
        }
        var missingIdentityReason = MutationPreparationActionRequiredReason.GROUP_MEMBER_REMOTE_EMAIL_IDENTITY_MISSING
        val projection = desiredEmailProjection(group.accountId, group.id) { missingIdentityReason = it }
            ?: return GatewayOutcome.Success(
                MutationPreparation.ActionRequired(
                    missingIdentityReason,
                ),
            )
        // The upload step reads current membership; its reconciled gateway also verifies
        // immediately before/after writing. A partial group also needs this read to
        // wait quietly once its accepted members are delivered.
        if (!command.requiresReconciliation && !projection.hasDeferredMembers) {
            return GatewayOutcome.Success(MutationPreparation.UploadAllowed)
        }
        return when (val current = membershipReader.members(expectedAccount, groupId)) {
            is GatewayOutcome.Failure -> current
            is GatewayOutcome.Success -> {
                if (current.value.account != expectedAccount || current.value.groupId != groupId) {
                    GatewayOutcome.Failure(GatewayFailureCategory.MALFORMED_RESPONSE)
                } else if (current.value.emailIds.toSet() == projection.emailIds.toSet()) {
                    // Accepted members are delivered, but the local membership of an omitted
                    // invalid occurrence is still durable. It is not a group conflict or ACK.
                    if (projection.hasDeferredMembers) return GatewayOutcome.Success(MutationPreparation.WaitingForDependencies)
                    GatewayOutcome.Success(
                        MutationPreparation.AlreadyApplied(
                            RemoteMutationAcknowledgement(group.remoteLabelId, group.remoteVersion),
                        ),
                    )
                } else {
                    GatewayOutcome.Success(MutationPreparation.UploadAllowed)
                }
            }
        }
    }

    internal suspend fun desiredEmailIds(
        accountId: String,
        groupId: String,
        onMissing: (MutationPreparationActionRequiredReason) -> Unit = {},
    ): List<RemoteEmailId>? = desiredEmailProjection(accountId, groupId, onMissing)?.emailIds

    internal data class GroupEmailProjection(val emailIds: List<RemoteEmailId>, val hasDeferredMembers: Boolean) {
        override fun toString() = "GroupEmailProjection(REDACTED)"
    }

    /** Omit only occurrences whose current durable overlay authorizes omission from the contact PUT. */
    internal suspend fun desiredEmailProjection(
        accountId: String,
        groupId: String,
        onMissing: (MutationPreparationActionRequiredReason) -> Unit = {},
    ): GroupEmailProjection? = database.withTransaction {
        val group = database.contactGroupDao().get(accountId, groupId) ?: return@withTransaction null
        val identities = mutableListOf<RemoteEmailId>()
        var deferred = false
        for (membership in group.memberships) {
            val value = database.contactDao().getValue(accountId, membership.contactId, membership.emailValueId)
                ?: run {
                    onMissing(MutationPreparationActionRequiredReason.GROUP_MEMBER_LOCAL_EMAIL_MISSING)
                    return@withTransaction null
                }
            val remoteId = StringMapCodec.decode(value.metadataEncoding)["protonEmailId"]
                ?.takeIf(String::isNotBlank)
            if (remoteId == null) {
                val contact = database.contactDao().get(accountId, membership.contactId)?.toDomain()
                val overlay = database.contactEmailUploadOverlayDao().getForContact(accountId, membership.contactId)
                    .singleOrNull { it.valueId == membership.emailValueId }
                if (contact != null && value.kind == "EMAIL" && overlay != null &&
                    overlay.canonicalRevision == contact.revision && overlay.sourceEmailId == null &&
                    overlay.acceptedValue == null && !overlay.requiresRemoteIdentity &&
                    com.patmanak.contako.data.local.RoomContactUploadOverlays(database).wireProjection(contact)
                        ?.values?.none { it.id == value.id } == true) {
                    deferred = true
                    continue
                }
                val repeated = database.contactDao().get(accountId, membership.contactId)?.values.orEmpty().any {
                    it.kind == "EMAIL" && it.id != value.id &&
                        it.value.trim().equals(value.value.trim(), ignoreCase = true)
                }
                onMissing(if (repeated) MutationPreparationActionRequiredReason.GROUP_MEMBER_DUPLICATE_EMAIL_IDENTITY_MISSING
                    else MutationPreparationActionRequiredReason.GROUP_MEMBER_REMOTE_EMAIL_IDENTITY_MISSING)
                return@withTransaction null
            }
            identities += RemoteEmailId(remoteId)
        }
        GroupEmailProjection(identities.distinct(), deferred)
    }
}

/** Maps durable canonical references to exactly one Proton write attempt per invocation. */
internal class RoomBackedMutationUploadGateway(
    private val expectedAccount: AccountScope,
    private val database: ContakoDatabase,
    private val contactGateway: ProtonContactMutationGateway,
    private val groupGateway: ProtonContactGroupGateway,
    private val membershipReader: ProtonEmailGroupMembershipReader,
    private val assignmentGateway: ProtonContactEmailLabelGateway,
) : MutationUploadGateway {
    private val preparation = RoomBackedMutationPreparationGateway(expectedAccount, database, membershipReader)

    override suspend fun upload(command: DurableMutationCommand): GatewayOutcome<RemoteMutationAcknowledgement> {
        if (command.accountId != expectedAccount.value) {
            return GatewayOutcome.Failure(GatewayFailureCategory.AUTHENTICATION_REQUIRED)
        }
        return when (AggregateType.valueOf(command.aggregateType)) {
            AggregateType.CONTACT -> uploadContact(command)
            AggregateType.GROUP -> uploadGroup(command)
        }
    }

    private suspend fun uploadContact(command: DurableMutationCommand): GatewayOutcome<RemoteMutationAcknowledgement> {
        val stored = database.contactDao().get(expectedAccount.value, command.aggregateId)
            ?: return GatewayOutcome.Failure(GatewayFailureCategory.VALIDATION_REJECTED)
        if (stored.contact.revision != command.revision || stored.contact.pendingMutationRevision != command.revision) {
            return GatewayOutcome.Failure(GatewayFailureCategory.CONFLICT)
        }
        val payload = database.contactPayloadDao().get(stored.contact.ownerKey)?.toDomain()
        val local = stored.toDomain().copy(preservationEnvelope = payload)
        val contact = if (command.operation == RemoteMutationOperation.DELETE) local else
            com.patmanak.contako.data.local.RoomContactUploadOverlays(database).wireProjection(local)
                ?: return GatewayOutcome.Failure(GatewayFailureCategory.RECONCILIATION_INCOMPLETE)
        val mutation = when (command.operation) {
            RemoteMutationOperation.CREATE -> ContactMutation.Create(contact)
            RemoteMutationOperation.UPDATE -> ContactMutation.Update(
                RemoteContactId(requireNotNull(contact.remoteContactId)), null, contact,
            )
            RemoteMutationOperation.DELETE -> ContactMutation.Delete(
                RemoteContactId(requireNotNull(contact.remoteContactId)), null,
            )
            RemoteMutationOperation.ASSIGNMENTS ->
                return GatewayOutcome.Failure(GatewayFailureCategory.VALIDATION_REJECTED)
        }
        return when (val result = contactGateway.apply(expectedAccount, mutation)) {
            is GatewayOutcome.Failure -> result
            is GatewayOutcome.Success -> GatewayOutcome.Success(
                RemoteMutationAcknowledgement(result.value.id.value, result.value.version?.value,
                    emailIdsByValueId = result.value.emailIdsByValueId),
            )
        }
    }

    private suspend fun uploadGroup(command: DurableMutationCommand): GatewayOutcome<RemoteMutationAcknowledgement> {
        val group = database.contactGroupDao().get(expectedAccount.value, command.aggregateId)?.toDomain()
            ?: return GatewayOutcome.Failure(GatewayFailureCategory.VALIDATION_REJECTED)
        return when (command.operation) {
            RemoteMutationOperation.CREATE -> when (
                val result = groupGateway.create(expectedAccount, ContactGroupMutation.Create(group.name, group.color))
            ) {
                is GatewayOutcome.Failure -> result
                is GatewayOutcome.Success -> GatewayOutcome.Success(
                    RemoteMutationAcknowledgement(
                        result.value.id.value,
                        result.value.fingerprint(),
                        fullyConverged = false,
                        nextOperation = RemoteMutationOperation.ASSIGNMENTS,
                    ),
                )
            }
            RemoteMutationOperation.UPDATE -> when (
                val result = groupGateway.update(
                    expectedAccount,
                    ContactGroupMutation.Update(RemoteGroupId(requireNotNull(group.remoteLabelId)), group.name, group.color),
                )
            ) {
                is GatewayOutcome.Failure -> result
                is GatewayOutcome.Success -> GatewayOutcome.Success(
                    RemoteMutationAcknowledgement(
                        result.value.id.value,
                        result.value.fingerprint(),
                        fullyConverged = false,
                        nextOperation = RemoteMutationOperation.ASSIGNMENTS,
                    ),
                )
            }
            RemoteMutationOperation.DELETE -> when (
                val result = groupGateway.delete(
                    expectedAccount,
                    ContactGroupMutation.Delete(RemoteGroupId(requireNotNull(group.remoteLabelId))),
                )
            ) {
                is GatewayOutcome.Failure -> result
                is GatewayOutcome.Success -> GatewayOutcome.Success(
                    RemoteMutationAcknowledgement(group.remoteLabelId, null),
                )
            }
            RemoteMutationOperation.ASSIGNMENTS -> uploadAssignmentStep(group.accountId, group.id)
        }
    }

    private suspend fun uploadAssignmentStep(
        accountId: String,
        groupId: String,
    ): GatewayOutcome<RemoteMutationAcknowledgement> {
        val group = database.contactGroupDao().get(accountId, groupId)?.toDomain()
            ?: return GatewayOutcome.Failure(GatewayFailureCategory.VALIDATION_REJECTED)
        val remoteGroupId = group.remoteLabelId?.let(::RemoteGroupId)
            ?: return GatewayOutcome.Failure(GatewayFailureCategory.VALIDATION_REJECTED)
        val projection = preparation.desiredEmailProjection(accountId, groupId)
            ?: return GatewayOutcome.Failure(GatewayFailureCategory.VALIDATION_REJECTED)
        val desired = projection.emailIds
        val current = when (val result = membershipReader.members(expectedAccount, remoteGroupId)) {
            is GatewayOutcome.Failure -> return result
            is GatewayOutcome.Success -> {
                if (result.value.account != expectedAccount || result.value.groupId != remoteGroupId)
                    return GatewayOutcome.Failure(GatewayFailureCategory.MALFORMED_RESPONSE)
                result.value.emailIds
            }
        }
        val missing = desired.filterNot(current.toSet()::contains)
        val extra = current.filterNot(desired.toSet()::contains)
        if (missing.isEmpty() && extra.isEmpty()) {
            if (projection.hasDeferredMembers) return GatewayOutcome.Success(RemoteMutationAcknowledgement(
                group.remoteLabelId, group.remoteVersion, fullyConverged = false, nextOperation = RemoteMutationOperation.ASSIGNMENTS))
            if (!com.patmanak.contako.data.local.RoomRemoteGroupAssignmentDependencies(database).acknowledgeVerifiedMembership(
                    expectedAccount, group.id, group.revision, remoteGroupId, current.toSet()))
                return GatewayOutcome.Success(RemoteMutationAcknowledgement(group.remoteLabelId, group.remoteVersion,
                    fullyConverged = false, nextOperation = RemoteMutationOperation.ASSIGNMENTS))
            return GatewayOutcome.Success(RemoteMutationAcknowledgement(group.remoteLabelId, group.remoteVersion))
        }
        val mutation = if (missing.isNotEmpty()) {
            EmailLabelMutation.Assign(remoteGroupId, missing.take(EmailLabelMutation.MAX_EMAIL_IDS_PER_MUTATION))
        } else {
            EmailLabelMutation.Remove(remoteGroupId, extra.take(EmailLabelMutation.MAX_EMAIL_IDS_PER_MUTATION))
        }
        return when (val result = assignmentGateway.apply(expectedAccount, mutation)) {
            is GatewayOutcome.Failure -> result
            is GatewayOutcome.Success -> {
                val remaining = if (missing.isNotEmpty()) {
                    missing.size > EmailLabelMutation.MAX_EMAIL_IDS_PER_MUTATION || extra.isNotEmpty()
                } else {
                    extra.size > EmailLabelMutation.MAX_EMAIL_IDS_PER_MUTATION
                }
                if (!remaining) {
                    val confirmed = when (val read = membershipReader.members(expectedAccount, remoteGroupId)) {
                        is GatewayOutcome.Failure -> return read
                        is GatewayOutcome.Success -> read.value
                    }
                    if (confirmed.account != expectedAccount || confirmed.groupId != remoteGroupId || confirmed.emailIds.toSet() != desired.toSet())
                        return GatewayOutcome.Failure(GatewayFailureCategory.CONFLICT)
                    if (!projection.hasDeferredMembers && !com.patmanak.contako.data.local.RoomRemoteGroupAssignmentDependencies(database).acknowledgeVerifiedMembership(
                            expectedAccount, group.id, group.revision, remoteGroupId, confirmed.emailIds.toSet()))
                        return GatewayOutcome.Success(RemoteMutationAcknowledgement(group.remoteLabelId, group.remoteVersion,
                            fullyConverged = false, nextOperation = RemoteMutationOperation.ASSIGNMENTS))
                }
                GatewayOutcome.Success(
                    RemoteMutationAcknowledgement(
                        group.remoteLabelId,
                        group.remoteVersion,
                        fullyConverged = !remaining && !projection.hasDeferredMembers,
                        nextOperation = if (remaining || projection.hasDeferredMembers) RemoteMutationOperation.ASSIGNMENTS else null,
                    ),
                )
            }
        }
    }
}
