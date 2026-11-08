package com.patmanak.contako.data.sync

import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.GatewayContactHydrationCategory
import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.data.gateway.GatewayOutcome
import com.patmanak.contako.data.gateway.InventoryCursor
import com.patmanak.contako.data.gateway.ProtonContactInventoryGateway
import com.patmanak.contako.data.gateway.ProtonVerifiedContactCardGateway
import com.patmanak.contako.data.gateway.RemoteContactId
import com.patmanak.contako.data.gateway.ValidatedCompleteInventory
import com.patmanak.contako.data.gateway.VerifiedContactCard
import com.patmanak.contako.data.gateway.ContactInventorySnapshotAuthority
import com.patmanak.contako.data.gateway.ProtonContactExistenceGateway
import com.patmanak.contako.data.gateway.RemoteContactPresence
import com.patmanak.contako.data.proton.ContactInventoryPlan
import com.patmanak.contako.data.proton.PersistentContactInventoryPlanner
import com.patmanak.contako.data.proton.StaleContactInventoryPlan
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal data class CanonicalReconciliationReceipt(
    val hydrated: Set<RemoteContactId>,
    val labelsReconciled: Set<RemoteContactId>,
    val deletionsReconciled: Set<RemoteContactId>,
)

/** Local-only boundary. Returning success means the complete batch is already durable. */
internal fun interface RemoteCanonicalReconciliationStore {
    suspend fun identityRepairContacts(account: AccountScope): Set<RemoteContactId> = emptySet()

    suspend fun commit(
        account: AccountScope,
        plan: ContactInventoryPlan,
        hydratedCards: List<VerifiedContactCard>,
        labelsToReconcile: Set<RemoteContactId>,
        deletionsToReconcile: Set<RemoteContactId>,
    ): CanonicalReconciliationReceipt
}

internal fun interface RemoteContactBatchObserver {
    fun afterDurableBatch(hydratedCount: Int, labelCount: Int, deletionCount: Int)

    companion object {
        val NONE = RemoteContactBatchObserver { _, _, _ -> }
    }
}

/** Payload-free boundary for a remote-contact result that requires user or operator action. */
internal enum class RemoteContactActionRequiredBoundary { INVENTORY, PLANNING, HYDRATION, HYDRATED_IDENTITY, DELETION_CONFIRMATION }

internal fun interface RemoteContactActionRequiredObserver {
    fun onActionRequired(
        boundary: RemoteContactActionRequiredBoundary,
        category: GatewayFailureCategory?,
        hydrationCategory: GatewayContactHydrationCategory?,
    )
}

internal sealed interface RemoteContactStageResult {
    /** Healthy cards and the inventory cursor are durable; rejected cards remain durable obligations. */
    data class Partial(val deferredCount: Int, val retryWaitingCount: Int = 0,
        val ignoredCount: Int = 0) : RemoteContactStageResult {
        val actionRequiredCount: Int get() = deferredCount - retryWaitingCount - ignoredCount
        init { require(deferredCount > 0 && retryWaitingCount >= 0 && ignoredCount >= 0 && actionRequiredCount >= 0) }
    }
    data class Success(
        val inventoryCount: Int,
        val hydratedCount: Int,
        val labelOnlyCount: Int,
        val deletedCount: Int,
    ) : RemoteContactStageResult

    data class RetryWaiting(val category: GatewayFailureCategory) : RemoteContactStageResult
    data object ActionRequired : RemoteContactStageResult
    data object Cancelled : RemoteContactStageResult
    data object StalePlan : RemoteContactStageResult
    data object LocalPersistenceFailure : RemoteContactStageResult
}

/** Bounded pager with no hydration side effect. */
internal class BoundedContactInventoryReader(
    private val gateway: ProtonContactInventoryGateway,
) {
    suspend fun read(
        account: AccountScope,
        isCancellationRequested: () -> Boolean,
    ): GatewayOutcome<ValidatedCompleteInventory> {
        val pages = mutableListOf<com.patmanak.contako.data.gateway.ContactInventoryPage>()
        var cursor: InventoryCursor? = null
        repeat(MAX_PAGE_REQUESTS) {
            if (isCancellationRequested()) return GatewayOutcome.Failure(GatewayFailureCategory.CANCELLED)
            when (val page = gateway.page(account, cursor)) {
                is GatewayOutcome.Failure -> return page
                is GatewayOutcome.Success -> {
                    pages += page.value
                    cursor = page.value.nextCursor
                    if (cursor == null) {
                        return try {
                            GatewayOutcome.Success(ValidatedCompleteInventory.fromPages(pages))
                        } catch (_: IllegalArgumentException) {
                            GatewayOutcome.Failure(GatewayFailureCategory.MALFORMED_RESPONSE)
                        }
                    }
                }
            }
        }
        return GatewayOutcome.Failure(GatewayFailureCategory.MALFORMED_RESPONSE)
    }

    private companion object {
        const val MAX_PAGE_REQUESTS = 100_000
    }
}

/**
 * Inventory -> bounded hydration -> durable canonical commit -> checkpoint CAS. No mutation upload
 * occurs in this stage; the durable mutation orchestrator runs only after this current-remote check.
 */
internal enum class RemoteImportPhase {
    EVENT_READ_START, EVENT_READ_DONE, INVENTORY_READ_START, INVENTORY_READ_DONE,
    PLAN_START, PLAN_DONE, CARD_READ_START, CARD_READ_DONE, LOCAL_COMMIT_START,
    LOCAL_COMMIT_DONE, CHECKPOINT_COMMIT_START, CHECKPOINT_COMMIT_DONE,
    LOCAL_COMMIT_FAILED, RECEIPT_MISMATCH, CHECKPOINT_COMMIT_FAILED,
    LOCAL_INTENT_REVISION_MISMATCH, LOCAL_CREATION_UID_MISMATCH,
    LOCAL_PENDING_GROUP_EMAIL_REMAP_FAILED, LOCAL_CONTACT_PROJECTION_RECEIPT_MISMATCH,
    LOCAL_MEMBERSHIP_PROJECTION_RECEIPT_MISMATCH,
}

/** Fixed local invariant identity only; never carries a contact, payload or identifier. */
internal enum class LocalReconciliationInvariant(val phase: RemoteImportPhase) {
    INTENT_REVISION(RemoteImportPhase.LOCAL_INTENT_REVISION_MISMATCH),
    CREATION_UID(RemoteImportPhase.LOCAL_CREATION_UID_MISMATCH),
    PENDING_GROUP_EMAIL_REMAP(RemoteImportPhase.LOCAL_PENDING_GROUP_EMAIL_REMAP_FAILED),
    CONTACT_PROJECTION_RECEIPT(RemoteImportPhase.LOCAL_CONTACT_PROJECTION_RECEIPT_MISMATCH),
    MEMBERSHIP_PROJECTION_RECEIPT(RemoteImportPhase.LOCAL_MEMBERSHIP_PROJECTION_RECEIPT_MISMATCH),
}

internal class LocalReconciliationInvariantFailure(val invariant: LocalReconciliationInvariant) :
    IllegalStateException(invariant.name)

internal fun checkLocalReconciliationInvariant(condition: Boolean, invariant: LocalReconciliationInvariant) {
    if (!condition) throw LocalReconciliationInvariantFailure(invariant)
}

internal class IncrementalRemoteContactStage(
    inventoryGateway: ProtonContactInventoryGateway,
    private val cardGateway: ProtonVerifiedContactCardGateway,
    private val planner: PersistentContactInventoryPlanner,
    private val canonicalStore: RemoteCanonicalReconciliationStore,
    private val batchObserver: RemoteContactBatchObserver = RemoteContactBatchObserver.NONE,
    private val actionRequiredObserver: RemoteContactActionRequiredObserver =
        RemoteContactActionRequiredObserver { _, _, _ -> },
    private val existenceGateway: ProtonContactExistenceGateway = ProtonContactExistenceGateway { _, _ ->
        GatewayOutcome.Failure(GatewayFailureCategory.UNKNOWN)
    },
    private val eventsGateway: com.patmanak.contako.data.proton.ProtonContactEventsGateway? = null,
    private val hydrationBatchSize: Int = HYDRATION_BATCH_SIZE,
    private val hydrationReadConcurrency: Int = HYDRATION_READ_CONCURRENCY,
    private val importObserver: SyncPassStageObserver = SyncPassStageObserver { },
    private val obligations: RemoteHydrationObligationStore? = null,
) {
    var lastFailureCategory: GatewayFailureCategory? = null
        private set
    var lastExceptionCategory: SyncPassExceptionCategory? = null
        private set
    init {
        require(hydrationBatchSize in 1..HYDRATION_BATCH_SIZE)
        require(hydrationReadConcurrency in 1..HYDRATION_READ_CONCURRENCY)
    }
    private fun observe(phase: RemoteImportPhase) = runCatching { importObserver.onImportPhase(phase) }.let { }
    private fun observeFailure(error: Throwable) = runCatching {
        lastExceptionCategory = syncPassExceptionCategory(error)
        if (error is LocalReconciliationInvariantFailure) importObserver.onImportPhase(error.invariant.phase)
        importObserver.onException(syncPassExceptionCategory(error))
    }.let { }
    private val inventoryReader = BoundedContactInventoryReader(inventoryGateway)

    suspend fun run(
        account: AccountScope,
        forceHydration: Boolean = false,
        isCancellationRequested: () -> Boolean = { false },
    ): RemoteContactStageResult {
        lastFailureCategory = null
        lastExceptionCategory = null
        val cursor = try { planner.eventCursor(account) } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            observeFailure(error)
            return RemoteContactStageResult.LocalPersistenceFailure
        }
        if (isCancellationRequested()) return RemoteContactStageResult.Cancelled
        observe(RemoteImportPhase.EVENT_READ_START)
        val events = when (val result = eventsGateway?.read(account, cursor)) {
            null -> null
            is GatewayOutcome.Success -> result.value
            is GatewayOutcome.Failure -> return mapFailure(RemoteContactActionRequiredBoundary.INVENTORY, result,
                GatewayDiagnosticBoundary.EVENT_READ)
        }
        observe(RemoteImportPhase.EVENT_READ_DONE)
        observe(RemoteImportPhase.INVENTORY_READ_START)
        val inventory = when (val result = inventoryReader.read(account, isCancellationRequested)) {
            is GatewayOutcome.Success -> result.value
            is GatewayOutcome.Failure -> return mapFailure(RemoteContactActionRequiredBoundary.INVENTORY, result)
        }
        observe(RemoteImportPhase.INVENTORY_READ_DONE)
        if (isCancellationRequested()) return RemoteContactStageResult.Cancelled
        observe(RemoteImportPhase.PLAN_START)
        val plan = try {
            planner.plan(account, inventory, forceHydration, events,
                canonicalStore.identityRepairContacts(account) + obligations?.pending(account).orEmpty())
        } catch (_: StaleContactInventoryPlan) {
            return RemoteContactStageResult.StalePlan
        } catch (_: IllegalArgumentException) {
            return actionRequired(RemoteContactActionRequiredBoundary.PLANNING)
        }
        observe(RemoteImportPhase.PLAN_DONE)
        if (inventory.snapshotAuthority == ContactInventorySnapshotAuthority.COMPLETE_PUBLIC_DIRECTORY) {
            for (id in plan.deleted.sortedBy { it.value }) {
                if (isCancellationRequested()) return RemoteContactStageResult.Cancelled
                when (val result = existenceGateway.check(account, id)) {
                    is GatewayOutcome.Failure -> return mapFailure(RemoteContactActionRequiredBoundary.DELETION_CONFIRMATION, result)
                    is GatewayOutcome.Success -> if (result.value != RemoteContactPresence.CONFIRMED_ABSENT) {
                        return RemoteContactStageResult.StalePlan
                    }
                }
            }
        }
        val durableHydrations = mutableSetOf<RemoteContactId>()
        val deferredHydrations = mutableSetOf<RemoteContactId>()
        val retryHydrations = mutableSetOf<RemoteContactId>()
        val ignoredHydrations = mutableSetOf<RemoteContactId>()
        val baselines = plan.nextCheckpoint.entries.associateBy { it.id }
        val knownEmailIds = baselines.values.flatMap { baseline -> baseline.emailGroupMemberships.map { it.emailId } }.toSet()
        val unknownEmailEvent = events?.changedEmails.orEmpty().any { it !in knownEmailIds }
        val readable = mutableListOf<RemoteContactId>()
        for (id in plan.hydrate.sortedBy { it.value }) {
            if (isCancellationRequested()) return RemoteContactStageResult.Cancelled
            val baseline = baselines.getValue(id)
            val eventChanged = forceHydration || events?.refreshAll == true || unknownEmailEvent ||
                id in events?.changedContacts.orEmpty() ||
                baseline.emailGroupMemberships.any { it.emailId in events?.changedEmails.orEmpty() }
            when (obligations?.readDisposition(account, baseline, eventChanged) ?: RemoteHydrationReadDisposition.READ) {
                RemoteHydrationReadDisposition.READ -> readable += id
                RemoteHydrationReadDisposition.RETRY_WAITING -> { deferredHydrations += id; retryHydrations += id }
                RemoteHydrationReadDisposition.ACTION_REQUIRED -> deferredHydrations += id
                RemoteHydrationReadDisposition.IGNORED -> { deferredHydrations += id; ignoredHydrations += id }
            }
        }
        val hydrationBatches = readable.chunked(hydrationBatchSize)
        val durableLabels = mutableSetOf<RemoteContactId>()
        val durableDeletions = mutableSetOf<RemoteContactId>()
        var sharedReadFailure: GatewayOutcome.Failure? = null
        var consecutiveServiceFailures = 0
        val batchCount = maxOf(1, hydrationBatches.size)
        repeat(batchCount) { batchIndex ->
            if (isCancellationRequested()) return RemoteContactStageResult.Cancelled
            val cards = ArrayList<VerifiedContactCard>(hydrationBatchSize)
            val hydrationBatch = hydrationBatches.getOrNull(batchIndex).orEmpty()
            hydrationBatch.chunked(hydrationReadConcurrency).forEach { window ->
                if (isCancellationRequested()) return RemoteContactStageResult.Cancelled
                val readFailureAtWindowStart = sharedReadFailure
                val hydratedWindow = coroutineScope {
                    window.map { contactId ->
                        async {
                            contactId to if (isCancellationRequested()) {
                                GatewayOutcome.Failure(GatewayFailureCategory.CANCELLED)
                            } else if (readFailureAtWindowStart != null) {
                                readFailureAtWindowStart
                            } else {
                                observe(RemoteImportPhase.CARD_READ_START)
                                cardGateway.fetch(account, contactId).also { observe(RemoteImportPhase.CARD_READ_DONE) }
                            }
                        }
                    }.awaitAll()
                }
                if (isCancellationRequested()) return RemoteContactStageResult.Cancelled
                hydratedWindow.forEach { (contactId, hydrated) ->
                    when (hydrated) {
                        is GatewayOutcome.Failure -> {
                            if (obligations == null || hydrated.category !in ISOLATABLE_CARD_FAILURES &&
                                !hydrated.category.isTransientHydrationFailure) {
                                return mapFailure(RemoteContactActionRequiredBoundary.HYDRATION, hydrated)
                            }
                            // The obligation MUST precede cursor acknowledgement. Never import an unverified card.
                            if (readFailureAtWindowStart != null) {
                                obligations.postpone(account, baselines.getValue(contactId), hydrated)
                            } else obligations.defer(account, baselines.getValue(contactId), hydrated)
                            deferredHydrations += contactId
                            if (hydrated.category.isTransientHydrationFailure) retryHydrations += contactId
                            mapFailure(RemoteContactActionRequiredBoundary.HYDRATION, hydrated)
                            if (readFailureAtWindowStart == null) {
                                consecutiveServiceFailures = if (hydrated.category in SHARED_SERVICE_FAILURES)
                                    consecutiveServiceFailures + 1 else 0
                                // A single timeout can be card-specific. Repeated transport/service
                                // failures indicate an outage: finish only this already-started window
                                // and durably postpone the unread cards without spending their attempts.
                                if (hydrated.category == GatewayFailureCategory.RATE_LIMITED ||
                                    consecutiveServiceFailures >= SERVICE_FAILURE_CIRCUIT_THRESHOLD) {
                                    val previous = sharedReadFailure
                                    val next = if (hydrated.category == GatewayFailureCategory.RATE_LIMITED)
                                        hydrated else previous ?: hydrated
                                    sharedReadFailure = next.copy(retryAfterMillis = maxOf(
                                        previous?.retryAfterMillis ?: 0L, hydrated.retryAfterMillis ?: 0L))
                                }
                            }
                        }
                        is GatewayOutcome.Success -> {
                            consecutiveServiceFailures = 0
                            if (hydrated.value.id != contactId) {
                                if (obligations == null) return actionRequired(RemoteContactActionRequiredBoundary.HYDRATED_IDENTITY)
                                obligations.defer(account, baselines.getValue(contactId),
                                    GatewayOutcome.Failure(GatewayFailureCategory.MALFORMED_RESPONSE))
                                deferredHydrations += contactId
                                actionRequired(RemoteContactActionRequiredBoundary.HYDRATED_IDENTITY)
                            } else {
                                cards += hydrated.value
                            }
                        }
                    }
                }
            }
            if (isCancellationRequested()) return RemoteContactStageResult.Cancelled
            val isFinalBatch = batchIndex == batchCount - 1
            val labels = if (isFinalBatch) plan.labelOnly else emptySet()
            val deletions = if (isFinalBatch) plan.deleted else emptySet()
            observe(RemoteImportPhase.LOCAL_COMMIT_START)
            val receipt = try {
                canonicalStore.commit(account, plan, cards, labels, deletions)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: LocalReconciliationInvariantFailure) {
                observe(RemoteImportPhase.LOCAL_COMMIT_FAILED)
                observeFailure(error)
                if (obligations == null || error.invariant != LocalReconciliationInvariant.PENDING_GROUP_EMAIL_REMAP)
                    return RemoteContactStageResult.LocalPersistenceFailure
                // The failed atomic batch was rolled back. Attribute only the known per-card remap
                // refusal; storage, ownership, revision and receipt failures keep their closed outcome.
                try {
                    val committed = mutableSetOf<RemoteContactId>()
                    for (card in cards) {
                        if (isCancellationRequested()) return RemoteContactStageResult.Cancelled
                        try {
                            val single = canonicalStore.commit(account, plan, listOf(card), emptySet(), emptySet())
                            check(single.hydrated == setOf(card.id) && single.labelsReconciled.isEmpty() &&
                                single.deletionsReconciled.isEmpty())
                            committed += card.id
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (refusal: LocalReconciliationInvariantFailure) {
                            if (refusal.invariant != LocalReconciliationInvariant.PENDING_GROUP_EMAIL_REMAP) throw refusal
                            observeFailure(refusal)
                            val failure = GatewayOutcome.Failure(GatewayFailureCategory.CONFLICT)
                            obligations.defer(account, baselines.getValue(card.id), failure)
                            deferredHydrations += card.id
                            mapFailure(RemoteContactActionRequiredBoundary.HYDRATION, failure)
                        }
                    }
                    val rest = if (labels.isNotEmpty() || deletions.isNotEmpty())
                        canonicalStore.commit(account, plan, emptyList(), labels, deletions)
                        else CanonicalReconciliationReceipt(emptySet(), emptySet(), emptySet())
                    check(rest.hydrated.isEmpty())
                    CanonicalReconciliationReceipt(committed, rest.labelsReconciled, rest.deletionsReconciled)
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    observeFailure(failure)
                    return RemoteContactStageResult.LocalPersistenceFailure
                }
            } catch (error: Exception) {
                observe(RemoteImportPhase.LOCAL_COMMIT_FAILED)
                observeFailure(error)
                return RemoteContactStageResult.LocalPersistenceFailure
            }
            observe(RemoteImportPhase.LOCAL_COMMIT_DONE)
            if (receipt.hydrated != cards.map(VerifiedContactCard::id).toSet() - deferredHydrations ||
                receipt.labelsReconciled != labels || receipt.deletionsReconciled != deletions
            ) {
                observe(RemoteImportPhase.RECEIPT_MISMATCH)
                return RemoteContactStageResult.LocalPersistenceFailure
            }
            durableHydrations += receipt.hydrated
            obligations?.resolved(account, receipt.hydrated + receipt.deletionsReconciled)
            durableLabels += receipt.labelsReconciled
            durableDeletions += receipt.deletionsReconciled
            batchObserver.afterDurableBatch(receipt.hydrated.size, labels.size, deletions.size)
        }
        val completed = try {
            plan.completedAfterDurableReconciliation(
                hydrated = durableHydrations,
                labelReconciled = durableLabels,
                deletionsReconciled = durableDeletions,
                canonicalPersistenceCommitted = true,
                deferredDurableHydrations = deferredHydrations,
            )
        } catch (_: IllegalArgumentException) {
            return RemoteContactStageResult.LocalPersistenceFailure
        }
        if (isCancellationRequested()) return RemoteContactStageResult.Cancelled
        observe(RemoteImportPhase.CHECKPOINT_COMMIT_START)
        try {
            planner.commit(account, completed)
        } catch (_: StaleContactInventoryPlan) {
            return RemoteContactStageResult.StalePlan
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            observe(RemoteImportPhase.CHECKPOINT_COMMIT_FAILED)
            observeFailure(error)
            return RemoteContactStageResult.LocalPersistenceFailure
        }
        observe(RemoteImportPhase.CHECKPOINT_COMMIT_DONE)
        if (deferredHydrations.isNotEmpty()) return RemoteContactStageResult.Partial(
            deferredHydrations.size, retryHydrations.size, ignoredHydrations.size)
        return RemoteContactStageResult.Success(
            inventoryCount = inventory.totalCount,
            hydratedCount = plan.hydrate.size,
            labelOnlyCount = plan.labelOnly.size,
            deletedCount = plan.deleted.size,
        )
    }

    private companion object {
        const val HYDRATION_BATCH_SIZE = 25
        const val HYDRATION_READ_CONCURRENCY = 10
        const val SERVICE_FAILURE_CIRCUIT_THRESHOLD = 3
        val SHARED_SERVICE_FAILURES = setOf(GatewayFailureCategory.NETWORK_UNAVAILABLE,
            GatewayFailureCategory.TIMEOUT, GatewayFailureCategory.REMOTE_SERVICE_FAILURE,
            GatewayFailureCategory.UNKNOWN)
        val ISOLATABLE_CARD_FAILURES = setOf(GatewayFailureCategory.MALFORMED_RESPONSE,
            GatewayFailureCategory.VALIDATION_REJECTED, GatewayFailureCategory.CRYPTOGRAPHIC_VERIFICATION_FAILED,
            GatewayFailureCategory.NOT_FOUND, GatewayFailureCategory.CONFLICT)
    }

    private fun mapFailure(
        boundary: RemoteContactActionRequiredBoundary,
        failure: GatewayOutcome.Failure,
        diagnosticBoundary: GatewayDiagnosticBoundary = when (boundary) {
            RemoteContactActionRequiredBoundary.INVENTORY -> GatewayDiagnosticBoundary.INVENTORY_READ
            RemoteContactActionRequiredBoundary.DELETION_CONFIRMATION -> GatewayDiagnosticBoundary.DELETION_CHECK
            else -> GatewayDiagnosticBoundary.CARD_READ
        },
    ): RemoteContactStageResult = failure.category.toStageResult().also { result ->
        lastFailureCategory = failure.category
        runCatching { importObserver.onGatewayFailure(diagnosticBoundary, failure) }
        if (result == RemoteContactStageResult.ActionRequired) {
            runCatching {
                actionRequiredObserver.onActionRequired(
                    boundary,
                    failure.category,
                    failure.contactHydrationCategory,
                )
            }
        }
    }

    private fun actionRequired(
        boundary: RemoteContactActionRequiredBoundary,
    ): RemoteContactStageResult.ActionRequired = RemoteContactStageResult.ActionRequired.also {
        runCatching { actionRequiredObserver.onActionRequired(boundary, null, null) }
    }
}

private fun GatewayFailureCategory.toStageResult(): RemoteContactStageResult = when (this) {
    GatewayFailureCategory.CANCELLED -> RemoteContactStageResult.Cancelled
    GatewayFailureCategory.NETWORK_UNAVAILABLE,
    GatewayFailureCategory.TIMEOUT,
    GatewayFailureCategory.RATE_LIMITED,
    GatewayFailureCategory.REMOTE_SERVICE_FAILURE,
    GatewayFailureCategory.UNKNOWN,
    -> RemoteContactStageResult.RetryWaiting(this)
    else -> RemoteContactStageResult.ActionRequired
}
