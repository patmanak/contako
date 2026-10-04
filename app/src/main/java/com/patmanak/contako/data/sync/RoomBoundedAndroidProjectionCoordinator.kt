package com.patmanak.contako.data.sync

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.patmanak.contako.data.android.AndroidProjectionWriteState
import com.patmanak.contako.data.android.AndroidAdoptionState
import com.patmanak.contako.data.android.AndroidTombstoneState
import com.patmanak.contako.data.android.RoomAndroidProjectionLedger
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipSnapshotBinaryCodec
import com.patmanak.contako.data.android.mapping.AndroidGroupProjectionPolicy
import com.patmanak.contako.data.android.mapping.CanonicalAndroidContactMapper
import com.patmanak.contako.data.local.AndroidProjectionLedgerEntity
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.data.local.toDomain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun interface AndroidProjectionItemExecutor {
    suspend fun project(
        context: AndroidInteroperabilityContext,
        ledger: AndroidProjectionLedgerEntity,
    ): AndroidBoundedPageResult
}

/** Reports a contact the projection could not write, so it can be surfaced instead of hidden. */
internal fun interface AndroidProjectionSkipObserver {
    fun onSkipped(canonicalContactId: String, result: AndroidBoundedPageResult)
}

/** Confirms exact owned raw identities before treating a stored CLEAN copy as current. */
internal fun interface AndroidCleanProjectionPresenceVerifier {
    suspend fun present(
        context: AndroidInteroperabilityContext,
        candidates: List<AndroidProjectionLedgerEntity>,
    ): Set<String>
}

/** Room-keyset traversal; CLEAN entries require a bounded provider metadata proof. */
internal class RoomBoundedAndroidProjectionCoordinator(
    private val database: ContakoDatabase,
    private val itemExecutor: AndroidProjectionItemExecutor,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val skipObserver: AndroidProjectionSkipObserver = AndroidProjectionSkipObserver { _, _ -> },
    private val groupProjectionCoordinator: AndroidCanonicalGroupProjectionCoordinator =
        AndroidCanonicalGroupProjectionCoordinator { AndroidBoundedPageResult.Applied },
    private val canonicalMapper: CanonicalAndroidContactMapper = CanonicalAndroidContactMapper(),
    private val presenceVerifier: AndroidCleanProjectionPresenceVerifier =
        AndroidCleanProjectionPresenceVerifier { _, _ -> emptySet() },
) : AndroidBoundedProjectionCoordinator {
    init { require(pageSize in 1..MAX_PAGE_SIZE) }

    override suspend fun projectPage(
        context: AndroidInteroperabilityContext,
        afterKey: String?,
    ): Pair<AndroidProjectionPage, AndroidBoundedPageResult> {
        return try {
        if (afterKey == null) {
            val groupResult = groupProjectionCoordinator.project(context)
            if (groupResult != AndroidBoundedPageResult.Applied) {
                return AndroidProjectionPage(null, 0) to groupResult
            }
        }
        val account = database.androidProjectionLedgerDao().getAccount(context.account.value)
            ?: return AndroidProjectionPage(null, 0) to AndroidBoundedPageResult.RepairRequired
        if (account.revision != context.accountRevision || account.providerEpoch != context.providerEpoch ||
            account.androidAccountName != context.androidAccountName
        ) {
            return AndroidProjectionPage(null, 0) to AndroidBoundedPageResult.ReplanRequired
        }
        val page = database.withTransaction {
            val dao = database.androidProjectionLedgerDao()
            if (dao.getAccount(context.account.value) != account) return@withTransaction null
            // Recover previously acknowledged local creations omitted from the ledger.
            // Bounded local discovery needs no full Proton card fetch and never replaces
            // existing bindings, tombstones or ownership. Normal adoption does the writing.
            dao.getContactsMissingProjectionLedger(context.account.value, afterKey.orEmpty(), pageSize)
                .forEach { contact ->
                    RoomAndroidProjectionLedger(database).attachCanonicalContact(
                        context.account, contact.id, requireNotNull(contact.remoteContactId),
                    )
                }
            dao.getProjectionPage(context.account.value, afterKey.orEmpty(), pageSize)
        } ?: return AndroidProjectionPage(null, 0) to AndroidBoundedPageResult.ReplanRequired
        if (page.any { it.providerEpoch != context.providerEpoch }) {
            return AndroidProjectionPage(null, page.size) to AndroidBoundedPageResult.ReplanRequired
        }
        var skipped = 0
        val locallyCurrentIds = currentProjectionIds(context, page)
        val detachedIds = page.filter {
            it.canonicalContactId in locallyCurrentIds &&
                it.projectionState == AndroidProjectionWriteState.DETACHED.name
        }.map { it.canonicalContactId }.toSet()
        val presenceCandidates = page.filter {
            it.canonicalContactId in locallyCurrentIds && it.canonicalContactId !in detachedIds
        }
        // Provider calls stay outside the Room transaction. The ordinary executor retains
        // all version/ownership guards if a copy is missing, dirty, deleted or rebound.
        val presentIds = if (presenceCandidates.isEmpty()) emptySet()
            else presenceVerifier.present(context, presenceCandidates)
        val currentIds = detachedIds + presentIds.intersect(locallyCurrentIds)
        val outcomes = executeContinuously(context, page, currentIds)
        outcomes.forEach { (ledger, result) ->
                if (result != null) when (result) {
                    // An item executor never reports PartiallyApplied; it is a page-level outcome.
                    AndroidBoundedPageResult.Applied,
                    AndroidBoundedPageResult.PartiallyApplied,
                    -> Unit
                    // A single unprojectable contact used to abort the page, so one bad entry hid
                    // every following contact from the system app and progress was one contact per
                    // pass. Repair-required is specific to that contact: record it and continue.
                    AndroidBoundedPageResult.RepairRequired -> {
                        skipped++
                        skipObserver.onSkipped(ledger.canonicalContactId, result)
                    }
                    // Replan normally means the pass observed stale state and should restart. But
                    // an entry left in WRITE_PENDING by an interrupted pass replans forever, and
                    // stopping here hid every following contact from the system app. That entry is
                    // recoverable on its own, so it is skipped like any other unprojectable one.
                    AndroidBoundedPageResult.ReplanRequired -> {
                        if (ledger.projectionState == AndroidProjectionWriteState.WRITE_PENDING.name) {
                            skipped++
                            skipObserver.onSkipped(ledger.canonicalContactId, result)
                        } else {
                            return AndroidProjectionPage(null, page.size) to result
                        }
                    }
                    // A local persistence failure describes the pass itself, not one contact.
                    AndroidBoundedPageResult.LocalPersistenceFailure ->
                        return AndroidProjectionPage(null, page.size) to result
                }
        }
        val next = page.lastOrNull()?.canonicalContactId?.takeIf { page.size == pageSize }
        // The page advances even when entries were skipped; the skipped ones are reported so the
        // user sees an actionable count rather than a silently incomplete projection.
        val outcome = if (skipped > 0) {
            AndroidBoundedPageResult.PartiallyApplied
        } else {
            AndroidBoundedPageResult.Applied
        }
        AndroidProjectionPage(next, page.size) to outcome
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SQLiteException) {
        AndroidProjectionPage(null, 0) to AndroidBoundedPageResult.LocalPersistenceFailure
        }
    }

    /** CLEAN describes the last write, not whether canonical values/memberships changed since. */
    private suspend fun currentProjectionIds(
        context: AndroidInteroperabilityContext,
        page: List<AndroidProjectionLedgerEntity>,
    ): Set<String> = database.withTransaction {
        require(page.size <= MAX_PAGE_SIZE)
        if (page.isEmpty()) return@withTransaction emptySet()
        val ids = page.map { it.canonicalContactId }
        // Keep the same transactional freshness proof, with four bounded queries per page
        // instead of one canonical/value pair and two membership queries per contact.
        val contacts = database.contactDao().getForProjectionPage(context.account.value, ids)
            .associateBy { it.contact.id }
        val memberships = database.androidGroupProjectionDao()
            .getMembershipsForProjectionPage(context.account.value, ids).associateBy { it.canonicalContactId }
        val baselines = database.androidGroupProjectionDao()
            .getMembershipBaselinesForProjectionPage(context.account.value, ids).associateBy { it.canonicalContactId }
        val groups = database.contactGroupDao().getAll(context.account.value).map { it.toDomain() }
        val policy = AndroidGroupProjectionPolicy()
        page.mapNotNull { ledger ->
            val canonical = contacts[ledger.canonicalContactId]?.toDomain() ?: return@mapNotNull null
            if (ledger.tombstoneState == AndroidTombstoneState.REMOTE_CONVERGED.name &&
                ledger.projectionState == AndroidProjectionWriteState.DETACHED.name &&
                canonical.isDeleted && canonical.pendingMutationRevision == null &&
                canonical.conflictState == null && canonical.remoteContactId == ledger.sourceIdentity
            ) return@mapNotNull ledger.canonicalContactId
            if (ledger.projectionState != AndroidProjectionWriteState.CLEAN.name ||
                ledger.adoptionState != AndroidAdoptionState.ADOPTED.name ||
                ledger.pendingProjectionFingerprint != null || ledger.rawContactLocator == null ||
                ledger.tombstoneState != "NONE"
            ) return@mapNotNull null
            if (canonical.isDeleted || canonical.remoteContactId != ledger.sourceIdentity) return@mapNotNull null
            try {
                if (canonicalMapper.fingerprint(canonicalMapper.project(canonical)).sha256Hex !=
                    ledger.canonicalProjectionFingerprint
                ) return@mapNotNull null
                val membership = memberships[ledger.canonicalContactId] ?: return@mapNotNull null
                if (membership.projectionState != AndroidProjectionWriteState.CLEAN.name ||
                    membership.pendingProjectionFingerprint != null ||
                    membership.providerEpoch != context.providerEpoch ||
                    membership.rawContactLocator != ledger.rawContactLocator
                ) return@mapNotNull null
                val baseline = baselines[ledger.canonicalContactId] ?: return@mapNotNull null
                if (AndroidGroupMembershipSnapshotBinaryCodec.integrityFingerprint(baseline.encodedSnapshot)
                        .sha256Hex != baseline.fingerprint
                ) return@mapNotNull null
                val observed = AndroidGroupMembershipSnapshotBinaryCodec.decode(baseline.encodedSnapshot)
                observed.requireCurrentCanonicalContext(canonical)
                val desired = policy.projectMemberships(canonical, groups)
                if (observed.semanticFingerprint().sha256Hex != membership.androidBaselineFingerprint ||
                    observed.canonicalGroupIds.toSet() != desired.canonicalGroupIds ||
                    observed.membershipAvailability != desired.availability
                ) return@mapNotNull null
                ledger.canonicalContactId
            } catch (_: IllegalArgumentException) {
                // Let the item executor report the bounded repair reason; never claim CLEAN.
                null
            }
        }.toSet()
    }

    /**
     * Keeps every approved slot useful instead of waiting for the slowest item in a fixed wave.
     * A pass-wide failure stops new work; already-running items finish exactly as they did in a
     * fixed window. Results stay in page order so the existing outcome policy remains stable.
     */
    private suspend fun executeContinuously(
        context: AndroidInteroperabilityContext,
        page: List<AndroidProjectionLedgerEntity>,
        currentIds: Set<String>,
    ): List<Pair<AndroidProjectionLedgerEntity, AndroidBoundedPageResult?>> = coroutineScope {
        val admission = Mutex()
        var nextIndex = 0
        var admissionClosed = false
        val outcomes = arrayOfNulls<Pair<AndroidProjectionLedgerEntity, AndroidBoundedPageResult?>>(page.size)
        val workerCount = minOf(CONTACT_PROJECTION_CONCURRENCY, page.size)

        List(workerCount) {
            async {
                while (true) {
                    val index = admission.withLock {
                        if (admissionClosed || nextIndex >= page.size) null else nextIndex++
                    } ?: break
                    val ledger = page[index]
                    val clean = ledger.canonicalContactId in currentIds
                    val result = if (clean) null else itemExecutor.project(context, ledger)
                    // Publish the outcome and close admission in one linearized operation. A
                    // concurrently completed successful worker may have admitted work before this
                    // lock is acquired, but no worker can observe the terminal publication and
                    // then admit another item.
                    admission.withLock {
                        outcomes[index] = ledger to result
                        if (result == AndroidBoundedPageResult.LocalPersistenceFailure ||
                            result == AndroidBoundedPageResult.ReplanRequired &&
                            ledger.projectionState != AndroidProjectionWriteState.WRITE_PENDING.name
                        ) {
                            admissionClosed = true
                        }
                    }
                }
            }
        }.awaitAll()

        outcomes.filterNotNull()
    }

    private companion object {
        const val DEFAULT_PAGE_SIZE = 100
        const val MAX_PAGE_SIZE = 100
        const val CONTACT_PROJECTION_CONCURRENCY = 16
    }
}
