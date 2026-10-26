package com.patmanak.contako.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.patmanak.contako.R
import com.patmanak.contako.domain.policy.DirectoryFilter
import com.patmanak.contako.domain.policy.OrganizationFacet

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
    organizations: List<OrganizationFacet>,
    onDraftChange: (DirectoryFilter) -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).testTag("directory_filter_sheet")) {
            // Facets share the scrolling content, so large address books never compose every company.
            LazyColumn(Modifier.weight(1f).testTag("directory_filter_options"),
                contentPadding = PaddingValues(horizontal = 16.dp)) {
                item(key = "title") {
                    Text(stringResource(R.string.directory_filter), style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(bottom = 12.dp))
                }
                item(key = "phone") {
                    DirectoryFilterOption(stringResource(R.string.directory_without_phone), draft.withoutPhone) {
                        onDraftChange(draft.copy(withoutPhone = it))
                    }
                }
                item(key = "email") {
                    DirectoryFilterOption(stringResource(R.string.directory_without_email), draft.withoutEmail) {
                        onDraftChange(draft.copy(withoutEmail = it))
                    }
                }
                item(key = "local") {
                    DirectoryFilterOption(stringResource(R.string.directory_local_only), draft.localOnly,
                        stringResource(R.string.directory_local_only_description)) {
                        onDraftChange(draft.copy(localOnly = it))
                    }
                }
                item(key = "recent") {
                    DirectoryFilterOption(stringResource(R.string.directory_recent_changes), draft.recentChanges,
                        stringResource(R.string.directory_recent_changes_description)) {
                        onDraftChange(draft.copy(recentChanges = it))
                    }
                }
                item(key = "organizations") {
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    Text(stringResource(R.string.directory_organizations), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.directory_organizations_description),
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
                }
                if (organizations.isEmpty()) item(key = "no-organizations") {
                    Text(stringResource(R.string.directory_organizations_empty), Modifier.padding(vertical = 12.dp))
                }
                items(organizations, key = { "organization:${it.key}" }) { organization ->
                    DirectoryFilterOption(organization.label, organization.key in draft.organizations) { selected ->
                        onDraftChange(draft.copy(organizations = if (selected) draft.organizations + organization.key
                            else draft.organizations - organization.key))
                    }
                }
            }
            HorizontalDivider()
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                Button(onClick = onApply) { Text(stringResource(R.string.directory_apply_filters)) }
            }
        }
    }
}

@Composable
private fun DirectoryFilterOption(label: String, checked: Boolean, description: String? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("directory_filter_option")
        .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange)
        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = null)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            description?.let { Text(it, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
internal fun DirectoryFilterSummary(
    filter: DirectoryFilter,
    organizations: List<OrganizationFacet>,
    resultCount: Int,
    onFilterChange: (DirectoryFilter) -> Unit,
) {
    Text(stringResource(R.string.directory_result_count, resultCount),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("directory_result_count"))
    if (!filter.isActive) return
    val labels = remember(organizations) { organizations.associate { it.key to it.label } }
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
            RemovableDirectoryFilter(stringResource(R.string.directory_recent_changes)) {
                onFilterChange(filter.copy(recentChanges = false))
            }
        }
        items(filter.organizations.toList(), key = { "organization:$it" }) { key ->
            RemovableDirectoryFilter(labels[key] ?: key) {
                onFilterChange(filter.copy(organizations = filter.organizations - key))
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
