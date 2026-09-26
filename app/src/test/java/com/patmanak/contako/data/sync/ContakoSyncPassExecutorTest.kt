package com.patmanak.contako.data.sync

import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.AvailableContactGroups
import com.patmanak.contako.data.gateway.ContactGroupCapabilities
import com.patmanak.contako.data.gateway.ContactGroupMutation
import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.data.gateway.ContactInventoryCoverage
import com.patmanak.contako.data.gateway.ContactInventoryPage
import com.patmanak.contako.data.gateway.ContactInventorySnapshotAuthority
import com.patmanak.contako.data.gateway.ContactInventoryVersionProvenance
import com.patmanak.contako.data.gateway.ContactInventoryMetadata
import com.patmanak.contako.data.gateway.GatewayOutcome
import com.patmanak.contako.data.gateway.ProtonContactInventoryGateway
import com.patmanak.contako.data.gateway.ProtonContactGroupGateway
import com.patmanak.contako.data.gateway.ProtonVerifiedContactCardGateway
import com.patmanak.contako.data.gateway.RemoteContactGroup
import com.patmanak.contako.data.gateway.RemoteContactId
import com.patmanak.contako.data.gateway.RemoteVersion
import com.patmanak.contako.data.gateway.VerifiedContactCard
import com.patmanak.contako.data.proton.ContactInventoryCheckpoint
import com.patmanak.contako.data.proton.ContactInventoryCheckpointStore
import com.patmanak.contako.data.proton.PersistentContactInventoryPlanner
import com.patmanak.contako.data.proton.VersionedContactInventoryCheckpoint
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.sync.SyncPassOutcome
import com.patmanak.contako.domain.sync.SyncPassRequest
import com.patmanak.contako.domain.sync.SyncScope
import com.patmanak.contako.domain.sync.SyncTrigger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContakoSyncPassExecutorTest {
    @Test
    fun `03-ORDER pass checks prerequisites and remote state before upload`() = runTest {
        val order = mutableListOf<String>()
        val store = SingleCommandStore(command())
        val executor = executor(
            prerequisite = { order += "prerequisite"; SyncPrerequisiteState.READY },
            inventory = { order += "remote-index" },
            canonical = { order += "canonical-commit" },
            store = store,
            prepare = { order += "mutation-prepare" },
            upload = { order += "mutation-upload" },
        )

        assertEquals(SyncPassOutcome.SUCCESS, executor.execute(request()))
        assertEquals(
            listOf("prerequisite", "remote-index", "canonical-commit", "mutation-prepare", "mutation-upload"),
            order,
        )
    }

    @Test
    fun `RF04 group replacement finishes its next durable step in the same pass`() = runTest {
        var writes = 0
        val executor = executor(
            prerequisite = { SyncPrerequisiteState.READY }, inventory = {}, canonical = {},
            store = SingleCommandStore(command()), prepare = {}, upload = {},
            uploadOutcome = {
                GatewayOutcome.Success(RemoteMutationAcknowledgement(
                    "remote", "version", fullyConverged = ++writes == 2,
                    nextOperation = if (writes == 2) null else RemoteMutationOperation.ASSIGNMENTS,
                ))
            },
        )
        assertEquals(SyncPassOutcome.SUCCESS, executor.execute(request()))
        assertEquals(2, writes)
    }

    @Test
    fun `RF04 continued progress remains bounded when more batches remain`() = runTest {
        var writes = 0
        val executor = executor(
            prerequisite = { SyncPrerequisiteState.READY }, inventory = {}, canonical = {},
            store = SingleCommandStore(command()), prepare = {}, upload = { writes++ },
            uploadOutcome = { GatewayOutcome.Success(RemoteMutationAcknowledgement(
                "remote", "version", false, RemoteMutationOperation.ASSIGNMENTS,
            )) },
        )
        assertEquals(SyncPassOutcome.RETRY_WAITING, executor.execute(request()))
        assertEquals(3, writes)
    }

    @Test
    fun `RF04 partial success does not cause immediate retry after a service failure`() = runTest {
        var writes = 0
        val executor = executor(
            prerequisite = { SyncPrerequisiteState.READY }, inventory = {}, canonical = {},
            store = SingleCommandStore(command()), prepare = {}, upload = {},
            uploadOutcome = {
                if (++writes == 1) GatewayOutcome.Success(RemoteMutationAcknowledgement(
                    "remote", "version", false, RemoteMutationOperation.ASSIGNMENTS,
                )) else GatewayOutcome.Failure(GatewayFailureCategory.REMOTE_SERVICE_FAILURE)
            },
        )
        assertEquals(SyncPassOutcome.RETRY_WAITING, executor.execute(request()))
        assertEquals(2, writes)
    }

    @Test
    fun `03-STATUS offline and authentication failures never enumerate or upload`() = runTest {
        listOf(
            SyncPrerequisiteState.OFFLINE to SyncPassOutcome.RETRY_WAITING,
            SyncPrerequisiteState.AUTHENTICATION_REQUIRED to SyncPassOutcome.ACTION_REQUIRED,
            SyncPrerequisiteState.INTERACTIVE_ACTION_REQUIRED to SyncPassOutcome.ACTION_REQUIRED,
        ).forEach { (prerequisite, expected) ->
            var externalCalls = 0
            val executor = executor(
                prerequisite = { prerequisite },
                inventory = { externalCalls++ },
                canonical = { externalCalls++ },
                store = SingleCommandStore(command()),
                prepare = { externalCalls++ },
                upload = { externalCalls++ },
            )

            assertEquals(expected, executor.execute(request()))
            assertEquals(0, externalCalls)
        }
    }

    @Test
    fun `04-ORDER Android ingestion and local projection run offline before network prerequisites`() = runTest {
        val order = mutableListOf<String>()
        val android = object : AndroidInteroperabilityStage {
            override suspend fun ingest(context: AndroidInteroperabilityContext, isCancelled: () -> Boolean): AndroidInteroperabilityStageResult {
                order += "android-ingest"
                return AndroidInteroperabilityStageResult.Success
            }

            override suspend fun project(context: AndroidInteroperabilityContext, isCancelled: () -> Boolean): AndroidInteroperabilityStageResult {
                order += "android-project"
                return AndroidInteroperabilityStageResult.Success
            }
        }
        val executor = executor(
            prerequisite = { order += "prerequisite"; SyncPrerequisiteState.OFFLINE },
            inventory = { order += "remote" },
            canonical = {},
            store = SingleCommandStore(command()),
            prepare = {},
            upload = {},
            android = android,
        )

        assertEquals(SyncPassOutcome.RETRY_WAITING, executor.execute(request()))
        assertEquals(listOf("android-ingest", "android-project", "prerequisite"), order)
    }

    @Test
    fun `04-ORDER final Android projection runs after remote reconciliation and outbox drain`() = runTest {
        val order = mutableListOf<String>()
        var projectionPass = 0
        val android = object : AndroidInteroperabilityStage {
            override suspend fun ingest(context: AndroidInteroperabilityContext, isCancelled: () -> Boolean): AndroidInteroperabilityStageResult {
                order += "android-ingest"
                return AndroidInteroperabilityStageResult.Success
            }

            override suspend fun project(context: AndroidInteroperabilityContext, isCancelled: () -> Boolean): AndroidInteroperabilityStageResult {
                order += "android-project-${++projectionPass}"
                return AndroidInteroperabilityStageResult.Success
            }
        }
        val executor = executor(
            prerequisite = { order += "prerequisite"; SyncPrerequisiteState.READY },
            inventory = { order += "remote-index" },
            canonical = { order += "canonical-commit" },
            store = SingleCommandStore(command()),
            prepare = { order += "mutation-prepare" },
            upload = { order += "mutation-upload" },
            android = android,
        )

        assertEquals(SyncPassOutcome.SUCCESS, executor.execute(request()))
        assertEquals(
            listOf(
                "android-ingest",
                "android-project-1",
                "prerequisite",
                "remote-index",
                "canonical-commit",
                "mutation-prepare",
                "mutation-upload",
                "android-project-2",
            ),
            order,
        )
    }

    @Test
    fun `04-ORDER final Android projection refreshes context after remote acknowledgement`() = runTest {
        val initial = AndroidInteroperabilityContext(AccountScope("account"), "android-account", 1, 3)
        val afterIngest = initial.copy(accountRevision = 2)
        val afterRemoteAcknowledgement = initial.copy(accountRevision = 4)
        var checks = 0
        val projected = mutableListOf<AndroidInteroperabilityContext>()
        val executor = executor(
            prerequisite = { SyncPrerequisiteState.READY },
            inventory = {},
            canonical = {},
            store = SingleCommandStore(command()),
            prepare = {},
            upload = {},
            androidPreflight = AndroidInteroperabilityPreflight { _, _ ->
                AndroidInteroperabilityPreflightResult.Ready(
                    when (++checks) {
                        1 -> initial
                        2 -> afterIngest
                        else -> afterRemoteAcknowledgement
                    },
                )
            },
            android = object : AndroidInteroperabilityStage {
                override suspend fun ingest(
                    context: AndroidInteroperabilityContext,
                    isCancelled: () -> Boolean,
                ) = AndroidInteroperabilityStageResult.Success.also { assertEquals(initial, context) }

                override suspend fun project(
                    context: AndroidInteroperabilityContext,
                    isCancelled: () -> Boolean,
                ) = AndroidInteroperabilityStageResult.Success.also { projected += context }
            },
        )

        assertEquals(SyncPassOutcome.SUCCESS, executor.execute(request()))
        assertEquals(3, checks)
        assertEquals(listOf(afterIngest, afterRemoteAcknowledgement), projected)
    }

    @Test
    fun `04-PREFLIGHT final refresh remains closed after successful Proton work`() = runTest {
        val ready = AndroidInteroperabilityPreflightResult.Ready(
            AndroidInteroperabilityContext(AccountScope("account"), "android-account", 1, 3),
        )
        listOf(
            AndroidInteroperabilityPreflightResult.PermissionDenied to SyncPassOutcome.ACTION_REQUIRED,
            AndroidInteroperabilityPreflightResult.ProviderUnavailable to SyncPassOutcome.RETRY_WAITING,
            AndroidInteroperabilityPreflightResult.RepairRequired(
                AndroidInteroperabilityRepairReason.PROVIDER_STATE_DIVERGED,
                ready.context,
            ) to SyncPassOutcome.ACTION_REQUIRED,
            AndroidInteroperabilityPreflightResult.Cancelled to SyncPassOutcome.CANCELLED,
            AndroidInteroperabilityPreflightResult.LocalPersistenceFailure to SyncPassOutcome.FAILED,
        ).forEach { (finalPreflight, expected) ->
            var checks = 0
            var projections = 0
            var protonCalls = 0
            val executor = executor(
                prerequisite = { protonCalls++; SyncPrerequisiteState.READY },
                inventory = { protonCalls++ },
                canonical = { protonCalls++ },
                store = SingleCommandStore(command()),
                prepare = { protonCalls++ },
                upload = {},
                androidPreflight = AndroidInteroperabilityPreflight { _, _ ->
                    if (++checks < 3) ready else finalPreflight
                },
                android = object : AndroidInteroperabilityStage {
                    override suspend fun ingest(
                        context: AndroidInteroperabilityContext,
                        isCancelled: () -> Boolean,
                    ) = AndroidInteroperabilityStageResult.Success

                    override suspend fun project(
                        context: AndroidInteroperabilityContext,
                        isCancelled: () -> Boolean,
                    ) = AndroidInteroperabilityStageResult.Success.also { projections++ }
                },
            )

            assertEquals(expected, executor.execute(request()))
            assertEquals(3, checks)
            assertEquals(1, projections)
            assertEquals(4, protonCalls)
        }
    }

    @Test
    fun `RF08 completed final traversal resolves initial projection retry without hiding ingestion retry`() = runTest {
        listOf(false, true).forEach { ingestionRetries ->
            var projections = 0
            val executor = executor(
                prerequisite = { SyncPrerequisiteState.READY },
                inventory = {}, canonical = {}, store = SingleCommandStore(null), prepare = {}, upload = {},
                android = object : AndroidInteroperabilityStage {
                    override suspend fun ingest(context: AndroidInteroperabilityContext, isCancelled: () -> Boolean) =
                        if (ingestionRetries) AndroidInteroperabilityStageResult.RetryWaiting
                        else AndroidInteroperabilityStageResult.Success

                    override suspend fun project(context: AndroidInteroperabilityContext, isCancelled: () -> Boolean) =
                        if (++projections == 1) AndroidInteroperabilityStageResult.RetryWaiting
                        else AndroidInteroperabilityStageResult.Success
                },
            )
            assertEquals(
                if (ingestionRetries) SyncPassOutcome.RETRY_WAITING else SyncPassOutcome.SUCCESS,
                executor.execute(request()),
            )
            assertEquals(2, projections)
        }
    }

    @Test
    fun `04-ORDER final projection replans only across proven durable context advances`() = runTest {
        val contexts = listOf(1L, 2L, 4L, 5L).map { revision ->
            AndroidInteroperabilityContext(AccountScope("account"), "android-account", revision, 3)
        }
        var checks = 0
        var projections = 0
        val executor = executor(
            prerequisite = { SyncPrerequisiteState.READY },
            inventory = {},
            canonical = {},
            store = SingleCommandStore(command()),
            prepare = {},
            upload = {},
            androidPreflight = AndroidInteroperabilityPreflight { _, _ ->
                AndroidInteroperabilityPreflightResult.Ready(contexts[checks++])
            },
            android = object : AndroidInteroperabilityStage {
                override suspend fun ingest(
                    context: AndroidInteroperabilityContext,
                    isCancelled: () -> Boolean,
                ) = AndroidInteroperabilityStageResult.Success

                override suspend fun project(
                    context: AndroidInteroperabilityContext,
                    isCancelled: () -> Boolean,
                ): AndroidInteroperabilityStageResult {
                    projections++
                    return if (projections == 2) {
                        AndroidInteroperabilityStageResult.RetryWaiting
                    } else {
                        AndroidInteroperabilityStageResult.Success
                    }
                }
            },
        )

        assertEquals(SyncPassOutcome.SUCCESS, executor.execute(request()))
        assertEquals(4, checks)
        assertEquals(3, projections)
    }

    @Test
    fun `04-ORDER final projection does not spin when retry context is unchanged`() = runTest {
        val context = AndroidInteroperabilityContext(AccountScope("account"), "android-account", 1, 3)
        var checks = 0
        var projections = 0
        val executor = executor(
            prerequisite = { SyncPrerequisiteState.READY },
            inventory = {},
            canonical = {},
            store = SingleCommandStore(null),
            prepare = {},
            upload = {},
            androidPreflight = AndroidInteroperabilityPreflight { _, _ ->
                checks++
                AndroidInteroperabilityPreflightResult.Ready(context)
            },
            android = object : AndroidInteroperabilityStage {
                override suspend fun ingest(
                    context: AndroidInteroperabilityContext,
                    isCancelled: () -> Boolean,
                ) = AndroidInteroperabilityStageResult.Success

                override suspend fun project(
                    context: AndroidInteroperabilityContext,
                    isCancelled: () -> Boolean,
                ) = if (++projections == 1) {
                    AndroidInteroperabilityStageResult.Success
                } else {
                    AndroidInteroperabilityStageResult.RetryWaiting
                }
            },
        )

        assertEquals(SyncPassOutcome.RETRY_WAITING, executor.execute(request()))
        assertEquals(4, checks)
        assertEquals(2, projections)
    }

    @Test
    fun `04-ORDER final projection replan loop is bounded despite advancing revisions`() = runTest {
        var checks = 0
        var projections = 0
        val executor = executor(
            prerequisite = { SyncPrerequisiteState.READY },
            inventory = {},
            canonical = {},
            store = SingleCommandStore(null),
            prepare = {},
            upload = {},
            androidPreflight = AndroidInteroperabilityPreflight { _, _ ->
                AndroidInteroperabilityPreflightResult.Ready(
                    AndroidInteroperabilityContext(
                        AccountScope("account"),
                        "android-account",
                        (++checks).toLong(),
                        3,
                    ),
                )
            },
            android = object : AndroidInteroperabilityStage {
                override suspend fun ingest(
                    context: AndroidInteroperabilityContext,
                    isCancelled: () -> Boolean,
                ) = AndroidInteroperabilityStageResult.Success

                override suspend fun project(
                    context: AndroidInteroperabilityContext,
                    isCancelled: () -> Boolean,
                ) = if (++projections == 1) {
                    AndroidInteroperabilityStageResult.Success
                } else {
                    AndroidInteroperabilityStageResult.RetryWaiting
                }
            },
        )

        assertEquals(SyncPassOutcome.RETRY_WAITING, executor.execute(request()))
        assertEquals(7, checks)
        assertEquals(6, projections)
    }

    @Test
    fun `04-DEGRADED Android action required does not block Proton and remains final outcome`() = runTest {
        val order = mutableListOf<String>()
        var projections = 0
        val android = object : AndroidInteroperabilityStage {
            override suspend fun ingest(context: AndroidInteroperabilityContext, isCancelled: () -> Boolean) =
                AndroidInteroperabilityStageResult.ActionRequired.also { order += "android-ingest-paused" }

            override suspend fun project(context: AndroidInteroperabilityContext, isCancelled: () -> Boolean) =
                AndroidInteroperabilityStageResult.Success.also {
                    order += "android-project-${++projections}"
                }
        }
        val executor = executor(
            prerequisite = { order += "prerequisite"; SyncPrerequisiteState.READY },
            inventory = { order += "remote-index" },
            canonical = { order += "canonical-commit" },
            store = SingleCommandStore(command()),
            prepare = { order += "mutation-prepare" },
            upload = { order += "mutation-upload" },
            android = android,
        )

        assertEquals(SyncPassOutcome.ACTION_REQUIRED, executor.execute(request()))
        assertEquals(
            listOf(
                "android-ingest-paused",
                "android-project-1",
                "prerequisite",
                "remote-index",
                "canonical-commit",
                "mutation-prepare",
                "mutation-upload",
                "android-project-2",
            ),
            order,
        )
    }

    @Test
    fun `04-DEGRADED Android retry waiting is combined after successful remote pass`() = runTest {
        var remoteCalls = 0
        val android = object : AndroidInteroperabilityStage {
            override suspend fun ingest(context: AndroidInteroperabilityContext, isCancelled: () -> Boolean) =
                AndroidInteroperabilityStageResult.RetryWaiting

            override suspend fun project(context: AndroidInteroperabilityContext, isCancelled: () -> Boolean) =
                AndroidInteroperabilityStageResult.Success
        }
        val executor = executor(
            prerequisite = { SyncPrerequisiteState.READY },
            inventory = { remoteCalls++ },
            canonical = { remoteCalls++ },
            store = SingleCommandStore(command()),
            prepare = { remoteCalls++ },
            upload = { remoteCalls++ },
            android = android,
        )

        assertEquals(SyncPassOutcome.RETRY_WAITING, executor.execute(request()))
        assertEquals(4, remoteCalls)
    }

    @Test
    fun `05-GROUP Free denial does not block ordinary contact synchronization`() = runTest {
        var contactCalls = 0
        val unavailableGroups = object : ProtonContactGroupGateway {
            override fun capabilities() = ContactGroupCapabilities.PROTON_CORE_36_6_2_SURFACE
            override suspend fun list(account: AccountScope) =
                GatewayOutcome.Failure(GatewayFailureCategory.PERMISSION_OR_PLAN_DENIED)
            override suspend fun create(account: AccountScope, mutation: ContactGroupMutation.Create): GatewayOutcome<RemoteContactGroup> = error("unused")
            override suspend fun update(account: AccountScope, mutation: ContactGroupMutation.Update): GatewayOutcome<RemoteContactGroup> = error("unused")
            override suspend fun delete(account: AccountScope, mutation: ContactGroupMutation.Delete): GatewayOutcome<Unit> = error("unused")
        }
        val executor = executor(
            prerequisite = { SyncPrerequisiteState.READY },
            inventory = { contactCalls++ },
            canonical = {},
            store = SingleCommandStore(null),
            prepare = {},
            upload = {},
            groupStage = IncrementalRemoteGroupStage(
                unavailableGroups,
                RemoteGroupReconciliationStore { _, _: AvailableContactGroups -> error("must not commit") },
            ),
        )

        assertEquals(SyncPassOutcome.SUCCESS, executor.execute(request()))
        assertEquals(1, contactCalls)
    }

    @Test
    fun `04-PREFLIGHT degraded Android skips every Android stage and preserves Proton pass`() = runTest {
        listOf(
            AndroidInteroperabilityPreflightResult.PermissionDenied to SyncPassOutcome.ACTION_REQUIRED,
            AndroidInteroperabilityPreflightResult.ProviderUnavailable to SyncPassOutcome.RETRY_WAITING,
            AndroidInteroperabilityPreflightResult.RepairRequired(
                AndroidInteroperabilityRepairReason.CROSS_ACCOUNT_BINDING,
            ) to SyncPassOutcome.ACTION_REQUIRED,
        ).forEach { (preflightResult, expected) ->
            var androidCalls = 0
            var remoteCalls = 0
            val android = object : AndroidInteroperabilityStage {
                override suspend fun ingest(
                    context: AndroidInteroperabilityContext,
                    isCancelled: () -> Boolean,
                ) = AndroidInteroperabilityStageResult.Success.also { androidCalls++ }

                override suspend fun project(
                    context: AndroidInteroperabilityContext,
                    isCancelled: () -> Boolean,
                ) = AndroidInteroperabilityStageResult.Success.also { androidCalls++ }
            }
            val executor = executor(
                prerequisite = { SyncPrerequisiteState.READY },
                inventory = { remoteCalls++ },
                canonical = { remoteCalls++ },
                store = SingleCommandStore(command()),
                prepare = { remoteCalls++ },
                upload = { remoteCalls++ },
                android = android,
                androidPreflight = AndroidInteroperabilityPreflight { _, _ -> preflightResult },
            )

            assertEquals(expected, executor.execute(request()))
            assertEquals(0, androidCalls)
            assertEquals(4, remoteCalls)
        }
    }

    @Test
    fun `04-PREFLIGHT local failure and cancellation are terminal before Android and Proton`() = runTest {
        listOf(
            AndroidInteroperabilityPreflightResult.LocalPersistenceFailure to SyncPassOutcome.FAILED,
            AndroidInteroperabilityPreflightResult.Cancelled to SyncPassOutcome.CANCELLED,
        ).forEach { (preflightResult, expected) ->
            var laterCalls = 0
            val executor = executor(
                prerequisite = { laterCalls++; SyncPrerequisiteState.READY },
                inventory = { laterCalls++ },
                canonical = { laterCalls++ },
                store = SingleCommandStore(command()),
                prepare = { laterCalls++ },
                upload = { laterCalls++ },
                androidPreflight = AndroidInteroperabilityPreflight { _, _ -> preflightResult },
            )

            assertEquals(expected, executor.execute(request()))
            assertEquals(0, laterCalls)
        }
    }

    @Test
    fun `04-RESET provider divergence repairs reloads context and continues without echo upload`() = runTest {
        val order = mutableListOf<String>()
        val stale = AndroidInteroperabilityContext(AccountScope("account"), "android-account", 7, 3)
        val refreshed = stale.copy(accountRevision = 9, providerEpoch = 4)
        var checks = 0
        var uploads = 0
        val executor = executor(
            prerequisite = { order += "prerequisite"; SyncPrerequisiteState.READY },
            inventory = { order += "remote-index" },
            canonical = { order += "canonical-commit" },
            store = SingleCommandStore(null),
            prepare = {},
            upload = { uploads++ },
            androidPreflight = AndroidInteroperabilityPreflight { _, _ ->
                order += "preflight-${++checks}"
                if (checks == 1) AndroidInteroperabilityPreflightResult.RepairRequired(
                    AndroidInteroperabilityRepairReason.PROVIDER_STATE_DIVERGED,
                    stale,
                ) else AndroidInteroperabilityPreflightResult.Ready(refreshed)
            },
            androidRepair = AndroidInteroperabilityRepairCoordinator { context, _ ->
                assertEquals(stale, context)
                order += "reset"
                AndroidInteroperabilityStageResult.Success
            },
            android = object : AndroidInteroperabilityStage {
                override suspend fun ingest(context: AndroidInteroperabilityContext, isCancelled: () -> Boolean) =
                    AndroidInteroperabilityStageResult.Success.also {
                        assertEquals(refreshed, context)
                        order += "android-ingest"
                    }

                override suspend fun project(context: AndroidInteroperabilityContext, isCancelled: () -> Boolean) =
                    AndroidInteroperabilityStageResult.Success.also {
                        assertEquals(refreshed, context)
                        order += "android-project"
                    }
            },
        )

        assertEquals(SyncPassOutcome.SUCCESS, executor.execute(request()))
        assertEquals(0, uploads)
        assertEquals(
            listOf(
                "preflight-1", "reset", "preflight-2", "android-ingest", "preflight-3",
                "android-project", "prerequisite", "remote-index",
                "canonical-commit", "preflight-4", "android-project",
            ),
            order,
        )
    }

    @Test
    fun `04-RESET interrupted reset stops before every Proton request`() = runTest {
        var protonCalls = 0
        val stale = AndroidInteroperabilityContext(AccountScope("account"), "android-account", 1, 0)
        val executor = executor(
            prerequisite = { protonCalls++; SyncPrerequisiteState.READY },
            inventory = { protonCalls++ },
            canonical = { protonCalls++ },
            store = SingleCommandStore(null),
            prepare = { protonCalls++ },
            upload = { protonCalls++ },
            androidPreflight = divergedPreflight(stale),
            androidRepair = AndroidInteroperabilityRepairCoordinator { _, _ ->
                AndroidInteroperabilityStageResult.Cancelled
            },
        )

        assertEquals(SyncPassOutcome.CANCELLED, executor.execute(request()))
        assertEquals(0, protonCalls)
    }

    @Test
    fun `04-RESET provider degradation remains Android-only`() = runTest {
        listOf(
            AndroidInteroperabilityStageResult.RetryWaiting to SyncPassOutcome.RETRY_WAITING,
            AndroidInteroperabilityStageResult.ActionRequired to SyncPassOutcome.ACTION_REQUIRED,
        ).forEach { (repairResult, expected) ->
            var protonCalls = 0
            val stale = AndroidInteroperabilityContext(AccountScope("account"), "android-account", 1, 0)
            val executor = executor(
                prerequisite = { protonCalls++; SyncPrerequisiteState.READY },
                inventory = { protonCalls++ },
                canonical = { protonCalls++ },
                store = SingleCommandStore(null),
                prepare = { protonCalls++ },
                upload = { protonCalls++ },
                androidPreflight = divergedPreflight(stale),
                androidRepair = AndroidInteroperabilityRepairCoordinator { _, _ -> repairResult },
            )

            assertEquals(expected, executor.execute(request()))
            assertEquals(3, protonCalls)
        }
    }

    @Test
    fun `04-RESET other repair reasons remain action required and never invoke reset`() = runTest {
        var repairs = 0
        val executor = executor(
            prerequisite = { SyncPrerequisiteState.READY },
            inventory = {},
            canonical = {},
            store = SingleCommandStore(null),
            prepare = {},
            upload = {},
            androidPreflight = AndroidInteroperabilityPreflight { _, _ ->
                AndroidInteroperabilityPreflightResult.RepairRequired(
                    AndroidInteroperabilityRepairReason.AUTHORITY_MISMATCH,
                )
            },
            androidRepair = AndroidInteroperabilityRepairCoordinator { _, _ ->
                repairs++
                AndroidInteroperabilityStageResult.Success
            },
        )

        assertEquals(SyncPassOutcome.ACTION_REQUIRED, executor.execute(request()))
        assertEquals(0, repairs)
    }

    @Test
    fun `04-FAILURE Android local persistence failure stops before network`() = runTest {
        var externalCalls = 0
        val android = object : AndroidInteroperabilityStage {
            override suspend fun ingest(context: AndroidInteroperabilityContext, isCancelled: () -> Boolean) =
                AndroidInteroperabilityStageResult.LocalPersistenceFailure

            override suspend fun project(context: AndroidInteroperabilityContext, isCancelled: () -> Boolean) =
                AndroidInteroperabilityStageResult.Success
        }
        val executor = executor(
            prerequisite = { externalCalls++; SyncPrerequisiteState.READY },
            inventory = { externalCalls++ },
            canonical = { externalCalls++ },
            store = SingleCommandStore(command()),
            prepare = { externalCalls++ },
            upload = { externalCalls++ },
            android = android,
        )

        assertEquals(SyncPassOutcome.FAILED, executor.execute(request()))
        assertEquals(0, externalCalls)
    }

    @Test
    fun `04-CANCEL cancellation after Android ingestion prevents projection and prerequisites`() = runTest {
        var generation = 0L
        val order = mutableListOf<String>()
        val android = object : AndroidInteroperabilityStage {
            override suspend fun ingest(context: AndroidInteroperabilityContext, isCancelled: () -> Boolean) =
                AndroidInteroperabilityStageResult.Success.also {
                    order += "android-ingest"
                    generation++
                }

            override suspend fun project(context: AndroidInteroperabilityContext, isCancelled: () -> Boolean) =
                AndroidInteroperabilityStageResult.Success.also { order += "android-project" }
        }
        val executor = executor(
            prerequisite = { order += "prerequisite"; SyncPrerequisiteState.READY },
            inventory = {},
            canonical = {},
            store = SingleCommandStore(command()),
            prepare = {},
            upload = {},
            android = android,
        )

        assertEquals(SyncPassOutcome.CANCELLED, executor.execute(request { generation }))
        assertEquals(listOf("android-ingest"), order)
    }

    @Test
    fun `04-CANCEL cancellation after local projection prevents prerequisites`() = runTest {
        var generation = 0L
        val order = mutableListOf<String>()
        val android = object : AndroidInteroperabilityStage {
            override suspend fun ingest(context: AndroidInteroperabilityContext, isCancelled: () -> Boolean) =
                AndroidInteroperabilityStageResult.Success.also { order += "android-ingest" }

            override suspend fun project(context: AndroidInteroperabilityContext, isCancelled: () -> Boolean) =
                AndroidInteroperabilityStageResult.Success.also {
                    order += "android-project"
                    generation++
                }
        }
        val executor = executor(
            prerequisite = { order += "prerequisite"; SyncPrerequisiteState.READY },
            inventory = {},
            canonical = {},
            store = SingleCommandStore(command()),
            prepare = {},
            upload = {},
            android = android,
        )

        assertEquals(SyncPassOutcome.CANCELLED, executor.execute(request { generation }))
        assertEquals(listOf("android-ingest", "android-project"), order)
    }

    @Test
    fun `04-CANCEL cancelled outbox drain prevents final Android projection`() = runTest {
        var generation = 0L
        var projections = 0
        var uploads = 0
        val android = object : AndroidInteroperabilityStage {
            override suspend fun ingest(context: AndroidInteroperabilityContext, isCancelled: () -> Boolean) =
                AndroidInteroperabilityStageResult.Success

            override suspend fun project(context: AndroidInteroperabilityContext, isCancelled: () -> Boolean) =
                AndroidInteroperabilityStageResult.Success.also { projections++ }
        }
        val executor = executor(
            prerequisite = { SyncPrerequisiteState.READY },
            inventory = {},
            canonical = {},
            store = SingleCommandStore(command()),
            prepare = { generation++ },
            upload = { uploads++ },
            android = android,
        )

        assertEquals(SyncPassOutcome.CANCELLED, executor.execute(request { generation }))
        assertEquals(1, projections)
        assertEquals(0, uploads)
    }

    @Test
    fun `03-CANCEL account cancellation after remote commit prevents upload`() = runTest {
        var generation = 0L
        var uploads = 0
        val request = SyncPassRequest(
            setOf(SyncTrigger.MANUAL), SyncScope.INCREMENTAL, 0, { generation }, 0, { 0 },
        )
        val executor = executor(
            prerequisite = { SyncPrerequisiteState.READY },
            inventory = {},
            canonical = { generation++ },
            store = SingleCommandStore(command()),
            prepare = {},
            upload = { uploads++ },
        )

        assertEquals(SyncPassOutcome.CANCELLED, executor.execute(request))
        assertEquals(0, uploads)
    }

    @Test
    fun `03-STATUS every terminal outcome is published once`() = runTest {
        val published = mutableListOf<SyncPassOutcome>()
        val executor = executor(
            prerequisite = { SyncPrerequisiteState.OFFLINE },
            inventory = {},
            canonical = {},
            store = SingleCommandStore(command()),
            prepare = {},
            upload = {},
            publish = { published += it },
        )

        assertEquals(SyncPassOutcome.RETRY_WAITING, executor.execute(request()))
        assertEquals(listOf(SyncPassOutcome.RETRY_WAITING), published)
    }

    @Test
    fun `03-FAULT exceptions at composed pass boundaries fail closed and publish once`() = runTest {
        val boundaries = listOf("prerequisite", "inventory", "canonical", "prepare", "upload")

        boundaries.forEach { faultBoundary ->
            val published = mutableListOf<SyncPassOutcome>()
            val executor = executor(
                prerequisite = {
                    if (faultBoundary == "prerequisite") error("PRIVATE_REMOTE_TEXT")
                    SyncPrerequisiteState.READY
                },
                inventory = {
                    if (faultBoundary == "inventory") error("PRIVATE_REMOTE_TEXT")
                },
                canonical = {
                    if (faultBoundary == "canonical") error("PRIVATE_REMOTE_TEXT")
                },
                store = SingleCommandStore(command()),
                prepare = {
                    if (faultBoundary == "prepare") error("PRIVATE_REMOTE_TEXT")
                },
                upload = {
                    if (faultBoundary == "upload") error("PRIVATE_REMOTE_TEXT")
                },
                publish = { published += it },
            )

            assertEquals(faultBoundary, SyncPassOutcome.FAILED, executor.execute(request()))
            assertEquals(faultBoundary, listOf(SyncPassOutcome.FAILED), published)
            assertFalse(faultBoundary, published.toString().contains("PRIVATE_REMOTE_TEXT"))
        }
    }

    @Test
    fun `03-CANCEL coroutine cancellation is sanitized and published once`() = runTest {
        val published = mutableListOf<SyncPassOutcome>()
        val executor = executor(
            prerequisite = { SyncPrerequisiteState.READY },
            inventory = { throw CancellationException("PRIVATE_REMOTE_TEXT") },
            canonical = {},
            store = SingleCommandStore(command()),
            prepare = {},
            upload = {},
            publish = { published += it },
        )

        assertEquals(SyncPassOutcome.CANCELLED, executor.execute(request()))
        assertEquals(listOf(SyncPassOutcome.CANCELLED), published)
        assertFalse(published.toString().contains("PRIVATE_REMOTE_TEXT"))
    }

    @Test
    fun `03-DIAGNOSTIC fixed pass stages identify a failing boundary without payloads`() = runTest {
        val observed = mutableListOf<SyncPassStage>()
        val failures = mutableListOf<Pair<SyncPassStage, SyncPassExceptionCategory>>()
        val outcomes = mutableListOf<SyncPassOutcome>()
        val executor = executor(
            prerequisite = { SyncPrerequisiteState.READY },
            inventory = { error("PRIVATE_REMOTE_TEXT") },
            canonical = {},
            store = SingleCommandStore(null),
            prepare = {},
            upload = {},
            stageObserver = object : SyncPassStageObserver {
                override fun onStage(stage: SyncPassStage) { observed += stage }
                override fun onException(category: SyncPassExceptionCategory) { failures += observed.last() to category }
                override fun onOutcome(outcome: SyncPassOutcome) { outcomes += outcome }
            },
        )

        assertEquals(SyncPassOutcome.FAILED, executor.execute(request()))
        assertEquals(listOf(SyncPassStage.REMOTE_CONTACTS to SyncPassExceptionCategory.INVALID_STATE), failures)
        assertEquals(listOf(SyncPassOutcome.FAILED), outcomes)
        assertEquals(SyncPassStage.COMPLETE, observed.last())
        assertEquals(
            SyncPassStage.REMOTE_CONTACTS,
            observed.last {
                it != SyncPassStage.STATUS_PUBLICATION && it != SyncPassStage.COMPLETE
            },
        )
        assertEquals(
            listOf(
                SyncPassStage.REQUEST_GATE,
                SyncPassStage.ANDROID_PREFLIGHT,
                SyncPassStage.ANDROID_INGEST,
                SyncPassStage.ANDROID_PREFLIGHT,
                SyncPassStage.ANDROID_INITIAL_PROJECTION,
                SyncPassStage.PREREQUISITES,
                SyncPassStage.REMOTE_GROUPS,
                SyncPassStage.REMOTE_CONTACTS,
                SyncPassStage.STATUS_PUBLICATION,
                SyncPassStage.COMPLETE,
            ),
            observed,
        )
        assertFalse(observed.toString().contains("PRIVATE_REMOTE_TEXT"))
    }

    @Test
    fun `diagnostic observer failures cannot alter publication or pass result`() = runTest {
        for (failPass in listOf(false, true)) {
            val published = mutableListOf<SyncPassOutcome>()
            val executor = executor(
                prerequisite = { SyncPrerequisiteState.READY },
                inventory = { if (failPass) error("PRIVATE_REMOTE_TEXT") }, canonical = {},
                store = SingleCommandStore(null), prepare = {}, upload = {}, publish = { published += it },
                stageObserver = object : SyncPassStageObserver {
                    override fun onStage(stage: SyncPassStage) { error("observer") }
                    override fun onException(category: SyncPassExceptionCategory) { error("observer") }
                    override fun onOutcome(outcome: SyncPassOutcome) { error("observer") }
                },
            )
            val expected = if (failPass) SyncPassOutcome.FAILED else SyncPassOutcome.SUCCESS
            assertEquals(expected, executor.execute(request()))
            assertEquals(listOf(expected), published)
        }
    }

    @Test
    fun `publication exception is categorized but still propagates unchanged`() = runTest {
        val failure = java.io.IOException("PRIVATE_LOCAL_TEXT")
        val observed = mutableListOf<SyncPassStage>()
        val categories = mutableListOf<SyncPassExceptionCategory>()
        val outcomes = mutableListOf<SyncPassOutcome>()
        val executor = executor(
            prerequisite = { SyncPrerequisiteState.READY }, inventory = {}, canonical = {},
            store = SingleCommandStore(null), prepare = {}, upload = {}, publish = { throw failure },
            stageObserver = object : SyncPassStageObserver {
                override fun onStage(stage: SyncPassStage) { observed += stage }
                override fun onException(category: SyncPassExceptionCategory) { categories += category }
                override fun onOutcome(outcome: SyncPassOutcome) { outcomes += outcome }
            },
        )
        try {
            executor.execute(request())
            org.junit.Assert.fail("Publication failure must still propagate")
        } catch (caught: java.io.IOException) {
            org.junit.Assert.assertSame(failure, caught)
        }
        assertEquals(SyncPassStage.STATUS_PUBLICATION, observed.last())
        assertEquals(listOf(SyncPassExceptionCategory.IO), categories)
        assertTrue(outcomes.isEmpty())
    }

    @Test
    fun `07-REPAIR checkpoints phases and clears only after status publication`() = runTest {
        val events = mutableListOf<String>()
        val repair = MemoryFullRepair(events)
        val android = object : AndroidInteroperabilityStage {
            override suspend fun ingest(context: AndroidInteroperabilityContext, isCancelled: () -> Boolean) =
                AndroidInteroperabilityStageResult.Success.also { events += "android-ingest" }
            override suspend fun project(context: AndroidInteroperabilityContext, isCancelled: () -> Boolean) =
                AndroidInteroperabilityStageResult.Success.also { events += "android" }
        }
        val executor = executor(
            prerequisite = { SyncPrerequisiteState.READY },
            inventory = { events += "remote" },
            canonical = {},
            store = SingleCommandStore(null),
            prepare = {},
            upload = {},
            publish = { events += "publish" },
            android = android,
            fullRepairCoordinator = FullRepairExecutionCoordinator { repair },
        )

        assertEquals(SyncPassOutcome.SUCCESS, executor.execute(fullRepairRequest()))
        assertEquals(
            listOf(
                "android-ingest", "remote", "REMOTE_ENUMERATION:1", "CANONICAL_RECONCILIATION:0",
                "CANONICAL_RECONCILIATION:1", "android", "ANDROID_PROJECTION:0",
                "ANDROID_PROJECTION:1", "PUBLISHING:0", "PUBLISHING:1", "publish", "clear",
            ),
            events,
        )
        assertTrue(repair.cleared)
    }

    @Test
    fun `full repair refreshes account revision after remote reconciliation before projection`() = runTest {
        var revision = 0L
        var projected = false
        val executor = executor(
            prerequisite = { SyncPrerequisiteState.READY }, inventory = {},
            canonical = { revision++ }, store = SingleCommandStore(null), prepare = {}, upload = {},
            androidPreflight = AndroidInteroperabilityPreflight { account, _ ->
                AndroidInteroperabilityPreflightResult.Ready(
                    AndroidInteroperabilityContext(account, "android-account", revision, 0),
                )
            },
            android = object : AndroidInteroperabilityStage {
                override suspend fun ingest(context: AndroidInteroperabilityContext, isCancelled: () -> Boolean) =
                    AndroidInteroperabilityStageResult.Success
                override suspend fun project(context: AndroidInteroperabilityContext, isCancelled: () -> Boolean): AndroidInteroperabilityStageResult {
                    projected = true
                    assertTrue(revision > 0)
                    return if (context.accountRevision == revision) AndroidInteroperabilityStageResult.Success
                    else AndroidInteroperabilityStageResult.RetryWaiting
                }
            },
            fullRepairCoordinator = FullRepairExecutionCoordinator { MemoryFullRepair(mutableListOf()) },
        )
        assertEquals(SyncPassOutcome.SUCCESS, executor.execute(fullRepairRequest()))
        assertTrue(projected)
    }

    @Test
    fun `full repair does not project when permission disappears during remote reconciliation`() = runTest {
        var permissionLost = false
        val repair = MemoryFullRepair(mutableListOf())
        val executor = executor(
            prerequisite = { SyncPrerequisiteState.READY }, inventory = {},
            canonical = { permissionLost = true }, store = SingleCommandStore(null), prepare = {}, upload = {},
            androidPreflight = AndroidInteroperabilityPreflight { account, _ ->
                if (permissionLost) AndroidInteroperabilityPreflightResult.PermissionDenied
                else AndroidInteroperabilityPreflightResult.Ready(
                    AndroidInteroperabilityContext(account, "android-account", 0, 0),
                )
            },
            android = object : AndroidInteroperabilityStage {
                override suspend fun ingest(context: AndroidInteroperabilityContext, isCancelled: () -> Boolean) =
                    AndroidInteroperabilityStageResult.Success
                override suspend fun project(context: AndroidInteroperabilityContext, isCancelled: () -> Boolean): AndroidInteroperabilityStageResult =
                    error("Projection must not run with stale permission")
            },
            fullRepairCoordinator = FullRepairExecutionCoordinator { repair },
        )
        assertEquals(SyncPassOutcome.ACTION_REQUIRED, executor.execute(fullRepairRequest()))
        assertFalse(repair.cleared)
    }

    @Test
    fun `07-REPAIR process recreation skips completed canonical phase`() = runTest {
        val events = mutableListOf<String>()
        val repair = MemoryFullRepair(events).apply {
            progress = progress.copy(
                revision = 3,
                phase = FullRepairPhase.CANONICAL_RECONCILIATION,
                completedUnits = 1,
                totalUnits = 1,
            )
        }
        val android = object : AndroidInteroperabilityStage {
            override suspend fun ingest(context: AndroidInteroperabilityContext, isCancelled: () -> Boolean) =
                AndroidInteroperabilityStageResult.Success
            override suspend fun project(context: AndroidInteroperabilityContext, isCancelled: () -> Boolean) =
                AndroidInteroperabilityStageResult.Success.also { events += "android" }
        }
        val executor = executor(
            prerequisite = { SyncPrerequisiteState.READY },
            inventory = { events += "remote" },
            canonical = {},
            store = SingleCommandStore(null),
            prepare = {},
            upload = {},
            android = android,
            fullRepairCoordinator = FullRepairExecutionCoordinator { repair },
        )

        assertEquals(SyncPassOutcome.SUCCESS, executor.execute(fullRepairRequest()))
        assertFalse(events.contains("remote"))
        assertTrue(events.contains("android"))
    }

    @Test
    fun `07-REPAIR explicit cancellation clears attempt without doing work`() = runTest {
        val events = mutableListOf<String>()
        val repair = MemoryFullRepair(events, cancellationRequested = true)
        val executor = executor(
            prerequisite = { SyncPrerequisiteState.READY },
            inventory = { events += "remote" },
            canonical = {},
            store = SingleCommandStore(null),
            prepare = {},
            upload = {},
            publish = { events += "publish" },
            fullRepairCoordinator = FullRepairExecutionCoordinator { repair },
        )

        assertEquals(SyncPassOutcome.CANCELLED, executor.execute(fullRepairRequest()))
        assertEquals(listOf("publish", "cancel-clear"), events)
    }

    private fun executor(
        prerequisite: suspend () -> SyncPrerequisiteState,
        inventory: () -> Unit,
        canonical: () -> Unit,
        store: SingleCommandStore,
        prepare: () -> Unit,
        upload: () -> Unit,
        publish: suspend (SyncPassOutcome) -> Unit = {},
        android: AndroidInteroperabilityStage = successfulAndroidStage(),
        androidPreflight: AndroidInteroperabilityPreflight = readyAndroidPreflight(),
        androidRepair: AndroidInteroperabilityRepairCoordinator =
            AndroidInteroperabilityRepairCoordinator { _, _ -> AndroidInteroperabilityStageResult.ActionRequired },
        groupStage: IncrementalRemoteGroupStage? = null,
        stageObserver: SyncPassStageObserver = SyncPassStageObserver { },
        androidStageResultObserver: AndroidSyncStageResultObserver =
            AndroidSyncStageResultObserver { _, _ -> },
        fullRepairCoordinator: FullRepairExecutionCoordinator = MissingFullRepairExecutionCoordinator,
        uploadOutcome: () -> GatewayOutcome<RemoteMutationAcknowledgement> = {
            GatewayOutcome.Success(RemoteMutationAcknowledgement("remote", "version"))
        },
    ): ContakoSyncPassExecutor {
        val checkpoint = MemoryCheckpointStore()
        val metadata = ContactInventoryMetadata(
            RemoteContactId("remote"), "Contact", RemoteVersion("version"), 1, 1,
            emptyList(), emptyList(), ContactInventoryVersionProvenance.REMOTE_SERVER,
            ContactInventoryCoverage.AUTHORITATIVE_REMOTE_REVISION,
        )
        val stage = IncrementalRemoteContactStage(
            ProtonContactInventoryGateway { _, _ ->
                inventory()
                GatewayOutcome.Success(
                    ContactInventoryPage(
                        listOf(metadata), null, null, 1,
                        ContactInventorySnapshotAuthority.AUTHORITATIVE_REMOTE_REVISION,
                    ),
                )
            },
            ProtonVerifiedContactCardGateway { _, id ->
                GatewayOutcome.Success(
                    VerifiedContactCard(id, RemoteVersion("version"), CanonicalContact("account", "remote")),
                )
            },
            PersistentContactInventoryPlanner(checkpoint),
            RemoteCanonicalReconciliationStore { _, _, cards, labels, deletions ->
                canonical()
                CanonicalReconciliationReceipt(cards.map { it.id }.toSet(), labels, deletions)
            },
        )
        val orchestrator = DurableMutationOrchestrator(
            store,
            MutationPreparationGateway {
                prepare()
                GatewayOutcome.Success(MutationPreparation.UploadAllowed)
            },
            MutationUploadGateway {
                upload()
                uploadOutcome()
            },
        )
        return ContakoSyncPassExecutor(
            AccountScope("account"),
            SyncPrerequisiteChecker(prerequisite),
            stage,
            orchestrator,
            { 1_000 },
            groupStage = groupStage,
            statusPublisher = SyncPassStatusPublisher(publish),
            androidPreflight = androidPreflight,
            androidStage = android,
            androidRepair = androidRepair,
            stageObserver = stageObserver,
            androidStageResultObserver = androidStageResultObserver,
            fullRepairCoordinator = fullRepairCoordinator,
        )
    }

    private fun request(currentGeneration: () -> Long = { 0 }) = SyncPassRequest(
        setOf(SyncTrigger.MANUAL), SyncScope.INCREMENTAL, 0, currentGeneration, 0, { 0 },
    )

    private fun fullRepairRequest() = SyncPassRequest(
        setOf(SyncTrigger.MANUAL), SyncScope.FULL_REPAIR, 0, { 0 }, 0, { 0 },
    )

    private fun command() = DurableMutationCommand(
        "account", "CONTACT", "local", 1, RemoteMutationOperation.UPDATE, 0, 0, false,
    )

    private fun successfulAndroidStage(): AndroidInteroperabilityStage =
        object : AndroidInteroperabilityStage {
            override suspend fun ingest(context: AndroidInteroperabilityContext, isCancelled: () -> Boolean) =
                if (isCancelled()) AndroidInteroperabilityStageResult.Cancelled
                else AndroidInteroperabilityStageResult.Success

            override suspend fun project(context: AndroidInteroperabilityContext, isCancelled: () -> Boolean) =
                if (isCancelled()) AndroidInteroperabilityStageResult.Cancelled
                else AndroidInteroperabilityStageResult.Success
        }

    private fun readyAndroidPreflight() = AndroidInteroperabilityPreflight { account, isCancelled ->
        if (isCancelled()) AndroidInteroperabilityPreflightResult.Cancelled
        else AndroidInteroperabilityPreflightResult.Ready(
            AndroidInteroperabilityContext(account, "android-account", 0, 0),
        )
    }

    private fun divergedPreflight(context: AndroidInteroperabilityContext) =
        AndroidInteroperabilityPreflight { _, _ ->
            AndroidInteroperabilityPreflightResult.RepairRequired(
                AndroidInteroperabilityRepairReason.PROVIDER_STATE_DIVERGED,
                context,
            )
        }
}

private class MemoryFullRepair(
    private val events: MutableList<String>,
    private val cancellationRequested: Boolean = false,
) : FullRepairExecution {
    override var progress = FullRepairProgress(
        0, FullRepairPhase.REMOTE_ENUMERATION, 0, null, 0, 0, false, false,
    )
    var cleared = false

    override fun isCancellationRequested() = cancellationRequested

    override suspend fun checkpoint(
        phase: FullRepairPhase,
        completedUnits: Long,
        totalUnits: Long,
    ): Boolean {
        events += "$phase:$completedUnits"
        progress = progress.copy(
            revision = progress.revision + 1,
            phase = phase,
            completedUnits = completedUnits,
            totalUnits = totalUnits,
        )
        return true
    }

    override suspend fun clearCancellation(): Boolean = true.also { events += "cancel-clear" }
    override suspend fun clearAfterPublished(): Boolean = true.also {
        cleared = true
        events += "clear"
    }
}

private class MemoryCheckpointStore : ContactInventoryCheckpointStore {
    private var current: VersionedContactInventoryCheckpoint? = null
    override suspend fun load(account: AccountScope) = current
    override suspend fun compareAndSet(
        account: AccountScope,
        expectedGeneration: Long?,
        checkpoint: ContactInventoryCheckpoint,
    ): Boolean {
        if (current?.generation != expectedGeneration) return false
        current = VersionedContactInventoryCheckpoint((expectedGeneration ?: -1) + 1, checkpoint)
        return true
    }
}

private class SingleCommandStore(private var command: DurableMutationCommand?) : MutationExecutionStore {
    private var inFlight = false
    override suspend fun recoverInterrupted(accountId: String, nowEpochMillis: Long) = 0
    override suspend fun eligible(accountId: String, nowEpochMillis: Long, limit: Int) = listOfNotNull(command)
    override suspend fun claim(command: DurableMutationCommand, nowEpochMillis: Long): Boolean {
        inFlight = this.command != null
        return inFlight
    }
    override suspend fun recordFailure(
        command: DurableMutationCommand,
        category: com.patmanak.contako.data.gateway.GatewayFailureCategory,
        decision: RetryDecision,
    ) = true
    override suspend fun acknowledgeAndFinish(
        command: DurableMutationCommand,
        acknowledgement: RemoteMutationAcknowledgement,
    ): Boolean {
        assertTrue(inFlight)
        this.command = null
        return true
    }
    override suspend fun supersedeAfterRemoteWinner(command: DurableMutationCommand) = true
    override suspend fun continueAfterPartialProgress(
        command: DurableMutationCommand,
        acknowledgement: RemoteMutationAcknowledgement,
        nowEpochMillis: Long,
    ) = true
}
