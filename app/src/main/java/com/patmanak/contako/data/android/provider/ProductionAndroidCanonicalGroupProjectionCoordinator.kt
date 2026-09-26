package com.patmanak.contako.data.android.provider

import android.content.ContentResolver
import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.patmanak.contako.data.android.AndroidAdoptionState
import com.patmanak.contako.data.android.AndroidIngestionState
import com.patmanak.contako.data.android.AndroidProjectionWriteState
import com.patmanak.contako.data.android.AndroidTombstoneState
import com.patmanak.contako.data.android.mapping.AndroidGroupSnapshot
import com.patmanak.contako.data.local.AndroidGroupProjectionLedgerEntity
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.data.local.RoomAndroidGroupProviderWriteJournal
import com.patmanak.contako.data.local.toDomain
import com.patmanak.contako.data.sync.AndroidBoundedPageResult
import com.patmanak.contako.data.sync.AndroidCanonicalGroupProjectionCoordinator
import com.patmanak.contako.data.sync.AndroidInteroperabilityContext
import kotlinx.coroutines.CancellationException

/** Projects canonical labels before contact memberships can reference their provider rows. */
internal class ProductionAndroidCanonicalGroupProjectionCoordinator(
    private val database: ContakoDatabase,
    contentResolver: ContentResolver,
) : AndroidCanonicalGroupProjectionCoordinator {
    private val writes = RoomAndroidGroupProviderWriteCoordinator(database, contentResolver)
    private val deletionJournal = RoomAndroidGroupProviderWriteJournal(database)

    override suspend fun project(context: AndroidInteroperabilityContext): AndroidBoundedPageResult {
        return try {
            var providerMutationCommitted = false
            val groups = database.contactGroupDao().getAll(context.account.value).map { it.toDomain() }
            for (canonical in groups) {
                val account = database.androidProjectionLedgerDao().getAccount(context.account.value)
                    ?: return AndroidBoundedPageResult.RepairRequired
                if (account.androidAccountName != context.androidAccountName ||
                    account.providerEpoch != context.providerEpoch
                ) return AndroidBoundedPageResult.ReplanRequired

                var ledger = database.androidGroupProjectionDao().getGroup(context.account.value, canonical.id)
                if (ledger == null) {
                    if (canonical.isDeleted) continue
                    val seed = AndroidGroupProjectionLedgerEntity(
                        accountId = context.account.value,
                        canonicalGroupId = canonical.id,
                        revision = 0,
                        providerEpoch = context.providerEpoch,
                        groupRowLocator = null,
                        providerVersion = null,
                        sourceIdentity = canonical.remoteLabelId,
                        canonicalProjectionFingerprint = null,
                        androidBaselineFingerprint = null,
                        pendingProjectionFingerprint = null,
                        projectionState = AndroidProjectionWriteState.DETACHED.name,
                        ingestionState = AndroidIngestionState.NONE.name,
                        tombstoneState = AndroidTombstoneState.NONE.name,
                        adoptionState = if (canonical.remoteLabelId == null) {
                            AndroidAdoptionState.AWAITING_REMOTE_ID.name
                        } else {
                            AndroidAdoptionState.SOURCE_ID_PENDING.name
                        },
                    )
                    database.withTransaction { database.androidGroupProjectionDao().insertGroup(seed) }
                    ledger = database.androidGroupProjectionDao().getGroup(context.account.value, canonical.id)
                        ?: return AndroidBoundedPageResult.LocalPersistenceFailure
                }
                if (ledger.providerEpoch != context.providerEpoch) return AndroidBoundedPageResult.ReplanRequired

                val desiredFingerprint = if (canonical.isDeleted) null else AndroidGroupSnapshot(
                    context.account.value,
                    canonical.id,
                    canonical.name,
                    canonical.isVisible,
                ).semanticFingerprint().sha256Hex
                if (!canonical.isDeleted && ledger.groupRowLocator != null && ledger.providerVersion != null &&
                    ledger.projectionState == AndroidProjectionWriteState.CLEAN.name &&
                    ledger.pendingProjectionFingerprint == null &&
                    ledger.canonicalProjectionFingerprint == desiredFingerprint &&
                    ledger.sourceIdentity == canonical.remoteLabelId
                ) continue
                if (canonical.isDeleted && ledger.groupRowLocator == null) continue
                if (canonical.isDeleted) {
                    // Contacts must first project their removed memberships while the old group
                    // is still a trusted live binding. Final projection revisits this gate.
                    ledger = deletionJournal.prepareCanonicalDeletion(ledger, canonical.revision) ?: continue
                }

                val currentAccount = database.androidProjectionLedgerDao().getAccount(context.account.value)
                    ?: return AndroidBoundedPageResult.RepairRequired
                val operation = when {
                    canonical.isDeleted -> AndroidGroupProviderOperation.DELETE
                    ledger.groupRowLocator == null -> AndroidGroupProviderOperation.CREATE
                    else -> AndroidGroupProviderOperation.UPDATE
                }
                val authorization = AndroidGroupWriteAuthorization(
                    context = AndroidGroupWriteContext(
                        accountId = context.account.value,
                        providerEpoch = context.providerEpoch,
                        expectedAccountRevision = currentAccount.revision,
                        expectedCanonicalGroupRevision = canonical.revision,
                        expectedGroupLedgerRevision = ledger.revision,
                    ),
                    accountName = AndroidProviderAccountName(context.androidAccountName),
                    canonicalGroupId = canonical.id,
                    operation = operation,
                    expectedGroupRowId = ledger.groupRowLocator,
                    expectedProviderVersion = ledger.providerVersion,
                    expectedSourceIdentity = ledger.sourceIdentity.toExpectedIdentity(),
                    sourceIdentityAfterWrite = canonical.remoteLabelId,
                    expectedDeleted = false,
                    desiredTitle = canonical.name.takeUnless { canonical.isDeleted },
                    desiredVisibility = canonical.isVisible.takeUnless { canonical.isDeleted },
                )
                val execution = writes.execute(authorization) { gateway ->
                    when (operation) {
                        AndroidGroupProviderOperation.CREATE -> gateway.ensureOwnedGroup(
                            authorization.context,
                            authorization.accountName,
                            authorization.canonicalGroupId,
                            authorization.sourceIdentityAfterWrite,
                            requireNotNull(authorization.desiredTitle),
                            requireNotNull(authorization.desiredVisibility),
                        )
                        AndroidGroupProviderOperation.UPDATE -> gateway.applyProjection(
                            authorization.context,
                            authorization.accountName,
                            authorization.canonicalGroupId,
                            requireNotNull(authorization.expectedGroupRowId),
                            requireNotNull(authorization.expectedProviderVersion),
                            authorization.expectedSourceIdentity,
                            authorization.sourceIdentityAfterWrite,
                            requireNotNull(authorization.desiredTitle),
                            requireNotNull(authorization.desiredVisibility),
                        )
                        AndroidGroupProviderOperation.DELETE -> gateway.deleteOwnedGroup(
                            authorization.context,
                            authorization.accountName,
                            authorization.canonicalGroupId,
                            requireNotNull(authorization.expectedGroupRowId),
                            requireNotNull(authorization.expectedProviderVersion),
                            authorization.expectedSourceIdentity,
                            authorization.expectedDeleted,
                        ).also { result ->
                            check(result !is AndroidDeleteGroupResult.AbsentRequiresDurableIntent)
                        }
                        AndroidGroupProviderOperation.ADOPT,
                        AndroidGroupProviderOperation.ACKNOWLEDGE,
                        -> error("Observation operations are not canonical projection operations")
                    }
                }
                when (execution) {
                    is AndroidGroupProviderWriteExecutionResult.Completed,
                    is AndroidGroupProviderWriteExecutionResult.AlreadyCompleted,
                    -> providerMutationCommitted = true
                    AndroidGroupProviderWriteExecutionResult.Stale -> return AndroidBoundedPageResult.ReplanRequired
                    AndroidGroupProviderWriteExecutionResult.Busy,
                    AndroidGroupProviderWriteExecutionResult.ProviderConflict,
                    -> return AndroidBoundedPageResult.RepairRequired
                }
            }
            if (providerMutationCommitted) AndroidBoundedPageResult.ReplanRequired else AndroidBoundedPageResult.Applied
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SQLiteException) {
            AndroidBoundedPageResult.LocalPersistenceFailure
        } catch (failure: AndroidGroupLifecycleException) {
            when (failure.category) {
                AndroidGroupLifecycleFailure.AUTHORIZATION_STALE -> AndroidBoundedPageResult.ReplanRequired
                else -> AndroidBoundedPageResult.RepairRequired
            }
        } catch (_: IllegalArgumentException) {
            AndroidBoundedPageResult.RepairRequired
        } catch (_: IllegalStateException) {
            AndroidBoundedPageResult.RepairRequired
        }
    }

    private fun String?.toExpectedIdentity(): AndroidExpectedSourceIdentity = if (this == null) {
        AndroidExpectedSourceIdentity.Missing
    } else {
        AndroidExpectedSourceIdentity.Present(this)
    }
}
