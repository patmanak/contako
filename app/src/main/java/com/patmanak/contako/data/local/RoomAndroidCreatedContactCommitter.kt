package com.patmanak.contako.data.local

import androidx.room.withTransaction
import com.patmanak.contako.data.android.AndroidLedgerCasResult
import com.patmanak.contako.data.android.AndroidIngestionState
import com.patmanak.contako.data.android.AndroidProjectionLedgerSnapshot
import com.patmanak.contako.data.android.AndroidRawContactLocator
import com.patmanak.contako.data.android.AndroidTombstoneState
import com.patmanak.contako.data.android.RoomAndroidProjectionLedger
import com.patmanak.contako.data.android.mapping.AndroidContactSnapshot
import com.patmanak.contako.data.android.mapping.CanonicalAndroidContactMapper
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.repository.SaveValidationIssue

/**
 * Atomic adoption boundary for a raw contact created by an Android system editor.
 *
 * The hidden shell, canonical value set, outbox create intent, ledger attachment, raw-contact
 * locator, and first complete Android baseline either commit together or remain entirely absent.
 * A retry after a lost acknowledgement recovers by provider epoch and raw-contact locator.
 */
internal class RoomAndroidCreatedContactCommitter(
    private val database: ContakoDatabase,
    private val account: AccountScope,
    private val ledger: RoomAndroidProjectionLedger = RoomAndroidProjectionLedger(database),
    private val canonicalMutations: RoomAndroidCanonicalMutationStore =
        RoomAndroidCanonicalMutationStore(database, account),
    private val mapper: CanonicalAndroidContactMapper = CanonicalAndroidContactMapper(),
) {
    suspend fun commit(
        canonicalContactId: String,
        locator: AndroidRawContactLocator,
        decodedContact: CanonicalContact,
        observedSnapshot: AndroidContactSnapshot,
    ): AndroidCreatedContactCommitResult {
        return try {
            database.withTransaction {
                commitInCurrentTransaction(canonicalContactId, locator, decodedContact, observedSnapshot)
            }
        } catch (abort: AndroidCreatedContactAbort) {
            when (abort.reason) {
                AndroidCreatedContactAbortReason.STALE -> AndroidCreatedContactCommitResult.Stale
                AndroidCreatedContactAbortReason.REJECTED ->
                    AndroidCreatedContactCommitResult.Rejected(abort.issues)
                AndroidCreatedContactAbortReason.INVALID_INPUT ->
                    AndroidCreatedContactCommitResult.InvalidInput(requireNotNull(abort.inputFailure))
            }
        }
    }

    internal suspend fun commitInCurrentTransaction(
        canonicalContactId: String,
        locator: AndroidRawContactLocator,
        decodedContact: CanonicalContact,
        observedSnapshot: AndroidContactSnapshot,
    ): AndroidCreatedContactCommitResult = commitInCurrentTransaction(
        canonicalContactId,
        locator,
        { decodedContact to observedSnapshot },
    )

    internal suspend fun commitInCurrentTransaction(
        canonicalContactId: String,
        locator: AndroidRawContactLocator,
        decode: () -> Pair<CanonicalContact, AndroidContactSnapshot>,
    ): AndroidCreatedContactCommitResult {
        check(database.inTransaction()) { "A caller-owned Room transaction is required" }
                val accountState = ledger.loadAccount(account)
                if ((accountState?.providerEpoch ?: INITIAL_PROVIDER_EPOCH) != locator.providerEpoch) {
                    return AndroidCreatedContactCommitResult.StaleProviderEpoch
                }

                recoverCommittedIdentity(locator)?.let { recovered ->
                    return recovered
                }
                when (val staged = canonicalMutations.stageAndroidCreatedShell(canonicalContactId)) {
                    is AndroidCanonicalMutationResult.Applied -> Unit
                    AndroidCanonicalMutationResult.Stale -> abort(AndroidCreatedContactAbortReason.STALE)
                    is AndroidCanonicalMutationResult.Rejected ->
                        abort(AndroidCreatedContactAbortReason.REJECTED, staged.issues)
                }

                // The identity resolver validates every provider-row locator against this ledger
                // scope. Stage the newly observed raw-contact locator in the same transaction
                // before decoding; any later rejection rolls the shell and locator back together.
                val attached = ledger.attachCanonicalContact(
                    account,
                    canonicalContactId,
                    createdRawContactLocator = locator,
                )
                if (
                    attached.revision != INITIAL_LEDGER_REVISION ||
                    attached.rawContactLocator != locator ||
                    attached.androidBaselineFingerprint != null ||
                    attached.pendingProjectionFingerprint != null
                ) {
                    abort(AndroidCreatedContactAbortReason.STALE)
                }
                val (decodedContact, observedSnapshot) = decode()
                val inputFailure = validateInput(canonicalContactId, decodedContact, observedSnapshot)
                if (inputFailure != null) {
                    abort(AndroidCreatedContactAbortReason.INVALID_INPUT, inputFailure = inputFailure)
                }
                val committed = when (
                    val mutation = canonicalMutations.applyContactDelta(
                        expectedCanonicalRevision = STAGED_SHELL_REVISION,
                        contact = decodedContact,
                    )
                ) {
                    is AndroidCanonicalMutationResult.Applied -> mutation.contact
                    AndroidCanonicalMutationResult.Stale -> abort(AndroidCreatedContactAbortReason.STALE)
                    is AndroidCanonicalMutationResult.Rejected ->
                        abort(AndroidCreatedContactAbortReason.REJECTED, mutation.issues)
                }
                val observedFingerprint = mapper.fingerprint(observedSnapshot)
                val canonicalFingerprint = mapper.fingerprint(mapper.project(committed))
                val baseline = when (
                    val result = ledger.establishObservedBaseline(
                        account = account,
                        canonicalContactId = canonicalContactId,
                        expectedRevision = attached.revision,
                        locator = locator,
                        canonicalProjectionFingerprint = canonicalFingerprint,
                        observedFingerprint = observedFingerprint,
                        observedSnapshot = observedSnapshot,
                    )
                ) {
                    is AndroidLedgerCasResult.Updated -> result.snapshot
                    AndroidLedgerCasResult.Stale -> abort(AndroidCreatedContactAbortReason.STALE)
                }
                return AndroidCreatedContactCommitResult.Applied(committed.revision, baseline)
    }

    private fun validateInput(
        canonicalContactId: String,
        decodedContact: CanonicalContact,
        observedSnapshot: AndroidContactSnapshot,
    ): AndroidCreatedContactInputFailure? = when {
        canonicalContactId.isBlank() ||
            canonicalContactId.length > MAX_CANONICAL_ID_LENGTH ||
            decodedContact.id != canonicalContactId ->
            AndroidCreatedContactInputFailure.CANONICAL_ID_MISMATCH
        decodedContact.accountId != account.value ->
            AndroidCreatedContactInputFailure.ACCOUNT_SCOPE_MISMATCH
        observedSnapshot.canonicalContactId != canonicalContactId ->
            AndroidCreatedContactInputFailure.SNAPSHOT_ID_MISMATCH
        decodedContact.remoteContactId != null ||
            decodedContact.remoteVCardUid != null ||
            decodedContact.remoteVersion != null ->
            AndroidCreatedContactInputFailure.REMOTE_STATE_ALREADY_PRESENT
        decodedContact.revision != 0L ||
            decodedContact.updatedAtEpochMillis != 0L ||
            decodedContact.pendingMutationRevision != null ||
            decodedContact.conflictState != null ||
            decodedContact.actionRequiredReasons.isNotEmpty() ||
            decodedContact.preservationEnvelope != null ||
            decodedContact.isDeleted -> AndroidCreatedContactInputFailure.NON_PRISTINE_CANONICAL_STATE
        else -> null
    }

    /**
     * A recovered identity instructs the caller to discard its candidate ID and decoded snapshot,
     * then re-read the raw contact using the returned canonical identity before any further delta.
     */
    private suspend fun recoverCommittedIdentity(
        locator: AndroidRawContactLocator,
    ): AndroidCreatedContactCommitResult? {
        val existing = ledger.loadByRawContactLocator(account, locator) ?: return null
        val stored = database.contactDao().get(account.value, existing.canonicalContactId)?.toDomain()
            ?: return AndroidCreatedContactCommitResult.LocatorConflict
        val baseline = ledger.loadBaseline(account, existing.canonicalContactId)
            ?: return AndroidCreatedContactCommitResult.LocatorConflict
        val pendingOutbox = stored.pendingMutationRevision?.let {
            database.outboxDao().get(account.value, AggregateType.CONTACT.name, existing.canonicalContactId)
        }
        val coherent =
            existing.rawContactLocator == locator &&
                existing.tombstoneState == AndroidTombstoneState.NONE &&
                existing.ingestionState == AndroidIngestionState.BASELINED &&
                baseline.canonicalContactId == existing.canonicalContactId &&
                !stored.isDeleted &&
                stored.revision >= FIRST_COMMITTED_CANONICAL_REVISION &&
                (stored.pendingMutationRevision == null ||
                    pendingOutbox?.revision == stored.pendingMutationRevision &&
                    pendingOutbox.operation == MutationOperation.UPSERT.name) &&
                existing.hasSourceIdentity == (stored.remoteContactId != null)
        return if (coherent) {
            AndroidCreatedContactCommitResult.RecoveredIdentity(existing)
        } else {
            AndroidCreatedContactCommitResult.LocatorConflict
        }
    }

    private fun abort(
        reason: AndroidCreatedContactAbortReason,
        issues: Set<SaveValidationIssue> = emptySet(),
        inputFailure: AndroidCreatedContactInputFailure? = null,
    ): Nothing = throw AndroidCreatedContactAbort(reason, issues, inputFailure)

    private companion object {
        const val INITIAL_PROVIDER_EPOCH = 0L
        const val INITIAL_LEDGER_REVISION = 0L
        const val STAGED_SHELL_REVISION = 0L
        const val FIRST_COMMITTED_CANONICAL_REVISION = 1L
        const val MAX_CANONICAL_ID_LENGTH = 4_096
    }
}

internal enum class AndroidCreatedContactInputFailure {
    ACCOUNT_SCOPE_MISMATCH,
    CANONICAL_ID_MISMATCH,
    SNAPSHOT_ID_MISMATCH,
    REMOTE_STATE_ALREADY_PRESENT,
    NON_PRISTINE_CANONICAL_STATE,
}

internal sealed interface AndroidCreatedContactCommitResult {
    data class Applied(
        val canonicalRevision: Long,
        val ledger: AndroidProjectionLedgerSnapshot,
    ) : AndroidCreatedContactCommitResult {
        override fun toString(): String =
            "AndroidCreatedContactCommitResult.Applied(REDACTED, canonicalRevision=$canonicalRevision)"
    }

    data class RecoveredIdentity(
        val ledger: AndroidProjectionLedgerSnapshot,
    ) : AndroidCreatedContactCommitResult {
        override fun toString(): String = "AndroidCreatedContactCommitResult.RecoveredIdentity(REDACTED)"
    }

    data class InvalidInput(
        val failure: AndroidCreatedContactInputFailure,
    ) : AndroidCreatedContactCommitResult

    data class Rejected(
        val issues: Set<SaveValidationIssue>,
    ) : AndroidCreatedContactCommitResult

    data object StaleProviderEpoch : AndroidCreatedContactCommitResult
    data object LocatorConflict : AndroidCreatedContactCommitResult
    data object Stale : AndroidCreatedContactCommitResult
}

private enum class AndroidCreatedContactAbortReason { STALE, REJECTED, INVALID_INPUT }

private class AndroidCreatedContactAbort(
    val reason: AndroidCreatedContactAbortReason,
    val issues: Set<SaveValidationIssue>,
    val inputFailure: AndroidCreatedContactInputFailure? = null,
) : IllegalStateException("Android-created contact commit aborted: ${reason.name}")
