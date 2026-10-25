package com.patmanak.contako.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.patmanak.contako.R
import com.patmanak.contako.domain.sync.InitialImportSnapshot
import com.patmanak.contako.domain.sync.AndroidAvailability
import com.patmanak.contako.domain.sync.SyncActivity

/** A read-only view of durable availability, never a second synchronization driver. */
@Composable
internal fun InitialImportOverview(
    progress: InitialImportSnapshot,
    activity: SyncActivity,
    androidAvailability: AndroidAvailability,
    hasProblems: Boolean,
    onViewContacts: () -> Unit,
    onViewProblems: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().testTag("initial_import_overview"),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(if (progress.remoteComplete) R.string.initial_import_ready else R.string.initial_import_title),
            style = MaterialTheme.typography.titleMedium)
        Text(stringResource(when {
            progress.inventoryEstablished -> R.string.initial_import_inventory_done
            activity == SyncActivity.RUNNING -> R.string.initial_import_inventory_search
            else -> R.string.initial_import_waiting
        }))
        // Activity comes from the live runner. A durable checkpoint never animates by itself.
        if (!progress.remoteComplete && activity == SyncActivity.RUNNING) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Text(stringResource(R.string.initial_import_available, progress.localContactCount))
        progress.inventoryContactCount?.let {
            Text(stringResource(R.string.initial_import_inventory, it))
        }
        if (progress.deferredContactCount > 0) {
            Text(stringResource(R.string.initial_import_deferred, progress.deferredContactCount))
        }
        Text(stringResource(when (androidAvailability) {
            AndroidAvailability.PERMISSION_REQUIRED -> R.string.initial_import_android_permission
            AndroidAvailability.CONTEXT_NOT_ESTABLISHED -> R.string.initial_import_android_context
            AndroidAvailability.UNAVAILABLE -> R.string.initial_import_android_unavailable
            AndroidAvailability.UNKNOWN -> R.string.initial_import_android_unknown
            AndroidAvailability.READY -> if (progress.androidPendingContactCount > 0)
                R.string.initial_import_android_pending else R.string.initial_import_android_no_pending
        }, progress.androidPendingContactCount))
        if (progress.localContactCount > 0) {
            Button(onClick = onViewContacts) { Text(stringResource(R.string.initial_import_view_contacts)) }
        }
        if (hasProblems || progress.deferredContactCount > 0) {
            TextButton(onClick = onViewProblems) { Text(stringResource(R.string.initial_import_view_problems)) }
        }
    }
}

@Composable
internal fun InitialImportDialog(
    progress: InitialImportSnapshot,
    activity: SyncActivity,
    androidAvailability: AndroidAvailability,
    hasProblems: Boolean,
    onDismiss: () -> Unit,
    onViewContacts: () -> Unit,
    onViewProblems: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                InitialImportOverview(progress, activity, androidAvailability, hasProblems, onViewContacts, onViewProblems)
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
            }
        }
    }
}
