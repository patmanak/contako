package com.patmanak.contako.data.sync

import com.patmanak.contako.data.gateway.*
import com.patmanak.contako.data.proton.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** One serialized pass shares the same event interval across group and contact reconciliation. */
internal class EventDrivenRemoteReadSession(
    private val planner: PersistentContactInventoryPlanner,
    private val eventsGateway: ProtonContactEventsGateway,
    val resources: ProtonContactEventResourceGateway,
    private val clock: () -> Long = System::currentTimeMillis,
    private val fullScanIntervalMillis: Long = FULL_SCAN_INTERVAL_MILLIS,
) {
    init { require(fullScanIntervalMillis > 0) }
    private var cached: Pair<AccountScope, GatewayOutcome<RemoteEventReadContext>>? = null
    private var forceFull = false

    fun reset(forceFull: Boolean = false) { cached = null; this.forceFull = forceFull }
    fun requireFullBaseline() {
        check(cached == null)
        forceFull = true
    }

    suspend fun prepare(account: AccountScope): GatewayOutcome<RemoteEventReadContext> {
        cached?.let { require(it.first == account); return it.second }
        val snapshot = planner.checkpoint(account)
        val now = clock().coerceAtLeast(0)
        val header = snapshot?.checkpoint
        val lastFull = header?.lastFullScanAtEpochMillis
        val resetInterval = forceFull || header?.eventStrategyVersion !in setOf(STRATEGY_VERSION, GROUP_RESCAN_REQUIRED) ||
            header?.eventCursor == null
        val safetyScanDue = lastFull == null || now < lastFull || now - lastFull >= fullScanIntervalMillis
        val full = resetInterval || header?.eventStrategyVersion == GROUP_RESCAN_REQUIRED || safetyScanDue
        // A full hydration captures latest BEFORE either peer inventory. Old/expired streams
        // cannot prevent bootstrap, and later mutations remain after this captured position.
        var events = eventsGateway.read(account, if (resetInterval) null else header?.eventCursor)
        var resetCursor = resetInterval
        // Unexpected cursor responses have no proved expiry semantics. At the safety deadline,
        // attempt latest once and establish a fully verified baseline before acknowledging it.
        // Network, authentication and throttling failures MUST NOT be treated as cursor expiry.
        if (!resetInterval && events is GatewayOutcome.Failure &&
            (events.category in CURSOR_RESET_FAILURES ||
                safetyScanDue && events.category in SAFETY_CURSOR_RESET_FAILURES)) {
            events = eventsGateway.read(account, null)
            resetCursor = true
        }
        val result = when (events) {
            is GatewayOutcome.Failure -> events
            is GatewayOutcome.Success -> GatewayOutcome.Success(RemoteEventReadContext(snapshot,
                events.value, full || events.value.refreshAll || resetCursor, now,
                fullHydration = resetCursor || events.value.refreshAll))
        }
        cached = account to result
        return result
    }

    /** Missing/recreated opaque identities require authoritative acquisition, never guessed absence. */
    suspend fun contactDelta(
        account: AccountScope,
        context: RemoteEventReadContext,
        repairIds: Set<RemoteContactId>,
        cancelled: () -> Boolean,
    ): ContactEventDeltaResult {
        val previous = requireNotNull(context.snapshot).checkpoint.entries.associateBy { it.id }
        val events = context.events
        val touched = (events.changedContacts + previous.values.filter { baseline ->
            baseline.groupIds.any { it in events.deletedGroups }
        }.map { it.id } + repairIds.filter { it !in previous }).toMutableSet()
        val updated = linkedMapOf<RemoteContactId, ContactInventoryMetadata>()
        suspend fun acquire(ids: Set<RemoteContactId>): GatewayOutcome.Failure? {
            for (window in (ids - events.deletedContacts - updated.keys).sortedBy { it.value }.chunked(10)) {
                if (cancelled()) return GatewayOutcome.Failure(GatewayFailureCategory.CANCELLED)
                val results = coroutineScope { window.map { id -> async { id to resources.contact(account, id) } }.awaitAll() }
                for ((id, outcome) in results) when (outcome) {
                    is GatewayOutcome.Failure -> return outcome
                    is GatewayOutcome.Success -> {
                        if (outcome.value.id != id) return GatewayOutcome.Failure(GatewayFailureCategory.MALFORMED_RESPONSE)
                        updated[id] = outcome.value
                    }
                }
            }
            return null
        }
        acquire(touched)?.let { return ContactEventDeltaResult.Failure(it) }
        val owners = mutableMapOf<RemoteEmailId, RemoteContactId>()
        for (baseline in previous.values) for (email in baseline.emailGroupMemberships) {
            val old = owners.put(email.emailId, baseline.id)
            if (old != null && old != baseline.id) return ContactEventDeltaResult.Failure(
                GatewayOutcome.Failure(GatewayFailureCategory.CONFLICT))
        }
        for (metadata in updated.values) for (email in metadata.emailIds) {
            val old = owners.put(email, metadata.id)
            if (old != null && old != metadata.id) return ContactEventDeltaResult.Failure(
                GatewayOutcome.Failure(GatewayFailureCategory.CONFLICT))
        }
        for (email in (events.changedEmails + events.deletedEmails).sortedBy { it.value }) {
            if (cancelled()) return ContactEventDeltaResult.Failure(GatewayOutcome.Failure(GatewayFailureCategory.CANCELLED))
            val owner = owners[email] ?: if (email in events.deletedEmails) {
                // The occurrence can have existed only between two passes. A complete LIGHT
                // inventory resolves the remaining contacts; this is not a lost event interval.
                return ContactEventDeltaResult.DeletedEmailOwnerUnknown
            } else when (val result = resources.emailOwner(account, email)) {
                is GatewayOutcome.Failure -> return ContactEventDeltaResult.Failure(result)
                is GatewayOutcome.Success -> result.value
            }
            touched += owner
        }
        acquire(touched)?.let { return ContactEventDeltaResult.Failure(it) }
        context.changedContactIds += touched
        return ContactEventDeltaResult.Resolved(updated.values.toList())
    }

    companion object {
        const val STRATEGY_VERSION = 1
        // Durable obligation to verify the entire group catalog before trusting another delta.
        const val GROUP_RESCAN_REQUIRED = 2
        // A bounded safety reconciliation, not a per-pass crawl; no retention SLA is assumed.
        const val FULL_SCAN_INTERVAL_MILLIS = 7L * 24 * 60 * 60 * 1000
        val RESOURCE_RESCAN_FAILURES = setOf(GatewayFailureCategory.NOT_FOUND,
            GatewayFailureCategory.CONFLICT, GatewayFailureCategory.MALFORMED_RESPONSE)
        private val CURSOR_RESET_FAILURES = setOf(GatewayFailureCategory.NOT_FOUND,
            GatewayFailureCategory.VALIDATION_REJECTED)
        private val SAFETY_CURSOR_RESET_FAILURES = setOf(GatewayFailureCategory.MALFORMED_RESPONSE,
            GatewayFailureCategory.UNKNOWN)
    }
}

internal sealed interface ContactEventDeltaResult {
    data class Resolved(val metadata: List<ContactInventoryMetadata>) : ContactEventDeltaResult
    data object DeletedEmailOwnerUnknown : ContactEventDeltaResult
    data class Failure(val failure: GatewayOutcome.Failure) : ContactEventDeltaResult
}

internal class RemoteEventReadContext(
    val snapshot: VersionedContactInventoryCheckpoint?,
    val events: ContactEventsDelta,
    var fullInventory: Boolean,
    val capturedAtEpochMillis: Long,
    var fullHydration: Boolean = fullInventory,
) {
    var groupsDurable = false
    var fullGroupCatalogDurable = false
    val changedContactIds = mutableSetOf<RemoteContactId>()
    fun checkpoint(plan: ContactInventoryPlan): ContactInventoryPlan = plan.withEventState(
        events.nextCursor,
        if (groupsDurable) EventDrivenRemoteReadSession.STRATEGY_VERSION else EventDrivenRemoteReadSession.GROUP_RESCAN_REQUIRED,
        if (groupsDurable && fullGroupCatalogDurable && fullInventory) capturedAtEpochMillis else snapshot?.checkpoint?.lastFullScanAtEpochMillis,
    )
    override fun toString(): String = "RemoteEventReadContext(REDACTED)"
}
