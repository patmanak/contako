package com.patmanak.contako.data.sync

import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.domain.sync.AndroidAvailability
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf

/** Observes the existing runner gate; collecting this view never performs a provider read. */
internal class ObservedAndroidPreflight(
    private val owner: AccountScope,
    private val delegate: AndroidInteroperabilityPreflight,
) : AndroidInteroperabilityPreflight {
    private data class Observation(
        val availability: AndroidAvailability,
        val isCancelled: () -> Boolean = { false },
    )

    private val generation = AtomicLong()
    private val observation = MutableStateFlow(Observation(AndroidAvailability.UNKNOWN))

    /** A new pass cannot use an earlier pass's successful prerequisite as current evidence. */
    fun reset() {
        generation.incrementAndGet()
        observation.value = Observation(AndroidAvailability.UNKNOWN)
    }

    fun observe(accountInvalidated: Flow<Boolean>, lifecycleState: Flow<*> = flowOf(Unit)): Flow<AndroidAvailability> =
        combine(observation, accountInvalidated, lifecycleState) { current, invalidated, _ ->
            // The captured runner generation remains cancelled after reactivation. This also
            // rejects stale evidence if a StateFlow collector coalesces invalidation/reactivation.
            if (invalidated || current.isCancelled()) AndroidAvailability.UNKNOWN else current.availability
        }.distinctUntilChanged()

    override suspend fun check(account: AccountScope, isCancelled: () -> Boolean): AndroidInteroperabilityPreflightResult {
        if (account != owner) return delegate.check(account, isCancelled)
        val expectedGeneration = generation.incrementAndGet()
        observation.value = Observation(AndroidAvailability.UNKNOWN, isCancelled)
        // Preserve the caller's exception/cancellation semantics; availability stays unknown.
        val result = delegate.check(account, isCancelled)
        if (generation.get() == expectedGeneration && !isCancelled()) {
            val availability = when (result) {
                is AndroidInteroperabilityPreflightResult.Ready ->
                    if (result.context.account == owner) AndroidAvailability.READY else AndroidAvailability.UNAVAILABLE
                AndroidInteroperabilityPreflightResult.PermissionDenied -> AndroidAvailability.PERMISSION_REQUIRED
                AndroidInteroperabilityPreflightResult.ProviderUnavailable -> AndroidAvailability.UNAVAILABLE
                is AndroidInteroperabilityPreflightResult.RepairRequired -> when (result.reason) {
                    AndroidInteroperabilityRepairReason.DURABLE_ACCOUNT_CONTEXT_MISSING,
                    AndroidInteroperabilityRepairReason.ANDROID_ACCOUNT_NAME_UNBOUND,
                    AndroidInteroperabilityRepairReason.ANDROID_ACCOUNT_MISSING -> AndroidAvailability.CONTEXT_NOT_ESTABLISHED
                    AndroidInteroperabilityRepairReason.CROSS_ACCOUNT_BINDING,
                    AndroidInteroperabilityRepairReason.AUTHORITY_MISMATCH,
                    AndroidInteroperabilityRepairReason.PROVIDER_STATE_DIVERGED -> AndroidAvailability.UNAVAILABLE
                }
                AndroidInteroperabilityPreflightResult.Cancelled,
                AndroidInteroperabilityPreflightResult.LocalPersistenceFailure -> AndroidAvailability.UNKNOWN
            }
            observation.value = Observation(availability) {
                generation.get() != expectedGeneration || isCancelled()
            }
        }
        return result
    }
}
