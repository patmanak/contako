package com.patmanak.contako.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.patmanak.contako.R
import com.patmanak.contako.domain.policy.DirectoryFilter

@Composable
internal fun DirectoryFilterAction(active: Boolean, onOpen: () -> Unit) {
    val activeDescription = stringResource(R.string.directory_filters_active)
    val icon = remember {
        ImageVector.Builder("DirectoryFilter", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(3f, 4f); lineTo(21f, 4f); lineTo(21f, 6f)
                lineTo(14f, 14f); lineTo(14f, 20f); lineTo(10f, 20f)
                lineTo(10f, 14f); lineTo(3f, 6f); close()
            }
        }.build()
    }
    IconButton(onClick = onOpen, modifier = Modifier.semantics {
        if (active) stateDescription = activeDescription
    }) {
        BadgedBox(badge = { if (active) Badge() }) {
            Icon(icon, contentDescription = stringResource(R.string.directory_filter))
        }
    }
}

/** Draft changes stay separate from the applied directory until the single Apply action. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun DirectoryFilterSheet(
    draft: DirectoryFilter,
    onDraftChange: (DirectoryFilter) -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    var daysInput by rememberSaveable { mutableStateOf(draft.recentDays.toString()) }
    val days = daysInput.toIntOrNull()?.takeIf { it > 0 }
    val keyboard = LocalSoftwareKeyboardController.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = { BottomSheetDefaults.DragHandle(Modifier.testTag("directory_filter_handle")) },
    ) {
        // Natural content height keeps the expanded anchor stable while the sheet consumes
        // top insets. Only the options scroll when a small window or IME constrains them.
        Column(Modifier.fillMaxWidth().testTag("directory_filter_sheet")) {
            Text(stringResource(R.string.directory_filter), style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).testTag("directory_filter_options")) {
                DirectoryFilterOption(stringResource(R.string.directory_without_phone), draft.withoutPhone) {
                    onDraftChange(draft.copy(withoutPhone = it))
                }
                DirectoryFilterOption(stringResource(R.string.directory_without_email), draft.withoutEmail) {
                    onDraftChange(draft.copy(withoutEmail = it))
                }
                DirectoryFilterOption(stringResource(R.string.directory_local_only), draft.localOnly) {
                    onDraftChange(draft.copy(localOnly = it))
                }
                DirectoryFilterOption(stringResource(R.string.directory_recent_changes), draft.recentChanges) {
                    onDraftChange(draft.copy(recentChanges = it))
                }
                if (draft.recentChanges) {
                    OutlinedTextField(value = daysInput, onValueChange = { input ->
                        if (input.all(Char::isDigit)) {
                            daysInput = input
                            input.toIntOrNull()?.takeIf { it > 0 }?.let { onDraftChange(draft.copy(recentDays = it)) }
                        }
                    }, label = { Text(stringResource(R.string.directory_recent_days)) },
                        isError = days == null,
                        supportingText = if (days == null) ({ Text(stringResource(R.string.directory_invalid_days)) }) else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                        singleLine = true,
                        modifier = Modifier.padding(start = 36.dp, bottom = 12.dp).widthIn(max = 160.dp)
                            .testTag("directory_recent_days"))
                }
            }
            HorizontalDivider()
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                Button(onClick = onApply, enabled = !draft.recentChanges || days != null) {
                    Text(stringResource(R.string.directory_apply_filters))
                }
            }
        }
    }
}

@Composable
private fun DirectoryFilterOption(label: String, checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("directory_filter_option")
        .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange)
        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = 12.dp))
    }
}

@Composable
internal fun DirectoryFilterSummary(
    filter: DirectoryFilter,
    resultCount: Int,
    onFilterChange: (DirectoryFilter) -> Unit,
) {
    Text(stringResource(R.string.directory_result_count, resultCount),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("directory_result_count"))
    if (!filter.isActive) return
    LazyRow(Modifier.fillMaxWidth().testTag("directory_active_filters"),
        contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (filter.withoutPhone) item(key = "phone") {
            RemovableDirectoryFilter(stringResource(R.string.directory_without_phone)) {
                onFilterChange(filter.copy(withoutPhone = false))
            }
        }
        if (filter.withoutEmail) item(key = "email") {
            RemovableDirectoryFilter(stringResource(R.string.directory_without_email)) {
                onFilterChange(filter.copy(withoutEmail = false))
            }
        }
        if (filter.localOnly) item(key = "local") {
            RemovableDirectoryFilter(stringResource(R.string.directory_local_only)) {
                onFilterChange(filter.copy(localOnly = false))
            }
        }
        if (filter.recentChanges) item(key = "recent") {
            RemovableDirectoryFilter(pluralStringResource(R.plurals.directory_recent_window, filter.recentDays, filter.recentDays)) {
                onFilterChange(filter.copy(recentChanges = false))
            }
        }
    }
}

@Composable
private fun RemovableDirectoryFilter(label: String, onRemove: () -> Unit) {
    val removeDescription = stringResource(R.string.directory_remove_filter, label)
    InputChip(selected = true, onClick = onRemove,
        label = { Text(label) }, trailingIcon = { Icon(Icons.Default.Close, contentDescription = null) },
        modifier = Modifier.widthIn(max = 280.dp).semantics { contentDescription = removeDescription })
}

@Composable
internal fun FilteredDirectoryEmpty(
    hasFilters: Boolean,
    hasQuery: Boolean,
    onClearFilters: () -> Unit,
    onClearQuery: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        item { Text(stringResource(R.string.directory_filtered_empty), style = MaterialTheme.typography.titleMedium) }
        if (hasFilters) item {
            TextButton(onClick = onClearFilters) { Text(stringResource(R.string.directory_clear_filters)) }
        }
        if (hasQuery) item {
            TextButton(onClick = onClearQuery) { Text(stringResource(R.string.directory_search_clear)) }
        }
    }
}

@Composable
internal fun DirectoryReadStatus(failed: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    if (failed) {
        LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            item { Text(stringResource(R.string.directory_read_failed), style = MaterialTheme.typography.titleMedium) }
            item { TextButton(onClick = onRetry) { Text(stringResource(R.string.directory_retry_read)) } }
        }
    } else {
        val loadingDescription = stringResource(R.string.directory_loading)
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.semantics { contentDescription = loadingDescription })
        }
    }
}
