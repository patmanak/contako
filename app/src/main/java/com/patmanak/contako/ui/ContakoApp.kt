package com.patmanak.contako.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.layout.ContentScale
import com.patmanak.contako.ui.components.rememberContactImage
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.annotation.DrawableRes
import androidx.compose.ui.res.painterResource
import androidx.annotation.StringRes
import com.patmanak.contako.BuildConfig
import com.patmanak.contako.R
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactGroup
import com.patmanak.contako.domain.model.GroupOperation
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.policy.CanonicalPrimaryValuePolicy
import com.patmanak.contako.domain.policy.PostalAddressPolicy
import com.patmanak.contako.domain.repository.ContactGroupAssignment
import com.patmanak.contako.ui.components.InfoCard
import com.patmanak.contako.ui.components.StatusLabel
import com.patmanak.contako.ui.theme.ThemeMode
import com.patmanak.contako.ui.theme.LocalContakoColors
import com.patmanak.contako.ui.locale.AppLanguage
import com.patmanak.contako.android.account.SignOutChoice
import com.patmanak.contako.android.account.SignOutResult
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContakoApp(
    viewModel: ContactsViewModel,
    accountAddress: String? = null,
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onThemeModeChange: (ThemeMode) -> Unit = {},
    language: AppLanguage = AppLanguage.SYSTEM,
    onLanguageChange: (AppLanguage) -> Unit = {},
    onConfigureSyncNotifications: () -> Unit = {},
    onSignOut: suspend (SignOutChoice) -> SignOutResult = { SignOutResult.CleanupFailed },
) {
    val state by viewModel.uiState.collectAsState()
    val conflictPanel by viewModel.conflictPanel.collectAsState()
    if (conflictPanel.contactId != null) ContactConflictDialog(conflictPanel, viewModel)
    var requestSearchFocus by remember(state.navigation.destination) { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var confirmSignOut by remember { mutableStateOf(false) }
    var signOutPendingReported by remember(accountAddress) { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var signOutFailure by remember { mutableStateOf<SignOutResult?>(null) }
    var signOutInProgress by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun executeSignOut(choice: SignOutChoice) {
        if (signOutInProgress) return
        signOutInProgress = true
        scope.launch {
            val result = onSignOut(choice)
            signOutInProgress = false
            when (result) {
                SignOutResult.SignedOut -> Unit
                SignOutResult.PendingChanges -> {
                    signOutPendingReported = true
                    confirmSignOut = true
                }
                SignOutResult.SyncFailed, SignOutResult.CleanupFailed -> signOutFailure = result
            }
        }
    }
    BackHandler(
        enabled = state.navigation != NavigationState() || state.contactEditor != null ||
            state.groupEditor != null || state.showAccountMenu || state.pendingContactDeletion != null ||
            state.pendingGroupDeletion != null || state.showUnsavedConfirmation,
    ) {
        viewModel.handleBack()
    }
    val syncActivityDescription = stringResource(R.string.sync_running)
    when {
        state.contactEditor != null -> ContactEditorScreen(requireNotNull(state.contactEditor), viewModel)
        state.groupEditor != null -> GroupEditorScreen(requireNotNull(state.groupEditor), viewModel)
        else -> BoxWithConstraints(Modifier.fillMaxSize()) {
        val expanded = navigationMode(maxWidth.value.toInt(), LocalDensity.current.fontScale) == NavigationMode.RAIL
        val directoryVisible = state.navigation.secondary == null &&
            state.navigation.destination != RootDestination.SYNC &&
            (expanded || state.navigation.current.selectedId == null)
        Row(Modifier.fillMaxSize()) {
            if (expanded) NavigationRail {
                RailDestinationItem(RootDestination.CONTACTS, Icons.Default.Person, state, viewModel)
                RailDestinationItem(RootDestination.GROUPS, Icons.AutoMirrored.Filled.List, state, viewModel)
                RailDestinationItem(RootDestination.SYNC, Icons.Default.Refresh, state, viewModel)
            }
            Scaffold(
                modifier = Modifier.weight(1f),
                topBar = {
                    TopAppBar(
                        title = { Text(screenTitle(state)) },
                        navigationIcon = {
                            if (state.navigation.secondary != null ||
                                (!expanded && state.navigation.current.selectedId != null)
                            ) {
                                IconButton(onClick = { viewModel.handleBack() }) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = stringResource(R.string.action_back),
                                    )
                                }
                            }
                        },
                        actions = {
                            if (directoryVisible) {
                                IconButton(onClick = {
                                    requestSearchFocus = true
                                    viewModel.openSearch()
                                }) {
                                    Icon(Icons.Default.Search, contentDescription = stringResource(R.string.directory_search))
                                }
                            }
                            val selectedContact = state.navigation.current.selectedId
                                ?.takeIf {
                                    state.navigation.secondary == null &&
                                        state.navigation.destination == RootDestination.CONTACTS
                                }
                                ?.let { id -> state.contacts.firstOrNull { it.id == id } }
                            val selectedGroup = state.navigation.current.selectedId
                                ?.takeIf {
                                    state.navigation.secondary == null &&
                                        state.navigation.destination == RootDestination.GROUPS
                                }
                                ?.let { id -> state.groups.firstOrNull { it.id == id } }
                            when {
                                selectedContact != null -> ContactDetailAppBarActions(selectedContact, viewModel)
                                selectedGroup != null -> GroupDetailAppBarActions(selectedGroup, viewModel, state.deniedGroupOperations)
                                else -> {
                                    IconButton(onClick = viewModel::showAccountMenu) {
                                        Icon(
                                            Icons.Default.AccountCircle,
                                            contentDescription = stringResource(R.string.account_menu),
                                        )
                                    }
                                    AccountMenu(
                                        expanded = state.showAccountMenu,
                                        accountAddress = accountAddress,
                                        onDismiss = viewModel::dismissAccountMenu,
                                        onOpen = viewModel::openSecondary,
                                        onSignOut = {
                                            viewModel.dismissAccountMenu()
                                            if (!signOutInProgress) {
                                                // A coordinator warning belongs to one attempt. The next
                                                // CONFIRM must observe current Room/provider intent again.
                                                signOutPendingReported = false
                                                confirmSignOut = true
                                            }
                                        },
                                    )
                                }
                            }
                        },
                    )
                },
                bottomBar = {
                    if (!expanded && state.navigation.secondary == null &&
                        state.navigation.current.selectedId == null
                    ) NavigationBar(
                        modifier = Modifier.height(
                            (if (LocalDensity.current.fontScale >= 1.3f) 80.dp else 64.dp) +
                                NavigationBarDefaults.windowInsets.asPaddingValues().calculateBottomPadding(),
                        ),
                    ) {
                        DestinationItem(RootDestination.CONTACTS, Icons.Default.Person, state, viewModel)
                        DestinationItem(RootDestination.GROUPS, Icons.AutoMirrored.Filled.List, state, viewModel)
                        DestinationItem(RootDestination.SYNC, Icons.Default.Refresh, state, viewModel)
                    }
                },
                floatingActionButton = {
                    if (state.navigation.secondary == null && state.navigation.current.selectedId == null) {
                        when (state.navigation.destination) {
                            RootDestination.CONTACTS -> CreationButton(stringResource(R.string.contacts_new)) { viewModel.editContact() }
                            RootDestination.GROUPS -> if (GroupOperation.CREATE !in state.deniedGroupOperations) {
                                CreationButton(stringResource(R.string.groups_new)) { viewModel.editGroup() }
                            }
                            RootDestination.SYNC -> Unit
                        }
                    }
                },
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    // A sync can be triggered from the dashboard, the scheduler or the system, so
                    // the indicator sits under the app bar rather than on one screen. It is a thin
                    // bar that adds no vertical shift, keeping it visible wherever the user is.
                    if (state.syncActivity != SyncActivity.IDLE) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .semantics {
                                    contentDescription = syncActivityDescription
                                },
                        )
                    }
                    if (state.navigation.secondary == null && !state.contactsPermissionGranted) {
                        ContactsPermissionBanner(
                            action = state.contactsPermissionAction,
                            onRequest = viewModel::requestContactsPermission,
                        )
                    }
                    if (directoryVisible && state.navigation.current.searchExpanded) {
                        DirectorySearch(
                            query = state.query,
                            onClose = {
                                requestSearchFocus = false
                                focusManager.clearFocus()
                                keyboard?.hide()
                                viewModel.closeSearch()
                            },
                            requestFocus = requestSearchFocus,
                            onFocusRequested = { requestSearchFocus = false },
                            onQueryChange = viewModel::updateQuery,
                        )
                    }
                    Box(Modifier.fillMaxSize().weight(1f)) {
                        state.navigation.secondary?.let {
                            SecondaryScreen(
                                it,
                                state,
                                viewModel,
                                themeMode,
                                onThemeModeChange,
                                language,
                                onLanguageChange,
                            )
                        } ?: when (state.navigation.destination) {
                            RootDestination.CONTACTS -> ContactsDestination(state, viewModel, expanded)
                            RootDestination.GROUPS -> GroupsDestination(state, viewModel, expanded)
                            RootDestination.SYNC -> SyncDashboard(state, viewModel, onConfigureSyncNotifications)
                        }
                    }
                }
            }
        }
        }
    }
    state.pendingContactDeletion?.let { contact ->
        NamedDeleteDialog(
            name = contact.resolvedDisplayName,
            status = state.contactDeletionStatus,
            onDismiss = viewModel::cancelContactDeletion,
            onConfirm = viewModel::confirmContactDeletion,
        )
    }
    state.pendingGroupDeletion?.let { group ->
        GroupDeleteDialog(
            group = group,
            status = state.groupDeletionStatus,
            onDismiss = viewModel::cancelGroupDeletion,
            onConfirm = viewModel::confirmGroupDeletion,
        )
    }
    if (state.showUnsavedConfirmation) UnsavedDialog(viewModel)
    if (state.showRepairConfirmation) AlertDialog(
        onDismissRequest = viewModel::dismissRepairConfirmation,
        title = { Text(stringResource(R.string.repair_confirm_title)) },
        text = { Text(stringResource(R.string.repair_confirm_body)) },
        confirmButton = { TextButton(onClick = viewModel::confirmRepair) { Text(stringResource(R.string.repair_confirm_action)) } },
        dismissButton = { TextButton(onClick = viewModel::dismissRepairConfirmation) { Text(stringResource(R.string.action_cancel)) } },
    )
    val signOutNeedsPendingChoice = signOutPendingReported || state.pendingMutationCount > 0
    if (confirmSignOut && !signOutNeedsPendingChoice) AlertDialog(
        onDismissRequest = { confirmSignOut = false },
        title = { Text(stringResource(R.string.dialog_signout_title)) },
        text = { Text(stringResource(R.string.dialog_signout_body)) },
        confirmButton = {
            TextButton(
                enabled = !signOutInProgress,
                onClick = {
                    confirmSignOut = false
                    executeSignOut(SignOutChoice.CONFIRM)
                },
            ) { Text(stringResource(R.string.account_sign_out)) }
        },
        dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
    if (confirmSignOut && signOutNeedsPendingChoice) AlertDialog(
        onDismissRequest = { confirmSignOut = false },
        title = { Text(stringResource(R.string.dialog_signout_title)) },
        text = {
            Text(if (signOutPendingReported) {
                stringResource(R.string.dialog_signout_pending_unverified)
            } else {
                pluralStringResource(R.plurals.dialog_signout_pending, state.pendingMutationCount, state.pendingMutationCount)
            })
        },
        confirmButton = {
            TextButton(enabled = !signOutInProgress, onClick = { confirmSignOut = false; executeSignOut(SignOutChoice.SYNC_NOW) }) {
                Text(stringResource(R.string.dialog_sync_before_signout))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { confirmSignOut = false }) { Text(stringResource(R.string.action_cancel)) }
                TextButton(onClick = { confirmSignOut = false; confirmDiscard = true }) {
                    Text(stringResource(R.string.dialog_discard_signout))
                }
            }
        },
    )
    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text(stringResource(R.string.dialog_discard_signout_title)) },
        text = { Text(stringResource(R.string.dialog_discard_signout_body)) },
        confirmButton = {
            TextButton(enabled = !signOutInProgress, onClick = { confirmDiscard = false; executeSignOut(SignOutChoice.DISCARD) }) {
                Text(stringResource(R.string.dialog_discard_signout_confirm))
            }
        },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
    signOutFailure?.let { failure ->
        AlertDialog(
            onDismissRequest = { signOutFailure = null },
            title = { Text(stringResource(R.string.dialog_signout_failed_title)) },
            text = { Text(stringResource(if (failure == SignOutResult.SyncFailed) R.string.dialog_signout_sync_failed else R.string.dialog_signout_cleanup_failed)) },
            confirmButton = { TextButton(onClick = { signOutFailure = null }) { Text(stringResource(R.string.action_close)) } },
        )
    }
}

@Composable
private fun ContactsPermissionBanner(action: ContactsPermissionAction, onRequest: () -> Unit) {
    val largeText = LocalDensity.current.fontScale >= 1.5f
    val actionLabel = stringResource(
        if (action == ContactsPermissionAction.OPEN_SETTINGS) {
            R.string.contacts_permission_open_settings
        } else {
            R.string.contacts_permission_action
        },
    )
    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        if (largeText) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(stringResource(R.string.contacts_permission_banner))
                TextButton(onClick = onRequest, modifier = Modifier.align(Alignment.End)) {
                    Text(actionLabel)
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.contacts_permission_banner), modifier = Modifier.weight(1f))
                TextButton(onClick = onRequest) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
private fun screenTitle(state: ContactsUiState): String = when {
    state.navigation.secondary != null -> stringResource(state.navigation.secondary.labelResource)
    state.navigation.current.selectedId != null && state.navigation.destination == RootDestination.CONTACTS ->
        stringResource(R.string.contact_detail_title)
    state.navigation.current.selectedId != null && state.navigation.destination == RootDestination.GROUPS ->
        state.groups.firstOrNull { it.id == state.navigation.current.selectedId }?.name
            ?: stringResource(R.string.nav_groups)
    else -> stringResource(state.navigation.destination.labelResource)
}

@Composable
private fun AccountMenu(
    expanded: Boolean,
    accountAddress: String?,
    onDismiss: () -> Unit,
    onOpen: (SecondaryDestination) -> Unit,
    onSignOut: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, modifier = Modifier.width(280.dp)) {
        accountAddress?.let { address ->
            Text(
                address,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
        }
        ACCOUNT_MENU_DESTINATIONS.forEach { destination ->
            DropdownMenuItem(text = { Text(stringResource(destination.labelResource)) }, onClick = { onOpen(destination) })
        }
        DropdownMenuItem(text = { Text(stringResource(R.string.account_sign_out)) }, onClick = onSignOut)
    }
}

@Composable
private fun ContactDetailAppBarActions(contact: CanonicalContact, viewModel: ContactsViewModel) {
    var expanded by remember(contact.id) { mutableStateOf(false) }
    IconButton(onClick = { viewModel.editContact(contact) }) {
        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.contact_edit_action))
    }
    IconButton(onClick = { expanded = true }) {
        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.contact_more_actions))
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_delete)) },
            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
            onClick = {
                expanded = false
                viewModel.requestContactDeletion(contact)
            },
        )
    }
}

@Composable
private fun GroupDetailAppBarActions(group: ContactGroup, viewModel: ContactsViewModel, denied: Set<GroupOperation>) {
    var expanded by remember(group.id) { mutableStateOf(false) }
    IconButton(enabled = GroupOperation.UPDATE !in denied || GroupOperation.ASSIGN_EMAILS !in denied, onClick = { viewModel.editGroup(group) }) {
        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.groups_edit))
    }
    IconButton(onClick = { expanded = true }) {
        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.group_more_actions))
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_delete_group)) },
            enabled = GroupOperation.DELETE !in denied,
            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
            onClick = {
                expanded = false
                viewModel.requestGroupDeletion(group)
            },
        )
    }
}

@Composable
private fun RowScope.DestinationItem(
    destination: RootDestination,
    icon: ImageVector,
    state: ContactsUiState,
    viewModel: ContactsViewModel,
) {
    val label = stringResource(destination.labelResource)
    val visibleLabel = if (destination == RootDestination.SYNC && LocalDensity.current.fontScale >= 1.5f) {
        stringResource(R.string.nav_sync_compact)
    } else label
    NavigationBarItem(
        selected = state.navigation.destination == destination,
        onClick = { viewModel.selectDestination(destination) },
        icon = { DestinationIcon(destination, icon, state) },
        label = { Text(visibleLabel, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { contentDescription = label }) },
    )
}

@Composable
private fun CreationButton(label: String, onClick: () -> Unit) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        icon = { Icon(Icons.Default.Add, contentDescription = null) },
        text = { Text(label) },
        modifier = Modifier.semantics { contentDescription = label },
    )
}

@Composable
private fun ContactsDestination(state: ContactsUiState, viewModel: ContactsViewModel, expanded: Boolean) {
    val selected = state.navigation.current.selectedId?.let { id -> state.contacts.firstOrNull { it.id == id } }
    if (expanded) Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(0.44f)) { ContactsDirectory(state, viewModel) }
        HorizontalDivider(Modifier.width(1.dp).fillMaxSize())
        Box(Modifier.weight(0.56f)) { ContactDetail(selected, state.groups, viewModel) }
    } else if (selected != null) ContactDetail(selected, state.groups, viewModel) else ContactsDirectory(state, viewModel)
}

@Composable
private fun ContactsDirectory(state: ContactsUiState, viewModel: ContactsViewModel) {
    Column(Modifier.fillMaxSize()) {
        if (state.contacts.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                EmptyDirectory(
                    title = stringResource(if (state.query.isBlank()) R.string.contacts_empty_title else R.string.contacts_search_empty_title),
                    body = if (state.query.isBlank()) {
                        stringResource(R.string.contacts_empty_body)
                    } else {
                        stringResource(R.string.contacts_search_empty_body)
                    },
                )
            }
        } else {
            val locale = LocalConfiguration.current.locales[0]
            val index = remember(state.contacts, locale) {
                DirectorySectionIndex.create(state.contacts, locale, CanonicalContact::resolvedDisplayName)
            }
            IndexedDirectory(
                index = index,
                key = CanonicalContact::id,
                initialIndex = state.navigation.current.scrollIndex,
                initialOffset = state.navigation.current.scrollOffset,
                reselectionRevision = state.navigation.current.reselectionRevision,
                onScrollSettled = viewModel::updateScroll,
                modifier = Modifier.weight(1f),
            ) { contact ->
                ContactRow(
                    contact = contact,
                    onOpen = { viewModel.selectContact(contact) },
                )
                HorizontalDivider(Modifier.padding(start = 76.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun GroupsDestination(state: ContactsUiState, viewModel: ContactsViewModel, expanded: Boolean) {
    val selected = state.navigation.current.selectedId?.let { id -> state.groups.firstOrNull { it.id == id } }
    if (expanded) Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(0.44f)) { GroupsDirectory(state, viewModel) }
        HorizontalDivider(Modifier.width(1.dp).fillMaxSize())
        Box(Modifier.weight(0.56f)) { GroupDetail(selected, state, viewModel) }
    } else if (selected != null) GroupDetail(selected, state, viewModel) else GroupsDirectory(state, viewModel)
}

@Composable
private fun GroupsDirectory(state: ContactsUiState, viewModel: ContactsViewModel) {
    Column(Modifier.fillMaxSize()) {
        if (state.deniedGroupOperations.isNotEmpty()) Text(stringResource(R.string.error_group_operation_unavailable), Modifier.padding(16.dp))
        if (state.groups.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                EmptyDirectory(
                    title = stringResource(if (state.query.isBlank()) R.string.groups_empty_title else R.string.groups_search_empty_title),
                    body = stringResource(if (state.query.isBlank()) R.string.groups_empty_body else R.string.contacts_search_empty_body),
                )
            }
        } else {
            val locale = LocalConfiguration.current.locales[0]
            val index = remember(state.groups, locale) {
                DirectorySectionIndex.create(state.groups, locale, ContactGroup::name)
            }
            IndexedDirectory(
                index = index,
                key = ContactGroup::id,
                initialIndex = state.navigation.current.scrollIndex,
                initialOffset = state.navigation.current.scrollOffset,
                reselectionRevision = state.navigation.current.reselectionRevision,
                onScrollSettled = viewModel::updateScroll,
                modifier = Modifier.weight(1f),
            ) { group ->
                GroupRow(group = group, onOpen = { viewModel.selectGroup(group) })
                HorizontalDivider(Modifier.padding(start = 52.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun DirectorySearch(
    query: String,
    onClose: (() -> Unit)? = null,
    requestFocus: Boolean = false,
    onFocusRequested: () -> Unit = {},
    onQueryChange: (String) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(requestFocus) {
        if (requestFocus) {
            focusRequester.requestFocus()
            keyboard?.show()
            onFocusRequested()
        }
    }
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(stringResource(R.string.directory_search)) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty() || onClose != null) {
                IconButton(onClick = { onClose?.invoke() ?: onQueryChange(query.take(0)) }) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.directory_search_clear),
                    )
                }
            }
        },
        shape = RoundedCornerShape(24.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = Color.Transparent,
        ),
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus(); keyboard?.hide() }),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .focusRequester(focusRequester),
    )
}

@Composable
private fun ContactRow(
    contact: CanonicalContact,
    onOpen: () -> Unit,
    membershipEmails: List<String>? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ContactImageBadge(contact, badgeSize = 60.dp, avatarSize = 56.dp, logoSize = 26.dp)
        Column(Modifier.weight(1f).padding(start = 8.dp, top = 6.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(contact.resolvedDisplayName, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val secondary = CanonicalPrimaryValuePolicy.select(contact, ContactValueKind.EMAIL)?.value
                ?: CanonicalPrimaryValuePolicy.select(contact, ContactValueKind.PHONE)?.value
                ?: CanonicalPrimaryValuePolicy.select(contact, ContactValueKind.ORGANIZATION)?.value
            (membershipEmails ?: listOfNotNull(secondary)).forEach { value ->
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (membershipEmails == null) 1 else Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            contact.actionRequiredReasons.firstOrNull()?.let {
                Text(
                    stringResource(if (it == "INVALID_EMAIL") R.string.action_required_invalid_email else R.string.action_required_missing_name),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = if (membershipEmails == null) 1 else Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun ContactImageBadge(
    contact: CanonicalContact,
    modifier: Modifier = Modifier,
    badgeSize: Dp = 52.dp,
    avatarSize: Dp = 44.dp,
    logoSize: Dp = 20.dp,
) {
    val logo = CanonicalPrimaryValuePolicy.select(contact, ContactValueKind.LOGO)
    val photoImage = rememberContactImage(orderedContactImageSources(contact, ContactValueKind.PHOTO))
    val logoImage = rememberContactImage(orderedContactImageSources(contact, ContactValueKind.LOGO))
    Box(modifier.size(badgeSize), contentAlignment = Alignment.Center) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.size(avatarSize)) {
            Box(contentAlignment = Alignment.Center) {
                if (photoImage != null) {
                    Image(
                        bitmap = photoImage,
                        contentDescription = stringResource(R.string.field_photo),
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        contactAvatarInitials(contact.resolvedDisplayName),
                        style = if (avatarSize >= 80.dp) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        if (logo != null) {
            Surface(
                shape = RoundedCornerShape(logoSize / 3),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(2.dp, MaterialTheme.colorScheme.surface),
                modifier = Modifier.size(logoSize).align(Alignment.BottomEnd),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (logoImage != null) {
                        Image(
                            bitmap = logoImage,
                            contentDescription = stringResource(R.string.field_logo),
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize().padding(2.dp),
                        )
                    } else {
                        Text(stringResource(R.string.logo_badge), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupRow(group: ContactGroup, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.size(44.dp)) {
            Box(contentAlignment = Alignment.Center) { GroupColorDot(group.color, size = 22.dp) }
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(group.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                pluralStringResource(R.plurals.groups_member_count, group.memberCount, group.memberCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun orderedContactImageSources(
    contact: CanonicalContact,
    kind: ContactValueKind,
): List<String> {
    val family = contact.valuesOf(kind)
    val primary = CanonicalPrimaryValuePolicy.select(family)
    return (listOfNotNull(primary) + family.filterNot { it.id == primary?.id })
        .flatMap(::contactValueImageSources)
        .distinct()
}

private fun contactValueImageSources(value: ContactValue): List<String> = listOfNotNull(
    value.binaryReference?.takeIf(String::isNotBlank),
    value.value.takeIf(String::isNotBlank),
).distinct()

@Composable
private fun GroupColorDot(rawColor: String, size: Dp = 18.dp) {
    val color = parseContactGroupColor(rawColor, MaterialTheme.colorScheme.primaryContainer)
    Surface(
        modifier = Modifier.size(size),
        shape = CircleShape,
        color = color,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {}
}

@Composable
private fun EmptyDirectory(title: String, body: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(16.dp))
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SyncDashboard(state: ContactsUiState, viewModel: ContactsViewModel, onConfigureNotifications: () -> Unit) {
    val conflicts by viewModel.conflicts.collectAsState()
    val semanticColors = LocalContakoColors.current
    val syncing = state.syncActivity != SyncActivity.IDLE
    val statusColor = when (state.syncDashboard.state) {
        SyncDashboardState.CURRENT -> semanticColors.success
        SyncDashboardState.PENDING, SyncDashboardState.OFFLINE -> semanticColors.pending
        else -> MaterialTheme.colorScheme.error
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            InfoCard(stringResource(syncStateTitle(state.syncDashboard))) {
                Text(
                    stringResource(syncStateBody(state.syncDashboard)),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(color = statusColor.copy(alpha = 0.35f))
                Text(
                    pluralStringResource(
                        R.plurals.sync_pending_change_count,
                        state.syncDashboard.pendingMutationCount,
                        state.syncDashboard.pendingMutationCount,
                    ),
                    color = statusColor,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        if (state.syncDashboard.actionRequiredCount > 0) {
            item {
                Card(
                    Modifier.fillMaxWidth().clickable {
                        viewModel.openSecondary(SecondaryDestination.ACTIONS)
                    },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Column(Modifier.weight(1f)) {
                            Text(
                                pluralStringResource(
                                    R.plurals.sync_action_required_count,
                                    state.syncDashboard.actionRequiredCount,
                                    state.syncDashboard.actionRequiredCount,
                                ),
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                stringResource(syncProblemBody(state.syncDashboard)),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(
                            stringResource(R.string.navigation_chevron),
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.clearAndSetSemantics {},
                        )
                    }
                }
            }
        }

        if (conflicts.isNotEmpty()) {
            item { Text(stringResource(R.string.conflict_title), style = MaterialTheme.typography.titleMedium) }
            items(conflicts, key = { "conflict:${it.contactId}" }) { conflict ->
                Card(Modifier.fillMaxWidth().clickable { viewModel.openConflict(conflict.contactId) }) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(state.actionContacts.firstOrNull { it.id == conflict.contactId }?.resolvedDisplayName
                            ?: stringResource(R.string.contact_detail_title), fontWeight = FontWeight.SemiBold)
                        Text(stringResource(if (conflict.choice == null) R.string.conflict_review else R.string.conflict_queued))
                    }
                }
            }
        }
        val blockedContacts = state.actionContacts.filter { it.id in state.syncDashboard.blockedMutationContactIds &&
            conflicts.none { conflict -> conflict.contactId == it.id } }
        if (blockedContacts.isNotEmpty()) {
            item { Text(stringResource(R.string.sync_state_blocked), style = MaterialTheme.typography.titleMedium) }
            items(blockedContacts, key = { "blocked:${it.id}" }) { contact ->
                ContactRow(contact, onOpen = { viewModel.openContactFromGroup(contact) })
            }
        }
        val waitingContacts = state.pendingContacts.filterNot { it.id in state.syncDashboard.blockedMutationContactIds }
        if (waitingContacts.isNotEmpty()) {
            item { Text(stringResource(R.string.sync_pending_contacts), style = MaterialTheme.typography.titleMedium) }
            items(waitingContacts, key = { "pending:${it.id}" }) { contact ->
                ContactRow(contact, onOpen = { viewModel.openContactFromGroup(contact) })
            }
        }
        if (state.androidPendingContacts.isNotEmpty()) {
            item {
                Text(stringResource(R.string.sync_android_pending_contacts, state.androidPendingContacts.size),
                    style = MaterialTheme.typography.titleMedium)
            }
            items(state.androidPendingContacts, key = { "android:${it.id}" }) { contact ->
                ContactRow(contact, onOpen = { viewModel.openContactFromGroup(contact) })
            }
        }
        item {
            InfoCard(stringResource(R.string.settings_android)) {
                Text(
                    stringResource(
                        if (state.contactsPermissionGranted) {
                            R.string.status_android_compatible
                        } else {
                            R.string.sync_body_android_degraded
                        },
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!state.contactsPermissionGranted) {
                    TextButton(onClick = viewModel::requestContactsPermission) {
                        Text(stringResource(
                            if (state.contactsPermissionAction == ContactsPermissionAction.OPEN_SETTINGS) {
                                R.string.contacts_permission_open_settings
                            } else {
                                R.string.contacts_permission_action
                            },
                        ))
                    }
                }
            }
        }

        state.repairProgress?.let { progress ->
            item {
                InfoCard(stringResource(if (syncing) R.string.repair_in_progress else R.string.sync_state_waiting)) {
                    Text(stringResource(repairPhaseTitle(progress.phase)))
                    if (progress.totalUnits != null && progress.totalUnits > 0) {
                        LinearProgressIndicator(
                            progress = { progress.completedUnits.toFloat() / progress.totalUnits.toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    TextButton(onClick = viewModel::cancelRepair, enabled = !progress.cancellationRequested) {
                        Text(stringResource(if (progress.cancellationRequested) R.string.repair_cancelling else R.string.repair_cancel))
                    }
                    // A durable checkpoint can outlive its running pass (retry or process death).
                    // Resume through the serialized engine, which also finalizes a pending cancel.
                    if (!syncing) {
                        Button(onClick = viewModel::startRepair) {
                            Text(stringResource(if (progress.cancellationRequested) R.string.repair_cancel else R.string.repair_confirm_action))
                        }
                    }
                }
            }
        } ?: item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = viewModel::syncNow,
                    enabled = !syncing,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.sync_now))
                }
                TextButton(
                    onClick = viewModel::startRepair,
                    enabled = !syncing,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.repair_start))
                }
            }
        }
        item {
            TextButton(onClick = onConfigureNotifications) { Text(stringResource(R.string.sync_alert_configure)) }
        }
        item(key = "local-diagnostics") { DiagnosticSettings(state) }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@StringRes
private fun syncStateTitle(snapshot: com.patmanak.contako.domain.sync.SyncDashboardSnapshot): Int = when (snapshot.state) {
    SyncDashboardState.CURRENT -> R.string.sync_state_current
    SyncDashboardState.PENDING -> if (snapshot.pendingMutationCount == 0) R.string.sync_state_waiting else R.string.sync_state_pending
    SyncDashboardState.BLOCKED -> R.string.sync_state_blocked
    SyncDashboardState.FAILED -> R.string.sync_state_failed
    SyncDashboardState.OFFLINE -> R.string.sync_state_offline
    SyncDashboardState.AUTHENTICATION_REQUIRED -> R.string.sync_state_auth
    SyncDashboardState.ANDROID_DEGRADED -> R.string.sync_state_android_degraded
    SyncDashboardState.ANDROID_PARTIAL -> R.string.sync_state_android_partial
}

@StringRes
private fun syncProblemBody(snapshot: com.patmanak.contako.domain.sync.SyncDashboardSnapshot): Int =
    when (snapshot.problem) {
        com.patmanak.contako.domain.sync.SyncProblem.VALIDATION_REJECTED -> R.string.sync_problem_validation
        com.patmanak.contako.domain.sync.SyncProblem.CONFLICT_RECOVERY_REQUIRED -> R.string.sync_problem_conflict
        com.patmanak.contako.domain.sync.SyncProblem.CRYPTOGRAPHIC_VERIFICATION_FAILED -> R.string.sync_problem_crypto
        com.patmanak.contako.domain.sync.SyncProblem.GROUP_CAPABILITY_REQUIRED -> R.string.sync_problem_group
        com.patmanak.contako.domain.sync.SyncProblem.INTERNAL_FAILURE -> R.string.sync_problem_internal
        else -> syncStateBody(snapshot)
    }

@StringRes
private fun syncStateBody(snapshot: com.patmanak.contako.domain.sync.SyncDashboardSnapshot): Int = when (snapshot.state) {
    SyncDashboardState.CURRENT -> R.string.sync_body_current
    SyncDashboardState.PENDING -> if (snapshot.pendingMutationCount == 0) R.string.sync_body_waiting else R.string.sync_body_pending
    SyncDashboardState.BLOCKED -> R.string.sync_body_blocked
    SyncDashboardState.FAILED -> R.string.sync_body_failed
    SyncDashboardState.OFFLINE -> R.string.sync_body_offline
    SyncDashboardState.AUTHENTICATION_REQUIRED -> R.string.sync_body_auth
    SyncDashboardState.ANDROID_DEGRADED -> R.string.sync_body_android_degraded
    SyncDashboardState.ANDROID_PARTIAL -> R.string.sync_body_android_partial
}

@StringRes
private fun repairPhaseTitle(phase: RepairPhase): Int = when (phase) {
    RepairPhase.REMOTE_ENUMERATION -> R.string.repair_phase_remote
    RepairPhase.CANONICAL_RECONCILIATION -> R.string.repair_phase_canonical
    RepairPhase.ANDROID_PROJECTION -> R.string.repair_phase_android
    RepairPhase.PUBLISHING -> R.string.repair_phase_publishing
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactEditorScreen(editor: ContactEditorState, viewModel: ContactsViewModel) {
    if (editor.saving) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = {},
            properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        ) {
            Surface(shape = MaterialTheme.shapes.medium) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.action_save))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val editorListState = rememberLazyListState()
    var sectionMenuExpanded by remember { mutableStateOf(false) }
    val sectionTitles = listOf(R.string.contact_detail_title, R.string.contact_identity_gallery_section,
        R.string.contact_email_section, R.string.nav_groups) + editorSections.map { it.title }
    var expandedSections by rememberSaveable(editor.draftGeneration) {
        mutableStateOf(listOf(R.string.contact_email_section, R.string.section_communication) +
            editorSections.filter { section -> editor.values.any { it.kind in section.kinds } }.map { it.title } +
            listOfNotNull(
                R.string.nav_groups.takeIf { editor.selectedGroupAssignments.isNotEmpty() },
            ))
    }
    fun toggleSection(title: Int) {
        expandedSections = if (title in expandedSections) expandedSections - title else expandedSections + title
    }
    LaunchedEffect(editor.fieldErrors) {
        val invalidKinds = (editor.values + editor.images)
            .filter { it.id in editor.fieldErrors }.map { it.kind }.toSet()
        val titles = editorSections.filter { section -> section.kinds.any { it in invalidKinds } }.map { it.title } +
            listOfNotNull(R.string.contact_identity_gallery_section.takeIf {
                ContactValueKind.PHOTO in invalidKinds || ContactValueKind.LOGO in invalidKinds
            })
        expandedSections = (expandedSections + titles).distinct()
        val firstSection = when {
            ContactValueKind.NICKNAME in invalidKinds -> 0
            ContactValueKind.EMAIL in invalidKinds -> 2
            else -> titles.map { sectionTitles.indexOf(it) }.filter { it >= 0 }.minOrNull()
        }
        firstSection?.let { editorListState.animateScrollToItem(it) }
    }
    // A picker result from a destroyed process must never target a newly created
    // draft whose in-memory generation happens to match the old one.
    var imageSelectionKind by remember { mutableStateOf<String?>(null) }
    var imageSelectionId by remember { mutableStateOf<String?>(null) }
    var imageSelectionDraftGeneration by remember { mutableStateOf(-1L) }
    var imageReadGeneration by remember { mutableStateOf(0L) }
    var imageReadJob by remember { mutableStateOf<Job?>(null) }
    var readingImage by remember { mutableStateOf(false) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val targetKind = imageSelectionKind?.let { encodedKind ->
            runCatching { ContactValueKind.valueOf(encodedKind) }.getOrNull()
        }
        val targetImageId = imageSelectionId
        val targetDraftGeneration = imageSelectionDraftGeneration
        imageSelectionKind = null
        imageSelectionId = null
        imageSelectionDraftGeneration = -1L
        if (uri != null && targetKind != null) {
            val generation = imageReadGeneration
            imageReadJob?.cancel()
            readingImage = true
            imageReadJob = scope.launch {
                try {
                    val dataUri = withContext(Dispatchers.IO) {
                        runCatching { readSelectedContactImage(context.contentResolver, uri) }.getOrNull()
                    }
                    if (generation != imageReadGeneration) return@launch
                    if (dataUri == null) {
                        viewModel.reportImageSelectionFailure(targetDraftGeneration)
                    } else {
                        viewModel.applySelectedImage(
                            draftGeneration = targetDraftGeneration,
                            kind = targetKind,
                            imageId = targetImageId,
                            value = dataUri,
                        )
                    }
                } finally {
                    if (generation == imageReadGeneration) readingImage = false
                }
            }
        }
    }
    fun selectImage(kind: ContactValueKind, imageId: String? = null) {
        imageReadGeneration += 1
        imageReadJob?.cancel()
        readingImage = false
        imageSelectionKind = kind.name
        imageSelectionDraftGeneration = editor.draftGeneration
        imageSelectionId = imageId
        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    Surface(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                Column {
                    EditorAppBar(
                        title = stringResource(if (editor.original == null) R.string.contacts_new else R.string.contacts_edit),
                        onCancel = viewModel::dismissContactEditor,
                        onSave = viewModel::saveContact,
                        saveTag = CONTACT_EDITOR_TOP_SAVE_TAG,
                        saveEnabled = !editor.saving && !readingImage,
                    )
                    if (editor.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                    editor.validationError?.let { error ->
                        Surface(color = MaterialTheme.colorScheme.errorContainer) {
                            Text(uiMessage(error), Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
                                .testTag("contact-editor-save-error"), color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
            },
            bottomBar = {
                Surface(modifier = Modifier.navigationBarsPadding(), tonalElevation = 2.dp) {
                    Box(Modifier.fillMaxWidth()) {
                        TextButton(onClick = { sectionMenuExpanded = true },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.editor_add_field))
                        }
                    }
                }
            },
        ) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 12.dp)
                    .testTag("contact_editor_fields"),
                state = editorListState,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    EditorCard {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            val preview = (editor.original ?: CanonicalContact(
                                accountId = LOCAL_ACCOUNT_ID,
                                id = LOCAL_ACCOUNT_ID,
                            )).copy(
                                firstName = editor.firstName,
                                lastName = editor.lastName,
                                displayName = editor.displayName,
                                values = editor.values + editor.images,
                            )
                            val photoAction = stringResource(R.string.field_add_photo)
                            ContactImageBadge(preview, badgeSize = 80.dp, avatarSize = 72.dp, logoSize = 24.dp,
                                modifier = Modifier.clickable(role = Role.Button) { selectImage(ContactValueKind.PHOTO) }
                                    .semantics { contentDescription = photoAction })
                        }
                        EditorField(stringResource(R.string.field_first_name), editor.firstName) {
                            viewModel.updateContactEditor(editor.copy(firstName = it))
                        }
                        EditorField(stringResource(R.string.field_last_name), editor.lastName) {
                            viewModel.updateContactEditor(editor.copy(lastName = it))
                        }
                        EditorField(stringResource(R.string.field_display_name), editor.displayName) {
                            viewModel.updateContactEditor(editor.copy(displayName = it))
                        }
                        if (editor.values.any { it.kind == ContactValueKind.NICKNAME }) {
                            ContactValueFamily(editor, ContactValueKind.NICKNAME, viewModel)
                        }
                    }
                }
                item {
                    if (editor.images.isNotEmpty() || R.string.contact_identity_gallery_section in expandedSections) {
                        EditorCard(R.string.contact_identity_gallery_section,
                            expanded = R.string.contact_identity_gallery_section in expandedSections,
                            onToggle = { toggleSection(R.string.contact_identity_gallery_section) }) {
                            Text(stringResource(R.string.section_photos), style = MaterialTheme.typography.titleSmall)
                            editor.images.filter { it.kind == ContactValueKind.PHOTO }.forEach { image ->
                                ImageEditorRow(image, editor, viewModel) {
                                    selectImage(ContactValueKind.PHOTO, image.id)
                                }
                            }
                            TextButton(onClick = { selectImage(ContactValueKind.PHOTO) }) {
                                Text(stringResource(R.string.field_add_photo))
                            }
                            Text(stringResource(R.string.section_logos), style = MaterialTheme.typography.titleSmall)
                            editor.images.filter { it.kind == ContactValueKind.LOGO }.forEach { image ->
                                ImageEditorRow(image, editor, viewModel) {
                                    selectImage(ContactValueKind.LOGO, image.id)
                                }
                            }
                            TextButton(onClick = { selectImage(ContactValueKind.LOGO) }) {
                                Text(stringResource(R.string.field_add_logo))
                            }
                            if (editor.validationError == UiMessage.INVALID_IMAGE) {
                                Text(
                                    uiMessage(UiMessage.INVALID_IMAGE),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
                item {
                    EditorCard(R.string.contact_email_section) {
                        ContactValueFamily(editor, ContactValueKind.EMAIL, viewModel)
                        if (editor.values.any { it.kind == ContactValueKind.EMAIL && it.value.isNotBlank() }) {
                            TextButton(onClick = {
                                expandedSections = (expandedSections + R.string.nav_groups).distinct()
                                scope.launch { editorListState.animateScrollToItem(3) }
                            }) { Text(stringResource(R.string.editor_email_groups)) }
                        }
                    }
                }
                item {
                    if (editor.selectedGroupAssignments.isNotEmpty() || R.string.nav_groups in expandedSections) {
                        EditorCard(R.string.nav_groups,
                            expanded = R.string.nav_groups in expandedSections,
                            onToggle = { toggleSection(R.string.nav_groups) }) {
                            ContactGroupAssignmentEditor(editor, viewModel)
                        }
                    }
                }
                editorSections.forEach { section ->
                    item {
                        if (section.title == R.string.section_communication || editor.values.any { it.kind in section.kinds }) {
                            EditorCard(section.title, expanded = section.title in expandedSections,
                                onToggle = { toggleSection(section.title) }) {
                                section.kinds.filter { kind -> kind == ContactValueKind.PHONE ||
                                    editor.values.any { it.kind == kind } }
                                    .forEach { kind -> ContactValueFamily(editor, kind, viewModel) }
                            }
                        }
                    }
                }
                editor.validationError?.takeUnless { it == UiMessage.INVALID_IMAGE }?.let { error ->
                    item { Text(uiMessage(error), color = MaterialTheme.colorScheme.error) }
                }
                item {
                    Button(
                        onClick = viewModel::saveContact,
                        modifier = Modifier.fillMaxWidth().testTag(CONTACT_EDITOR_BOTTOM_SAVE_TAG),
                        enabled = !editor.saving && !readingImage,
                    ) {
                        Text(stringResource(R.string.action_save))
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
    if (sectionMenuExpanded) {
        AlertDialog(
            onDismissRequest = { sectionMenuExpanded = false },
            title = { Text(stringResource(R.string.editor_add_field)) },
            text = {
                LazyColumn(Modifier.testTag("contact_editor_field_picker")) {
                    item {
                        TextButton(onClick = {
                            sectionMenuExpanded = false
                            selectImage(ContactValueKind.PHOTO)
                            expandedSections = (expandedSections + R.string.contact_identity_gallery_section).distinct()
                        }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.field_photo)) }
                    }
                    item {
                        TextButton(onClick = {
                            sectionMenuExpanded = false
                            selectImage(ContactValueKind.LOGO)
                            expandedSections = (expandedSections + R.string.contact_identity_gallery_section).distinct()
                        }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.field_logo)) }
                    }
                    item {
                        TextButton(onClick = {
                            sectionMenuExpanded = false
                            expandedSections = (expandedSections + R.string.nav_groups).distinct()
                            scope.launch { editorListState.animateScrollToItem(3) }
                        }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.nav_groups)) }
                    }
                    val families = listOf(EditorSection(R.string.contact_detail_title, listOf(ContactValueKind.NICKNAME)),
                        EditorSection(R.string.contact_email_section, listOf(ContactValueKind.EMAIL))) + editorSections
                    families.forEach { section ->
                        val availableKinds = section.kinds.filter { kind ->
                            kind !in ContactsViewModel.SINGLETON_VALUE_KINDS || editor.values.none { it.kind == kind }
                        }
                        if (availableKinds.isNotEmpty()) {
                            item { Text(stringResource(section.title), style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(top = 12.dp)) }
                            items(availableKinds, key = { it.name }) { kind ->
                                TextButton(onClick = {
                                    viewModel.addContactValue(kind)
                                    sectionMenuExpanded = false
                                    expandedSections = (expandedSections + section.title).distinct()
                                    scope.launch { editorListState.animateScrollToItem(sectionTitles.indexOf(section.title)) }
                                }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(contactValueLabel(kind))) }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { sectionMenuExpanded = false }) {
                Text(stringResource(R.string.action_cancel))
            } },
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun EditorAppBar(title: String, onCancel: () -> Unit, onSave: () -> Unit, saveTag: String,
                         saveEnabled: Boolean = true) {
    val largeText = LocalDensity.current.fontScale >= 1.5f
    Column {
        TopAppBar(
            title = { Text(title, maxLines = if (largeText) 2 else 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_cancel))
                }
            },
            actions = {
                if (!largeText) {
                    TextButton(onClick = onSave, enabled = saveEnabled, modifier = Modifier.testTag(saveTag)) {
                        Text(stringResource(R.string.action_save))
                    }
                }
            },
        )
        if (largeText) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                FilledTonalButton(onClick = onSave, enabled = saveEnabled, modifier = Modifier.testTag(saveTag)) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }
}

@Composable
private fun EditorCard(
    @StringRes title: Int? = null,
    expanded: Boolean = true,
    onToggle: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            title?.let {
                if (onToggle != null) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(selected = expanded, role = Role.Button, onClick = onToggle),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(it), modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary)
                        Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null)
                    }
                } else {
                    Text(stringResource(it), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
            if (expanded) content()
        }
    }
}

private data class EditorSection(@param:StringRes val title: Int, val kinds: List<ContactValueKind>)

private val editorSections = listOf(
    EditorSection(R.string.section_communication, listOf(
        ContactValueKind.PHONE, ContactValueKind.URL,
    )),
    EditorSection(R.string.section_organization_relationships, listOf(
        ContactValueKind.ORGANIZATION, ContactValueKind.TITLE, ContactValueKind.ROLE,
        ContactValueKind.RELATIONSHIP,
    )),
    EditorSection(R.string.contact_addresses_section, listOf(ContactValueKind.POSTAL_ADDRESS)),
    EditorSection(R.string.contact_dates_section, listOf(
        ContactValueKind.BIRTHDAY, ContactValueKind.ANNIVERSARY, ContactValueKind.CUSTOM_DATE,
    )),
    EditorSection(R.string.contact_notes_section, listOf(ContactValueKind.NOTE)),
    EditorSection(R.string.section_advanced, listOf(
        ContactValueKind.PHONETIC_NAME, ContactValueKind.LANGUAGE, ContactValueKind.TIME_ZONE,
        ContactValueKind.GENDER, ContactValueKind.MEMBER,
        ContactValueKind.PUBLIC_KEY,
    )),
)

@Composable
private fun ContactGroupAssignmentEditor(editor: ContactEditorState, viewModel: ContactsViewModel) {
    if (!editor.assignmentsEnabled) Text(stringResource(R.string.error_group_operation_unavailable))
    Text(
        stringResource(R.string.help_group_mapping),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val emails = editor.values.filter {
        it.kind == ContactValueKind.EMAIL && it.value.isNotBlank()
    }.sortedBy(ContactValue::order)
    when {
        emails.isEmpty() -> Text(
            stringResource(R.string.groups_membership_empty),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        editor.groupOptions.isEmpty() -> Text(
            stringResource(R.string.groups_empty_title),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> emails.forEach { email ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        email.value.ifBlank { stringResource(R.string.field_email) },
                        style = MaterialTheme.typography.labelLarge,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        editor.groupOptions.forEach { group ->
                            val selected = ContactGroupAssignment(group.id, email.id) in editor.selectedGroupAssignments
                            FilterChip(
                                selected = selected,
                                onClick = { viewModel.toggleContactGroupAssignment(group.id, email.id) },
                                enabled = editor.assignmentsEnabled && !editor.saving,
                                label = { Text(group.name) },
                                leadingIcon = {
                                    Surface(
                                        shape = CircleShape,
                                        color = parseContactGroupColor(group.color, MaterialTheme.colorScheme.primaryContainer),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                        modifier = Modifier.size(12.dp),
                                    ) {}
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactValueFamily(
    editor: ContactEditorState,
    kind: ContactValueKind,
    viewModel: ContactsViewModel,
) {
    val rows = editor.values.filter { it.kind == kind }.sortedBy(ContactValue::order)
    val preferredId = CanonicalPrimaryValuePolicy.select(rows)?.id
    Column(Modifier.fillMaxWidth()) {
        if (kind == ContactValueKind.CUSTOM_DATE) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(contactValueLabel(kind)), style = MaterialTheme.typography.labelLarge)
            if (kind == ContactValueKind.CUSTOM_DATE) {
                val localOnlyDescription = stringResource(R.string.field_custom_date_local_only)
                Text(
                    stringResource(R.string.field_local_only_badge),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.semantics {
                        contentDescription = localOnlyDescription
                    },
                )
            }
        }
        rows.forEach { value ->
            var optionsExpanded by rememberSaveable(value.id) { mutableStateOf(false) }
            val position = rows.indexOfFirst { it.id == value.id }
            val familyLabel = stringResource(contactValueLabel(kind))
            val occurrenceLabel = if (rows.size > 1) {
                stringResource(R.string.field_value_occurrence, familyLabel, position + 1, rows.size)
            } else {
                familyLabel
            }
            val moveUpDescription = stringResource(R.string.field_move_up, occurrenceLabel)
            val moveDownDescription = stringResource(R.string.field_move_down, occurrenceLabel)
            Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                if (rows.size > 1 && value.id == preferredId) {
                    PrimaryValueBadge()
                }
                if (kind == ContactValueKind.POSTAL_ADDRESS) {
                    PostalAddressPolicy.componentKeys.forEach { component ->
                        OutlinedTextField(
                            value = value.components[component].orEmpty(),
                            onValueChange = {
                                viewModel.updatePostalAddressComponent(value.id, component, it)
                            },
                            label = { Text(stringResource(postalAddressComponentLabel(component))) },
                            singleLine = true,
                            isError = value.id in editor.fieldErrors,
                            supportingText = if (component == PostalAddressPolicy.STREET) {
                                editor.fieldErrors[value.id]?.let { error -> ({ Text(uiMessage(error)) }) }
                            } else {
                                null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else if (kind in setOf(ContactValueKind.BIRTHDAY, ContactValueKind.ANNIVERSARY, ContactValueKind.CUSTOM_DATE)) {
                    androidx.compose.runtime.key(value.id) {
                        ContactDateField(
                            value = value.value,
                            label = familyLabel,
                            error = editor.fieldErrors[value.id]?.let { uiMessage(it) },
                            onValueChange = { viewModel.updateContactValue(value.id, it) },
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = value.value,
                        onValueChange = { viewModel.updateContactValue(value.id, it) },
                        label = { Text(stringResource(contactValueLabel(kind))) },
                        singleLine = kind != ContactValueKind.NOTE && kind != ContactValueKind.PUBLIC_KEY,
                        keyboardOptions = contactKeyboardOptions(kind),
                        isError = value.id in editor.fieldErrors,
                        supportingText = editor.fieldErrors[value.id]?.let { error -> ({ Text(uiMessage(error)) }) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (optionsExpanded) {
                    ContactValueTypeEditor(value) { viewModel.updateContactValueLabel(value.id, it) }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { optionsExpanded = !optionsExpanded },
                        modifier = Modifier.weight(1f).testTag("contact_value_options_${value.id}")) {
                        Text(value.label?.takeIf(String::isNotBlank) ?: stringResource(R.string.field_options),
                            modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
                        Icon(if (optionsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null)
                    }
                    IconButton(onClick = { viewModel.deleteContactValue(value.id) }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.field_delete, occurrenceLabel))
                    }
                }
                if (rows.size > 1 && optionsExpanded) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        IconButton(onClick = { viewModel.preferContactValue(value.id) }) {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = if (value.id == preferredId) {
                                    stringResource(R.string.field_preferred, occurrenceLabel)
                                } else {
                                    stringResource(R.string.field_make_preferred, occurrenceLabel)
                                },
                                tint = if (value.id == preferredId) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        IconButton(onClick = { viewModel.moveContactValue(value.id, -1) }, enabled = position > 0) {
                            Icon(
                                Icons.Default.KeyboardArrowUp,
                                contentDescription = moveUpDescription,
                            )
                        }
                        IconButton(onClick = { viewModel.moveContactValue(value.id, 1) }, enabled = position < rows.lastIndex) {
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = moveDownDescription,
                            )
                        }
                    }
                }
                if (position < rows.lastIndex) {
                    HorizontalDivider(Modifier.padding(top = 8.dp))
                }
            }
        }
        if (kind !in ContactsViewModel.SINGLETON_VALUE_KINDS || rows.isEmpty()) {
            TextButton(onClick = { viewModel.addContactValue(kind) }) {
                Text(stringResource(R.string.field_add, stringResource(contactValueLabel(kind))))
            }
        }
    }
}

private fun contactKeyboardOptions(kind: ContactValueKind): KeyboardOptions = KeyboardOptions(
    keyboardType = when (kind) {
        ContactValueKind.EMAIL -> KeyboardType.Email
        ContactValueKind.PHONE -> KeyboardType.Phone
        ContactValueKind.URL -> KeyboardType.Uri
        else -> KeyboardType.Text
    },
)

@StringRes
private fun postalAddressComponentLabel(component: String): Int = when (component) {
    PostalAddressPolicy.PO_BOX -> R.string.field_address_po_box
    PostalAddressPolicy.EXTENDED -> R.string.field_address_extended
    PostalAddressPolicy.STREET -> R.string.field_address_street
    PostalAddressPolicy.LOCALITY -> R.string.field_address_locality
    PostalAddressPolicy.REGION -> R.string.field_address_region
    PostalAddressPolicy.POSTAL_CODE -> R.string.field_address_postal_code
    PostalAddressPolicy.COUNTRY -> R.string.field_address_country
    else -> error("Unsupported postal address component")
}

@Composable
private fun ContactValueTypeEditor(value: ContactValue, onLabelChange: (String) -> Unit) {
    if (value.kind !in setOf(ContactValueKind.EMAIL, ContactValueKind.PHONE, ContactValueKind.POSTAL_ADDRESS)) {
        OutlinedTextField(
            value = value.label.orEmpty(), onValueChange = onLabelChange,
            label = { Text(stringResource(R.string.field_custom_label)) },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        return
    }
    val custom = "__custom__"
    var editingCustom by remember(value.id) { mutableStateOf(false) }
    val options = buildList {
        add("" to stringResource(R.string.contact_type_unspecified))
        add("home" to stringResource(R.string.contact_type_home))
        add("work" to stringResource(R.string.contact_type_work))
        if (value.kind == ContactValueKind.PHONE) add("cell" to stringResource(R.string.contact_type_mobile))
        add("other" to stringResource(R.string.contact_type_other))
        add(custom to stringResource(R.string.contact_type_custom))
    }
    val current = value.label.orEmpty().lowercase(java.util.Locale.ROOT)
        .let { if (it == "mobile" && value.kind == ContactValueKind.PHONE) "cell" else it }
    val selected = if (editingCustom || options.none { it.first == current }) custom else current
    SettingsDropdown(
        label = stringResource(R.string.field_custom_label),
        selectedLabel = options.first { it.first == selected }.second,
        options = options,
        onSelect = { type ->
            editingCustom = type == custom
            // Unknown labels remain verbatim until the owner actually changes their text.
            if (!editingCustom) onLabelChange(type)
        },
    )
    if (selected == custom) {
        OutlinedTextField(
            value = value.label.orEmpty(), onValueChange = onLabelChange,
            label = { Text(stringResource(R.string.contact_type_custom)) },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
    }
}

internal fun contactValueLabel(kind: ContactValueKind): Int = when (kind) {
    ContactValueKind.EMAIL -> R.string.field_email
    ContactValueKind.PHONE -> R.string.field_phone
    ContactValueKind.URL -> R.string.field_website
    ContactValueKind.NICKNAME -> R.string.field_nickname
    ContactValueKind.ORGANIZATION -> R.string.field_organization
    ContactValueKind.TITLE -> R.string.field_job_title
    ContactValueKind.RELATIONSHIP -> R.string.field_relationship
    ContactValueKind.POSTAL_ADDRESS -> R.string.field_address
    ContactValueKind.BIRTHDAY -> R.string.field_birthday
    ContactValueKind.ANNIVERSARY -> R.string.field_anniversary
    ContactValueKind.CUSTOM_DATE -> R.string.field_custom_date
    ContactValueKind.NOTE -> R.string.field_note
    ContactValueKind.LANGUAGE -> R.string.field_language
    ContactValueKind.TIME_ZONE -> R.string.field_time_zone
    ContactValueKind.GENDER -> R.string.field_gender
    ContactValueKind.PUBLIC_KEY -> R.string.field_public_key
    ContactValueKind.MEMBER -> R.string.field_member
    ContactValueKind.CATEGORY -> R.string.field_category
    ContactValueKind.ROLE -> R.string.field_role
    ContactValueKind.PHONETIC_NAME -> R.string.field_phonetic_name
    else -> error("Unsupported editable contact kind: $kind")
}

@Composable
private fun ImageEditorRow(
    image: ContactValue,
    editor: ContactEditorState,
    viewModel: ContactsViewModel,
    onChooseImage: () -> Unit,
) {
    val familyName = stringResource(
        if (image.kind == ContactValueKind.PHOTO) R.string.field_photo else R.string.field_logo,
    )
    val family = editor.images.filter { it.kind == image.kind }.sortedBy(ContactValue::order)
    val isPrimary = image.id == CanonicalPrimaryValuePolicy.select(family)?.id
    val position = family.indexOfFirst { it.id == image.id }
    val occurrenceLabel = if (family.size > 1) {
        stringResource(R.string.field_value_occurrence, familyName, position + 1, family.size)
    } else {
        familyName
    }
    val moveUpDescription = stringResource(R.string.field_move_up, occurrenceLabel)
    val moveDownDescription = stringResource(R.string.field_move_down, occurrenceLabel)
    val preview = rememberContactImage(contactValueImageSources(image))
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(64.dp),
            ) {
                if (preview != null) {
                    Image(
                        bitmap = preview,
                        contentDescription = occurrenceLabel,
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize().padding(4.dp),
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Person, contentDescription = null)
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(occurrenceLabel, style = MaterialTheme.typography.labelLarge)
                if (isPrimary) PrimaryValueBadge()
            }
            TextButton(onClick = onChooseImage) {
                Text(stringResource(R.string.action_replace))
            }
        }
        editor.fieldErrors[image.id]?.let { error ->
            Text(uiMessage(error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { viewModel.preferImage(image.id) }) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = if (isPrimary) {
                        stringResource(R.string.field_preferred, occurrenceLabel)
                    } else {
                        stringResource(R.string.field_make_preferred, occurrenceLabel)
                    },
                )
            }
            IconButton(
                onClick = { viewModel.moveImage(image.id, -1) },
                enabled = position > 0,
            ) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = moveUpDescription)
            }
            IconButton(
                onClick = { viewModel.moveImage(image.id, 1) },
                enabled = position in 0 until family.lastIndex,
            ) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = moveDownDescription)
            }
            IconButton(onClick = { viewModel.deleteImage(image.id) }) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.field_delete, occurrenceLabel))
            }
        }
        if (position < family.lastIndex) HorizontalDivider(Modifier.padding(top = 6.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupEditorScreen(editor: GroupEditorState, viewModel: ContactsViewModel) {
    var memberQuery by rememberSaveable(editor.original?.id) { mutableStateOf("") }
    val visibleEmailOptions = remember(editor.emailOptions, memberQuery) {
        val query = memberQuery.trim()
        editor.emailOptions.filter {
            query.isEmpty() || it.contactName.contains(query, ignoreCase = true) ||
                it.email.contains(query, ignoreCase = true)
        }
    }
    Surface(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                EditorAppBar(
                    title = stringResource(if (editor.original == null) R.string.groups_new else R.string.groups_edit),
                    onCancel = viewModel::dismissGroupEditor,
                    onSave = viewModel::saveGroup,
                    saveTag = GROUP_EDITOR_TOP_SAVE_TAG,
                    saveEnabled = !editor.saving,
                )
            },
        ) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    EditorCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = CircleShape,
                                color = parseContactGroupColor(editor.color, MaterialTheme.colorScheme.primaryContainer),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                modifier = Modifier.size(40.dp),
                            ) {}
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                EditorField(stringResource(R.string.groups_name), editor.name, enabled = editor.detailsEnabled && !editor.saving) {
                                    viewModel.updateGroupEditor(editor.copy(name = it))
                                }
                                editor.validationError?.let {
                                    Text(uiMessage(it), color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                        Text(stringResource(R.string.groups_color), style = MaterialTheme.typography.titleSmall)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            EditorPresentationPolicy.groupColorPalette.forEachIndexed { index, color ->
                                val selected = editor.color.equals(color, ignoreCase = true)
                                val colorDescription = stringResource(
                                    R.string.groups_color_choice,
                                    index + 1,
                                    EditorPresentationPolicy.groupColorPalette.size,
                                )
                                Surface(
                                    shape = CircleShape,
                                    color = parseContactGroupColor(color, MaterialTheme.colorScheme.primaryContainer),
                                    border = BorderStroke(
                                        if (selected) 3.dp else 1.dp,
                                        if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                                    ),
                                    modifier = Modifier
                                        .size(48.dp)
                                        .selectable(
                                            selected = selected,
                                            role = Role.RadioButton,
                                            onClick = { viewModel.updateGroupEditor(editor.copy(color = color)) },
                                            enabled = editor.detailsEnabled && !editor.saving,
                                        )
                                        .semantics {
                                            contentDescription = colorDescription
                                        },
                                ) {}
                            }
                        }
                    }
                }
                item {
                    EditorCard(R.string.groups_members) {
                        Text(
                            stringResource(R.string.groups_membership_help),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (editor.emailOptions.isEmpty()) {
                            Text(
                                stringResource(R.string.groups_membership_empty),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            DirectorySearch(memberQuery) { memberQuery = it }
                            if (visibleEmailOptions.isEmpty()) {
                                Text(
                                    stringResource(R.string.contacts_search_empty_title),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                items(
                    items = visibleEmailOptions,
                    key = EditorPresentationPolicy::membershipKey,
                ) { option ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = editor.membershipsEnabled && !editor.saving) { viewModel.toggleGroupMembership(option) },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                enabled = editor.membershipsEnabled && !editor.saving,
                                checked = option.membership in editor.selectedMemberships,
                                onCheckedChange = { viewModel.toggleGroupMembership(option) },
                            )
                            Column {
                                Text(option.contactName, fontWeight = FontWeight.Medium)
                                Text(
                                    option.email,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                if (editor.contactsWithoutEmailCount > 0) {
                    item {
                        Text(
                            pluralStringResource(R.plurals.groups_contacts_without_email, editor.contactsWithoutEmailCount, editor.contactsWithoutEmailCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item {
                    Button(
                        onClick = viewModel::saveGroup,
                        modifier = Modifier.fillMaxWidth().testTag(GROUP_EDITOR_BOTTOM_SAVE_TAG),
                        enabled = !editor.saving,
                    ) {
                        Text(stringResource(R.string.action_save))
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
    }

@Composable
private fun EditorField(label: String, value: String, enabled: Boolean = true, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        enabled = enabled,
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun NamedDeleteDialog(name: String, status: DeletionStatus, onDismiss: () -> Unit, onConfirm: () -> Unit) =
    DeletionDialog(name, stringResource(R.string.dialog_delete_contact_body),
        stringResource(R.string.action_delete), status, onDismiss, onConfirm)

@Composable
private fun GroupDeleteDialog(group: ContactGroup, status: DeletionStatus, onDismiss: () -> Unit, onConfirm: () -> Unit) =
    DeletionDialog(group.name,
        pluralStringResource(R.plurals.dialog_delete_group_body, group.memberCount, group.memberCount),
        stringResource(R.string.action_delete_group), status, onDismiss, onConfirm)

@Composable
private fun DeletionDialog(
    name: String, body: String, confirmLabel: String, status: DeletionStatus,
    onDismiss: () -> Unit, onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!status.inProgress) onDismiss() },
        title = { Text(stringResource(R.string.dialog_delete_title, name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(body)
                if (status.inProgress) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (status.failed) Text(stringResource(R.string.error_delete_failed),
                    color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(enabled = !status.inProgress, onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(enabled = !status.inProgress, onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private val RootDestination.labelResource: Int
    get() = when (this) {
        RootDestination.CONTACTS -> R.string.nav_contacts
        RootDestination.GROUPS -> R.string.nav_groups
        RootDestination.SYNC -> R.string.nav_sync
    }

private val SecondaryDestination.labelResource: Int
    get() = when (this) {
        SecondaryDestination.SETTINGS -> R.string.nav_settings
        SecondaryDestination.ACTIONS -> R.string.nav_actions
        SecondaryDestination.ABOUT -> R.string.nav_about
        SecondaryDestination.USAGE -> R.string.nav_usage
    }

@Composable
private fun RailDestinationItem(destination: RootDestination, icon: ImageVector, state: ContactsUiState, viewModel: ContactsViewModel) {
    NavigationRailItem(
        selected = state.navigation.destination == destination,
        onClick = { viewModel.selectDestination(destination) },
        icon = { DestinationIcon(destination, icon, state) },
        label = { Text(stringResource(destination.labelResource)) },
    )
}

@Composable
private fun DestinationIcon(destination: RootDestination, icon: ImageVector, state: ContactsUiState) {
    val actionRequiredCount = state.syncDashboard.actionRequiredCount
    if (destination == RootDestination.SYNC && actionRequiredCount > 0) {
        val actionDescription = pluralStringResource(
            R.plurals.sync_action_required_count,
            actionRequiredCount,
            actionRequiredCount,
        )
        BadgedBox(
            modifier = Modifier.semantics { contentDescription = actionDescription },
            badge = {
                Badge(Modifier.clearAndSetSemantics { }) {
                    Text(actionRequiredBadgeText(actionRequiredCount))
                }
            },
        ) {
            Icon(icon, contentDescription = null)
        }
    } else {
        Icon(icon, contentDescription = null)
    }
}

private val ACCOUNT_MENU_DESTINATIONS = listOf(
    SecondaryDestination.SETTINGS,
    SecondaryDestination.ABOUT,
    SecondaryDestination.USAGE,
)

internal const val CONTACT_EDITOR_TOP_SAVE_TAG = "contact_editor_top_save"
internal const val CONTACT_EDITOR_BOTTOM_SAVE_TAG = "contact_editor_bottom_save"
internal const val GROUP_EDITOR_TOP_SAVE_TAG = "group_editor_top_save"
internal const val GROUP_EDITOR_BOTTOM_SAVE_TAG = "group_editor_bottom_save"

@Composable
private fun ContactDetail(contact: CanonicalContact?, groups: List<ContactGroup>, viewModel: ContactsViewModel) {
    if (contact == null) return EmptyDirectory(stringResource(R.string.contact_detail_title), stringResource(R.string.contact_detail_empty))
    val context = LocalContext.current
    var galleryValue by remember(contact.id) { mutableStateOf<ContactValue?>(null) }
    var quickActionPicker by remember(contact.id) { mutableStateOf<ContactQuickAction?>(null) }
    val photos = contact.valuesOf(ContactValueKind.PHOTO)
    val galleryImages = photos + contact.valuesOf(ContactValueKind.LOGO)
    val singleImage = galleryImages.singleOrNull()
    val singleImageLabel = singleImage?.let {
        stringResource(if (it.kind == ContactValueKind.PHOTO) R.string.field_photo else R.string.field_logo)
    }
    val nickname = contact.valuesOf(ContactValueKind.NICKNAME).firstOrNull()?.value
    val organization = contact.valuesOf(ContactValueKind.ORGANIZATION).firstOrNull()?.value
    val quickActions = ContactQuickAction.entries.filter { contactQuickActionTargets(contact, it).isNotEmpty() }
    BackHandler(enabled = galleryValue != null) { galleryValue = null }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ContactImageBadge(
                    contact, badgeSize = 128.dp, avatarSize = 120.dp, logoSize = 36.dp,
                    modifier = if (singleImage != null) Modifier
                        .clickable(role = Role.Button) { galleryValue = singleImage }
                        .semantics { contentDescription = requireNotNull(singleImageLabel) }
                    else Modifier,
                )
                Text(
                    contact.resolvedDisplayName,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                val nameParts = listOf(
                    R.string.field_first_name to contact.firstName,
                    R.string.field_last_name to contact.lastName,
                ).filter { it.second.isNotBlank() }
                if (nameParts.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        nameParts.forEach { (label, value) ->
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(stringResource(label), style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(value, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
                if (!nickname.isNullOrBlank()) {
                    Text(
                        nickname,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!organization.isNullOrBlank()) {
                    Text(
                        organization,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (quickActions.isNotEmpty()) item {
            ContactQuickActions(
                actions = quickActions,
                onAction = { action ->
                    val targets = contactQuickActionTargets(contact, action)
                    if (targets.size == 1) {
                        context.launchContactQuickAction(action, targets.single().value)
                    } else {
                        quickActionPicker = action
                    }
                },
            )
        }
        if (galleryImages.size > 1) {
            item {
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        DetailHeading(R.string.contact_identity_gallery_section)
                        galleryImages.forEach { photo ->
                            val family = galleryImages.filter { it.kind == photo.kind }
                            val familyName = stringResource(if (photo.kind == ContactValueKind.PHOTO) R.string.field_photo else R.string.field_logo)
                            val label = photo.label?.takeIf(String::isNotBlank) ?: if (family.size > 1) {
                                stringResource(R.string.field_value_occurrence, familyName, family.indexOf(photo) + 1, family.size)
                            } else familyName
                            val thumbnail = rememberContactImage(contactValueImageSources(photo))
                            TextButton(
                                onClick = { galleryValue = photo },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Surface(Modifier.size(64.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                                        if (thumbnail != null) {
                                            Image(thumbnail, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp))
                                        } else Box(contentAlignment = Alignment.Center) {
                                            Icon(Icons.Default.Person, contentDescription = null)
                                        }
                                    }
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(label, textAlign = TextAlign.Start)
                                        if (family.size > 1 && photo.id == CanonicalPrimaryValuePolicy.select(family)?.id) PrimaryValueBadge()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        detailSections.forEach { section ->
            val values = section.kinds.flatMap { kind -> contact.valuesOf(kind).map { kind to it } }
            if (values.isNotEmpty()) {
                item {
                    ContactDetailCard(
                        title = section.title,
                        values = values,
                        groups = groups,
                        contactId = contact.id,
                    )
                }
            }
        }
        // The preservation envelope contains whole source cards, not extra fields.
        val preservedValues = contact.valuesOf(ContactValueKind.UNKNOWN_VCARD_PROPERTY)
        if (preservedValues.isNotEmpty()) {
            item {
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DetailHeading(R.string.contact_preserved_section)
                        Text(stringResource(R.string.contact_read_only_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        preservedValues.forEach { value ->
                            SelectionContainer {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(value.label?.takeIf(String::isNotBlank)
                                        ?: stringResource(R.string.contact_preserved_section),
                                        style = MaterialTheme.typography.labelLarge)
                                    Text(value.value, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (contact.pendingMutationRevision != null || contact.actionRequiredReasons.isNotEmpty() ||
            contact.conflictState != null) item {
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DetailHeading(R.string.contact_status_section)
                    if (contact.pendingMutationRevision != null) {
                        StatusLabel(Icons.Default.Refresh, stringResource(R.string.status_pending_local))
                    }
                    if (contact.conflictState != null) {
                        StatusLabel(Icons.Default.Edit, stringResource(R.string.sync_problem_conflict))
                    }
                    contact.actionRequiredReasons.forEach { reason ->
                        StatusLabel(Icons.Default.Edit, stringResource(actionReasonBody(reason)))
                    }
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
    quickActionPicker?.let { action ->
        ContactActionTargetDialog(
            action = action,
            targets = contactQuickActionTargets(contact, action),
            onDismiss = { quickActionPicker = null },
            onSelect = { value ->
                quickActionPicker = null
                context.launchContactQuickAction(action, value)
            },
        )
    }
    galleryValue?.let { image ->
        val preview = rememberContactImage(contactValueImageSources(image))
        val imageLabel = image.label?.takeIf(String::isNotBlank) ?: stringResource(
            if (image.kind == ContactValueKind.PHOTO) R.string.field_photo else R.string.field_logo,
        )
        Dialog(onDismissRequest = { galleryValue = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (preview != null) {
                    Image(
                        bitmap = preview,
                        contentDescription = imageLabel,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                } else {
                    // A remote URI is never fetched here, so the placeholder remains meaningful.
                    Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(120.dp))
                    Text(stringResource(R.string.gallery_preview_unavailable), style = MaterialTheme.typography.titleMedium)
                }
                Text(imageLabel, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { galleryValue = null }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.action_close))
                }
            }
        }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContactQuickActions(
    actions: List<ContactQuickAction>,
    onAction: (ContactQuickAction) -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val labels = actions.associateWith { stringResource(it.labelResource()) }
    val labelStyle = MaterialTheme.typography.labelMedium
    val minimumWidth = with(density) {
        (labels.values.maxOfOrNull { measurer.measure(AnnotatedString(it), labelStyle).size.width } ?: 0).toDp()
    } + 24.dp
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        val gap = 8.dp
        val columns = ((maxWidth + gap) / (maxOf(80.dp, minimumWidth) + gap)).toInt()
            .coerceIn(1, actions.size.coerceAtLeast(1))
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            actions.chunked(columns).forEach { rowActions ->
                // Row weights distribute whole pixels. Independently rounded fixed widths in
                // FlowRow could exceed the available width by one pixel and wrap the last tile.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    rowActions.forEach { action ->
                        val label = labels.getValue(action)
                        Surface(
                            onClick = { onAction(action) },
                            modifier = Modifier.weight(1f).heightIn(min = if (fontScale >= 1.5f) 128.dp else 88.dp),
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ) {
                            Column(
                                Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
                            ) {
                                Icon(action.icon(), contentDescription = null)
                                Text(
                                    label,
                                    modifier = Modifier.fillMaxWidth(),
                                    style = MaterialTheme.typography.labelMedium,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    repeat(columns - rowActions.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun ContactActionTargetDialog(
    action: ContactQuickAction,
    targets: List<ContactValue>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(action.labelResource())) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                items(targets, key = ContactValue::id) { target ->
                    TextButton(
                        onClick = { onSelect(target.value) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(
                                target.label ?: stringResource(contactValueLabel(target.kind)),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(target.value, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

private fun ContactQuickAction.labelResource(): Int = when (this) {
    ContactQuickAction.CALL -> R.string.contact_action_call
    ContactQuickAction.MESSAGE -> R.string.contact_action_message
    ContactQuickAction.EMAIL -> R.string.contact_action_email
    ContactQuickAction.MAP -> R.string.contact_action_map
}

private fun ContactQuickAction.icon(): ImageVector = when (this) {
    ContactQuickAction.CALL -> Icons.Default.Call
    ContactQuickAction.MESSAGE -> Icons.AutoMirrored.Filled.Send
    ContactQuickAction.EMAIL -> Icons.Default.Email
    ContactQuickAction.MAP -> Icons.Default.LocationOn
}

private fun android.content.Context.launchContactQuickAction(action: ContactQuickAction, value: String) {
    val intent = when (action) {
        ContactQuickAction.CALL -> Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", value, null))
        ContactQuickAction.MESSAGE -> Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", value, null))
        ContactQuickAction.EMAIL -> Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", value, null))
        ContactQuickAction.MAP -> Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(value)}"))
    }
    launchSafe(intent)
}

@Composable
private fun ContactDetailCard(
    @StringRes title: Int,
    values: List<Pair<ContactValueKind, ContactValue>>,
    groups: List<ContactGroup>,
    contactId: String,
) {
    val familySizes = values.groupingBy { it.first }.eachCount()
    val primaryIds = values.groupBy { it.first }.mapValues { (_, family) ->
        CanonicalPrimaryValuePolicy.select(family.map { it.second })?.id
    }
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text(
                stringResource(title),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            values.forEachIndexed { index, (kind, value) ->
                if (index > 0) {
                    HorizontalDivider(
                        Modifier.padding(start = 62.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                ContactDetailValueRow(
                    kind = kind,
                    value = value,
                    groups = groups,
                    contactId = contactId,
                    showPrimary = (familySizes[kind] ?: 0) > 1 && primaryIds[kind] == value.id,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContactDetailValueRow(
    kind: ContactValueKind,
    value: ContactValue,
    groups: List<ContactGroup>,
    contactId: String,
    showPrimary: Boolean,
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val displayValue = if (kind in setOf(ContactValueKind.BIRTHDAY, ContactValueKind.ANNIVERSARY, ContactValueKind.CUSTOM_DATE)) {
        remember(value.value, locale) {
            formatContactDate(value.value, locale, android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMMd"))
        }
    } else value.value
    val labelResource = when (value.label?.lowercase(java.util.Locale.ROOT)) {
        "home" -> R.string.contact_type_home
        "work" -> R.string.contact_type_work
        "cell", "mobile" -> R.string.contact_type_mobile
        "other" -> R.string.contact_type_other
        else -> null
    }
    val actionable = kind in setOf(
        ContactValueKind.EMAIL,
        ContactValueKind.PHONE,
        ContactValueKind.POSTAL_ADDRESS,
        ContactValueKind.URL,
    )
    val assignedGroups = if (kind == ContactValueKind.EMAIL) {
        groups.filter { group ->
            group.memberships.any { it.contactId == contactId && it.emailValueId == value.id }
        }
    } else {
        emptyList()
    }
    val rowModifier = if (actionable) {
        Modifier.clickable { context.launchContactValue(kind, value.value) }
    } else {
        Modifier
    }
    Row(
        rowModifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = detailIcon(kind),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    labelResource?.let { stringResource(it) } ?: value.label?.takeIf(String::isNotBlank) ?: stringResource(contactValueLabel(kind)),
                    modifier = Modifier.align(Alignment.CenterVertically),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (showPrimary) PrimaryValueBadge(Modifier.align(Alignment.CenterVertically))
            }
            Text(
                displayValue,
                style = MaterialTheme.typography.bodyLarge,
                color = if (actionable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            if (kind == ContactValueKind.EMAIL && assignedGroups.isNotEmpty()) {
                ContactGroupChips(assignedGroups)
            }
        }
    }
}

@Composable
private fun PrimaryValueBadge(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Text(
            stringResource(R.string.field_primary_badge),
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContactGroupChips(groups: List<ContactGroup>) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        groups.sortedWith(compareBy(ContactGroup::order, ContactGroup::name)).forEach { group ->
            val background = parseContactGroupColor(group.color, MaterialTheme.colorScheme.primaryContainer)
            val foreground = contrastingContactGroupContentColor(background)
            Surface(
                shape = CircleShape,
                color = background,
                contentColor = foreground,
                border = BorderStroke(1.dp, foreground.copy(alpha = 0.28f)),
            ) {
                Text(
                    group.name,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

internal fun parseContactGroupColor(raw: String, fallback: Color): Color {
    val digits = raw.takeIf { it.startsWith('#') }?.drop(1) ?: return fallback
    val parsed = digits.toLongOrNull(16) ?: return fallback
    val argb = when (digits.length) {
        6 -> parsed or 0xFF000000L
        8 -> parsed
        else -> return fallback
    }
    return Color(argb.toInt())
}

internal fun contrastingContactGroupContentColor(background: Color): Color {
    val dark = Color(0xFF191927)
    val light = Color.White
    return if (contrastRatio(background, dark) >= contrastRatio(background, light)) dark else light
}

private fun contrastRatio(first: Color, second: Color): Float {
    val lighter = maxOf(first.luminance(), second.luminance())
    val darker = minOf(first.luminance(), second.luminance())
    return (lighter + 0.05f) / (darker + 0.05f)
}

private fun detailIcon(kind: ContactValueKind): ImageVector = when (kind) {
    ContactValueKind.EMAIL -> Icons.Default.Email
    ContactValueKind.PHONE -> Icons.Default.Call
    ContactValueKind.POSTAL_ADDRESS -> Icons.Default.LocationOn
    ContactValueKind.NOTE -> Icons.Default.Edit
    ContactValueKind.BIRTHDAY,
    ContactValueKind.ANNIVERSARY,
    ContactValueKind.CUSTOM_DATE,
    -> Icons.Default.Star
    else -> Icons.Default.Person
}

private fun android.content.Context.launchContactValue(kind: ContactValueKind, value: String) {
    val intent = when (kind) {
        ContactValueKind.EMAIL -> Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", value, null))
        ContactValueKind.PHONE -> Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", value, null))
        ContactValueKind.POSTAL_ADDRESS -> Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(value)}"))
        ContactValueKind.URL -> safeHttpsContactUrl(value)?.let { safe ->
            Intent(Intent.ACTION_VIEW, Uri.parse(safe))
        } ?: return
        else -> return
    }
    launchSafe(intent)
}

/** Shared wordmark follows the app's selected theme, including manual overrides. */
@Composable
private fun BrandBanner() {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        ContakoBrand(
            modifier = Modifier.fillMaxWidth(0.8f).heightIn(max = 140.dp),
        )
    }
}

@Composable
private fun CreditLogoCard(title: String, @DrawableRes drawable: Int, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Image(
                painter = painterResource(drawable),
                // The adjacent title already names the credit, so the image is decorative.
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.height(48.dp),
            )
            Text(title, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun DetailHeading(@StringRes title: Int) {
    Text(stringResource(title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

private fun android.content.Context.launchSafe(intent: Intent) {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // The device has no compatible handler for this optional system action.
    } catch (_: SecurityException) {
        // A device policy or provider denied the optional system action.
    }
}

private val detailSections = listOf(
    EditorSection(R.string.contact_email_section, listOf(ContactValueKind.EMAIL)),
    EditorSection(R.string.section_communication, listOf(ContactValueKind.PHONE, ContactValueKind.URL)),
    EditorSection(R.string.section_organization_relationships, listOf(
        ContactValueKind.ORGANIZATION, ContactValueKind.TITLE, ContactValueKind.ROLE, ContactValueKind.RELATIONSHIP,
    )),
    EditorSection(R.string.contact_addresses_section, listOf(ContactValueKind.POSTAL_ADDRESS)),
    EditorSection(R.string.contact_dates_section, listOf(
        ContactValueKind.BIRTHDAY, ContactValueKind.ANNIVERSARY, ContactValueKind.CUSTOM_DATE,
    )),
    EditorSection(R.string.contact_notes_section, listOf(ContactValueKind.NOTE)),
    EditorSection(R.string.section_advanced, listOf(
        ContactValueKind.PHONETIC_NAME, ContactValueKind.LANGUAGE,
        ContactValueKind.TIME_ZONE, ContactValueKind.GENDER,
        ContactValueKind.MEMBER, ContactValueKind.PUBLIC_KEY,
    )),
)

@Composable
private fun GroupDetail(group: ContactGroup?, state: ContactsUiState, viewModel: ContactsViewModel) {
    if (group == null) return EmptyDirectory(stringResource(R.string.nav_groups), stringResource(R.string.group_detail_empty))
    val members = state.contacts.filter { contact -> group.memberships.any { it.contactId == contact.id } }
    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GroupColorDot(group.color, 24.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(group.name, style = MaterialTheme.typography.headlineSmall)
                        Text(pluralStringResource(R.plurals.groups_member_count, group.memberCount, group.memberCount))
                    }
                }
            }
        }
        item {
            Button(onClick = { viewModel.editGroup(group) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.groups_add_members))
            }
        }
        items(members, key = CanonicalContact::id) { member ->
            val memberEmailIds = group.memberships.filter { it.contactId == member.id }
                .mapTo(hashSetOf()) { it.emailValueId }
            ContactRow(
                contact = member,
                onOpen = { viewModel.openContactFromGroup(member) },
                membershipEmails = member.valuesOf(ContactValueKind.EMAIL)
                    .filter { it.id in memberEmailIds }
                    .map { it.value },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun SecondaryScreen(
    destination: SecondaryDestination,
    state: ContactsUiState,
    viewModel: ContactsViewModel,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    language: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
) {
    if (destination == SecondaryDestination.ABOUT || destination == SecondaryDestination.USAGE) {
        key(destination) { InformationScreen(usage = destination == SecondaryDestination.USAGE) }
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (destination) {
            SecondaryDestination.SETTINGS -> {
                item {
                    SettingsDropdown(
                        label = stringResource(R.string.settings_appearance),
                        selectedLabel = themeModeLabel(themeMode),
                        options = ThemeMode.entries.map { mode -> mode to themeModeLabel(mode) },
                        onSelect = onThemeModeChange,
                    )
                }
                item {
                    SettingsDropdown(
                        label = stringResource(R.string.settings_language),
                        selectedLabel = languageLabel(language),
                        options = AppLanguage.entries.map { option -> option to languageLabel(option) },
                        onSelect = onLanguageChange,
                    )
                }
            }
            SecondaryDestination.ACTIONS -> {
                val contacts = state.actionContacts
                if (state.syncDashboard.actionRequiredCount > 0) {
                    item {
                        InfoCard(stringResource(syncStateTitle(state.syncDashboard))) {
                            Text(stringResource(syncProblemBody(state.syncDashboard)))
                            Text(stringResource(R.string.sync_problem_scope), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { viewModel.selectDestination(RootDestination.SYNC) }) {
                                Text(stringResource(R.string.nav_sync))
                            }
                        }
                    }
                }
                if (contacts.isEmpty() && state.syncDashboard.actionRequiredCount == 0) {
                    item {
                        InfoCard(stringResource(R.string.actions_empty_title)) {
                            Text(
                                stringResource(R.string.actions_empty_body),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else items(contacts, key = CanonicalContact::id) { contact ->
                    Card(Modifier.fillMaxWidth().clickable { viewModel.editContact(contact) }) {
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            ContactImageBadge(contact)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(contact.resolvedDisplayName, fontWeight = FontWeight.SemiBold)
                                if (contact.conflictState != null) {
                                    Text(stringResource(R.string.sync_problem_conflict),
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                if (contact.id in state.syncDashboard.androidPendingContactIds) {
                                    Text(stringResource(R.string.sync_android_contact_pending),
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                if (contact.id in state.syncDashboard.blockedMutationContactIds) {
                                    Text(stringResource(R.string.sync_body_blocked),
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                contact.actionRequiredReasons.forEach { reason ->
                                    Text(
                                        stringResource(actionReasonBody(reason)),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Text(
                                stringResource(R.string.navigation_chevron),
                                style = MaterialTheme.typography.headlineSmall,
                                modifier = Modifier.clearAndSetSemantics {},
                            )
                        }
                    }
                }
            }
            SecondaryDestination.ABOUT, SecondaryDestination.USAGE -> Unit
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InformationScreen(usage: Boolean) {
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("information_content"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!usage) {
            item("brand") { BrandBanner() }
            item("project") {
                InfoCard(stringResource(R.string.about_title)) {
                    Text(stringResource(R.string.about_description))
                    Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = {
                        context.launchSafe(Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/patmanak/contako")))
                    }) { Text(stringResource(R.string.about_github)) }
                }
            }
            item("credits") {
                InfoCard(stringResource(R.string.about_credits)) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CreditLogoCard(
                            modifier = Modifier.widthIn(min = 140.dp).weight(1f),
                            title = stringResource(R.string.about_creator_title),
                            drawable = R.drawable.patmanak_logo,
                        )
                        CreditLogoCard(
                            modifier = Modifier.widthIn(min = 140.dp).weight(1f),
                            title = stringResource(R.string.about_proton_title),
                            drawable = R.drawable.proton_logo,
                        )
                    }
                    Text(stringResource(R.string.about_unofficial),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item("privacy") {
                InfoCard(stringResource(R.string.about_privacy)) {
                    Text(stringResource(R.string.about_privacy_summary))
                }
            }
            item("licenses") {
                InfoCard(stringResource(R.string.about_licenses)) {
                    Text(stringResource(R.string.about_license_summary))
                    TextButton(onClick = {
                        context.launchSafe(Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/patmanak/contako/blob/main/docs/LICENSING.md")))
                    }) { Text(stringResource(R.string.about_license_details)) }
                }
            }
        } else {
            listOf(
                R.string.about_features to R.string.about_features_summary,
                R.string.about_sync to R.string.about_sync_summary,
                R.string.about_limits to R.string.about_limits_summary,
            ).forEach { (title, body) ->
                item(title) {
                    InfoCard(stringResource(title)) { Text(stringResource(body)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> SettingsDropdown(
    label: String,
    selectedLabel: String,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
                .semantics {
                    contentDescription = "$label, $selectedLabel"
                    role = Role.Button
                    stateDescription = selectedLabel
                },
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, optionLabel) ->
                DropdownMenuItem(
                    text = { Text(optionLabel) },
                    onClick = {
                        expanded = false
                        onSelect(value)
                    },
                )
            }
        }
    }
}

@Composable
private fun themeModeLabel(mode: ThemeMode): String = stringResource(when (mode) {
    ThemeMode.SYSTEM -> R.string.settings_theme_system
    ThemeMode.LIGHT -> R.string.settings_theme_light
    ThemeMode.DARK -> R.string.settings_theme_dark
})

@Composable
private fun languageLabel(language: AppLanguage): String = when (language) {
    AppLanguage.SYSTEM -> "🌐"
    AppLanguage.ENGLISH -> "🇬🇧"
    AppLanguage.FRENCH -> "🇫🇷"
    AppLanguage.GERMAN -> "🇩🇪"
    AppLanguage.SPANISH -> "🇪🇸"
    AppLanguage.ITALIAN -> "🇮🇹"
    AppLanguage.DUTCH -> "🇳🇱"
    AppLanguage.POLISH -> "🇵🇱"
    AppLanguage.PORTUGUESE -> "🇵🇹"
} + "  " + stringResource(when (language) {
    AppLanguage.SYSTEM -> R.string.settings_language_system
    AppLanguage.ENGLISH -> R.string.language_english
    AppLanguage.FRENCH -> R.string.language_french
    AppLanguage.GERMAN -> R.string.language_german
    AppLanguage.SPANISH -> R.string.language_spanish
    AppLanguage.ITALIAN -> R.string.language_italian
    AppLanguage.DUTCH -> R.string.language_dutch
    AppLanguage.POLISH -> R.string.language_polish
    AppLanguage.PORTUGUESE -> R.string.language_portuguese
})

@StringRes
private fun actionReasonBody(reason: String): Int = when (reason) {
    "INVALID_EMAIL" -> R.string.actions_invalid_email_body
    "MISSING_NAME" -> R.string.actions_missing_name_body
    else -> R.string.actions_recovery_body
}

@Composable
private fun UnsavedDialog(viewModel: ContactsViewModel) {
    val largeText = LocalDensity.current.fontScale >= 1.5f
    AlertDialog(
        onDismissRequest = viewModel::keepEditing,
        title = { Text(stringResource(R.string.dialog_unsaved_title)) },
        text = { Text(stringResource(R.string.dialog_unsaved_body)) },
        confirmButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (largeText) {
                    TextButton(onClick = viewModel::keepEditing, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.dialog_keep_editing))
                    }
                }
                TextButton(
                    onClick = viewModel::discardEditor,
                    modifier = if (largeText) Modifier.fillMaxWidth() else Modifier,
                ) { Text(stringResource(R.string.dialog_discard_changes)) }
            }
        },
        dismissButton = if (largeText) null else {
            { TextButton(onClick = viewModel::keepEditing) { Text(stringResource(R.string.dialog_keep_editing)) } }
        },
    )
}

@Composable
private fun uiMessage(message: UiMessage): String = stringResource(when (message) {
    UiMessage.CORRECT_HIGHLIGHTED_FIELDS -> R.string.error_correct_highlighted
    UiMessage.ENTER_GROUP_NAME -> R.string.error_enter_group_name
    UiMessage.SAVE_REJECTED -> R.string.error_save_rejected
    UiMessage.SAVE_FAILED -> R.string.error_save_failed
    UiMessage.STALE_CONTACT_EDIT -> R.string.error_stale_contact_edit
    UiMessage.GROUP_OPERATION_UNAVAILABLE -> R.string.error_group_operation_unavailable
    UiMessage.INVALID_PUBLIC_KEY -> R.string.error_public_key
    UiMessage.INVALID_LANGUAGE -> R.string.error_language
    UiMessage.INVALID_TIME_ZONE -> R.string.error_time_zone
    UiMessage.INVALID_GENDER -> R.string.error_gender
    UiMessage.INVALID_IMAGE -> R.string.error_image
    UiMessage.INVALID_URL -> R.string.error_url
    UiMessage.INVALID_DATE -> R.string.error_date
    UiMessage.INVALID_VALUE -> R.string.error_invalid_value
})
