package com.patmanak.contako.data.sync

import com.patmanak.contako.data.android.provider.AndroidContactsProviderReader
import com.patmanak.contako.data.android.provider.AndroidGroupsProviderReader
import com.patmanak.contako.data.android.provider.AndroidProviderAccountName
import com.patmanak.contako.data.android.provider.AndroidProviderBoundaryException
import com.patmanak.contako.data.android.provider.AndroidProviderFailureCategory
import com.patmanak.contako.data.android.provider.AndroidStableRawContactObservationPage
import com.patmanak.contako.data.android.provider.AndroidStableRawContactPageResult
import com.patmanak.contako.data.android.provider.AndroidOwnedGroupRowPage
import com.patmanak.contako.data.android.provider.readAdaptiveAndroidContactPage

internal sealed interface AndroidBoundedPageResult {
    data object Applied : AndroidBoundedPageResult

    /**
     * The page advanced, but at least one contact could not be projected and was skipped.
     *
     * Distinct from [Applied] so the outcome stays truthful, and from [RepairRequired] so one
     * unprojectable contact no longer blocks every following contact.
     */
    data object PartiallyApplied : AndroidBoundedPageResult
    data object ReplanRequired : AndroidBoundedPageResult
    data object RepairRequired : AndroidBoundedPageResult
    data object LocalPersistenceFailure : AndroidBoundedPageResult
}

/** Closed ingestion repair causes; no provider row, contact value, or identity may cross it. */
internal enum class AndroidIngestActionRequiredReason {
    GROUP_PAGE_CHAIN,
    GROUP_PLAN,
    GROUP_COMMIT,
    GROUP_PROVIDER_FINALIZATION,
    CONTACT_EXISTING_PLAN,
    CONTACT_EXISTING_COMMIT,
    CONTACT_INVALID_IDENTITY_SHAPE,
    CONTACT_DELETED_WITH_DATA,
    CONTACT_BINARY_ROW,
    CONTACT_MIME_ROUTING,
    CONTACT_UNSUPPORTED_OWNED_MIME,
    CONTACT_CREATED_ROW_CODEC,
    CONTACT_CREATED_ARGUMENT,
    CONTACT_CREATED_REPLAY_LEDGER,
    CONTACT_CREATED_COMMIT,
    CONTACT_DELETED_IDENTITY,
    CONTACT_DELETED_COMMIT,
    PROVIDER_PERMISSION_DENIED,
    PROVIDER_BOUNDARY,
}

internal fun interface AndroidIngestActionRequiredObserver {
    fun onActionRequired(reason: AndroidIngestActionRequiredReason)
}

/** Closed retry causes; no provider row, contact value, identity, or exception may cross it. */
internal enum class AndroidIngestReplanReason {
    GROUP_OBSERVATION_STALE,
    CONTACT_PAGE_UNSTABLE,
    CONTACT_OBSERVATION_STALE,
    CONTACT_CATALOG_STALE,
    CONTACT_EXISTING_PLAN_STALE,
    CONTACT_EXISTING_COMMIT_STALE,
    CONTACT_CREATED_COMMIT_STALE,
    CONTACT_DELETED_COMMIT_STALE,
    CONTACT_ACK_AUTHORIZATION_STALE,
    CONTACT_PROVIDER_ACK_STALE,
    PROVIDER_UNAVAILABLE,
}

internal fun interface AndroidIngestReplanObserver {
    fun onReplan(reason: AndroidIngestReplanReason)
}

internal interface AndroidBoundedObservationCoordinator {
    suspend fun ingestGroups(
        context: AndroidInteroperabilityContext,
        pages: List<AndroidOwnedGroupRowPage>,
    ): AndroidBoundedPageResult

    suspend fun ingestContacts(
        context: AndroidInteroperabilityContext,
        page: AndroidStableRawContactObservationPage,
    ): AndroidBoundedPageResult
}

internal data class AndroidProjectionPage(
    val nextKey: String?,
    val itemCount: Int,
) {
    init {
        require(itemCount in 0..MAX_ITEMS)
        require(nextKey == null || nextKey.isNotBlank())
        require(itemCount > 0 || nextKey == null)
    }

    private companion object { const val MAX_ITEMS = 100 }
}

internal fun interface AndroidBoundedProjectionCoordinator {
    suspend fun projectPage(
        context: AndroidInteroperabilityContext,
        afterKey: String?,
    ): Pair<AndroidProjectionPage, AndroidBoundedPageResult>
}

internal fun interface AndroidStableContactPageReader {
    fun read(
        accountName: AndroidProviderAccountName,
        afterRawContactId: Long,
    ): AndroidStableRawContactPageResult
}

internal fun interface AndroidGroupPageReader {
    fun read(
        accountName: AndroidProviderAccountName,
        afterGroupRowId: Long,
    ): AndroidOwnedGroupRowPage
}

/** Bounded production stage. All mutation policies are mandatory injected authorities. */
internal class BoundedAndroidInteroperabilityStage(
    private val contactsReader: AndroidStableContactPageReader,
    private val groupsReader: AndroidGroupPageReader,
    private val observationCoordinator: AndroidBoundedObservationCoordinator,
    private val projectionCoordinator: AndroidBoundedProjectionCoordinator,
    private val maxReplansPerPage: Int = 2,
    private val actionRequiredObserver: AndroidIngestActionRequiredObserver =
        AndroidIngestActionRequiredObserver { },
    private val replanObserver: AndroidIngestReplanObserver = AndroidIngestReplanObserver { },
) : AndroidInteroperabilityStage {
    init { require(maxReplansPerPage in 0..4) }

    override suspend fun ingest(
        context: AndroidInteroperabilityContext,
        isCancelled: () -> Boolean,
    ): AndroidInteroperabilityStageResult = boundary(observeIngestRepair = true) {
        val accountName = AndroidProviderAccountName(context.androidAccountName)
        val groupPages = readAllGroupPages(accountName, isCancelled)
            ?: return@boundary AndroidInteroperabilityStageResult.Cancelled
        val groupResult = observationCoordinator.ingestGroups(context, groupPages)
        if (groupResult == AndroidBoundedPageResult.ReplanRequired) {
            notifyReplan(AndroidIngestReplanReason.GROUP_OBSERVATION_STALE)
        }
        map(groupResult)?.let { return@boundary it }

        var after = 0L
        var incompleteContactPage = false
        do {
            if (isCancelled()) return@boundary AndroidInteroperabilityStageResult.Cancelled
            var attempts = 0
            var stable: AndroidStableRawContactObservationPage? = null
            while (stable == null) {
                when (val read = contactsReader.read(accountName, after)) {
                    is AndroidStableRawContactPageResult.Stable -> stable = read.page
                    AndroidStableRawContactPageResult.ReplanRequired -> {
                        if (++attempts > maxReplansPerPage) {
                            return@boundary retryWaiting(AndroidIngestReplanReason.CONTACT_PAGE_UNSTABLE)
                        }
                    }
                }
                if (isCancelled()) return@boundary AndroidInteroperabilityStageResult.Cancelled
            }
            val contactResult = observationCoordinator.ingestContacts(context, stable)
            if (contactResult == AndroidBoundedPageResult.PartiallyApplied) incompleteContactPage = true
            if (contactResult == AndroidBoundedPageResult.ReplanRequired) {
                notifyReplan(AndroidIngestReplanReason.CONTACT_OBSERVATION_STALE)
            }
            map(contactResult)?.let { return@boundary it }
            after = stable.nextAfterRawContactId ?: 0L
        } while (after != 0L)
        if (incompleteContactPage) AndroidInteroperabilityStageResult.ActionRequired else AndroidInteroperabilityStageResult.Success
    }

    override suspend fun project(
        context: AndroidInteroperabilityContext,
        isCancelled: () -> Boolean,
    ): AndroidInteroperabilityStageResult = boundary {
        var after: String? = null
        var partiallyApplied = false
        do {
            if (isCancelled()) return@boundary AndroidInteroperabilityStageResult.Cancelled
            val (page, result) = projectionCoordinator.projectPage(context, after)
            if (result == AndroidBoundedPageResult.PartiallyApplied) partiallyApplied = true
            map(result)?.let { return@boundary it }
            after = page.nextKey
        } while (after != null)
        if (partiallyApplied) AndroidInteroperabilityStageResult.ActionRequired else AndroidInteroperabilityStageResult.Success
    }

    private fun readAllGroupPages(
        accountName: AndroidProviderAccountName,
        isCancelled: () -> Boolean,
    ): List<AndroidOwnedGroupRowPage>? {
        val pages = mutableListOf<AndroidOwnedGroupRowPage>()
        var after = 0L
        do {
            if (isCancelled()) return null
            val page = groupsReader.read(accountName, after)
            pages += page
            if (pages.size > MAX_GROUP_PAGES) {
                throw AndroidProviderBoundaryException(AndroidProviderFailureCategory.BOUND_EXCEEDED)
            }
            after = page.nextAfterGroupRowId ?: 0L
        } while (after != 0L)
        return pages
    }

    private fun map(result: AndroidBoundedPageResult): AndroidInteroperabilityStageResult? = when (result) {
        AndroidBoundedPageResult.Applied -> null
        // Returning null keeps the traversal going to the next page: the skipped contacts are
        // already recorded, and stopping here would reintroduce the blockage this replaced.
        AndroidBoundedPageResult.PartiallyApplied -> null
        AndroidBoundedPageResult.ReplanRequired -> AndroidInteroperabilityStageResult.RetryWaiting
        AndroidBoundedPageResult.RepairRequired -> AndroidInteroperabilityStageResult.ActionRequired
        AndroidBoundedPageResult.LocalPersistenceFailure -> AndroidInteroperabilityStageResult.LocalPersistenceFailure
    }

    private suspend fun boundary(
        observeIngestRepair: Boolean = false,
        block: suspend () -> AndroidInteroperabilityStageResult,
    ): AndroidInteroperabilityStageResult = try {
        block()
    } catch (error: AndroidProviderBoundaryException) {
        when (error.category) {
            AndroidProviderFailureCategory.PERMISSION_DENIED -> if (observeIngestRepair) {
                actionRequired(AndroidIngestActionRequiredReason.PROVIDER_PERMISSION_DENIED)
            } else AndroidInteroperabilityStageResult.ActionRequired
            AndroidProviderFailureCategory.PROVIDER_UNAVAILABLE -> if (observeIngestRepair) {
                retryWaiting(AndroidIngestReplanReason.PROVIDER_UNAVAILABLE)
            } else AndroidInteroperabilityStageResult.RetryWaiting
            else -> if (observeIngestRepair) {
                actionRequired(AndroidIngestActionRequiredReason.PROVIDER_BOUNDARY)
            } else AndroidInteroperabilityStageResult.ActionRequired
        }
    }

    private fun actionRequired(
        reason: AndroidIngestActionRequiredReason,
    ): AndroidInteroperabilityStageResult.ActionRequired = AndroidInteroperabilityStageResult.ActionRequired.also {
        runCatching { actionRequiredObserver.onActionRequired(reason) }
    }

    private fun retryWaiting(
        reason: AndroidIngestReplanReason,
    ): AndroidInteroperabilityStageResult.RetryWaiting = AndroidInteroperabilityStageResult.RetryWaiting.also {
        notifyReplan(reason)
    }

    private fun notifyReplan(reason: AndroidIngestReplanReason) {
        runCatching { replanObserver.onReplan(reason) }
    }

    companion object {
        private const val MAX_GROUP_PAGES = 6

        fun productionReaders(
            contacts: AndroidContactsProviderReader,
            groups: AndroidGroupsProviderReader,
        ): Pair<AndroidStableContactPageReader, AndroidGroupPageReader> =
            AndroidStableContactPageReader { account, after ->
                readAdaptiveAndroidContactPage { limit ->
                    contacts.readStableObservationPage(account, after, limit, includeDeleted = true)
                }
            } to AndroidGroupPageReader { account, after ->
                groups.readGroupPage(account, after, 100, includeDeleted = true)
            }
    }
}
