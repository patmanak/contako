package com.patmanak.contako.data.sync

import androidx.room.withTransaction
import com.patmanak.contako.data.android.mapping.AndroidContactSnapshot
import com.patmanak.contako.data.android.mapping.AndroidContactSnapshotBinaryCodec
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipSnapshotBinaryCodec
import com.patmanak.contako.data.android.mapping.AndroidRowKind
import com.patmanak.contako.data.android.mapping.CanonicalAndroidContactMapper
import com.patmanak.contako.data.android.provider.*
import com.patmanak.contako.data.gateway.*
import com.patmanak.contako.data.local.*
import com.patmanak.contako.domain.sync.DeletionRecoveryBlocker
import com.patmanak.contako.domain.sync.DeletionRecoveryState
import com.patmanak.contako.domain.sync.SyncPassOutcome
import kotlinx.coroutines.CancellationException

/** Runs only inside the account runner, after remote reconciliation and before outbox delivery. */
internal fun interface DeletedContactRecoveryPhase {
    suspend fun run(account: AccountScope, isCancelled: () -> Boolean): SyncPassOutcome
}

internal interface DeletedContactRecoveryExecutionStore {
    suspend fun discover(account: String)
    suspend fun eligible(account: String): List<DeletionRecoveryEntity>
    /** Durable rotation only; this is never remote absence evidence or consent. */
    suspend fun markExamined(row: DeletionRecoveryEntity): Boolean = true
    suspend fun verifyAbsence(row: DeletionRecoveryEntity): Boolean
    suspend fun block(row: DeletionRecoveryEntity, reason: DeletionRecoveryBlocker)
}

internal class RoomDeletedContactRecoveryExecutionStore(private val store: RoomDeletionRecoveryStore) :
    DeletedContactRecoveryExecutionStore {
    override suspend fun discover(account: String) = store.discover(account)
    override suspend fun eligible(account: String) = store.eligible(account)
    override suspend fun markExamined(row: DeletionRecoveryEntity) = store.markExamined(row.accountId, row.contactId, row.generation)
    override suspend fun verifyAbsence(row: DeletionRecoveryEntity) = store.verifyAbsence(
        row.accountId, row.contactId, row.generation, row.localRevision, row.remoteId)
    override suspend fun block(row: DeletionRecoveryEntity, reason: DeletionRecoveryBlocker) {
        store.block(row.accountId, row.contactId, row.generation, reason)
    }
}

internal sealed interface DeletedContactNativeResult {
    data object Complete : DeletedContactNativeResult
    data object Cancelled : DeletedContactNativeResult
    data class Blocked(val reason: DeletionRecoveryBlocker) : DeletedContactNativeResult
}

internal fun interface DeletedContactNativeCleanup {
    suspend fun clean(row: DeletionRecoveryEntity, isCancelled: () -> Boolean): DeletedContactNativeResult
}

/** A retained deletion never becomes a CREATE without an explicit, still-current durable choice. */
internal class DeletedContactRecovery(
    private val store: DeletedContactRecoveryExecutionStore,
    private val existence: ProtonContactExistenceGateway,
    private val native: DeletedContactNativeCleanup,
) : DeletedContactRecoveryPhase {
    override suspend fun run(account: AccountScope, isCancelled: () -> Boolean): SyncPassOutcome {
        if (isCancelled()) return SyncPassOutcome.CANCELLED
        store.discover(account.value)
        var outcome = SyncPassOutcome.SUCCESS
        // Bound the targeted remote work. Ordinary inventory hydration remains incremental.
        for (row in store.eligible(account.value).take(100)) {
            if (isCancelled()) return SyncPassOutcome.CANCELLED
            if (row.accountId != account.value) return SyncPassOutcome.FAILED
            // Persist the turn before a read can fail, be cancelled or stop the process. The
            // next pass must reach later candidates even if earlier ones stay blocked forever.
            if (!store.markExamined(row)) {
                outcome = SyncPassOutcome.ACTION_REQUIRED
                continue
            }
            if (isCancelled()) return SyncPassOutcome.CANCELLED
            when (val presence = existence.check(account, RemoteContactId(row.remoteId))) {
                is GatewayOutcome.Failure -> {
                    if (presence.category == GatewayFailureCategory.CANCELLED || isCancelled()) return SyncPassOutcome.CANCELLED
                    store.block(row, DeletionRecoveryBlocker.REMOTE_UNAVAILABLE)
                    outcome = SyncPassOutcome.ACTION_REQUIRED
                    continue
                }
                is GatewayOutcome.Success -> if (presence.value != RemoteContactPresence.CONFIRMED_ABSENT) {
                    store.block(row, DeletionRecoveryBlocker.REMOTE_PRESENT)
                    outcome = SyncPassOutcome.ACTION_REQUIRED
                    continue
                }
            }
            if (isCancelled()) return SyncPassOutcome.CANCELLED
            if (!store.verifyAbsence(row)) {
                store.block(row, DeletionRecoveryBlocker.LOCAL_CHANGED)
                outcome = SyncPassOutcome.ACTION_REQUIRED
                continue
            }
            if (row.choice == null || row.state !in EXECUTABLE_RECOVERY_STATES) continue
            when (val result = native.clean(row, isCancelled)) {
                DeletedContactNativeResult.Complete -> Unit
                DeletedContactNativeResult.Cancelled -> return SyncPassOutcome.CANCELLED
                is DeletedContactNativeResult.Blocked -> {
                    store.block(row, result.reason)
                    outcome = SyncPassOutcome.ACTION_REQUIRED
                }
            }
        }
        return outcome
    }
}

private val EXECUTABLE_RECOVERY_STATES = setOf(DeletionRecoveryState.QUEUED.name,
    DeletionRecoveryState.CLEANUP_PENDING.name, DeletionRecoveryState.BLOCKED.name)

/** Revalidate the old identity at every unambiguous CREATE preparation, including delayed delivery. */
internal class DeletedContactRecreationPreparationGuard(
    private val account: AccountScope,
    private val store: RoomDeletionRecoveryStore,
    private val existence: ProtonContactExistenceGateway,
    private val delegate: MutationPreparationGateway,
) : MutationPreparationGateway {
    override suspend fun prepare(command: DurableMutationCommand): GatewayOutcome<MutationPreparation> {
        if (command.accountId != account.value) return GatewayOutcome.Failure(GatewayFailureCategory.AUTHENTICATION_REQUIRED)
        if (command.aggregateType == AggregateType.CONTACT.name && command.operation == RemoteMutationOperation.CREATE &&
            !command.requiresReconciliation) {
            val recovery = store.forDestination(account.value, command.aggregateId)
            if (recovery != null) {
                when (val presence = existence.check(account, RemoteContactId(recovery.remoteId))) {
                    is GatewayOutcome.Failure -> return presence
                    is GatewayOutcome.Success -> if (presence.value != RemoteContactPresence.CONFIRMED_ABSENT) {
                        store.suspendDestination(account.value, recovery.contactId, recovery.generation)
                        return GatewayOutcome.Success(MutationPreparation.ActionRequired(
                            MutationPreparationActionRequiredReason.REMOTE_UPDATE_CONFLICT))
                    }
                }
            }
        }
        // Ambiguous delivery uses the existing UID reconciliation and MUST NOT replay CREATE.
        return delegate.prepare(command)
    }
}

/**
 * The original canonical row stays active while native intent can still arrive. Only the final
 * Room commit retires it. A prepared versioned delete is durable before the provider call, so a
 * later absence is admissible only for that exact obligation, never for an unprepared source.
 */
internal class ProductionDeletedContactNativeCleanup(
    private val database: ContakoDatabase,
    private val store: RoomDeletionRecoveryStore,
    private val preflight: AndroidInteroperabilityPreflight,
    private val stage: BoundedAndroidInteroperabilityStage,
    private val reader: AndroidContactsProviderReader,
    private val lifecycle: AndroidRawContactLifecycleGateway,
) : DeletedContactNativeCleanup {
    override suspend fun clean(row: DeletionRecoveryEntity, isCancelled: () -> Boolean): DeletedContactNativeResult = try {
        cleanCurrent(row, isCancelled)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: AndroidProviderBoundaryException) {
        blocked(DeletionRecoveryBlocker.ANDROID_UNAVAILABLE)
    } catch (_: AndroidRawContactLifecycleException) {
        blocked(DeletionRecoveryBlocker.ANDROID_UNAVAILABLE)
    } catch (_: AndroidProviderMimeRouterException) {
        blocked(DeletionRecoveryBlocker.OPAQUE_ANDROID_DATA)
    }

    private suspend fun cleanCurrent(row: DeletionRecoveryEntity, isCancelled: () -> Boolean): DeletedContactNativeResult {
        if (isCancelled()) return DeletedContactNativeResult.Cancelled
        val account = AccountScope(row.accountId)
        // A retained draft is independent once its old source cleanup was committed. That
        // source may since have been re-imported or edited; no later draft action authorizes
        // deleting or modifying its new provider row, canonical intent or memberships.
        if (row.sourceRetired) {
            return if (!isCancelled() && store.stageDraft(row.accountId, row.contactId, row.generation) &&
                store.complete(row.accountId, row.contactId, row.generation)) DeletedContactNativeResult.Complete
            else if (isCancelled()) DeletedContactNativeResult.Cancelled else blocked(DeletionRecoveryBlocker.LOCAL_CHANGED)
        }
        val initialBinding = database.androidProjectionLedgerDao().getAccount(row.accountId)
        val initialLedger = database.androidProjectionLedgerDao().get(row.accountId, row.contactId)
        // No provisioned Android account and no historical raw locator means no provider action.
        if (initialBinding == null && initialLedger?.rawContactLocator == null) {
            return AndroidProviderAccountMutationLocks.withContactProjectionSlot(row.accountId) {
                database.withTransaction {
                    if (isCancelled()) return@withTransaction DeletedContactNativeResult.Cancelled
                    if (database.androidProjectionLedgerDao().getAccount(row.accountId) != null ||
                        database.androidProjectionLedgerDao().get(row.accountId, row.contactId) != initialLedger)
                        return@withTransaction blocked(DeletionRecoveryBlocker.ANDROID_UNAVAILABLE)
                    if (store.stageDraft(row.accountId, row.contactId, row.generation) &&
                        store.complete(row.accountId, row.contactId, row.generation)) DeletedContactNativeResult.Complete
                    else blocked(DeletionRecoveryBlocker.LOCAL_CHANGED)
                }
            }
        }
        val first = preflight.check(account, isCancelled)
        if (first == AndroidInteroperabilityPreflightResult.Cancelled) return DeletedContactNativeResult.Cancelled
        var context = (first as? AndroidInteroperabilityPreflightResult.Ready)?.context
            ?: return blocked(DeletionRecoveryBlocker.ANDROID_UNAVAILABLE)
        var ledger = database.androidProjectionLedgerDao().get(row.accountId, row.contactId)
        if (ledger != null && (ledger.sourceIdentity != row.remoteId || ledger.providerEpoch != context.providerEpoch))
            return blocked(DeletionRecoveryBlocker.NATIVE_CHANGES)
        val accountName = AndroidProviderAccountName(context.androidAccountName)
        val source = AndroidExpectedSourceIdentity.Present(row.remoteId)
        val found = lifecycle.findOwnedRawContactForDeletion(accountName, row.contactId, source)
        if (found == null) {
            if (ledger?.rawContactLocator != null && !matchesPrepared(row, ledger, context))
                return blocked(DeletionRecoveryBlocker.NATIVE_CHANGES)
            return finalizeAbsent(row, context, ledger, isCancelled)
        }
        if (ledger == null || ledger.rawContactLocator != found.rawContactId)
            return blocked(DeletionRecoveryBlocker.NATIVE_CHANGES)
        // Replayed prepared commands cannot adopt a new provider version as their old consent.
        if (row.cleanupRaw != null && (!matchesPrepared(row, ledger, context) || row.cleanupVersion != found.version))
            return blocked(DeletionRecoveryBlocker.NATIVE_CHANGES)
        when (stage.ingestTarget(context, row.contactId, ledger.rawContactLocator, row.remoteId, isCancelled)) {
            AndroidInteroperabilityStageResult.Success -> Unit
            AndroidInteroperabilityStageResult.Cancelled -> return DeletedContactNativeResult.Cancelled
            AndroidInteroperabilityStageResult.LocalPersistenceFailure -> return blocked(DeletionRecoveryBlocker.ANDROID_UNAVAILABLE)
            else -> return blocked(DeletionRecoveryBlocker.NATIVE_CHANGES)
        }
        if (isCancelled()) return DeletedContactNativeResult.Cancelled
        val refreshed = preflight.check(account, isCancelled)
        if (refreshed == AndroidInteroperabilityPreflightResult.Cancelled) return DeletedContactNativeResult.Cancelled
        val currentContext = (refreshed as? AndroidInteroperabilityPreflightResult.Ready)?.context
            ?: return blocked(DeletionRecoveryBlocker.ANDROID_UNAVAILABLE)
        if (currentContext.providerEpoch != context.providerEpoch || currentContext.androidAccountName != context.androidAccountName)
            return blocked(DeletionRecoveryBlocker.NATIVE_CHANGES)
        context = currentContext
        ledger = database.androidProjectionLedgerDao().get(row.accountId, row.contactId)
            ?: return blocked(DeletionRecoveryBlocker.NATIVE_CHANGES)
        // Saving the known local work is independent from a later refusal to discard opaque
        // Android-only rows. The old raw contact remains intact while cleanup is blocked.
        if (!store.stageDraft(row.accountId, row.contactId, row.generation))
            return blocked(DeletionRecoveryBlocker.LOCAL_CHANGED)
        val observation = (reader.readStableRawContact(accountName, found.rawContactId) as? AndroidStableRawContactPageResult.Stable)
            ?.page?.observations?.singleOrNull() ?: return blocked(DeletionRecoveryBlocker.NATIVE_CHANGES)
        val raw = observation.rawContact
        if (raw.dirty || raw.deleted || raw.rawContactId != ledger.rawContactLocator ||
            raw.canonicalContactIdClaim != row.contactId || raw.sourceIdentity != row.remoteId)
            return blocked(DeletionRecoveryBlocker.NATIVE_CHANGES)
        val route = AndroidProviderMimeRouter().route(raw.rawContactId, observation.dataRows)
        if (route.unsupportedOwnedRows.rows.isNotEmpty() || route.providerLocalRows.rows.isNotEmpty())
            return blocked(DeletionRecoveryBlocker.OPAQUE_ANDROID_DATA)
        val expectedLedger = ledger
        // This marker must survive even when provider deletion commits and completion is lost.
        val prepared = AndroidProviderAccountMutationLocks.withContactProjectionSlot(row.accountId) {
            database.withTransaction {
                currentContext(context) && database.androidProjectionLedgerDao().get(row.accountId, row.contactId) == expectedLedger &&
                    safeReceipts(row, expectedLedger, context, observation) &&
                    store.markCleanup(row.accountId, row.contactId, row.generation, context.providerEpoch,
                        raw.rawContactId, raw.version, expectedLedger.revision)
            }
        }
        if (!prepared) return blocked(DeletionRecoveryBlocker.NATIVE_CHANGES)
        if (isCancelled()) return DeletedContactNativeResult.Cancelled
        return AndroidProviderAccountMutationLocks.withContactProjectionSlot(row.accountId) {
            database.withTransaction {
                val current = store.get(row.accountId, row.contactId)
                if (current == null || !currentContext(context) ||
                    database.androidProjectionLedgerDao().get(row.accountId, row.contactId) != expectedLedger ||
                    !safeReceipts(row, expectedLedger, context, observation) || !matchesPrepared(current, expectedLedger, context))
                    return@withTransaction blocked(DeletionRecoveryBlocker.NATIVE_CHANGES)
                if (isCancelled()) return@withTransaction DeletedContactNativeResult.Cancelled
                // DIRTY=0 and the captured VERSION are part of the actual provider DELETE.
                when (lifecycle.deleteOwnedRawContact(accountName, row.contactId, raw.rawContactId, raw.version,
                    source, requireClean = true)) {
                    AndroidDeleteRawContactResult.Stale -> blocked(DeletionRecoveryBlocker.NATIVE_CHANGES)
                    else -> if (store.complete(row.accountId, row.contactId, row.generation)) DeletedContactNativeResult.Complete
                        else blocked(DeletionRecoveryBlocker.LOCAL_CHANGED)
                }
            }
        }
    }

    private suspend fun finalizeAbsent(row: DeletionRecoveryEntity, context: AndroidInteroperabilityContext,
        expectedLedger: AndroidProjectionLedgerEntity?, isCancelled: () -> Boolean): DeletedContactNativeResult =
        AndroidProviderAccountMutationLocks.withContactProjectionSlot(row.accountId) {
            database.withTransaction {
                if (isCancelled()) return@withTransaction DeletedContactNativeResult.Cancelled
                if (!currentContext(context) || database.androidProjectionLedgerDao().get(row.accountId, row.contactId) != expectedLedger)
                    return@withTransaction blocked(DeletionRecoveryBlocker.NATIVE_CHANGES)
                // Recheck under the slot; an unrelated absence observation alone is insufficient.
                if (lifecycle.findOwnedRawContactForDeletion(AndroidProviderAccountName(context.androidAccountName),
                        row.contactId, AndroidExpectedSourceIdentity.Present(row.remoteId)) != null)
                    return@withTransaction blocked(DeletionRecoveryBlocker.NATIVE_CHANGES)
                if (expectedLedger?.rawContactLocator != null && !matchesPrepared(row, expectedLedger, context))
                    return@withTransaction blocked(DeletionRecoveryBlocker.NATIVE_CHANGES)
                if (store.stageDraft(row.accountId, row.contactId, row.generation) &&
                    store.complete(row.accountId, row.contactId, row.generation)) DeletedContactNativeResult.Complete
                else blocked(DeletionRecoveryBlocker.LOCAL_CHANGED)
            }
        }

    private suspend fun currentContext(context: AndroidInteroperabilityContext): Boolean {
        val dao = database.androidProjectionLedgerDao()
        val binding = dao.getAccount(context.account.value) ?: return false
        return binding.revision == context.accountRevision && binding.providerEpoch == context.providerEpoch &&
            binding.androidAccountName == context.androidAccountName && !binding.providerRepairPending &&
            dao.countOtherAccountsBoundToAndroidName(context.account.value, context.androidAccountName) == 0
    }

    private suspend fun safeReceipts(row: DeletionRecoveryEntity, ledger: AndroidProjectionLedgerEntity,
        context: AndroidInteroperabilityContext, observation: AndroidStableRawContactObservation): Boolean {
        if (ledger.sourceIdentity != row.remoteId || ledger.tombstoneState != "NONE" || ledger.adoptionState != "ADOPTED" ||
            ledger.pendingProjectionFingerprint != null || ledger.projectionState == "WRITE_PENDING") return false
        val baseline = database.androidProjectionLedgerDao().getBaseline(row.accountId, row.contactId) ?: return false
        if (baseline.fingerprint != ledger.androidBaselineFingerprint) return false
        val contactSnapshot = try { AndroidContactSnapshotBinaryCodec.decode(baseline.encodedSnapshot) }
            catch (_: IllegalArgumentException) { return false }
        if (CanonicalAndroidContactMapper().fingerprint(contactSnapshot).sha256Hex != baseline.fingerprint) return false
        val route = AndroidProviderMimeRouter().route(observation.rawContact.rawContactId, observation.dataRows)
        val photoReceipt = database.androidGroupProjectionDao().getPhotoProjectionReceipt(row.accountId, row.contactId)
        if (!recoveryObservationMatchesContactBaseline(AndroidProviderAccountName(context.androidAccountName), row.contactId,
                contactSnapshot, route.contactRows) { bytes, reference ->
                if (photoReceipt != null && photoReceipt.binaryReference == reference && photoReceipt.matchesPhoto(context, row.contactId, observation))
                    true
                else CanonicalPhotoBinaryLoader.load(reference)?.contentEquals(bytes) == true
            }) return false
        val groupDao = database.androidGroupProjectionDao()
        val membership = groupDao.getMembership(row.accountId, row.contactId) ?: return false
        val membershipBaseline = groupDao.getMembershipBaseline(row.accountId, row.contactId) ?: return false
        val membershipSnapshot = try { AndroidGroupMembershipSnapshotBinaryCodec.decode(membershipBaseline.encodedSnapshot) }
            catch (_: IllegalArgumentException) { return false }
        if (membership.providerEpoch != ledger.providerEpoch || membership.rawContactLocator != ledger.rawContactLocator ||
            membership.pendingProjectionFingerprint != null || membership.projectionState == "WRITE_PENDING" ||
            AndroidGroupMembershipSnapshotBinaryCodec.integrityFingerprint(membershipBaseline.encodedSnapshot).sha256Hex != membershipBaseline.fingerprint ||
            membership.androidBaselineFingerprint != membershipSnapshot.semanticFingerprint().sha256Hex) return false
        // Exact provider membership occurrences must still match the ingested baseline. Neither
        // aggregate membership counts nor a canonical group intent acknowledges a new native edge.
        val expectedMembershipRows = membershipSnapshot.locatorMappings.associate { it.dataRowLocator to it.groupRowLocator }
        if (route.groupMembershipRows.rows.size != expectedMembershipRows.size || route.groupMembershipRows.rows.any {
                expectedMembershipRows[it.dataRowId]?.toString() != it.stringSlots.firstOrNull()
            }) return false
        // An outstanding photo write cannot be mistaken for a fully captured native observation.
        if (groupDao.getPhotoProviderWriteJournal(row.accountId, row.contactId) != null) return false
        return true
    }

    private fun matchesPrepared(row: DeletionRecoveryEntity, ledger: AndroidProjectionLedgerEntity,
        context: AndroidInteroperabilityContext) = row.cleanupRaw != null && row.cleanupVersion != null &&
        row.cleanupRaw == ledger.rawContactLocator && row.cleanupEpoch == context.providerEpoch &&
        row.cleanupLedgerRevision == ledger.revision && ledger.sourceIdentity == row.remoteId

    private fun blocked(reason: DeletionRecoveryBlocker) = DeletedContactNativeResult.Blocked(reason)
}

/** Read-only binding against a whole ingested baseline; never allocate or adopt provider claims. */
internal fun recoveryObservationMatchesContactBaseline(
    accountName: AndroidProviderAccountName,
    contactId: String,
    baseline: AndroidContactSnapshot,
    rows: ContactRows,
    photoMatches: (ByteArray, String) -> Boolean,
): Boolean = try {
    val baselineByLocator = baseline.rows.associateBy { it.identity.providerRowId }
    if (baseline.canonicalContactId != contactId || baselineByLocator.size != baseline.rows.size ||
        null in baselineByLocator.keys || rows.rows.size != baseline.rows.size) false
    else {
        val resolver = AndroidProviderIdentityResolver { claims ->
            val bindings = mutableMapOf<Long, AndroidDurableValueBinding>()
            val matches = claims.all { claim ->
                val expected = baselineByLocator[claim.providerRowId]
                if (expected == null || claim.accountName != accountName || claim.canonicalContactId != contactId ||
                    claim.kind != expected.kind ||
                    claim.claimedCanonicalValueId?.let { it != expected.identity.canonicalValueId } == true ||
                    claim.claimedLinkedCanonicalValueIds.any { (role, value) -> expected.linkedCanonicalValueIds[role] != value }) false
                else { bindings[claim.providerRowId] = AndroidDurableValueBinding(expected.identity.canonicalValueId,
                    expected.linkedCanonicalValueIds); true }
            }
            if (matches) AndroidProviderIdentityResolution.Bound(bindings) else AndroidProviderIdentityResolution.Divergence
        }
        val reference = baseline.rows.singleOrNull { it.kind == AndroidRowKind.PHOTO }?.binaryReference
        val codec = AndroidProviderRowCodec(resolver, AndroidDurablePhotoCapture { _, _, _, bytes ->
            require(reference != null && photoMatches(bytes, reference))
            reference
        })
        val rawId = rows.rows.firstOrNull()?.rawContactId
        if (rawId == null) baseline.rows.isEmpty() else {
            val observed = codec.decode(accountName, contactId, rawId, rows.rows)
            val mapper = CanonicalAndroidContactMapper()
            mapper.fingerprint(observed) == mapper.fingerprint(baseline)
        }
    }
} catch (_: IllegalArgumentException) { false }
