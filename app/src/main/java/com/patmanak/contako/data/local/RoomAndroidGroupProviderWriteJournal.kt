package com.patmanak.contako.data.local

import androidx.room.withTransaction
import com.patmanak.contako.data.android.AndroidAdoptionState
import com.patmanak.contako.data.android.AndroidIngestionState
import com.patmanak.contako.data.android.AndroidProjectionWriteState
import com.patmanak.contako.data.android.AndroidTombstoneState
import com.patmanak.contako.data.android.mapping.AndroidGroupSnapshot
import com.patmanak.contako.data.android.mapping.AndroidGroupSnapshotBinaryCodec
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipSnapshotBinaryCodec
import com.patmanak.contako.data.android.mapping.AndroidGroupSnapshotCodecException
import com.patmanak.contako.data.android.provider.AndroidExpectedSourceIdentity
import com.patmanak.contako.data.android.provider.AndroidGroupProviderOperation
import com.patmanak.contako.data.android.provider.AndroidGroupWriteAuthorization
import com.patmanak.contako.data.android.provider.AndroidGroupWriteAuthorizer
import com.patmanak.contako.data.android.provider.AndroidOwnedGroupHandle
import com.patmanak.contako.data.android.provider.AndroidVerifiedGroupProviderPostState
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal sealed interface AndroidGroupProviderWritePreparation {
    data class Prepared(val commandFingerprint: String) : AndroidGroupProviderWritePreparation
    data class AlreadyPrepared(val commandFingerprint: String) : AndroidGroupProviderWritePreparation
    data class AlreadyCommitted(
        val commandFingerprint: String,
        val outcome: AndroidGroupProviderWriteOutcome,
    ) : AndroidGroupProviderWritePreparation
    data object Stale : AndroidGroupProviderWritePreparation
    data object Busy : AndroidGroupProviderWritePreparation
}

internal sealed interface AndroidGroupProviderWriteOutcome {
    data class Present(val handle: AndroidOwnedGroupHandle) : AndroidGroupProviderWriteOutcome
    data object Deleted : AndroidGroupProviderWriteOutcome
}

internal sealed interface AndroidGroupProviderWriteCommitResult {
    data object Committed : AndroidGroupProviderWriteCommitResult
    data object AlreadyCommitted : AndroidGroupProviderWriteCommitResult
    data object Stale : AndroidGroupProviderWriteCommitResult
}

internal sealed interface AndroidGroupProviderWriteCompletionResult {
    data class Completed(val committedGroupLedgerRevision: Long) : AndroidGroupProviderWriteCompletionResult
    data class AlreadyCompleted(val committedGroupLedgerRevision: Long) : AndroidGroupProviderWriteCompletionResult
    data object Stale : AndroidGroupProviderWriteCompletionResult
}

/**
 * Durable PREPARED -> provider -> COMMITTED -> ledger boundary for Android Groups writes.
 *
 * A caller MUST prepare first, pass [authorizer] to the provider gateway, persist the exact
 * provider outcome with [markProviderCommitted], and finally call [completeCommitted]. A restart
 * can replay PREPARED idempotently or complete COMMITTED without touching ContactsProvider again.
 */
internal class RoomAndroidGroupProviderWriteJournal(
    private val database: ContakoDatabase,
) {
    private val dao get() = database.androidGroupProjectionDao()

    val authorizer: AndroidGroupWriteAuthorizer = AndroidGroupWriteAuthorizer(::isCurrent)

    /** Defers group removal until remote acknowledgement and live membership projection agree. */
    suspend fun prepareCanonicalDeletion(
        expected: AndroidGroupProjectionLedgerEntity,
        expectedCanonicalRevision: Long,
    ): AndroidGroupProjectionLedgerEntity? = AndroidProviderAccountMutationLocks.withAccountLock(expected.accountId) {
        database.withTransaction {
            val current = dao.getGroup(expected.accountId, expected.canonicalGroupId)
            if (current != expected) return@withTransaction null
            val canonical = database.contactGroupDao().get(expected.accountId, expected.canonicalGroupId)?.group
                ?: return@withTransaction null
            if (!canonical.isDeleted || canonical.revision != expectedCanonicalRevision ||
                canonical.remoteLabelId != expected.sourceIdentity || canonical.pendingMutationRevision != null ||
                canonical.conflictState != null || database.outboxDao().get(
                    expected.accountId, AggregateType.GROUP.name, expected.canonicalGroupId,
                ) != null || !hasConvergedMemberships(expected.accountId, expected.canonicalGroupId)
            ) return@withTransaction null
            if (expected.tombstoneState != AndroidTombstoneState.NONE.name) return@withTransaction expected
            expected.copy(
                revision = Math.incrementExact(expected.revision),
                projectionState = AndroidProjectionWriteState.DETACHED.name,
                tombstoneState = AndroidTombstoneState.CANONICAL_COMMITTED.name,
                pendingProjectionFingerprint = null,
            ).also { check(dao.updateGroup(it) == 1) }
        }
    }

    suspend fun prepare(
        authorization: AndroidGroupWriteAuthorization,
    ): AndroidGroupProviderWritePreparation {
        val candidate = authorization.toPreparedEntity()
        return database.withTransaction {
            val existing = dao.getGroupProviderWriteJournal(
                authorization.context.accountId,
                authorization.canonicalGroupId,
            )
            if (existing != null) {
                if (existing.commandFingerprint == candidate.commandFingerprint) {
                    return@withTransaction if (existing.state == STATE_PREPARED ||
                        existing.state == STATE_REPAIR_REQUIRED
                    ) {
                        if (hasCurrentDurableClaims(authorization)) {
                            if (existing.state == STATE_REPAIR_REQUIRED) {
                                dao.upsertGroupProviderWriteJournal(candidate)
                            }
                            AndroidGroupProviderWritePreparation.AlreadyPrepared(existing.commandFingerprint)
                        } else {
                            AndroidGroupProviderWritePreparation.Stale
                        }
                    } else {
                        AndroidGroupProviderWritePreparation.AlreadyCommitted(
                            existing.commandFingerprint,
                            existing.toOutcome(),
                        )
                    }
                }
                val replaceableCompleted = existing.state == STATE_COMMITTED &&
                    existing.completedAccountRevision != null &&
                    existing.completedGroupLedgerRevision != null
                // A different PREPARED command may already have changed ContactsProvider before
                // process death. It MUST retain its proof until a coordinator has classified that
                // exact old pre/post-state; only an explicitly repairable record is replaceable.
                val replaceableUncommitted = existing.state == STATE_REPAIR_REQUIRED
                if (!replaceableCompleted && !replaceableUncommitted) {
                    return@withTransaction AndroidGroupProviderWritePreparation.Busy
                }
            }
            if (!hasCurrentDurableClaims(authorization)) {
                return@withTransaction AndroidGroupProviderWritePreparation.Stale
            }
            dao.upsertGroupProviderWriteJournal(candidate)
            AndroidGroupProviderWritePreparation.Prepared(candidate.commandFingerprint)
        }
    }

    suspend fun markProviderCommitted(
        authorization: AndroidGroupWriteAuthorization,
        verifiedState: AndroidVerifiedGroupProviderPostState,
    ): AndroidGroupProviderWriteCommitResult = database.withTransaction {
        val outcome = verifiedState.toOutcome()
        val expected = authorization.toPreparedEntity()
        val current = dao.getGroupProviderWriteJournal(
            authorization.context.accountId,
            authorization.canonicalGroupId,
        ) ?: return@withTransaction AndroidGroupProviderWriteCommitResult.Stale
        if (current.commandFingerprint != expected.commandFingerprint) {
            return@withTransaction AndroidGroupProviderWriteCommitResult.Stale
        }
        if (current.state == STATE_COMMITTED) {
            return@withTransaction if (current.toOutcome() == outcome &&
                current.resultProviderStateFingerprint == verifiedState.providerStateFingerprint
            ) {
                AndroidGroupProviderWriteCommitResult.AlreadyCommitted
            } else {
                AndroidGroupProviderWriteCommitResult.Stale
            }
        }
        if (!hasCurrentDurableClaims(authorization) || !authorization.accepts(verifiedState)) {
            return@withTransaction AndroidGroupProviderWriteCommitResult.Stale
        }
        dao.upsertGroupProviderWriteJournal(current.withVerifiedState(verifiedState))
        AndroidGroupProviderWriteCommitResult.Committed
    }

    suspend fun markRepairRequired(authorization: AndroidGroupWriteAuthorization): Boolean =
        database.withTransaction {
            val expected = authorization.toPreparedEntity()
            val current = dao.getGroupProviderWriteJournal(
                authorization.context.accountId,
                authorization.canonicalGroupId,
            ) ?: return@withTransaction false
            if (current.commandFingerprint != expected.commandFingerprint) return@withTransaction false
            if (current.state == STATE_REPAIR_REQUIRED) return@withTransaction true
            if (current.state != STATE_PREPARED) return@withTransaction false
            dao.upsertGroupProviderWriteJournal(current.copy(state = STATE_REPAIR_REQUIRED))
            true
        }

    suspend fun completeCommitted(
        authorization: AndroidGroupWriteAuthorization,
    ): AndroidGroupProviderWriteCompletionResult = database.withTransaction {
        val expected = authorization.toPreparedEntity()
        val journal = dao.getGroupProviderWriteJournal(
            authorization.context.accountId,
            authorization.canonicalGroupId,
        ) ?: return@withTransaction AndroidGroupProviderWriteCompletionResult.Stale
        if (journal.state != STATE_COMMITTED || journal.commandFingerprint != expected.commandFingerprint) {
            return@withTransaction AndroidGroupProviderWriteCompletionResult.Stale
        }
        if (!hasCurrentDurableClaims(authorization)) {
            return@withTransaction if (isDurablyCompleted(journal)) {
                AndroidGroupProviderWriteCompletionResult.AlreadyCompleted(
                    Math.incrementExact(journal.expectedGroupLedgerRevision),
                )
            } else {
                AndroidGroupProviderWriteCompletionResult.Stale
            }
        }
        val account = requireNotNull(
            database.androidProjectionLedgerDao().getAccount(authorization.context.accountId),
        )
        val group = requireNotNull(
            dao.getGroup(authorization.context.accountId, authorization.canonicalGroupId),
        )
        val nextAccountRevision = Math.incrementExact(account.revision)
        val nextGroupRevision = Math.incrementExact(group.revision)
        if (database.androidProjectionLedgerDao().compareAndSetAccountRevisionAtProviderEpoch(
                account.accountId,
                account.revision,
                account.providerEpoch,
            ) != 1
        ) {
            return@withTransaction AndroidGroupProviderWriteCompletionResult.Stale
        }
        if (dao.compareAndSetGroupRevision(group.accountId, group.canonicalGroupId, group.revision) != 1) {
            error("Group ledger CAS failed inside serialized Room transaction")
        }

        val outcome = journal.toOutcome()
        val next = when (outcome) {
            AndroidGroupProviderWriteOutcome.Deleted -> group.copy(
                revision = nextGroupRevision,
                groupRowLocator = null,
                providerVersion = null,
                androidBaselineFingerprint = null,
                pendingProjectionFingerprint = null,
                projectionState = AndroidProjectionWriteState.DETACHED.name,
                tombstoneState = AndroidTombstoneState.REMOTE_CONVERGED.name,
                adoptionState = if (group.sourceIdentity == null) {
                    AndroidAdoptionState.AWAITING_REMOTE_ID.name
                } else {
                    AndroidAdoptionState.SOURCE_ID_PENDING.name
                },
            )
            is AndroidGroupProviderWriteOutcome.Present -> {
                val encoded = requireNotNull(journal.desiredSnapshot)
                val snapshot = AndroidGroupSnapshotBinaryCodec.decode(encoded)
                check(snapshot.accountId == group.accountId)
                check(snapshot.canonicalGroupId == group.canonicalGroupId)
                val semantic = snapshot.semanticFingerprint().sha256Hex
                check(semantic == journal.desiredSemanticFingerprint)
                group.copy(
                    revision = nextGroupRevision,
                    groupRowLocator = outcome.handle.groupRowId,
                    providerVersion = outcome.handle.version,
                    sourceIdentity = journal.resultSourceIdentity,
                    canonicalProjectionFingerprint = semantic,
                    androidBaselineFingerprint = semantic,
                    pendingProjectionFingerprint = null,
                    projectionState = AndroidProjectionWriteState.CLEAN.name,
                    ingestionState = AndroidIngestionState.BASELINED.name,
                    tombstoneState = AndroidTombstoneState.NONE.name,
                    adoptionState = if (journal.resultSourceIdentity == null) {
                        AndroidAdoptionState.AWAITING_REMOTE_ID.name
                    } else {
                        AndroidAdoptionState.ADOPTED.name
                    },
                )
            }
        }
        check(dao.updateGroup(next) == 1)
        if (outcome == AndroidGroupProviderWriteOutcome.Deleted) {
            dao.deleteGroupBaseline(group.accountId, group.canonicalGroupId)
        } else {
            dao.upsertGroupBaseline(
                AndroidGroupProjectionBaselineEntity(
                    accountId = group.accountId,
                    canonicalGroupId = group.canonicalGroupId,
                    fingerprint = requireNotNull(journal.desiredSnapshotIntegrityFingerprint),
                    encodedSnapshot = requireNotNull(journal.desiredSnapshot).copyOf(),
                ),
            )
        }
        dao.upsertGroupProviderWriteJournal(
            journal.copy(
                completedAccountRevision = nextAccountRevision,
                completedGroupLedgerRevision = nextGroupRevision,
            ),
        )
        check(nextAccountRevision == account.revision + 1)
        AndroidGroupProviderWriteCompletionResult.Completed(nextGroupRevision)
    }

    private suspend fun isCurrent(authorization: AndroidGroupWriteAuthorization): Boolean =
        database.withTransaction {
            val journal = dao.getGroupProviderWriteJournal(
                authorization.context.accountId,
                authorization.canonicalGroupId,
            ) ?: return@withTransaction false
            journal.state == STATE_PREPARED &&
                journal.commandFingerprint == authorization.toPreparedEntity().commandFingerprint &&
                hasCurrentDurableClaims(authorization)
        }

    private suspend fun hasCurrentDurableClaims(authorization: AndroidGroupWriteAuthorization): Boolean {
        val context = authorization.context
        val account = database.androidProjectionLedgerDao().getAccount(context.accountId) ?: return false
        if (account.revision != context.expectedAccountRevision || account.providerEpoch != context.providerEpoch) {
            return false
        }
        if (account.androidAccountName != authorization.accountName.value) return false
        val group = dao.getGroup(context.accountId, authorization.canonicalGroupId) ?: return false
        val canonical = database.contactGroupDao().get(context.accountId, authorization.canonicalGroupId)?.group
            ?: return false
        if (canonical.revision != context.expectedCanonicalGroupRevision ||
            canonical.isDeleted != (authorization.operation == AndroidGroupProviderOperation.DELETE)
        ) {
            return false
        }
        if (canonical.remoteLabelId != authorization.sourceIdentityAfterWrite) return false
        if (authorization.operation != AndroidGroupProviderOperation.DELETE &&
            (canonical.name != authorization.desiredTitle || canonical.isVisible != authorization.desiredVisibility)
        ) {
            return false
        }
        if (group.revision != context.expectedGroupLedgerRevision ||
            group.providerEpoch != context.providerEpoch ||
            group.groupRowLocator != authorization.expectedGroupRowId ||
            group.providerVersion != authorization.expectedProviderVersion ||
            group.sourceIdentity != authorization.expectedSourceIdentity.valueOrNull()
        ) {
            return false
        }
        val hasCanonicalTombstone = group.tombstoneState != AndroidTombstoneState.NONE.name
        if (hasCanonicalTombstone != (authorization.operation == AndroidGroupProviderOperation.DELETE)) {
            return false
        }
        return authorization.operation != AndroidGroupProviderOperation.DELETE ||
            hasConvergedMemberships(context.accountId, authorization.canonicalGroupId)
    }

    private suspend fun hasConvergedMemberships(accountId: String, canonicalGroupId: String): Boolean {
        if (database.contactGroupDao().countMembershipsForGroup(accountId, canonicalGroupId) != 0) return false
        val providerEpoch = database.androidProjectionLedgerDao().getAccount(accountId)?.providerEpoch
            ?: return false
        val contactLedgers = database.androidProjectionLedgerDao().getAll(accountId)
        val terminalLedgers = contactLedgers.filter {
            it.providerEpoch == providerEpoch && it.tombstoneState == AndroidTombstoneState.REMOTE_CONVERGED.name &&
                it.projectionState == AndroidProjectionWriteState.DETACHED.name
        }
        val terminalContacts = terminalLedgers.map { it.canonicalContactId }.chunked(100).flatMap {
            database.contactDao().getForProjectionPage(accountId, it)
        }.associateBy { it.contact.id }
        val completedDeletions = terminalLedgers.filter { ledger ->
            val canonical = terminalContacts[ledger.canonicalContactId]?.contact
            canonical?.isDeleted == true && canonical.pendingMutationRevision == null &&
                canonical.conflictState == null && canonical.remoteContactId == ledger.sourceIdentity
        }.map { it.canonicalContactId }.toSet()
        // Historical membership receipts of purged contacts cannot block a live group forever.
        // Only the terminal provider-deletion receipt permits excluding those rows.
        val membershipLedgers = dao.getAllMemberships(accountId)
            .filterNot { it.canonicalContactId in completedDeletions }
        val membershipsByContactId = membershipLedgers.associateBy { it.canonicalContactId }
        for (contactLedger in contactLedgers.filterNot { it.canonicalContactId in completedDeletions }) {
            if (contactLedger.providerEpoch != providerEpoch) return false
            val rawContactLocator = contactLedger.rawContactLocator ?: continue
            val membershipLedger = membershipsByContactId[contactLedger.canonicalContactId]
                ?: return false
            if (membershipLedger.rawContactLocator != rawContactLocator) return false
        }
        for (ledger in membershipLedgers) {
            if (ledger.providerEpoch != providerEpoch ||
                ledger.pendingProjectionFingerprint != null ||
                ledger.projectionState != AndroidProjectionWriteState.CLEAN.name
            ) {
                return false
            }
            val baseline = dao.getMembershipBaseline(accountId, ledger.canonicalContactId)
                ?: return false
            val integrity = try {
                AndroidGroupMembershipSnapshotBinaryCodec.integrityFingerprint(baseline.encodedSnapshot).sha256Hex
            } catch (_: AndroidGroupSnapshotCodecException) {
                return false
            }
            if (integrity != baseline.fingerprint) return false
            val decoded = try {
                AndroidGroupMembershipSnapshotBinaryCodec.decode(baseline.encodedSnapshot)
            } catch (_: AndroidGroupSnapshotCodecException) {
                return false
            }
            val semantic = decoded.semanticFingerprint().sha256Hex
            if (decoded.accountId != accountId ||
                decoded.canonicalContactId != ledger.canonicalContactId ||
                semantic != ledger.androidBaselineFingerprint ||
                semantic != ledger.canonicalProjectionFingerprint ||
                canonicalGroupId in decoded.canonicalGroupIds
            ) {
                return false
            }
        }
        return true
    }

    private suspend fun isDurablyCompleted(journal: AndroidGroupProviderWriteJournalEntity): Boolean {
        val account = database.androidProjectionLedgerDao().getAccount(journal.accountId) ?: return false
        val group = dao.getGroup(journal.accountId, journal.canonicalGroupId) ?: return false
        if (journal.completedAccountRevision == null || journal.completedGroupLedgerRevision == null ||
            account.providerEpoch != journal.providerEpoch ||
            account.revision != journal.completedAccountRevision ||
            group.providerEpoch != journal.providerEpoch ||
            group.revision != journal.completedGroupLedgerRevision
        ) {
            return false
        }
        return if (journal.resultDeleted == true) {
            group.groupRowLocator == null && group.providerVersion == null &&
                group.projectionState == AndroidProjectionWriteState.DETACHED.name
        } else {
            group.groupRowLocator == journal.resultGroupRowLocator &&
                group.providerVersion == journal.resultProviderVersion &&
                group.sourceIdentity == journal.resultSourceIdentity &&
                group.androidBaselineFingerprint == journal.desiredSemanticFingerprint &&
                group.pendingProjectionFingerprint == null &&
                group.projectionState == AndroidProjectionWriteState.CLEAN.name
        }
    }

    private fun AndroidGroupWriteAuthorization.toPreparedEntity(): AndroidGroupProviderWriteJournalEntity {
        val snapshot = if (desiredTitle == null && desiredVisibility == null) {
            null
        } else {
            AndroidGroupSnapshot(
                context.accountId,
                canonicalGroupId,
                requireNotNull(desiredTitle),
                requireNotNull(desiredVisibility),
            )
        }
        require((operation == AndroidGroupProviderOperation.DELETE) == (snapshot == null))
        val encoded = snapshot?.let(AndroidGroupSnapshotBinaryCodec::encode)
        val semantic = snapshot?.semanticFingerprint()?.sha256Hex
        val integrity = encoded?.let(AndroidGroupSnapshotBinaryCodec::integrityFingerprint)?.sha256Hex
        val commandFingerprint = commandFingerprint(encoded)
        return AndroidGroupProviderWriteJournalEntity(
            accountId = context.accountId,
            canonicalGroupId = canonicalGroupId,
            androidAccountName = accountName.value,
            providerEpoch = context.providerEpoch,
            operation = operation.name,
            state = STATE_PREPARED,
            expectedAccountRevision = context.expectedAccountRevision,
            expectedCanonicalGroupRevision = context.expectedCanonicalGroupRevision,
            expectedGroupLedgerRevision = context.expectedGroupLedgerRevision,
            expectedGroupRowLocator = expectedGroupRowId,
            expectedProviderVersion = expectedProviderVersion,
            expectedSourceIdentity = expectedSourceIdentity.valueOrNull(),
            sourceIdentityAfterWrite = sourceIdentityAfterWrite,
            expectedDeleted = expectedDeleted,
            desiredSemanticFingerprint = semantic,
            desiredSnapshotIntegrityFingerprint = integrity,
            desiredSnapshot = encoded,
            commandFingerprint = commandFingerprint,
            resultGroupRowLocator = null,
            resultProviderVersion = null,
            resultSourceIdentity = null,
            resultDeleted = null,
            resultProviderStateFingerprint = null,
            completedAccountRevision = null,
            completedGroupLedgerRevision = null,
        )
    }

    private fun AndroidGroupWriteAuthorization.commandFingerprint(encodedSnapshot: ByteArray?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.putText("contako-android-group-provider-write-v1")
        digest.putText(context.accountId)
        digest.putText(accountName.value)
        digest.putText(canonicalGroupId)
        digest.putText(operation.name)
        digest.putLong(context.providerEpoch)
        digest.putLong(context.expectedAccountRevision)
        digest.putLong(context.expectedCanonicalGroupRevision)
        digest.putLong(context.expectedGroupLedgerRevision)
        digest.putNullableLong(expectedGroupRowId)
        digest.putNullableLong(expectedProviderVersion)
        digest.putNullableText(expectedSourceIdentity.valueOrNull())
        digest.putNullableText(sourceIdentityAfterWrite)
        digest.update(if (expectedDeleted) 1 else 0)
        digest.putBytes(encodedSnapshot)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun AndroidGroupProviderWriteJournalEntity.withVerifiedState(
        state: AndroidVerifiedGroupProviderPostState,
    ): AndroidGroupProviderWriteJournalEntity = when (state) {
        is AndroidVerifiedGroupProviderPostState.Absent -> copy(
            state = STATE_COMMITTED,
            resultDeleted = true,
            resultProviderStateFingerprint = state.providerStateFingerprint,
        )
        is AndroidVerifiedGroupProviderPostState.Present -> copy(
            state = STATE_COMMITTED,
            resultGroupRowLocator = state.handle.groupRowId,
            resultProviderVersion = state.handle.version,
            resultSourceIdentity = state.sourceIdentity,
            resultDeleted = false,
            resultProviderStateFingerprint = state.providerStateFingerprint,
        )
    }

    private fun AndroidVerifiedGroupProviderPostState.toOutcome(): AndroidGroupProviderWriteOutcome = when (this) {
        is AndroidVerifiedGroupProviderPostState.Absent -> AndroidGroupProviderWriteOutcome.Deleted
        is AndroidVerifiedGroupProviderPostState.Present -> AndroidGroupProviderWriteOutcome.Present(handle)
    }

    private fun AndroidGroupWriteAuthorization.accepts(
        state: AndroidVerifiedGroupProviderPostState,
    ): Boolean {
        return when (state) {
            is AndroidVerifiedGroupProviderPostState.Absent -> operation == AndroidGroupProviderOperation.DELETE
            is AndroidVerifiedGroupProviderPostState.Present -> {
                if (operation == AndroidGroupProviderOperation.DELETE || state.handle.deleted) return false
                if (expectedGroupRowId != null && state.handle.groupRowId != expectedGroupRowId) return false
                if (expectedProviderVersion != null && state.handle.version < expectedProviderVersion) return false
                state.sourceIdentity == sourceIdentityAfterWrite &&
                    state.handle.hasSourceIdentity == (sourceIdentityAfterWrite != null)
            }
        }
    }

    private fun AndroidGroupProviderWriteJournalEntity.toOutcome(): AndroidGroupProviderWriteOutcome =
        if (resultDeleted == true) {
            AndroidGroupProviderWriteOutcome.Deleted
        } else {
            AndroidGroupProviderWriteOutcome.Present(
                AndroidOwnedGroupHandle(
                    groupRowId = requireNotNull(resultGroupRowLocator),
                    version = requireNotNull(resultProviderVersion),
                    hasSourceIdentity = resultSourceIdentity != null,
                    deleted = false,
                ),
            )
        }

    private fun AndroidExpectedSourceIdentity.valueOrNull(): String? = when (this) {
        AndroidExpectedSourceIdentity.Missing -> null
        is AndroidExpectedSourceIdentity.Present -> value
    }

    private fun MessageDigest.putText(value: String) = putBytes(value.toByteArray(StandardCharsets.UTF_8))

    private fun MessageDigest.putNullableText(value: String?) {
        if (value == null) update(0) else {
            update(1)
            putText(value)
        }
    }

    private fun MessageDigest.putLong(value: Long) = update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value).array())

    private fun MessageDigest.putNullableLong(value: Long?) {
        if (value == null) update(0) else {
            update(1)
            putLong(value)
        }
    }

    private fun MessageDigest.putBytes(value: ByteArray?) {
        if (value == null) {
            update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(-1).array())
        } else {
            update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value.size).array())
            update(value)
        }
    }

    private companion object {
        const val STATE_PREPARED = "PREPARED"
        const val STATE_COMMITTED = "COMMITTED"
        const val STATE_REPAIR_REQUIRED = "REPAIR_REQUIRED"
    }
}
