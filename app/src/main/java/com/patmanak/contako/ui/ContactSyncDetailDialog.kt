package com.patmanak.contako.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.patmanak.contako.R
import com.patmanak.contako.domain.sync.*
import java.text.DateFormat
import java.util.Date

data class ContactSyncPanelState(
    val contactId: String? = null,
    val detail: ContactSyncDetail? = null,
    val loading: Boolean = false,
    val failed: Boolean = false,
) {
    override fun toString() = "ContactSyncPanelState(REDACTED)"
}

/** Rendering and dismissing this panel never contact either peer. Refresh is an explicit action. */
@Composable
internal fun ContactSyncDetailDialog(panel: ContactSyncPanelState, state: ContactsUiState, viewModel: ContactsViewModel) {
    Dialog(onDismissRequest = viewModel::dismissContactSyncDetail) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.contact_sync_title), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.contact_sync_local_read), style = MaterialTheme.typography.bodySmall)
                if (panel.loading) CircularProgressIndicator()
                val detail = panel.detail
                if (!panel.loading && (panel.failed || detail == null)) Text(stringResource(R.string.contact_sync_unavailable))
                if (detail != null) {
                    detail.displayName?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                    Text(stringResource(R.string.contact_sync_local), style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() })
                    Text(stringResource(if (detail.isDeleted) R.string.contact_sync_deleted_local else R.string.contact_sync_saved_local))
                    Text(stringResource(R.string.contact_sync_local_version, detail.localRevision))
                    if (detail.localOnlyFieldCount > 0) Text(stringResource(R.string.contact_sync_local_only, detail.localOnlyFieldCount))
                    HorizontalDivider()
                    ContactPeerEvidenceSection(R.string.contact_sync_proton, detail.proton, detail.localRevision)
                    Text(stringResource(R.string.contact_sync_proton_scope), style = MaterialTheme.typography.bodySmall)
                    if (detail.groupChangesPending) Text(stringResource(R.string.contact_sync_groups_pending))
                    HorizontalDivider()
                    val unavailable = !state.contactsPermissionGranted || state.syncDashboard.androidAvailability in setOf(
                        AndroidAvailability.PERMISSION_REQUIRED, AndroidAvailability.CONTEXT_NOT_ESTABLISHED, AndroidAvailability.UNAVAILABLE)
                    val android = if (unavailable) detail.android.copy(state = ContactPeerSyncState.UNAVAILABLE)
                        else detail.android
                    ContactPeerEvidenceSection(R.string.contact_sync_android, android, detail.localRevision)
                    if (!state.contactsPermissionGranted) Text(stringResource(R.string.targeted_permission))
                    Text(stringResource(R.string.contact_sync_android_scope), style = MaterialTheme.typography.bodySmall)
                    Button(enabled = state.syncActivity == SyncActivity.IDLE, onClick = viewModel::syncNow,
                        modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.contact_sync_refresh)) }
                    if (listOf(detail.proton.state, detail.android.state).any {
                            it in setOf(ContactPeerSyncState.CONFLICT, ContactPeerSyncState.ACTION_REQUIRED)
                        }) TextButton(onClick = {
                        viewModel.dismissContactSyncDetail()
                        viewModel.openSecondary(SecondaryDestination.ACTIONS)
                    }) { Text(stringResource(R.string.initial_import_view_problems)) }
                }
                TextButton(onClick = viewModel::dismissContactSyncDetail) { Text(stringResource(R.string.action_close)) }
            }
        }
    }
}

@Composable
private fun ContactPeerEvidenceSection(title: Int, peer: ContactPeerSyncDetail, currentRevision: Long) {
    Text(stringResource(title), style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() })
    Text(stringResource(when (peer.state) {
        ContactPeerSyncState.CONFIRMED -> R.string.contact_sync_confirmed
        ContactPeerSyncState.PENDING -> R.string.contact_sync_pending
        ContactPeerSyncState.CONFLICT -> R.string.contact_sync_conflict
        ContactPeerSyncState.ACTION_REQUIRED -> R.string.contact_sync_attention
        ContactPeerSyncState.HISTORICAL -> R.string.contact_sync_historical
        ContactPeerSyncState.UNKNOWN -> R.string.contact_sync_unknown
        ContactPeerSyncState.UNAVAILABLE -> R.string.contact_sync_peer_unavailable
    }))
    peer.evidence?.let { proof ->
        Text(stringResource(if (proof.localRevision == currentRevision && peer.state == ContactPeerSyncState.CONFIRMED)
            R.string.contact_sync_covered_version else R.string.contact_sync_previous_version, proof.localRevision))
        Text(stringResource(when (proof.kind) {
            ContactSyncEvidenceKind.VERIFIED_READ -> R.string.contact_sync_verified_read
            ContactSyncEvidenceKind.WRITE_ACKNOWLEDGEMENT -> R.string.contact_sync_acknowledged_write
            ContactSyncEvidenceKind.CONFIRMED_ABSENCE -> R.string.contact_sync_verified_absence
            ContactSyncEvidenceKind.ANDROID_READBACK -> R.string.contact_sync_android_readback
            ContactSyncEvidenceKind.ANDROID_ABSENCE -> R.string.contact_sync_android_absence
        }), style = MaterialTheme.typography.bodySmall)
        val locale = LocalConfiguration.current.locales[0]
        val time = proof.confirmedAtEpochMillis
        Text(if (time == null) stringResource(R.string.contact_sync_time_unknown) else
            stringResource(R.string.contact_sync_last_confirmation,
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(time))))
    }
}
