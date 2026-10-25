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

/** Exact owned-source read used only by a scoped request admitted by the shared runner. */
internal fun interface AndroidExactContactReader {
    fun read(accountName: AndroidProviderAccountName, rawContactId: Long): AndroidStableRawContactPageResult
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
    private val exactContactsReader: AndroidExactContactReader? = null,
) : AndroidInteroperabilityStage, AndroidScopedFailureProofSource {
    @Volatile override var lastFailureIsFullyScoped: Boolean = false
        private set
    init { require(maxReplansPerPage in 0..4) }

    /**
     * Refreshes the shared bounded group prerequisite, then ingests at most one exact owned
     * contact. A missing locator is not a deletion proof. The projection executor still has to
     * establish ownership and reject an unexpected dirty source before writing a new copy.
     */
    suspend fun ingestTarget(
        context: AndroidInteroperabilityContext,
        contactId: String,
        rawContactId: Long?,
        expectedSourceIdentity: String?,
        isCancelled: () -> Boolean,
    ): AndroidInteroperabilityStageResult = boundary(observeIngestRepair = true) {
        lastFailureIsFullyScoped = false
        require(contactId.isNotBlank())
        if (isCancelled()) return@boundary AndroidInteroperabilityStageResult.Cancelled
        val accountName = AndroidProviderAccountName(context.androidAccountName)
        val groupPages = readAllGroupPages(accountName, isCancelled)
            ?: return@boundary AndroidInteroperabilityStageResult.Cancelled
        if (isCancelled()) return@boundary AndroidInteroperabilityStageResult.Cancelled
        val groups = observationCoordinator.ingestGroups(context, groupPages)
        if (groups != AndroidBoundedPageResult.Applied) {
            if (groups == AndroidBoundedPageResult.ReplanRequired) {
                notifyReplan(AndroidIngestReplanReason.GROUP_OBSERVATION_STALE)
            }
            return@boundary map(groups) ?: AndroidInteroperabilityStageResult.ActionRequired
        }
        if (isCancelled()) return@boundary AndroidInteroperabilityStageResult.Cancelled
        if (rawContactId == null) return@boundary AndroidInteroperabilityStageResult.Success
        require(rawContactId > 0)
        if (expectedSourceIdentity.isNullOrBlank()) {
            return@boundary actionRequired(AndroidIngestActionRequiredReason.CONTACT_INVALID_IDENTITY_SHAPE)
        }
        val reader = exactContactsReader
            ?: return@boundary retryWaiting(AndroidIngestReplanReason.CONTACT_PAGE_UNSTABLE)
        var attempts = 0
        var page: AndroidStableRawContactObservationPage? = null
        while (page == null) {
            if (isCancelled()) return@boundary AndroidInteroperabilityStageResult.Cancelled
            when (val read = reader.read(accountName, rawContactId)) {
                is AndroidStableRawContactPageResult.Stable -> page = read.page
                AndroidStableRawContactPageResult.ReplanRequired -> if (++attempts > maxReplansPerPage) {
                    return@boundary retryWaiting(AndroidIngestReplanReason.CONTACT_PAGE_UNSTABLE)
                }
            }
        }
        if (isCancelled()) return@boundary AndroidInteroperabilityStageResult.Cancelled
        val raw = page.observations.singleOrNull()?.rawContact
        if (page.nextAfterRawContactId != null || raw == null || raw.rawContactId != rawContactId ||
            raw.canonicalContactIdClaim != contactId || raw.sourceIdentity != expectedSourceIdentity
        ) {
            return@boundary actionRequired(AndroidIngestActionRequiredReason.CONTACT_INVALID_IDENTITY_SHAPE)
        }
        when (val observed = observationCoordinator.ingestContacts(context, page)) {
            AndroidBoundedPageResult.Applied -> AndroidInteroperabilityStageResult.Success
            AndroidBoundedPageResult.PartiallyApplied -> {
                lastFailureIsFullyScoped = (observationCoordinator as? AndroidScopedFailureProofSource)
                    ?.lastFailureIsFullyScoped == true
                AndroidInteroperabilityStageResult.ActionRequired
            }
            else -> map(observed) ?: AndroidInteroperabilityStageResult.ActionRequired
        }
    }

    override suspend fun ingest(
        context: AndroidInteroperabilityContext,
        isCancelled: () -> Boolean,
    ): AndroidInteroperabilityStageResult = boundary(observeIngestRepair = true) {
        lastFailureIsFullyScoped = false
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
        var everyPartialPageScoped = groupResult == AndroidBoundedPageResult.Applied
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
            if (contactResult == AndroidBoundedPageResult.PartiallyApplied) {
                incompleteContactPage = true
                everyPartialPageScoped = (observationCoordinator as? AndroidScopedFailureProofSource)
                    ?.lastFailureIsFullyScoped == true && everyPartialPageScoped
            }
            if (contactResult == AndroidBoundedPageResult.ReplanRequired) {
                notifyReplan(AndroidIngestReplanReason.CONTACT_OBSERVATION_STALE)
            }
            map(contactResult)?.let { return@boundary it }
            after = stable.nextAfterRawContactId ?: 0L
        } while (after != 0L)
        lastFailureIsFullyScoped = incompleteContactPage && everyPartialPageScoped
        if (incompleteContactPage) AndroidInteroperabilityStageResult.ActionRequired else AndroidInteroperabilityStageResult.Success
    }

    override suspend fun project(
        context: AndroidInteroperabilityContext,
        isCancelled: () -> Boolean,
    ): AndroidInteroperabilityStageResult = boundary {
        lastFailureIsFullyScoped = false
        var after: String? = null
        var partiallyApplied = false
        var everyPartialPageScoped = true
        do {
            if (isCancelled()) return@boundary AndroidInteroperabilityStageResult.Cancelled
            val (page, result) = projectionCoordinator.projectPage(context, after)
            if (result == AndroidBoundedPageResult.PartiallyApplied) {
                partiallyApplied = true
                everyPartialPageScoped = (projectionCoordinator as? AndroidScopedFailureProofSource)
                    ?.lastFailureIsFullyScoped == true && everyPartialPageScoped
            }
            map(result)?.let { return@boundary it }
            after = page.nextKey
        } while (after != null)
        lastFailureIsFullyScoped = partiallyApplied && everyPartialPageScoped
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
        lastFailureIsFullyScoped = false
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
