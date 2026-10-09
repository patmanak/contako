package com.patmanak.contako.data.sync

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
        if (command.operation == RemoteMutationOperation.DELETE && contact.remoteContactId == null) {
            return GatewayOutcome.Success(
                MutationPreparation.AlreadyApplied(RemoteMutationAcknowledgement(null, null)),
            )
        }
        if (command.requiresReconciliation && contact.remoteContactId == null) {
            return GatewayOutcome.Success(
                MutationPreparation.ActionRequired(
                    MutationPreparationActionRequiredReason.RECONCILIATION_REMOTE_IDENTITY_MISSING,
                ),
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
                    else GatewayOutcome.Success(com.patmanak.contako.data.local.RoomContactConflictStore(database)
                        .prepare(expectedAccount.value, contact.id, command.revision, fresh.value))
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
        // Removed edges are absent from desired memberships, and email-label metadata may
        // predate independent group edits. Without a durable negative-edge baseline, wait
        // for all account contact writes before reconciling assignments (including empty
        // groups). A blocked contact retains group intent instead of acknowledging it early.
        if (database.outboxDao().hasPendingContactWrite(group.accountId)) {
            return GatewayOutcome.Success(MutationPreparation.WaitingForDependencies)
        }
        var missingIdentityReason = MutationPreparationActionRequiredReason.GROUP_MEMBER_REMOTE_EMAIL_IDENTITY_MISSING
        val desired = desiredEmailIds(group.accountId, group.id) { missingIdentityReason = it }
            ?: return GatewayOutcome.Success(
                MutationPreparation.ActionRequired(
                    missingIdentityReason,
                ),
            )
        // The upload step reads current membership; its reconciled gateway also verifies
        // immediately before/after writing. Only ambiguous delivery needs this extra read.
        if (!command.requiresReconciliation) {
            return GatewayOutcome.Success(MutationPreparation.UploadAllowed)
        }
        return when (val current = membershipReader.members(expectedAccount, groupId)) {
            is GatewayOutcome.Failure -> current
            is GatewayOutcome.Success -> {
                if (current.value.account != expectedAccount || current.value.groupId != groupId) {
                    GatewayOutcome.Failure(GatewayFailureCategory.MALFORMED_RESPONSE)
                } else if (current.value.emailIds.toSet() == desired.toSet()) {
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
    ): List<RemoteEmailId>? {
        val group = database.contactGroupDao().get(accountId, groupId) ?: return null
        return group.memberships.map { membership ->
            val value = database.contactDao().getValue(accountId, membership.contactId, membership.emailValueId)
                ?: run {
                    onMissing(MutationPreparationActionRequiredReason.GROUP_MEMBER_LOCAL_EMAIL_MISSING)
                    return null
                }
            val remoteId = StringMapCodec.decode(value.metadataEncoding)["protonEmailId"]
                ?.takeIf(String::isNotBlank) ?: run {
                    val repeated = database.contactDao().get(accountId, membership.contactId)?.values.orEmpty().any {
                        it.kind == "EMAIL" && it.id != value.id &&
                            it.value.trim().equals(value.value.trim(), ignoreCase = true)
                    }
                    onMissing(if (repeated) MutationPreparationActionRequiredReason.GROUP_MEMBER_DUPLICATE_EMAIL_IDENTITY_MISSING
                        else MutationPreparationActionRequiredReason.GROUP_MEMBER_REMOTE_EMAIL_IDENTITY_MISSING)
                    return null
                }
            RemoteEmailId(remoteId)
        }.distinct()
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
        val contact = stored.toDomain().copy(preservationEnvelope = payload)
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
        val desired = preparation.desiredEmailIds(accountId, groupId)
            ?: return GatewayOutcome.Failure(GatewayFailureCategory.VALIDATION_REJECTED)
        val current = when (val result = membershipReader.members(expectedAccount, remoteGroupId)) {
            is GatewayOutcome.Failure -> return result
            is GatewayOutcome.Success -> result.value.emailIds
        }
        val missing = desired.filterNot(current.toSet()::contains)
        val extra = current.filterNot(desired.toSet()::contains)
        if (missing.isEmpty() && extra.isEmpty()) {
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
                GatewayOutcome.Success(
                    RemoteMutationAcknowledgement(
                        group.remoteLabelId,
                        group.remoteVersion,
                        fullyConverged = !remaining,
                        nextOperation = if (remaining) RemoteMutationOperation.ASSIGNMENTS else null,
                    ),
                )
            }
        }
    }
}
