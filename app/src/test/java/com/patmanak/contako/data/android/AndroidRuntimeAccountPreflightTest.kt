package com.patmanak.contako.data.android

import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.sync.AndroidInteroperabilityPreflightResult
import com.patmanak.contako.data.sync.AndroidInteroperabilityRepairReason
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AndroidRuntimeAccountPreflightTest {
    private val account = AccountScope("account")

    @Test
    fun `04-PREFLIGHT ready freezes exact durable scope`() = runTest {
        var probedName: String? = null
        val preflight = preflight(
            probe = { name, _ ->
                probedName = name
                AndroidRuntimeProbeResult.Ready
            },
        )

        val result = preflight.check(account) { false } as AndroidInteroperabilityPreflightResult.Ready

        assertEquals(account, result.context.account)
        assertEquals("android-account", probedName)
        assertEquals(7, result.context.accountRevision)
        assertEquals(3, result.context.providerEpoch)
        assertFalse(result.context.toString().contains("android-account"))
    }

    @Test
    fun `04-PREFLIGHT missing unbound and cross-account contexts fail before provider access`() = runTest {
        listOf(
            null to AndroidInteroperabilityRepairReason.DURABLE_ACCOUNT_CONTEXT_MISSING,
            context(androidAccountName = null) to AndroidInteroperabilityRepairReason.ANDROID_ACCOUNT_NAME_UNBOUND,
            context(hasCrossAccountBinding = true) to AndroidInteroperabilityRepairReason.CROSS_ACCOUNT_BINDING,
        ).forEach { (durable, expected) ->
            var probes = 0
            val result = preflight(durable) { _, _ ->
                probes++
                AndroidRuntimeProbeResult.Ready
            }.check(account) { false } as AndroidInteroperabilityPreflightResult.RepairRequired

            assertEquals(expected, result.reason)
            assertEquals(0, probes)
        }
    }

    @Test
    fun `04-PREFLIGHT runtime failures are classified without repair writes`() = runTest {
        val cases = listOf(
            AndroidRuntimeProbeResult.PermissionDenied to AndroidInteroperabilityPreflightResult.PermissionDenied,
            AndroidRuntimeProbeResult.ProviderUnavailable to AndroidInteroperabilityPreflightResult.ProviderUnavailable,
            AndroidRuntimeProbeResult.AndroidAccountMissing to repair(
                AndroidInteroperabilityRepairReason.ANDROID_ACCOUNT_MISSING,
            ),
            AndroidRuntimeProbeResult.AuthorityMismatch to repair(
                AndroidInteroperabilityRepairReason.AUTHORITY_MISMATCH,
            ),
        )

        cases.forEach { (probeResult, expected) ->
            assertEquals(expected, preflight(probe = { _, _ -> probeResult }).check(account) { false })
        }
    }

    @Test
    fun `04-PREFLIGHT provider divergence carries only its frozen repair context`() = runTest {
        val diverged = preflight(probe = { _, _ ->
            AndroidRuntimeProbeResult.ProviderStateDiverged
        }).check(account) { false } as AndroidInteroperabilityPreflightResult.RepairRequired
        val other = preflight(probe = { _, _ ->
            AndroidRuntimeProbeResult.AndroidAccountMissing
        }).check(account) { false } as AndroidInteroperabilityPreflightResult.RepairRequired

        assertEquals(account, diverged.context?.account)
        assertFalse(diverged.context.toString().contains("android-account"))
        assertEquals(7L, diverged.context?.accountRevision)
        assertEquals(3L, diverged.context?.providerEpoch)
        assertEquals(null, other.context)
    }

    @Test
    fun `04-PREFLIGHT cancellation stops at every boundary`() = runTest {
        var reads = 0
        var probes = 0
        assertEquals(
            AndroidInteroperabilityPreflightResult.Cancelled,
            AndroidRuntimeAccountPreflight(
                { reads++; context() },
                { _, _ -> probes++; AndroidRuntimeProbeResult.Ready },
            ).check(account) { true },
        )
        assertEquals(0, reads)
        assertEquals(0, probes)

        var cancelled = false
        assertEquals(
            AndroidInteroperabilityPreflightResult.Cancelled,
            AndroidRuntimeAccountPreflight(
                {
                    cancelled = true
                    context()
                },
                { _, _ -> probes++; AndroidRuntimeProbeResult.Ready },
            ).check(account) { cancelled },
        )
        assertEquals(0, probes)
    }

    @Test
    fun `04-PREFLIGHT durable read exception is a redacted local failure`() = runTest {
        val result = AndroidRuntimeAccountPreflight(
            { error("PRIVATE_ACCOUNT_VALUE") },
            { _, _ -> AndroidRuntimeProbeResult.Ready },
        ).check(account) { false }

        assertEquals(AndroidInteroperabilityPreflightResult.LocalPersistenceFailure, result)
        assertFalse(result.toString().contains("PRIVATE_ACCOUNT_VALUE"))
    }

    @Test
    fun durableReadCancellationPropagatesWithoutProbingAndroid() = runTest {
        val cancellation = kotlinx.coroutines.CancellationException("synthetic cancellation")
        try {
            AndroidRuntimeAccountPreflight(
                { throw cancellation },
                { _, _ -> error("ANDROID_PROBE_MUST_NOT_RUN") },
            ).check(account) { false }
            org.junit.Assert.fail("CANCELLATION_WAS_SWALLOWED")
        } catch (actual: kotlinx.coroutines.CancellationException) {
            org.junit.Assert.assertSame(cancellation, actual)
        }
    }

    private fun preflight(
        durable: AndroidDurableAccountContext? = context(),
        probe: (String, Long) -> AndroidRuntimeProbeResult = { _, _ -> AndroidRuntimeProbeResult.Ready },
    ) = AndroidRuntimeAccountPreflight({ durable }, probe)

    private fun context(
        androidAccountName: String? = "android-account",
        hasCrossAccountBinding: Boolean = false,
    ) = AndroidDurableAccountContext(account, androidAccountName, 7, 3, hasCrossAccountBinding)

    private fun repair(reason: AndroidInteroperabilityRepairReason) =
        AndroidInteroperabilityPreflightResult.RepairRequired(reason)
}
