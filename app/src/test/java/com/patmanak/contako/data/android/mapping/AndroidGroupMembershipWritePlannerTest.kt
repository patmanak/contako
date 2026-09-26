package com.patmanak.contako.data.android.mapping

import com.patmanak.contako.data.android.provider.AndroidProviderAccountName
import com.patmanak.contako.data.android.provider.AndroidOwnedGroupRow
import com.patmanak.contako.data.android.provider.AndroidOwnedGroupRowPage
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class AndroidGroupMembershipWritePlannerTest {
    private val planner = AndroidGroupMembershipWritePlanner()

    @Test
    fun `plan deterministically merges deletes and inserts from trusted current epoch bindings`() {
        val plan = planner.plan(
            account = ACCOUNT,
            androidAccountName = ANDROID_ACCOUNT,
            providerEpoch = EPOCH,
            contact = contact(),
            desired = projection("group-b", "group-c"),
            current = snapshot(mapping("group-a", 11, 101), mapping("group-b", 12, 102)),
            catalog = catalog("group-a" to 11L, "group-b" to 12L, "group-c" to 13L),
            trustedBindings = listOf(
                binding("group-a", 11),
                binding("group-b", 12),
                binding("group-c", 13),
            ),
        )

        assertEquals(setOf("group-b", "group-c"), plan.desiredCanonicalGroupIds)
        assertEquals(
            listOf(
                AndroidGroupMembershipRowOperation.Delete("group-a", 11, 101),
                AndroidGroupMembershipRowOperation.Insert("group-c", 13),
            ),
            plan.operations,
        )
        assertFalse(plan.toString().contains("group-a"))
    }

    @Test
    fun `matching current and desired memberships produce a zero-write plan`() {
        val plan = planner.plan(
            ACCOUNT,
            ANDROID_ACCOUNT,
            EPOCH,
            contact(),
            projection("group-a"),
            snapshot(mapping("group-a", 11, 101)),
            catalog("group-a" to 11L),
            listOf(binding("group-a", 11)),
        )

        assertEquals(emptyList<AndroidGroupMembershipRowOperation>(), plan.operations)
        assertEquals(listOf("group-a"), plan.assertedGroupBindings.map { it.canonicalGroupId })
    }

    @Test
    fun `operation locator must exactly match the binding asserted by the writer`() {
        assertThrows(IllegalArgumentException::class.java) {
            AndroidGroupMembershipWritePlan(
                account = ACCOUNT,
                androidAccountName = ANDROID_ACCOUNT,
                providerEpoch = EPOCH,
                canonicalContactId = CONTACT_ID,
                preferredEmailValueId = EMAIL_ID,
                availability = AndroidGroupMembershipAvailability.AVAILABLE,
                desiredCanonicalGroupIds = setOf("group-a"),
                operations = listOf(AndroidGroupMembershipRowOperation.Insert("group-a", 99)),
                assertedGroupBindings = listOf(AndroidWritableGroupBinding("group-a", 11, "remote-a", 3)),
            )
        }
    }

    @Test
    fun `delete cannot contradict the desired membership set`() {
        assertThrows(IllegalArgumentException::class.java) {
            AndroidGroupMembershipWritePlan(
                account = ACCOUNT,
                androidAccountName = ANDROID_ACCOUNT,
                providerEpoch = EPOCH,
                canonicalContactId = CONTACT_ID,
                preferredEmailValueId = EMAIL_ID,
                availability = AndroidGroupMembershipAvailability.AVAILABLE,
                desiredCanonicalGroupIds = setOf("group-a"),
                operations = listOf(AndroidGroupMembershipRowOperation.Delete("group-a", 11, 101)),
                assertedGroupBindings = listOf(AndroidWritableGroupBinding("group-a", 11, "remote-a", 3)),
            )
        }
    }

    @Test
    fun `no-email repair deletes impossible provider memberships without inventing an address`() {
        val noEmailContact = CanonicalContact(ACCOUNT.value, CONTACT_ID)
        val current = AndroidGroupMembershipSnapshot.create(
            accountId = ACCOUNT.value,
            canonicalContactId = CONTACT_ID,
            preferredEmailValueId = null,
            membershipAvailability = AndroidGroupMembershipAvailability.NO_EMAIL,
            locatorMappings = listOf(mapping("group-a", 11, 101)),
        )
        val desired = AndroidGroupMembershipProjection(
            preferredEmailValueId = null,
            canonicalGroupIds = emptySet(),
            availability = AndroidGroupMembershipAvailability.NO_EMAIL,
        )

        val plan = planner.plan(
            ACCOUNT,
            ANDROID_ACCOUNT,
            EPOCH,
            noEmailContact,
            desired,
            current,
            catalog("group-a" to 11L),
            listOf(binding("group-a", 11)),
        )

        assertEquals(listOf(AndroidGroupMembershipRowOperation.Delete("group-a", 11, 101)), plan.operations)
    }

    @Test
    fun `stale preferred email cannot authorize a provider mutation`() {
        val error = assertThrows(AndroidGroupMembershipWritePlanException::class.java) {
            planner.plan(
                ACCOUNT,
                ANDROID_ACCOUNT,
                EPOCH,
                contact(emailId = "email-current"),
                projection("group-a", emailId = "email-current"),
                snapshot(mapping("group-a", 11, 101), emailId = "email-stale"),
                catalog("group-a" to 11L),
                listOf(binding("group-a", 11)),
            )
        }

        assertEquals(AndroidGroupMembershipWritePlanFailure.STALE_CANONICAL_CONTEXT, error.category)
        assertFalse(error.message.orEmpty().contains("email-stale"))
    }

    @Test
    fun `missing recycled duplicate or cross-scope group bindings fail closed`() {
        val current = snapshot(mapping("group-a", 11, 101))
        val desired = projection("group-a", "group-b")
        assertFailure(AndroidGroupMembershipWritePlanFailure.UNTRUSTED_GROUP_IDENTITY) {
            planner.plan(
                ACCOUNT,
                ANDROID_ACCOUNT,
                EPOCH,
                contact(),
                desired,
                current,
                catalog("group-a" to 11L, "group-b" to 12L),
                listOf(binding("group-a", 11)),
            )
        }
        assertFailure(AndroidGroupMembershipWritePlanFailure.UNTRUSTED_GROUP_IDENTITY) {
            planner.plan(
                ACCOUNT,
                ANDROID_ACCOUNT,
                EPOCH,
                contact(),
                desired,
                current,
                catalog("group-a" to 11L, "group-b" to 12L),
                listOf(binding("group-a", 99), binding("group-b", 12)),
            )
        }
        assertFailure(AndroidGroupMembershipWritePlanFailure.DUPLICATE_LOCATOR) {
            planner.plan(
                ACCOUNT,
                ANDROID_ACCOUNT,
                EPOCH,
                contact(),
                desired,
                current,
                catalog("group-a" to 11L, "group-b" to 12L),
                listOf(binding("group-a", 11), binding("group-b", 11)),
            )
        }
        assertFailure(AndroidGroupMembershipWritePlanFailure.UNTRUSTED_GROUP_IDENTITY) {
            planner.plan(
                ACCOUNT,
                ANDROID_ACCOUNT,
                EPOCH,
                contact(),
                desired,
                current,
                catalog("group-a" to 11L, "group-b" to 12L),
                listOf(binding("group-a", 11), binding("group-b", 12, epoch = EPOCH + 1)),
            )
        }
    }

    @Test
    fun `desired membership bound is enforced before provider planning`() {
        val desired = projection(*(1..129).map { "group-$it" }.toTypedArray())
        assertFailure(AndroidGroupMembershipWritePlanFailure.BOUND_EXCEEDED) {
            planner.plan(
                ACCOUNT,
                ANDROID_ACCOUNT,
                EPOCH,
                contact(),
                desired,
                snapshot(),
                catalog(),
                emptyList(),
            )
        }
    }

    private fun contact(emailId: String = EMAIL_ID) = CanonicalContact(
        accountId = ACCOUNT.value,
        id = CONTACT_ID,
        values = listOf(
            ContactValue(
                id = emailId,
                kind = ContactValueKind.EMAIL,
                value = "redacted@example.invalid",
                order = 0,
                metadata = mapOf("PREF" to "1"),
            ),
        ),
    )

    private fun projection(vararg groupIds: String, emailId: String = EMAIL_ID) =
        AndroidGroupMembershipProjection(
            preferredEmailValueId = emailId,
            canonicalGroupIds = groupIds.toSet(),
            availability = AndroidGroupMembershipAvailability.AVAILABLE,
        )

    private fun snapshot(
        vararg mappings: AndroidGroupMembershipLocatorMapping,
        emailId: String = EMAIL_ID,
    ) = AndroidGroupMembershipSnapshot.create(
        accountId = ACCOUNT.value,
        canonicalContactId = CONTACT_ID,
        preferredEmailValueId = emailId,
        membershipAvailability = AndroidGroupMembershipAvailability.AVAILABLE,
        locatorMappings = mappings.toList(),
    )

    private fun mapping(id: String, groupLocator: Long, dataLocator: Long) =
        AndroidGroupMembershipLocatorMapping(id, groupLocator, dataLocator)

    private fun binding(id: String, locator: Long, epoch: Long = EPOCH) = AndroidTrustedGroupBinding(
        account = ACCOUNT,
        androidAccountName = ANDROID_ACCOUNT,
        providerEpoch = epoch,
        canonicalGroupId = id,
        groupRowId = locator,
        expectedProviderVersion = 3,
        sourceIdentity = "remote-$id",
    )

    private fun catalog(vararg identities: Pair<String, Long>) =
        AndroidCompleteGroupCatalog.fromExhaustivePages(
            account = ACCOUNT,
            providerEpoch = EPOCH,
            pages = listOf(
                AndroidOwnedGroupRowPage(
                    accountName = ANDROID_ACCOUNT,
                    requestedAfterGroupRowId = 0,
                    groups = identities.sortedBy(Pair<String, Long>::second).map { (id, locator) ->
                        AndroidOwnedGroupRow(
                            groupRowId = locator,
                            canonicalGroupIdClaim = id,
                            sourceIdentity = "remote-$id",
                            title = "Synthetic",
                            dirty = false,
                            deleted = false,
                            visible = true,
                            shouldSync = true,
                            version = 3,
                        )
                    },
                    nextAfterGroupRowId = null,
                ),
            ),
        )

    private fun assertFailure(expected: AndroidGroupMembershipWritePlanFailure, action: () -> Unit) {
        val error = assertThrows(AndroidGroupMembershipWritePlanException::class.java) { action() }
        assertEquals(expected, error.category)
        assertFalse(error.message.orEmpty().contains("group-a"))
    }

    private companion object {
        val ACCOUNT = AccountScope("account")
        val ANDROID_ACCOUNT = AndroidProviderAccountName("android-account")
        const val CONTACT_ID = "contact"
        const val EMAIL_ID = "email-primary"
        const val EPOCH = 7L
    }
}
