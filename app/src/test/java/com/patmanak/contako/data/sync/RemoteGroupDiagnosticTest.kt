package com.patmanak.contako.data.sync

import com.patmanak.contako.data.gateway.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RemoteGroupDiagnosticTest {
    @Test fun classifiedReadFailureSurvivesAThrowingObserverWithoutChangingOutcome() = runTest {
        var observed: GatewayFailureCategory? = null
        val stage = IncrementalRemoteGroupStage(
            Gateway(GatewayOutcome.Failure(GatewayFailureCategory.MALFORMED_RESPONSE)),
            RemoteGroupReconciliationStore { _, _ -> error("must not commit") },
            failureObserver = { observed = it; error("observer only") },
        )
        assertEquals(RemoteGroupStageResult.ActionRequired, stage.run(AccountScope("synthetic-account")))
        assertEquals(GatewayFailureCategory.MALFORMED_RESPONSE, observed)
    }

    @Test fun localCommitFailureExposesOnlyTheClosedExceptionClass() = runTest {
        var observed: SyncPassExceptionCategory? = null
        val stage = IncrementalRemoteGroupStage(
            Gateway(GatewayOutcome.Success(AvailableContactGroups(emptyList()))),
            RemoteGroupReconciliationStore { _, _ -> throw IllegalStateException("private canary must not be logged") },
            exceptionObserver = { observed = it; error("observer only") },
        )
        assertEquals(RemoteGroupStageResult.LocalPersistenceFailure, stage.run(AccountScope("synthetic-account")))
        assertEquals(SyncPassExceptionCategory.INVALID_STATE, observed)
    }

    private class Gateway(val result: GatewayOutcome<AvailableContactGroups>) : ProtonContactGroupGateway {
        override fun capabilities(): ContactGroupCapabilities = error("unused")
        override suspend fun list(account: AccountScope) = result
        override suspend fun create(account: AccountScope, mutation: ContactGroupMutation.Create): GatewayOutcome<RemoteContactGroup> = error("unused")
        override suspend fun update(account: AccountScope, mutation: ContactGroupMutation.Update): GatewayOutcome<RemoteContactGroup> = error("unused")
        override suspend fun delete(account: AccountScope, mutation: ContactGroupMutation.Delete): GatewayOutcome<Unit> = error("unused")
    }
}
