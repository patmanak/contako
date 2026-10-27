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

internal enum class InitialImportStatus { IN_PROGRESS, COMPLETED, COMPLETED_WITH_ERRORS }

internal fun initialImportStatus(progress: InitialImportSnapshot, activity: SyncActivity,
    availability: AndroidAvailability, hasProblems: Boolean): InitialImportStatus = when {
    activity != SyncActivity.IDLE -> InitialImportStatus.IN_PROGRESS
    hasProblems || progress.deferredContactCount > 0 || availability in setOf(
        AndroidAvailability.PERMISSION_REQUIRED, AndroidAvailability.UNAVAILABLE) -> InitialImportStatus.COMPLETED_WITH_ERRORS
    progress.remoteComplete && availability == AndroidAvailability.READY && progress.androidPendingContactCount == 0 &&
        progress.androidSynchronizedContactCount == progress.localContactCount -> InitialImportStatus.COMPLETED
    else -> InitialImportStatus.IN_PROGRESS
}

/** A read-only view of durable availability, never a second synchronization driver. */
@Composable
internal fun InitialImportOverview(
    progress: InitialImportSnapshot,
    activity: SyncActivity,
    androidAvailability: AndroidAvailability,
    hasProblems: Boolean,
    onViewContacts: () -> Unit,
    onViewProblems: () -> Unit,
    androidErrorContactCount: Int = 0,
) {
    val problems = hasProblems || androidErrorContactCount > 0
    val status = initialImportStatus(progress, activity, androidAvailability, problems)
    val pending = stringResource(R.string.initial_import_pending)
    Column(Modifier.fillMaxWidth().testTag("initial_import_overview"),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.initial_import_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(when (status) {
            InitialImportStatus.IN_PROGRESS -> R.string.initial_import_in_progress
            InitialImportStatus.COMPLETED -> R.string.initial_import_completed
            InitialImportStatus.COMPLETED_WITH_ERRORS -> R.string.initial_import_completed_errors
        }), color = if (status == InitialImportStatus.COMPLETED_WITH_ERRORS) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant)
        // Activity comes from the live runner. A durable checkpoint never animates by itself.
        if (activity == SyncActivity.RUNNING) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.initial_import_inventory, progress.inventoryContactCount?.toString() ?: pending))
            Text(stringResource(R.string.initial_import_available,
                progress.localContactCount.takeIf { it > 0 || progress.inventoryEstablished }?.toString() ?: pending))
            Text(stringResource(R.string.initial_import_android_synced,
                progress.androidSynchronizedContactCount?.takeIf { androidAvailability == AndroidAvailability.READY &&
                    (it > 0 || progress.inventoryEstablished) }?.toString() ?: pending))
        }
        if (progress.deferredContactCount > 0) {
            Text(stringResource(R.string.initial_import_deferred, progress.deferredContactCount))
        }
        val androidProblem = when (androidAvailability) {
            AndroidAvailability.PERMISSION_REQUIRED -> R.string.initial_import_android_permission
            AndroidAvailability.CONTEXT_NOT_ESTABLISHED -> R.string.initial_import_android_context
            AndroidAvailability.UNAVAILABLE -> R.string.initial_import_android_unavailable
            AndroidAvailability.UNKNOWN, AndroidAvailability.READY -> null
        }
        if (androidProblem != null) Text(stringResource(androidProblem))
        if (androidErrorContactCount > 0) {
            Text(stringResource(R.string.initial_import_android_errors, androidErrorContactCount))
        }
        if (progress.localContactCount > 0) {
            Button(onClick = onViewContacts) { Text(stringResource(R.string.initial_import_view_contacts)) }
        }
        if (problems || progress.deferredContactCount > 0) {
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
    androidErrorContactCount: Int = 0,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                InitialImportOverview(progress, activity, androidAvailability, hasProblems, onViewContacts, onViewProblems,
                    androidErrorContactCount)
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
            }
        }
    }
}
