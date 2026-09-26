package com.patmanak.contako.data.local

import android.database.sqlite.SQLiteBlobTooBigException
import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.patmanak.contako.data.android.AndroidAdoptionState
import com.patmanak.contako.data.android.AndroidProjectionWriteState
import com.patmanak.contako.data.android.AndroidRawContactLocator
import com.patmanak.contako.data.android.AndroidTombstoneState
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipSnapshot
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipSnapshotBinaryCodec
import com.patmanak.contako.data.android.mapping.AndroidGroupProjectionPolicy
import com.patmanak.contako.data.android.mapping.AndroidGroupSnapshotBinaryCodec
import com.patmanak.contako.data.android.mapping.AndroidGroupSnapshotCodecException
import com.patmanak.contako.data.android.mapping.AndroidGroupSnapshotContextException
import com.patmanak.contako.data.gateway.AccountScope
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal data class RoomExpectedAndroidGroupBinding(
    val canonicalGroupId: String,
    val expectedLedgerRevision: Long,
    val groupRowLocator: Long,
    val expectedProviderVersion: Long,
    val sourceIdentity: String?,
) {
    init {
        require(canonicalGroupId.isNotBlank())
        require(expectedLedgerRevision >= 0)
        require(groupRowLocator > 0)
        require(expectedProviderVersion >= 0)
        require(sourceIdentity == null || sourceIdentity.isNotBlank())
    }

    override fun toString(): String =
        "RoomExpectedAndroidGroupBinding(REDACTED, revision=$expectedLedgerRevision)"
}

internal data class RoomAndroidGroupMembershipObservationCommand(
    val account: AccountScope,
    val expectedAccountRevision: Long,
    val locator: AndroidRawContactLocator,
    val canonicalContactId: String,
    val expectedCanonicalContactRevision: Long,
    val expectedContactLedgerRevision: Long,
    val expectedMembershipLedgerRevision: Long,
    val expectedCanonicalGroupStates: List<RoomExpectedContactGroupState>,
    val expectedAndroidGroupBindings: List<RoomExpectedAndroidGroupBinding>,
    val observedSnapshot: AndroidGroupMembershipSnapshot,
    val expectedContactAdoptionState: AndroidAdoptionState = AndroidAdoptionState.ADOPTED,
    val allowInitialCanonicalDelta: Boolean = false,
) {
    init {
        require(expectedAccountRevision >= 0)
        require(canonicalContactId.isNotBlank())
        require(expectedCanonicalContactRevision >= 0)
        require(expectedContactLedgerRevision >= 0)
        require(expectedMembershipLedgerRevision >= 0)
        require(expectedAccountRevision < Long.MAX_VALUE)
        require(expectedMembershipLedgerRevision < Long.MAX_VALUE)
        require(expectedCanonicalGroupStates.size <= MAX_GROUPS)
        require(expectedAndroidGroupBindings.size <= MAX_GROUPS)
        require(expectedCanonicalGroupStates.map(RoomExpectedContactGroupState::groupId).distinct().size ==
            expectedCanonicalGroupStates.size)
        require(expectedAndroidGroupBindings.map(RoomExpectedAndroidGroupBinding::canonicalGroupId).distinct().size ==
            expectedAndroidGroupBindings.size)
        require(expectedAndroidGroupBindings.map(RoomExpectedAndroidGroupBinding::groupRowLocator).distinct().size ==
            expectedAndroidGroupBindings.size)
    }

    override fun toString(): String =
        "RoomAndroidGroupMembershipObservationCommand(REDACTED, " +
            "groupCount=${expectedCanonicalGroupStates.size})"

    private companion object {
        const val MAX_GROUPS = 512
    }
}

internal enum class RoomAndroidGroupMembershipObservationClassification {
    BASELINED,
    NO_CHANGE,
    SELF_WRITE_RECONCILED,
    CANONICAL_ALREADY_CONVERGED,
    CANONICAL_DELTA_COMMITTED,
}

internal sealed interface RoomAndroidGroupMembershipObservationResult {
    data class Applied(
        val classification: RoomAndroidGroupMembershipObservationClassification,
        val changedGroupCount: Int,
        val committedAccountRevision: Long,
        val committedMembershipLedgerRevision: Long,
    ) : RoomAndroidGroupMembershipObservationResult {
        init {
            require(changedGroupCount >= 0)
            require(committedAccountRevision >= 0)
            require(committedMembershipLedgerRevision >= 0)
        }
    }

    data class AlreadyCommitted(
        val committedAccountRevision: Long,
        val committedMembershipLedgerRevision: Long,
    ) : RoomAndroidGroupMembershipObservationResult

    data object StaleAccount : RoomAndroidGroupMembershipObservationResult
    data object StaleContactLedger : RoomAndroidGroupMembershipObservationResult
    data object StaleMembershipLedger : RoomAndroidGroupMembershipObservationResult
    data object StaleGroupLedger : RoomAndroidGroupMembershipObservationResult
    data object StaleCanonicalContext : RoomAndroidGroupMembershipObservationResult
    data object StaleGroupContext : RoomAndroidGroupMembershipObservationResult
    data object RepairRequired : RoomAndroidGroupMembershipObservationResult
}

/** Payload-free reasons for the closed membership-ledger stale result. */
internal enum class RoomAndroidMembershipLedgerStaleReason {
    MISSING,
    REVISION,
    PROVIDER_EPOCH,
    RAW_CONTACT_LOCATOR,
    PREFERRED_EMAIL_CONTEXT,
    COMPARE_AND_SET,
}

internal fun interface RoomAndroidMembershipLedgerStaleObserver {
    fun onStale(reason: RoomAndroidMembershipLedgerStaleReason)
}

internal enum class RoomAndroidGroupMembershipCommitCheckpoint {
    AFTER_ACCOUNT_CAS,
    AFTER_CANONICAL_GROUPS,
    AFTER_MEMBERSHIP_LEDGER_CAS,
    AFTER_MEMBERSHIP_LEDGER_UPDATE,
    AFTER_MEMBERSHIP_BASELINE,
    AFTER_COMMIT_RECEIPT,
}

internal fun interface RoomAndroidGroupMembershipCommitCheckpointHook {
    fun onCheckpoint(checkpoint: RoomAndroidGroupMembershipCommitCheckpoint)

    companion object {
        val NONE = RoomAndroidGroupMembershipCommitCheckpointHook { }
    }
}

/**
 * Commits one complete preferred-email membership observation as one Room transaction.
 * Provider reads/writes and network work are deliberately outside this boundary.
 */
internal class RoomAndroidGroupMembershipObservationCommitter(
    private val database: ContakoDatabase,
    private val repository: RoomContactRepository,
    private val checkpointHook: RoomAndroidGroupMembershipCommitCheckpointHook =
        RoomAndroidGroupMembershipCommitCheckpointHook.NONE,
    private val membershipLedgerStaleObserver: RoomAndroidMembershipLedgerStaleObserver =
        RoomAndroidMembershipLedgerStaleObserver { },
) {
    init {
        require(repository.isBackedBy(database))
    }

    suspend fun commit(
        unfrozenCommand: RoomAndroidGroupMembershipObservationCommand,
    ): RoomAndroidGroupMembershipObservationResult = commit(unfrozenCommand, acquireAccountLock = true)

    /** Used only by a coordinator that already owns the shared account mutation lock. */
    internal suspend fun commitWithAccountLockHeld(
        unfrozenCommand: RoomAndroidGroupMembershipObservationCommand,
    ): RoomAndroidGroupMembershipObservationResult = commit(unfrozenCommand, acquireAccountLock = false)

    private suspend fun commit(
        unfrozenCommand: RoomAndroidGroupMembershipObservationCommand,
        acquireAccountLock: Boolean,
    ): RoomAndroidGroupMembershipObservationResult {
        val copiedVectors = unfrozenCommand.copy(
            expectedCanonicalGroupStates = unfrozenCommand.expectedCanonicalGroupStates.map { it.copy() },
            expectedAndroidGroupBindings = unfrozenCommand.expectedAndroidGroupBindings.map { it.copy() },
        )
        val encodedBaseline = AndroidGroupMembershipSnapshotBinaryCodec.encode(copiedVectors.observedSnapshot)
        val command = copiedVectors.copy(
            observedSnapshot = AndroidGroupMembershipSnapshotBinaryCodec.decode(encodedBaseline),
        )
        val integrityFingerprint = AndroidGroupMembershipSnapshotBinaryCodec.integrityFingerprint(encodedBaseline)
        val observedSemanticFingerprint = command.observedSnapshot.semanticFingerprint()
        val commandFingerprint = commandFingerprint(command, encodedBaseline)
        val expectedReceipt = AndroidGroupMembershipCommitReceiptEntity(
            accountId = command.account.value,
            canonicalContactId = command.canonicalContactId,
            providerEpoch = command.locator.providerEpoch,
            expectedAccountRevision = command.expectedAccountRevision,
            expectedMembershipLedgerRevision = command.expectedMembershipLedgerRevision,
            committedAccountRevision = Math.incrementExact(command.expectedAccountRevision),
            committedMembershipLedgerRevision = Math.incrementExact(command.expectedMembershipLedgerRevision),
            commandFingerprint = commandFingerprint,
        )
        val operation: suspend () -> RoomAndroidGroupMembershipObservationResult = {
            try {
            database.withTransaction {
                val accountEntity = database.androidProjectionLedgerDao().getAccount(command.account.value)
                    ?: return@withTransaction RoomAndroidGroupMembershipObservationResult.StaleAccount
                if (accountEntity.providerEpoch == command.locator.providerEpoch &&
                    accountEntity.revision == Math.incrementExact(command.expectedAccountRevision)
                ) {
                    val receipt = try {
                        database.androidGroupProjectionDao().getMembershipCommitReceipt(
                            command.account.value,
                            command.canonicalContactId,
                        )
                    } catch (_: IllegalArgumentException) {
                        repair()
                    } catch (_: ArithmeticException) {
                        repair()
                    } catch (_: SQLiteException) {
                        repair()
                    }
                    if (receipt == expectedReceipt &&
                        isExactCommittedPostState(
                            command,
                            encodedBaseline,
                            integrityFingerprint.sha256Hex,
                            observedSemanticFingerprint.sha256Hex,
                        )
                    ) {
                        return@withTransaction RoomAndroidGroupMembershipObservationResult.AlreadyCommitted(
                            committedAccountRevision = accountEntity.revision,
                            committedMembershipLedgerRevision =
                                Math.incrementExact(command.expectedMembershipLedgerRevision),
                        )
                    }
                    return@withTransaction RoomAndroidGroupMembershipObservationResult.StaleAccount
                }
                if (accountEntity.revision != command.expectedAccountRevision ||
                    accountEntity.providerEpoch != command.locator.providerEpoch
                ) {
                    return@withTransaction RoomAndroidGroupMembershipObservationResult.StaleAccount
                }

                val contactLedger = database.androidProjectionLedgerDao().get(
                    command.account.value,
                    command.canonicalContactId,
                ) ?: return@withTransaction RoomAndroidGroupMembershipObservationResult.StaleContactLedger
                if (contactLedger.revision != command.expectedContactLedgerRevision ||
                    contactLedger.providerEpoch != command.locator.providerEpoch ||
                    contactLedger.rawContactLocator != command.locator.localRowHandle ||
                    contactLedger.tombstoneState != AndroidTombstoneState.NONE.name ||
                    contactLedger.adoptionState != command.expectedContactAdoptionState.name
                ) {
                    return@withTransaction RoomAndroidGroupMembershipObservationResult.StaleContactLedger
                }

                val canonicalContact = database.contactDao().get(
                    command.account.value,
                    command.canonicalContactId,
                )?.toDomain() ?: return@withTransaction RoomAndroidGroupMembershipObservationResult.StaleCanonicalContext
                if (canonicalContact.revision != command.expectedCanonicalContactRevision || canonicalContact.isDeleted) {
                    return@withTransaction RoomAndroidGroupMembershipObservationResult.StaleCanonicalContext
                }
                try {
                    command.observedSnapshot.requireCurrentCanonicalContext(canonicalContact)
                } catch (_: AndroidGroupSnapshotContextException) {
                    return@withTransaction RoomAndroidGroupMembershipObservationResult.StaleCanonicalContext
                }

                val groupDao = database.androidGroupProjectionDao()
                val membershipLedger = groupDao.getMembership(
                    command.account.value,
                    command.canonicalContactId,
                ) ?: return@withTransaction staleMembershipLedger(
                    RoomAndroidMembershipLedgerStaleReason.MISSING,
                )
                if (membershipLedger.revision != command.expectedMembershipLedgerRevision) {
                    return@withTransaction staleMembershipLedger(
                        RoomAndroidMembershipLedgerStaleReason.REVISION,
                    )
                }
                if (membershipLedger.providerEpoch != command.locator.providerEpoch) {
                    return@withTransaction staleMembershipLedger(
                        RoomAndroidMembershipLedgerStaleReason.PROVIDER_EPOCH,
                    )
                }
                if (membershipLedger.rawContactLocator != command.locator.localRowHandle) {
                    return@withTransaction staleMembershipLedger(
                        RoomAndroidMembershipLedgerStaleReason.RAW_CONTACT_LOCATOR,
                    )
                }
                if (membershipLedger.preferredEmailValueId != command.observedSnapshot.preferredEmailValueId) {
                    return@withTransaction staleMembershipLedger(
                        RoomAndroidMembershipLedgerStaleReason.PREFERRED_EMAIL_CONTEXT,
                    )
                }

                val previousBaseline = validatePreviousBaseline(command, membershipLedger)
                    ?: if (membershipLedger.androidBaselineFingerprint == null) {
                        null
                    } else {
                        return@withTransaction RoomAndroidGroupMembershipObservationResult.RepairRequired
                    }
                val requiredBindingIds = previousBaseline?.canonicalGroupIds.orEmpty().toSet() +
                    command.observedSnapshot.canonicalGroupIds
                if (command.expectedAndroidGroupBindings.mapTo(mutableSetOf()) { it.canonicalGroupId } !=
                    requiredBindingIds
                ) {
                    return@withTransaction RoomAndroidGroupMembershipObservationResult.StaleGroupLedger
                }
                if (!hasExactGroupLedgers(command, previousBaseline)) {
                    return@withTransaction RoomAndroidGroupMembershipObservationResult.StaleGroupLedger
                }

                val canonicalGroups = database.contactGroupDao().getAll(command.account.value)
                    .map(ContactGroupWithMemberships::toDomain)
                val canonicalProjection = AndroidGroupProjectionPolicy().projectMemberships(
                    canonicalContact,
                    canonicalGroups,
                )
                val canonicalMatchesObservation =
                    canonicalProjection.preferredEmailValueId == command.observedSnapshot.preferredEmailValueId &&
                        canonicalProjection.availability == command.observedSnapshot.membershipAvailability &&
                        canonicalProjection.canonicalGroupIds == command.observedSnapshot.canonicalGroupIds.toSet()
                val classification = when {
                    membershipLedger.pendingProjectionFingerprint == observedSemanticFingerprint.sha256Hex -> {
                        if (!canonicalMatchesObservation) {
                            return@withTransaction RoomAndroidGroupMembershipObservationResult.RepairRequired
                        }
                        RoomAndroidGroupMembershipObservationClassification.SELF_WRITE_RECONCILED
                    }
                    membershipLedger.androidBaselineFingerprint == null -> {
                        if (!canonicalMatchesObservation && !command.allowInitialCanonicalDelta) {
                            return@withTransaction RoomAndroidGroupMembershipObservationResult.RepairRequired
                        }
                        if (canonicalMatchesObservation) {
                            RoomAndroidGroupMembershipObservationClassification.BASELINED
                        } else {
                            RoomAndroidGroupMembershipObservationClassification.CANONICAL_DELTA_COMMITTED
                        }
                    }
                    membershipLedger.androidBaselineFingerprint == observedSemanticFingerprint.sha256Hex -> {
                        if (!canonicalMatchesObservation) {
                            return@withTransaction RoomAndroidGroupMembershipObservationResult.RepairRequired
                        }
                        RoomAndroidGroupMembershipObservationClassification.NO_CHANGE
                    }
                    canonicalMatchesObservation ->
                        RoomAndroidGroupMembershipObservationClassification.CANONICAL_ALREADY_CONVERGED
                    else -> RoomAndroidGroupMembershipObservationClassification.CANONICAL_DELTA_COMMITTED
                }

                if (database.androidProjectionLedgerDao().compareAndSetAccountRevisionAtProviderEpoch(
                        command.account.value,
                        command.expectedAccountRevision,
                        command.locator.providerEpoch,
                    ) != 1
                ) {
                    return@withTransaction RoomAndroidGroupMembershipObservationResult.StaleAccount
                }
                checkpoint(RoomAndroidGroupMembershipCommitCheckpoint.AFTER_ACCOUNT_CAS)

                val canonicalResult = repository.applyObservedGroupMembershipsInCurrentTransaction(
                    accountId = command.account.value,
                    contactId = command.canonicalContactId,
                    expectedCanonicalRevision = command.expectedCanonicalContactRevision,
                    expectedPreferredEmailValueId = command.observedSnapshot.preferredEmailValueId,
                    expectedGroupStates = command.expectedCanonicalGroupStates,
                    observedCanonicalGroupIds = command.observedSnapshot.canonicalGroupIds.toSet(),
                )
                val changedGroupCount = when (canonicalResult) {
                    is RoomObservedGroupMembershipMutationResult.Applied -> canonicalResult.changedGroupIds.size
                    RoomObservedGroupMembershipMutationResult.NoChange -> 0
                    RoomObservedGroupMembershipMutationResult.StaleContact ->
                        abort(RoomAndroidGroupMembershipObservationResult.StaleCanonicalContext)
                    RoomObservedGroupMembershipMutationResult.StaleGroupContext,
                    RoomObservedGroupMembershipMutationResult.DeleteIntentConflict,
                    -> abort(RoomAndroidGroupMembershipObservationResult.StaleGroupContext)
                }
                if (classification !=
                    RoomAndroidGroupMembershipObservationClassification.CANONICAL_DELTA_COMMITTED &&
                    changedGroupCount != 0
                ) {
                    abort(RoomAndroidGroupMembershipObservationResult.RepairRequired)
                }
                checkpoint(RoomAndroidGroupMembershipCommitCheckpoint.AFTER_CANONICAL_GROUPS)

                if (groupDao.compareAndSetMembershipRevision(
                        command.account.value,
                        command.canonicalContactId,
                        command.expectedMembershipLedgerRevision,
                    ) != 1
                ) {
                    abort(staleMembershipLedger(RoomAndroidMembershipLedgerStaleReason.COMPARE_AND_SET))
                }
                checkpoint(RoomAndroidGroupMembershipCommitCheckpoint.AFTER_MEMBERSHIP_LEDGER_CAS)
                val updatedLedger = membershipLedger.copy(
                    revision = Math.incrementExact(command.expectedMembershipLedgerRevision),
                    rawContactLocator = command.locator.localRowHandle,
                    preferredEmailValueId = command.observedSnapshot.preferredEmailValueId,
                    canonicalProjectionFingerprint = observedSemanticFingerprint.sha256Hex,
                    androidBaselineFingerprint = observedSemanticFingerprint.sha256Hex,
                    pendingProjectionFingerprint = null,
                    projectionState = AndroidProjectionWriteState.CLEAN.name,
                    ingestionState = if (classification ==
                        RoomAndroidGroupMembershipObservationClassification.CANONICAL_DELTA_COMMITTED
                    ) {
                        "CANONICAL_DELTA_COMMITTED"
                    } else {
                        "BASELINED"
                    },
                )
                check(groupDao.updateMembership(updatedLedger) == 1)
                checkpoint(RoomAndroidGroupMembershipCommitCheckpoint.AFTER_MEMBERSHIP_LEDGER_UPDATE)
                groupDao.upsertMembershipBaseline(
                    AndroidGroupMembershipBaselineEntity(
                        accountId = command.account.value,
                        canonicalContactId = command.canonicalContactId,
                        fingerprint = integrityFingerprint.sha256Hex,
                        encodedSnapshot = encodedBaseline,
                    ),
                )
                checkpoint(RoomAndroidGroupMembershipCommitCheckpoint.AFTER_MEMBERSHIP_BASELINE)
                groupDao.upsertMembershipCommitReceipt(expectedReceipt)
                checkpoint(RoomAndroidGroupMembershipCommitCheckpoint.AFTER_COMMIT_RECEIPT)
                RoomAndroidGroupMembershipObservationResult.Applied(
                    classification = classification,
                    changedGroupCount = changedGroupCount,
                    committedAccountRevision = Math.incrementExact(command.expectedAccountRevision),
                    committedMembershipLedgerRevision =
                        Math.incrementExact(command.expectedMembershipLedgerRevision),
                )
            }
            } catch (abort: MembershipCommitAbort) {
                abort.result
            }
        }
        return if (acquireAccountLock) {
            AndroidProviderAccountMutationLocks.withAccountLock(command.account.value) { operation() }
        } else {
            operation()
        }
    }

    private suspend fun validatePreviousBaseline(
        command: RoomAndroidGroupMembershipObservationCommand,
        ledger: AndroidGroupMembershipProjectionLedgerEntity,
    ): AndroidGroupMembershipSnapshot? {
        val entity = try {
            database.androidGroupProjectionDao().getMembershipBaseline(
                command.account.value,
                command.canonicalContactId,
            )
        } catch (_: SQLiteBlobTooBigException) {
            repair()
        }
        if (ledger.androidBaselineFingerprint == null) return entity?.let { repair() }
        entity ?: return null
        val integrity = try {
            AndroidGroupMembershipSnapshotBinaryCodec.integrityFingerprint(entity.encodedSnapshot)
        } catch (_: AndroidGroupSnapshotCodecException) {
            repair()
        }
        if (integrity.sha256Hex != entity.fingerprint) repair()
        val decoded = try {
            AndroidGroupMembershipSnapshotBinaryCodec.decode(entity.encodedSnapshot)
        } catch (_: AndroidGroupSnapshotCodecException) {
            repair()
        }
        if (decoded.accountId != command.account.value ||
            decoded.canonicalContactId != command.canonicalContactId ||
            decoded.semanticFingerprint().sha256Hex != ledger.androidBaselineFingerprint
        ) {
            repair()
        }
        return decoded
    }

    private suspend fun hasExactGroupLedgers(
        command: RoomAndroidGroupMembershipObservationCommand,
        previousBaseline: AndroidGroupMembershipSnapshot?,
    ): Boolean {
        val previousLocatorById = previousBaseline?.locatorMappings.orEmpty()
            .associate { it.canonicalGroupId to it.groupRowLocator }
        val observedLocatorById = command.observedSnapshot.locatorMappings
            .associate { it.canonicalGroupId to it.groupRowLocator }
        return command.expectedAndroidGroupBindings.all { expected ->
            val requiredLocator = observedLocatorById[expected.canonicalGroupId]
                ?: previousLocatorById[expected.canonicalGroupId]
                ?: return@all false
            if (requiredLocator != expected.groupRowLocator) return@all false
            val current = database.androidGroupProjectionDao().getGroup(
                command.account.value,
                expected.canonicalGroupId,
            ) ?: return@all false
            if (current.revision != expected.expectedLedgerRevision) {
                return@all false
            }
            val previousOnlyDeleted = observedLocatorById[expected.canonicalGroupId] == null &&
                previousLocatorById[expected.canonicalGroupId] != null &&
                isExactDeletedPreviousOnlyGroup(command, current, expected)
            if (previousOnlyDeleted) return@all true
            if (current.providerEpoch != command.locator.providerEpoch ||
                current.groupRowLocator != expected.groupRowLocator ||
                current.providerVersion != expected.expectedProviderVersion ||
                current.sourceIdentity != expected.sourceIdentity ||
                current.tombstoneState != AndroidTombstoneState.NONE.name ||
                current.adoptionState != AndroidAdoptionState.ADOPTED.name
            ) {
                return@all false
            }
            if (current.projectionState != AndroidProjectionWriteState.CLEAN.name) repair()
            validateGroupBaseline(command, current)
            true
        }
    }

    private suspend fun validateGroupBaseline(
        command: RoomAndroidGroupMembershipObservationCommand,
        ledger: AndroidGroupProjectionLedgerEntity,
    ) {
        val entity = try {
            database.androidGroupProjectionDao().getGroupBaseline(
                command.account.value,
                ledger.canonicalGroupId,
            )
        } catch (_: SQLiteBlobTooBigException) {
            repair()
        }
        if (ledger.androidBaselineFingerprint == null) {
            if (entity != null) repair()
            repair()
        }
        entity ?: repair()
        val integrity = try {
            AndroidGroupSnapshotBinaryCodec.integrityFingerprint(entity.encodedSnapshot)
        } catch (_: AndroidGroupSnapshotCodecException) {
            repair()
        }
        if (integrity.sha256Hex != entity.fingerprint) repair()
        val decoded = try {
            AndroidGroupSnapshotBinaryCodec.decode(entity.encodedSnapshot)
        } catch (_: AndroidGroupSnapshotCodecException) {
            repair()
        }
        if (decoded.accountId != command.account.value ||
            decoded.canonicalGroupId != ledger.canonicalGroupId ||
            decoded.semanticFingerprint().sha256Hex != ledger.androidBaselineFingerprint
        ) {
            repair()
        }
    }

    private suspend fun isExactCommittedPostState(
        command: RoomAndroidGroupMembershipObservationCommand,
        expectedEncodedBaseline: ByteArray,
        expectedIntegrityFingerprint: String,
        expectedSemanticFingerprint: String,
    ): Boolean {
        val contactLedger = database.androidProjectionLedgerDao().get(
            command.account.value,
            command.canonicalContactId,
        ) ?: return false
        if (contactLedger.revision != command.expectedContactLedgerRevision ||
            contactLedger.providerEpoch != command.locator.providerEpoch ||
            contactLedger.rawContactLocator != command.locator.localRowHandle ||
            contactLedger.tombstoneState != AndroidTombstoneState.NONE.name ||
            contactLedger.adoptionState != command.expectedContactAdoptionState.name
        ) {
            return false
        }
        val membership = database.androidGroupProjectionDao().getMembership(
            command.account.value,
            command.canonicalContactId,
        ) ?: return false
        if (membership.revision != Math.incrementExact(command.expectedMembershipLedgerRevision) ||
            membership.providerEpoch != command.locator.providerEpoch ||
            membership.rawContactLocator != command.locator.localRowHandle ||
            membership.preferredEmailValueId != command.observedSnapshot.preferredEmailValueId ||
            membership.canonicalProjectionFingerprint != expectedSemanticFingerprint ||
            membership.androidBaselineFingerprint != expectedSemanticFingerprint ||
            membership.pendingProjectionFingerprint != null ||
            membership.projectionState != AndroidProjectionWriteState.CLEAN.name
        ) {
            return false
        }
        val baseline = try {
            database.androidGroupProjectionDao().getMembershipBaseline(
                command.account.value,
                command.canonicalContactId,
            )
        } catch (_: SQLiteBlobTooBigException) {
            repair()
        } ?: return false
        if (baseline.fingerprint != expectedIntegrityFingerprint ||
            !baseline.encodedSnapshot.contentEquals(expectedEncodedBaseline)
        ) {
            return false
        }
        val contact = database.contactDao().get(command.account.value, command.canonicalContactId)
            ?.toDomain() ?: return false
        if (contact.revision != command.expectedCanonicalContactRevision || contact.isDeleted) return false
        try {
            command.observedSnapshot.requireCurrentCanonicalContext(contact)
        } catch (_: AndroidGroupSnapshotContextException) {
            return false
        }
        val groups = database.contactGroupDao().getAll(command.account.value)
        val expectedStatesById = command.expectedCanonicalGroupStates.associateBy(RoomExpectedContactGroupState::groupId)
        if (groups.map { it.group.id }.toSet() != expectedStatesById.keys) return false
        groups.forEach { stored ->
            val expected = expectedStatesById.getValue(stored.group.id)
            if (stored.group.isDeleted != expected.isDeleted ||
                stored.group.revision !in expected.revision..Math.incrementExact(expected.revision)
            ) {
                return false
            }
            if (stored.group.revision == Math.incrementExact(expected.revision)) {
                val outbox = database.outboxDao().get(
                    command.account.value,
                    AggregateType.GROUP.name,
                    stored.group.id,
                ) ?: return false
                if (outbox.revision != stored.group.revision ||
                    outbox.operation !in setOf(MutationOperation.UPSERT.name, MutationOperation.ASSIGNMENTS.name) ||
                    (stored.group.remoteLabelId == null &&
                        outbox.operation != MutationOperation.UPSERT.name) ||
                    outbox.remoteIdentity != stored.group.remoteLabelId ||
                    outbox.remoteVersion != stored.group.remoteVersion ||
                    outbox.idempotencyKey !=
                    "${command.account.value}:${AggregateType.GROUP.name}:${stored.group.id}:" +
                    "${outbox.revision}:${outbox.operation}"
                ) {
                    return false
                }
            }
        }
        val projection = AndroidGroupProjectionPolicy().projectMemberships(
            contact,
            groups.map(ContactGroupWithMemberships::toDomain),
        )
        if (projection.preferredEmailValueId != command.observedSnapshot.preferredEmailValueId ||
            projection.availability != command.observedSnapshot.membershipAvailability ||
            projection.canonicalGroupIds != command.observedSnapshot.canonicalGroupIds.toSet()
        ) {
            return false
        }
        val observedLocatorById = command.observedSnapshot.locatorMappings
            .associate { it.canonicalGroupId to it.groupRowLocator }
        return command.expectedAndroidGroupBindings.all { binding ->
            val ledger = database.androidGroupProjectionDao().getGroup(
                command.account.value,
                binding.canonicalGroupId,
            ) ?: return@all false
            val previousOnlyDeleted = observedLocatorById[binding.canonicalGroupId] == null &&
                isExactDeletedPreviousOnlyGroup(command, ledger, binding)
            if (previousOnlyDeleted) return@all true
            if (ledger.revision != binding.expectedLedgerRevision ||
                ledger.providerEpoch != command.locator.providerEpoch ||
                ledger.groupRowLocator != binding.groupRowLocator ||
                ledger.sourceIdentity != binding.sourceIdentity ||
                ledger.projectionState != AndroidProjectionWriteState.CLEAN.name ||
                ledger.tombstoneState != AndroidTombstoneState.NONE.name ||
                ledger.adoptionState != AndroidAdoptionState.ADOPTED.name ||
                observedLocatorById[binding.canonicalGroupId]?.let { it != binding.groupRowLocator } == true
            ) {
                return@all false
            }
            validateGroupBaseline(command, ledger)
            true
        }
    }

    private suspend fun isExactDeletedPreviousOnlyGroup(
        command: RoomAndroidGroupMembershipObservationCommand,
        ledger: AndroidGroupProjectionLedgerEntity,
        binding: RoomExpectedAndroidGroupBinding,
    ): Boolean {
        val canonical = database.contactGroupDao().get(
            command.account.value,
            binding.canonicalGroupId,
        )?.group ?: return false
        return canonical.isDeleted &&
            ledger.revision == binding.expectedLedgerRevision &&
            ledger.providerEpoch == command.locator.providerEpoch &&
            ledger.groupRowLocator == binding.groupRowLocator &&
            ledger.sourceIdentity == binding.sourceIdentity &&
            ledger.tombstoneState == AndroidTombstoneState.CANONICAL_COMMITTED.name &&
            ledger.projectionState == AndroidProjectionWriteState.DETACHED.name &&
            ledger.adoptionState == AndroidAdoptionState.ADOPTED.name
    }

    private fun checkpoint(checkpoint: RoomAndroidGroupMembershipCommitCheckpoint) {
        checkpointHook.onCheckpoint(checkpoint)
    }

    private fun commandFingerprint(
        command: RoomAndroidGroupMembershipObservationCommand,
        encodedBaseline: ByteArray,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.putString(COMMAND_FINGERPRINT_DOMAIN)
        digest.putString(command.account.value)
        digest.putLong(command.expectedAccountRevision)
        digest.putLong(command.locator.providerEpoch)
        digest.putLong(command.locator.localRowHandle)
        digest.putString(command.canonicalContactId)
        digest.putLong(command.expectedCanonicalContactRevision)
        digest.putLong(command.expectedContactLedgerRevision)
        digest.putLong(command.expectedMembershipLedgerRevision)
        digest.putBytes(encodedBaseline)
        val groupStates = command.expectedCanonicalGroupStates.sortedBy { it.groupId }
        digest.putInt(groupStates.size)
        groupStates.forEach { state ->
            digest.putString(state.groupId)
            digest.putLong(state.revision)
            digest.putBoolean(state.isDeleted)
        }
        val bindings = command.expectedAndroidGroupBindings.sortedBy { it.canonicalGroupId }
        digest.putInt(bindings.size)
        bindings.forEach { binding ->
            digest.putString(binding.canonicalGroupId)
            digest.putLong(binding.expectedLedgerRevision)
            digest.putLong(binding.groupRowLocator)
            digest.putNullableString(binding.sourceIdentity)
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun repair(): Nothing = abort(RoomAndroidGroupMembershipObservationResult.RepairRequired)

    private fun staleMembershipLedger(
        reason: RoomAndroidMembershipLedgerStaleReason,
    ): RoomAndroidGroupMembershipObservationResult.StaleMembershipLedger {
        runCatching { membershipLedgerStaleObserver.onStale(reason) }
        return RoomAndroidGroupMembershipObservationResult.StaleMembershipLedger
    }

    private fun abort(result: RoomAndroidGroupMembershipObservationResult): Nothing =
        throw MembershipCommitAbort(result)

    private companion object {
        const val COMMAND_FINGERPRINT_DOMAIN = "contako/android-group-membership-command/v1"
    }
}

private fun MessageDigest.putInt(value: Int) {
    update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value).array())
}

private fun MessageDigest.putLong(value: Long) {
    update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value).array())
}

private fun MessageDigest.putBoolean(value: Boolean) {
    update(if (value) 1 else 0)
}

private fun MessageDigest.putString(value: String) {
    putBytes(value.toByteArray(StandardCharsets.UTF_8))
}

private fun MessageDigest.putNullableString(value: String?) {
    if (value == null) {
        putInt(-1)
    } else {
        putString(value)
    }
}

private fun MessageDigest.putBytes(value: ByteArray) {
    putInt(value.size)
    update(value)
}

private class MembershipCommitAbort(
    val result: RoomAndroidGroupMembershipObservationResult,
) : IllegalStateException("Android group membership commit aborted")
