package com.patmanak.contako.data.sync

import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.data.gateway.GatewayOutcome

class DurableMutationCommand(
    internal val accountId: String,
    internal val aggregateType: String,
    internal val aggregateId: String,
    val revision: Long,
    val operation: RemoteMutationOperation,
    val attemptCount: Int,
    val nextEligibleEpochMillis: Long,
    val requiresReconciliation: Boolean,
) {
    init {
        require(accountId.isNotBlank())
        require(aggregateType.isNotBlank())
        require(aggregateId.isNotBlank())
        require(revision > 0)
        require(attemptCount >= 0)
    }

    override fun toString(): String =
        "DurableMutationCommand(REDACTED, revision=$revision, operation=$operation, " +
            "attemptCount=$attemptCount, requiresReconciliation=$requiresReconciliation)"
}

class RemoteMutationAcknowledgement(
    internal val remoteIdentity: String?,
    internal val remoteVersion: String?,
    val fullyConverged: Boolean = true,
    val nextOperation: RemoteMutationOperation? = null,
    internal val emailIdsByValueId: Map<String, String>? = null,
) {
    init {
        require(fullyConverged == (nextOperation == null))
    }
    override fun toString(): String = "RemoteMutationAcknowledgement(REDACTED)"
}

sealed interface MutationPreparation {
    data object UploadAllowed : MutationPreparation
    /** Account contact writes must finish before dependent assignments can run. */
    data object WaitingForDependencies : MutationPreparation
    class AlreadyApplied(val acknowledgement: RemoteMutationAcknowledgement) : MutationPreparation {
        override fun toString(): String = "MutationPreparation.AlreadyApplied(REDACTED)"
    }

    /** The boundary has already made the remote winner durable in canonical storage. */
    data object RemoteWinnerCommitted : MutationPreparation
    /** Canonical adoption and outbox removal were committed atomically by preparation. */
    data object ResolvedWithoutUpload : MutationPreparation
    class ActionRequired(val reason: MutationPreparationActionRequiredReason) : MutationPreparation {
        override fun toString(): String = "MutationPreparation.ActionRequired(reason=$reason)"
    }
}

/** Closed local causes only; no aggregate identity or payload may cross this boundary. */
enum class MutationPreparationActionRequiredReason {
    AGGREGATE_MISSING,
    REVISION_MISMATCH,
    RECONCILIATION_REMOTE_IDENTITY_MISSING,
    GROUP_REMOTE_IDENTITY_MISSING,
    GROUP_MEMBER_REMOTE_EMAIL_IDENTITY_MISSING,
    GROUP_MEMBER_LOCAL_EMAIL_MISSING,
    GROUP_MEMBER_DUPLICATE_EMAIL_IDENTITY_MISSING,
    UNSPECIFIED,
    REMOTE_UPDATE_CONFLICT,
}

enum class MutationActionRequiredSource { PREPARATION_FAILURE, PREPARATION_DECISION, UPLOAD_FAILURE }

/** Payload-free terminal diagnostic emitted only when automatic mutation processing stops. */
data class MutationActionRequiredDiagnostic(
    val source: MutationActionRequiredSource,
    val operation: RemoteMutationOperation,
    val failureCategory: GatewayFailureCategory?,
    val preparationReason: MutationPreparationActionRequiredReason?,
) {
    init {
        require((source == MutationActionRequiredSource.PREPARATION_DECISION) == (preparationReason != null))
        require((source != MutationActionRequiredSource.PREPARATION_DECISION) == (failureCategory != null))
    }
}

fun interface MutationActionRequiredObserver {
    fun onActionRequired(diagnostic: MutationActionRequiredDiagnostic)
}

/** Durable local lifecycle seam; its Room implementation uses revision compare-and-set. */
interface MutationExecutionStore {
    suspend fun remaining(accountId: String): MutationRemainingWork = MutationRemainingWork()
    suspend fun recoverInterrupted(accountId: String, nowEpochMillis: Long): Int
    suspend fun eligible(accountId: String, nowEpochMillis: Long, limit: Int): List<DurableMutationCommand>
    suspend fun claim(command: DurableMutationCommand, nowEpochMillis: Long): Boolean
    suspend fun recordFailure(
        command: DurableMutationCommand,
        category: GatewayFailureCategory,
        decision: RetryDecision,
    ): Boolean

    suspend fun acknowledgeAndFinish(
        command: DurableMutationCommand,
        acknowledgement: RemoteMutationAcknowledgement,
    ): Boolean

    suspend fun supersedeAfterRemoteWinner(command: DurableMutationCommand): Boolean
    suspend fun continueAfterPartialProgress(
        command: DurableMutationCommand,
        acknowledgement: RemoteMutationAcknowledgement,
        nowEpochMillis: Long,
    ): Boolean
}

data class MutationRemainingWork(val actionsRequired: Int = 0, val pending: Int = 0,
    val failureCategories: Set<GatewayFailureCategory> = emptySet()) {
    init { require(actionsRequired >= 0 && pending >= 0) }
}

/**
 * Mandatory remote-check boundary. It MUST make no write. A remote winner is committed durably by
 * this boundary before [MutationPreparation.RemoteWinnerCommitted] is returned.
 */
fun interface MutationPreparationGateway {
    suspend fun prepare(command: DurableMutationCommand): GatewayOutcome<MutationPreparation>
}

/** One invocation is exactly one remote write attempt and performs no internal replay. */
fun interface MutationUploadGateway {
    suspend fun upload(command: DurableMutationCommand): GatewayOutcome<RemoteMutationAcknowledgement>
}

data class MutationDrainResult(
    val examined: Int,
    val uploaded: Int,
    val reconciledWithoutUpload: Int,
    val retryWaiting: Int,
    val actionRequired: Int,
    val progressPending: Int,
    val cancelled: Boolean,
    val failureCategories: Set<GatewayFailureCategory> = emptySet(),
)

/** Serial, bounded durable mutation drain used by every synchronization entry point. */
class DurableMutationOrchestrator(
    private val store: MutationExecutionStore,
    private val preparationGateway: MutationPreparationGateway,
    private val uploadGateway: MutationUploadGateway,
    private val retryPolicy: MutationRetryPolicy = MutationRetryPolicy(),
    private val batchLimit: Int = 100,
    private val actionRequiredObserver: MutationActionRequiredObserver = MutationActionRequiredObserver { },
) {
    init {
        require(batchLimit in 1..100)
    }

    /**
     * Called under the account runner's serialization before remote reconciliation. A create
     * may have reached Proton before the process stopped, so its durable ambiguity must be
     * restored before hydration tries to match an unbound local creation by UID.
     */
    suspend fun recoverInterrupted(accountId: String, nowEpochMillis: Long): Int {
        require(accountId.isNotBlank())
        return store.recoverInterrupted(accountId, nowEpochMillis)
    }

    suspend fun drain(
        accountId: String,
        nowEpochMillis: Long,
        isCancellationRequested: () -> Boolean = { false },
    ): MutationDrainResult {
        require(accountId.isNotBlank())
        recoverInterrupted(accountId, nowEpochMillis)
        val eligible = store.eligible(accountId, nowEpochMillis, batchLimit)
        var examined = 0
        var uploaded = 0
        var reconciled = 0
        var retryWaiting = 0
        var actionRequired = 0
        var progressPending = 0
        val failureCategories = mutableSetOf<GatewayFailureCategory>()
        suspend fun result(cancelled: Boolean = false): MutationDrainResult {
            val remaining = if (cancelled) MutationRemainingWork() else store.remaining(accountId)
            return MutationDrainResult(examined, uploaded, reconciled, retryWaiting,
                maxOf(actionRequired, remaining.actionsRequired), maxOf(progressPending, remaining.pending),
                cancelled, failureCategories + remaining.failureCategories)
        }

        for (command in eligible) {
            if (isCancellationRequested()) {
                return result(cancelled = true)
            }
            if (!store.claim(command, nowEpochMillis)) continue
            examined++
            if (isCancellationRequested()) {
                store.recordFailure(command, GatewayFailureCategory.CANCELLED, RetryDecision.Cancelled)
                return result(cancelled = true)
            }

            when (val preparation = preparationGateway.prepare(command)) {
                is GatewayOutcome.Failure -> {
                    failureCategories += preparation.category
                    val decision = retryPolicy.decide(
                        preparation.category,
                        command.operation,
                        command.attemptCount,
                        nowEpochMillis,
                        preparation.retryAfterMillis,
                    )
                    store.recordFailure(command, preparation.category, decision)
                    when (decision) {
                        is RetryDecision.RetryAt -> retryWaiting++
                        RetryDecision.ActionRequired -> {
                            actionRequired++
                            observeActionRequired(
                                MutationActionRequiredSource.PREPARATION_FAILURE,
                                command,
                                failureCategory = preparation.category,
                            )
                        }
                        RetryDecision.Cancelled -> return result(cancelled = true)
                        RetryDecision.DeleteConverged -> reconciled++
                    }
                    // Once the account/session is rejected, no later account write is safe.
                    // Its intent remains untouched; the failed command was already persisted.
                    if (preparation.category in ACCOUNT_REAUTHENTICATION_FAILURES) return result()
                }
                is GatewayOutcome.Success -> when (val decision = preparation.value) {
                    MutationPreparation.UploadAllowed -> {
                        if (isCancellationRequested()) {
                            store.recordFailure(command, GatewayFailureCategory.CANCELLED, RetryDecision.Cancelled)
                            return result(cancelled = true)
                        }
                        when (val upload = uploadGateway.upload(command)) {
                            is GatewayOutcome.Success -> {
                                if (upload.value.fullyConverged) {
                                    if (store.acknowledgeAndFinish(command, upload.value)) uploaded++
                                } else if (store.continueAfterPartialProgress(command, upload.value, nowEpochMillis)) {
                                    progressPending++
                                }
                            }
                            is GatewayOutcome.Failure -> {
                                failureCategories += upload.category
                                val retry = retryPolicy.decide(
                                    upload.category,
                                    command.operation,
                                    command.attemptCount,
                                    nowEpochMillis,
                                    upload.retryAfterMillis,
                                )
                                store.recordFailure(command, upload.category, retry)
                                when (retry) {
                                    is RetryDecision.RetryAt -> retryWaiting++
                                    RetryDecision.ActionRequired -> {
                                        actionRequired++
                                        observeActionRequired(
                                            MutationActionRequiredSource.UPLOAD_FAILURE,
                                            command,
                                            failureCategory = upload.category,
                                        )
                                    }
                                    RetryDecision.Cancelled -> return result(cancelled = true)
                                    RetryDecision.DeleteConverged -> reconciled++
                                }
                                if (upload.category in ACCOUNT_REAUTHENTICATION_FAILURES) return result()
                            }
                        }
                    }
                    is MutationPreparation.AlreadyApplied -> {
                        if (decision.acknowledgement.fullyConverged) {
                            if (store.acknowledgeAndFinish(command, decision.acknowledgement)) reconciled++
                        } else if (
                            store.continueAfterPartialProgress(command, decision.acknowledgement, nowEpochMillis)
                        ) {
                            progressPending++
                        }
                    }
                    MutationPreparation.RemoteWinnerCommitted -> {
                        if (store.supersedeAfterRemoteWinner(command)) reconciled++
                    }
                    MutationPreparation.ResolvedWithoutUpload -> reconciled++
                    MutationPreparation.WaitingForDependencies -> {
                        // Release the claim without spending the failure/retry budget. The next
                        // bounded drain rechecks the dependency; no remote write was attempted.
                        store.recordFailure(command, GatewayFailureCategory.CANCELLED, RetryDecision.Cancelled)
                        progressPending++
                    }
                    is MutationPreparation.ActionRequired -> {
                        failureCategories += GatewayFailureCategory.CONFLICT
                        store.recordFailure(
                            command,
                            GatewayFailureCategory.CONFLICT,
                            RetryDecision.ActionRequired,
                        )
                        actionRequired++
                        observeActionRequired(
                            MutationActionRequiredSource.PREPARATION_DECISION,
                            command,
                            preparationReason = decision.reason,
                        )
                    }
                }
            }
        }
        return result()
    }

    private fun observeActionRequired(
        source: MutationActionRequiredSource,
        command: DurableMutationCommand,
        failureCategory: GatewayFailureCategory? = null,
        preparationReason: MutationPreparationActionRequiredReason? = null,
    ) {
        // Diagnostics must never alter mutation processing and receive only closed enums.
        runCatching {
            actionRequiredObserver.onActionRequired(
                MutationActionRequiredDiagnostic(
                    source = source,
                    operation = command.operation,
                    failureCategory = failureCategory,
                    preparationReason = preparationReason,
                ),
            )
        }
    }

    private companion object {
        val ACCOUNT_REAUTHENTICATION_FAILURES = setOf(GatewayFailureCategory.AUTHENTICATION_REQUIRED,
            GatewayFailureCategory.HUMAN_VERIFICATION_REQUIRED, GatewayFailureCategory.UNSUPPORTED_AUTHENTICATION,
            GatewayFailureCategory.CLIENT_IDENTITY_REJECTED, GatewayFailureCategory.ACCOUNT_ALREADY_CONNECTED)
    }
}
