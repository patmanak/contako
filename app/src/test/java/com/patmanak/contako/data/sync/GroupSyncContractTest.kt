package com.patmanak.contako.data.sync

import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.AvailableContactGroups
import com.patmanak.contako.data.gateway.ContactGroupCapabilities
import com.patmanak.contako.data.gateway.ContactGroupMutation
import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.data.gateway.GatewayOutcome
import com.patmanak.contako.data.gateway.ProtonContactGroupGateway
import com.patmanak.contako.data.gateway.RemoteContactGroup
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactGroup
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.model.GroupMembership
import com.patmanak.contako.domain.policy.CanonicalPrimaryValuePolicy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupSyncContractTest {
    @Test
    fun `05-GROUP loads FX-G-001 through FX-G-005 and executes their production oracles`() {
        val fixtures = (1..5).associate { index ->
            val id = "FX-G-${index.toString().padStart(3, '0')}"
            id to Json.parseToJsonElement(groupFixture(id.lowercase() + ".json")).jsonObject
        }
        assertEquals((1..5).map { "FX-G-${it.toString().padStart(3, '0')}" }.toSet(), fixtures.keys)
        fixtures.forEach { (id, fixture) -> assertEquals(id, fixture.getValue("id").jsonPrimitive.content) }

        val preferred = fixtures.getValue("FX-G-001")
        val emails = preferred.getValue("contacts").jsonArray.single().jsonObject.getValue("emails").jsonArray
        val contact = CanonicalContact(
            accountId = ACCOUNT.value,
            id = "contact-a",
            displayName = "Fixture contact",
            values = emails.mapIndexed { order, element ->
                val email = element.jsonObject
                ContactValue(
                    id = email.getValue("id").jsonPrimitive.content,
                    kind = ContactValueKind.EMAIL,
                    value = "fixture-$order@example.test",
                    order = order,
                    metadata = mapOf("vcardPref" to email.getValue("pref").jsonPrimitive.content),
                )
            },
        )
        assertEquals("email-preferred", CanonicalPrimaryValuePolicy.preferredEmail(contact)?.id)

        val duplicateNames = fixtures.getValue("FX-G-002").getValue("groups").jsonArray
        assertEquals(1, duplicateNames.map { it.jsonObject.getValue("name").jsonPrimitive.content }.distinct().size)
        assertEquals(2, duplicateNames.map { it.jsonObject.getValue("id").jsonPrimitive.content }.distinct().size)

        val many = fixtures.getValue("FX-G-003").getValue("groups").jsonArray.last().jsonObject
        val memberships = many.getValue("members").jsonArray.map { member ->
            val (contactId, emailId) = member.jsonPrimitive.content.split(':')
            GroupMembership(contactId, emailId)
        }
        assertEquals(2, ContactGroup(ACCOUNT.value, "g-many", "Many", memberships = memberships).memberCount)
        assertEquals(
            listOf("rename", "recolor", "delete"),
            fixtures.getValue("FX-G-003").getValue("operations").jsonArray.map { it.jsonPrimitive.content },
        )

        val capabilities = fixtures.getValue("FX-G-004")
        assertEquals(
            listOf("unknown", "available", "unavailable", "unknown", "available"),
            capabilities.getValue("states").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(5, capabilities.getValue("paidMailHints").jsonArray.size)

        val offline = fixtures.getValue("FX-G-005")
        assertEquals("contact-no-email", offline.getValue("contact").jsonPrimitive.content)
        assertTrue(offline.getValue("operations").jsonArray.map { it.jsonPrimitive.content }
            .containsAll(listOf("assign-offline", "capability-unavailable", "contact-sync")))
    }

    @Test
    fun `05-GROUP explicit Free denial is unavailable without local persistence`() = runTest {
        var commits = 0
        val result = IncrementalRemoteGroupStage(
            gateway(GatewayOutcome.Failure(GatewayFailureCategory.PERMISSION_OR_PLAN_DENIED)),
            RemoteGroupReconciliationStore { _, _ -> commits++ },
        ).run(ACCOUNT)

        assertEquals(RemoteGroupStageResult.Unavailable, result)
        assertEquals(0, commits)
    }

    @Test
    fun `05-GROUP transient failure remains unknown and retryable`() = runTest {
        val result = IncrementalRemoteGroupStage(
            gateway(GatewayOutcome.Failure(GatewayFailureCategory.NETWORK_UNAVAILABLE)),
            RemoteGroupReconciliationStore { _, _ -> error("must not commit") },
        ).run(ACCOUNT)

        assertTrue(result is RemoteGroupStageResult.RetryWaiting)
    }

    private fun gateway(outcome: GatewayOutcome<AvailableContactGroups>) = object : ProtonContactGroupGateway {
        override fun capabilities() = ContactGroupCapabilities.PROTON_CORE_36_6_2_SURFACE
        override suspend fun list(account: AccountScope) = outcome
        override suspend fun create(account: AccountScope, mutation: ContactGroupMutation.Create): GatewayOutcome<RemoteContactGroup> = error("unused")
        override suspend fun update(account: AccountScope, mutation: ContactGroupMutation.Update): GatewayOutcome<RemoteContactGroup> = error("unused")
        override suspend fun delete(account: AccountScope, mutation: ContactGroupMutation.Delete): GatewayOutcome<Unit> = error("unused")
    }

    private fun groupFixture(name: String): String = requireNotNull(
        javaClass.getResource("/fixtures/groups/$name"),
    ).readText()

    private companion object {
        val ACCOUNT = AccountScope("synthetic-v05-account")
    }
}
