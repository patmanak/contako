package com.patmanak.contako.data.sync

import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.data.gateway.GatewayOutcome
import com.patmanak.contako.data.gateway.ProtonVerifiedContactCardGateway
import com.patmanak.contako.data.gateway.RemoteContactId
import com.patmanak.contako.data.gateway.VerifiedContactCard
import com.patmanak.contako.data.local.TargetedRecoveryRequestEntity
import com.patmanak.contako.domain.sync.SyncPassOutcome
import com.patmanak.contako.domain.sync.TargetedRecoveryKind
import com.patmanak.contako.domain.sync.TargetedRecoveryPrerequisite

/** Durable metadata/transaction seam. No payload is copied into a recovery command. */
internal interface TargetedRecoveryExecutionStore {
    suspend fun recoverInterrupted(account: AccountScope, now: Long)
    suspend fun eligible(account: AccountScope, now: Long): List<TargetedRecoveryRequestEntity>
    suspend fun claim(request: TargetedRecoveryRequestEntity, now: Long): TargetedRecoveryRequestEntity?
    suspend fun validate(request: TargetedRecoveryRequestEntity): TargetedRecoveryWorkResult?
    suspend fun rebaseAfterIngestion(request: TargetedRecoveryRequestEntity, revision: Long?): TargetedRecoveryRequestEntity?
    suspend fun completeVerified(request: TargetedRecoveryRequestEntity, card: VerifiedContactCard,
        now: Long, isCancelled: () -> Boolean): TargetedRecoveryWorkResult
    suspend fun finish(request: TargetedRecoveryRequestEntity, result: TargetedRecoveryWorkResult,
        now: Long, isCancelled: () -> Boolean)
    suspend fun settleAfterOrdinaryPass(account: AccountScope, now: Long, isCancelled: () -> Boolean)
}

/** Only called from the existing serialized executor, after session and interrupted-write checks. */
internal class TargetedContactRecovery(
    private val store: TargetedRecoveryExecutionStore,
    private val verifiedCards: ProtonVerifiedContactCardGateway,
    private val android: TargetedAndroidRecovery,
    private val prepareProton: suspend (TargetedRecoveryRequestEntity, () -> Boolean) -> TargetedRecoveryWorkResult,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun run(account: AccountScope,
        remotePrerequisites: suspend () -> SyncPrerequisiteState = { SyncPrerequisiteState.READY },
        beforeRemote: suspend () -> Unit = {}, isCancelled: () -> Boolean): SyncPassOutcome {
        if (isCancelled()) return SyncPassOutcome.CANCELLED
        store.recoverInterrupted(account, clock())
        var outcome = SyncPassOutcome.SUCCESS
        var checkedRemote: SyncPrerequisiteState? = null
        var mutationsRecovered = false
        for (pending in store.eligible(account, clock())) {
            if (isCancelled()) return SyncPassOutcome.CANCELLED
            var command = store.claim(pending, clock()) ?: continue
            var result = store.validate(command)
            if (result == null) result = try { when (TargetedRecoveryKind.valueOf(command.kind)) {
                TargetedRecoveryKind.ANDROID_COPY -> {
                    val revision = command.expectedRevision
                    val epoch = command.expectedEpoch
                    if (revision == null || epoch == null) TargetedRecoveryWorkResult.Stale(TargetedRecoveryPrerequisite.ANDROID_CONTEXT)
                    else android.recover(account, command.targetId, revision, epoch, isCancelled)
                }
                TargetedRecoveryKind.PROTON_CARD -> {
                    val remote = checkedRemote ?: remotePrerequisites().also { checkedRemote = it }
                    if (remote == SyncPrerequisiteState.READY && !mutationsRecovered) {
                        beforeRemote(); mutationsRecovered = true
                    }
                    val prerequisite = when (remote) {
                        SyncPrerequisiteState.READY -> prepareProton(command, isCancelled)
                        SyncPrerequisiteState.OFFLINE -> TargetedRecoveryWorkResult.Failed(GatewayFailureCategory.NETWORK_UNAVAILABLE,
                            TargetedRecoveryPrerequisite.UNAVAILABLE)
                        SyncPrerequisiteState.AUTHENTICATION_REQUIRED, SyncPrerequisiteState.INTERACTIVE_ACTION_REQUIRED ->
                            TargetedRecoveryWorkResult.Failed(GatewayFailureCategory.AUTHENTICATION_REQUIRED, TargetedRecoveryPrerequisite.AUTHENTICATION)
                    }
                    if (prerequisite !is TargetedRecoveryWorkResult.Resolved) prerequisite
                    else {
                        val rebased = store.rebaseAfterIngestion(command, prerequisite.canonicalRevision)
                        if (rebased == null) TargetedRecoveryWorkResult.Stale()
                        else {
                            command = rebased
                            if (isCancelled()) TargetedRecoveryWorkResult.Cancelled
                            else when (val fetched = verifiedCards.fetch(account, RemoteContactId(command.targetId))) {
                                is GatewayOutcome.Success -> store.completeVerified(command, fetched.value, clock(), isCancelled)
                                is GatewayOutcome.Failure -> TargetedRecoveryWorkResult.Failed(fetched.category,
                                    retryAfterMillis = fetched.retryAfterMillis)
                            }
                        }
                    }
                }
            } } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { TargetedRecoveryWorkResult.Failed(GatewayFailureCategory.UNKNOWN,
                TargetedRecoveryPrerequisite.UNAVAILABLE) }
            store.finish(command, result, clock(), isCancelled)
            outcome = when (result) {
                TargetedRecoveryWorkResult.Cancelled -> return SyncPassOutcome.CANCELLED
                is TargetedRecoveryWorkResult.Resolved -> outcome
                is TargetedRecoveryWorkResult.Stale -> if (outcome == SyncPassOutcome.SUCCESS) SyncPassOutcome.ACTION_REQUIRED else outcome
                is TargetedRecoveryWorkResult.Failed -> when (result.category) {
                    GatewayFailureCategory.NETWORK_UNAVAILABLE, GatewayFailureCategory.TIMEOUT,
                    GatewayFailureCategory.RATE_LIMITED, GatewayFailureCategory.REMOTE_SERVICE_FAILURE,
                    GatewayFailureCategory.LOCAL_REQUEST_BUDGET_EXHAUSTED ->
                        if (outcome == SyncPassOutcome.SUCCESS) SyncPassOutcome.RETRY_WAITING else outcome
                    else -> SyncPassOutcome.ACTION_REQUIRED
                }
            }
        }
        return outcome
    }

    suspend fun unavailable(account: AccountScope, category: GatewayFailureCategory,
        prerequisite: TargetedRecoveryPrerequisite, isCancelled: () -> Boolean) {
        if (isCancelled()) return
        store.recoverInterrupted(account, clock())
        store.eligible(account, clock()).forEach { pending ->
            if (!isCancelled()) store.claim(pending, clock())?.let {
                store.finish(it, TargetedRecoveryWorkResult.Failed(category, prerequisite), clock(), isCancelled)
            }
        }
    }

    /** No extra GET or provider projection after a normal/full pass already treated these items. */
    suspend fun settleAfterOrdinaryPass(account: AccountScope, isCancelled: () -> Boolean) =
        store.settleAfterOrdinaryPass(account, clock(), isCancelled)
}

/** Common ingestion may be necessary before replacing an existing canonical remote baseline.
 * It is local-only and never projects before the current remote card has been read. */
internal suspend fun prepareTargetedProtonRead(
    database: com.patmanak.contako.data.local.ContakoDatabase,
    preflight: AndroidInteroperabilityPreflight,
    androidStage: AndroidInteroperabilityStage,
    request: TargetedRecoveryRequestEntity,
    isCancelled: () -> Boolean,
): TargetedRecoveryWorkResult {
    if (isCancelled()) return TargetedRecoveryWorkResult.Cancelled
    if (request.expectedRevision == null) return TargetedRecoveryWorkResult.Resolved()
    val contacts = database.contactDao().getEntitiesByRemoteContactId(request.accountId, request.targetId)
    val contact = contacts.singleOrNull() ?: return TargetedRecoveryWorkResult.Stale(TargetedRecoveryPrerequisite.REMOTE_IDENTITY)
    if (contact.revision != request.expectedRevision) return TargetedRecoveryWorkResult.Stale()
    val binding = database.androidProjectionLedgerDao().getAccount(request.accountId)
    val ledger = database.androidProjectionLedgerDao().get(request.accountId, contact.id)
    if (binding?.androidAccountName == null && ledger?.rawContactLocator == null)
        return TargetedRecoveryWorkResult.Resolved(contact.revision)
    return when (val ready = preflight.check(AccountScope(request.accountId), isCancelled)) {
        is AndroidInteroperabilityPreflightResult.Ready -> {
            if (ready.context.providerEpoch != request.expectedEpoch) TargetedRecoveryWorkResult.Stale(TargetedRecoveryPrerequisite.ANDROID_CONTEXT)
            else when (androidStage.ingest(ready.context, isCancelled)) {
                AndroidInteroperabilityStageResult.Success -> {
                    val current = database.contactDao().getEntity(request.accountId, contact.id)
                    if (current == null || current.remoteContactId != request.targetId) TargetedRecoveryWorkResult.Stale()
                    else TargetedRecoveryWorkResult.Resolved(current.revision)
                }
                AndroidInteroperabilityStageResult.Cancelled -> TargetedRecoveryWorkResult.Cancelled
                else -> TargetedRecoveryWorkResult.Failed(GatewayFailureCategory.UNKNOWN, TargetedRecoveryPrerequisite.ANDROID_CONTEXT)
            }
        }
        AndroidInteroperabilityPreflightResult.Cancelled -> TargetedRecoveryWorkResult.Cancelled
        AndroidInteroperabilityPreflightResult.PermissionDenied ->
            TargetedRecoveryWorkResult.Failed(GatewayFailureCategory.UNKNOWN, TargetedRecoveryPrerequisite.ANDROID_PERMISSION)
        else -> TargetedRecoveryWorkResult.Failed(GatewayFailureCategory.UNKNOWN, TargetedRecoveryPrerequisite.ANDROID_CONTEXT)
    }
}
