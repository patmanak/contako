package com.patmanak.contako.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.patmanak.contako.R
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.sync.ContactConflictChoice

@Composable
internal fun ContactConflictDialog(panel: ConflictPanelState, viewModel: ContactsViewModel) {
    Dialog(onDismissRequest = viewModel::dismissConflict, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.conflict_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.conflict_explanation))
                if (panel.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (panel.failed) {
                    Text(stringResource(R.string.conflict_refresh), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { panel.contactId?.let(viewModel::openConflict) }, enabled = !panel.busy) {
                        Text(stringResource(R.string.conflict_review))
                    }
                }
                val detail = panel.detail
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (detail != null) {
                        item { ConflictVersion(stringResource(R.string.conflict_local), detail.local) }
                        item { ConflictVersion(stringResource(R.string.conflict_proton), detail.proton) }
                    }
                }
                if (detail?.summary?.choice != null) Text(stringResource(R.string.conflict_queued))
                if (detail?.summary?.remoteDeleted == true) Text(stringResource(R.string.conflict_remote_deleted))
                if (detail != null && !detail.protonChoiceAvailable) Text(stringResource(R.string.conflict_group_pending))
                if (detail != null && !detail.summary.remoteDeleted && detail.summary.choice == null && !panel.failed) {
                    Button(onClick = { viewModel.chooseConflict(ContactConflictChoice.LOCAL) },
                        enabled = !panel.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.conflict_keep_local)) }
                    OutlinedButton(onClick = { viewModel.chooseConflict(ContactConflictChoice.PROTON) },
                        enabled = !panel.busy && detail.protonChoiceAvailable, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.conflict_use_proton)) }
                }
                TextButton(onClick = viewModel::dismissConflict, enabled = !panel.busy) { Text(stringResource(R.string.action_cancel)) }
            }
        }
    }
}

@Composable
private fun ConflictVersion(title: String, contact: CanonicalContact) {
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
