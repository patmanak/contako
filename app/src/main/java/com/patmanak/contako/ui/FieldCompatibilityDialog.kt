package com.patmanak.contako.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.patmanak.contako.R
import com.patmanak.contako.data.android.mapping.*
import com.patmanak.contako.domain.model.EmailGroupAssignmentAvailability
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValueKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class FieldCompatibilityTarget(val kind: ContactValueKind, val valueId: String? = null,
    val editing: Boolean = true, val groups: Boolean = false) {
    override fun toString() = "FieldCompatibilityTarget(kind=$kind, editing=$editing, groups=$groups)"
}

@Composable
internal fun FieldCompatibilityButton(kind: ContactValueKind, onClick: () -> Unit) {
    IconButton(onClick, Modifier.testTag("field-help-${kind.name}")) {
        Icon(Icons.Outlined.Info, stringResource(R.string.compatibility_about, stringResource(conflictValueLabel(kind))))
    }
}

@Composable
internal fun FieldCompatibilityDialog(target: FieldCompatibilityTarget, state: ContactsUiState, viewModel: ContactsViewModel) {
    val contact = remember(target, state.contactEditor, state.selectedContact) {
        if (!target.editing) state.selectedContact else state.contactEditor?.let { editor ->
            (editor.original ?: CanonicalContact("compatibility-draft", "compatibility-draft")).copy(
                firstName = editor.firstName, lastName = editor.lastName, displayName = editor.displayName,
                values = editor.values + editor.images + editor.original?.values.orEmpty()
                    .filter { it.kind == ContactValueKind.STRUCTURED_NAME },
            )
        }
    }
    val original = if (target.editing) state.contactEditor?.original else null
    var description by remember(target, contact, original) { mutableStateOf<FieldCompatibilityDescription?>(null) }
    LaunchedEffect(target, contact, original) {
        description = withContext(Dispatchers.Default) {
            when {
                target.valueId == null -> ContactFieldCompatibility.describeFamily(target.kind)
                contact == null -> FieldCompatibilityDescription(target.kind, ProtonFieldSupport.UNKNOWN,
                    AndroidFieldScope.UNKNOWN, represented = null, localLabelOnly = false)
                target.editing -> ContactFieldCompatibility.describeDraft(contact, original, target.valueId).copy(kind = target.kind)
                else -> ContactFieldCompatibility.describe(contact, target.valueId).copy(kind = target.kind)
            }
        }
    }
    val availability by viewModel.groupAssignmentAvailability.collectAsState()
    val savedId = if (target.editing) state.contactEditor?.original?.id else state.selectedContact?.id
    description?.let { facts ->
        FieldCompatibilityContent(facts, state.contactsPermissionGranted, target.groups,
            availability, viewModel::dismissFieldCompatibility,
            onStatus = savedId?.let { id -> { viewModel.dismissFieldCompatibility(); viewModel.openContactSyncDetail(id) } })
    }
}

/** Format, account capability, editor visibility and delivery are intentionally separate. */
@Composable
internal fun FieldCompatibilityContent(description: FieldCompatibilityDescription, contactsPermissionGranted: Boolean,
    groups: Boolean, assignmentAvailability: EmailGroupAssignmentAvailability, onClose: () -> Unit,
    onStatus: (() -> Unit)? = null) {
    Dialog(onDismissRequest = onClose) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.heightIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(20.dp)
                .testTag("field-compatibility-help"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HelpHeading(R.string.compatibility_title)
                Text(stringResource(conflictValueLabel(description.kind)), style = MaterialTheme.typography.titleMedium)
                HelpHeading(R.string.compatibility_local)
                Text(stringResource(R.string.compatibility_local_text))
                HelpHeading(R.string.compatibility_proton)
                Text(stringResource(when (description.proton) {
                    ProtonFieldSupport.GENERATED -> R.string.compatibility_proton_generated
                    ProtonFieldSupport.LOCAL_ONLY -> R.string.compatibility_proton_local
                    ProtonFieldSupport.PRESERVED_READ_ONLY -> R.string.compatibility_proton_preserved
                    ProtonFieldSupport.UNKNOWN -> R.string.compatibility_unknown
                }))
                if (description.localLabelOnly) Text(stringResource(R.string.compatibility_local_label))
                val emailGroups = groups || description.kind == ContactValueKind.EMAIL
                if (emailGroups) {
                    Text(stringResource(R.string.compatibility_email_groups))
                    Text(stringResource(when (assignmentAvailability) {
                        EmailGroupAssignmentAvailability.AVAILABLE -> R.string.compatibility_groups_available
                        EmailGroupAssignmentAvailability.DENIED -> R.string.compatibility_groups_denied
                        EmailGroupAssignmentAvailability.UNKNOWN -> R.string.compatibility_groups_unknown
                    }), modifier = Modifier.testTag("field-help-group-capability"))
                }
                HelpHeading(R.string.compatibility_android)
                Text(stringResource(when (description.android) {
                    AndroidFieldScope.ALL_OCCURRENCES -> R.string.compatibility_android_all
                    AndroidFieldScope.PRIMARY_ONLY -> R.string.compatibility_android_primary
                    AndroidFieldScope.NONE -> R.string.compatibility_android_none
                    AndroidFieldScope.UNKNOWN -> R.string.compatibility_unknown
                }))
                description.represented?.takeIf { description.android != AndroidFieldScope.NONE }?.let {
                    Text(stringResource(if (it) R.string.compatibility_occurrence_included else R.string.compatibility_occurrence_omitted))
                }
                if (emailGroups) Text(stringResource(when (description.preferredEmail) {
                    true -> R.string.compatibility_email_preferred
                    false -> R.string.compatibility_email_secondary
                    null -> R.string.compatibility_email_preference
                }))
                if (!contactsPermissionGranted) Text(stringResource(R.string.compatibility_permission))
                if (description.kind == ContactValueKind.PHOTO) Text(stringResource(R.string.compatibility_photo))
                if (description.kind == ContactValueKind.UNKNOWN_VCARD_PROPERTY) Text(stringResource(R.string.compatibility_opaque_android))
                HelpHeading(R.string.compatibility_editor)
                Text(stringResource(R.string.compatibility_editor_text))
                Text(stringResource(R.string.compatibility_delivery))
                if (onStatus != null) TextButton(onClick = onStatus, modifier = Modifier.testTag("field-help-status")) {
                    Text(stringResource(R.string.conflict_open_status))
                }
                TextButton(onClick = onClose, modifier = Modifier.testTag("field-help-close")) { Text(stringResource(R.string.action_close)) }
            }
        }
    }
}

@Composable
private fun HelpHeading(text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
}
