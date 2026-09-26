package com.patmanak.contako.data.local

import com.patmanak.contako.data.android.AndroidObservationResult
import com.patmanak.contako.data.android.AndroidProjectionLedgerSnapshot
import com.patmanak.contako.data.android.AndroidRawContactLocator
import com.patmanak.contako.data.android.RoomAndroidProjectionLedger
import com.patmanak.contako.data.android.mapping.AndroidCanonicalDelta
import com.patmanak.contako.data.android.mapping.AndroidContactSnapshot
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.domain.repository.SaveValidationIssue

internal enum class AndroidCanonicalContactRejectionReason {
    IDENTITY_SHAPE,
    BLANK_ACCOUNT_ID,
    ACCOUNT_SCOPE_MISMATCH,
    BLANK_CONTACT_ID,
    BLANK_VALUE_ID,
    DUPLICATE_VALUE_ID,
    NEGATIVE_VALUE_ORDER,
    DUPLICATE_VALUE_ORDER,
    LOCAL_ID_GENERATION_FAILED,
    OTHER_SINGLE_ISSUE,
    MULTIPLE_ISSUES,
}

internal fun interface AndroidCanonicalContactRejectionObserver {
    fun onRejected(reason: AndroidCanonicalContactRejectionReason)
}

/**
 * Atomic bridge from a validated Android observation to canonical state and the projection ledger.
 *
 * The ledger owns the outer Room transaction. Any non-applied canonical mutation is converted to a
 * private control-flow exception so the ledger revision claim and every canonical/outbox write are
 * rolled back together. Diagnostics contain categories only.
 */
internal class RoomAndroidObservationCommitter(
    private val account: AccountScope,
    private val ledger: RoomAndroidProjectionLedger,
    private val canonicalMutations: RoomAndroidCanonicalMutationStore,
    private val rejectionObserver: AndroidCanonicalContactRejectionObserver =
        AndroidCanonicalContactRejectionObserver { },
) {
    suspend fun commitContactDelta(
        ledgerSnapshot: AndroidProjectionLedgerSnapshot,
        locator: AndroidRawContactLocator,
        expectedCanonicalRevision: Long,
        delta: AndroidCanonicalDelta,
        observedSnapshot: AndroidContactSnapshot,
    ): AndroidObservationCommitResult {
        if (
            ledgerSnapshot.canonicalContactId != delta.contact.id ||
            delta.contact.accountId != account.value ||
            observedSnapshot.canonicalContactId != ledgerSnapshot.canonicalContactId
        ) {
            notifyRejected(AndroidCanonicalContactRejectionReason.IDENTITY_SHAPE)
            return AndroidObservationCommitResult.CanonicalRejected
        }
        return commit {
            ledger.ingestObservation(
                account = account,
                canonicalContactId = ledgerSnapshot.canonicalContactId,
                expectedRevision = ledgerSnapshot.revision,
                locator = locator,
                observedFingerprint = delta.observedFingerprint,
                deleted = false,
                resultingCanonicalProjectionFingerprint = delta.resultingCanonicalFingerprint,
                observedSnapshot = observedSnapshot,
            ) {
                if (!delta.hasChanges) {
                    if (!canonicalMutations.isCurrentRevision(
                            expectedCanonicalRevision,
                            ledgerSnapshot.canonicalContactId,
                        )
                    ) {
                        throw CanonicalMutationAbort(CanonicalAbort.STALE)
                    }
                    return@ingestObservation
                }
                when (
                    val mutation = canonicalMutations.applyContactDelta(
                        expectedCanonicalRevision = expectedCanonicalRevision,
                        contact = delta.contact,
                    )
                ) {
                    is AndroidCanonicalMutationResult.Applied -> Unit
                    AndroidCanonicalMutationResult.Stale -> throw CanonicalMutationAbort(CanonicalAbort.STALE)
                    is AndroidCanonicalMutationResult.Rejected -> {
                        notifyRejected(classifyRejection(mutation.issues))
                        throw CanonicalMutationAbort(CanonicalAbort.REJECTED)
                    }
                }
            }
        }
    }

    private fun classifyRejection(issues: Set<SaveValidationIssue>): AndroidCanonicalContactRejectionReason {
        if (issues.size != 1) return AndroidCanonicalContactRejectionReason.MULTIPLE_ISSUES
        return when (issues.single()) {
            SaveValidationIssue.BLANK_ACCOUNT_ID -> AndroidCanonicalContactRejectionReason.BLANK_ACCOUNT_ID
            SaveValidationIssue.ACCOUNT_SCOPE_MISMATCH ->
                AndroidCanonicalContactRejectionReason.ACCOUNT_SCOPE_MISMATCH
            SaveValidationIssue.BLANK_CONTACT_ID -> AndroidCanonicalContactRejectionReason.BLANK_CONTACT_ID
            SaveValidationIssue.BLANK_VALUE_ID -> AndroidCanonicalContactRejectionReason.BLANK_VALUE_ID
            SaveValidationIssue.DUPLICATE_VALUE_ID -> AndroidCanonicalContactRejectionReason.DUPLICATE_VALUE_ID
            SaveValidationIssue.NEGATIVE_VALUE_ORDER ->
                AndroidCanonicalContactRejectionReason.NEGATIVE_VALUE_ORDER
            SaveValidationIssue.DUPLICATE_VALUE_ORDER ->
                AndroidCanonicalContactRejectionReason.DUPLICATE_VALUE_ORDER
            SaveValidationIssue.LOCAL_ID_GENERATION_FAILED ->
                AndroidCanonicalContactRejectionReason.LOCAL_ID_GENERATION_FAILED
            else -> AndroidCanonicalContactRejectionReason.OTHER_SINGLE_ISSUE
        }
    }

    private fun notifyRejected(reason: AndroidCanonicalContactRejectionReason) {
        runCatching { rejectionObserver.onRejected(reason) }
    }

    suspend fun commitDeletion(
        ledgerSnapshot: AndroidProjectionLedgerSnapshot,
        locator: AndroidRawContactLocator,
        expectedCanonicalRevision: Long,
    ): AndroidObservationCommitResult = commit {
        ledger.ingestObservation(
            account = account,
            canonicalContactId = ledgerSnapshot.canonicalContactId,
            expectedRevision = ledgerSnapshot.revision,
            locator = locator,
            observedFingerprint = null,
            deleted = true,
            resultingCanonicalProjectionFingerprint = null,
            observedSnapshot = null,
        ) {
            when (
                canonicalMutations.applyDeletion(
                    expectedCanonicalRevision = expectedCanonicalRevision,
                    contactId = ledgerSnapshot.canonicalContactId,
                )
            ) {
                is AndroidCanonicalMutationResult.Applied -> Unit
                AndroidCanonicalMutationResult.Stale -> throw CanonicalMutationAbort(CanonicalAbort.STALE)
                is AndroidCanonicalMutationResult.Rejected ->
                    throw CanonicalMutationAbort(CanonicalAbort.REJECTED)
            }
        }
    }

    private suspend fun commit(
        operation: suspend () -> AndroidObservationResult,
    ): AndroidObservationCommitResult = try {
        when (val result = operation()) {
            is AndroidObservationResult.Applied -> AndroidObservationCommitResult.Applied(result)
            AndroidObservationResult.Stale -> AndroidObservationCommitResult.LedgerStale
            AndroidObservationResult.StaleProviderEpoch ->
                AndroidObservationCommitResult.StaleProviderEpoch
        }
    } catch (abort: CanonicalMutationAbort) {
        when (abort.category) {
            CanonicalAbort.STALE -> AndroidObservationCommitResult.CanonicalStale
            CanonicalAbort.REJECTED -> AndroidObservationCommitResult.CanonicalRejected
        }
    }
}

internal sealed interface AndroidObservationCommitResult {
    data class Applied(val observation: AndroidObservationResult.Applied) : AndroidObservationCommitResult
    data object LedgerStale : AndroidObservationCommitResult
    data object StaleProviderEpoch : AndroidObservationCommitResult
    data object CanonicalStale : AndroidObservationCommitResult
    data object CanonicalRejected : AndroidObservationCommitResult
}

private enum class CanonicalAbort { STALE, REJECTED }

private class CanonicalMutationAbort(
    val category: CanonicalAbort,
) : IllegalStateException("Android canonical observation aborted: ${category.name}")
