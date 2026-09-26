package com.patmanak.contako.data.android.mapping

import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactGroup
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.model.GroupMembership
import com.patmanak.contako.domain.policy.CanonicalPrimaryValuePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidGroupProjectionPolicyTest {
    private val policy = AndroidGroupProjectionPolicy()

    @Test
    fun preferredEmailUsesLowestPositiveVcardPrefBeforePrimaryAndStableOrder() {
        val contact = contact(
            email("declared-primary", order = 0, primary = true),
            email("pref-two", order = 1, pref = 2),
            email("pref-one", order = 2, pref = 1),
        )

        assertEquals("pref-one", CanonicalPrimaryValuePolicy.preferredEmail(contact)?.id)
        assertEquals(
            "pref-one",
            CanonicalAndroidContactMapper().project(contact).rows
                .single { it.kind == AndroidRowKind.EMAIL && it.isPrimary }
                .identity.canonicalValueId,
        )
    }

    @Test
    fun blankPrimaryEmailFallsBackToTheNextRankedNonblankEmail() {
        val contact = contact(
            email("blank", order = 0, primary = true).copy(value = ""),
            email("usable", order = 1),
        )

        assertEquals("usable", CanonicalPrimaryValuePolicy.preferredEmail(contact)?.id)
        assertEquals(
            "usable",
            CanonicalAndroidContactMapper().project(contact).rows
                .single { it.kind == AndroidRowKind.EMAIL && it.isPrimary }
                .identity.canonicalValueId,
        )
    }

    @Test
    fun stableOrderWinsOverAStaleBooleanPrimaryWhenNoPositivePrefExists() {
        val contact = contact(
            email("ordered-first", order = 0),
            email("stale-primary", order = 1, primary = true),
        )

        assertEquals("ordered-first", CanonicalPrimaryValuePolicy.preferredEmail(contact)?.id)
        assertEquals(
            "ordered-first",
            CanonicalAndroidContactMapper().project(contact).rows
                .single { it.kind == AndroidRowKind.EMAIL && it.isPrimary }
                .identity.canonicalValueId,
        )
    }

    @Test
    fun projectionUsesOnlyPreferredEmailMembershipAndPreservesDuplicateNamesById() {
        val contact = contact(email("preferred", 0, primary = true), email("secondary", 1))
        val groups = listOf(
            group("group-a", "Same", GroupMembership(CONTACT_ID, "preferred")),
            group("group-b", "Same", GroupMembership(CONTACT_ID, "secondary")),
        )

        val descriptors = policy.projectGroups(ACCOUNT, groups)
        val memberships = policy.projectMemberships(contact, groups)

        assertEquals(listOf("group-a", "group-b"), descriptors.map(AndroidProjectedGroup::canonicalGroupId))
        assertEquals(setOf("group-a"), memberships.canonicalGroupIds)
        assertEquals("preferred", memberships.preferredEmailValueId)
    }

    @Test
    fun observedDeltaChangesOnlyPreferredEmailAndPreservesHiddenSecondaryAssignments() {
        val contact = contact(email("preferred", 0, primary = true), email("secondary", 1))
        val untouched = GroupMembership(CONTACT_ID, "secondary")
        val foreign = GroupMembership("other-contact", "other-email")
        val groups = listOf(
            group("remove", "Remove", GroupMembership(CONTACT_ID, "preferred"), untouched),
            group("add", "Add", foreign),
        )

        val delta = policy.applyObservedMemberships(contact, groups, setOf("add"))

        assertEquals(setOf("remove", "add"), delta.changedGroupIds)
        assertEquals(listOf(untouched), delta.updatedGroups.single { it.id == "remove" }.memberships)
        assertEquals(
            listOf(foreign, GroupMembership(CONTACT_ID, "preferred")),
            delta.updatedGroups.single { it.id == "add" }.memberships,
        )
    }

    @Test
    fun identicalObservedMembershipsDoNotReorderOrReportAChange() {
        val contact = contact(email("preferred", 0, primary = true))
        val target = GroupMembership(CONTACT_ID, "preferred")
        val trailing = GroupMembership("other-contact", "other-email")
        val groups = listOf(group("same", "Same", target, trailing))

        val delta = policy.applyObservedMemberships(contact, groups, setOf("same"))

        assertTrue(delta.changedGroupIds.isEmpty())
        assertEquals(groups, delta.updatedGroups)
    }

    @Test
    fun noEmailCannotCreateAnAssignmentAndRequiresControlledReprojection() {
        val contact = contact()
        val groups = listOf(group("group", "Group"))

        val projected = policy.projectMemberships(contact, groups)
        val delta = policy.applyObservedMemberships(contact, groups, setOf("group"))

        assertEquals(AndroidGroupMembershipAvailability.NO_EMAIL, projected.availability)
        assertTrue(projected.canonicalGroupIds.isEmpty())
        assertEquals(AndroidGroupMembershipAvailability.NO_EMAIL, delta.availability)
        assertTrue(delta.changedGroupIds.isEmpty())
        assertEquals(groups, delta.updatedGroups)
    }

    @Test
    fun unknownDeletedCrossAccountDuplicateAndOversizedInputsFailClosed() {
        val contact = contact(email("email", 0, primary = true))
        val active = group("active", "Active")
        val deleted = group("deleted", "Deleted").copy(isDeleted = true)
        assertFailure(AndroidGroupProjectionFailure.UNKNOWN_GROUP_ID) {
            policy.applyObservedMemberships(contact, listOf(active, deleted), setOf("deleted"))
        }
        assertFailure(AndroidGroupProjectionFailure.ACCOUNT_SCOPE_MISMATCH) {
            policy.projectGroups(ACCOUNT, listOf(active.copy(accountId = "foreign")))
        }
        assertFailure(AndroidGroupProjectionFailure.DUPLICATE_GROUP_ID) {
            policy.projectGroups(ACCOUNT, listOf(active, active.copy(name = "Other")))
        }
        assertFailure(AndroidGroupProjectionFailure.BOUND_EXCEEDED) {
            policy.projectGroups(ACCOUNT, List(513) { index -> group("g-$index", "Group") })
        }
    }

    @Test
    fun diagnosticsAreCategoricalAndRedacted() {
        val projected = policy.projectGroups(ACCOUNT, listOf(group("private-id", "Private name"))).single()
        val failure = runCatching {
            policy.applyObservedMemberships(
                contact(email("private-email-id", 0, primary = true)),
                listOf(group("private-id", "Private name")),
                setOf("unknown-private-id"),
            )
        }.exceptionOrNull()

        assertTrue(projected.toString().contains("REDACTED"))
        assertFalse(projected.toString().contains("Private name"))
        assertFalse(failure.toString().contains("unknown-private-id"))
    }

    private fun assertFailure(expected: AndroidGroupProjectionFailure, block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull() as AndroidGroupProjectionException
        assertEquals(expected, failure.category)
    }

    private fun contact(vararg values: ContactValue) = CanonicalContact(
        accountId = ACCOUNT,
        id = CONTACT_ID,
        displayName = "Synthetic",
        values = values.toList(),
    )

    private fun email(id: String, order: Int, primary: Boolean = false, pref: Int? = null) = ContactValue(
        id = id,
        kind = ContactValueKind.EMAIL,
        value = "$id@example.test",
        order = order,
        isPrimary = primary,
        metadata = pref?.let { mapOf("vcardPref" to it.toString()) }.orEmpty(),
    )

    private fun group(id: String, name: String, vararg memberships: GroupMembership) = ContactGroup(
        accountId = ACCOUNT,
        id = id,
        name = name,
        memberships = memberships.toList(),
    )

    private companion object {
        const val ACCOUNT = "account"
        const val CONTACT_ID = "contact"
    }
}
