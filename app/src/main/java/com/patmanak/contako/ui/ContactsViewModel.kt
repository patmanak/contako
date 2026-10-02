package com.patmanak.contako.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactGroup
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.model.GroupMembership
import com.patmanak.contako.domain.policy.ContactSearch
import com.patmanak.contako.domain.policy.CanonicalPrimaryValuePolicy
import com.patmanak.contako.domain.policy.PostalAddressPolicy
import com.patmanak.contako.domain.repository.ContactGroupAssignment
import com.patmanak.contako.domain.model.GroupOperation
import com.patmanak.contako.domain.repository.ContactRepository
import com.patmanak.contako.domain.repository.SaveResult
import com.patmanak.contako.data.proton.ProtonContactFieldValidator
import com.patmanak.contako.data.proton.EditableValueError
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/**
 * Account scope used before a Proton account is connected, and the default for tests and previews.
 *
 * This is NOT the scope the synchronization engine uses. `ProtonGateCRuntime` owns the real one
 * through its own `AccountScope`, and every durable row the engine reads or writes is keyed by it.
 * A screen built on this constant while the engine runs on another scope observes a different
 * partition of the database: contacts appear saved locally, pending mutations are counted against
 * a scope nothing drains, and sync status never changes. Production callers MUST pass the runtime
 * scope through [ContactsViewModel.factory].
 */
internal const val LOCAL_ACCOUNT_ID = "local-v0.1"

data class ContactEditorState(
    val assignmentsEnabled: Boolean = true,
    val draftGeneration: Long = 0,
    val original: CanonicalContact? = null,
    val firstName: String = "",
    val lastName: String = "",
    val displayName: String = "",
    val values: List<ContactValue> = emptyList(),
    val images: List<ContactValue> = emptyList(),
    val groupOptions: List<ContactGroupOption> = emptyList(),
    val initialGroupAssignments: Set<ContactGroupAssignment> = emptySet(),
    val selectedGroupAssignments: Set<ContactGroupAssignment> = emptySet(),
    val fieldErrors: Map<String, UiMessage> = emptyMap(),
    val validationError: UiMessage? = null,
    val saving: Boolean = false,
)

data class ContactGroupOption(
    val id: String,
    val name: String,
    val color: String,
)

data class GroupEditorState(
    val detailsEnabled: Boolean = true,
    val membershipsEnabled: Boolean = true,
    val draftGeneration: Long = 0,
    val saving: Boolean = false,
    val original: ContactGroup? = null,
    val name: String = "",
    val color: String = com.patmanak.contako.domain.model.ContactGroupDefaults.CREATE_COLOR,
    val emailOptions: List<EmailMembershipOption> = emptyList(),
    val selectedMemberships: Set<GroupMembership> = emptySet(),
    val contactsWithoutEmailCount: Int = 0,
    val validationError: UiMessage? = null,
)

enum class UiMessage {
    CORRECT_HIGHLIGHTED_FIELDS,
    ENTER_GROUP_NAME,
    SAVE_REJECTED,
    SAVE_FAILED,
    STALE_CONTACT_EDIT,
    GROUP_OPERATION_UNAVAILABLE,
    INVALID_PUBLIC_KEY,
    INVALID_LANGUAGE,
    INVALID_TIME_ZONE,
    INVALID_GENDER,
    INVALID_IMAGE,
    INVALID_URL,
    INVALID_DATE,
    INVALID_VALUE,
}

data class EmailMembershipOption(
    val contactId: String,
    val emailValueId: String,
    val contactName: String,
    val email: String,
) {
    val membership: GroupMembership
        get() = GroupMembership(contactId, emailValueId)
}

data class DeletionStatus(val inProgress: Boolean = false, val failed: Boolean = false)

data class ConflictPanelState(
    val contactId: String? = null,
    val detail: com.patmanak.contako.domain.sync.ContactConflictDetail? = null,
    val busy: Boolean = false,
    val failed: Boolean = false,
) {
    override fun toString() = "ConflictPanelState(REDACTED)"
}

data class ContactsUiState(
    val deniedGroupOperations: Set<GroupOperation> = emptySet(),
    val navigation: NavigationState = NavigationState(),
    val query: String = "",
    val contacts: List<CanonicalContact> = emptyList(),
    val actionContacts: List<CanonicalContact> = emptyList(),
    val pendingContacts: List<CanonicalContact> = emptyList(),
    val androidPendingContacts: List<CanonicalContact> = emptyList(),
    val groups: List<ContactGroup> = emptyList(),
    val pendingMutationCount: Int = 0,
    val syncDashboard: SyncDashboardSnapshot = SyncDashboardSnapshot(SyncDashboardState.CURRENT),
    val repairProgress: RepairProgress? = null,
    val showRepairConfirmation: Boolean = false,
    val contactsPermissionGranted: Boolean = true,
    val contactsPermissionAction: ContactsPermissionAction = ContactsPermissionAction.REQUEST,
    val syncActivity: SyncActivity = SyncActivity.IDLE,
    val contactEditor: ContactEditorState? = null,
    val groupEditor: GroupEditorState? = null,
    val pendingContactDeletion: CanonicalContact? = null,
    val pendingGroupDeletion: ContactGroup? = null,
    val contactDeletionStatus: DeletionStatus = DeletionStatus(),
    val groupDeletionStatus: DeletionStatus = DeletionStatus(),
    val showAccountMenu: Boolean = false,
    val showUnsavedConfirmation: Boolean = false,
    val totalContactCount: Int = contacts.size,
    val totalGroupCount: Int = groups.size,
)

class ContactsViewModel(
    private val repository: ContactRepository,
    private val syncRecoveryDataSource: SyncRecoveryDataSource = EmptySyncRecoveryDataSource,
    private val contactsPermissionBoundary: ContactsPermissionBoundary = GrantedContactsPermissionBoundary,
    private val onMutationCommitted: () -> Unit = {},
    private val fieldValidator: ProtonContactFieldValidator = ProtonContactFieldValidator(),
    savedStateHandle: SavedStateHandle = SavedStateHandle(),
    /** MUST match the scope the synchronization engine runs on. See [LOCAL_ACCOUNT_ID]. */
    private val accountId: String = LOCAL_ACCOUNT_ID,
    deniedGroupOperations: kotlinx.coroutines.flow.Flow<Set<GroupOperation>> = kotlinx.coroutines.flow.flowOf(emptySet()),
) : ViewModel() {
    private val deniedGroups = deniedGroupOperations.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val conflicts = syncRecoveryDataSource.observeConflicts(accountId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val mutableConflictPanel = MutableStateFlow(ConflictPanelState())
    val conflictPanel: StateFlow<ConflictPanelState> = mutableConflictPanel
    private var conflictPanelGeneration = 0L

    fun dismissConflict() {
        if (mutableConflictPanel.value.busy) return
        conflictPanelGeneration++
        mutableConflictPanel.value = ConflictPanelState()
    }

    fun openConflict(contactId: String) {
        val generation = ++conflictPanelGeneration
        mutableConflictPanel.value = ConflictPanelState(contactId = contactId, busy = true)
        viewModelScope.launch {
            val detail = try { syncRecoveryDataSource.loadConflict(accountId, contactId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            if (generation == conflictPanelGeneration) {
                mutableConflictPanel.value = ConflictPanelState(contactId, detail, failed = detail == null)
            }
        }
    }

    fun chooseConflict(choice: com.patmanak.contako.domain.sync.ContactConflictChoice) {
        val panel = mutableConflictPanel.value.takeUnless { it.busy } ?: return
        val detail = panel.detail ?: return
        mutableConflictPanel.value = panel.copy(busy = true, failed = false)
        viewModelScope.launch {
            val queued = try { syncRecoveryDataSource.chooseConflict(accountId, detail.summary, choice) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
            mutableConflictPanel.value = if (queued) ConflictPanelState() else panel.copy(failed = true)
        }
    }
    private val navigationStore = NavigationStateStore(savedStateHandle)
    private val navigation = MutableStateFlow(navigationStore.snapshot())
    private val contactEditor = MutableStateFlow<ContactEditorState?>(null)
    private var nextContactEditorGeneration = 0L
    private var nextGroupEditorGeneration = 0L
    private val groupEditor = MutableStateFlow<GroupEditorState?>(null)
    private val pendingContactDeletion = MutableStateFlow<CanonicalContact?>(null)
    private val pendingGroupDeletion = MutableStateFlow<ContactGroup?>(null)
    private val contactDeletionStatus = MutableStateFlow(DeletionStatus())
    private val groupDeletionStatus = MutableStateFlow(DeletionStatus())
    private val showAccountMenu = MutableStateFlow(false)
    private val showUnsavedConfirmation = MutableStateFlow(false)
    private val showRepairConfirmation = MutableStateFlow(false)
    private val allContacts = repository.observeContacts(accountId)
        .onEach { contacts ->
            clearMissingSelection(RootDestination.CONTACTS, contacts.map { it.id }.toSet())
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val allGroups = repository.observeGroups(accountId)
        .onEach { groups ->
            clearMissingSelection(RootDestination.GROUPS, groups.map { it.id }.toSet())
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val uiState: StateFlow<ContactsUiState> = combine(
        allContacts,
        allGroups,
        repository.observePendingMutationCount(accountId),
        navigation,
        contactEditor,
        groupEditor,
        pendingContactDeletion,
        pendingGroupDeletion,
        showAccountMenu,
        showUnsavedConfirmation,
        syncRecoveryDataSource.observeStatus(accountId),
        syncRecoveryDataSource.observeRepair(accountId),
        showRepairConfirmation,
        contactsPermissionBoundary.granted,
        // A pass often completes in about a second, which is faster than a user can perceive. Hold
        // the visible state briefly so a requested sync never looks like it did nothing; without
        // this the indicator can appear and vanish between two frames.
        syncRecoveryDataSource.observeActivity(accountId).holdVisible(MIN_ACTIVITY_VISIBLE_MILLIS),
        contactsPermissionBoundary.action,
        contactDeletionStatus,
        groupDeletionStatus,
        deniedGroups,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val contacts = values[0] as List<CanonicalContact>
        @Suppress("UNCHECKED_CAST")
        val groups = values[1] as List<ContactGroup>
        val activeNavigation = values[3] as NavigationState
        val activeQuery = activeNavigation.current.query
        val detailOpen = activeNavigation.current.selectedId != null
        val pendingCount = values[2] as Int
        val publishedDashboard = values[10] as SyncDashboardSnapshot?
        val dashboard = (publishedDashboard ?: SyncDashboardSnapshot(SyncDashboardState.CURRENT)).let {
            it.copy(
                state = if (it.state == SyncDashboardState.CURRENT && pendingCount > 0) {
                    SyncDashboardState.PENDING
                } else it.state,
                // Room intent changes before Android schedules a pass and publishes its status.
                pendingMutationCount = pendingCount,
            )
        }
        val contactsInMatchingGroups = if (activeQuery.isBlank()) emptySet() else {
            groups.filter { ContactSearch.matchesGroupName(it.name, activeQuery) }
                .flatMap(ContactGroup::memberships)
                .map(GroupMembership::contactId)
                .toSet()
        }
        // Missing/unfinished Android copies are ordinary work during initial sync. Only
        // a published partial-projection failure makes those outstanding copies actionable.
        val failedAndroidContactIds = if (dashboard.state == SyncDashboardState.ANDROID_PARTIAL) {
            dashboard.androidPendingContactIds
        } else emptySet()
        val actionContacts = contacts.filter { it.actionRequiredReasons.isNotEmpty() || it.conflictState != null ||
            it.id in failedAndroidContactIds || it.id in dashboard.blockedMutationContactIds }
        ContactsUiState(
            deniedGroupOperations = values[18] as Set<GroupOperation>,
            navigation = activeNavigation,
            query = activeQuery,
            contacts = if (detailOpen) contacts else contacts.filter {
                ContactSearch.matches(it, activeQuery) || it.id in contactsInMatchingGroups
            },
            actionContacts = actionContacts,
            pendingContacts = contacts.filter { it.pendingMutationRevision != null },
            androidPendingContacts = contacts.filter { it.id in dashboard.androidPendingContactIds },
            groups = if (detailOpen) groups else groups.filter { ContactSearch.matchesGroupName(it.name, activeQuery) },
            pendingMutationCount = values[2] as Int,
            contactEditor = (values[4] as ContactEditorState?)?.copy(assignmentsEnabled = GroupOperation.ASSIGN_EMAILS !in deniedGroups.value),
            groupEditor = (values[5] as GroupEditorState?)?.let { editor -> editor.copy(
                detailsEnabled = (if (editor.original == null) GroupOperation.CREATE else GroupOperation.UPDATE) !in deniedGroups.value,
                membershipsEnabled = GroupOperation.ASSIGN_EMAILS !in deniedGroups.value,
            ) },
            pendingContactDeletion = values[6] as CanonicalContact?,
            pendingGroupDeletion = values[7] as ContactGroup?,
            showAccountMenu = values[8] as Boolean,
            showUnsavedConfirmation = values[9] as Boolean,
            // A global projection problem may be published as one issue even with several
            // affected contacts. Keep global/group issues, but never undercount the visible list.
            syncDashboard = dashboard.copy(actionRequiredCount = maxOf(dashboard.actionRequiredCount, actionContacts.size)),
            repairProgress = values[11] as RepairProgress?,
            showRepairConfirmation = values[12] as Boolean,
            contactsPermissionGranted = values[13] as Boolean,
            syncActivity = values[14] as SyncActivity,
            contactsPermissionAction = values[15] as ContactsPermissionAction,
            contactDeletionStatus = values[16] as DeletionStatus,
            groupDeletionStatus = values[17] as DeletionStatus,
            totalContactCount = contacts.size,
            totalGroupCount = groups.size,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ContactsUiState())

    fun selectDestination(value: RootDestination) {
        navigationStore.select(value)
        navigation.value = navigationStore.snapshot()
    }

    fun updateQuery(value: String) {
        navigationStore.updateQuery(value)
        navigation.value = navigationStore.snapshot()
    }

    fun updateScroll(index: Int, offset: Int) {
        navigationStore.updateScroll(index, offset)
        navigation.value = navigationStore.snapshot()
    }

    fun selectContact(contact: CanonicalContact) {
        navigationStore.selectItem(contact.id)
        navigation.value = navigationStore.snapshot()
    }

    fun selectGroup(group: ContactGroup) {
        navigationStore.selectItem(group.id)
        navigation.value = navigationStore.snapshot()
    }

    /**
     * Opens a contact from the Groups destination.
     *
     * Members used to be plain text, so reaching a member's details meant leaving Groups and
     * finding the contact by hand. Switching destinations first keeps one selection model rather
     * than teaching the Groups screen to render contact details of its own.
     */
    fun openContactFromGroup(contact: CanonicalContact) {
        navigationStore.select(RootDestination.CONTACTS)
        navigationStore.selectItem(contact.id)
        navigation.value = navigationStore.snapshot()
    }

    fun openSecondary(destination: SecondaryDestination) {
        showAccountMenu.value = false
        navigationStore.open(destination)
        navigation.value = navigationStore.snapshot()
    }

    fun showAccountMenu() { showAccountMenu.value = true }
    fun dismissAccountMenu() { showAccountMenu.value = false }

    fun requestContactsPermission() = contactsPermissionBoundary.requestPermission()

    fun syncNow() {
        viewModelScope.launch { syncRecoveryDataSource.requestSync(accountId) }
    }

    fun startRepair() {
        viewModelScope.launch {
            showRepairConfirmation.value = syncRecoveryDataSource.beginRepair(
                accountId,
                mobileDataConfirmed = false,
            ) == RepairStartResult.CONFIRMATION_REQUIRED
        }
    }

    fun confirmRepair() {
        showRepairConfirmation.value = false
        viewModelScope.launch {
            syncRecoveryDataSource.beginRepair(accountId, mobileDataConfirmed = true)
        }
    }

    fun dismissRepairConfirmation() { showRepairConfirmation.value = false }

    fun cancelRepair() {
        viewModelScope.launch { syncRecoveryDataSource.cancelRepair(accountId) }
    }

    fun editContact(contact: CanonicalContact? = null) {
        val normalizedContact = contact?.copy(values = contact.values.map(PostalAddressPolicy::normalize))
        val initialAssignments = normalizedContact?.let { editedContact ->
            allGroups.value.flatMap { group ->
                group.memberships.filter { it.contactId == editedContact.id }.map { membership ->
                    ContactGroupAssignment(group.id, membership.emailValueId)
                }
            }.toSet()
        }.orEmpty()
        contactEditor.value = ContactEditorState(
            draftGeneration = ++nextContactEditorGeneration,
            original = normalizedContact,
            firstName = normalizedContact?.firstName.orEmpty(),
            lastName = normalizedContact?.lastName.orEmpty(),
            displayName = normalizedContact?.displayName.orEmpty(),
            values = normalizedContact?.values.orEmpty().filter { it.kind in EDITABLE_VALUE_KINDS },
            images = normalizedContact?.values.orEmpty().filter { it.kind in IMAGE_KINDS }
                .sortedWith(compareBy<ContactValue> { it.kind }.thenBy(ContactValue::order)),
            groupOptions = allGroups.value.map { ContactGroupOption(it.id, it.name, it.color) },
            initialGroupAssignments = initialAssignments,
            selectedGroupAssignments = initialAssignments,
        )
    }

    fun updateContactEditor(value: ContactEditorState) {
        val current = contactEditor.value ?: return
        if (current.saving || current.draftGeneration != value.draftGeneration) return
        contactEditor.value = value.copy(validationError = null, fieldErrors = emptyMap())
    }

    fun dismissContactEditor() {
        requestEditorDismiss()
    }

    fun requestEditorDismiss() {
        if (contactEditor.value?.saving == true || groupEditor.value?.saving == true) return
        when {
            contactEditor.value?.isDirty() == true || groupEditor.value?.isDirty() == true ->
                showUnsavedConfirmation.value = true
            else -> discardEditor()
        }
    }

    fun keepEditing() { showUnsavedConfirmation.value = false }

    fun discardEditor() {
        showUnsavedConfirmation.value = false
        contactEditor.value = null
        groupEditor.value = null
    }

    fun handleBack(): Boolean = when {
        showUnsavedConfirmation.value -> { showUnsavedConfirmation.value = false; true }
        pendingContactDeletion.value != null -> { pendingContactDeletion.value = null; true }
        pendingGroupDeletion.value != null -> { pendingGroupDeletion.value = null; true }
        showAccountMenu.value -> { showAccountMenu.value = false; true }
        contactEditor.value != null || groupEditor.value != null -> { requestEditorDismiss(); true }
        else -> navigationStore.back().also { navigation.value = navigationStore.snapshot() }
    }

    fun saveContact() {
        val editor = contactEditor.value?.takeUnless { it.saving } ?: return
        if (editor.saving) return
        val original = editor.original
        val editedValues = normalizeEditorValues(editor.values)
        val editedImages = normalizeImages(editor.images)
        val originalValues = original?.values.orEmpty().associateBy(ContactValue::id)
        val errors = (editedValues + editedImages).filterNot { value ->
            // D-067 preserves imported payloads unchanged; only edited/new content needs
            // editor validation. The upload codec still checks against the hydrated envelope.
            val previous = originalValues[value.id]
            original?.remoteContactId != null && value.preservationKey != null && previous != null &&
                value.preservationKey == previous.preservationKey && value.kind == previous.kind &&
                value.value == previous.value && value.label == previous.label &&
                value.components == previous.components && value.metadata == previous.metadata
        }.mapNotNull { value ->
            fieldValidator.editableValueError(value)?.let { value.id to it.toUiMessage() }
        }.toMap()
        if (errors.isNotEmpty()) {
            contactEditor.value = editor.copy(
                fieldErrors = errors,
                validationError = UiMessage.CORRECT_HIGHLIGHTED_FIELDS,
            )
            return
        }
        val editedStructuredNameId = original?.valuesOf(ContactValueKind.STRUCTURED_NAME)?.firstOrNull()?.id
        val preservedValues = original?.values.orEmpty().filter {
            it.kind !in EDITABLE_VALUE_KINDS && it.kind !in IMAGE_KINDS
        }.map { value ->
            // The imported N property is authoritative at serialization/projection boundaries.
            // Keep its hidden components and decoration, but apply the user's explicit name edit.
            if (value.kind == ContactValueKind.STRUCTURED_NAME && value.id == editedStructuredNameId && original != null &&
                (editor.firstName != original.firstName || editor.lastName != original.lastName)
            ) {
                val components = value.components + mapOf("given" to editor.firstName, "family" to editor.lastName)
                value.copy(
                    components = components,
                    value = listOf("family", "given", "additional", "prefix", "suffix")
                        .joinToString(";") { components[it].orEmpty() },
                )
            } else value
        }
        val contact = (original ?: CanonicalContact(accountId = accountId, id = "")).copy(
            firstName = editor.firstName,
            lastName = editor.lastName,
            displayName = editor.displayName,
            values = preservedValues + editedValues + editedImages,
        ).let { candidate ->
            candidate.copy(displayName = candidate.displayName.ifBlank { candidate.fullName })
        }
        val retainedEmailIds = editedValues.filter { it.kind == ContactValueKind.EMAIL }
            .map(ContactValue::id)
            .toSet()
        val assignments = editor.selectedGroupAssignments.filterTo(mutableSetOf()) {
            it.emailValueId in retainedEmailIds
        }
        if (GroupOperation.ASSIGN_EMAILS in deniedGroups.value && assignments != editor.initialGroupAssignments) {
            contactEditor.value = editor.copy(validationError = UiMessage.GROUP_OPERATION_UNAVAILABLE)
            return
        }
        contactEditor.value = editor.copy(saving = true, validationError = null)
        viewModelScope.launch {
            val result = try {
                repository.saveContactWithGroupAssignments(
                    contact = contact,
                    assignments = assignments,
                    managedGroupIds = editor.groupOptions.map(ContactGroupOption::id).toSet(),
                    baseline = original?.let {
                        com.patmanak.contako.domain.repository.ContactEditBaseline(it, editor.initialGroupAssignments)
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep the draft and surface failure; never log contact data or exception text.
                if (contactEditor.value?.draftGeneration == editor.draftGeneration) {
                    contactEditor.value = editor.copy(validationError = UiMessage.SAVE_FAILED)
                }
                return@launch
            }
            when (result) {
                is SaveResult.Saved -> {
                    if (contactEditor.value?.draftGeneration == editor.draftGeneration) contactEditor.value = null
                    if (editor.original == null) {
                        if (navigationStore.snapshot().destination != RootDestination.CONTACTS) {
                            navigationStore.select(RootDestination.CONTACTS)
                        }
                        navigationStore.selectItem(result.value.id)
                        navigation.value = navigationStore.snapshot()
                    }
                    onMutationCommitted()
                }
                is SaveResult.Rejected -> if (contactEditor.value?.draftGeneration == editor.draftGeneration) {
                    contactEditor.value = editor.copy(validationError =
                        if (com.patmanak.contako.domain.repository.SaveValidationIssue.STALE_CONTACT_EDIT in result.issues)
                            UiMessage.STALE_CONTACT_EDIT else UiMessage.SAVE_REJECTED)
                }
            }
        }
    }

    fun openSearch() {
        navigationStore.openSearch()
        navigation.value = navigationStore.snapshot()
    }

    fun closeSearch() {
        navigationStore.closeSearch()
        navigation.value = navigationStore.snapshot()
    }

    /** Only repository emissions prove absence; the stateIn loading placeholder must not. */
    private fun clearMissingSelection(destination: RootDestination, visibleIds: Set<String>) {
        val selectedId = navigationStore.snapshot().destinationStates.getValue(destination).selectedId ?: return
        if (selectedId !in visibleIds) {
            navigationStore.clearSelection(destination, selectedId)
            navigation.value = navigationStore.snapshot()
        }
    }

    fun toggleContactGroupAssignment(groupId: String, emailValueId: String) {
        if (GroupOperation.ASSIGN_EMAILS in deniedGroups.value) return
        val editor = contactEditor.value?.takeUnless { it.saving } ?: return
        if (editor.groupOptions.none { it.id == groupId }) return
        val editableEmailExists = editor.values.any {
            it.kind == ContactValueKind.EMAIL && it.id == emailValueId && it.value.isNotBlank()
        }
        if (!editableEmailExists) return
        val assignment = ContactGroupAssignment(groupId, emailValueId)
        val selected = editor.selectedGroupAssignments.toMutableSet().apply {
            if (!add(assignment)) remove(assignment)
        }
        contactEditor.value = editor.copy(
            selectedGroupAssignments = selected,
            validationError = null,
        )
    }

    fun requestContactDeletion(contact: CanonicalContact) {
        if (contactDeletionStatus.value.inProgress) return
        contactDeletionStatus.value = DeletionStatus()
        pendingContactDeletion.value = contact
    }

    fun addContactValue(kind: ContactValueKind) {
        require(kind in EDITABLE_VALUE_KINDS)
        val editor = contactEditor.value?.takeUnless { it.saving } ?: return
        if (kind in SINGLETON_VALUE_KINDS && editor.values.any { it.kind == kind }) return
        contactEditor.value = editor.copy(
            values = editor.values + ContactValue(
                id = UUID.randomUUID().toString(),
                kind = kind,
                value = "",
                order = editor.values.count { it.kind == kind },
                metadata = if (kind == ContactValueKind.CUSTOM_DATE) {
                    mapOf("syncDisposition" to "LOCAL_ONLY")
                } else {
                    emptyMap()
                },
                components = if (kind == ContactValueKind.POSTAL_ADDRESS) {
                    PostalAddressPolicy.emptyComponents()
                } else {
                    emptyMap()
                },
            ),
            validationError = null,
            fieldErrors = emptyMap(),
        )
    }

    fun updateContactValue(id: String, value: String) {
        val editor = contactEditor.value?.takeUnless { it.saving } ?: return
        contactEditor.value = editor.copy(
            values = editor.values.map {
                if (it.id != id) it else if (it.kind == ContactValueKind.POSTAL_ADDRESS) {
                    // Compatibility for legacy callers of the former free-form editor.
                    PostalAddressPolicy.updateComponent(
                        it.copy(components = PostalAddressPolicy.emptyComponents()),
                        PostalAddressPolicy.STREET,
                        value,
                    )
                } else {
                    it.copy(value = value)
                }
            },
            validationError = null,
            fieldErrors = editor.fieldErrors - id,
        )
    }

    fun updatePostalAddressComponent(id: String, component: String, value: String) {
        val editor = contactEditor.value?.takeUnless { it.saving } ?: return
        require(component in PostalAddressPolicy.componentKeys)
        contactEditor.value = editor.copy(
            values = editor.values.map {
                if (it.id == id && it.kind == ContactValueKind.POSTAL_ADDRESS) {
                    PostalAddressPolicy.updateComponent(it, component, value)
                } else {
                    it
                }
            },
            validationError = null,
            fieldErrors = editor.fieldErrors - id,
        )
    }

    fun updateContactValueLabel(id: String, label: String) {
        val editor = contactEditor.value?.takeUnless { it.saving } ?: return
        contactEditor.value = editor.copy(
            values = editor.values.map {
                if (it.id == id) it.copy(label = label.trim().ifEmpty { null }) else it
            },
            validationError = null,
        )
    }

    fun moveContactValue(id: String, offset: Int) {
        val editor = contactEditor.value?.takeUnless { it.saving } ?: return
        val selected = editor.values.singleOrNull { it.id == id } ?: return
        val family = editor.values.filter { it.kind == selected.kind }
            .sortedBy(ContactValue::order).toMutableList()
        val from = family.indexOfFirst { it.id == id }
        val to = (from + offset).coerceIn(0, family.lastIndex)
        if (from == to) return
        family.add(to, family.removeAt(from))
        val replacements = family.mapIndexed { index, value -> value.copy(order = index) }
            .associateBy(ContactValue::id)
        contactEditor.value = editor.copy(values = editor.values.map { replacements[it.id] ?: it })
    }

    fun preferContactValue(id: String) {
        val editor = contactEditor.value?.takeUnless { it.saving } ?: return
        val selected = editor.values.singleOrNull { it.id == id } ?: return
        val family = editor.values.filter { it.kind == selected.kind }.sortedBy(ContactValue::order)
        if (family.size < 2) return
        val preference = (listOf(selected) + family.filterNot { it.id == id })
            .mapIndexed { index, value -> value.id to (index + 1).toString() }
            .toMap()
        contactEditor.value = editor.copy(values = editor.values.map { value ->
            if (value.kind != selected.kind) value else value.copy(
                metadata = value.metadata +
                    (CanonicalPrimaryValuePolicy.VCARD_PREF_METADATA to preference.getValue(value.id)),
            )
        })
    }

    fun deleteContactValue(id: String) {
        val editor = contactEditor.value?.takeUnless { it.saving } ?: return
        contactEditor.value = editor.copy(
            values = normalizeEditorValues(editor.values.filterNot { it.id == id }),
            validationError = null,
            fieldErrors = editor.fieldErrors - id,
        )
    }

    fun addImage(kind: ContactValueKind, value: String = "") {
        require(kind in IMAGE_KINDS)
        val editor = contactEditor.value?.takeUnless { it.saving } ?: return
        val family = editor.images.filter { it.kind == kind }
        contactEditor.value = editor.copy(
            images = editor.images + ContactValue(
                id = UUID.randomUUID().toString(),
                kind = kind,
                value = value,
                order = family.size,
                isPrimary = family.isEmpty(),
            ),
            validationError = null,
            fieldErrors = emptyMap(),
        )
    }

    fun reportImageSelectionFailure(draftGeneration: Long? = null) {
        val editor = contactEditor.value?.takeUnless { it.saving } ?: return
        if (draftGeneration != null && editor.draftGeneration != draftGeneration) return
        contactEditor.value = editor.copy(validationError = UiMessage.INVALID_IMAGE)
    }

    fun applySelectedImage(
        draftGeneration: Long,
        kind: ContactValueKind,
        imageId: String?,
        value: String,
    ) {
        require(kind in IMAGE_KINDS)
        val editor = contactEditor.value?.takeUnless { it.saving } ?: return
        if (editor.draftGeneration != draftGeneration) return
        if (imageId == null) {
            addImage(kind, value)
            return
        }
        if (editor.images.none { it.id == imageId && it.kind == kind }) {
            reportImageSelectionFailure(draftGeneration)
        } else {
            updateImage(imageId, value)
        }
    }

    fun updateImage(id: String, value: String) = updateImages { image ->
        if (image.id == id) image.copy(value = value) else image
    }

    fun deleteImage(id: String) {
        val editor = contactEditor.value?.takeUnless { it.saving } ?: return
        contactEditor.value = editor.copy(
            images = normalizeImages(editor.images.filterNot { it.id == id }),
            validationError = null,
            fieldErrors = editor.fieldErrors - id,
        )
    }

    fun moveImage(id: String, offset: Int) {
        val editor = contactEditor.value?.takeUnless { it.saving } ?: return
        val selected = editor.images.singleOrNull { it.id == id } ?: return
        val family = editor.images.filter { it.kind == selected.kind }.sortedBy(ContactValue::order).toMutableList()
        val from = family.indexOfFirst { it.id == id }
        val to = (from + offset).coerceIn(0, family.lastIndex)
        if (from == to) return
        val moved = family.removeAt(from)
        family.add(to, moved)
        val replacements = family.mapIndexed { index, image -> image.copy(order = index) }.associateBy(ContactValue::id)
        contactEditor.value = editor.copy(images = editor.images.map { replacements[it.id] ?: it })
    }

    fun preferImage(id: String) {
        val editor = contactEditor.value?.takeUnless { it.saving } ?: return
        val selected = editor.images.singleOrNull { it.id == id } ?: return
        val family = editor.images.filter { it.kind == selected.kind }.sortedBy(ContactValue::order)
        val preference = (listOf(selected) + family.filterNot { it.id == id })
            .mapIndexed { index, image -> image.id to (index + 1).toString() }.toMap()
        contactEditor.value = editor.copy(images = editor.images.map { image ->
            if (image.kind != selected.kind) image else image.copy(
                isPrimary = image.id == id,
                metadata = image.metadata + ("vcardPref" to preference.getValue(image.id)),
            )
        })
    }

    fun cancelContactDeletion() {
        if (contactDeletionStatus.value.inProgress) return
        pendingContactDeletion.value = null
        contactDeletionStatus.value = DeletionStatus()
    }

    fun confirmContactDeletion() = confirmDeletion(
        pendingContactDeletion, contactDeletionStatus, RootDestination.CONTACTS,
        id = { it.id }, delete = { repository.deleteContact(it.accountId, it.id) },
    )

    fun editGroup(group: ContactGroup? = null) {
        if (group == null && GroupOperation.CREATE in deniedGroups.value) return
        val options = allContacts.value.flatMap { contact ->
            contact.valuesOf(ContactValueKind.EMAIL).map { email ->
                EmailMembershipOption(
                    contactId = contact.id,
                    emailValueId = email.id,
                    contactName = contact.resolvedDisplayName,
                    email = email.value,
                )
            }
        }
        groupEditor.value = GroupEditorState(
            draftGeneration = ++nextGroupEditorGeneration,
            original = group,
            name = group?.name.orEmpty(),
            color = group?.color ?: com.patmanak.contako.domain.model.ContactGroupDefaults.CREATE_COLOR,
            emailOptions = options,
            selectedMemberships = group?.memberships.orEmpty().toSet(),
            contactsWithoutEmailCount = allContacts.value.count {
                it.valuesOf(ContactValueKind.EMAIL).isEmpty()
            },
        )
    }

    fun updateGroupEditor(value: GroupEditorState) {
        val current = groupEditor.value ?: return
        if (current.saving || current.draftGeneration != value.draftGeneration) return
        if ((value.name != current.name || value.color != current.color) &&
            (if (current.original == null) GroupOperation.CREATE else GroupOperation.UPDATE) in deniedGroups.value) return
        groupEditor.value = value.copy(validationError = null)
    }

    fun dismissGroupEditor() {
        requestEditorDismiss()
    }

    fun toggleGroupMembership(option: EmailMembershipOption) {
        if (GroupOperation.ASSIGN_EMAILS in deniedGroups.value) return
        val editor = groupEditor.value ?: return
        if (editor.saving) return
        val membership = option.membership
        val selected = editor.selectedMemberships.toMutableSet().apply {
            if (!add(membership)) remove(membership)
        }
        groupEditor.value = editor.copy(selectedMemberships = selected)
    }

    fun saveGroup() {
        val editor = groupEditor.value ?: return
        if (editor.saving) return
        val required = buildSet {
            if (editor.original == null) add(GroupOperation.CREATE)
            else if (editor.name != editor.original.name || editor.color != editor.original.color) add(GroupOperation.UPDATE)
            if (editor.selectedMemberships != editor.original?.memberships.orEmpty().toSet()) add(GroupOperation.ASSIGN_EMAILS)
        }
        if (required.any { it in deniedGroups.value }) {
            groupEditor.value = editor.copy(validationError = UiMessage.GROUP_OPERATION_UNAVAILABLE)
            return
        }
        if (editor.name.isBlank()) {
            groupEditor.value = editor.copy(validationError = UiMessage.ENTER_GROUP_NAME)
            return
        }
        val group = (editor.original ?: ContactGroup(accountId = accountId, id = "", name = ""))
            .copy(
                name = editor.name,
                color = editor.color,
                memberships = editor.selectedMemberships.toList(),
            )
        groupEditor.value = editor.copy(saving = true, validationError = null)
        viewModelScope.launch {
            val result = try {
                repository.saveGroup(group)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (groupEditor.value?.draftGeneration == editor.draftGeneration) {
                    groupEditor.value = editor.copy(validationError = UiMessage.SAVE_FAILED)
                }
                return@launch
            }
            val ownsEditor = groupEditor.value?.draftGeneration == editor.draftGeneration
            when (result) {
                is SaveResult.Saved -> {
                    if (ownsEditor) groupEditor.value = null
                    if (ownsEditor && editor.original == null) {
                        if (navigationStore.snapshot().destination != RootDestination.GROUPS) {
                            navigationStore.select(RootDestination.GROUPS)
                        }
                        navigationStore.selectItem(result.value.id)
                        navigation.value = navigationStore.snapshot()
                    }
                    onMutationCommitted()
                }
                is SaveResult.Rejected -> if (ownsEditor) groupEditor.value = editor.copy(
                    validationError = UiMessage.SAVE_REJECTED,
                )
            }
        }
    }

    fun requestGroupDeletion(group: ContactGroup) {
        if (GroupOperation.DELETE in deniedGroups.value) return
        if (groupDeletionStatus.value.inProgress) return
        groupDeletionStatus.value = DeletionStatus()
        pendingGroupDeletion.value = group
    }

    fun cancelGroupDeletion() {
        if (groupDeletionStatus.value.inProgress) return
        pendingGroupDeletion.value = null
        groupDeletionStatus.value = DeletionStatus()
    }

    fun confirmGroupDeletion() = if (GroupOperation.DELETE in deniedGroups.value) Unit else confirmDeletion(
        pendingGroupDeletion, groupDeletionStatus, RootDestination.GROUPS,
        id = { it.id }, delete = { repository.deleteGroup(it.accountId, it.id) },
    )

    private fun <T> confirmDeletion(
        pending: MutableStateFlow<T?>,
        status: MutableStateFlow<DeletionStatus>,
        destination: RootDestination,
        id: (T) -> String,
        delete: suspend (T) -> Unit,
    ) {
        val target = pending.value ?: return
        if (status.value.inProgress) return
        // Set before launching: rapid taps cannot submit the same intent twice.
        status.value = DeletionStatus(inProgress = true)
        viewModelScope.launch {
            try {
                delete(target)
            } catch (cancelled: CancellationException) {
                status.value = DeletionStatus()
                throw cancelled
            } catch (_: Exception) {
                status.value = DeletionStatus(failed = true)
                return@launch
            }
            pending.value = null
            status.value = DeletionStatus()
            navigationStore.clearSelection(destination, id(target))
            navigation.value = navigationStore.snapshot()
            try {
                onMutationCommitted()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The delete is already durable. Lifecycle/periodic dispatch can retry
                // the outbox; a wake-up failure must not be reported as a failed delete.
            }
        }
    }

    private fun updateImages(transform: (ContactValue) -> ContactValue) {
        val editor = contactEditor.value?.takeUnless { it.saving } ?: return
        val images = editor.images.map(transform)
        val changedIds = editor.images.zip(images).filter { (before, after) -> before != after }.map { it.first.id }
        contactEditor.value = editor.copy(
            images = images,
            validationError = null,
            fieldErrors = editor.fieldErrors - changedIds.toSet(),
        )
    }

    private fun normalizeImages(images: List<ContactValue>): List<ContactValue> = IMAGE_KINDS.flatMap { kind ->
        val family = images.filter { it.kind == kind && it.value.isNotBlank() }.sortedBy(ContactValue::order)
        val selectedId = family.firstOrNull { it.isPrimary }?.id ?: family.firstOrNull()?.id
        family.mapIndexed { index, image -> image.copy(order = index, isPrimary = image.id == selectedId) }
    }

    private fun normalizeEditorValues(values: List<ContactValue>): List<ContactValue> =
        EDITOR_KIND_ORDER.flatMap { kind ->
            values.map(PostalAddressPolicy::normalize)
                .filter { it.kind == kind && it.value.isNotBlank() }
                .sortedBy(ContactValue::order)
                .mapIndexed { index, value -> value.copy(order = index) }
        }

    companion object {
        private val IMAGE_KINDS = setOf(ContactValueKind.PHOTO, ContactValueKind.LOGO)
        val EDITABLE_VALUE_KINDS = ContactValueKind.entries.toSet() - IMAGE_KINDS - setOf(
            ContactValueKind.STRUCTURED_NAME,
            ContactValueKind.UNKNOWN_VCARD_PROPERTY,
        )
        val SINGLETON_VALUE_KINDS = setOf(
            ContactValueKind.BIRTHDAY,
            ContactValueKind.ANNIVERSARY,
            ContactValueKind.GENDER,
        )
        private val EDITOR_KIND_ORDER = ContactValueKind.entries.filter { it in EDITABLE_VALUE_KINDS }

        fun factory(
            repository: ContactRepository,
            syncRecoveryDataSource: SyncRecoveryDataSource = EmptySyncRecoveryDataSource,
            contactsPermissionBoundary: ContactsPermissionBoundary = GrantedContactsPermissionBoundary,
            onMutationCommitted: () -> Unit = {},
            accountId: String = LOCAL_ACCOUNT_ID,
            deniedGroupOperations: kotlinx.coroutines.flow.Flow<Set<GroupOperation>> = kotlinx.coroutines.flow.flowOf(emptySet()),
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                    ContactsViewModel(
                        repository = repository,
                        deniedGroupOperations = deniedGroupOperations,
                        syncRecoveryDataSource = syncRecoveryDataSource,
                        contactsPermissionBoundary = contactsPermissionBoundary,
                        onMutationCommitted = onMutationCommitted,
                        savedStateHandle = extras.createSavedStateHandle(),
                        accountId = accountId,
                    ) as T
            }
    }
}

/** Minimum time an active sync stays visible, so a fast pass is still perceivable. */
private const val MIN_ACTIVITY_VISIBLE_MILLIS = 900L

/**
 * Emits activity immediately, but keeps a non-idle value on screen for at least [minVisibleMillis].
 *
 * The runner can start and finish a pass faster than the UI can render it, which made a manual sync
 * look inert even though it had run. Returning to idle is delayed, never the onset.
 */
private fun Flow<SyncActivity>.holdVisible(minVisibleMillis: Long): Flow<SyncActivity> = flow {
    var busySince: Long? = null
    collect { activity ->
        if (activity != SyncActivity.IDLE) {
            if (busySince == null) busySince = System.currentTimeMillis()
            emit(activity)
        } else {
            val startedAt = busySince
            busySince = null
            if (startedAt != null) {
                val remaining = minVisibleMillis - (System.currentTimeMillis() - startedAt)
                if (remaining > 0) delay(remaining)
            }
            emit(SyncActivity.IDLE)
        }
    }
}

private fun ContactEditorState.isDirty(): Boolean = original?.let { contact ->
    firstName != contact.firstName || lastName != contact.lastName || displayName != contact.displayName ||
        values != contact.values.filter { it.kind in ContactsViewModel.EDITABLE_VALUE_KINDS } ||
        images != contact.values.filter { it.kind == ContactValueKind.PHOTO || it.kind == ContactValueKind.LOGO }
            .sortedWith(compareBy<ContactValue> { it.kind }.thenBy(ContactValue::order)) ||
        selectedGroupAssignments != initialGroupAssignments
} ?: (firstName.isNotEmpty() || lastName.isNotEmpty() || displayName.isNotEmpty() ||
    values.any { it.value.isNotEmpty() } || images.any { it.value.isNotEmpty() } ||
    selectedGroupAssignments.isNotEmpty())

private fun GroupEditorState.isDirty(): Boolean = original?.let {
    name != it.name || color != it.color || selectedMemberships != it.memberships.toSet()
} ?: (name.isNotEmpty() ||
    color != com.patmanak.contako.domain.model.ContactGroupDefaults.CREATE_COLOR ||
    selectedMemberships.isNotEmpty())

private fun EditableValueError.toUiMessage(): UiMessage = when (this) {
    EditableValueError.PUBLIC_KEY -> UiMessage.INVALID_PUBLIC_KEY
    EditableValueError.LANGUAGE -> UiMessage.INVALID_LANGUAGE
    EditableValueError.TIME_ZONE -> UiMessage.INVALID_TIME_ZONE
    EditableValueError.GENDER -> UiMessage.INVALID_GENDER
    EditableValueError.IMAGE -> UiMessage.INVALID_IMAGE
    EditableValueError.URL -> UiMessage.INVALID_URL
    EditableValueError.DATE -> UiMessage.INVALID_DATE
    EditableValueError.GENERIC -> UiMessage.INVALID_VALUE
}
