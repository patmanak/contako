package com.patmanak.contako.data.proton

import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.ContactInventoryCoverage
import com.patmanak.contako.data.gateway.ContactInventoryMetadata
import com.patmanak.contako.data.gateway.ContactInventoryPage
import com.patmanak.contako.data.gateway.ContactInventorySnapshotAuthority
import com.patmanak.contako.data.gateway.ContactInventoryVersionProvenance
import com.patmanak.contako.data.gateway.RemoteContactId
import com.patmanak.contako.data.gateway.RemoteVersion
import com.patmanak.contako.data.gateway.ValidatedCompleteInventory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactInventoryPlannerCasTest {
    @Test
    fun `checkpoint completion requires exact durable reconciliation proof before store access`() = runTest {
        val store = RecordingCheckpointStore()
        val planner = PersistentContactInventoryPlanner(store)
        val plan = planner.plan(ACCOUNT, inventory("contact", "version-1"))

        assertTrue(runCatching { planner.commit(ACCOUNT, plan) }.isFailure)
        assertEquals(0, store.compareAndSetCalls)
        assertTrue(runCatching {
            plan.completedAfterDurableReconciliation(
                hydrated = emptySet(),
                labelReconciled = plan.labelOnly,
                deletionsReconciled = plan.deleted,
                canonicalPersistenceCommitted = true,
            )
        }.isFailure)
        assertTrue(runCatching {
            plan.completedAfterDurableReconciliation(
                hydrated = plan.hydrate,
                labelReconciled = plan.labelOnly,
                deletionsReconciled = plan.deleted,
                canonicalPersistenceCommitted = false,
            )
        }.isFailure)

        planner.commit(ACCOUNT, plan.fullyCompleted())
        assertEquals(1, store.compareAndSetCalls)
        assertEquals(0L, store.load(ACCOUNT)?.generation)
    }

    @Test
    fun `generation cas rejects stale replay without crossing account scope`() = runTest {
        val store = RecordingCheckpointStore()
        val planner = PersistentContactInventoryPlanner(store)
        val inventory = inventory("contact", "version-1")
        val winner = planner.plan(ACCOUNT, inventory)
        val stale = planner.plan(ACCOUNT, inventory)

        planner.commit(ACCOUNT, winner.fullyCompleted())
        assertTrue(runCatching { planner.commit(ACCOUNT, stale.fullyCompleted()) }
            .exceptionOrNull() is StaleContactInventoryPlan)
        assertTrue(runCatching { planner.commit(ACCOUNT, winner.fullyCompleted()) }
            .exceptionOrNull() is StaleContactInventoryPlan)

        val foreign = planner.plan(FOREIGN_ACCOUNT, inventory("foreign-contact", "foreign-version"))
        planner.commit(FOREIGN_ACCOUNT, foreign.fullyCompleted())
        assertEquals(0L, store.load(ACCOUNT)?.generation)
        assertEquals(0L, store.load(FOREIGN_ACCOUNT)?.generation)
        assertEquals(RemoteContactId("contact"), store.load(ACCOUNT)?.checkpoint?.entries?.single()?.id)
        assertEquals(
            RemoteContactId("foreign-contact"),
            store.load(FOREIGN_ACCOUNT)?.checkpoint?.entries?.single()?.id,
        )
    }

    @Test
    fun `checkpoint diagnostics redact stable identities and values`() {
        val baseline = baseline("private-contact", "private-version")
        val checkpoint = ContactInventoryCheckpoint(listOf(baseline))

        assertEquals("ContactInventoryBaseline(REDACTED)", baseline.toString())
        assertEquals("ContactInventoryCheckpoint(entryCount=1)", checkpoint.toString())
        assertFalse(checkpoint.toString().contains("private-contact"))
        assertFalse(checkpoint.toString().contains("private-version"))
    }

    @Test
    fun `empty unattested snapshot cannot infer deletion or replace a non-empty checkpoint`() = runTest {
        val store = RecordingCheckpointStore()
        val planner = PersistentContactInventoryPlanner(store)
        planner.commit(ACCOUNT, planner.plan(ACCOUNT, inventory("contact", "version-1")).fullyCompleted())

        val emptyUnattested = ValidatedCompleteInventory.fromPages(
            listOf(ContactInventoryPage(emptyList(), null, null, 0)),
        )

        assertTrue(runCatching { planner.plan(ACCOUNT, emptyUnattested) }.isFailure)
        assertEquals(1, store.compareAndSetCalls)
        assertEquals(RemoteContactId("contact"), store.load(ACCOUNT)?.checkpoint?.entries?.single()?.id)
    }

    /** `D-096`: the maintained public directory is the production inventory and MUST be planned. */
    @Test
    fun `public directory inventory is accepted and plans hydration`() = runTest {
        val planner = PersistentContactInventoryPlanner(RecordingCheckpointStore())

        val plan = planner.plan(ACCOUNT, publicDirectoryInventory("contact-1"))

        assertEquals(setOf(RemoteContactId("contact-1")), plan.hydrate)
        assertTrue(plan.deleted.isEmpty())
    }

    /** Without server size or modification time, the version fingerprint carries change detection. */
    @Test
    fun `public directory change detection relies on the version fingerprint`() = runTest {
        val store = RecordingCheckpointStore()
        val planner = PersistentContactInventoryPlanner(store)
        planner.commit(
            ACCOUNT,
            planner.plan(ACCOUNT, publicDirectoryInventory("contact-1", "v1")).fullyCompleted(),
        )

        val unchanged = planner.plan(ACCOUNT, publicDirectoryInventory("contact-1", "v1"))
        assertTrue(unchanged.hydrate.isEmpty())

        val changed = planner.plan(ACCOUNT, publicDirectoryInventory("contact-1", "v2"))
        assertEquals(setOf(RemoteContactId("contact-1")), changed.hydrate)
    }

    /**
     * A snapshot claiming server authority without proving it stays rejected: that is weaker than
     * an honest fingerprint, because it invites trusting a revision that was never attested.
     */
    @Test
    fun `unattested per contact revisions remain rejected`() = runTest {
        val planner = PersistentContactInventoryPlanner(RecordingCheckpointStore())

        assertTrue(
            runCatching { planner.plan(ACCOUNT, unattestedInventory("contact-1")) }
                .exceptionOrNull() is IllegalArgumentException,
        )
    }

    /** Mixed coverage would make change detection silently inconsistent across the snapshot. */
    @Test
    fun `mixed coverage within one snapshot is rejected`() = runTest {
        val planner = PersistentContactInventoryPlanner(RecordingCheckpointStore())
        val mixed = ValidatedCompleteInventory.fromPages(
            listOf(
                ContactInventoryPage(
                    contacts = listOf(
                        publicDirectoryMetadata("contact-1", "v1"),
                        ContactInventoryMetadata(
                            id = RemoteContactId("contact-2"),
                            displayName = "Fixture",
                            version = RemoteVersion("v1"),
                            sizeBytes = 1,
                            modifiedAtEpochSeconds = 1,
                            emailIds = emptyList(),
                            groupIds = emptyList(),
                            versionProvenance = ContactInventoryVersionProvenance.REMOTE_SERVER,
                            coverage = ContactInventoryCoverage.AUTHORITATIVE_REMOTE_REVISION,
                        ),
                    ),
                    requestedCursor = null,
                    nextCursor = null,
                    totalCount = 2,
                    snapshotAuthority = ContactInventorySnapshotAuthority.AUTHORITATIVE_REMOTE_REVISION,
                ),
            ),
        )

        assertTrue(
            runCatching { planner.plan(ACCOUNT, mixed) }.exceptionOrNull() is IllegalArgumentException,
        )
    }

    /** Exactly what `ProtonPublicContactGateway.toInventoryMetadata` emits at runtime. */
    private fun publicDirectoryMetadata(id: String, version: String) = ContactInventoryMetadata(
        id = RemoteContactId(id),
        displayName = "Fixture",
        version = RemoteVersion(version),
        sizeBytes = null,
        modifiedAtEpochSeconds = null,
        emailIds = emptyList(),
        groupIds = emptyList(),
        versionProvenance = ContactInventoryVersionProvenance.LOCAL_INDEX_FINGERPRINT,
        coverage = ContactInventoryCoverage.PUBLIC_DIRECTORY_FIELDS_ONLY,
    )

    private fun publicDirectoryInventory(
        id: String,
        version: String = "fingerprint",
    ): ValidatedCompleteInventory = ValidatedCompleteInventory.fromPages(
        listOf(
            ContactInventoryPage(
                contacts = listOf(publicDirectoryMetadata(id, version)),
                requestedCursor = null,
                nextCursor = null,
                totalCount = 1,
                snapshotAuthority = ContactInventorySnapshotAuthority.AUTHORITATIVE_REMOTE_REVISION,
            ),
        ),
    )

    private fun unattestedInventory(id: String): ValidatedCompleteInventory =
        ValidatedCompleteInventory.fromPages(
            listOf(
                ContactInventoryPage(
                    contacts = listOf(
                        ContactInventoryMetadata(
                            id = RemoteContactId(id),
                            displayName = "Fixture",
                            version = RemoteVersion("v1"),
                            sizeBytes = 1,
                            modifiedAtEpochSeconds = 1,
                            emailIds = emptyList(),
                            groupIds = emptyList(),
                            versionProvenance =
                                ContactInventoryVersionProvenance.REMOTE_SERVER_UNATTESTED,
                            coverage = ContactInventoryCoverage.REMOTE_REVISION_UNATTESTED,
                        ),
                    ),
                    requestedCursor = null,
                    nextCursor = null,
                    totalCount = 1,
                    snapshotAuthority = ContactInventorySnapshotAuthority.AUTHORITATIVE_REMOTE_REVISION,
                ),
            ),
        )

    private fun ContactInventoryPlan.fullyCompleted(): ContactInventoryPlan =
        completedAfterDurableReconciliation(hydrate, labelOnly, deleted, canonicalPersistenceCommitted = true)

    private fun inventory(id: String, version: String): ValidatedCompleteInventory =
        ValidatedCompleteInventory.fromPages(
            listOf(
                ContactInventoryPage(
                    contacts = listOf(
                        ContactInventoryMetadata(
                            id = RemoteContactId(id),
                            displayName = "Fixture",
                            version = RemoteVersion(version),
                            sizeBytes = 1,
                            modifiedAtEpochSeconds = 1,
                            emailIds = emptyList(),
                            groupIds = emptyList(),
                            versionProvenance = ContactInventoryVersionProvenance.REMOTE_SERVER,
                            coverage = ContactInventoryCoverage.AUTHORITATIVE_REMOTE_REVISION,
                        ),
                    ),
                    requestedCursor = null,
                    nextCursor = null,
                    totalCount = 1,
                    snapshotAuthority =
                        ContactInventorySnapshotAuthority.AUTHORITATIVE_REMOTE_REVISION,
                ),
            ),
        )

    private fun baseline(id: String, version: String) = ContactInventoryBaseline(
        id = RemoteContactId(id),
        displayName = "Fixture",
        version = RemoteVersion(version),
        sizeBytes = 1,
        modifiedAtEpochSeconds = 1,
        groupIds = emptyList(),
        emailGroupMemberships = emptyList(),
    )

    private class RecordingCheckpointStore : ContactInventoryCheckpointStore {
        private val values = mutableMapOf<AccountScope, VersionedContactInventoryCheckpoint>()
        var compareAndSetCalls = 0

        override suspend fun load(account: AccountScope): VersionedContactInventoryCheckpoint? = values[account]

        override suspend fun compareAndSet(
            account: AccountScope,
            expectedGeneration: Long?,
            checkpoint: ContactInventoryCheckpoint,
        ): Boolean {
            compareAndSetCalls++
            val current = values[account]
            if (current?.generation != expectedGeneration) return false
            values[account] = VersionedContactInventoryCheckpoint(Math.addExact(expectedGeneration ?: -1L, 1L), checkpoint)
            return true
        }
    }

    private companion object {
        val ACCOUNT = AccountScope("primary")
        val FOREIGN_ACCOUNT = AccountScope("foreign")
    }
}
