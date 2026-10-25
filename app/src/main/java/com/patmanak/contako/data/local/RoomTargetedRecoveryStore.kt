package com.patmanak.contako.data.local

import androidx.room.withTransaction
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.data.gateway.VerifiedContactCard
import com.patmanak.contako.data.sync.MutationRetryPolicy
import com.patmanak.contako.data.sync.RemoteMutationOperation
import com.patmanak.contako.data.sync.RetryDecision
import com.patmanak.contako.data.sync.TargetedRecoveryExecutionStore
import com.patmanak.contako.data.sync.TargetedRecoveryWorkResult
import com.patmanak.contako.domain.sync.*
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.map

/** Account-owned request metadata; all authority is reconstructed inside guarded transactions. */
internal class RoomTargetedRecoveryStore(private val database: ContakoDatabase,
    private val retryPolicy: MutationRetryPolicy = MutationRetryPolicy()) : TargetedRecoveryExecutionStore {
    private val dao get() = database.targetedRecoveryRequestDao()

    suspend fun preview(account: AccountScope, kind: TargetedRecoveryKind, targetId: String): TargetedRecoveryPreview =
        database.withTransaction { previewCurrent(account.value, kind, targetId) }

    private suspend fun previewCurrent(account: String, kind: TargetedRecoveryKind, target: String): TargetedRecoveryPreview {
        val binding = database.androidProjectionLedgerDao().getAccount(account)
        if (kind == TargetedRecoveryKind.PROTON_CARD) {
            val obligation = database.remoteHydrationObligationDao().get(account, target)
                ?: return TargetedRecoveryPreview(kind, target, "", null, binding?.providerEpoch,
                    TargetedRecoveryPrerequisite.NO_OBLIGATION)
            val matches = database.contactDao().getEntitiesByRemoteContactId(account, target)
            return TargetedRecoveryPreview(kind, target, obligation.generation,
                matches.singleOrNull()?.revision, binding?.providerEpoch,
                if (matches.size > 1) TargetedRecoveryPrerequisite.REMOTE_IDENTITY else null,
                matches.singleOrNull()?.displayName ?: obligation.displayName, matches.singleOrNull()?.isDeleted ?: false)
        }
        val contact = database.contactDao().getEntity(account, target)
            ?: return TargetedRecoveryPreview(kind, target, "", null, binding?.providerEpoch,
                TargetedRecoveryPrerequisite.NO_OBLIGATION)
        val ledger = database.androidProjectionLedgerDao().get(account, target)
        val issues = listOfNotNull(database.androidContactIssueDao().get(account, target, "PROJECTION"),
            database.androidContactIssueDao().get(account, target, "INGEST"))
            .filter { it.providerEpoch == binding?.providerEpoch }
        val pending = (!contact.isDeleted && (ledger == null || ledger.projectionState != "CLEAN" ||
            ledger.pendingProjectionFingerprint != null)) || issues.isNotEmpty()
        val source = listOf(contact.revision, binding?.providerEpoch, ledger?.revision, ledger?.projectionState,
            ledger?.pendingProjectionFingerprint, issues.map { "${it.origin}:${it.canonicalRevision}:${it.reason}" }).joinToString("|")
        val generation = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        return TargetedRecoveryPreview(kind, target, generation, contact.revision, binding?.providerEpoch,
            when { !pending -> TargetedRecoveryPrerequisite.NO_OBLIGATION
                binding?.androidAccountName == null || binding.providerRepairPending ||
                    (ledger != null && ledger.providerEpoch != binding.providerEpoch) -> TargetedRecoveryPrerequisite.ANDROID_CONTEXT
                else -> null }, contact.displayName, contact.isDeleted)
    }

    suspend fun queue(account: AccountScope, expected: TargetedRecoveryPreview, now: Long): TargetedRecoveryQueueResult =
        database.withTransaction {
            val current = previewCurrent(account.value, expected.kind, expected.targetId)
            if (!current.canQueue) return@withTransaction TargetedRecoveryQueueResult.UNAVAILABLE
            if (!sameSource(current, expected.obligationGeneration, expected.canonicalRevision, expected.providerEpoch))
                return@withTransaction TargetedRecoveryQueueResult.STALE
            val existing = dao.get(account.value, expected.kind.name, expected.targetId)
            if (existing?.state == "RUNNING") return@withTransaction TargetedRecoveryQueueResult.ALREADY_PENDING
            if (existing != null && existing.sourceGeneration == current.obligationGeneration &&
                existing.expectedRevision == current.canonicalRevision && existing.expectedEpoch == current.providerEpoch &&
                existing.state !in TERMINAL && (existing.state != "ACTION_REQUIRED" || existing.nextEligibleAt > now))
                return@withTransaction TargetedRecoveryQueueResult.ALREADY_PENDING
            dao.upsert(TargetedRecoveryRequestEntity(account.value, expected.kind.name, expected.targetId,
                UUID.randomUUID().toString(), current.obligationGeneration, current.canonicalRevision, current.providerEpoch,
                TargetedRecoveryState.REQUESTED.name, existing?.attemptCount ?: 0, maxOf(now, existing?.nextEligibleAt ?: now),
                existing?.createdAt ?: now, now, null, null))
            TargetedRecoveryQueueResult.QUEUED
        }

    fun observe(account: AccountScope) = dao.observe(account.value).map { rows -> rows.map { row ->
        val request = row.request
        TargetedRecoveryStatus(TargetedRecoveryKind.valueOf(request.kind), request.targetId,
            TargetedRecoveryState.valueOf(request.state), request.nextEligibleAt.takeIf {
                request.state == TargetedRecoveryState.DEFERRED.name },
            request.prerequisite?.let(TargetedRecoveryPrerequisite::valueOf), row.displayName)
    } }

    suspend fun hasEligible(account: AccountScope, now: Long) = dao.all(account.value).any {
        it.state == TargetedRecoveryState.RUNNING.name ||
            (it.state in setOf("REQUESTED", "DEFERRED") && it.nextEligibleAt <= now)
    }

    override suspend fun recoverInterrupted(account: AccountScope, now: Long) { dao.recoverInterrupted(account.value, now) }
    override suspend fun eligible(account: AccountScope, now: Long) = dao.eligible(account.value, now, 10)

    override suspend fun claim(request: TargetedRecoveryRequestEntity, now: Long): TargetedRecoveryRequestEntity? =
        database.withTransaction {
            val current = dao.get(request.accountId, request.kind, request.targetId) ?: return@withTransaction null
            if (current.requestGeneration != request.requestGeneration || current.state !in setOf("REQUESTED", "DEFERRED") ||
                current.nextEligibleAt > now) return@withTransaction null
            current.copy(state = "RUNNING", claimToken = UUID.randomUUID().toString(),
                attemptCount = current.attemptCount + 1, nextEligibleAt = maxOf(current.nextEligibleAt, now + 30_000),
                updatedAt = now).also { dao.upsert(it) }
        }

    override suspend fun validate(request: TargetedRecoveryRequestEntity): TargetedRecoveryWorkResult? = database.withTransaction {
        if (!claimed(request)) return@withTransaction TargetedRecoveryWorkResult.Cancelled
        val preview = previewCurrent(request.accountId, TargetedRecoveryKind.valueOf(request.kind), request.targetId)
        when {
            preview.prerequisite == TargetedRecoveryPrerequisite.NO_OBLIGATION -> TargetedRecoveryWorkResult.Resolved(preview.canonicalRevision)
            preview.prerequisite != null -> TargetedRecoveryWorkResult.Stale(preview.prerequisite)
            !sameSource(preview, request.sourceGeneration, request.expectedRevision, request.expectedEpoch) -> TargetedRecoveryWorkResult.Stale()
            else -> null
        }
    }

    override suspend fun rebaseAfterIngestion(request: TargetedRecoveryRequestEntity, revision: Long?) = database.withTransaction {
        if (!claimed(request)) return@withTransaction null
        val preview = previewCurrent(request.accountId, TargetedRecoveryKind.valueOf(request.kind), request.targetId)
        if (!preview.canQueue || preview.obligationGeneration != request.sourceGeneration ||
            preview.providerEpoch != request.expectedEpoch || preview.canonicalRevision != revision) return@withTransaction null
        request.copy(expectedRevision = revision).also { dao.upsert(it) }
    }

    override suspend fun completeVerified(request: TargetedRecoveryRequestEntity, card: VerifiedContactCard,
        now: Long, isCancelled: () -> Boolean): TargetedRecoveryWorkResult = database.withTransaction {
        if (isCancelled() || !claimed(request)) return@withTransaction TargetedRecoveryWorkResult.Cancelled
        if (card.id.value != request.targetId || card.contact.accountId != request.accountId)
            return@withTransaction TargetedRecoveryWorkResult.Stale(TargetedRecoveryPrerequisite.REMOTE_IDENTITY)
        val preview = previewCurrent(request.accountId, TargetedRecoveryKind.PROTON_CARD, request.targetId)
        if (!preview.canQueue || !sameSource(preview, request.sourceGeneration, request.expectedRevision, request.expectedEpoch))
            return@withTransaction TargetedRecoveryWorkResult.Stale()
        RoomRemoteCanonicalReconciliationStore(database).reconcileTargetedVerified(AccountScope(request.accountId), card)
        if (isCancelled()) throw kotlinx.coroutines.CancellationException()
        database.remoteHydrationObligationDao().delete(request.accountId, setOf(request.targetId))
        val conflict = database.contactDao().getEntitiesByRemoteContactId(request.accountId, request.targetId)
            .any { it.conflictState != null }
        dao.upsert(request.copy(state = if (conflict) "ACTION_REQUIRED" else "RESOLVED", claimToken = null,
            prerequisite = if (conflict) TargetedRecoveryPrerequisite.CONTACT_CHANGED.name else null, updatedAt = now))
        if (conflict) TargetedRecoveryWorkResult.Failed(GatewayFailureCategory.CONFLICT, TargetedRecoveryPrerequisite.CONTACT_CHANGED)
        else TargetedRecoveryWorkResult.Resolved()
    }

    override suspend fun finish(request: TargetedRecoveryRequestEntity, result: TargetedRecoveryWorkResult,
        now: Long, isCancelled: () -> Boolean) = database.withTransaction {
        if (isCancelled() || !claimed(request)) return@withTransaction
        if (result is TargetedRecoveryWorkResult.Failed) {
            val current = previewCurrent(request.accountId, TargetedRecoveryKind.valueOf(request.kind), request.targetId)
            val internalAndroidReplan = request.kind == TargetedRecoveryKind.ANDROID_COPY.name &&
                current.canonicalRevision == request.expectedRevision && current.providerEpoch == request.expectedEpoch
            if (!sameSource(current, request.sourceGeneration, request.expectedRevision, request.expectedEpoch) && !internalAndroidReplan) {
                dao.upsert(request.copy(state = "STALE", claimToken = null,
                    prerequisite = TargetedRecoveryPrerequisite.CONTACT_CHANGED.name, updatedAt = now))
                return@withTransaction
            }
        }
        var state = "DEFERRED"
        var next = now
        var prerequisite: TargetedRecoveryPrerequisite? = null
        when (result) {
            is TargetedRecoveryWorkResult.Resolved -> {
                if (request.kind == TargetedRecoveryKind.ANDROID_COPY.name) {
                    val revision = database.contactDao().getEntity(request.accountId, request.targetId)?.revision
                    val epoch = database.androidProjectionLedgerDao().getAccount(request.accountId)?.providerEpoch
                    val pending = previewCurrent(request.accountId, TargetedRecoveryKind.ANDROID_COPY, request.targetId)
                    if (revision != result.canonicalRevision || epoch != request.expectedEpoch ||
                        pending.prerequisite != TargetedRecoveryPrerequisite.NO_OBLIGATION) {
                        state = "STALE"; prerequisite = TargetedRecoveryPrerequisite.CONTACT_CHANGED
                    } else state = "RESOLVED"
                } else state = "RESOLVED"
            }
            is TargetedRecoveryWorkResult.Stale -> { state = "STALE"; prerequisite = result.prerequisite }
            TargetedRecoveryWorkResult.Cancelled -> Unit
            is TargetedRecoveryWorkResult.Failed -> {
                prerequisite = result.prerequisite
                val retryCategory = if (result.category == GatewayFailureCategory.LOCAL_REQUEST_BUDGET_EXHAUSTED)
                    GatewayFailureCategory.RATE_LIMITED else result.category
                val decision = retryPolicy.decide(retryCategory, RemoteMutationOperation.UPDATE,
                    (request.attemptCount - 1).coerceAtLeast(0), now, result.retryAfterMillis)
                if (decision is RetryDecision.RetryAt) next = decision.nextEligibleEpochMillis
                else { state = "ACTION_REQUIRED"; next = maxOf(request.nextEligibleAt, now + 30_000) }
            }
        }
        val source = if (request.kind == TargetedRecoveryKind.ANDROID_COPY.name && result is TargetedRecoveryWorkResult.Failed)
            previewCurrent(request.accountId, TargetedRecoveryKind.ANDROID_COPY, request.targetId).obligationGeneration else request.sourceGeneration
        dao.upsert(request.copy(state = state, claimToken = null, nextEligibleAt = next, sourceGeneration = source,
            prerequisite = prerequisite?.name, updatedAt = now))
    }

    override suspend fun settleAfterOrdinaryPass(account: AccountScope, now: Long, isCancelled: () -> Boolean) {
        if (isCancelled()) return
        database.withTransaction {
            for (request in dao.all(account.value).filter { it.state !in TERMINAL }) {
                if (isCancelled()) throw kotlinx.coroutines.CancellationException()
                val preview = previewCurrent(account.value, TargetedRecoveryKind.valueOf(request.kind), request.targetId)
                val conflict = request.kind == TargetedRecoveryKind.PROTON_CARD.name &&
                    database.contactDao().getEntitiesByRemoteContactId(account.value, request.targetId).any { it.conflictState != null }
                val state = when {
                    conflict -> "ACTION_REQUIRED"
                    preview.prerequisite == TargetedRecoveryPrerequisite.NO_OBLIGATION -> "RESOLVED"
                    !sameSource(preview, request.sourceGeneration, request.expectedRevision, request.expectedEpoch) -> "STALE"
                    request.state == "ACTION_REQUIRED" -> "ACTION_REQUIRED"
                    else -> "DEFERRED"
                }
                dao.upsert(request.copy(state = state, claimToken = null, updatedAt = now,
                    nextEligibleAt = maxOf(request.nextEligibleAt, now + 5_000), prerequisite = preview.prerequisite?.name))
            }
        }
    }

    private suspend fun claimed(request: TargetedRecoveryRequestEntity): Boolean {
        val current = dao.get(request.accountId, request.kind, request.targetId) ?: return false
        return current.requestGeneration == request.requestGeneration && current.state == "RUNNING" &&
            current.claimToken != null && current.claimToken == request.claimToken
    }
    private fun sameSource(preview: TargetedRecoveryPreview, generation: String, revision: Long?, epoch: Long?) =
        preview.obligationGeneration == generation && preview.canonicalRevision == revision && preview.providerEpoch == epoch

    private companion object { val TERMINAL = setOf("RESOLVED", "STALE") }
}
