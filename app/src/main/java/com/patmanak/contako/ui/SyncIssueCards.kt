package com.patmanak.contako.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.patmanak.contako.R
import com.patmanak.contako.domain.sync.*
import com.patmanak.contako.ui.components.InfoCard

/** Private display locators, never diagnostic data or authority to mutate a peer. */
internal data class SyncIssueItem(
    val key: String,
    val targetId: String?,
    val name: String?,
    val problem: SyncProblem?,
    val advice: SyncIssueAdvice,
    val canReview: Boolean = false,
    val isDeleted: Boolean = false,
) {
    override fun toString() = "SyncIssueItem(REDACTED, scope=${advice.scope}, cause=${advice.cause})"
}

/** Current durable state only: retained historical diagnostic failures are intentionally absent. */
internal fun currentSyncIssues(state: ContactsUiState, conflicts: List<ContactConflictSummary>): List<SyncIssueItem> {
    val snapshot = state.syncDashboard
    val contacts = state.actionContacts.associateBy { it.id }
    val result = mutableListOf<SyncIssueItem>()
    fun add(key: String, id: String?, name: String?, problem: SyncProblem?, scope: SyncIssueScope,
        durable: Boolean = false, review: Boolean = false, deleted: Boolean = false,
        compare: Boolean = false, queuedChoice: Boolean = false) {
        result += SyncIssueItem(key, id, name, problem,
            SyncIssuePresentationPolicy.present(problem, scope, snapshot.state, durable, compare, review,
                contactsPermissionMissing = !state.contactsPermissionGranted,
                conflictChoiceQueued = queuedChoice,
                canTarget = id != null && scope in setOf(SyncIssueScope.PROTON_IMPORT, SyncIssueScope.ANDROID_COPY),
                knownValidationIssues = if (scope == SyncIssueScope.CONTACT) SyncValidationIssue.entries
                    .filter { it.name in contacts[id]?.actionRequiredReasons.orEmpty() }.toSet() else emptySet()), review, deleted)
    }
    conflicts.forEach { conflict ->
        val contact = contacts[conflict.contactId]
        add("conflict:${conflict.contactId}", conflict.contactId, contact?.resolvedDisplayName,
            SyncProblem.CONFLICT_RECOVERY_REQUIRED, SyncIssueScope.CONTACT,
            durable = true, review = contact != null && !contact.isDeleted,
            compare = !conflict.remoteDeleted, queuedChoice = conflict.choice != null && !conflict.remoteDeleted)
    }
    snapshot.blockedItems.forEach { item ->
        if (item.type == SyncBlockedItemType.CONTACT && conflicts.any { it.contactId == item.id }) return@forEach
        val group = item.type == SyncBlockedItemType.GROUP
        val review = !item.isDeleted && if (group) state.actionGroups.any { it.id == item.id } else contacts.containsKey(item.id)
        add("blocked:${item.type}:${item.id}", item.id, item.displayName, item.problem,
            if (group) SyncIssueScope.GROUP else SyncIssueScope.CONTACT,
            durable = true, review = review, deleted = item.isDeleted)
    }
    snapshot.importProblems.forEach { item ->
        add("import:${item.remoteId}", item.remoteId, item.displayName, item.problem, SyncIssueScope.PROTON_IMPORT)
    }
    snapshot.androidContactIssues.forEach { item ->
        add("android:${item.kind}:${item.contactId}", item.contactId,
            item.displayName ?: contacts[item.contactId]?.resolvedDisplayName,
            if (item.kind == SyncAndroidContactIssueKind.ANDROID_INGEST) SyncProblem.ANDROID_INTEROPERABILITY_DEGRADED
                else SyncProblem.ANDROID_CONTACTS_PARTIALLY_PROJECTED,
            SyncIssueScope.ANDROID_COPY, durable = item.kind == SyncAndroidContactIssueKind.ANDROID_PROJECTION,
            review = contacts.containsKey(item.contactId) && !item.isDeleted, deleted = item.isDeleted)
    }
    state.actionContacts.forEach { contact ->
        if (result.none { it.targetId == contact.id && it.advice.scope == SyncIssueScope.CONTACT }) {
            if (contact.actionRequiredReasons.isNotEmpty() || contact.conflictState != null ||
                contact.id in snapshot.blockedMutationContactIds) {
                add("contact:${contact.id}", contact.id, contact.resolvedDisplayName,
                    if (contact.conflictState != null) SyncProblem.CONFLICT_RECOVERY_REQUIRED
                    else if (contact.actionRequiredReasons.isNotEmpty()) SyncProblem.VALIDATION_REJECTED
                    else SyncProblem.INTERNAL_FAILURE, SyncIssueScope.CONTACT,
                    durable = contact.pendingMutationRevision != null,
                    review = !contact.isDeleted, deleted = contact.isDeleted)
            }
        }
    }
    if (!state.contactsPermissionGranted && result.none { it.advice.action == SyncIssueAction.CONTACTS_PERMISSION }) {
        add("permission", null, null, SyncProblem.ANDROID_INTEROPERABILITY_DEGRADED, SyncIssueScope.ANDROID_COPY)
    }
    val globalProblem = snapshot.problem ?: when {
        snapshot.state == SyncDashboardState.AUTHENTICATION_REQUIRED -> SyncProblem.AUTHENTICATION_REQUIRED
        result.isEmpty() && snapshot.state in setOf(SyncDashboardState.FAILED, SyncDashboardState.BLOCKED) ->
            SyncProblem.INTERNAL_FAILURE
        else -> null
    }
    if ((globalProblem != null && result.none { it.problem == globalProblem }) ||
        (result.isEmpty() && snapshot.actionRequiredCount > 0) || snapshot.state == SyncDashboardState.OFFLINE) {
        add("account", null, null, globalProblem, SyncIssueScope.ACCOUNT)
    }
    return result
}

/** Resources are selected from closed facts; no raw validation string reaches user-facing copy. */
internal fun syncIssueBodyResources(advice: SyncIssueAdvice): List<Int> {
    if (advice.cause == SyncIssueCause.VALIDATION && advice.validationIssues.isNotEmpty()) {
        return SyncValidationIssue.entries.filter { it in advice.validationIssues }.map { when (it) {
            SyncValidationIssue.MISSING_NAME -> R.string.actions_missing_name_body
            SyncValidationIssue.INVALID_EMAIL -> R.string.actions_invalid_email_body
        } }
    }
    return listOf(when (advice.cause) {
        SyncIssueCause.AUTHENTICATION -> R.string.sync_issue_auth
        SyncIssueCause.NETWORK -> R.string.sync_issue_network
        SyncIssueCause.CONFLICT -> R.string.sync_issue_conflict
        SyncIssueCause.VALIDATION -> R.string.sync_issue_validation
        SyncIssueCause.CRYPTOGRAPHY -> R.string.sync_issue_crypto
        SyncIssueCause.GROUP_CAPABILITY -> R.string.sync_issue_groups
        SyncIssueCause.REMOTE_PERMISSION -> R.string.sync_issue_remote_permission
        SyncIssueCause.ANDROID -> R.string.sync_issue_android_body
        SyncIssueCause.UNKNOWN -> R.string.sync_issue_unknown
    })
}

@Composable
internal fun SyncIssueCard(item: SyncIssueItem, state: ContactsUiState, viewModel: ContactsViewModel,
    onReauthenticate: () -> Unit) {
    val advice = item.advice
    val targetedKind = when (advice.scope) {
        SyncIssueScope.PROTON_IMPORT -> TargetedRecoveryKind.PROTON_CARD
        SyncIssueScope.ANDROID_COPY -> item.targetId?.let { TargetedRecoveryKind.ANDROID_COPY }
        else -> null
    }
    val targetedStatus = state.syncDashboard.targetedRecoveries.firstOrNull {
        it.kind == targetedKind && it.targetId == item.targetId
    }
    val actionAdvice = targetedStatus?.let {
        SyncIssuePresentationPolicy.presentTargetedFailure(it, !state.contactsPermissionGranted)
    } ?: advice
    val targetIsPrimary = targetedKind != null && item.targetId != null && actionAdvice.action == SyncIssueAction.PREVIEW_TARGETED_RECOVERY
    val scopeLabel = stringResource(when (advice.scope) {
        SyncIssueScope.ACCOUNT -> R.string.sync_issue_account
        SyncIssueScope.CONTACT -> R.string.contact_detail_title
        SyncIssueScope.GROUP -> R.string.nav_groups
        SyncIssueScope.PROTON_IMPORT -> R.string.sync_issue_import
        SyncIssueScope.ANDROID_COPY -> R.string.sync_issue_android
    })
    fun review() {
        val id = item.targetId ?: return
        if (advice.scope == SyncIssueScope.GROUP) viewModel.openAffectedGroup(id)
        else state.actionContacts.firstOrNull { it.id == id }?.let(viewModel::openContactFromGroup)
    }
    fun diagnostics() { viewModel.openSecondary(SecondaryDestination.DIAGNOSTICS) }
    InfoCard(item.name?.takeIf(String::isNotBlank) ?: scopeLabel) {
        Text(scopeLabel, style = MaterialTheme.typography.labelMedium)
        syncIssueBodyResources(advice).forEach { Text(stringResource(it)) }
        if (advice.localIntentSaved) Text(stringResource(R.string.sync_issue_saved),
            style = MaterialTheme.typography.bodySmall)
        if (item.isDeleted) Text(stringResource(R.string.sync_deletion_pending))
        targetedStatus?.let { TargetedRecoveryStatusText(it) }
        if (targetIsPrimary) {
            Button(onClick = { viewModel.openTargetedRecovery(requireNotNull(targetedKind), requireNotNull(item.targetId)) },
                enabled = targetedStatus?.state !in setOf(TargetedRecoveryState.REQUESTED, TargetedRecoveryState.RUNNING)) {
                Text(stringResource(if (targetedKind == TargetedRecoveryKind.PROTON_CARD) R.string.targeted_proton_title
                    else R.string.targeted_android_title))
            }
        } else if (actionAdvice.action == SyncIssueAction.WAIT) {
            if (targetedStatus == null) Text(stringResource(R.string.conflict_queued))
        }
        else Button(enabled = actionAdvice.action != SyncIssueAction.RETRY_SYNC || state.syncActivity == SyncActivity.IDLE,
            onClick = {
                when (actionAdvice.action) {
                    SyncIssueAction.CONTACTS_PERMISSION -> viewModel.requestContactsPermission()
                    SyncIssueAction.SIGN_IN -> onReauthenticate()
                    SyncIssueAction.RETRY_SYNC -> viewModel.syncNow()
                    SyncIssueAction.COMPARE_VERSIONS -> item.targetId?.let(viewModel::openConflict)
                    SyncIssueAction.REVIEW_ITEM -> review()
                    SyncIssueAction.VIEW_DIAGNOSTICS -> diagnostics()
                    SyncIssueAction.PREVIEW_TARGETED_RECOVERY -> targetedKind?.let { kind ->
                        item.targetId?.let { viewModel.openTargetedRecovery(kind, it) }
                    }
                    SyncIssueAction.WAIT -> Unit
                }
            }) {
            Text(stringResource(when (actionAdvice.action) {
                SyncIssueAction.CONTACTS_PERMISSION -> if (state.contactsPermissionAction == ContactsPermissionAction.OPEN_SETTINGS)
                    R.string.contacts_permission_open_settings else R.string.contacts_permission_action
                SyncIssueAction.SIGN_IN -> R.string.auth_sign_in
                SyncIssueAction.RETRY_SYNC -> R.string.sync_now
                SyncIssueAction.COMPARE_VERSIONS -> R.string.conflict_review
                SyncIssueAction.REVIEW_ITEM -> R.string.sync_issue_review
                SyncIssueAction.VIEW_DIAGNOSTICS -> R.string.sync_issue_diagnostics
                SyncIssueAction.PREVIEW_TARGETED_RECOVERY -> if (targetedKind == TargetedRecoveryKind.PROTON_CARD)
                    R.string.targeted_proton_title else R.string.targeted_android_title
                SyncIssueAction.WAIT -> R.string.conflict_queued
            }))
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Import-only remote targets have no committed local contact to inspect yet.
            if (item.targetId != null && item.advice.scope in setOf(SyncIssueScope.CONTACT, SyncIssueScope.ANDROID_COPY)) {
                TextButton(onClick = { viewModel.openContactSyncDetail(item.targetId) }) {
                    Text(stringResource(R.string.contact_sync_title))
                }
            }
            // Diagnostic/sign-in advice must not remove the original scoped recovery path.
            // Queueing a fresh preview still revalidates its source and retained retry budget.
            if (targetedKind != null && item.targetId != null && !targetIsPrimary &&
                actionAdvice.action != SyncIssueAction.WAIT &&
                targetedStatus?.state !in setOf(TargetedRecoveryState.REQUESTED, TargetedRecoveryState.RUNNING)) {
                TextButton(onClick = { viewModel.openTargetedRecovery(targetedKind, item.targetId) }) {
                    Text(stringResource(if (targetedKind == TargetedRecoveryKind.PROTON_CARD)
                        R.string.targeted_proton_title else R.string.targeted_android_title))
                }
            }
            if (item.canReview && actionAdvice.action != SyncIssueAction.REVIEW_ITEM) {
                TextButton(onClick = ::review) { Text(stringResource(R.string.sync_issue_review)) }
            }
            if (targetIsPrimary || actionAdvice.action != SyncIssueAction.VIEW_DIAGNOSTICS) {
                TextButton(onClick = ::diagnostics) { Text(stringResource(R.string.sync_issue_diagnostics)) }
            }
        }
    }
}
