package com.patmanak.contako.data.proton

import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.ContactInventoryCoverage
import com.patmanak.contako.data.gateway.ContactInventoryMetadata
import com.patmanak.contako.data.gateway.ContactInventorySnapshotAuthority
import com.patmanak.contako.data.gateway.ContactInventoryVersionProvenance
import com.patmanak.contako.data.gateway.RemoteContactId
import com.patmanak.contako.data.gateway.RemoteEmailGroupMembership
import com.patmanak.contako.data.gateway.RemoteEmailId
import com.patmanak.contako.data.gateway.RemoteGroupId
import com.patmanak.contako.data.gateway.RemoteVersion
import com.patmanak.contako.data.gateway.ValidatedCompleteInventory

/** Durable account-scoped seam implemented by the app-private Room database. */
internal interface ContactInventoryCheckpointStore {
    suspend fun load(account: AccountScope): VersionedContactInventoryCheckpoint?

    /** Atomic account-scoped compare-and-set; false means that this plan is stale and MUST be replayed. */
    suspend fun compareAndSet(
        account: AccountScope,
        expectedGeneration: Long?,
        checkpoint: ContactInventoryCheckpoint,
    ): Boolean
}

internal class VersionedContactInventoryCheckpoint(
    val generation: Long,
    val checkpoint: ContactInventoryCheckpoint,
) {
    init {
        require(generation >= 0)
    }
}

internal class ContactInventoryCheckpoint(entries: List<ContactInventoryBaseline>, val eventCursor: String? = null) {
    val entries: List<ContactInventoryBaseline> = entries.sortedBy { it.id.value }

    init {
        eventCursor?.let(::validateContactEventCursor)
        require(this.entries.size <= com.patmanak.contako.data.gateway.ContactInventoryPage.MAX_INVENTORY_TOTAL)
        require(this.entries.map(ContactInventoryBaseline::id).distinct().size == this.entries.size)
    }

    override fun toString(): String = "ContactInventoryCheckpoint(entryCount=${entries.size})"
}

internal class ContactInventoryBaseline(
    val id: RemoteContactId,
    val displayName: String?,
    val version: RemoteVersion,
    /** Absent for a public-directory inventory; see `D-096`. */
    val sizeBytes: Long?,
    /** Absent for a public-directory inventory; see `D-096`. */
    val modifiedAtEpochSeconds: Long?,
    groupIds: List<RemoteGroupId>,
    emailGroupMemberships: List<RemoteEmailGroupMembership>,
) {
    val groupIds: List<RemoteGroupId> = groupIds.sortedBy { it.value }
    val emailGroupMemberships: List<RemoteEmailGroupMembership> = emailGroupMemberships
        .sortedBy { it.emailId.value }

    init {
        require(sizeBytes == null || sizeBytes in 0..ContactInventoryMetadata.MAX_CONTACT_SIZE_BYTES)
        require(
            modifiedAtEpochSeconds == null ||
                modifiedAtEpochSeconds in 0..ContactInventoryMetadata.MAX_EPOCH_SECONDS,
        )
        require(this.groupIds.distinct().size == this.groupIds.size)
        require(this.emailGroupMemberships.map(RemoteEmailGroupMembership::emailId).distinct().size ==
            this.emailGroupMemberships.size)
    }

    fun contentChangedFrom(previous: ContactInventoryBaseline): Boolean =
        version != previous.version ||
            displayName != previous.displayName ||
            sizeBytes != previous.sizeBytes ||
            modifiedAtEpochSeconds != previous.modifiedAtEpochSeconds

    fun membershipsChangedFrom(previous: ContactInventoryBaseline): Boolean =
        groupIds != previous.groupIds || membershipFingerprint() != previous.membershipFingerprint()

    private fun membershipFingerprint(): List<Pair<RemoteEmailId, List<RemoteGroupId>>> =
        emailGroupMemberships.map { membership ->
            membership.emailId to membership.groupIds.sortedBy { it.value }
        }

    override fun toString(): String = "ContactInventoryBaseline(REDACTED)"
}

internal class ContactInventoryPlan private constructor(
    internal val account: AccountScope,
    internal val expectedCheckpointGeneration: Long?,
    hydrate: Set<RemoteContactId>,
    labelOnly: Set<RemoteContactId>,
    deleted: Set<RemoteContactId>,
    val nextCheckpoint: ContactInventoryCheckpoint,
    private val completedSource: ContactInventoryPlan?,
) {
    val hydrate: Set<RemoteContactId> = hydrate.toSet()
    val labelOnly: Set<RemoteContactId> = labelOnly.toSet()
    val deleted: Set<RemoteContactId> = deleted.toSet()

    init {
        require(this.hydrate.intersect(this.labelOnly).isEmpty())
        require(this.hydrate.intersect(this.deleted).isEmpty())
        require(this.labelOnly.intersect(this.deleted).isEmpty())
    }

    override fun toString(): String =
        "ContactInventoryPlan(hydrate=${hydrate.size}, labelOnly=${labelOnly.size}, deleted=${deleted.size})"

    fun completedAfterDurableReconciliation(
        hydrated: Set<RemoteContactId>,
        labelReconciled: Set<RemoteContactId>,
        deletionsReconciled: Set<RemoteContactId>,
        canonicalPersistenceCommitted: Boolean,
    ): ContactInventoryPlan {
        require(completedSource == null)
        require(hydrated == hydrate)
        require(labelReconciled == labelOnly)
        require(deletionsReconciled == deleted)
        require(canonicalPersistenceCommitted)
        return ContactInventoryPlan(
            account = account,
            expectedCheckpointGeneration = expectedCheckpointGeneration,
            hydrate = hydrate,
            labelOnly = labelOnly,
            deleted = deleted,
            nextCheckpoint = nextCheckpoint,
            completedSource = this,
        )
    }

    /**
     * Returns the exact pending plan that issued this completion proof. The proof state and the
     * constructor are private, so another file in this module cannot manufacture a completed plan.
     * This proves the declared reconciliation sets and commit flag, not the external durability of
     * a caller that lies about its own storage transaction.
     */
    internal fun verifiedCompletedSource(): ContactInventoryPlan =
        completedSource ?: throw IllegalArgumentException()

    companion object {
        internal fun pending(
            account: AccountScope,
            expectedCheckpointGeneration: Long?,
            hydrate: Set<RemoteContactId>,
            labelOnly: Set<RemoteContactId>,
            deleted: Set<RemoteContactId>,
            nextCheckpoint: ContactInventoryCheckpoint,
        ): ContactInventoryPlan = ContactInventoryPlan(
            account = account,
            expectedCheckpointGeneration = expectedCheckpointGeneration,
            hydrate = hydrate,
            labelOnly = labelOnly,
            deleted = deleted,
            nextCheckpoint = nextCheckpoint,
            completedSource = null,
        )
    }
}

internal class StaleContactInventoryPlan : IllegalStateException()

/**
 * Plans bounded incremental reconciliation. Public-directory fingerprints detect public changes,
 * not all private edits. The execution stage MUST confirm absent IDs before applying deletion;
 * completion of SDK pagination alone is not an authoritative server snapshot (D-032/D-096).
 */
internal class PersistentContactInventoryPlanner(
    private val checkpointStore: ContactInventoryCheckpointStore,
) {
    suspend fun eventCursor(account: AccountScope): String? = checkpointStore.load(account)?.checkpoint?.eventCursor

    suspend fun plan(
        account: AccountScope,
        inventory: ValidatedCompleteInventory,
        forceHydration: Boolean = false,
        events: ContactEventsDelta? = null,
        identityRepairContacts: Set<RemoteContactId> = emptySet(),
    ): ContactInventoryPlan {
        requireAuthoritative(inventory)
        val loadedCheckpoint = checkpointStore.load(account)
        val previous = loadedCheckpoint?.checkpoint?.entries.orEmpty().associateBy(ContactInventoryBaseline::id)
        val current = inventory.contacts.associate { metadata -> metadata.id to metadata.toBaseline() }

        // An event newer than the directory MUST NOT be consumed before its card can be read.
        if (events != null && !events.refreshAll && !current.keys.containsAll(events.changedContacts)) {
            throw StaleContactInventoryPlan()
        }

        val eventEmails = events?.changedEmails.orEmpty()
        val emailContacts = mutableSetOf<RemoteContactId>()
        val unresolvedEmails = eventEmails.toMutableSet()
        if (eventEmails.isNotEmpty()) {
            (previous.values.asSequence() + current.values.asSequence()).forEach { baseline ->
                baseline.emailGroupMemberships.forEach { membership ->
                    if (membership.emailId in eventEmails) {
                        unresolvedEmails -= membership.emailId
                        emailContacts += baseline.id
                    }
                }
            }
        }
        // A removed/replaced email can be located in the previous checkpoint. Unknown IDs
        // require a full refresh, rather than consuming a potentially private change silently.
        val refreshAll = events?.refreshAll == true || unresolvedEmails.isNotEmpty()

        val hydrate = current.filter { (id, now) ->
            val before = previous[id]
            forceHydration || refreshAll || id in identityRepairContacts || id in events?.changedContacts.orEmpty() || id in emailContacts ||
                before == null || now.contentChangedFrom(before)
        }.keys
        val labelOnly = current.filter { (id, now) ->
            val before = previous[id]
            before != null && id !in hydrate && now.membershipsChangedFrom(before)
        }.keys
        val deleted = previous.keys - current.keys
        val checkpoint = ContactInventoryCheckpoint(current.values.toList(), events?.nextCursor ?: loadedCheckpoint?.checkpoint?.eventCursor)
        return ContactInventoryPlan.pending(
            account,
            loadedCheckpoint?.generation,
            hydrate,
            labelOnly,
            deleted,
            checkpoint,
        )
    }

    /** Called only after every planned hydration/deletion/label reconciliation commits durably. */
    suspend fun commit(account: AccountScope, completedPlan: ContactInventoryPlan) {
        val plan = completedPlan.verifiedCompletedSource()
        require(plan.account == account)
        if (!checkpointStore.compareAndSet(account, plan.expectedCheckpointGeneration, plan.nextCheckpoint)) {
            throw StaleContactInventoryPlan()
        }
    }

    /**
     * Collection-level authority is still mandatory: an unattested snapshot MUST NOT authorize
     * deletion. Per-contact revision metadata is not, because `D-096` accepts the maintained public
     * directory, whose entries carry a local fingerprint instead of server size/modification time.
     */
    private fun requireAuthoritative(inventory: ValidatedCompleteInventory) {
        require(
            inventory.snapshotAuthority in setOf(
                ContactInventorySnapshotAuthority.AUTHORITATIVE_REMOTE_REVISION,
                ContactInventorySnapshotAuthority.COMPLETE_PUBLIC_DIRECTORY,
            ),
        )
        // Coverage and provenance MUST be uniform across the snapshot. Mixing an authoritative
        // entry with a fingerprint-only one would make change detection silently inconsistent.
        require(inventory.contacts.map { it.coverage }.distinct().size <= 1)
        require(inventory.contacts.map { it.versionProvenance }.distinct().size <= 1)
        require(inventory.contacts.all { metadata ->
            when (metadata.coverage) {
                ContactInventoryCoverage.AUTHORITATIVE_REMOTE_REVISION ->
                    metadata.versionProvenance == ContactInventoryVersionProvenance.REMOTE_SERVER &&
                        metadata.sizeBytes != null &&
                        metadata.modifiedAtEpochSeconds != null
                ContactInventoryCoverage.PUBLIC_DIRECTORY_FIELDS_ONLY ->
                    metadata.versionProvenance ==
                        ContactInventoryVersionProvenance.LOCAL_INDEX_FINGERPRINT
                // Unattested per-contact revisions stay rejected: they claim server authority
                // without proving it, which is weaker than an honest fingerprint.
                ContactInventoryCoverage.REMOTE_REVISION_UNATTESTED -> false
            }
        })
    }

    private fun ContactInventoryMetadata.toBaseline(): ContactInventoryBaseline = ContactInventoryBaseline(
        id = id,
        displayName = displayName,
        version = version,
        sizeBytes = sizeBytes,
        modifiedAtEpochSeconds = modifiedAtEpochSeconds,
        groupIds = groupIds,
        emailGroupMemberships = emailGroupMemberships,
    )
}
