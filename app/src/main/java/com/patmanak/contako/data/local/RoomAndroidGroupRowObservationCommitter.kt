package com.patmanak.contako.data.local

import androidx.room.withTransaction
import com.patmanak.contako.data.android.AndroidAdoptionState
import com.patmanak.contako.data.android.AndroidIngestionState
import com.patmanak.contako.data.android.AndroidProjectionWriteState
import com.patmanak.contako.data.android.AndroidTombstoneState
import com.patmanak.contako.data.android.mapping.AndroidGroupSnapshot
import com.patmanak.contako.data.android.mapping.AndroidGroupSnapshotBinaryCodec
import com.patmanak.contako.data.gateway.AccountScope
import java.nio.ByteBuffer
import java.security.MessageDigest

internal data class RoomAndroidGroupRowObservationCommand(
    val account: AccountScope,
    val androidAccountName: String,
    val expectedAccountRevision: Long,
    val providerEpoch: Long,
    val canonicalGroupId: String,
    val expectedCanonicalGroupRevision: Long?,
    val expectedGroupLedgerRevision: Long?,
    val groupRowLocator: Long,
    val providerVersion: Long,
    val sourceIdentity: String?,
    val deleted: Boolean,
    val observedSnapshot: AndroidGroupSnapshot?,
) {
    init {
        require(androidAccountName.isNotBlank())
        require(expectedAccountRevision >= 0 && providerEpoch >= 0)
        require(canonicalGroupId.isNotBlank())
        require(expectedCanonicalGroupRevision == null || expectedCanonicalGroupRevision >= 0)
        require(expectedGroupLedgerRevision == null || expectedGroupLedgerRevision >= 0)
        require(groupRowLocator > 0 && providerVersion >= 0)
        require(deleted == (observedSnapshot == null))
    }

    override fun toString(): String = "RoomAndroidGroupRowObservationCommand(REDACTED, deleted=$deleted)"
}

internal enum class RoomAndroidGroupRowObservationClassification {
    BASELINED, SELF_WRITE_RECONCILED, NO_CHANGE, ANDROID_CREATED,
    ANDROID_EDIT_COMMITTED, ANDROID_DELETE_COMMITTED,
}

internal sealed interface RoomAndroidGroupRowObservationResult {
    data class Applied(
        val classification: RoomAndroidGroupRowObservationClassification,
        val committedAccountRevision: Long,
        val committedGroupLedgerRevision: Long,
    ) : RoomAndroidGroupRowObservationResult
    data object AlreadyCommitted : RoomAndroidGroupRowObservationResult
    data object StaleAccount : RoomAndroidGroupRowObservationResult
    data object StaleProviderEpoch : RoomAndroidGroupRowObservationResult
    data object StaleProviderRow : RoomAndroidGroupRowObservationResult
    data object StaleCanonicalGroup : RoomAndroidGroupRowObservationResult
    data object StaleGroupLedger : RoomAndroidGroupRowObservationResult
    data object RepairRequired : RoomAndroidGroupRowObservationResult
}

internal enum class RoomAndroidGroupRowObservationCheckpoint {
    AFTER_ACCOUNT_CAS,
    AFTER_CANONICAL_MUTATION,
    AFTER_LEDGER_MUTATION,
    AFTER_BASELINE_MUTATION,
    AFTER_RECEIPT,
}

/** Closed invariant causes only; no group/provider/database value may cross this boundary. */
internal enum class RoomAndroidGroupCommitRepairReason {
    UNFINISHED_PROVIDER_WRITE_JOURNAL,
    LEDGER_PROJECTION_REPAIR_REQUIRED,
    BASELINE_PRESENT_WITHOUT_LEDGER_FINGERPRINT,
    BASELINE_MISSING,
    BASELINE_INTEGRITY_FAILURE,
    BASELINE_IDENTITY_FAILURE,
}

internal fun interface RoomAndroidGroupCommitRepairObserver {
    fun onRepairRequired(reason: RoomAndroidGroupCommitRepairReason)
}

internal fun interface RoomAndroidGroupRowObservationCheckpointHook {
    fun onCheckpoint(checkpoint: RoomAndroidGroupRowObservationCheckpoint)

    companion object {
        val NONE = RoomAndroidGroupRowObservationCheckpointHook { }
    }
}

internal class RoomAndroidGroupRowObservationCommitter(
    private val database: ContakoDatabase,
    private val repository: RoomContactRepository,
    private val checkpointHook: RoomAndroidGroupRowObservationCheckpointHook =
        RoomAndroidGroupRowObservationCheckpointHook.NONE,
    private val repairObserver: RoomAndroidGroupCommitRepairObserver =
        RoomAndroidGroupCommitRepairObserver { },
) {
    init { require(repository.isBackedBy(database)) }

    suspend fun commit(
        input: RoomAndroidGroupRowObservationCommand,
    ): RoomAndroidGroupRowObservationResult {
        val encoded = input.observedSnapshot?.let(AndroidGroupSnapshotBinaryCodec::encode)
        val command = input.copy(observedSnapshot = encoded?.let(AndroidGroupSnapshotBinaryCodec::decode))
        if (command.observedSnapshot?.accountId != null &&
            (command.observedSnapshot.accountId != command.account.value ||
                command.observedSnapshot.canonicalGroupId != command.canonicalGroupId)
        ) return RoomAndroidGroupRowObservationResult.StaleCanonicalGroup
        val semantic = command.observedSnapshot?.semanticFingerprint()?.sha256Hex
        val integrity = encoded?.let(AndroidGroupSnapshotBinaryCodec::integrityFingerprint)?.sha256Hex
        val commandFingerprint = fingerprint(command, encoded)
        return AndroidProviderAccountMutationLocks.withAccountLock(command.account.value) {
            try {
            database.withTransaction {
                val account = database.androidProjectionLedgerDao().getAccount(command.account.value)
                    ?: return@withTransaction RoomAndroidGroupRowObservationResult.StaleAccount
                if (account.providerEpoch != command.providerEpoch) {
                    return@withTransaction RoomAndroidGroupRowObservationResult.StaleProviderEpoch
                }
                val retainedReceipt = database.androidGroupProjectionDao().getGroupObservationCommitReceipt(
                    command.account.value, command.canonicalGroupId,
                )
                if (retainedReceipt?.commandFingerprint == commandFingerprint &&
                    account.revision == retainedReceipt.committedAccountRevision &&
                    retainedReceipt.postStateFingerprint == postStateFingerprint(command)
                ) return@withTransaction RoomAndroidGroupRowObservationResult.AlreadyCommitted
                if (account.revision != command.expectedAccountRevision ||
                    account.androidAccountName != command.androidAccountName
                ) return@withTransaction RoomAndroidGroupRowObservationResult.StaleAccount
                val dao = database.androidGroupProjectionDao()
                val ledger = dao.getGroup(command.account.value, command.canonicalGroupId)
                val journal = dao.getGroupProviderWriteJournal(command.account.value, command.canonicalGroupId)
                if (journal != null && !isDurablyCompleted(journal, account, ledger)) {
                    repair(RoomAndroidGroupCommitRepairReason.UNFINISHED_PROVIDER_WRITE_JOURNAL)
                }
                if ((ledger == null) != (command.expectedGroupLedgerRevision == null) ||
                    ledger != null && (ledger.revision != command.expectedGroupLedgerRevision ||
                    ledger.providerEpoch != command.providerEpoch ||
                    ledger.groupRowLocator != command.groupRowLocator ||
                    ledger.sourceIdentity != command.sourceIdentity)
                ) return@withTransaction RoomAndroidGroupRowObservationResult.StaleGroupLedger
                if (ledger?.providerVersion != null && ledger.providerVersion > command.providerVersion) {
                    return@withTransaction RoomAndroidGroupRowObservationResult.StaleProviderRow
                }
                val previous = ledger?.let { validateBaseline(command, it) }
                if (ledger?.projectionState == AndroidProjectionWriteState.REPAIR_REQUIRED.name) {
                    repair(RoomAndroidGroupCommitRepairReason.LEDGER_PROJECTION_REPAIR_REQUIRED)
                }
                val canonical = database.contactGroupDao().get(command.account.value, command.canonicalGroupId)?.toDomain()
                if (canonical?.revision != command.expectedCanonicalGroupRevision) {
                    return@withTransaction RoomAndroidGroupRowObservationResult.StaleCanonicalGroup
                }
                val canonicalMatches = if (command.deleted) canonical?.isDeleted == true else
                    canonical != null && !canonical.isDeleted && canonical.name == command.observedSnapshot!!.title &&
                        canonical.isVisible == command.observedSnapshot.isVisible
                val classification = when {
                    command.deleted -> RoomAndroidGroupRowObservationClassification.ANDROID_DELETE_COMMITTED
                    ledger?.pendingProjectionFingerprint == semantic && canonicalMatches ->
                        RoomAndroidGroupRowObservationClassification.SELF_WRITE_RECONCILED
                    ledger != null && ledger.androidBaselineFingerprint == null && canonicalMatches ->
                        RoomAndroidGroupRowObservationClassification.BASELINED
                    previous?.semanticFingerprint()?.sha256Hex == semantic && canonicalMatches ->
                        RoomAndroidGroupRowObservationClassification.NO_CHANGE
                    canonical == null -> RoomAndroidGroupRowObservationClassification.ANDROID_CREATED
                    else -> RoomAndroidGroupRowObservationClassification.ANDROID_EDIT_COMMITTED
                }
                if (ledger?.providerVersion == command.providerVersion &&
                    classification == RoomAndroidGroupRowObservationClassification.ANDROID_EDIT_COMMITTED
                ) return@withTransaction RoomAndroidGroupRowObservationResult.StaleProviderRow
                if (database.androidProjectionLedgerDao().compareAndSetAccountRevisionAtProviderEpoch(
                        command.account.value, command.expectedAccountRevision, command.providerEpoch,
                    ) != 1 || ledger != null && dao.compareAndSetGroupRevision(
                        command.account.value, command.canonicalGroupId, requireNotNull(command.expectedGroupLedgerRevision),
                    ) != 1
                ) return@withTransaction RoomAndroidGroupRowObservationResult.StaleAccount
                checkpointHook.onCheckpoint(RoomAndroidGroupRowObservationCheckpoint.AFTER_ACCOUNT_CAS)
                when (repository.applyObservedGroupRowInCurrentTransaction(
                    command.account.value, command.canonicalGroupId, command.expectedCanonicalGroupRevision,
                    command.observedSnapshot?.title, command.observedSnapshot?.isVisible, command.deleted,
                )) {
                    RoomObservedGroupRowMutationResult.Stale,
                    RoomObservedGroupRowMutationResult.DeleteIntentConflict,
                    RoomObservedGroupRowMutationResult.Stale ->
                        abort(RoomAndroidGroupRowObservationResult.StaleCanonicalGroup)
                    else -> Unit
                }
                checkpointHook.onCheckpoint(RoomAndroidGroupRowObservationCheckpoint.AFTER_CANONICAL_MUTATION)
                val next = (ledger ?: AndroidGroupProjectionLedgerEntity(
                    accountId = command.account.value,
                    canonicalGroupId = command.canonicalGroupId,
                    revision = 0,
                    providerEpoch = command.providerEpoch,
                    groupRowLocator = command.groupRowLocator,
                    providerVersion = null,
                    sourceIdentity = command.sourceIdentity,
                    canonicalProjectionFingerprint = null,
                    androidBaselineFingerprint = null,
                    pendingProjectionFingerprint = null,
                    projectionState = AndroidProjectionWriteState.DETACHED.name,
                    ingestionState = AndroidIngestionState.NONE.name,
                    tombstoneState = AndroidTombstoneState.NONE.name,
                    adoptionState = if (command.sourceIdentity == null) {
                        AndroidAdoptionState.AWAITING_REMOTE_ID.name
                    } else {
                        AndroidAdoptionState.ADOPTED.name
                    },
                )).copy(
                    revision = Math.incrementExact(command.expectedGroupLedgerRevision ?: 0),
                    providerVersion = command.providerVersion,
                    canonicalProjectionFingerprint = semantic,
                    androidBaselineFingerprint = semantic,
                    pendingProjectionFingerprint = null,
                    projectionState = if (command.deleted) AndroidProjectionWriteState.DETACHED.name else AndroidProjectionWriteState.CLEAN.name,
                    ingestionState = if (classification == RoomAndroidGroupRowObservationClassification.ANDROID_EDIT_COMMITTED ||
                        classification == RoomAndroidGroupRowObservationClassification.ANDROID_CREATED || command.deleted
                    ) AndroidIngestionState.CANONICAL_DELTA_COMMITTED.name else AndroidIngestionState.BASELINED.name,
                    tombstoneState = if (command.deleted) AndroidTombstoneState.CANONICAL_COMMITTED.name else AndroidTombstoneState.NONE.name,
                    adoptionState = if (command.sourceIdentity == null) AndroidAdoptionState.AWAITING_REMOTE_ID.name else AndroidAdoptionState.ADOPTED.name,
                )
                if (ledger == null) check(dao.insertGroup(next) != -1L) else check(dao.updateGroup(next) == 1)
                checkpointHook.onCheckpoint(RoomAndroidGroupRowObservationCheckpoint.AFTER_LEDGER_MUTATION)
                if (command.deleted) dao.deleteGroupBaseline(command.account.value, command.canonicalGroupId)
                else dao.upsertGroupBaseline(AndroidGroupProjectionBaselineEntity(
                    command.account.value, command.canonicalGroupId, requireNotNull(integrity), requireNotNull(encoded),
                ))
                checkpointHook.onCheckpoint(RoomAndroidGroupRowObservationCheckpoint.AFTER_BASELINE_MUTATION)
                val committedAccountRevision = Math.incrementExact(command.expectedAccountRevision)
                val committedGroupLedgerRevision = Math.incrementExact(command.expectedGroupLedgerRevision ?: 0)
                val receipt = AndroidGroupObservationCommitReceiptEntity(
                    command.account.value, command.canonicalGroupId, command.providerEpoch,
                    command.expectedAccountRevision, command.expectedGroupLedgerRevision ?: 0,
                    committedAccountRevision, committedGroupLedgerRevision, commandFingerprint,
                    postStateFingerprint(command),
                )
                dao.upsertGroupObservationCommitReceipt(receipt)
                checkpointHook.onCheckpoint(RoomAndroidGroupRowObservationCheckpoint.AFTER_RECEIPT)
                RoomAndroidGroupRowObservationResult.Applied(
                    classification, committedAccountRevision, committedGroupLedgerRevision,
                )
            }
            } catch (abort: GroupObservationAbort) {
                abort.result
            }
        }
    }

    private suspend fun validateBaseline(
        command: RoomAndroidGroupRowObservationCommand,
        ledger: AndroidGroupProjectionLedgerEntity,
    ): AndroidGroupSnapshot? {
        val entity = database.androidGroupProjectionDao().getGroupBaseline(command.account.value, command.canonicalGroupId)
        if (ledger.androidBaselineFingerprint == null) return if (entity == null) null else {
            repair(RoomAndroidGroupCommitRepairReason.BASELINE_PRESENT_WITHOUT_LEDGER_FINGERPRINT)
        }
        entity ?: repair(RoomAndroidGroupCommitRepairReason.BASELINE_MISSING)
        if (AndroidGroupSnapshotBinaryCodec.integrityFingerprint(entity.encodedSnapshot).sha256Hex != entity.fingerprint) {
            repair(RoomAndroidGroupCommitRepairReason.BASELINE_INTEGRITY_FAILURE)
        }
        val decoded = AndroidGroupSnapshotBinaryCodec.decode(entity.encodedSnapshot)
        if (decoded.accountId != command.account.value || decoded.canonicalGroupId != command.canonicalGroupId ||
            decoded.semanticFingerprint().sha256Hex != ledger.androidBaselineFingerprint
        ) repair(RoomAndroidGroupCommitRepairReason.BASELINE_IDENTITY_FAILURE)
        return decoded
    }

    private suspend fun postStateFingerprint(c: RoomAndroidGroupRowObservationCommand): String {
        val canonical = database.contactGroupDao().get(c.account.value, c.canonicalGroupId)?.group
        val outbox = database.outboxDao().get(c.account.value, AggregateType.GROUP.name, c.canonicalGroupId)
        val ledger = database.androidGroupProjectionDao().getGroup(c.account.value, c.canonicalGroupId)
        val baseline = database.androidGroupProjectionDao().getGroupBaseline(c.account.value, c.canonicalGroupId)
        return digestValues(listOf(
            canonical?.revision, canonical?.name, canonical?.isVisible, canonical?.isDeleted,
            canonical?.pendingMutationRevision, outbox?.operation, outbox?.revision,
            outbox?.remoteIdentity, outbox?.remoteVersion, ledger?.revision, ledger?.providerEpoch,
            ledger?.groupRowLocator, ledger?.providerVersion, ledger?.sourceIdentity,
            ledger?.canonicalProjectionFingerprint, ledger?.androidBaselineFingerprint,
            ledger?.pendingProjectionFingerprint, ledger?.projectionState, ledger?.ingestionState,
            ledger?.tombstoneState, ledger?.adoptionState, baseline?.fingerprint,
            baseline?.encodedSnapshot?.contentHashCode(),
        ))
    }

    private fun isDurablyCompleted(
        journal: AndroidGroupProviderWriteJournalEntity,
        account: AndroidProjectionAccountEntity,
        ledger: AndroidGroupProjectionLedgerEntity?,
    ): Boolean {
        val completedAccountRevision = journal.completedAccountRevision ?: return false
        val completedGroupLedgerRevision = journal.completedGroupLedgerRevision ?: return false
        if (journal.resultDeleted == null || journal.resultProviderStateFingerprint == null) return false
        if (ledger == null || journal.providerEpoch != account.providerEpoch ||
            ledger.providerEpoch != journal.providerEpoch ||
            account.revision < completedAccountRevision || ledger.revision < completedGroupLedgerRevision
        ) return false
        // Account revision is shared by every Android projection and both revisions are monotonic.
        // A later durable revision supersedes this completed proof; exact revision still validates
        // the provider outcome so a partial or inconsistent completion remains fail-closed.
        if (ledger.revision > completedGroupLedgerRevision) return true
        return journal.resultDeleted == true && ledger.groupRowLocator == null && ledger.providerVersion == null ||
            journal.resultDeleted == false && ledger.groupRowLocator == journal.resultGroupRowLocator &&
                ledger.providerVersion == journal.resultProviderVersion
    }

    private fun fingerprint(c: RoomAndroidGroupRowObservationCommand, encoded: ByteArray?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(value: ByteArray) { digest.update(ByteBuffer.allocate(4).putInt(value.size).array()); digest.update(value) }
        listOf(c.account.value, c.androidAccountName, c.canonicalGroupId, c.sourceIdentity.orEmpty(),
            c.expectedAccountRevision.toString(), c.providerEpoch.toString(), c.expectedCanonicalGroupRevision.toString(),
            c.expectedGroupLedgerRevision.toString(), c.groupRowLocator.toString(), c.providerVersion.toString(), c.deleted.toString(),
        ).forEach { add(it.toByteArray()) }
        add(encoded ?: byteArrayOf())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun digestValues(values: List<Any?>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        values.forEach { value ->
            val bytes = value?.toString()?.toByteArray() ?: byteArrayOf()
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun repair(reason: RoomAndroidGroupCommitRepairReason): Nothing {
        runCatching { repairObserver.onRepairRequired(reason) }
        abort(RoomAndroidGroupRowObservationResult.RepairRequired)
    }
    private fun abort(result: RoomAndroidGroupRowObservationResult): Nothing = throw GroupObservationAbort(result)
}

private class GroupObservationAbort(
    val result: RoomAndroidGroupRowObservationResult,
) : RuntimeException(null, null, false, false)
