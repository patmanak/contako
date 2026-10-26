package com.patmanak.contako.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.Image
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.patmanak.contako.R
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.policy.*
import com.patmanak.contako.domain.sync.ContactConflictChoice
import com.patmanak.contako.ui.components.rememberContactImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ContactConflictDialog(panel: ConflictPanelState, viewModel: ContactsViewModel) {
    ContactConflictContent(panel,
        onClose = viewModel::dismissConflict,
        onReload = { panel.contactId?.let(viewModel::openConflict) },
        onChoose = viewModel::chooseConflict,
        onStatus = { panel.contactId?.let { viewModel.dismissConflict(); viewModel.openContactSyncDetail(it) } },
        onGroups = { id ->
            viewModel.dismissConflict()
            if (id == null) viewModel.selectDestination(RootDestination.GROUPS) else viewModel.openAffectedGroup(id)
        },
        onDeletion = { panel.contactId?.let { viewModel.dismissConflict(); viewModel.openDeletionRecovery(it) } })
}

/** Local inspection only. The final confirm callback is the sole mutation entry point. */
@Composable
internal fun ContactConflictContent(panel: ConflictPanelState, onClose: () -> Unit, onReload: () -> Unit,
    onChoose: (ContactConflictChoice) -> Unit, onStatus: () -> Unit, onGroups: (String?) -> Unit, onDeletion: () -> Unit) {
    val detail = panel.detail
    var showSame by remember(detail?.summary) { mutableStateOf(false) }
    var choice by remember(detail?.summary) { mutableStateOf<ContactConflictChoice?>(null) }
    var compared by remember(detail) { mutableStateOf<List<ConflictComparedRow>?>(null) }
    LaunchedEffect(detail) {
        compared = detail?.let { withContext(Dispatchers.Default) { ContactConflictComparison.compare(it.local, it.proton, it.groups) } }
    }
    val preview = when (choice) {
        ContactConflictChoice.LOCAL -> detail?.localPreview
        ContactConflictChoice.PROTON -> detail?.protonPreview
        null -> null
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.conflict_differences), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() })
                if (panel.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.weight(1f).testTag("conflict-comparison-list"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text(stringResource(R.string.conflict_explanation)) }
                    if (panel.unavailable) item { Text(stringResource(R.string.conflict_no_comparison)) }
                    if (panel.failed || (detail != null && !detail.isCurrent)) item {
                        Text(stringResource(R.string.conflict_refresh), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onReload, enabled = !panel.busy) { Text(stringResource(R.string.conflict_review)) }
                    }
                    if (detail != null) {
                        item { Text(detail.local.resolvedDisplayName, style = MaterialTheme.typography.titleMedium) }
                        item { Text(stringResource(R.string.conflict_group_context), style = MaterialTheme.typography.bodySmall) }
                        if (detail.summary.choice != null) item {
                            Text(stringResource(R.string.conflict_queued), modifier = Modifier.testTag("conflict-choice-queued"))
                        }
                        if (detail.summary.remoteDeleted) item {
                            Text(stringResource(R.string.conflict_remote_deleted))
                            TextButton(onClick = onDeletion) { Text(stringResource(R.string.deletion_recovery_title)) }
                        }
                        if (!detail.protonChoiceAvailable && detail.isCurrent && !detail.summary.remoteDeleted && detail.protonPreview != null) item {
                            Text(stringResource(R.string.conflict_group_dependency))
                            TextButton(onClick = { onGroups(null) }) { Text(stringResource(R.string.conflict_review_groups)) }
                            detail.groups.filter { group -> group.pendingMutationRevision != null &&
                                group.memberships.any { it.contactId == detail.local.id } }.forEach { group ->
                                TextButton(onClick = { onGroups(group.id) }) { Text(group.name) }
                            }
                        }
                        if (detail.isCurrent && detail.protonPreview == null) item { Text(stringResource(R.string.conflict_unavailable)) }
                        if (choice == null) {
                            item {
                                Row(Modifier.fillMaxWidth().testTag("conflict-show-same")
                                    .toggleable(showSame, role = Role.Checkbox, onValueChange = { showSame = it }),
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Checkbox(showSame, onCheckedChange = null)
                                    Text(stringResource(R.string.conflict_show_same))
                                }
                            }
                            items(compared.orEmpty().filter { showSame || it.difference != ConflictDifference.SAME }) { row ->
                                ConflictComparedField(row)
                            }
                            if (compared == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                            if (!panel.failed && detail.isCurrent && detail.summary.choice == null && !detail.summary.remoteDeleted) {
                                item {
                                    Button(onClick = { choice = ContactConflictChoice.LOCAL }, enabled = !panel.busy && detail.localPreview != null,
                                        modifier = Modifier.fillMaxWidth().testTag("conflict-review-local")) { Text(stringResource(R.string.conflict_keep_local)) }
                                    OutlinedButton(onClick = { choice = ContactConflictChoice.PROTON },
                                        enabled = !panel.busy && detail.protonChoiceAvailable && detail.protonPreview != null,
                                        modifier = Modifier.fillMaxWidth().testTag("conflict-review-proton")) { Text(stringResource(R.string.conflict_use_proton)) }
                                }
                            }
                        } else {
                            item {
                                Text(stringResource(R.string.conflict_choice_preview), style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.semantics { heading() })
                                Text(stringResource(when {
                                    choice == ContactConflictChoice.PROTON -> R.string.conflict_proton_effect
                                    preview?.isDeleted == true -> R.string.conflict_local_delete_effect
                                    else -> R.string.conflict_local_effect
                                }))
                                Text(stringResource(R.string.conflict_android_effect))
                                Text(stringResource(R.string.conflict_preservation_scope))
                            }
                            if (preview != null) {
                                item {
                                    Text(stringResource(R.string.conflict_after_choice), style = MaterialTheme.typography.titleMedium)
                                    Text(preview.displayName)
                                    Text(stringResource(R.string.field_first_name) + ": " + preview.firstName)
                                    Text(stringResource(R.string.field_last_name) + ": " + preview.lastName)
                                }
                                items(preview.values) { value ->
                                    Card(Modifier.fillMaxWidth()) {
                                        Column(Modifier.padding(12.dp)) {
                                            Text(stringResource(conflictValueLabel(value.kind)), style = MaterialTheme.typography.titleSmall)
                                            ConflictStoredValue(value)
                                        }
                                    }
                                }
                            }
                            item {
                                Button(onClick = { choice?.let(onChoose) },
                                    enabled = !panel.busy && !panel.failed && detail.isCurrent && preview != null &&
                                        detail.summary.choice == null && !detail.summary.remoteDeleted &&
                                        (choice != ContactConflictChoice.PROTON || detail.protonChoiceAvailable),
                                    modifier = Modifier.fillMaxWidth().testTag("conflict-confirm")) { Text(stringResource(R.string.conflict_confirm_choice)) }
                                TextButton(onClick = { choice = null }, enabled = !panel.busy) { Text(stringResource(R.string.action_back)) }
                            }
                        }
                    }
                    item { TextButton(onClick = onStatus, enabled = !panel.busy) { Text(stringResource(R.string.conflict_open_status)) } }
                }
                TextButton(onClick = onClose, enabled = !panel.busy) { Text(stringResource(R.string.action_close)) }
            }
        }
    }
}

@Composable
private fun ConflictComparedField(row: ConflictComparedRow) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(when (row.field) {
                ConflictField.DISPLAY_NAME -> R.string.field_display_name
                ConflictField.FIRST_NAME -> R.string.field_first_name
                ConflictField.LAST_NAME -> R.string.field_last_name
                ConflictField.DELETION -> R.string.conflict_deleted
                ConflictField.PRESERVATION -> R.string.contact_preserved_section
                ConflictField.VALUE -> conflictValueLabel(requireNotNull(row.kind))
            }), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            Text(stringResource(when (row.difference) {
                ConflictDifference.SAME -> R.string.conflict_same
                ConflictDifference.LOCAL_ONLY -> R.string.conflict_local_only
                ConflictDifference.PROTON_ONLY -> R.string.conflict_proton_only
                ConflictDifference.DIFFERENT -> R.string.conflict_different
                ConflictDifference.UNAVAILABLE -> R.string.conflict_unavailable
            }))
            if (row.ambiguous) Text(stringResource(R.string.conflict_ambiguous))
            if (row.field == ConflictField.PRESERVATION) Text(stringResource(R.string.conflict_preservation_scope))
            else {
                ConflictComparedSide(R.string.conflict_local, row.localOrdinal, row.localText, row.localValue, row.localGroups, row.field)
                HorizontalDivider()
                ConflictComparedSide(R.string.conflict_proton, row.protonOrdinal, row.protonText, row.protonValue, row.protonGroups, row.field)
                if (row.localValue?.metadata != row.protonValue?.metadata) Text(stringResource(R.string.conflict_attributes))
            }
        }
    }
}

@Composable
private fun ConflictComparedSide(title: Int, ordinal: Int?, text: String?, value: ContactValue?, groups: List<String>?, field: ConflictField) {
    Text(if (ordinal == null) stringResource(title) else stringResource(R.string.conflict_occurrence, stringResource(title), ordinal),
        style = MaterialTheme.typography.labelLarge)
    if (value != null) {
        ConflictStoredValue(value)
        if (value.kind == ContactValueKind.EMAIL) Text(if (groups == null) stringResource(R.string.conflict_groups_unknown)
            else stringResource(R.string.conflict_groups, groups.joinToString(", ").ifEmpty { stringResource(R.string.conflict_none) }))
    } else Text(when {
        text == null -> stringResource(R.string.conflict_not_present)
        field == ConflictField.DELETION -> stringResource(if (text == "true") R.string.conflict_deleted else R.string.conflict_contact_kept)
        else -> text
    })
}

@Composable
private fun ConflictStoredValue(value: ContactValue) {
    if (value.kind in setOf(ContactValueKind.PHOTO, ContactValueKind.LOGO)) {
        val source = value.binaryReference ?: value.value
        // The existing bounded decoder accepts inline rasters only; a URL is never fetched.
        val image = rememberContactImage(source)
        if (image != null) Image(image, stringResource(conflictValueLabel(value.kind)), Modifier.size(96.dp))
        Text(stringResource(if (source.startsWith("data:", true)) R.string.conflict_media_inline else R.string.conflict_media_reference))
    } else Text(value.value)
    value.label?.let { Text(stringResource(R.string.conflict_label, it)) }
    if (value.isPrimary) Text(stringResource(R.string.field_primary_badge))
    Text(stringResource(R.string.conflict_position, value.order + 1))
    value.metadata["vcardPref"]?.let { Text(stringResource(R.string.conflict_preference, it)) }
    value.metadata["vcardTypeTokens"]?.takeIf(String::isNotBlank)?.let {
        Text(stringResource(R.string.conflict_types, it.split('\u001f').joinToString(", ")))
    }
    value.components.toSortedMap().entries.forEachIndexed { index, (component, content) ->
        val label = conflictComponentLabel(component, value.kind)
        Text((label?.let { stringResource(it) } ?: stringResource(R.string.conflict_component, index + 1)) + ": " + content)
    }
}

internal fun conflictComponentLabel(component: String, kind: ContactValueKind): Int? = when {
    kind == ContactValueKind.POSTAL_ADDRESS && component in PostalAddressPolicy.componentKeys -> postalAddressComponentLabel(component)
    kind in setOf(ContactValueKind.STRUCTURED_NAME, ContactValueKind.PHONETIC_NAME) -> when (component) {
        "given" -> R.string.field_first_name
        "family" -> R.string.field_last_name
        "additional" -> R.string.conflict_additional_names
        "prefix" -> R.string.conflict_name_prefix
        "suffix" -> R.string.conflict_name_suffix
        else -> null
    }
    else -> null
}

@Composable
internal fun ConflictVersion(title: String, contact: CanonicalContact) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (contact.isDeleted) Text(stringResource(R.string.conflict_deleted), color = MaterialTheme.colorScheme.error)
            Text(contact.resolvedDisplayName)
            Text(listOf(contact.firstName, contact.lastName).filter(String::isNotBlank).joinToString(" "))
            ContactImageBadge(contact)
            contact.values.filter { it.kind !in setOf(ContactValueKind.PHOTO, ContactValueKind.LOGO) }.forEach { value ->
                Text(stringResource(conflictValueLabel(value.kind)), style = MaterialTheme.typography.labelMedium)
                Text(value.value)
                if (value.components.isNotEmpty()) Text(value.components.values.joinToString(" · "))
            }
        }
    }
}

/** A comparison includes preserved fields that the editor intentionally cannot modify. */
internal fun conflictValueLabel(kind: ContactValueKind): Int = when (kind) {
    ContactValueKind.STRUCTURED_NAME -> R.string.field_structured_name
    ContactValueKind.UNKNOWN_VCARD_PROPERTY -> R.string.contact_preserved_section
    ContactValueKind.PHOTO -> R.string.field_photo
    ContactValueKind.LOGO -> R.string.field_logo
    else -> contactValueLabel(kind)
}
