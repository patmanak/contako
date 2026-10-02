package com.patmanak.contako.data.proton

import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.AvailableContactGroups
import com.patmanak.contako.data.gateway.ContactGroupCapabilities
import com.patmanak.contako.data.gateway.ContactGroupCapability
import com.patmanak.contako.data.gateway.ContactGroupCapabilityAvailability
import com.patmanak.contako.data.gateway.ContactGroupMutation
import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.data.gateway.GatewayOutcome
import com.patmanak.contako.data.gateway.ProtonContactGroupGateway
import com.patmanak.contako.data.gateway.RemoteContactGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Account/session-scoped D-053 evidence around the group gateway.
 *
 * A paid-Mail value is deliberately retained as a UI preflight hint only. It never changes the
 * authoritative availability state. This decorator is absent from ordinary contact gateways:
 * contact inventory, hydration, and CRUD MUST remain usable for every group state.
 */
internal class AccountScopedContactGroupCapabilityCoordinator(
    private val expectedAccount: AccountScope,
    private val delegate: ProtonContactGroupGateway,
) : ProtonContactGroupGateway {
    private val lock = Any()
    private val availability = ContactGroupCapability.entries.associateWith {
        ContactGroupCapabilityAvailability.UNKNOWN
    }.toMutableMap()
    private val state = MutableStateFlow(ContactGroupCapabilitySnapshot())
    private val denied = MutableStateFlow<Set<com.patmanak.contako.domain.model.GroupOperation>>(emptySet())
    val deniedOperations = denied.asStateFlow()

    fun assignments(delegate: com.patmanak.contako.data.gateway.ProtonContactEmailLabelGateway) =
        object : com.patmanak.contako.data.gateway.ProtonContactEmailLabelGateway {
            override suspend fun apply(account: AccountScope, mutation: com.patmanak.contako.data.gateway.EmailLabelMutation): GatewayOutcome<Unit> =
                observe(account, ContactGroupCapability.ASSIGN_EMAILS) { delegate.apply(account, mutation) }
        }

    fun state(): StateFlow<ContactGroupCapabilitySnapshot> = state.asStateFlow()

    fun snapshot(): ContactGroupCapabilitySnapshot = state.value

    override fun capabilities(): ContactGroupCapabilities = synchronized(lock) {
        ContactGroupCapabilities(
            availability = availability.toMap(),
            publicCoreSurface = ContactGroupCapability.entries.associateWith { capability ->
                delegate.capabilities().publicCoreSurface(capability)
            },
        )
    }

    /** Records the maintained generic paid-Mail signal without treating it as authorization. */
    fun updatePaidMailHint(account: AccountScope, hasPaidMail: Boolean?) {
        requireExpected(account)
        synchronized(lock) {
            state.value = state.value.copy(
                paidMailHint = hasPaidMail,
                revision = state.value.nextRevision(),
            )
        }
    }

    /** Session replacement, restoration, subscription change, or refresh invalidates evidence. */
    fun invalidate(
        account: AccountScope,
        reason: ContactGroupCapabilityInvalidationReason,
    ) {
        requireExpected(account)
        synchronized(lock) {
            availability.keys.forEach { availability[it] = ContactGroupCapabilityAvailability.UNKNOWN }
            denied.value = emptySet()
            state.value = state.value.copy(
                availability = ContactGroupCapabilityAvailability.UNKNOWN,
                evidence = ContactGroupCapabilityEvidence.NONE,
                paidMailHint = if (reason.clearsPaidMailHint) null else state.value.paidMailHint,
                revision = state.value.nextRevision(),
            )
        }
    }

    /** Compatibility shorthand for callers that do not need to retain a current preflight hint. */
    fun invalidate(account: AccountScope) {
        invalidate(account, ContactGroupCapabilityInvalidationReason.SESSION_CHANGED)
    }

    override suspend fun list(account: AccountScope): GatewayOutcome<AvailableContactGroups> =
        observe(account, ContactGroupCapability.LIST) { delegate.list(account) }

    override suspend fun create(
        account: AccountScope,
        mutation: ContactGroupMutation.Create,
    ): GatewayOutcome<RemoteContactGroup> = observe(account, ContactGroupCapability.CREATE) {
        delegate.create(account, mutation)
    }

    override suspend fun update(
        account: AccountScope,
        mutation: ContactGroupMutation.Update,
    ): GatewayOutcome<RemoteContactGroup> = observe(account, ContactGroupCapability.UPDATE) {
        delegate.update(account, mutation)
    }

    override suspend fun delete(
        account: AccountScope,
        mutation: ContactGroupMutation.Delete,
    ): GatewayOutcome<Unit> = observe(account, ContactGroupCapability.DELETE) {
        delegate.delete(account, mutation)
    }

    private suspend fun <T> observe(
        account: AccountScope,
        capability: ContactGroupCapability,
        operation: suspend () -> GatewayOutcome<T>,
    ): GatewayOutcome<T> {
        if (account != expectedAccount) {
            return GatewayOutcome.Failure(GatewayFailureCategory.AUTHENTICATION_REQUIRED)
        }
        val outcome = try {
            operation()
        } catch (cancellation: CancellationException) {
            record(capability, ContactGroupCapabilityAvailability.UNKNOWN, ContactGroupCapabilityEvidence.NONE)
            throw cancellation
        }
        when (outcome) {
            is GatewayOutcome.Success -> record(
                capability,
                ContactGroupCapabilityAvailability.AVAILABLE,
                ContactGroupCapabilityEvidence.AUTHORITATIVE_GROUP_SUCCESS,
            )

            is GatewayOutcome.Failure -> if (
                outcome.category == GatewayFailureCategory.PERMISSION_OR_PLAN_DENIED
            ) {
                record(
                    capability,
                    ContactGroupCapabilityAvailability.DENIED,
                    ContactGroupCapabilityEvidence.EXPLICIT_PERMISSION_OR_PLAN_DENIAL,
                )
            } else {
                record(
                    capability,
                    ContactGroupCapabilityAvailability.UNKNOWN,
                    ContactGroupCapabilityEvidence.NONE,
                )
            }
        }
        return outcome
    }

    private fun record(
        capability: ContactGroupCapability,
        resolved: ContactGroupCapabilityAvailability,
        evidence: ContactGroupCapabilityEvidence,
    ) {
        synchronized(lock) {
            availability[capability] = resolved
            denied.value = com.patmanak.contako.domain.model.GroupOperation.entries.filterTo(mutableSetOf()) {
                availability[ContactGroupCapability.valueOf(it.name)] == ContactGroupCapabilityAvailability.DENIED
            }
            state.value = state.value.copy(
                availability = resolved,
                evidence = evidence,
                revision = state.value.nextRevision(),
            )
        }
    }

    private fun requireExpected(account: AccountScope) {
        require(account == expectedAccount)
    }
}

internal data class ContactGroupCapabilitySnapshot(
    val availability: ContactGroupCapabilityAvailability = ContactGroupCapabilityAvailability.UNKNOWN,
    val paidMailHint: Boolean? = null,
    val evidence: ContactGroupCapabilityEvidence = ContactGroupCapabilityEvidence.NONE,
    val revision: Long = 0,
) {
    init {
        require(revision >= 0)
        require(
            when (availability) {
                ContactGroupCapabilityAvailability.AVAILABLE ->
                    evidence == ContactGroupCapabilityEvidence.AUTHORITATIVE_GROUP_SUCCESS

                ContactGroupCapabilityAvailability.DENIED ->
                    evidence == ContactGroupCapabilityEvidence.EXPLICIT_PERMISSION_OR_PLAN_DENIAL

                ContactGroupCapabilityAvailability.UNKNOWN ->
                    evidence == ContactGroupCapabilityEvidence.NONE
            },
        )
    }

    internal fun nextRevision(): Long = Math.incrementExact(revision)

    override fun toString(): String =
        "ContactGroupCapabilitySnapshot(availability=$availability, evidence=$evidence, revision=$revision)"
}

internal enum class ContactGroupCapabilityEvidence {
    NONE,
    AUTHORITATIVE_GROUP_SUCCESS,
    EXPLICIT_PERMISSION_OR_PLAN_DENIAL,
}

internal enum class ContactGroupCapabilityInvalidationReason(
    internal val clearsPaidMailHint: Boolean,
) {
    LOGIN_OR_SESSION_RESTORED(clearsPaidMailHint = true),
    SESSION_CHANGED(clearsPaidMailHint = true),
    USER_OR_SUBSCRIPTION_CHANGED(clearsPaidMailHint = true),
    STALE_FOREGROUND_CHECK(clearsPaidMailHint = false),
    MANUAL_REFRESH(clearsPaidMailHint = false),
}
