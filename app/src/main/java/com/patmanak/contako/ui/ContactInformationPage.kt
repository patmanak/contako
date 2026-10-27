package com.patmanak.contako.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.patmanak.contako.R
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.model.EmailGroupAssignmentAvailability
import com.patmanak.contako.ui.components.rememberContactImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ContactInformationEntry(editor: ContactEditorState, viewModel: ContactsViewModel) {
    val contact = remember(editor) { editor.informationContact() }
    ContactInformationEntry(contact, viewModel, original = editor.original, editing = true,
        sessionKey = editor.draftGeneration)
}

@Composable
internal fun ContactInformationEntry(contact: CanonicalContact, viewModel: ContactsViewModel,
    modifier: Modifier = Modifier, original: CanonicalContact? = null, editing: Boolean = false,
    sessionKey: Long = 0) {
    var opened by rememberSaveable(contact.accountId, contact.id, editing, sessionKey) { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(stringResource(R.string.contact_information)) },
        leadingContent = { Icon(Icons.Outlined.Info, contentDescription = null) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        modifier = modifier.fillMaxWidth().testTag("contact-information-entry")
            .clickable(role = Role.Button) { opened = true },
    )
    if (opened) {
        val state by viewModel.uiState.collectAsState()
        val availability by viewModel.groupAssignmentAvailability.collectAsState()
        val savedId = if (editing) original?.id else contact.id
        ContactInformationPage(contact, original, editing, state.contactsPermissionGranted, availability,
            onClose = { opened = false }, onStatus = savedId?.let { id -> {
                opened = false
                viewModel.openContactSyncDetail(id)
            } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContactInformationPage(contact: CanonicalContact, original: CanonicalContact? = null,
    editing: Boolean = false, contactsPermissionGranted: Boolean,
    assignmentAvailability: EmailGroupAssignmentAvailability, onClose: () -> Unit,
    onStatus: (() -> Unit)? = null) {
    var fields by remember(contact, original, editing) { mutableStateOf<List<ContactInformationField>?>(null) }
    var selectedField by remember(contact, original, editing) { mutableStateOf<ContactInformationField?>(null) }
    var collapsed by rememberSaveable(contact.accountId, contact.id) {
        mutableStateOf(listOf(ContactFieldDestination.SHARED.name))
    }
    var showHelp by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(contact, original, editing) {
        fields = withContext(Dispatchers.Default) { contactInformationFields(contact, original, editing) }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(Modifier.fillMaxSize().testTag("contact-information-page"), topBar = {
            TopAppBar(title = { Text(stringResource(R.string.contact_information), maxLines = 1,
                overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onClose, modifier = Modifier.testTag("contact-information-back")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                } }, actions = {
                    IconButton(onClick = { showHelp = true }) {
                        Icon(Icons.Outlined.Info, stringResource(R.string.contact_information_help))
                    }
                })
        }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("contact-information-fields"),
                contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    Text(contact.resolvedDisplayName, style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(16.dp))
                }
                if (fields == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                ContactFieldDestination.entries.forEach { destination ->
                    val section = fields.orEmpty().filter { it.destination == destination }
                    if (section.isNotEmpty()) {
                        val expanded = destination.name !in collapsed
                        item(key = destination.name) {
                            ListItem(headlineContent = { Text(stringResource(destination.labelResource())) },
                                trailingContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(section.size.toString(), style = MaterialTheme.typography.labelLarge)
                                        Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                            contentDescription = null)
                                    }
                                }, modifier = Modifier.fillMaxWidth().testTag("contact-information-${destination.name}")
                                    .clickable(role = Role.Button) { collapsed = if (expanded)
                                        collapsed + destination.name else collapsed - destination.name })
                        }
                        if (expanded) itemsIndexed(section, key = { index, _ -> "${destination.name}:$index" }) { _, field ->
                            ContactInformationValue(field) { selectedField = field }
                        }
                        item { HorizontalDivider() }
                    }
                }
                if (fields?.isEmpty() == true) item {
                    Text(stringResource(R.string.contact_information_empty), modifier = Modifier.padding(16.dp))
                }
                if (onStatus != null) item {
                    TextButton(onClick = onStatus, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                        Text(stringResource(R.string.conflict_open_status))
                    }
                }
            }
        }
        selectedField?.let { selected ->
            FieldCompatibilityContent(selected.facts, contactsPermissionGranted,
                selected.value.kind == ContactValueKind.EMAIL, assignmentAvailability,
                onClose = { selectedField = null }, onStatus = onStatus)
        }
        if (showHelp) AlertDialog(onDismissRequest = { showHelp = false },
            title = { Text(stringResource(R.string.contact_information_help)) },
            text = { Text(stringResource(R.string.contact_information_help_body)) },
            confirmButton = { TextButton(onClick = { showHelp = false }) { Text(stringResource(R.string.action_close)) } })
    }
}

@Composable
private fun ContactInformationValue(field: ContactInformationField, onClick: () -> Unit) {
    val value = field.value
    val image = value.kind in setOf(ContactValueKind.PHOTO, ContactValueKind.LOGO)
    ListItem(
        headlineContent = { Text(listOfNotNull(stringResource(conflictValueLabel(value.kind)),
            value.label?.takeIf(String::isNotBlank)).joinToString(" · ")) },
        supportingContent = { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (image) {
                val thumbnail = rememberContactImage(contactValueImageSources(value))
                if (thumbnail != null) Image(thumbnail, contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier.size(64.dp))
                else Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Person, contentDescription = null)
                }
            } else SelectionContainer { Text(value.value) }
            if (field.facts.localLabelOnly) Text(stringResource(R.string.contact_information_local_label),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        modifier = Modifier.fillMaxWidth().testTag("contact-information-value-${value.kind.name}")
            .clickable(role = Role.Button, onClick = onClick),
    )
}

private fun ContactFieldDestination.labelResource(): Int = when (this) {
    ContactFieldDestination.SHARED -> R.string.contact_information_shared
    ContactFieldDestination.PROTON -> R.string.contact_information_proton
    ContactFieldDestination.ANDROID -> R.string.contact_information_android
    ContactFieldDestination.LOCAL -> R.string.contact_information_local
    ContactFieldDestination.READ_ONLY -> R.string.contact_information_read_only
    ContactFieldDestination.UNKNOWN -> R.string.contact_information_unknown
}
