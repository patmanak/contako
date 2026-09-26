package com.patmanak.contako.ui

import android.os.Build
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.patmanak.contako.BuildConfig
import com.patmanak.contako.R
import com.patmanak.contako.diagnostics.DiagnosticSyncState
import com.patmanak.contako.diagnostics.LocalDiagnosticInput
import com.patmanak.contako.diagnostics.LocalDiagnosticReport
import com.patmanak.contako.diagnostics.LocalDiagnosticReportGenerator
import com.patmanak.contako.ui.components.InfoCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun DiagnosticSettings(state: ContactsUiState) {
    val context = LocalContext.current
    var report by remember { mutableStateOf<LocalDiagnosticReport?>(null) }
    var exportSucceeded by remember { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<LocalDiagnosticReport?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val selectedReport = pendingExport
        pendingExport = null
        if (uri == null) {
            exporting = false
        } else {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        writeDiagnosticDocument(context, uri, requireNotNull(selectedReport))
                    }
                    exportSucceeded = true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    exportSucceeded = false
                } finally {
                    exporting = false
                }
            }
        }
    }

    InfoCard(stringResource(R.string.diagnostics_title)) {
        Text(stringResource(R.string.diagnostics_body), style = MaterialTheme.typography.bodySmall)
        Button(
            enabled = !exporting,
            onClick = {
                report = LocalDiagnosticReportGenerator.generate(
                    LocalDiagnosticInput(
                        appVersion = BuildConfig.VERSION_NAME,
                        apiLevel = Build.VERSION.SDK_INT,
                        debugBuild = BuildConfig.DEBUG,
                        contactCount = state.totalContactCount,
                        groupCount = state.totalGroupCount,
                        pendingMutationCount = state.pendingMutationCount,
                        actionRequiredCount = maxOf(state.actionContacts.size, state.syncDashboard.actionRequiredCount),
                        syncState = DiagnosticSyncState.fromDashboard(state.syncDashboard.state),
                        contactsPermissionGranted = state.contactsPermissionGranted,
                    ),
                )
                exportSucceeded = null
            },
        ) { Text(stringResource(R.string.diagnostics_generate)) }
        report?.let { generated ->
            Column(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(generated.content, style = MaterialTheme.typography.bodySmall)
                Button(enabled = !exporting, onClick = {
                    pendingExport = generated
                    exporting = true
                    exportSucceeded = null
                    try {
                        export.launch("contako-diagnostic.txt")
                    } catch (_: Exception) {
                        pendingExport = null
                        exporting = false
                        exportSucceeded = false
                    }
                }) {
                    Text(stringResource(R.string.diagnostics_export))
                }
                OutlinedButton(enabled = !exporting, onClick = { report = null; exportSucceeded = null }) {
                    Text(stringResource(R.string.diagnostics_delete))
                }
            }
        } ?: Text(stringResource(R.string.diagnostics_none), style = MaterialTheme.typography.bodySmall)
        exportSucceeded?.let { succeeded ->
            Text(stringResource(if (succeeded) R.string.diagnostics_exported else R.string.diagnostics_export_failed))
        }
    }
}

private fun writeDiagnosticDocument(context: Context, uri: android.net.Uri, report: LocalDiagnosticReport) {
    context.contentResolver.openOutputStream(uri, "w")?.use { stream ->
        stream.write(report.content.toByteArray(Charsets.UTF_8))
    } ?: error("Document provider returned no output stream")
}
