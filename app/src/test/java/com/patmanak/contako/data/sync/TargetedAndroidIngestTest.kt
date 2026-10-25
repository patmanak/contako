package com.patmanak.contako.data.sync

import com.patmanak.contako.data.android.provider.AndroidOwnedGroupRow
import com.patmanak.contako.data.android.provider.AndroidOwnedGroupRowPage
import com.patmanak.contako.data.android.provider.AndroidOwnedRawContact
import com.patmanak.contako.data.android.provider.AndroidStableRawContactObservation
import com.patmanak.contako.data.android.provider.AndroidStableRawContactObservationPage
import com.patmanak.contako.data.android.provider.AndroidStableRawContactPageResult
import com.patmanak.contako.data.gateway.AccountScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TargetedAndroidIngestTest {
    @Test fun nativeTargetIsReadOnceAfterTheBoundedGroupPrerequisiteAndBeforeProjection() = runTest {
        val order = mutableListOf<String>()
        var savedNativeIntent = false
        val stage = stage(
            groups = { account, after ->
                assertEquals(CONTEXT.androidAccountName, account.value)
                order += "group-read"
                AndroidOwnedGroupRowPage(account, after, emptyList(), null)
            },
            exact = { account, id ->
                assertEquals(CONTEXT.androidAccountName, account.value)
                assertEquals(RAW, id)
                order += "target-read"
                stable()
            },
            observations = object : AndroidBoundedObservationCoordinator {
                override suspend fun ingestGroups(context: AndroidInteroperabilityContext,
                    pages: List<AndroidOwnedGroupRowPage>): AndroidBoundedPageResult {
                    order += "group-commit"
                    assertEquals(1, pages.size)
                    return AndroidBoundedPageResult.Applied
                }
                override suspend fun ingestContacts(context: AndroidInteroperabilityContext,
                    page: AndroidStableRawContactObservationPage): AndroidBoundedPageResult {
                    order += "target-commit"
                    assertEquals(listOf(RAW), page.observations.map { it.rawContact.rawContactId })
                    savedNativeIntent = true
                    return AndroidBoundedPageResult.Applied
                }
            },
        )
        assertEquals(AndroidInteroperabilityStageResult.Success,
            stage.ingestTarget(CONTEXT, TARGET, RAW, SOURCE) { false })
        assertTrue(savedNativeIntent)
        assertEquals(listOf("group-read", "group-commit", "target-read", "target-commit"), order)
    }

    @Test fun anotherIdentityOrPageCannotBeAdoptedThroughTheRequestedLocator() = runTest {
        val wrong = listOf(
            page(raw().copy(canonicalContactIdClaim = "another-contact")),
            page(raw().copy(sourceIdentity = "another-source")),
            page(raw().copy(rawContactId = RAW + 1)),
            AndroidStableRawContactObservationPage(emptyList(), null),
            AndroidStableRawContactObservationPage(listOf(observation(raw()), observation(raw().copy(rawContactId = RAW + 1))), null),
            AndroidStableRawContactObservationPage(listOf(observation(raw())), RAW),
        )
        wrong.forEach { input ->
            var commits = 0
            val stage = stage(exact = { _, _ -> AndroidStableRawContactPageResult.Stable(input) },
                observations = coordinator { commits++ })
            assertEquals(AndroidInteroperabilityStageResult.ActionRequired,
                stage.ingestTarget(CONTEXT, TARGET, RAW, SOURCE) { false })
            assertEquals(0, commits)
        }
    }

    @Test fun missingRawLocatorDoesNotMeanDeletionAndNeverStartsDirectoryTraversal() = runTest {
        var groupCommits = 0
        val stage = stage(exact = { _, _ -> error("No exact source exists yet") },
            observations = object : AndroidBoundedObservationCoordinator {
                override suspend fun ingestGroups(context: AndroidInteroperabilityContext,
                    pages: List<AndroidOwnedGroupRowPage>) = AndroidBoundedPageResult.Applied.also { groupCommits++ }
                override suspend fun ingestContacts(context: AndroidInteroperabilityContext,
                    page: AndroidStableRawContactObservationPage): AndroidBoundedPageResult = error("No deletion may be inferred")
            })
        assertEquals(AndroidInteroperabilityStageResult.Success,
            stage.ingestTarget(CONTEXT, TARGET, null, SOURCE) { false })
        assertEquals(1, groupCommits)
    }

    @Test fun unstableExactReadKeepsTheExistingReplanBudget() = runTest {
        var reads = 0
        var commits = 0
        val stage = stage(exact = { _, _ -> reads++; AndroidStableRawContactPageResult.ReplanRequired },
            observations = coordinator { commits++ })
        assertEquals(AndroidInteroperabilityStageResult.RetryWaiting,
            stage.ingestTarget(CONTEXT, TARGET, RAW, SOURCE) { false })
        assertEquals(3, reads)
        assertEquals(0, commits)
    }

    @Test fun cancellationBeforeReadAndAfterReadCannotCommitOrProjectTheTarget() = runTest {
        var reads = 0
        var commits = 0
        var cancelled = false
        val stage = stage(exact = { _, _ -> reads++; cancelled = true; stable() },
            observations = coordinator { commits++ })
        assertEquals(AndroidInteroperabilityStageResult.Cancelled,
            stage.ingestTarget(CONTEXT, TARGET, RAW, SOURCE) { true })
        assertEquals(0, reads)
        assertEquals(AndroidInteroperabilityStageResult.Cancelled,
            stage.ingestTarget(CONTEXT, TARGET, RAW, SOURCE) { cancelled })
        assertEquals(1, reads)
        assertEquals(0, commits)
    }

    @Test fun groupPrerequisiteFailureDoesNotBecomeSuccessOrStartAnExactContactRead() = runTest {
        listOf(
            AndroidBoundedPageResult.RepairRequired to AndroidInteroperabilityStageResult.ActionRequired,
            AndroidBoundedPageResult.PartiallyApplied to AndroidInteroperabilityStageResult.ActionRequired,
            AndroidBoundedPageResult.ReplanRequired to AndroidInteroperabilityStageResult.RetryWaiting,
            AndroidBoundedPageResult.LocalPersistenceFailure to AndroidInteroperabilityStageResult.LocalPersistenceFailure,
        ).forEach { (failure, expected) ->
            val stage = stage(exact = { _, _ -> error("Group prerequisite failed") },
                observations = object : AndroidBoundedObservationCoordinator {
                    override suspend fun ingestGroups(context: AndroidInteroperabilityContext,
                        pages: List<AndroidOwnedGroupRowPage>) = failure
                    override suspend fun ingestContacts(context: AndroidInteroperabilityContext,
                        page: AndroidStableRawContactObservationPage): AndroidBoundedPageResult = error("Group prerequisite failed")
                })
            assertEquals(expected, stage.ingestTarget(CONTEXT, TARGET, RAW, SOURCE) { false })
        }
    }

    @Test fun aMissingSourceClaimIsNotAnInvitationToIngestAnotherOwnedContact() = runTest {
        var reads = 0
        val stage = stage(exact = { _, _ -> reads++; stable() })
        assertEquals(AndroidInteroperabilityStageResult.ActionRequired,
            stage.ingestTarget(CONTEXT, TARGET, RAW, null) { false })
        assertEquals(0, reads)
    }

    @Test fun sharedGroupReadsKeepTheExistingSixPageCeiling() = runTest {
        var reads = 0
        var commits = 0
        val stage = stage(groups = { account, after ->
            reads++
            val next = after + 1
            val group = AndroidOwnedGroupRow(next, "synthetic-group", "synthetic-source", "Synthetic group",
                dirty = false, deleted = false, visible = true, shouldSync = true, version = 1)
            AndroidOwnedGroupRowPage(account, after, listOf(group), next)
        }, exact = { _, _ -> error("Bounded group prerequisite was not established") },
            observations = coordinator { commits++ })
        assertEquals(AndroidInteroperabilityStageResult.ActionRequired,
            stage.ingestTarget(CONTEXT, TARGET, RAW, SOURCE) { false })
        assertEquals(7, reads) // The seventh bounded page is refused, never ingested.
        assertEquals(0, commits)
    }

    private fun stage(
        exact: AndroidExactContactReader = AndroidExactContactReader { _, _ -> stable() },
        groups: AndroidGroupPageReader = AndroidGroupPageReader { account, after ->
            AndroidOwnedGroupRowPage(account, after, emptyList(), null)
        },
        observations: AndroidBoundedObservationCoordinator = coordinator { },
    ) = BoundedAndroidInteroperabilityStage(
        contactsReader = { _, _ -> error("A targeted request cannot traverse the contact directory") },
        groupsReader = groups,
        observationCoordinator = observations,
        projectionCoordinator = { _, _ -> error("Ingestion cannot project contacts") },
        exactContactsReader = exact,
    )

    private fun coordinator(onContact: () -> Unit) = object : AndroidBoundedObservationCoordinator {
        override suspend fun ingestGroups(context: AndroidInteroperabilityContext,
            pages: List<AndroidOwnedGroupRowPage>) = AndroidBoundedPageResult.Applied
        override suspend fun ingestContacts(context: AndroidInteroperabilityContext,
            page: AndroidStableRawContactObservationPage) = AndroidBoundedPageResult.Applied.also { onContact() }
    }
    private companion object {
        val CONTEXT = AndroidInteroperabilityContext(AccountScope("synthetic-owner"), "synthetic-android-account", 1, 0)
        const val TARGET = "synthetic-target"
        const val SOURCE = "synthetic-source"
        const val RAW = 42L
        fun raw() = AndroidOwnedRawContact(RAW, TARGET, SOURCE, dirty = true, deleted = false, version = 7)
        fun observation(raw: AndroidOwnedRawContact) = AndroidStableRawContactObservation(raw, emptyList())
        fun page(raw: AndroidOwnedRawContact) = AndroidStableRawContactObservationPage(listOf(observation(raw)), null)
        fun stable() = AndroidStableRawContactPageResult.Stable(page(raw()))
    }
}
