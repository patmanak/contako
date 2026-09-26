package com.patmanak.contako.qa.contracts

import com.patmanak.contako.data.gateway.ContactInventoryMetadata
import com.patmanak.contako.data.gateway.ContactInventoryPage
import com.patmanak.contako.data.gateway.ContactInventoryCoverage
import com.patmanak.contako.data.gateway.ContactInventoryVersionProvenance
import com.patmanak.contako.data.gateway.InventoryCursor
import com.patmanak.contako.data.gateway.RemoteContactId
import com.patmanak.contako.data.gateway.RemoteEmailGroupMembership
import com.patmanak.contako.data.gateway.RemoteEmailId
import com.patmanak.contako.data.gateway.RemoteGroupId
import com.patmanak.contako.data.gateway.RemoteVersion
import com.patmanak.contako.data.gateway.ValidatedCompleteInventory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Executable differential-inventory oracle; production hydration adapters are intentionally absent. */
class ContactInventoryOracleTest {
    @Test
    fun zeroTwelveAndThreeHundredContactInventoriesHydrateOnlyTheInitialPass() {
        listOf(0, 12, 300).forEach { count ->
            val inventory = inventory(count)
            val initial = ReferenceIndexPlanner.plan(emptyMap(), inventory)
            val afterRestart = ReferenceIndexPlanner.plan(initial.nextBaseline, inventory)

            assertEquals(count, initial.hydrate.size)
            assertEquals(0, afterRestart.hydrate.size)
            assertEquals(0, afterRestart.delete.size)
            assertEquals(0, afterRestart.labelOnly.size)
        }
    }

    @Test
    fun oneChangedContactHydratesExactlyOnceAndLabelOnlyChangeDoesNotHydrate() {
        val first = inventory(12)
        val baseline = ReferenceIndexPlanner.plan(emptyMap(), first).nextBaseline
        val changed = inventory(
            12,
            versionOverride = mapOf(4 to "v2"),
            emailGroupOverride = mapOf(
                7 to listOf(
                    RemoteEmailGroupMembership(
                        emailId = RemoteEmailId("email-7"),
                        groupIds = listOf(RemoteGroupId("group-new")),
                    ),
                ),
            ),
        )

        val plan = ReferenceIndexPlanner.plan(baseline, changed)

        assertEquals(setOf(RemoteContactId("contact-4")), plan.hydrate)
        assertEquals(setOf(RemoteContactId("contact-7")), plan.labelOnly)
        assertEquals(emptySet<RemoteContactId>(), plan.delete)
    }

    @Test
    fun displayNameAndEmailMembershipChangesRemainDistinct() {
        val first = inventory(12)
        val baseline = ReferenceIndexPlanner.plan(emptyMap(), first).nextBaseline
        val changed = inventory(
            12,
            displayNameOverride = mapOf(3 to "Changed display name"),
            emailGroupOverride = mapOf(
                8 to listOf(
                    RemoteEmailGroupMembership(
                        emailId = RemoteEmailId("email-8"),
                        groupIds = listOf(RemoteGroupId("group-eight")),
                    ),
                ),
            ),
        )

        val plan = ReferenceIndexPlanner.plan(baseline, changed)

        assertEquals(setOf(RemoteContactId("contact-3")), plan.hydrate)
        assertEquals(setOf(RemoteContactId("contact-8")), plan.labelOnly)
    }

    @Test
    fun completeAbsenceDeletesButIncompleteOrMalformedPagesFailBeforePlanning() {
        val full = inventory(12)
        val baseline = ReferenceIndexPlanner.plan(emptyMap(), full).nextBaseline
        val completeWithoutLast = inventory(11)

        val plan = ReferenceIndexPlanner.plan(baseline, completeWithoutLast)

        assertEquals(setOf(RemoteContactId("contact-11")), plan.delete)
        assertTrue(
            runCatching {
                ValidatedCompleteInventory.fromPages(
                    listOf(
                        ContactInventoryPage(
                            contacts = full.contacts.take(6),
                            requestedCursor = null,
                            nextCursor = InventoryCursor("missing-terminal-page"),
                            totalCount = 12,
                        ),
                    ),
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                ValidatedCompleteInventory.fromPages(
                    listOf(
                        ContactInventoryPage(
                            contacts = listOf(metadata(0), metadata(0)),
                            requestedCursor = null,
                            nextCursor = null,
                            totalCount = 2,
                        ),
                    ),
                )
            }.isFailure,
        )
    }

    @Test
    fun planningIsInvariantToInventoryOrderAndNeverFallsBackToNPlusOne() {
        val full = inventory(300)
        val baseline = ReferenceIndexPlanner.plan(emptyMap(), full).nextBaseline
        val reordered = ValidatedCompleteInventory.fromPages(
            listOf(ContactInventoryPage(full.contacts.reversed(), null, null, 300)),
        )

        val plan = ReferenceIndexPlanner.plan(baseline, reordered)

        assertEquals(emptySet<RemoteContactId>(), plan.hydrate)
        assertEquals(emptySet<RemoteContactId>(), plan.delete)
        assertEquals(1, plan.inventoryCalls)
        assertEquals(0, plan.fullCardCalls)
    }

    private fun inventory(
        count: Int,
        versionOverride: Map<Int, String> = emptyMap(),
        displayNameOverride: Map<Int, String> = emptyMap(),
        emailGroupOverride: Map<Int, List<RemoteEmailGroupMembership>> = emptyMap(),
    ): ValidatedCompleteInventory = ValidatedCompleteInventory.fromPages(
        listOf(
            ContactInventoryPage(
                contacts = List(count) { index ->
                    metadata(
                        index,
                        version = versionOverride[index] ?: "v1",
                        displayName = displayNameOverride[index] ?: "Contact $index",
                        emailGroups = emailGroupOverride[index].orEmpty(),
                    )
                },
                requestedCursor = null,
                nextCursor = null,
                totalCount = count,
            ),
        ),
    )

    private fun metadata(
        index: Int,
        version: String = "v1",
        displayName: String = "Contact $index",
        emailGroups: List<RemoteEmailGroupMembership> = emptyList(),
    ): ContactInventoryMetadata = ContactInventoryMetadata(
        id = RemoteContactId("contact-$index"),
        displayName = displayName,
        version = RemoteVersion(version),
        sizeBytes = 1,
        modifiedAtEpochSeconds = 1,
        emailIds = listOf(RemoteEmailId("email-$index")),
        groupIds = emailGroups.flatMap(RemoteEmailGroupMembership::groupIds).distinct(),
        versionProvenance = ContactInventoryVersionProvenance.REMOTE_SERVER,
        coverage = ContactInventoryCoverage.AUTHORITATIVE_REMOTE_REVISION,
        emailGroupMemberships = emailGroups,
    )
}

private data class IndexBaseline(
    val version: RemoteVersion,
    val displayName: String?,
    val groups: List<RemoteGroupId>,
    val emailGroups: List<RemoteEmailGroupMembership>,
)

private data class IndexPlan(
    val hydrate: Set<RemoteContactId>,
    val labelOnly: Set<RemoteContactId>,
    val delete: Set<RemoteContactId>,
    val nextBaseline: Map<RemoteContactId, IndexBaseline>,
    val inventoryCalls: Int,
) {
    val fullCardCalls: Int get() = hydrate.size
}

private object ReferenceIndexPlanner {
    fun plan(
        baseline: Map<RemoteContactId, IndexBaseline>,
        inventory: ValidatedCompleteInventory,
    ): IndexPlan {
        val remote = inventory.contacts.associate { metadata ->
            metadata.id to IndexBaseline(
                version = metadata.version,
                displayName = metadata.displayName,
                groups = metadata.groupIds,
                emailGroups = metadata.emailGroupMemberships,
            )
        }
        val hydrate = remote.filter { (id, current) ->
            val previous = baseline[id]
            previous == null || previous.version != current.version || previous.displayName != current.displayName
        }.keys
        val labelOnly = remote.filter { (id, current) ->
            val previous = baseline[id]
            previous != null && id !in hydrate &&
                (previous.groups != current.groups || !previous.emailGroups.sameMembershipsAs(current.emailGroups))
        }.keys
        return IndexPlan(
            hydrate = hydrate,
            labelOnly = labelOnly,
            delete = baseline.keys - remote.keys,
            nextBaseline = remote,
            inventoryCalls = 1,
        )
    }
}

private fun List<RemoteEmailGroupMembership>.sameMembershipsAs(
    other: List<RemoteEmailGroupMembership>,
): Boolean = map { it.emailId to it.groupIds }.toSet() == other.map { it.emailId to it.groupIds }.toSet()
