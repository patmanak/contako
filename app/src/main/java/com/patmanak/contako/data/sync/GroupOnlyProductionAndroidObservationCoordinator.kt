package com.patmanak.contako.data.sync

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.patmanak.contako.data.android.mapping.AndroidGroupSnapshot
import com.patmanak.contako.data.android.provider.AndroidAdoptGroupResult
import com.patmanak.contako.data.android.provider.AndroidExpectedSourceIdentity
import com.patmanak.contako.data.android.provider.AndroidGroupLifecycleException
import com.patmanak.contako.data.android.provider.AndroidGroupLifecycleFailure
import com.patmanak.contako.data.android.provider.AndroidGroupProviderOperation
import com.patmanak.contako.data.android.provider.AndroidGroupProviderWriteExecutionResult
import com.patmanak.contako.data.android.provider.AndroidGroupWriteAuthorization
import com.patmanak.contako.data.android.provider.AndroidGroupWriteContext
import com.patmanak.contako.data.android.provider.AndroidOwnedGroupRow
import com.patmanak.contako.data.android.provider.AndroidOwnedGroupRowPage
import com.patmanak.contako.data.android.provider.AndroidProviderAccountName
import com.patmanak.contako.data.android.provider.AndroidProviderAcknowledgementResult
import com.patmanak.contako.data.android.provider.AndroidProviderBoundaryException
import com.patmanak.contako.data.android.provider.AndroidProviderFailureCategory
import com.patmanak.contako.data.android.provider.AndroidStableRawContactObservationPage
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.data.local.RoomAndroidGroupRowObservationCommand
import com.patmanak.contako.data.local.RoomAndroidGroupRowObservationCommitter
import com.patmanak.contako.data.local.RoomAndroidGroupRowObservationResult
import com.patmanak.contako.data.local.RoomAndroidGroupCommitRepairObserver
import com.patmanak.contako.data.local.RoomContactRepository
import com.patmanak.contako.data.local.toDomain
import java.util.UUID
import kotlinx.coroutines.CancellationException

internal enum class AndroidGroupObservationProviderAction { NONE, ADOPT, ACKNOWLEDGE }

internal data class AndroidGroupObservationPlan(
    val command: RoomAndroidGroupRowObservationCommand,
    val providerAction: AndroidGroupObservationProviderAction,
)

internal sealed interface AndroidGroupObservationPlanResult {
    data class Ready(val plan: AndroidGroupObservationPlan) : AndroidGroupObservationPlanResult
    data object ReplanRequired : AndroidGroupObservationPlanResult
    data object RepairRequired : AndroidGroupObservationPlanResult
    data object LocalPersistenceFailure : AndroidGroupObservationPlanResult
}

internal fun interface AndroidGroupObservationPlanner {
    suspend fun plan(
        context: AndroidInteroperabilityContext,
        row: AndroidOwnedGroupRow,
    ): AndroidGroupObservationPlanResult
}

internal fun interface AndroidGroupObservationCommitAuthority {
    suspend fun commit(command: RoomAndroidGroupRowObservationCommand): RoomAndroidGroupRowObservationResult
}

internal fun interface AndroidGroupObservationProviderFinalizer {
    suspend fun finalize(
        context: AndroidInteroperabilityContext,
        row: AndroidOwnedGroupRow,
        plan: AndroidGroupObservationPlan,
    ): AndroidBoundedPageResult
}

/**
 * Production H04-04 Groups ingestion boundary.
 *
 * Contact ingestion is delegated so the Groups catalog and contact transaction authorities remain
 * independently testable.
 */
internal class GroupOnlyProductionAndroidBoundedObservationCoordinator private constructor(
    private val planner: AndroidGroupObservationPlanner,
    private val committer: AndroidGroupObservationCommitAuthority,
    private val providerFinalizer: AndroidGroupObservationProviderFinalizer,
    private val contactCoordinator: AndroidProductionContactObservationCoordinator =
        AndroidProductionContactObservationCoordinator.FAIL_CLOSED,
    private val actionRequiredObserver: AndroidIngestActionRequiredObserver =
        AndroidIngestActionRequiredObserver { },
) : AndroidBoundedObservationCoordinator {

    override suspend fun ingestGroups(
        context: AndroidInteroperabilityContext,
        pages: List<AndroidOwnedGroupRowPage>,
    ): AndroidBoundedPageResult {
        if (!hasExactPageChain(context, pages)) {
            return repair(AndroidIngestActionRequiredReason.GROUP_PAGE_CHAIN)
        }
        for (page in pages) {
            for (row in page.groups) {
                val plan = when (val result = planner.plan(context, row)) {
                    is AndroidGroupObservationPlanResult.Ready -> result.plan
                    AndroidGroupObservationPlanResult.ReplanRequired ->
                        return AndroidBoundedPageResult.ReplanRequired
                    AndroidGroupObservationPlanResult.RepairRequired ->
                        return repair(AndroidIngestActionRequiredReason.GROUP_PLAN)
                    AndroidGroupObservationPlanResult.LocalPersistenceFailure ->
                        return AndroidBoundedPageResult.LocalPersistenceFailure
                }
                when (committer.commit(plan.command)) {
                    is RoomAndroidGroupRowObservationResult.Applied,
                    RoomAndroidGroupRowObservationResult.AlreadyCommitted,
                    -> Unit
                    RoomAndroidGroupRowObservationResult.RepairRequired ->
                        return repair(AndroidIngestActionRequiredReason.GROUP_COMMIT)
                    RoomAndroidGroupRowObservationResult.StaleAccount,
                    RoomAndroidGroupRowObservationResult.StaleProviderEpoch,
                    RoomAndroidGroupRowObservationResult.StaleProviderRow,
                    RoomAndroidGroupRowObservationResult.StaleCanonicalGroup,
                    RoomAndroidGroupRowObservationResult.StaleGroupLedger,
                    -> return AndroidBoundedPageResult.ReplanRequired
                }
                // Provider DIRTY is touched only after the Room committer returned durable success.
                when (val finalized = providerFinalizer.finalize(context, row, plan)) {
                    AndroidBoundedPageResult.Applied -> Unit
                    AndroidBoundedPageResult.RepairRequired ->
                        return repair(AndroidIngestActionRequiredReason.GROUP_PROVIDER_FINALIZATION)
                    else -> return finalized
                }
            }
        }
        contactCoordinator.acceptGroupCatalog(context, pages)
        return AndroidBoundedPageResult.Applied
    }

    override suspend fun ingestContacts(
        context: AndroidInteroperabilityContext,
        page: AndroidStableRawContactObservationPage,
    ): AndroidBoundedPageResult = contactCoordinator.ingest(context, page)

    private fun repair(reason: AndroidIngestActionRequiredReason): AndroidBoundedPageResult.RepairRequired =
        AndroidBoundedPageResult.RepairRequired.also {
            runCatching { actionRequiredObserver.onActionRequired(reason) }
        }

    private fun hasExactPageChain(
        context: AndroidInteroperabilityContext,
        pages: List<AndroidOwnedGroupRowPage>,
    ): Boolean {
        if (pages.isEmpty()) return false
        var expectedAfter = 0L
        var previousRow = 0L
        pages.forEachIndexed { index, page ->
            if (page.accountName.value != context.androidAccountName ||
                page.requestedAfterGroupRowId != expectedAfter ||
                page.groups.any { it.groupRowId <= previousRow }
            ) return false
            if (page.groups.isNotEmpty()) previousRow = page.groups.last().groupRowId
            if (index < pages.lastIndex && page.nextAfterGroupRowId == null) return false
            if (index == pages.lastIndex && page.nextAfterGroupRowId != null) return false
            expectedAfter = page.nextAfterGroupRowId ?: 0L
        }
        return true
    }

    internal companion object {
        fun forTest(
            planner: AndroidGroupObservationPlanner,
            committer: AndroidGroupObservationCommitAuthority,
            providerFinalizer: AndroidGroupObservationProviderFinalizer,
            contactCoordinator: AndroidProductionContactObservationCoordinator =
                AndroidProductionContactObservationCoordinator.FAIL_CLOSED,
            actionRequiredObserver: AndroidIngestActionRequiredObserver =
                AndroidIngestActionRequiredObserver { },
        ) = GroupOnlyProductionAndroidBoundedObservationCoordinator(
            planner, committer, providerFinalizer, contactCoordinator, actionRequiredObserver,
        )

        fun compose(
            database: ContakoDatabase,
            repository: RoomContactRepository,
            groupIdFactory: () -> UUID = UUID::randomUUID,
            providerFinalizer: AndroidGroupObservationProviderFinalizer,
            contactCoordinator: AndroidProductionContactObservationCoordinator =
                AndroidProductionContactObservationCoordinator.FAIL_CLOSED,
            actionRequiredObserver: AndroidIngestActionRequiredObserver =
                AndroidIngestActionRequiredObserver { },
            groupCommitRepairObserver: RoomAndroidGroupCommitRepairObserver =
                RoomAndroidGroupCommitRepairObserver { },
        ) = GroupOnlyProductionAndroidBoundedObservationCoordinator(
            RoomAndroidGroupObservationPlanner(database, groupIdFactory),
            AndroidGroupObservationCommitAuthority(
                RoomAndroidGroupRowObservationCommitter(
                    database,
                    repository,
                    repairObserver = groupCommitRepairObserver,
                )::commit,
            ),
            providerFinalizer,
            contactCoordinator,
            actionRequiredObserver,
        )
    }
}

private class RoomAndroidGroupObservationPlanner(
    private val database: ContakoDatabase,
    private val groupIdFactory: () -> UUID,
) : AndroidGroupObservationPlanner {
    override suspend fun plan(
        context: AndroidInteroperabilityContext,
        row: AndroidOwnedGroupRow,
    ): AndroidGroupObservationPlanResult = try {
        database.withTransaction {
            val account = database.androidProjectionLedgerDao().getAccount(context.account.value)
                ?: return@withTransaction AndroidGroupObservationPlanResult.RepairRequired
            if (account.androidAccountName != context.androidAccountName ||
                account.providerEpoch != context.providerEpoch
            ) return@withTransaction AndroidGroupObservationPlanResult.ReplanRequired
            if (row.readOnly || !row.shouldSync) {
                return@withTransaction AndroidGroupObservationPlanResult.RepairRequired
            }

            val groupDao = database.androidGroupProjectionDao()
            val byLocator = groupDao.getGroupByLocator(
                context.account.value,
                context.providerEpoch,
                row.groupRowId,
            )
            val claimedId = row.canonicalGroupIdClaim
            val canonicalGroupId = when {
                claimedId != null -> claimedId
                byLocator != null -> byLocator.canonicalGroupId
                row.sourceIdentity != null || row.deleted ->
                    return@withTransaction AndroidGroupObservationPlanResult.RepairRequired
                else -> groupIdFactory().toString()
            }
            val canonical = database.contactGroupDao().get(context.account.value, canonicalGroupId)?.toDomain()
            if ((claimedId != null || byLocator != null) && canonical == null) {
                return@withTransaction AndroidGroupObservationPlanResult.RepairRequired
            }
            if (canonical?.remoteLabelId != row.sourceIdentity) {
                return@withTransaction AndroidGroupObservationPlanResult.RepairRequired
            }

            val ledger = groupDao.getGroup(context.account.value, canonicalGroupId)
            if (byLocator != null && byLocator.canonicalGroupId != canonicalGroupId) {
                return@withTransaction AndroidGroupObservationPlanResult.RepairRequired
            }
            if (ledger != null && (ledger.providerEpoch != context.providerEpoch ||
                    ledger.groupRowLocator != row.groupRowId || ledger.sourceIdentity != row.sourceIdentity)
            ) return@withTransaction AndroidGroupObservationPlanResult.ReplanRequired

            val command = RoomAndroidGroupRowObservationCommand(
                account = context.account,
                androidAccountName = context.androidAccountName,
                expectedAccountRevision = account.revision,
                providerEpoch = account.providerEpoch,
                canonicalGroupId = canonicalGroupId,
                expectedCanonicalGroupRevision = canonical?.revision,
                expectedGroupLedgerRevision = ledger?.revision,
                groupRowLocator = row.groupRowId,
                providerVersion = row.version,
                sourceIdentity = row.sourceIdentity,
                deleted = row.deleted,
                observedSnapshot = if (row.deleted) null else AndroidGroupSnapshot(
                    context.account.value,
                    canonicalGroupId,
                    row.title,
                    row.visible,
                ),
            )
            AndroidGroupObservationPlanResult.Ready(AndroidGroupObservationPlan(
                command,
                when {
                    claimedId == null -> AndroidGroupObservationProviderAction.ADOPT
                    row.dirty && !row.deleted -> AndroidGroupObservationProviderAction.ACKNOWLEDGE
                    else -> AndroidGroupObservationProviderAction.NONE
                },
            ))
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SQLiteException) {
        AndroidGroupObservationPlanResult.LocalPersistenceFailure
    }

}

internal class RoomAndroidGroupObservationProviderFinalizer(
    private val database: ContakoDatabase,
    private val writes: AndroidGroupObservationProviderWriteExecutor,
) : AndroidGroupObservationProviderFinalizer {
    override suspend fun finalize(
        context: AndroidInteroperabilityContext,
        row: AndroidOwnedGroupRow,
        plan: AndroidGroupObservationPlan,
    ): AndroidBoundedPageResult {
        if (plan.providerAction == AndroidGroupObservationProviderAction.NONE) {
            return AndroidBoundedPageResult.Applied
        }
        val authorization = try {
            database.withTransaction { currentAuthorization(context, row, plan) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SQLiteException) {
            return AndroidBoundedPageResult.LocalPersistenceFailure
        } ?: return AndroidBoundedPageResult.ReplanRequired

        return try {
            val execution = writes.execute(authorization) { gateway ->
                when (plan.providerAction) {
                    AndroidGroupObservationProviderAction.ADOPT -> {
                        when (gateway.adoptUnclaimedGroup(
                            authorization.context,
                            authorization.accountName,
                            row.groupRowId,
                            row.version,
                            authorization.canonicalGroupId,
                        )) {
                            is AndroidAdoptGroupResult.Adopted,
                            is AndroidAdoptGroupResult.RecoveredAfterLostAcknowledgement,
                            -> Unit
                            AndroidAdoptGroupResult.Stale -> Unit
                        }
                    }
                    AndroidGroupObservationProviderAction.ACKNOWLEDGE -> {
                        when (gateway.acknowledgeObservation(
                            authorization.context,
                            authorization.accountName,
                            authorization.canonicalGroupId,
                            row.groupRowId,
                            row.version,
                            authorization.expectedSourceIdentity,
                        )) {
                            AndroidProviderAcknowledgementResult.Acknowledged,
                            AndroidProviderAcknowledgementResult.Stale,
                            -> Unit
                        }
                    }
                    AndroidGroupObservationProviderAction.NONE -> error("Provider action was already filtered")
                }
            }
            when (execution) {
                is AndroidGroupProviderWriteExecutionResult.Completed,
                is AndroidGroupProviderWriteExecutionResult.AlreadyCompleted,
                -> AndroidBoundedPageResult.Applied
                AndroidGroupProviderWriteExecutionResult.Stale -> AndroidBoundedPageResult.ReplanRequired
                AndroidGroupProviderWriteExecutionResult.Busy,
                AndroidGroupProviderWriteExecutionResult.ProviderConflict,
                -> AndroidBoundedPageResult.RepairRequired
            }
        } catch (failure: AndroidGroupLifecycleException) {
            when (failure.category) {
                AndroidGroupLifecycleFailure.PERMISSION_DENIED -> throw AndroidProviderBoundaryException(
                    AndroidProviderFailureCategory.PERMISSION_DENIED,
                )
                AndroidGroupLifecycleFailure.PROVIDER_UNAVAILABLE -> throw AndroidProviderBoundaryException(
                    AndroidProviderFailureCategory.PROVIDER_UNAVAILABLE,
                )
                AndroidGroupLifecycleFailure.AUTHORIZATION_STALE -> AndroidBoundedPageResult.ReplanRequired
                else -> AndroidBoundedPageResult.RepairRequired
            }
        }
    }

    private suspend fun currentAuthorization(
        context: AndroidInteroperabilityContext,
        row: AndroidOwnedGroupRow,
        plan: AndroidGroupObservationPlan,
    ): AndroidGroupWriteAuthorization? {
        val account = database.androidProjectionLedgerDao().getAccount(context.account.value) ?: return null
        val canonicalGroupId = plan.command.canonicalGroupId
        val canonical = database.contactGroupDao().get(context.account.value, canonicalGroupId)?.toDomain()
            ?: return null
        val ledger = database.androidGroupProjectionDao().getGroup(context.account.value, canonicalGroupId)
            ?: return null
        if (account.androidAccountName != context.androidAccountName ||
            account.providerEpoch != context.providerEpoch || ledger.providerEpoch != context.providerEpoch ||
            ledger.groupRowLocator != row.groupRowId || ledger.providerVersion != row.version ||
            ledger.sourceIdentity != row.sourceIdentity || canonical.remoteLabelId != row.sourceIdentity ||
            canonical.isDeleted || canonical.name != row.title || canonical.isVisible != row.visible
        ) return null
        return AndroidGroupWriteAuthorization(
            context = AndroidGroupWriteContext(
                context.account.value,
                account.providerEpoch,
                account.revision,
                canonical.revision,
                ledger.revision,
            ),
            accountName = AndroidProviderAccountName(context.androidAccountName),
            canonicalGroupId = canonicalGroupId,
            operation = when (plan.providerAction) {
                AndroidGroupObservationProviderAction.ADOPT -> AndroidGroupProviderOperation.ADOPT
                AndroidGroupObservationProviderAction.ACKNOWLEDGE -> AndroidGroupProviderOperation.ACKNOWLEDGE
                AndroidGroupObservationProviderAction.NONE -> return null
            },
            expectedGroupRowId = row.groupRowId,
            expectedProviderVersion = row.version,
            expectedSourceIdentity = row.sourceIdentity.toExpectedSource(),
            sourceIdentityAfterWrite = row.sourceIdentity,
            expectedDeleted = false,
            desiredTitle = row.title,
            desiredVisibility = row.visible,
        )
    }

    private fun String?.toExpectedSource(): AndroidExpectedSourceIdentity = if (this == null) {
        AndroidExpectedSourceIdentity.Missing
    } else {
        AndroidExpectedSourceIdentity.Present(this)
    }
}

internal fun interface AndroidGroupObservationProviderWriteExecutor {
    suspend fun execute(
        authorization: AndroidGroupWriteAuthorization,
        providerMutation: suspend (com.patmanak.contako.data.android.provider.AndroidGroupLifecycleGateway) -> Unit,
    ): AndroidGroupProviderWriteExecutionResult
}
