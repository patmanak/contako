package com.patmanak.contako.data.android.mapping

import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactGroup
import com.patmanak.contako.domain.model.GroupMembership
import com.patmanak.contako.domain.policy.CanonicalPrimaryValuePolicy

internal enum class AndroidGroupProjectionFailure {
    ACCOUNT_SCOPE_MISMATCH,
    DUPLICATE_GROUP_ID,
    UNKNOWN_GROUP_ID,
    BOUND_EXCEEDED,
}

/** Category-only failure: group/contact names and stable identities are never disclosed. */
internal class AndroidGroupProjectionException(
    val category: AndroidGroupProjectionFailure,
) : IllegalArgumentException("Android group projection failed: ${category.name}")

internal data class AndroidProjectedGroup(
    val canonicalGroupId: String,
    val title: String,
    val color: String,
    val order: Int,
    val isVisible: Boolean,
) {
    override fun toString(): String =
        "AndroidProjectedGroup(REDACTED, order=$order, isVisible=$isVisible)"
}

internal enum class AndroidGroupMembershipAvailability { AVAILABLE, NO_EMAIL }

internal data class AndroidGroupMembershipProjection(
    val preferredEmailValueId: String?,
    val canonicalGroupIds: Set<String>,
    val availability: AndroidGroupMembershipAvailability,
) {
    init {
        require((availability == AndroidGroupMembershipAvailability.NO_EMAIL) == (preferredEmailValueId == null))
        require(availability != AndroidGroupMembershipAvailability.NO_EMAIL || canonicalGroupIds.isEmpty())
    }

    override fun toString(): String =
        "AndroidGroupMembershipProjection(REDACTED, groupCount=${canonicalGroupIds.size}, " +
            "availability=$availability)"
}

internal data class AndroidGroupMembershipDelta(
    val updatedGroups: List<ContactGroup>,
    val changedGroupIds: Set<String>,
    val preferredEmailValueId: String?,
    val availability: AndroidGroupMembershipAvailability,
) {
    override fun toString(): String =
        "AndroidGroupMembershipDelta(REDACTED, changedGroupCount=${changedGroupIds.size}, " +
            "availability=$availability)"
}

/** Pure preferred-email compatibility policy; provider locators belong to a separate gateway. */
internal class AndroidGroupProjectionPolicy {
    fun projectGroups(accountId: String, groups: List<ContactGroup>): List<AndroidProjectedGroup> {
        validateGroups(accountId, groups)
        return groups.asSequence()
            .filterNot(ContactGroup::isDeleted)
            .sortedWith(
                compareBy<ContactGroup>(ContactGroup::order)
                    .thenBy(ContactGroup::name)
                    .thenBy(ContactGroup::id),
            )
            .map { group ->
                AndroidProjectedGroup(
                    canonicalGroupId = group.id,
                    title = group.name,
                    color = group.color,
                    order = group.order,
                    isVisible = group.isVisible,
                )
            }
            .toList()
    }

    fun projectMemberships(
        contact: CanonicalContact,
        groups: List<ContactGroup>,
    ): AndroidGroupMembershipProjection {
        validateGroups(contact.accountId, groups)
        val email = CanonicalPrimaryValuePolicy.preferredEmail(contact)
            ?: return AndroidGroupMembershipProjection(
                preferredEmailValueId = null,
                canonicalGroupIds = emptySet(),
                availability = AndroidGroupMembershipAvailability.NO_EMAIL,
            )
        val desired = groups.asSequence()
            .filterNot(ContactGroup::isDeleted)
            .filter { group ->
                group.memberships.any { membership ->
                    membership.contactId == contact.id && membership.emailValueId == email.id
                }
            }
            .map(ContactGroup::id)
            .toSortedSet()
        return AndroidGroupMembershipProjection(
            preferredEmailValueId = email.id,
            canonicalGroupIds = desired,
            availability = AndroidGroupMembershipAvailability.AVAILABLE,
        )
    }

    fun applyObservedMemberships(
        contact: CanonicalContact,
        groups: List<ContactGroup>,
        observedCanonicalGroupIds: Set<String>,
    ): AndroidGroupMembershipDelta {
        validateGroups(contact.accountId, groups)
        if (observedCanonicalGroupIds.size > MAX_GROUPS) fail(AndroidGroupProjectionFailure.BOUND_EXCEEDED)
        val activeIds = groups.filterNot(ContactGroup::isDeleted).map(ContactGroup::id).toSet()
        if (!activeIds.containsAll(observedCanonicalGroupIds)) {
            fail(AndroidGroupProjectionFailure.UNKNOWN_GROUP_ID)
        }
        val email = CanonicalPrimaryValuePolicy.preferredEmail(contact)
            ?: return AndroidGroupMembershipDelta(
                updatedGroups = groups,
                changedGroupIds = emptySet(),
                preferredEmailValueId = null,
                availability = AndroidGroupMembershipAvailability.NO_EMAIL,
            )

        val changedIds = mutableSetOf<String>()
        val updated = groups.map { group ->
            if (group.isDeleted) return@map group
            val hasTarget = group.memberships.any { membership ->
                membership.contactId == contact.id && membership.emailValueId == email.id
            }
            val shouldHaveTarget = group.id in observedCanonicalGroupIds
            if (hasTarget == shouldHaveTarget) return@map group
            val withoutTarget = group.memberships.filterNot { membership ->
                membership.contactId == contact.id && membership.emailValueId == email.id
            }
            val nextMemberships = if (shouldHaveTarget) {
                withoutTarget + GroupMembership(contact.id, email.id)
            } else {
                withoutTarget
            }
            if (nextMemberships == group.memberships) {
                group
            } else {
                changedIds += group.id
                group.copy(memberships = nextMemberships)
            }
        }
        return AndroidGroupMembershipDelta(
            updatedGroups = updated,
            changedGroupIds = changedIds,
            preferredEmailValueId = email.id,
            availability = AndroidGroupMembershipAvailability.AVAILABLE,
        )
    }

    private fun validateGroups(accountId: String, groups: List<ContactGroup>) {
        if (groups.size > MAX_GROUPS) fail(AndroidGroupProjectionFailure.BOUND_EXCEEDED)
        if (groups.any { it.accountId != accountId }) fail(AndroidGroupProjectionFailure.ACCOUNT_SCOPE_MISMATCH)
        if (groups.map(ContactGroup::id).distinct().size != groups.size) {
            fail(AndroidGroupProjectionFailure.DUPLICATE_GROUP_ID)
        }
    }

    private fun fail(category: AndroidGroupProjectionFailure): Nothing =
        throw AndroidGroupProjectionException(category)

    private companion object {
        const val MAX_GROUPS = 512
    }
}
