package com.patmanak.contako.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.patmanak.contako.R
import com.patmanak.contako.domain.sync.*

data class DeletionRecoveryPanelState(val contactId: String? = null, val detail: DeletionRecoveryDetail? = null,
    val busy: Boolean = false, val failed: Boolean = false) {
    override fun toString() = "DeletionRecoveryPanelState(REDACTED)"
}

/** Consent is separate from inspection; no choice or synchronization is implicit on opening. */
@Composable
internal fun DeletionRecoveryDialog(panel: DeletionRecoveryPanelState, model: ContactsViewModel) {
    var selected by rememberSaveable(panel.detail?.summary?.generation, panel.detail?.summary?.draftRevision) {
        mutableStateOf<DeletionRecoveryChoice?>(null)
    }
    Dialog(onDismissRequest = model::dismissDeletionRecovery, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.deletion_recovery_title), style = MaterialTheme.typography.titleLarge)
                if (panel.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text(stringResource(if (panel.detail?.summary?.absenceVerified == true)
                        R.string.deletion_recovery_explanation else R.string.deletion_recovery_unverified)) }
                    if (panel.failed) item {
                        Text(stringResource(R.string.deletion_recovery_stale), color = MaterialTheme.colorScheme.error)
                        TextButton(enabled = !panel.busy, onClick = { panel.contactId?.let(model::openDeletionRecovery) }) {
                            Text(stringResource(R.string.conflict_review))
                        }
                    }
                    panel.detail?.let { detail ->
                        val summary = detail.summary
                        item { Text(stringResource(deletionRecoveryStateLabel(summary.state))) }
                        if (summary.blocker != DeletionRecoveryBlocker.NONE) item {
                            Text(stringResource(deletionRecoveryBlockerLabel(summary.blocker)))
                        }
                        if (summary.hasDraft && summary.blocker == DeletionRecoveryBlocker.LOCAL_CHANGED) item {
                            Text(stringResource(R.string.deletion_recovery_two_versions))
                        }
                        item { ConflictVersion(stringResource(if (summary.hasDraft) R.string.deletion_recovery_draft
                            else R.string.conflict_local), detail.contact) }
                        if (detail.retainedGroupAssignmentCount > 0) item {
                            Text(stringResource(R.string.deletion_recovery_groups, detail.retainedGroupAssignmentCount))
                        }
                        val terminal = summary.state in setOf(DeletionRecoveryState.RECREATED, DeletionRecoveryState.DISCARDED)
                        if (!terminal && summary.choice == null) {
                            if (summary.hasDraft && summary.destinationRevision == null) item {
                                TextButton(enabled = !panel.busy, onClick = model::editDeletionDraft) {
                                    Text(stringResource(R.string.deletion_recovery_edit))
                                }
                            }
                            if (summary.absenceVerified) {
                                val choices = if (summary.destinationRevision != null) listOf(DeletionRecoveryChoice.RECREATE)
                                    else DeletionRecoveryChoice.entries
                                choices.forEach { choice -> item {
                                    OutlinedButton(enabled = !panel.busy, onClick = { selected = choice }, modifier = Modifier.fillMaxWidth()) {
                                        Text(stringResource(deletionRecoveryChoiceLabel(choice)))
                                    }
                                } }
                            }
                        }
                        if (!terminal) item {
                            TextButton(enabled = !panel.busy, onClick = model::syncNow) {
                                Text(stringResource(R.string.deletion_recovery_verify))
                            }
                        }
                    }
                }
                TextButton(enabled = !panel.busy, onClick = model::dismissDeletionRecovery) {
                    Text(stringResource(R.string.action_close))
                }
            }
        }
    }
    selected?.let { choice ->
        AlertDialog(onDismissRequest = { selected = null },
            title = { Text(stringResource(deletionRecoveryChoiceLabel(choice))) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(panel.detail?.contact?.resolvedDisplayName.orEmpty())
                Text(stringResource(when (choice) {
                    DeletionRecoveryChoice.KEEP_DRAFT -> R.string.deletion_recovery_keep_confirm
                    DeletionRecoveryChoice.RECREATE -> R.string.deletion_recovery_create_confirm
                    DeletionRecoveryChoice.DISCARD -> R.string.deletion_recovery_discard_confirm
                }))
            } },
            confirmButton = { TextButton(enabled = !panel.busy, onClick = {
                selected = null; model.chooseDeletionRecovery(choice)
            }) { Text(stringResource(deletionRecoveryChoiceLabel(choice))) } },
            dismissButton = { TextButton(onClick = { selected = null }) { Text(stringResource(R.string.action_cancel)) } })
    }
}

internal fun deletionRecoveryChoiceLabel(choice: DeletionRecoveryChoice) = when (choice) {
    DeletionRecoveryChoice.KEEP_DRAFT -> R.string.deletion_recovery_keep
    DeletionRecoveryChoice.RECREATE -> R.string.deletion_recovery_create
    DeletionRecoveryChoice.DISCARD -> R.string.deletion_recovery_discard
}
internal fun deletionRecoveryStateLabel(state: DeletionRecoveryState) = when (state) {
    DeletionRecoveryState.NEEDS_VERIFICATION -> R.string.deletion_recovery_unverified
    DeletionRecoveryState.READY -> R.string.deletion_recovery_ready
    DeletionRecoveryState.QUEUED, DeletionRecoveryState.CLEANUP_PENDING -> R.string.deletion_recovery_pending
    DeletionRecoveryState.DRAFT -> R.string.deletion_recovery_saved
    DeletionRecoveryState.RECREATED -> R.string.deletion_recovery_created
    DeletionRecoveryState.DISCARDED -> R.string.deletion_recovery_discarded
    DeletionRecoveryState.STALE -> R.string.deletion_recovery_stale
    DeletionRecoveryState.BLOCKED -> R.string.deletion_recovery_blocked
}
internal fun deletionRecoveryBlockerLabel(blocker: DeletionRecoveryBlocker) = when (blocker) {
    DeletionRecoveryBlocker.REMOTE_PRESENT -> R.string.deletion_recovery_remote_present
    DeletionRecoveryBlocker.LOCAL_CHANGED -> R.string.deletion_recovery_stale
    DeletionRecoveryBlocker.NATIVE_CHANGES -> R.string.deletion_recovery_native_changes
    DeletionRecoveryBlocker.ANDROID_UNAVAILABLE -> R.string.deletion_recovery_android_unavailable
    DeletionRecoveryBlocker.OPAQUE_ANDROID_DATA -> R.string.deletion_recovery_opaque
    DeletionRecoveryBlocker.GROUPS_UNRESOLVED -> R.string.deletion_recovery_groups_unresolved
    DeletionRecoveryBlocker.CONTENT_NOT_TRANSFERABLE -> R.string.deletion_recovery_content
    DeletionRecoveryBlocker.REMOTE_UNAVAILABLE -> R.string.deletion_recovery_remote_unavailable
    DeletionRecoveryBlocker.NONE -> R.string.deletion_recovery_ready
}
