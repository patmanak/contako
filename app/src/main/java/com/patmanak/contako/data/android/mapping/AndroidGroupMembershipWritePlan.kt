package com.patmanak.contako.data.android.mapping

import com.patmanak.contako.data.android.provider.AndroidProviderAccountName
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.domain.model.CanonicalContact

internal enum class AndroidGroupMembershipWritePlanFailure {
    ACCOUNT_SCOPE_MISMATCH,
    CONTACT_IDENTITY_MISMATCH,
    STALE_CANONICAL_CONTEXT,
    UNTRUSTED_GROUP_IDENTITY,
    DUPLICATE_LOCATOR,
    BOUND_EXCEEDED,
}

/** Category-only planning failure; canonical identities and locators remain redacted. */
internal class AndroidGroupMembershipWritePlanException(
    val category: AndroidGroupMembershipWritePlanFailure,
) : IllegalArgumentException("Android group membership write planning failed: ${category.name}")

internal sealed interface AndroidGroupMembershipRowOperation {
    val canonicalGroupId: String
    val groupRowLocator: Long

    data class Insert(
        override val canonicalGroupId: String,
        override val groupRowLocator: Long,
    ) : AndroidGroupMembershipRowOperation {
        init {
            require(canonicalGroupId.isNotBlank())
            require(groupRowLocator > 0)
        }

        override fun toString(): String = "AndroidGroupMembershipRowOperation.Insert(REDACTED)"
    }

    data class Delete(
        override val canonicalGroupId: String,
        override val groupRowLocator: Long,
        val dataRowLocator: Long,
    ) : AndroidGroupMembershipRowOperation {
        init {
            require(canonicalGroupId.isNotBlank())
            require(groupRowLocator > 0)
            require(dataRowLocator > 0)
        }

        override fun toString(): String = "AndroidGroupMembershipRowOperation.Delete(REDACTED)"
    }
}

// A membership's semantic identity is its canonical group. Re-targeting a Data row would change
// that identity, so replacements are deliberately represented as one guarded delete plus insert.

/** Complete provider claim that the writer asserts inside the same batch as membership mutations. */
internal data class AndroidWritableGroupBinding(
    val canonicalGroupId: String,
    val groupRowLocator: Long,
    val sourceIdentity: String?,
    val expectedVersion: Long,
) {
    init {
        require(canonicalGroupId.isNotBlank())
        require(groupRowLocator > 0)
        require(sourceIdentity == null || sourceIdentity.isNotBlank())
        require(expectedVersion >= 0)
    }

    override fun toString(): String = "AndroidWritableGroupBinding(REDACTED)"
}

/**
 * Provider write plan derived only from one complete current observation and current ledger bindings.
 * The writer MUST apply these operations in the contact's existing version-guarded batch.
 */
internal data class AndroidGroupMembershipWritePlan(
    val account: AccountScope,
    val androidAccountName: AndroidProviderAccountName,
    val providerEpoch: Long,
    val canonicalContactId: String,
    val preferredEmailValueId: String?,
    val availability: AndroidGroupMembershipAvailability,
    val desiredCanonicalGroupIds: Set<String>,
    val operations: List<AndroidGroupMembershipRowOperation>,
    val assertedGroupBindings: List<AndroidWritableGroupBinding>,
) {
    init {
        require(providerEpoch >= 0)
        require(canonicalContactId.isNotBlank())
        require((availability == AndroidGroupMembershipAvailability.NO_EMAIL) == (preferredEmailValueId == null))
        require(availability != AndroidGroupMembershipAvailability.NO_EMAIL || desiredCanonicalGroupIds.isEmpty())
        require(desiredCanonicalGroupIds.size <= MAX_MEMBERSHIPS)
        require(operations.size <= MAX_MEMBERSHIPS)
        require(operations.map(AndroidGroupMembershipRowOperation::canonicalGroupId).distinct().size == operations.size)
        require(operations.mapNotNull { (it as? AndroidGroupMembershipRowOperation.Delete)?.dataRowLocator }
            .distinct().size == operations.count { it is AndroidGroupMembershipRowOperation.Delete })
        require(operations.filterIsInstance<AndroidGroupMembershipRowOperation.Delete>()
            .none { it.canonicalGroupId in desiredCanonicalGroupIds })
        require(assertedGroupBindings.map(AndroidWritableGroupBinding::canonicalGroupId).distinct().size ==
            assertedGroupBindings.size)
        require(assertedGroupBindings.map(AndroidWritableGroupBinding::groupRowLocator).distinct().size ==
            assertedGroupBindings.size)
        val operationLocatorById = operations.associate {
            it.canonicalGroupId to it.groupRowLocator
        }
        val bindingLocatorById = assertedGroupBindings.associate {
            it.canonicalGroupId to it.groupRowLocator
        }
        require(operationLocatorById.all { (id, locator) -> bindingLocatorById[id] == locator })
        val requiredBindingIds = desiredCanonicalGroupIds + operations
            .filterIsInstance<AndroidGroupMembershipRowOperation.Delete>()
            .map(AndroidGroupMembershipRowOperation.Delete::canonicalGroupId)
        require(bindingLocatorById.keys == requiredBindingIds)
    }

    override fun toString(): String =
        "AndroidGroupMembershipWritePlan(REDACTED, operationCount=${operations.size}, availability=$availability)"

    companion object {
        const val MAX_MEMBERSHIPS = 128
    }
}

internal class AndroidGroupMembershipWritePlanner {
    fun plan(
        account: AccountScope,
        androidAccountName: AndroidProviderAccountName,
        providerEpoch: Long,
        contact: CanonicalContact,
        desired: AndroidGroupMembershipProjection,
        current: AndroidGroupMembershipSnapshot,
        catalog: AndroidCompleteGroupCatalog,
        trustedBindings: List<AndroidTrustedGroupBinding>,
    ): AndroidGroupMembershipWritePlan {
        if (providerEpoch < 0) fail(AndroidGroupMembershipWritePlanFailure.ACCOUNT_SCOPE_MISMATCH)
        if (contact.accountId != account.value || current.accountId != account.value) {
            fail(AndroidGroupMembershipWritePlanFailure.ACCOUNT_SCOPE_MISMATCH)
        }
        if (catalog.account != account || catalog.androidAccountName != androidAccountName ||
            catalog.providerEpoch != providerEpoch
        ) {
            fail(AndroidGroupMembershipWritePlanFailure.ACCOUNT_SCOPE_MISMATCH)
        }
        if (current.canonicalContactId != contact.id) {
            fail(AndroidGroupMembershipWritePlanFailure.CONTACT_IDENTITY_MISMATCH)
        }
        try {
            current.requireCurrentCanonicalContext(contact)
        } catch (_: AndroidGroupSnapshotContextException) {
            fail(AndroidGroupMembershipWritePlanFailure.STALE_CANONICAL_CONTEXT)
        }
        if (desired.preferredEmailValueId != current.preferredEmailValueId ||
            desired.availability != current.membershipAvailability
        ) {
            fail(AndroidGroupMembershipWritePlanFailure.STALE_CANONICAL_CONTEXT)
        }
        if (desired.canonicalGroupIds.size > AndroidGroupMembershipWritePlan.MAX_MEMBERSHIPS ||
            current.locatorMappings.size > AndroidGroupMembershipWritePlan.MAX_MEMBERSHIPS ||
            trustedBindings.size > MAX_BINDINGS
        ) {
            fail(AndroidGroupMembershipWritePlanFailure.BOUND_EXCEEDED)
        }
        if (trustedBindings.any {
                it.account != account ||
                    it.androidAccountName != androidAccountName ||
                    it.providerEpoch != providerEpoch
            }
        ) {
            fail(AndroidGroupMembershipWritePlanFailure.UNTRUSTED_GROUP_IDENTITY)
        }
        if (trustedBindings.map(AndroidTrustedGroupBinding::canonicalGroupId).distinct().size != trustedBindings.size ||
            trustedBindings.map(AndroidTrustedGroupBinding::groupRowId).distinct().size != trustedBindings.size
        ) {
            fail(AndroidGroupMembershipWritePlanFailure.DUPLICATE_LOCATOR)
        }
        val bindingsById = trustedBindings.associateBy(AndroidTrustedGroupBinding::canonicalGroupId)
        val claimedCatalogRows = catalog.rows.filter { it.canonicalGroupIdClaim != null }
        if (claimedCatalogRows.map { requireNotNull(it.canonicalGroupIdClaim) }.distinct().size !=
            claimedCatalogRows.size
        ) {
            fail(AndroidGroupMembershipWritePlanFailure.DUPLICATE_LOCATOR)
        }
        val catalogById = claimedCatalogRows.associateBy { requireNotNull(it.canonicalGroupIdClaim) }
        val requiredIds = current.canonicalGroupIds.toSet() + desired.canonicalGroupIds
        if (!bindingsById.keys.containsAll(requiredIds) || !catalogById.keys.containsAll(requiredIds)) {
            fail(AndroidGroupMembershipWritePlanFailure.UNTRUSTED_GROUP_IDENTITY)
        }
        requiredIds.forEach { groupId ->
            val binding = bindingsById.getValue(groupId)
            val row = catalogById.getValue(groupId)
            if (row.deleted || row.groupRowId != binding.groupRowId ||
                row.sourceIdentity != binding.sourceIdentity
            ) {
                fail(AndroidGroupMembershipWritePlanFailure.UNTRUSTED_GROUP_IDENTITY)
            }
        }
        current.locatorMappings.forEach { mapping ->
            if (bindingsById.getValue(mapping.canonicalGroupId).groupRowId != mapping.groupRowLocator) {
                fail(AndroidGroupMembershipWritePlanFailure.UNTRUSTED_GROUP_IDENTITY)
            }
        }

        val currentById = current.locatorMappings.associateBy(AndroidGroupMembershipLocatorMapping::canonicalGroupId)
        val deletes = (currentById.keys - desired.canonicalGroupIds).sorted().map { groupId ->
            val mapping = currentById.getValue(groupId)
            AndroidGroupMembershipRowOperation.Delete(
                canonicalGroupId = groupId,
                groupRowLocator = mapping.groupRowLocator,
                dataRowLocator = mapping.dataRowLocator,
            )
        }
        val inserts = (desired.canonicalGroupIds - currentById.keys).sorted().map { groupId ->
            AndroidGroupMembershipRowOperation.Insert(
                canonicalGroupId = groupId,
                groupRowLocator = bindingsById.getValue(groupId).groupRowId,
            )
        }
        return AndroidGroupMembershipWritePlan(
            account = account,
            androidAccountName = androidAccountName,
            providerEpoch = providerEpoch,
            canonicalContactId = contact.id,
            preferredEmailValueId = desired.preferredEmailValueId,
            availability = desired.availability,
            desiredCanonicalGroupIds = desired.canonicalGroupIds.toSet(),
            operations = deletes + inserts,
            assertedGroupBindings = requiredIds.sorted().map { groupId ->
                val binding = bindingsById.getValue(groupId)
                val row = catalogById.getValue(groupId)
                AndroidWritableGroupBinding(
                    canonicalGroupId = groupId,
                    groupRowLocator = binding.groupRowId,
                    sourceIdentity = binding.sourceIdentity,
                    expectedVersion = row.version,
                )
            },
        )
    }

    private fun fail(category: AndroidGroupMembershipWritePlanFailure): Nothing =
        throw AndroidGroupMembershipWritePlanException(category)

    private companion object {
        const val MAX_BINDINGS = 512
    }
}
