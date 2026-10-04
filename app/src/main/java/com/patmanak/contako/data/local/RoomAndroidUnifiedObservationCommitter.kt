package com.patmanak.contako.data.local

import androidx.room.withTransaction
import com.patmanak.contako.data.android.AndroidProjectionLedgerSnapshot
import com.patmanak.contako.data.android.AndroidRawContactLocator
import com.patmanak.contako.data.android.mapping.AndroidCanonicalDelta
import com.patmanak.contako.data.android.mapping.AndroidContactSnapshot
import com.patmanak.contako.data.android.mapping.AndroidContactSnapshotBinaryCodec
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipSnapshotBinaryCodec
import com.patmanak.contako.data.android.mapping.AndroidCompleteGroupCatalog
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipAvailability
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipLocatorMapping
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipObservationDecoder
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipSnapshot
import com.patmanak.contako.data.android.mapping.AndroidTrustedGroupBinding
import com.patmanak.contako.data.android.provider.GroupMembershipRows
import com.patmanak.contako.data.android.provider.AndroidOwnedDataRow
import com.patmanak.contako.domain.policy.CanonicalPrimaryValuePolicy
import com.patmanak.contako.domain.repository.SaveValidationIssue
import java.nio.ByteBuffer
import java.security.MessageDigest

internal data class RoomAndroidUnifiedObservationCommand(
    val contactLedger: AndroidProjectionLedgerSnapshot,
    val locator: AndroidRawContactLocator,
    val rawContactVersion: Long,
    val expectedCanonicalContactRevision: Long,
    val contactDelta: AndroidCanonicalDelta,
    val contactSnapshot: AndroidContactSnapshot,
    val membership: RoomAndroidGroupMembershipObservationCommand,
) {
    init {
        require(contactLedger.canonicalContactId == contactSnapshot.canonicalContactId)
        require(contactLedger.canonicalContactId == membership.canonicalContactId)
        require(locator == membership.locator)
        require(rawContactVersion >= 0)
    }

    override fun toString(): String = "RoomAndroidUnifiedObservationCommand(REDACTED)"
}

internal sealed interface RoomAndroidUnifiedObservationResult {
    data class Applied(
        val contact: AndroidObservationCommitResult.Applied,
        val membership: RoomAndroidGroupMembershipObservationResult.Applied,
    ) : RoomAndroidUnifiedObservationResult

    data class AlreadyCommitted(
        val contact: AndroidObservationCommitResult.Applied,
        val membership: RoomAndroidGroupMembershipObservationResult.AlreadyCommitted,
    ) : RoomAndroidUnifiedObservationResult

    data object ReplanRequired : RoomAndroidUnifiedObservationResult
    data object RepairRequired : RoomAndroidUnifiedObservationResult
}

internal enum class RoomAndroidUnifiedCommitRepairReason {
    CONTACT_CANONICAL_REJECTED,
    MEMBERSHIP_REPAIR_REQUIRED,
}

internal fun interface RoomAndroidUnifiedCommitRepairObserver {
    fun onRepairRequired(reason: RoomAndroidUnifiedCommitRepairReason)
}

internal enum class RoomAndroidUnifiedCommitReplanReason {
    REPLAY_RECEIPT,
    CONTACT_LEDGER_STALE,
    CONTACT_PROVIDER_EPOCH_STALE,
    CONTACT_CANONICAL_STALE,
    CANONICAL_POST_COMMIT_MISSING,
    MEMBERSHIP_STALE_ACCOUNT,
    MEMBERSHIP_STALE_CONTACT_LEDGER,
    MEMBERSHIP_STALE_MEMBERSHIP_LEDGER,
    MEMBERSHIP_STALE_GROUP_LEDGER,
    MEMBERSHIP_STALE_CANONICAL_CONTEXT,
    MEMBERSHIP_STALE_GROUP_CONTEXT,
}

internal fun interface RoomAndroidUnifiedCommitReplanObserver {
    fun onReplanRequired(reason: RoomAndroidUnifiedCommitReplanReason)
}

internal data class RoomAndroidCreatedRawContactAuthorization(
    val accountId: String,
    val providerEpoch: Long,
    val rawContactLocator: Long,
    val rawContactVersion: Long,
    val canonicalContactIdClaim: String?,
    val sourceIdentity: String?,
    val deleted: Boolean,
    val dirty: Boolean,
) {
    init {
        require(accountId.isNotBlank())
        require(providerEpoch >= 0 && rawContactLocator > 0 && rawContactVersion >= 0)
    }

    override fun toString(): String = "RoomAndroidCreatedRawContactAuthorization(REDACTED)"
}

internal sealed interface RoomAndroidCreatedUnifiedObservationResult {
    data class Applied(
        val canonicalRevision: Long,
        val contactLedgerRevision: Long,
        val membershipLedgerRevision: Long,
    ) : RoomAndroidCreatedUnifiedObservationResult
    data object Replayed : RoomAndroidCreatedUnifiedObservationResult
    data object ReplanRequired : RoomAndroidCreatedUnifiedObservationResult
    data object RepairRequired : RoomAndroidCreatedUnifiedObservationResult
    /** Canonical payload validation rejected this contact; the surrounding transaction rolled back. */
    data object RejectedPayload : RoomAndroidCreatedUnifiedObservationResult
}

internal data class RoomAndroidCreatedMembershipObservation(
    val catalog: AndroidCompleteGroupCatalog,
    val rows: GroupMembershipRows,
    val stableDataRows: List<AndroidOwnedDataRow>,
)

internal sealed interface RoomAndroidDeletedUnifiedObservationResult {
    data class Applied(val canonicalRevision: Long, val contactLedgerRevision: Long) :
        RoomAndroidDeletedUnifiedObservationResult
    data object Replayed : RoomAndroidDeletedUnifiedObservationResult
    data object ReplanRequired : RoomAndroidDeletedUnifiedObservationResult
    data object RepairRequired : RoomAndroidDeletedUnifiedObservationResult
}

/**
 * One lock and one outer Room transaction authorize contact and same-snapshot memberships.
 * Provider acknowledgement is deliberately outside this boundary and may run only after success.
 */
internal class RoomAndroidUnifiedObservationCommitter(
    private val database: ContakoDatabase,
    private val contactCommitter: RoomAndroidObservationCommitter,
    private val membershipCommitter: RoomAndroidGroupMembershipObservationCommitter,
    private val repairObserver: RoomAndroidUnifiedCommitRepairObserver =
        RoomAndroidUnifiedCommitRepairObserver { },
    private val replanObserver: RoomAndroidUnifiedCommitReplanObserver =
        RoomAndroidUnifiedCommitReplanObserver { },
) {
    suspend fun commitCreated(
        canonicalContactId: String,
        authorization: RoomAndroidCreatedRawContactAuthorization,
        observedContact: AndroidContactSnapshot,
        createdCommitter: RoomAndroidCreatedContactCommitter,
        membershipObservation: RoomAndroidCreatedMembershipObservation? = null,
        mapper: com.patmanak.contako.data.android.mapping.CanonicalAndroidContactMapper =
            com.patmanak.contako.data.android.mapping.CanonicalAndroidContactMapper(),
    ): RoomAndroidCreatedUnifiedObservationResult {
        return commitCreated(canonicalContactId, authorization, createdCommitter, membershipObservation, mapper) { observedContact }
    }

    suspend fun commitCreated(
        canonicalContactId: String,
        authorization: RoomAndroidCreatedRawContactAuthorization,
        createdCommitter: RoomAndroidCreatedContactCommitter,
        membershipObservation: RoomAndroidCreatedMembershipObservation? = null,
        mapper: com.patmanak.contako.data.android.mapping.CanonicalAndroidContactMapper =
            com.patmanak.contako.data.android.mapping.CanonicalAndroidContactMapper(),
        decode: () -> AndroidContactSnapshot,
    ): RoomAndroidCreatedUnifiedObservationResult {
        if (authorization.accountId.isBlank() || authorization.providerEpoch < 0 ||
            authorization.canonicalContactIdClaim != null || authorization.sourceIdentity != null || authorization.deleted
        ) return RoomAndroidCreatedUnifiedObservationResult.ReplanRequired
        val locator = AndroidRawContactLocator(authorization.providerEpoch, authorization.rawContactLocator)
        val frozenMembershipObservation = membershipObservation?.let { observation ->
            observation.copy(
                rows = GroupMembershipRows(observation.rows.rows.map { it.copy(stringSlots = it.stringSlots.toList()) }),
                stableDataRows = observation.stableDataRows.map {
                    it.copy(stringSlots = it.stringSlots.toList(), binarySlot = it.binarySlot?.copyOf())
                },
            )
        }
        return AndroidProviderAccountMutationLocks.withAccountLock(authorization.accountId) {
            try {
                database.withTransaction {
                    if (frozenMembershipObservation == null ||
                        frozenMembershipObservation.catalog.account.value != authorization.accountId ||
                        frozenMembershipObservation.catalog.providerEpoch != authorization.providerEpoch
                    ) abortCreated(RoomAndroidCreatedUnifiedObservationResult.ReplanRequired)
                    database.androidProjectionLedgerDao().getByRawContactLocator(
                        authorization.accountId,
                        authorization.providerEpoch,
                        authorization.rawContactLocator,
                    )?.let { existing ->
                        val receipt = database.androidProjectionLedgerDao().getUnifiedObservationCommitReceipt(
                            authorization.accountId,
                            existing.canonicalContactId,
                        ) ?: return@withTransaction RoomAndroidCreatedUnifiedObservationResult.RepairRequired
                        val committedMembership = database.androidGroupProjectionDao().getMembership(
                            authorization.accountId,
                            existing.canonicalContactId,
                        )
                        val committedBaseline = database.androidGroupProjectionDao().getMembershipBaseline(
                            authorization.accountId,
                            existing.canonicalContactId,
                        )
                        val membershipReceipt = database.androidGroupProjectionDao().getMembershipCommitReceipt(
                            authorization.accountId,
                            existing.canonicalContactId,
                        )
                        val account = database.androidProjectionLedgerDao().getAccount(authorization.accountId)
                        val expectedReplayProof = digest(listOf(
                            authorization.accountId,
                            existing.canonicalContactId,
                            authorization.providerEpoch,
                            authorization.rawContactLocator,
                            authorization.rawContactVersion,
                            authorization.dirty,
                            frozenMembershipObservation.stableDataRows.map(::createdRowProof),
                        ))
                        if (receipt.providerEpoch == authorization.providerEpoch &&
                            receipt.rawContactVersion == authorization.rawContactVersion &&
                            receipt.rawContactLocator == authorization.rawContactLocator &&
                            receipt.committedContactLedgerRevision == existing.revision &&
                            committedMembership?.revision == receipt.committedMembershipLedgerRevision &&
                            committedBaseline != null &&
                            membershipReceipt?.committedMembershipLedgerRevision ==
                                receipt.committedMembershipLedgerRevision &&
                            membershipReceipt.committedAccountRevision == receipt.committedAccountRevision &&
                            account?.revision == receipt.committedAccountRevision &&
                            receipt.commandFingerprint == expectedReplayProof
                        ) return@withTransaction RoomAndroidCreatedUnifiedObservationResult.Replayed
                        return@withTransaction RoomAndroidCreatedUnifiedObservationResult.ReplanRequired
                    }
                    val created = createdCommitter.commitInCurrentTransaction(
                        canonicalContactId,
                        locator,
                    ) {
                        val observedContact = decode()
                        val decoded = mapper.adoptAndroidCreatedContact(
                            authorization.accountId,
                            canonicalContactId,
                            observedContact,
                        )
                        decoded to observedContact
                    }
                    if (created !is AndroidCreatedContactCommitResult.Applied) {
                        abortCreated(RoomAndroidCreatedUnifiedObservationResult.ReplanRequired)
                    }
                    val dao = database.androidProjectionLedgerDao()
                    val account = dao.getAccount(authorization.accountId)
                        ?: abortCreated(RoomAndroidCreatedUnifiedObservationResult.ReplanRequired)
                    val contactLedger = dao.get(authorization.accountId, canonicalContactId)
                        ?: abortCreated(RoomAndroidCreatedUnifiedObservationResult.RepairRequired)
                    val observedContact = database.androidProjectionLedgerDao().getBaseline(
                        authorization.accountId,
                        canonicalContactId,
                    ) ?: abortCreated(RoomAndroidCreatedUnifiedObservationResult.RepairRequired)
                    val decoded = database.contactDao().get(authorization.accountId, canonicalContactId)?.toDomain()
                        ?: abortCreated(RoomAndroidCreatedUnifiedObservationResult.RepairRequired)
                    val groupDao = database.androidGroupProjectionDao()
                    val membership = AndroidGroupMembershipProjectionLedgerEntity(
                        accountId = authorization.accountId,
                        canonicalContactId = canonicalContactId,
                        revision = 0,
                        providerEpoch = authorization.providerEpoch,
                        rawContactLocator = authorization.rawContactLocator,
                        preferredEmailValueId = com.patmanak.contako.domain.policy.CanonicalPrimaryValuePolicy
                            .preferredEmail(decoded)?.id,
                        canonicalProjectionFingerprint = null,
                        androidBaselineFingerprint = null,
                        pendingProjectionFingerprint = null,
                        projectionState = com.patmanak.contako.data.android.AndroidProjectionWriteState.CLEAN.name,
                        ingestionState = com.patmanak.contako.data.android.AndroidIngestionState.NONE.name,
                    )
                    if (groupDao.insertMembership(membership) == -1L) {
                        abortCreated(RoomAndroidCreatedUnifiedObservationResult.ReplanRequired)
                    }
                    val groupLedgers = groupDao.getAllGroups(authorization.accountId)
                    val trusted = groupLedgers.map { group ->
                        AndroidTrustedGroupBinding(
                            frozenMembershipObservation.catalog.account,
                            frozenMembershipObservation.catalog.androidAccountName,
                            authorization.providerEpoch,
                            group.canonicalGroupId,
                            group.groupRowLocator
                                ?: abortCreated(RoomAndroidCreatedUnifiedObservationResult.RepairRequired),
                            group.providerVersion
                                ?: abortCreated(RoomAndroidCreatedUnifiedObservationResult.RepairRequired),
                            group.sourceIdentity,
                        )
                    }
                    val resolved = AndroidGroupMembershipObservationDecoder().decode(
                        frozenMembershipObservation.catalog,
                        authorization.rawContactLocator,
                        frozenMembershipObservation.rows,
                        trusted,
                    )
                    val bindingByLocator = trusted.associateBy(AndroidTrustedGroupBinding::groupRowId)
                    val mappings = frozenMembershipObservation.rows.rows.map { row ->
                        val locatorValue = row.stringSlots.firstOrNull()?.toLongOrNull()
                            ?: abortCreated(RoomAndroidCreatedUnifiedObservationResult.RepairRequired)
                        val binding = bindingByLocator[locatorValue]
                            ?: abortCreated(RoomAndroidCreatedUnifiedObservationResult.RepairRequired)
                        if (binding.canonicalGroupId !in resolved.canonicalGroupIds) {
                            abortCreated(RoomAndroidCreatedUnifiedObservationResult.RepairRequired)
                        }
                        AndroidGroupMembershipLocatorMapping(
                            binding.canonicalGroupId,
                            locatorValue,
                            row.dataRowId,
                        )
                    }
                    val preferredEmail = CanonicalPrimaryValuePolicy.preferredEmail(decoded)?.id
                    val membershipSnapshot = AndroidGroupMembershipSnapshot.create(
                        authorization.accountId,
                        canonicalContactId,
                        preferredEmail,
                        if (preferredEmail == null) AndroidGroupMembershipAvailability.NO_EMAIL
                        else AndroidGroupMembershipAvailability.AVAILABLE,
                        mappings,
                    )
                    val canonicalGroups = database.contactGroupDao().getAll(authorization.accountId)
                    val membershipResult = membershipCommitter.commitWithAccountLockHeld(
                        RoomAndroidGroupMembershipObservationCommand(
                            account = frozenMembershipObservation.catalog.account,
                            expectedAccountRevision = account.revision,
                            locator = locator,
                            canonicalContactId = canonicalContactId,
                            expectedCanonicalContactRevision = decoded.revision,
                            expectedContactLedgerRevision = contactLedger.revision,
                            expectedMembershipLedgerRevision = membership.revision,
                            expectedCanonicalGroupStates = canonicalGroups.map {
                                RoomExpectedContactGroupState(it.group.id, it.group.revision, it.group.isDeleted)
                            }.sortedBy(RoomExpectedContactGroupState::groupId),
                            expectedAndroidGroupBindings = resolved.canonicalGroupIds.sorted().map { groupId ->
                                val group = groupLedgers.firstOrNull { it.canonicalGroupId == groupId }
                                    ?: abortCreated(RoomAndroidCreatedUnifiedObservationResult.RepairRequired)
                                RoomExpectedAndroidGroupBinding(
                                    groupId,
                                    group.revision,
                                    requireNotNull(group.groupRowLocator),
                                    requireNotNull(group.providerVersion),
                                    group.sourceIdentity,
                                )
                            },
                            observedSnapshot = membershipSnapshot,
                            expectedContactAdoptionState = com.patmanak.contako.data.android.AndroidAdoptionState.AWAITING_REMOTE_ID,
                            allowInitialCanonicalDelta = true,
                        ),
                    )
                    if (membershipResult !is RoomAndroidGroupMembershipObservationResult.Applied) {
                        abortCreated(when (membershipResult) {
                            RoomAndroidGroupMembershipObservationResult.RepairRequired ->
                                RoomAndroidCreatedUnifiedObservationResult.RepairRequired
                            else -> RoomAndroidCreatedUnifiedObservationResult.ReplanRequired
                        })
                    }
                    val commandProof = digest(listOf(
                        authorization.accountId, canonicalContactId, authorization.providerEpoch,
                        authorization.rawContactLocator, authorization.rawContactVersion,
                        authorization.dirty,
                        frozenMembershipObservation.stableDataRows.map(::createdRowProof),
                    ))
                    dao.upsertUnifiedObservationCommitReceipt(
                        AndroidUnifiedObservationCommitReceiptEntity(
                            authorization.accountId,
                            canonicalContactId,
                            authorization.providerEpoch,
                            authorization.rawContactLocator,
                            authorization.rawContactVersion,
                            account.revision,
                            membershipResult.committedAccountRevision,
                            contactLedger.revision,
                            membershipResult.committedMembershipLedgerRevision,
                            commandProof,
                            digest(listOf(created.canonicalRevision, contactLedger.revision,
                                membershipResult.committedMembershipLedgerRevision,
                                membershipSnapshot.semanticFingerprint().sha256Hex)),
                        ),
                    )
                    RoomAndroidCreatedUnifiedObservationResult.Applied(
                        created.canonicalRevision,
                        contactLedger.revision,
                        membershipResult.committedMembershipLedgerRevision,
                    )
                }
            } catch (abort: CreatedUnifiedObservationAbort) {
                abort.result
            } catch (abort: AndroidCreatedContactAbort) {
                if (abort.reason == AndroidCreatedContactAbortReason.REJECTED && abort.issues.isNotEmpty() &&
                    abort.issues.all { it == SaveValidationIssue.NEGATIVE_VALUE_ORDER || it == SaveValidationIssue.DUPLICATE_VALUE_ORDER }
                ) {
                    RoomAndroidCreatedUnifiedObservationResult.RejectedPayload
                } else when (abort.reason) {
                    AndroidCreatedContactAbortReason.STALE -> RoomAndroidCreatedUnifiedObservationResult.ReplanRequired
                    AndroidCreatedContactAbortReason.REJECTED,
                    AndroidCreatedContactAbortReason.INVALID_INPUT,
                    -> RoomAndroidCreatedUnifiedObservationResult.RepairRequired
                }
            }
        }
    }

    suspend fun commitDeleted(
        authorization: RoomAndroidCreatedRawContactAuthorization,
    ): RoomAndroidDeletedUnifiedObservationResult {
        val claim = authorization.canonicalContactIdClaim
            ?: return RoomAndroidDeletedUnifiedObservationResult.ReplanRequired
        val source = authorization.sourceIdentity
            ?: return RoomAndroidDeletedUnifiedObservationResult.RepairRequired
        if (!authorization.deleted) return RoomAndroidDeletedUnifiedObservationResult.ReplanRequired
        return AndroidProviderAccountMutationLocks.withAccountLock(authorization.accountId) {
            try {
                database.withTransaction {
                    val dao = database.androidProjectionLedgerDao()
                    val account = dao.getAccount(authorization.accountId)
                        ?: abortDeleted(RoomAndroidDeletedUnifiedObservationResult.ReplanRequired)
                    if (account.providerEpoch != authorization.providerEpoch) {
                        abortDeleted(RoomAndroidDeletedUnifiedObservationResult.ReplanRequired)
                    }
                    val ledger = dao.get(authorization.accountId, claim)
                        ?: abortDeleted(RoomAndroidDeletedUnifiedObservationResult.RepairRequired)
                    val receipt = dao.getUnifiedObservationCommitReceipt(authorization.accountId, claim)
                    if (ledger.tombstoneState == com.patmanak.contako.data.android.AndroidTombstoneState.CANONICAL_COMMITTED.name) {
                        if (receipt?.providerEpoch == authorization.providerEpoch &&
                            receipt.rawContactLocator == authorization.rawContactLocator &&
                            receipt.rawContactVersion == authorization.rawContactVersion &&
                            ledger.sourceIdentity == source
                        ) return@withTransaction RoomAndroidDeletedUnifiedObservationResult.Replayed
                        abortDeleted(RoomAndroidDeletedUnifiedObservationResult.ReplanRequired)
                    }
                    if (ledger.providerEpoch != authorization.providerEpoch ||
                        ledger.rawContactLocator != authorization.rawContactLocator || ledger.sourceIdentity != source ||
                        dao.getByRawContactLocator(
                            authorization.accountId,
                            authorization.providerEpoch,
                            authorization.rawContactLocator,
                        ) != ledger
                    ) abortDeleted(RoomAndroidDeletedUnifiedObservationResult.RepairRequired)
                    val canonical = database.contactDao().get(authorization.accountId, claim)?.toDomain()
                        ?: abortDeleted(RoomAndroidDeletedUnifiedObservationResult.RepairRequired)
                    if (canonical.isDeleted || canonical.remoteContactId != source) {
                        abortDeleted(RoomAndroidDeletedUnifiedObservationResult.RepairRequired)
                    }
                    val membershipDao = database.androidGroupProjectionDao()
                    val membership = membershipDao.getMembership(authorization.accountId, claim)
                        ?: abortDeleted(RoomAndroidDeletedUnifiedObservationResult.RepairRequired)
                    if (membership.providerEpoch != authorization.providerEpoch ||
                        membership.rawContactLocator != authorization.rawContactLocator
                    ) abortDeleted(RoomAndroidDeletedUnifiedObservationResult.RepairRequired)

                    val deleted = contactCommitter.commitDeletion(
                        ledger.toSnapshot(),
                        AndroidRawContactLocator(authorization.providerEpoch, authorization.rawContactLocator),
                        canonical.revision,
                    )
                    if (deleted !is AndroidObservationCommitResult.Applied) {
                        abortDeleted(if (deleted == AndroidObservationCommitResult.CanonicalRejected) {
                            RoomAndroidDeletedUnifiedObservationResult.RepairRequired
                        } else RoomAndroidDeletedUnifiedObservationResult.ReplanRequired)
                    }
                    val detachedMembership = membership.copy(
                        revision = Math.incrementExact(membership.revision),
                        preferredEmailValueId = null,
                        canonicalProjectionFingerprint = null,
                        androidBaselineFingerprint = null,
                        pendingProjectionFingerprint = null,
                        projectionState = com.patmanak.contako.data.android.AndroidProjectionWriteState.DETACHED.name,
                        ingestionState = com.patmanak.contako.data.android.AndroidIngestionState.CANONICAL_DELTA_COMMITTED.name,
                    )
                    if (membershipDao.compareAndSetMembershipRevision(
                            authorization.accountId, claim, membership.revision,
                        ) != 1 || membershipDao.updateMembership(detachedMembership) != 1
                    ) abortDeleted(RoomAndroidDeletedUnifiedObservationResult.ReplanRequired)
                    membershipDao.deleteMembershipBaseline(authorization.accountId, claim)
                    membershipDao.deleteMembershipCommitReceipt(authorization.accountId, claim)
                    if (dao.compareAndSetAccountRevisionAtProviderEpoch(
                            authorization.accountId, account.revision, authorization.providerEpoch,
                        ) != 1
                    ) abortDeleted(RoomAndroidDeletedUnifiedObservationResult.ReplanRequired)
                    val committedLedger = deleted.observation.snapshot
                    dao.upsertUnifiedObservationCommitReceipt(AndroidUnifiedObservationCommitReceiptEntity(
                        authorization.accountId, claim, authorization.providerEpoch,
                        authorization.rawContactLocator, authorization.rawContactVersion,
                        account.revision, Math.incrementExact(account.revision), committedLedger.revision,
                        detachedMembership.revision,
                        digest(listOf("deleted", authorization.accountId, claim, authorization.providerEpoch,
                            authorization.rawContactLocator, authorization.rawContactVersion, source)),
                        digest(listOf(canonical.revision + 1, committedLedger.revision,
                            detachedMembership.revision, source)),
                    ))
                    RoomAndroidDeletedUnifiedObservationResult.Applied(canonical.revision + 1, committedLedger.revision)
                }
            } catch (abort: DeletedUnifiedObservationAbort) {
                abort.result
            }
        }
    }

    suspend fun commit(
        command: RoomAndroidUnifiedObservationCommand,
    ): RoomAndroidUnifiedObservationResult =
        AndroidProviderAccountMutationLocks.withAccountLock(command.membership.account.value) {
            try {
                database.withTransaction {
                    val dao = database.androidProjectionLedgerDao()
                    val commandFingerprint = commandFingerprint(command)
                    val existingReceipt = dao.getUnifiedObservationCommitReceipt(
                        command.membership.account.value,
                        command.contactLedger.canonicalContactId,
                    )
                    if (existingReceipt?.commandFingerprint == commandFingerprint &&
                        existingReceipt.postStateFingerprint == postStateFingerprint(command)
                    ) {
                        notifyReplan(RoomAndroidUnifiedCommitReplanReason.REPLAY_RECEIPT)
                        return@withTransaction RoomAndroidUnifiedObservationResult.ReplanRequired
                    }
                    // Validate the planned group vector before any mutation. Contact persistence
                    // queues assignment reconciliation and advances those groups in this same
                    // transaction; those own writes must not look like concurrent group edits.
                    if (canonicalGroupStates(command.membership.account.value) !=
                        command.membership.expectedCanonicalGroupStates.sortedBy(RoomExpectedContactGroupState::groupId)
                    ) replan(RoomAndroidUnifiedCommitReplanReason.MEMBERSHIP_STALE_GROUP_CONTEXT)
                    val contact = contactCommitter.commitContactDelta(
                        ledgerSnapshot = command.contactLedger,
                        locator = command.locator,
                        expectedCanonicalRevision = command.expectedCanonicalContactRevision,
                        delta = command.contactDelta,
                        observedSnapshot = command.contactSnapshot,
                    )
                    if (contact !is AndroidObservationCommitResult.Applied) {
                        if (contact == AndroidObservationCommitResult.CanonicalRejected) {
                            repair(RoomAndroidUnifiedCommitRepairReason.CONTACT_CANONICAL_REJECTED)
                        }
                        replan(contactReplanReason(contact))
                    }

                    val committedCanonicalRevision = database.contactDao().get(
                        command.membership.account.value,
                        command.contactLedger.canonicalContactId,
                    )?.contact?.revision
                        ?: replan(RoomAndroidUnifiedCommitReplanReason.CANONICAL_POST_COMMIT_MISSING)
                    val membership = membershipCommitter.commitWithAccountLockHeld(
                        command.membership.copy(
                            expectedCanonicalContactRevision = committedCanonicalRevision,
                            expectedContactLedgerRevision = contact.observation.snapshot.revision,
                            expectedCanonicalGroupStates = canonicalGroupStates(command.membership.account.value),
                        ),
                    )
                    when (membership) {
                        is RoomAndroidGroupMembershipObservationResult.Applied -> {
                            dao.upsertUnifiedObservationCommitReceipt(
                                AndroidUnifiedObservationCommitReceiptEntity(
                                    accountId = command.membership.account.value,
                                    canonicalContactId = command.contactLedger.canonicalContactId,
                                    providerEpoch = command.locator.providerEpoch,
                                    rawContactLocator = command.locator.localRowHandle,
                                    rawContactVersion = command.rawContactVersion,
                                    expectedAccountRevision = command.membership.expectedAccountRevision,
                                    committedAccountRevision = membership.committedAccountRevision,
                                    committedContactLedgerRevision = contact.observation.snapshot.revision,
                                    committedMembershipLedgerRevision = membership.committedMembershipLedgerRevision,
                                    commandFingerprint = commandFingerprint,
                                    postStateFingerprint = postStateFingerprint(command),
                                ),
                            )
                            RoomAndroidUnifiedObservationResult.Applied(contact, membership)
                        }
                        is RoomAndroidGroupMembershipObservationResult.AlreadyCommitted ->
                            RoomAndroidUnifiedObservationResult.AlreadyCommitted(contact, membership)
                        RoomAndroidGroupMembershipObservationResult.RepairRequired ->
                            repair(RoomAndroidUnifiedCommitRepairReason.MEMBERSHIP_REPAIR_REQUIRED)
                        RoomAndroidGroupMembershipObservationResult.StaleAccount ->
                            replan(RoomAndroidUnifiedCommitReplanReason.MEMBERSHIP_STALE_ACCOUNT)
                        RoomAndroidGroupMembershipObservationResult.StaleContactLedger ->
                            replan(RoomAndroidUnifiedCommitReplanReason.MEMBERSHIP_STALE_CONTACT_LEDGER)
                        RoomAndroidGroupMembershipObservationResult.StaleMembershipLedger ->
                            replan(RoomAndroidUnifiedCommitReplanReason.MEMBERSHIP_STALE_MEMBERSHIP_LEDGER)
                        RoomAndroidGroupMembershipObservationResult.StaleGroupLedger ->
                            replan(RoomAndroidUnifiedCommitReplanReason.MEMBERSHIP_STALE_GROUP_LEDGER)
                        RoomAndroidGroupMembershipObservationResult.StaleCanonicalContext ->
                            replan(RoomAndroidUnifiedCommitReplanReason.MEMBERSHIP_STALE_CANONICAL_CONTEXT)
                        RoomAndroidGroupMembershipObservationResult.StaleGroupContext ->
                            replan(RoomAndroidUnifiedCommitReplanReason.MEMBERSHIP_STALE_GROUP_CONTEXT)
                    }
                }
            } catch (abort: UnifiedObservationAbort) {
                abort.result
            }
        }

    private suspend fun canonicalGroupStates(accountId: String): List<RoomExpectedContactGroupState> =
        database.contactGroupDao().getAll(accountId).map { stored ->
            RoomExpectedContactGroupState(stored.group.id, stored.group.revision, stored.group.isDeleted)
        }.sortedBy(RoomExpectedContactGroupState::groupId)

    private suspend fun postStateFingerprint(command: RoomAndroidUnifiedObservationCommand): String {
        val accountId = command.membership.account.value
        val contactId = command.contactLedger.canonicalContactId
        val contact = database.contactDao().get(accountId, contactId)?.contact
        val contactLedger = database.androidProjectionLedgerDao().get(accountId, contactId)
        val membership = database.androidGroupProjectionDao().getMembership(accountId, contactId)
        val contactOutbox = database.outboxDao().get(accountId, AggregateType.CONTACT.name, contactId)
        return digest(listOf(
            contact?.revision, contact?.pendingMutationRevision, contact?.isDeleted,
            contactLedger?.revision, contactLedger?.androidBaselineFingerprint,
            membership?.revision, membership?.androidBaselineFingerprint,
            contactOutbox?.operation, contactOutbox?.revision,
        ))
    }

    private fun commandFingerprint(command: RoomAndroidUnifiedObservationCommand): String = digest(listOf(
        command.membership.account.value,
        command.contactLedger.canonicalContactId,
        command.locator.providerEpoch,
        command.locator.localRowHandle,
        command.rawContactVersion,
        command.expectedCanonicalContactRevision,
        AndroidContactSnapshotBinaryCodec.encode(command.contactSnapshot).contentHashCode(),
        AndroidGroupMembershipSnapshotBinaryCodec.encode(command.membership.observedSnapshot).contentHashCode(),
        command.membership.expectedCanonicalGroupStates,
        command.membership.expectedAndroidGroupBindings,
    ))

    private fun digest(values: List<Any?>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        values.forEach { value ->
            val bytes = value?.toString()?.toByteArray() ?: byteArrayOf()
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Provider version and exact photo content both protect a lost-ack creation replay. */
    private fun createdRowProof(row: AndroidOwnedDataRow): List<Any?> {
        val originalProof = listOf(row.dataRowId, row.rawContactId, row.mimeType, row.canonicalValueId,
            row.canonicalOrder, row.linkedValueIdsEncoding, row.isPrimary, row.isSuperPrimary, row.stringSlots)
        // Preserve pre-photo receipt compatibility for non-binary creations.
        val bytes = row.binarySlot ?: return originalProof
        val binaryProof = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
        return originalProof + binaryProof
    }

    private fun contactReplanReason(
        contact: AndroidObservationCommitResult,
    ): RoomAndroidUnifiedCommitReplanReason = when (contact) {
        AndroidObservationCommitResult.LedgerStale ->
            RoomAndroidUnifiedCommitReplanReason.CONTACT_LEDGER_STALE
        AndroidObservationCommitResult.StaleProviderEpoch ->
            RoomAndroidUnifiedCommitReplanReason.CONTACT_PROVIDER_EPOCH_STALE
        AndroidObservationCommitResult.CanonicalStale ->
            RoomAndroidUnifiedCommitReplanReason.CONTACT_CANONICAL_STALE
        is AndroidObservationCommitResult.Applied,
        AndroidObservationCommitResult.CanonicalRejected,
        -> error("CONTACT_REPLAN_REASON_INVALID")
    }

    private fun abort(result: RoomAndroidUnifiedObservationResult): Nothing =
        throw UnifiedObservationAbort(result)

    private fun repair(reason: RoomAndroidUnifiedCommitRepairReason): Nothing {
        runCatching { repairObserver.onRepairRequired(reason) }
        abort(RoomAndroidUnifiedObservationResult.RepairRequired)
    }

    private fun replan(reason: RoomAndroidUnifiedCommitReplanReason): Nothing {
        notifyReplan(reason)
        abort(RoomAndroidUnifiedObservationResult.ReplanRequired)
    }

    private fun notifyReplan(reason: RoomAndroidUnifiedCommitReplanReason) {
        runCatching { replanObserver.onReplanRequired(reason) }
    }

    private fun abortCreated(result: RoomAndroidCreatedUnifiedObservationResult): Nothing =
        throw CreatedUnifiedObservationAbort(result)

    private fun abortDeleted(result: RoomAndroidDeletedUnifiedObservationResult): Nothing =
        throw DeletedUnifiedObservationAbort(result)
}

private class UnifiedObservationAbort(
    val result: RoomAndroidUnifiedObservationResult,
) : RuntimeException(null, null, false, false)

private class CreatedUnifiedObservationAbort(
    val result: RoomAndroidCreatedUnifiedObservationResult,
) : RuntimeException(null, null, false, false)

private class DeletedUnifiedObservationAbort(
    val result: RoomAndroidDeletedUnifiedObservationResult,
) : RuntimeException(null, null, false, false)

private fun AndroidProjectionLedgerEntity.toSnapshot() = AndroidProjectionLedgerSnapshot(
    canonicalContactId, revision, providerEpoch,
    rawContactLocator?.let { AndroidRawContactLocator(providerEpoch, it) }, sourceIdentity != null,
    canonicalProjectionFingerprint?.let { com.patmanak.contako.data.android.AndroidProjectionFingerprint(it) },
    androidBaselineFingerprint?.let { com.patmanak.contako.data.android.AndroidProjectionFingerprint(it) },
    pendingProjectionFingerprint?.let { com.patmanak.contako.data.android.AndroidProjectionFingerprint(it) },
    com.patmanak.contako.data.android.AndroidProjectionWriteState.valueOf(projectionState),
    com.patmanak.contako.data.android.AndroidIngestionState.valueOf(ingestionState),
    com.patmanak.contako.data.android.AndroidTombstoneState.valueOf(tombstoneState),
    com.patmanak.contako.data.android.AndroidAdoptionState.valueOf(adoptionState),
)
