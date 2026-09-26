package com.patmanak.contako.data.sync

import com.patmanak.contako.data.android.mapping.AndroidGroupSnapshot
import com.patmanak.contako.data.android.provider.AndroidOwnedGroupRow
import com.patmanak.contako.data.android.provider.AndroidOwnedGroupRowPage
import com.patmanak.contako.data.android.provider.AndroidProviderAccountName
import com.patmanak.contako.data.android.provider.AndroidStableRawContactObservationPage
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.local.RoomAndroidGroupRowObservationClassification
import com.patmanak.contako.data.local.RoomAndroidGroupRowObservationCommand
import com.patmanak.contako.data.local.RoomAndroidGroupRowObservationResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class GroupOnlyProductionAndroidObservationCoordinatorTest {
    @Test
    fun `04-GROUP coordinator commits every row before provider finalization`() = runTest {
        val events = mutableListOf<String>()
        var revision = 3L
        val coordinator = coordinator(
            planner = { context, row ->
                AndroidGroupObservationPlanResult.Ready(plan(context, row, revision++))
            },
            committer = { command ->
                events += "commit:${command.groupRowLocator}:${command.expectedAccountRevision}"
                RoomAndroidGroupRowObservationResult.Applied(
                    RoomAndroidGroupRowObservationClassification.ANDROID_EDIT_COMMITTED,
                    command.expectedAccountRevision + 1,
                    (command.expectedGroupLedgerRevision ?: 0) + 1,
                )
            },
            finalizer = { _, row, _ ->
                events += "provider:${row.groupRowId}"
                AndroidBoundedPageResult.Applied
            },
        )

        assertEquals(AndroidBoundedPageResult.Applied, coordinator.ingestGroups(context(), pages(row(4), row(9))))
        assertEquals(
            listOf("commit:4:3", "provider:4", "commit:9:4", "provider:9"),
            events,
        )
    }

    @Test
    fun `04-GROUP stale durable commit never acknowledges DIRTY and stops page`() = runTest {
        var finalized = 0
        var planned = 0
        val coordinator = coordinator(
            planner = { context, row ->
                planned++
                AndroidGroupObservationPlanResult.Ready(plan(context, row, 8))
            },
            committer = { RoomAndroidGroupRowObservationResult.StaleAccount },
            finalizer = { _, _, _ -> finalized++; AndroidBoundedPageResult.Applied },
        )

        assertEquals(
            AndroidBoundedPageResult.ReplanRequired,
            coordinator.ingestGroups(context(), pages(row(4), row(9))),
        )
        assertEquals(1, planned)
        assertEquals(0, finalized)
    }

    @Test
    fun `04-GROUP repair and local persistence classifications remain fail closed`() = runTest {
        listOf(
            AndroidGroupObservationPlanResult.RepairRequired to AndroidBoundedPageResult.RepairRequired,
            AndroidGroupObservationPlanResult.LocalPersistenceFailure to
                AndroidBoundedPageResult.LocalPersistenceFailure,
        ).forEach { (planned, expected) ->
            val coordinator = coordinator(
                planner = { _, _ -> planned },
                committer = { error("commit must not run") },
                finalizer = { _, _, _ -> error("provider must not run") },
            )
            assertEquals(expected, coordinator.ingestGroups(context(), pages(row(4))))
        }
    }

    @Test
    fun `08-DIAGNOSTIC group repair observer emits only the closed boundary cause`() = runTest {
        val observed = mutableListOf<AndroidIngestActionRequiredReason>()
        val coordinator = coordinator(
            planner = { _, _ -> AndroidGroupObservationPlanResult.RepairRequired },
            committer = { error("commit must not run") },
            finalizer = { _, _, _ -> error("provider must not run") },
            observer = AndroidIngestActionRequiredObserver(observed::add),
        )

        assertEquals(AndroidBoundedPageResult.RepairRequired, coordinator.ingestGroups(context(), pages(row(4))))
        assertEquals(listOf(AndroidIngestActionRequiredReason.GROUP_PLAN), observed)

        val inert = coordinator(
            planner = { _, _ -> AndroidGroupObservationPlanResult.RepairRequired },
            committer = { error("commit must not run") },
            finalizer = { _, _, _ -> error("provider must not run") },
            observer = AndroidIngestActionRequiredObserver { error("PRIVATE_PROVIDER_TEXT") },
        )
        assertEquals(AndroidBoundedPageResult.RepairRequired, inert.ingestGroups(context(), pages(row(4))))
    }

    @Test
    fun `04-GROUP rejects foreign or discontinuous page chains without writes`() = runTest {
        var planned = 0
        val coordinator = coordinator(
            planner = { _, _ -> planned++; AndroidGroupObservationPlanResult.RepairRequired },
            committer = { error("commit must not run") },
            finalizer = { _, _, _ -> error("provider must not run") },
        )
        val foreign = AndroidOwnedGroupRowPage(AndroidProviderAccountName("foreign"), 0, listOf(row(4)), null)
        val discontinuous = listOf(
            AndroidOwnedGroupRowPage(AndroidProviderAccountName(ANDROID_ACCOUNT), 0, listOf(row(4)), 4),
            AndroidOwnedGroupRowPage(AndroidProviderAccountName(ANDROID_ACCOUNT), 5, listOf(row(9)), null),
        )

        assertEquals(AndroidBoundedPageResult.RepairRequired, coordinator.ingestGroups(context(), listOf(foreign)))
        assertEquals(AndroidBoundedPageResult.RepairRequired, coordinator.ingestGroups(context(), discontinuous))
        assertEquals(0, planned)
    }

    @Test
    fun `04-GROUP contact path explicitly requires repair`() = runTest {
        val coordinator = coordinator(
            planner = { _, _ -> error("unused") },
            committer = { error("unused") },
            finalizer = { _, _, _ -> error("unused") },
        )
        assertEquals(
            AndroidBoundedPageResult.RepairRequired,
            coordinator.ingestContacts(context(), AndroidStableRawContactObservationPage(emptyList(), null)),
        )
    }

    @Test
    fun `04-CONTACT group catalog is installed before contact page delegation`() = runTest {
        val events = mutableListOf<String>()
        val contacts = object : AndroidProductionContactObservationCoordinator {
            override fun acceptGroupCatalog(
                context: AndroidInteroperabilityContext,
                pages: List<AndroidOwnedGroupRowPage>,
            ) {
                events += "catalog:${pages.single().groups.single().groupRowId}"
            }

            override suspend fun ingest(
                context: AndroidInteroperabilityContext,
                page: AndroidStableRawContactObservationPage,
            ): AndroidBoundedPageResult {
                events += "contacts:${page.observations.size}"
                return AndroidBoundedPageResult.Applied
            }
        }
        val coordinator = GroupOnlyProductionAndroidBoundedObservationCoordinator.forTest(
            planner = { context, row -> AndroidGroupObservationPlanResult.Ready(plan(context, row, 3)) },
            committer = { command ->
                RoomAndroidGroupRowObservationResult.Applied(
                    RoomAndroidGroupRowObservationClassification.NO_CHANGE,
                    command.expectedAccountRevision + 1,
                    requireNotNull(command.expectedGroupLedgerRevision) + 1,
                )
            },
            providerFinalizer = { _, _, _ -> AndroidBoundedPageResult.Applied },
            contactCoordinator = contacts,
        )

        assertEquals(AndroidBoundedPageResult.Applied, coordinator.ingestGroups(context(), pages(row(4))))
        assertEquals(
            AndroidBoundedPageResult.Applied,
            coordinator.ingestContacts(context(), AndroidStableRawContactObservationPage(emptyList(), null)),
        )
        assertEquals(listOf("catalog:4", "contacts:0"), events)
    }

    private fun coordinator(
        planner: AndroidGroupObservationPlanner,
        committer: AndroidGroupObservationCommitAuthority,
        finalizer: AndroidGroupObservationProviderFinalizer,
        observer: AndroidIngestActionRequiredObserver = AndroidIngestActionRequiredObserver { },
    ) = GroupOnlyProductionAndroidBoundedObservationCoordinator.forTest(
        planner,
        committer,
        finalizer,
        actionRequiredObserver = observer,
    )

    private fun plan(
        context: AndroidInteroperabilityContext,
        row: AndroidOwnedGroupRow,
        revision: Long,
    ) = AndroidGroupObservationPlan(
        RoomAndroidGroupRowObservationCommand(
            context.account,
            context.androidAccountName,
            revision,
            context.providerEpoch,
            GROUP_ID,
            2,
            1,
            row.groupRowId,
            row.version,
            row.sourceIdentity,
            row.deleted,
            if (row.deleted) null else AndroidGroupSnapshot(context.account.value, GROUP_ID, row.title, row.visible),
        ),
        AndroidGroupObservationProviderAction.ACKNOWLEDGE,
    )

    private fun pages(vararg rows: AndroidOwnedGroupRow): List<AndroidOwnedGroupRowPage> =
        listOf(AndroidOwnedGroupRowPage(AndroidProviderAccountName(ANDROID_ACCOUNT), 0, rows.toList(), null))

    private fun row(id: Long) = AndroidOwnedGroupRow(
        id,
        GROUP_ID,
        "remote-group",
        "Synthetic group",
        dirty = true,
        deleted = false,
        visible = true,
        shouldSync = true,
        version = 7,
    )

    private fun context() = AndroidInteroperabilityContext(AccountScope("account"), ANDROID_ACCOUNT, 3, 0)

    private companion object {
        const val ANDROID_ACCOUNT = "android-account"
        const val GROUP_ID = "00000000-0000-0000-0000-000000000001"
    }
}
