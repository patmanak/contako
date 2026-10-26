package com.patmanak.contako.domain.repository

import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactGroup
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

/** A local directory read, with observation dates separate from contact/conflict content. */
data class ContactDirectorySnapshot(
    val contacts: List<CanonicalContact> = emptyList(),
    val contentChanges: Map<String, Long> = emptyMap(),
) {
    override fun toString() = "ContactDirectorySnapshot(REDACTED)"
}

interface ContactRepository {
    fun observeContacts(accountId: String): Flow<List<CanonicalContact>>
    fun observeGroups(accountId: String): Flow<List<ContactGroup>>
    fun observePendingMutationCount(accountId: String): Flow<Int>
    fun observeContentChanges(accountId: String): Flow<Map<String, Long>> = flowOf(emptyMap())
    fun observeDirectory(accountId: String): Flow<ContactDirectorySnapshot> =
        combine(observeContacts(accountId), observeContentChanges(accountId)) { contacts, changes ->
            ContactDirectorySnapshot(contacts, changes)
        }

    suspend fun getContact(accountId: String, contactId: String): CanonicalContact?
    suspend fun saveContact(contact: CanonicalContact): SaveResult<CanonicalContact>
    suspend fun saveContactWithGroupAssignments(
        contact: CanonicalContact,
        assignments: Set<ContactGroupAssignment>,
        managedGroupIds: Set<String>,
        baseline: ContactEditBaseline? = null,
    ): SaveResult<CanonicalContact>
    suspend fun deleteContact(accountId: String, contactId: String)
    suspend fun saveGroup(group: ContactGroup): SaveResult<ContactGroup>
    suspend fun deleteGroup(accountId: String, groupId: String)
}

data class ContactGroupAssignment(
    val groupId: String,
    val emailValueId: String,
)

/** Opening state used only for optimistic editor concurrency, never as an upload payload. */
data class ContactEditBaseline(
    val contact: CanonicalContact,
    val assignments: Set<ContactGroupAssignment>,
) {
    override fun toString(): String = "ContactEditBaseline(REDACTED)"
}

sealed interface SaveResult<out T> {
    data class Saved<T>(val value: T) : SaveResult<T>
    data class Rejected(val issues: Set<SaveValidationIssue>) : SaveResult<Nothing>
}

enum class SaveValidationIssue {
    STALE_CONTACT_EDIT,
    BLANK_ACCOUNT_ID,
    ACCOUNT_SCOPE_MISMATCH,
    BLANK_CONTACT_ID,
    BLANK_VALUE_ID,
    DUPLICATE_VALUE_ID,
    NEGATIVE_VALUE_ORDER,
    DUPLICATE_VALUE_ORDER,
    BLANK_GROUP_NAME,
    DUPLICATE_GROUP_MEMBERSHIP,
    MEMBERSHIP_NOT_EMAIL,
    UNKNOWN_GROUP_ASSIGNMENT,
    REMOTE_GROUP_WITHOUT_LOCAL_ID,
    LOCAL_ID_GENERATION_FAILED,
}
