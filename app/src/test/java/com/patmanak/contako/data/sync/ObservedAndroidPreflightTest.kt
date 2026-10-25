package com.patmanak.contako.data.sync

import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.domain.sync.AndroidAvailability
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ObservedAndroidPreflightTest {
    private val owner = AccountScope("synthetic-owner")
    private val ready = AndroidInteroperabilityPreflightResult.Ready(
        AndroidInteroperabilityContext(owner, "synthetic@example.test", 1, 2))

    @Test fun consultationIsPassiveAndOnlyCurrentPreflightFactsEstablishReadiness() = runTest {
        var result: AndroidInteroperabilityPreflightResult = ready
        var calls = 0
        val observed = ObservedAndroidPreflight(owner, AndroidInteroperabilityPreflight { _, _ -> calls++; result })
        val state = observed.observe(MutableStateFlow(false))
        assertEquals(AndroidAvailability.UNKNOWN, state.first())
        assertEquals(0, calls)
        val cases = listOf(
            ready to AndroidAvailability.READY,
            AndroidInteroperabilityPreflightResult.PermissionDenied to AndroidAvailability.PERMISSION_REQUIRED,
            AndroidInteroperabilityPreflightResult.RepairRequired(AndroidInteroperabilityRepairReason.DURABLE_ACCOUNT_CONTEXT_MISSING)
                to AndroidAvailability.CONTEXT_NOT_ESTABLISHED,
            AndroidInteroperabilityPreflightResult.RepairRequired(AndroidInteroperabilityRepairReason.ANDROID_ACCOUNT_NAME_UNBOUND)
                to AndroidAvailability.CONTEXT_NOT_ESTABLISHED,
            AndroidInteroperabilityPreflightResult.RepairRequired(AndroidInteroperabilityRepairReason.ANDROID_ACCOUNT_MISSING)
                to AndroidAvailability.CONTEXT_NOT_ESTABLISHED,
            AndroidInteroperabilityPreflightResult.ProviderUnavailable to AndroidAvailability.UNAVAILABLE,
            AndroidInteroperabilityPreflightResult.RepairRequired(AndroidInteroperabilityRepairReason.CROSS_ACCOUNT_BINDING)
                to AndroidAvailability.UNAVAILABLE,
            AndroidInteroperabilityPreflightResult.RepairRequired(AndroidInteroperabilityRepairReason.AUTHORITY_MISMATCH)
                to AndroidAvailability.UNAVAILABLE,
            AndroidInteroperabilityPreflightResult.RepairRequired(AndroidInteroperabilityRepairReason.PROVIDER_STATE_DIVERGED)
                to AndroidAvailability.UNAVAILABLE,
            AndroidInteroperabilityPreflightResult.LocalPersistenceFailure to AndroidAvailability.UNKNOWN,
            AndroidInteroperabilityPreflightResult.Cancelled to AndroidAvailability.UNKNOWN,
            AndroidInteroperabilityPreflightResult.Ready(ready.context.copy(account = AccountScope("synthetic-foreign")))
                to AndroidAvailability.UNAVAILABLE,
            ready to AndroidAvailability.READY,
        )
        cases.forEach { (next, expected) ->
            result = next
            assertSame(next, observed.check(owner) { false })
            assertEquals(expected, state.first())
        }
        assertEquals(cases.size, calls)
        observed.reset()
        assertEquals(AndroidAvailability.UNKNOWN, state.first())
        assertEquals(cases.size, calls)
    }

    @Test fun invalidationAndReadmissionCannotReuseThePreviousOwnerGeneration() = runTest {
        var ownerGeneration = 0
        val invalidated = MutableStateFlow(false)
        val lifecycle = MutableStateFlow(1)
        val observed = ObservedAndroidPreflight(owner, AndroidInteroperabilityPreflight { _, _ -> ready })
        val states = mutableListOf<AndroidAvailability>()
        backgroundScope.launch { observed.observe(invalidated, lifecycle).collect { states += it } }
        observed.check(owner) { ownerGeneration != 0 }
        runCurrent()
        assertEquals(AndroidAvailability.READY, states.last())
        ownerGeneration++
        // Both boolean emissions may be coalesced before the UI gets time to collect.
        invalidated.value = true
        invalidated.value = false
        lifecycle.value = 2
        runCurrent()
        assertEquals(AndroidAvailability.UNKNOWN, states.last())
        val currentGeneration = ownerGeneration
        observed.check(owner) { ownerGeneration != currentGeneration }
        runCurrent()
        assertEquals(AndroidAvailability.READY, states.last())
        invalidated.value = true
        runCurrent()
        assertEquals(AndroidAvailability.UNKNOWN, states.last())
    }

    @Test fun latePreflightResultCannotPublishAfterCancellationOrAReplacementPass() = runTest {
        for (cancelOwner in listOf(false, true)) {
            var ownerGeneration = 0
            val response = CompletableDeferred<AndroidInteroperabilityPreflightResult>()
            val observed = ObservedAndroidPreflight(owner, AndroidInteroperabilityPreflight { _, _ -> response.await() })
            val state = observed.observe(MutableStateFlow(false))
            val request = async { observed.check(owner) { ownerGeneration != 0 } }
            runCurrent()
            if (cancelOwner) ownerGeneration++ else observed.reset()
            response.complete(ready)
            assertSame(ready, request.await())
            assertEquals(AndroidAvailability.UNKNOWN, state.first())
        }
    }

    @Test fun foreignAccountAndThrownFailureCannotCreateReadinessForTheBoundOwner() = runTest {
        val foreign = AccountScope("synthetic-foreign")
        var throwFailure = false
        val expected = IllegalStateException("Synthetic preflight failure")
        val observed = ObservedAndroidPreflight(owner, AndroidInteroperabilityPreflight { _, _ ->
            if (throwFailure) throw expected else ready
        })
        val state = observed.observe(MutableStateFlow(false))
        assertSame(ready, observed.check(foreign) { false })
        assertEquals(AndroidAvailability.UNKNOWN, state.first())
        observed.check(owner) { false }
        assertEquals(AndroidAvailability.READY, state.first())
        throwFailure = true
        try {
            observed.check(owner) { false }
            fail("Expected original failure")
        } catch (actual: IllegalStateException) { assertSame(expected, actual) }
        assertEquals(AndroidAvailability.UNKNOWN, state.first())
    }
}
