package com.patmanak.contako.data.proton

import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.AvailableContactGroups
import com.patmanak.contako.data.gateway.ContactGroupCapabilities
import com.patmanak.contako.data.gateway.ContactGroupCapability
import com.patmanak.contako.data.gateway.ContactGroupCapabilityAvailability
import com.patmanak.contako.data.gateway.ContactGroupMutation
import com.patmanak.contako.data.gateway.ContactGroupPublicCoreSurface
import com.patmanak.contako.data.gateway.ContactInventoryPage
import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.data.gateway.GatewayOutcome
import com.patmanak.contako.data.gateway.InventoryCursor
import com.patmanak.contako.data.gateway.ProtonContactGroupGateway
import com.patmanak.contako.data.gateway.ProtonContactInventoryGateway
import com.patmanak.contako.data.gateway.RemoteContactGroup
import com.patmanak.contako.data.gateway.RemoteGroupId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupCapabilityCoordinatorTest {
    @Test fun uiDenialsRemainOperationScopedIncludingEmailAssignments() = runTest {
        val fake = FakeGroupGateway()
        val coordinator = AccountScopedContactGroupCapabilityCoordinator(ACCOUNT, fake)
        fake.createOutcome = GatewayOutcome.Failure(GatewayFailureCategory.PERMISSION_OR_PLAN_DENIED)
        coordinator.create(ACCOUNT, ContactGroupMutation.Create("Fixture", null))
        coordinator.list(ACCOUNT)
        assertEquals(setOf(com.patmanak.contako.domain.model.GroupOperation.CREATE), coordinator.deniedOperations.value)
        val assignments = coordinator.assignments(object : com.patmanak.contako.data.gateway.ProtonContactEmailLabelGateway {
            override suspend fun apply(account: AccountScope, mutation: com.patmanak.contako.data.gateway.EmailLabelMutation) =
                GatewayOutcome.Failure(GatewayFailureCategory.PERMISSION_OR_PLAN_DENIED)
        })
        assignments.apply(ACCOUNT, com.patmanak.contako.data.gateway.EmailLabelMutation.Assign(RemoteGroupId("group"),
            listOf(com.patmanak.contako.data.gateway.RemoteEmailId("email"))))
        assertEquals(setOf(com.patmanak.contako.domain.model.GroupOperation.CREATE,
            com.patmanak.contako.domain.model.GroupOperation.ASSIGN_EMAILS), coordinator.deniedOperations.value)
        coordinator.invalidate(ACCOUNT)
        assertTrue(coordinator.deniedOperations.value.isEmpty())
    }
    @Test
    fun `02-GROUP-CAP success alone proves available and explicit denial alone proves unavailable`() = runTest {
        val fake = FakeGroupGateway()
        val coordinator = AccountScopedContactGroupCapabilityCoordinator(ACCOUNT, fake)

        assertAvailability(coordinator, ContactGroupCapability.LIST, ContactGroupCapabilityAvailability.UNKNOWN)
        coordinator.list(ACCOUNT)
        assertAvailability(coordinator, ContactGroupCapability.LIST, ContactGroupCapabilityAvailability.AVAILABLE)
        assertEquals(ContactGroupCapabilityAvailability.AVAILABLE, coordinator.snapshot().availability)

        fake.createOutcome = GatewayOutcome.Failure(GatewayFailureCategory.PERMISSION_OR_PLAN_DENIED)
        coordinator.create(ACCOUNT, ContactGroupMutation.Create("Fixture", null))
        assertAvailability(coordinator, ContactGroupCapability.CREATE, ContactGroupCapabilityAvailability.DENIED)
        assertEquals(ContactGroupCapabilityAvailability.DENIED, coordinator.snapshot().availability)
        assertEquals(
            ContactGroupCapabilityEvidence.EXPLICIT_PERMISSION_OR_PLAN_DENIAL,
            coordinator.snapshot().evidence,
        )
    }

    @Test
    fun `02-GROUP-CAP paid Mail is only a hint and every other failure remains unknown`() = runTest {
        val fake = FakeGroupGateway()
        val coordinator = AccountScopedContactGroupCapabilityCoordinator(ACCOUNT, fake)
        coordinator.updatePaidMailHint(ACCOUNT, true)
        assertEquals(ContactGroupCapabilityAvailability.UNKNOWN, coordinator.snapshot().availability)
        assertEquals(true, coordinator.snapshot().paidMailHint)

        val nonAuthoritativeFailures = GatewayFailureCategory.entries -
            GatewayFailureCategory.PERMISSION_OR_PLAN_DENIED
        nonAuthoritativeFailures.forEach { failure ->
            fake.updateOutcome = GatewayOutcome.Success(group())
            coordinator.update(ACCOUNT, ContactGroupMutation.Update(RemoteGroupId("group"), "Fixture", null))
            fake.updateOutcome = GatewayOutcome.Failure(failure)
            coordinator.update(ACCOUNT, ContactGroupMutation.Update(RemoteGroupId("group"), "Fixture", null))

            assertAvailability(
                coordinator,
                ContactGroupCapability.UPDATE,
                ContactGroupCapabilityAvailability.UNKNOWN,
            )
            assertEquals(ContactGroupCapabilityAvailability.UNKNOWN, coordinator.snapshot().availability)
        }
    }

    @Test
    fun `02-GROUP-CAP cancellation propagates and invalidates only the attempted capability`() = runTest {
        val cancellation = CancellationException("synthetic")
        val fake = FakeGroupGateway().apply { listFailure = cancellation }
        val coordinator = AccountScopedContactGroupCapabilityCoordinator(ACCOUNT, fake)
        coordinator.create(ACCOUNT, ContactGroupMutation.Create("Fixture", null))

        var observed: CancellationException? = null
        try {
            coordinator.list(ACCOUNT)
        } catch (error: CancellationException) {
            observed = error
        }

        assertSame(cancellation, observed)
        assertAvailability(coordinator, ContactGroupCapability.LIST, ContactGroupCapabilityAvailability.UNKNOWN)
        assertAvailability(coordinator, ContactGroupCapability.CREATE, ContactGroupCapabilityAvailability.AVAILABLE)
        assertEquals(ContactGroupCapabilityAvailability.UNKNOWN, coordinator.snapshot().availability)
    }

    @Test
    fun `02-GROUP-CAP session subscription foreground and manual refresh invalidate evidence`() = runTest {
        ContactGroupCapabilityInvalidationReason.entries.forEach { reason ->
            val coordinator = AccountScopedContactGroupCapabilityCoordinator(ACCOUNT, FakeGroupGateway())
            coordinator.updatePaidMailHint(ACCOUNT, true)
            coordinator.list(ACCOUNT)
            val revision = coordinator.snapshot().revision

            coordinator.invalidate(ACCOUNT, reason)

            ContactGroupCapability.entries.forEach { capability ->
                assertAvailability(coordinator, capability, ContactGroupCapabilityAvailability.UNKNOWN)
            }
            assertEquals(ContactGroupCapabilityAvailability.UNKNOWN, coordinator.snapshot().availability)
            assertTrue(coordinator.snapshot().revision > revision)
            if (reason.clearsPaidMailHint) {
                assertNull(coordinator.snapshot().paidMailHint)
            } else {
                assertEquals(true, coordinator.snapshot().paidMailHint)
            }
        }
    }

    @Test
    fun `02-GROUP-CAP foreign account is rejected without calling the delegate`() = runTest {
        val fake = FakeGroupGateway()
        val coordinator = AccountScopedContactGroupCapabilityCoordinator(ACCOUNT, fake)

        val outcome = coordinator.list(AccountScope("synthetic-foreign-account"))

        assertEquals(
            GatewayFailureCategory.AUTHENTICATION_REQUIRED,
            (outcome as GatewayOutcome.Failure).category,
        )
        assertEquals(0, fake.calls)
        assertEquals(ContactGroupCapabilityAvailability.UNKNOWN, coordinator.snapshot().availability)
    }

    @Test
    fun `02-GROUP-CAP duplicate group names remain distinct by remote ID`() = runTest {
        val fake = FakeGroupGateway().apply {
            listOutcome = GatewayOutcome.Success(
                AvailableContactGroups(
                    listOf(
                        RemoteContactGroup(RemoteGroupId("one"), "Same", "#6D4AFF"),
                        RemoteContactGroup(RemoteGroupId("two"), "Same", "#6D4AFF"),
                    ),
                ),
            )
        }

        val result = AccountScopedContactGroupCapabilityCoordinator(ACCOUNT, fake).list(ACCOUNT)

        assertEquals(2, (result as GatewayOutcome.Success).value.groups.size)
    }

    @Test
    fun `02-GROUP-CAP unavailable groups never gate ordinary contact inventory`() = runTest {
        val fake = FakeGroupGateway().apply {
            listOutcome = GatewayOutcome.Failure(GatewayFailureCategory.PERMISSION_OR_PLAN_DENIED)
        }
        val coordinator = AccountScopedContactGroupCapabilityCoordinator(ACCOUNT, fake)
        coordinator.list(ACCOUNT)
        var contactCalls = 0
        val ordinaryContacts = object : ProtonContactInventoryGateway {
            override suspend fun page(
                account: AccountScope,
                cursor: InventoryCursor?,
            ): GatewayOutcome<ContactInventoryPage> {
                contactCalls++
                return GatewayOutcome.Success(
                    ContactInventoryPage(
                        contacts = emptyList(),
                        requestedCursor = cursor,
                        nextCursor = null,
                        totalCount = 0,
                    ),
                )
            }
        }

        val outcome = ordinaryContacts.page(ACCOUNT, null)

        assertTrue(outcome is GatewayOutcome.Success)
        assertEquals(1, contactCalls)
        assertEquals(ContactGroupCapabilityAvailability.DENIED, coordinator.snapshot().availability)
    }

    @Test
    fun `05-GROUP capability composition preserves independent operation evidence`() = runTest {
        val fake = FakeGroupGateway()
        val coordinator = AccountScopedContactGroupCapabilityCoordinator(ACCOUNT, fake)
        coordinator.list(ACCOUNT)
        fake.createOutcome = GatewayOutcome.Failure(GatewayFailureCategory.PERMISSION_OR_PLAN_DENIED)
        coordinator.create(ACCOUNT, ContactGroupMutation.Create("Fixture", null))

        assertAvailability(coordinator, ContactGroupCapability.LIST, ContactGroupCapabilityAvailability.AVAILABLE)
        assertAvailability(coordinator, ContactGroupCapability.CREATE, ContactGroupCapabilityAvailability.DENIED)
        assertAvailability(coordinator, ContactGroupCapability.UPDATE, ContactGroupCapabilityAvailability.UNKNOWN)
        assertAvailability(coordinator, ContactGroupCapability.DELETE, ContactGroupCapabilityAvailability.UNKNOWN)
        assertAvailability(coordinator, ContactGroupCapability.ASSIGN_EMAILS, ContactGroupCapabilityAvailability.UNKNOWN)
    }

    private fun assertAvailability(
        coordinator: AccountScopedContactGroupCapabilityCoordinator,
        capability: ContactGroupCapability,
        expected: ContactGroupCapabilityAvailability,
    ) = assertEquals(expected, coordinator.capabilities().availability(capability))

    private companion object {
        val ACCOUNT = AccountScope("synthetic-primary-account")
    }
}

private class FakeGroupGateway : ProtonContactGroupGateway {
    var calls = 0
    var listFailure: CancellationException? = null
    var listOutcome: GatewayOutcome<AvailableContactGroups> = GatewayOutcome.Success(AvailableContactGroups(emptyList()))
    var createOutcome: GatewayOutcome<RemoteContactGroup> = GatewayOutcome.Success(group())
    var updateOutcome: GatewayOutcome<RemoteContactGroup> = GatewayOutcome.Success(group())
    var deleteOutcome: GatewayOutcome<Unit> = GatewayOutcome.Success(Unit)

    override fun capabilities() = ContactGroupCapabilities(
        availability = emptyMap(),
        publicCoreSurface = ContactGroupCapability.entries.associateWith {
            if (it == ContactGroupCapability.ASSIGN_EMAILS) ContactGroupPublicCoreSurface.NOT_EXPOSED
            else ContactGroupPublicCoreSurface.EXPOSED
        },
    )

    override suspend fun list(account: AccountScope): GatewayOutcome<AvailableContactGroups> {
        calls++
        listFailure?.let { throw it }
        return listOutcome
    }

    override suspend fun create(
        account: AccountScope,
        mutation: ContactGroupMutation.Create,
    ) = createOutcome.also { calls++ }

    override suspend fun update(
        account: AccountScope,
        mutation: ContactGroupMutation.Update,
    ) = updateOutcome.also { calls++ }

    override suspend fun delete(
        account: AccountScope,
        mutation: ContactGroupMutation.Delete,
    ) = deleteOutcome.also { calls++ }

    private companion object {
        fun group() = RemoteContactGroup(RemoteGroupId("group"), "Fixture", "#6D4AFF")
    }
}

private fun group() = RemoteContactGroup(RemoteGroupId("group"), "Fixture", "#6D4AFF")
