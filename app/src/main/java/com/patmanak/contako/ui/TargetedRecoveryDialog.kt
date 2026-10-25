package com.patmanak.contako.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.patmanak.contako.R
import com.patmanak.contako.domain.sync.*

data class TargetedRecoveryPanelState(
    val kind: TargetedRecoveryKind? = null,
    val targetId: String? = null,
    val preview: TargetedRecoveryPreview? = null,
    val loading: Boolean = false,
    val submitting: Boolean = false,
    val failed: Boolean = false,
    val queueResult: TargetedRecoveryQueueResult? = null,
) {
    override fun toString() = "TargetedRecoveryPanelState(REDACTED, loading=$loading, submitting=$submitting)"
}

@Composable
internal fun TargetedRecoveryDialog(panel: TargetedRecoveryPanelState, viewModel: ContactsViewModel) {
    val kind = panel.kind ?: return
    Dialog(onDismissRequest = viewModel::dismissTargetedRecovery) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(if (kind == TargetedRecoveryKind.PROTON_CARD) R.string.targeted_proton_title
                    else R.string.targeted_android_title), style = MaterialTheme.typography.titleLarge)
                panel.preview?.displayName?.takeIf(String::isNotBlank)?.let { Text(it) }
                Text(stringResource(if (kind == TargetedRecoveryKind.PROTON_CARD) R.string.targeted_proton_body
                    else if (panel.preview?.isDeleted == true) R.string.targeted_android_delete_body
                    else R.string.targeted_android_body))
                Text(stringResource(R.string.targeted_scope_note), style = MaterialTheme.typography.bodySmall)
                if (panel.loading || panel.submitting) CircularProgressIndicator()
                panel.preview?.prerequisite?.let { Text(stringResource(targetedPrerequisiteText(it))) }
                if (panel.failed) Text(stringResource(if (panel.queueResult == TargetedRecoveryQueueResult.STALE)
                    R.string.targeted_changed else R.string.targeted_unavailable))
                if (panel.preview?.canQueue == true && !panel.failed) {
                    Button(onClick = viewModel::confirmTargetedRecovery, enabled = !panel.loading && !panel.submitting) {
                        Text(stringResource(R.string.targeted_confirm))
                    }
                }
                if ((panel.failed || panel.preview?.canQueue == false) && !panel.submitting) {
                    TextButton(onClick = { panel.targetId?.let { viewModel.openTargetedRecovery(kind, it) } }) {
                        Text(stringResource(R.string.targeted_refresh))
                    }
                }
                TextButton(onClick = viewModel::dismissTargetedRecovery, enabled = !panel.submitting) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    }
}

internal fun targetedPrerequisiteText(prerequisite: TargetedRecoveryPrerequisite): Int = when (prerequisite) {
    TargetedRecoveryPrerequisite.NO_OBLIGATION -> R.string.targeted_no_obligation
    TargetedRecoveryPrerequisite.AUTHENTICATION -> R.string.sync_issue_auth
    TargetedRecoveryPrerequisite.ANDROID_PERMISSION -> R.string.targeted_permission
    TargetedRecoveryPrerequisite.ANDROID_CONTEXT, TargetedRecoveryPrerequisite.GROUP_CONTEXT -> R.string.targeted_dependency
    TargetedRecoveryPrerequisite.CONTACT_CHANGED -> R.string.targeted_changed
    TargetedRecoveryPrerequisite.REMOTE_IDENTITY -> R.string.targeted_identity
    TargetedRecoveryPrerequisite.UNAVAILABLE -> R.string.targeted_unavailable
}

@Composable
internal fun TargetedRecoveryStatusText(status: TargetedRecoveryStatus) {
    Text(stringResource(when (status.state) {
        TargetedRecoveryState.REQUESTED -> R.string.targeted_queued
        TargetedRecoveryState.RUNNING -> R.string.targeted_running
        TargetedRecoveryState.DEFERRED -> R.string.targeted_deferred
        TargetedRecoveryState.RESOLVED -> R.string.targeted_resolved
        TargetedRecoveryState.STALE -> R.string.targeted_changed
        TargetedRecoveryState.ACTION_REQUIRED -> R.string.targeted_attention
    }))
    status.prerequisite?.let { Text(stringResource(targetedPrerequisiteText(it)), style = MaterialTheme.typography.bodySmall) }
    SyncIssuePresentationPolicy.presentTargetedFailure(status)?.let { advice ->
        syncIssueBodyResources(advice).forEach { message ->
            Text(stringResource(R.string.targeted_last_failure, stringResource(message)), style = MaterialTheme.typography.bodySmall)
        }
    }
    if (status.state == TargetedRecoveryState.DEFERRED) status.nextEligibleAtEpochMillis?.let { eligible ->
        val locale = LocalConfiguration.current.locales[0]
        val formatted = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT,
            locale).format(java.util.Date(eligible))
        Text(stringResource(R.string.targeted_retry_after, formatted), style = MaterialTheme.typography.bodySmall)
    }
}
