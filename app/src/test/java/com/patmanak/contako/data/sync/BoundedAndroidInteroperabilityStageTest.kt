package com.patmanak.contako.data.sync

import com.patmanak.contako.data.android.provider.AndroidOwnedGroupRowPage
import com.patmanak.contako.data.android.provider.AndroidProviderAccountName
import com.patmanak.contako.data.android.provider.AndroidStableRawContactObservationPage
import com.patmanak.contako.data.android.provider.AndroidStableRawContactPageResult
import com.patmanak.contako.data.gateway.AccountScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class BoundedAndroidInteroperabilityStageTest {
    @Test
    fun partialContactIngestionVisitsLaterPagesAndNeverReportsSuccess() = runTest {
        var visited = 0
        val stage = BoundedAndroidInteroperabilityStage(
            contactsReader = { _, after -> AndroidStableRawContactPageResult.Stable(
                AndroidStableRawContactObservationPage(
                    if (after == 0L) listOf(com.patmanak.contako.data.android.provider.AndroidStableRawContactObservation(
                        com.patmanak.contako.data.android.provider.AndroidOwnedRawContact(100L,
                            sourceIdentity = null, dirty = true, deleted = false, version = 0), emptyList())) else emptyList(),
                    if (after == 0L) 100L else null)) },
            groupsReader = { account, after -> AndroidOwnedGroupRowPage(account, after, emptyList(), null) },
            observationCoordinator = object : AndroidBoundedObservationCoordinator {
                override suspend fun ingestGroups(context: AndroidInteroperabilityContext, pages: List<AndroidOwnedGroupRowPage>) = AndroidBoundedPageResult.Applied
                override suspend fun ingestContacts(context: AndroidInteroperabilityContext, page: AndroidStableRawContactObservationPage): AndroidBoundedPageResult {
                    visited++
                    return if (visited == 1) AndroidBoundedPageResult.PartiallyApplied else AndroidBoundedPageResult.Applied
                }
            },
            projectionCoordinator = { _, _ -> error("No projection during ingestion") },
        )
        assertEquals(AndroidInteroperabilityStageResult.ActionRequired, stage.ingest(context()) { false })
        assertEquals(2, visited)
        visited = 0
        assertEquals(AndroidInteroperabilityStageResult.Cancelled, stage.ingest(context()) { visited == 1 })
        assertEquals(1, visited)
    }
    @Test
    fun `04-STAGE cancellation before provider work is terminal`() = runTest {
        var coordinatorCalls = 0
        val stage = BoundedAndroidInteroperabilityStage(
            contactsReader = { _, _ -> error("contact reader must not run") },
            groupsReader = { _, _ -> error("group reader must not run") },
            observationCoordinator = coordinator { coordinatorCalls++ },
            projectionCoordinator = { _, _ -> error("projection must not run") },
        )

        assertEquals(AndroidInteroperabilityStageResult.Cancelled, stage.ingest(context()) { true })
        assertEquals(0, coordinatorCalls)
    }

    @Test
    fun `04-STAGE projection pages are bounded and cancellation safe`() = runTest {
        var calls = 0
        val stage = stage(
            coordinator = coordinator { },
            projection = { _, after ->
                calls++
                if (after == null) AndroidProjectionPage("next", 100) to AndroidBoundedPageResult.Applied
                else AndroidProjectionPage(null, 1) to AndroidBoundedPageResult.Applied
            },
        )

        assertEquals(AndroidInteroperabilityStageResult.Success, stage.project(context()) { false })
        assertEquals(2, calls)
    }

    @Test
    fun `RF08 incomplete projection visits later pages and reports action required`() = runTest {
        var calls = 0
        val stage = stage(coordinator = coordinator { }, projection = { _, after ->
            calls++
            if (after == null) AndroidProjectionPage("next", 100) to AndroidBoundedPageResult.PartiallyApplied
            else AndroidProjectionPage(null, 1) to AndroidBoundedPageResult.Applied
        })
        assertEquals(AndroidInteroperabilityStageResult.ActionRequired, stage.project(context()) { false })
        assertEquals(2, calls)
    }

    @Test
    fun `04-STAGE repair and persistence results remain distinct`() = runTest {
        listOf(
            AndroidBoundedPageResult.RepairRequired to AndroidInteroperabilityStageResult.ActionRequired,
            AndroidBoundedPageResult.ReplanRequired to AndroidInteroperabilityStageResult.RetryWaiting,
            AndroidBoundedPageResult.LocalPersistenceFailure to AndroidInteroperabilityStageResult.LocalPersistenceFailure,
        ).forEach { (pageResult, expected) ->
            val stage = stage(
                coordinator = coordinator { },
                projection = { _, _ -> AndroidProjectionPage(null, 0) to pageResult },
            )
            assertEquals(expected, stage.project(context()) { false })
        }
    }

    private fun stage(
        coordinator: AndroidBoundedObservationCoordinator,
        projection: AndroidBoundedProjectionCoordinator,
    ) = BoundedAndroidInteroperabilityStage(
        contactsReader = { _, _ ->
            AndroidStableRawContactPageResult.Stable(AndroidStableRawContactObservationPage(emptyList(), null))
        },
        groupsReader = { account, after -> AndroidOwnedGroupRowPage(account, after, emptyList(), null) },
        observationCoordinator = coordinator,
        projectionCoordinator = projection,
    )

    private fun coordinator(onCall: () -> Unit) = object : AndroidBoundedObservationCoordinator {
        override suspend fun ingestGroups(
            context: AndroidInteroperabilityContext,
            pages: List<AndroidOwnedGroupRowPage>,
        ) = AndroidBoundedPageResult.Applied.also { onCall() }

        override suspend fun ingestContacts(
            context: AndroidInteroperabilityContext,
            page: AndroidStableRawContactObservationPage,
        ) = AndroidBoundedPageResult.Applied.also { onCall() }
    }

    private fun context() = AndroidInteroperabilityContext(AccountScope("account"), "android-account", 0, 0)
}
