package com.patmanak.contako.data.sync

import com.patmanak.contako.data.android.provider.AndroidOwnedGroupRowPage
import com.patmanak.contako.data.android.provider.AndroidOwnedRawContact
import com.patmanak.contako.data.android.provider.AndroidProviderAccountName
import com.patmanak.contako.data.android.provider.AndroidStableRawContactObservation
import com.patmanak.contako.data.android.provider.AndroidStableRawContactObservationPage
import com.patmanak.contako.data.android.provider.AndroidStableRawContactPageResult
import com.patmanak.contako.data.gateway.AccountScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Proof propagation only. Exact durable issue retention is checked separately with Room. */
class TargetedAndroidScopedFailureProofTest {
    @Test fun allPartialProjectionPagesNeedExplicitScopedProofAndDoNotChangeTheOutcome() = runTest {
        for (proofs in listOf(listOf(true, true), listOf(true, false), listOf(false, true))) {
            val projector = Projection(proofs.map { AndroidBoundedPageResult.PartiallyApplied to it })
            val stage = stage(projector)
            assertEquals(AndroidInteroperabilityStageResult.ActionRequired, stage.project(CONTEXT) { false })
            assertEquals(proofs.all { it }, stage.lastFailureIsFullyScoped)
        }
    }

    @Test fun laterUnscopedProjectionFailureAndTheNextOperationCannotReuseAnEarlierProof() = runTest {
        val terminal = stage(Projection(listOf(AndroidBoundedPageResult.PartiallyApplied to true,
            AndroidBoundedPageResult.RepairRequired to false)))
        assertEquals(AndroidInteroperabilityStageResult.ActionRequired, terminal.project(CONTEXT) { false })
        assertFalse(terminal.lastFailureIsFullyScoped)
        val projector = Projection(listOf(AndroidBoundedPageResult.PartiallyApplied to true))
        val stage = stage(projector)
        assertEquals(AndroidInteroperabilityStageResult.ActionRequired, stage.project(CONTEXT) { false })
        assertTrue(stage.lastFailureIsFullyScoped)
        projector.results = listOf(AndroidBoundedPageResult.Applied to true) // Stale delegate proof is not enough.
        assertEquals(AndroidInteroperabilityStageResult.Success, stage.project(CONTEXT) { false })
        assertFalse(stage.lastFailureIsFullyScoped)
        assertEquals(AndroidInteroperabilityStageResult.Cancelled, stage.project(CONTEXT) { true })
        assertFalse(stage.lastFailureIsFullyScoped)
    }

    @Test fun ingestAggregatesOnlyThePartialPagesWhoseCoordinatorExplicitlyRetainedEveryFailure() = runTest {
        for (proofs in listOf(listOf(true, true), listOf(true, false), listOf(false, true))) {
            var pageIndex = 0
            val coordinator = object : AndroidBoundedObservationCoordinator, AndroidScopedFailureProofSource {
                override var lastFailureIsFullyScoped = false
                override suspend fun ingestGroups(context: AndroidInteroperabilityContext,
                    pages: List<AndroidOwnedGroupRowPage>) = AndroidBoundedPageResult.Applied
                override suspend fun ingestContacts(context: AndroidInteroperabilityContext,
                    page: AndroidStableRawContactObservationPage): AndroidBoundedPageResult {
                    lastFailureIsFullyScoped = proofs[pageIndex++]
                    return AndroidBoundedPageResult.PartiallyApplied
                }
            }
            val stage = BoundedAndroidInteroperabilityStage(
                contactsReader = { _, after ->
                    val id = after + 1
                    AndroidStableRawContactPageResult.Stable(AndroidStableRawContactObservationPage(
                        listOf(observation(id)), if (id < 2) id else null))
                }, groupsReader = { name, after -> AndroidOwnedGroupRowPage(name, after, emptyList(), null) },
                observationCoordinator = coordinator, projectionCoordinator = { _, _ -> error("No projection") },
            )
            assertEquals(AndroidInteroperabilityStageResult.ActionRequired, stage.ingest(CONTEXT) { false })
            assertEquals(proofs.all { it }, stage.lastFailureIsFullyScoped)
            assertEquals(2, pageIndex)
        }
    }

    @Test fun aPartialResultWithoutAProofSourceRemainsUnscoped() = runTest {
        val stage = stage(AndroidBoundedProjectionCoordinator { _, _ ->
            AndroidProjectionPage(null, 1) to AndroidBoundedPageResult.PartiallyApplied
        })
        assertEquals(AndroidInteroperabilityStageResult.ActionRequired, stage.project(CONTEXT) { false })
        assertFalse(stage.lastFailureIsFullyScoped)
    }

    @Test fun theGroupWrapperRelaysContactProofButNeverAttributesGroupOrTerminalFailures() = runTest {
        val delegate = object : AndroidProductionContactObservationCoordinator, AndroidScopedFailureProofSource {
            var result: AndroidBoundedPageResult = AndroidBoundedPageResult.PartiallyApplied
            override val lastFailureIsFullyScoped = true
            override fun acceptGroupCatalog(context: AndroidInteroperabilityContext, pages: List<AndroidOwnedGroupRowPage>) = Unit
            override suspend fun ingest(context: AndroidInteroperabilityContext,
                page: AndroidStableRawContactObservationPage) = result
        }
        val wrapper = GroupOnlyProductionAndroidBoundedObservationCoordinator.forTest(
            planner = { _, _ -> error("No group rows") }, committer = { error("No group rows") },
            providerFinalizer = { _, _, _ -> error("No group rows") }, contactCoordinator = delegate)
        val page = AndroidStableRawContactObservationPage(listOf(observation(1)), null)
        assertEquals(AndroidBoundedPageResult.PartiallyApplied, wrapper.ingestContacts(CONTEXT, page))
        assertTrue(wrapper.lastFailureIsFullyScoped)
        assertEquals(AndroidBoundedPageResult.Applied, wrapper.ingestGroups(CONTEXT,
            listOf(AndroidOwnedGroupRowPage(AndroidProviderAccountName(CONTEXT.androidAccountName), 0, emptyList(), null))))
        assertFalse(wrapper.lastFailureIsFullyScoped)
        delegate.result = AndroidBoundedPageResult.RepairRequired
        assertEquals(AndroidBoundedPageResult.RepairRequired, wrapper.ingestContacts(CONTEXT, page))
        assertFalse(wrapper.lastFailureIsFullyScoped)
    }

    private class Projection(var results: List<Pair<AndroidBoundedPageResult, Boolean>>) :
        AndroidBoundedProjectionCoordinator, AndroidScopedFailureProofSource {
        override var lastFailureIsFullyScoped = false
        override suspend fun projectPage(context: AndroidInteroperabilityContext,
            afterKey: String?): Pair<AndroidProjectionPage, AndroidBoundedPageResult> {
            val index = afterKey?.toInt() ?: 0
            val (result, proof) = results[index]
            lastFailureIsFullyScoped = proof
            return AndroidProjectionPage((index + 1).takeIf { it < results.size }?.toString(), 1) to result
        }
    }

    private fun stage(projector: AndroidBoundedProjectionCoordinator) = BoundedAndroidInteroperabilityStage(
        contactsReader = { _, _ -> error("No contact ingestion") },
        groupsReader = { _, _ -> error("No group ingestion") },
        observationCoordinator = object : AndroidBoundedObservationCoordinator {
            override suspend fun ingestGroups(context: AndroidInteroperabilityContext,
                pages: List<AndroidOwnedGroupRowPage>): AndroidBoundedPageResult = error("No group ingestion")
            override suspend fun ingestContacts(context: AndroidInteroperabilityContext,
                page: AndroidStableRawContactObservationPage): AndroidBoundedPageResult = error("No contact ingestion")
        }, projectionCoordinator = projector,
    )
    private companion object {
        val CONTEXT = AndroidInteroperabilityContext(AccountScope("synthetic-owner"), "synthetic-android", 1, 0)
        fun observation(id: Long) = AndroidStableRawContactObservation(
            AndroidOwnedRawContact(id, "synthetic-$id", "remote-$id", dirty = true, deleted = false, version = 1), emptyList())
    }
}
