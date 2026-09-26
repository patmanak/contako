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
internal enum class RemoteContactActionRequiredBoundary { INVENTORY, PLANNING, HYDRATION, HYDRATED_IDENTITY }

internal fun interface RemoteContactActionRequiredObserver {
    fun onActionRequired(
        boundary: RemoteContactActionRequiredBoundary,
        category: GatewayFailureCategory?,
        hydrationCategory: GatewayContactHydrationCategory?,
    )
}

internal sealed interface RemoteContactStageResult {
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
internal class IncrementalRemoteContactStage(
    inventoryGateway: ProtonContactInventoryGateway,
    private val cardGateway: ProtonVerifiedContactCardGateway,
    private val planner: PersistentContactInventoryPlanner,
    private val canonicalStore: RemoteCanonicalReconciliationStore,
    private val batchObserver: RemoteContactBatchObserver = RemoteContactBatchObserver.NONE,
    private val actionRequiredObserver: RemoteContactActionRequiredObserver =
        RemoteContactActionRequiredObserver { _, _, _ -> },
) {
    private val inventoryReader = BoundedContactInventoryReader(inventoryGateway)

    suspend fun run(
        account: AccountScope,
        forceHydration: Boolean = false,
        isCancellationRequested: () -> Boolean = { false },
    ): RemoteContactStageResult {
        val inventory = when (val result = inventoryReader.read(account, isCancellationRequested)) {
            is GatewayOutcome.Success -> result.value
            is GatewayOutcome.Failure -> return mapFailure(RemoteContactActionRequiredBoundary.INVENTORY, result)
        }
        if (isCancellationRequested()) return RemoteContactStageResult.Cancelled
        val plan = try {
            planner.plan(account, inventory, forceHydration)
        } catch (_: IllegalArgumentException) {
            return actionRequired(RemoteContactActionRequiredBoundary.PLANNING)
        }
        val hydrationBatches = plan.hydrate.sortedBy { it.value }.chunked(HYDRATION_BATCH_SIZE)
        val durableHydrations = mutableSetOf<RemoteContactId>()
        val durableLabels = mutableSetOf<RemoteContactId>()
        val durableDeletions = mutableSetOf<RemoteContactId>()
        val batchCount = maxOf(1, hydrationBatches.size)
        repeat(batchCount) { batchIndex ->
            if (isCancellationRequested()) return RemoteContactStageResult.Cancelled
            val cards = ArrayList<VerifiedContactCard>(HYDRATION_BATCH_SIZE)
            val hydrationBatch = hydrationBatches.getOrNull(batchIndex).orEmpty()
            hydrationBatch.chunked(HYDRATION_READ_CONCURRENCY).forEach { window ->
                if (isCancellationRequested()) return RemoteContactStageResult.Cancelled
                val hydratedWindow = coroutineScope {
                    window.map { contactId ->
                        async {
                            contactId to if (isCancellationRequested()) {
                                GatewayOutcome.Failure(GatewayFailureCategory.CANCELLED)
                            } else {
                                cardGateway.fetch(account, contactId)
                            }
                        }
                    }.awaitAll()
                }
                if (isCancellationRequested()) return RemoteContactStageResult.Cancelled
                hydratedWindow.forEach { (contactId, hydrated) ->
                    when (hydrated) {
                        is GatewayOutcome.Failure -> return mapFailure(
                            RemoteContactActionRequiredBoundary.HYDRATION,
                            hydrated,
                        )
                        is GatewayOutcome.Success -> {
                            if (hydrated.value.id != contactId) {
                                return actionRequired(RemoteContactActionRequiredBoundary.HYDRATED_IDENTITY)
                            }
                            cards += hydrated.value
                        }
                    }
                }
            }
            if (isCancellationRequested()) return RemoteContactStageResult.Cancelled
            val isFinalBatch = batchIndex == batchCount - 1
            val labels = if (isFinalBatch) plan.labelOnly else emptySet()
            val deletions = if (isFinalBatch) plan.deleted else emptySet()
            val receipt = try {
                canonicalStore.commit(account, plan, cards, labels, deletions)
            } catch (_: Throwable) {
                return RemoteContactStageResult.LocalPersistenceFailure
            }
            if (receipt.hydrated != cards.map(VerifiedContactCard::id).toSet() ||
                receipt.labelsReconciled != labels || receipt.deletionsReconciled != deletions
            ) {
                return RemoteContactStageResult.LocalPersistenceFailure
            }
            durableHydrations += receipt.hydrated
            durableLabels += receipt.labelsReconciled
            durableDeletions += receipt.deletionsReconciled
            batchObserver.afterDurableBatch(cards.size, labels.size, deletions.size)
        }
        val completed = try {
            plan.completedAfterDurableReconciliation(
                hydrated = durableHydrations,
                labelReconciled = durableLabels,
                deletionsReconciled = durableDeletions,
                canonicalPersistenceCommitted = true,
            )
        } catch (_: IllegalArgumentException) {
            return RemoteContactStageResult.LocalPersistenceFailure
        }
        if (isCancellationRequested()) return RemoteContactStageResult.Cancelled
        try {
            planner.commit(account, completed)
        } catch (_: StaleContactInventoryPlan) {
            return RemoteContactStageResult.StalePlan
        } catch (_: Throwable) {
            return RemoteContactStageResult.LocalPersistenceFailure
        }
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
    }

    private fun mapFailure(
        boundary: RemoteContactActionRequiredBoundary,
        failure: GatewayOutcome.Failure,
    ): RemoteContactStageResult = failure.category.toStageResult().also { result ->
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
