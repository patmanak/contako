package com.patmanak.contako.data.sync

import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.data.gateway.GatewayOutcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableMutationOrchestratorTest {
    @Test
    fun `03-FAULT lost acknowledgement reconciles before replay and never duplicates upload`() = runTest {
        val store = FakeMutationStore(command())
        val order = mutableListOf<String>()
        var authoritativeRemoteMatch = false
        var uploads = 0
        val orchestrator = orchestrator(
            store,
            prepare = { command ->
                order += "prepare:${command.requiresReconciliation}"
                if (authoritativeRemoteMatch) success(MutationPreparation.AlreadyApplied(ack()))
                else success(MutationPreparation.UploadAllowed)
            },
            upload = {
                order += "upload"
                uploads++
                authoritativeRemoteMatch = true
                failure(GatewayFailureCategory.TIMEOUT)
            },
        )

        val first = orchestrator.drain(ACCOUNT, 1_000)
        assertEquals(1, first.retryWaiting)
        assertEquals(listOf("prepare:false", "upload"), order)
        assertTrue(store.current!!.requiresReconciliation)

        val tooEarly = orchestrator.drain(ACCOUNT, 1_999)
        assertEquals(0, tooEarly.examined)
        val reconciled = orchestrator.drain(ACCOUNT, 2_000)

        assertEquals(1, reconciled.reconciledWithoutUpload)
        assertEquals(1, uploads)
        assertEquals(listOf("prepare:false", "upload", "prepare:true"), order)
        assertEquals(null, store.current)
    }

    @Test
    fun `03-ORDER every upload is preceded by current remote preparation`() = runTest {
        val store = FakeMutationStore(command(operation = RemoteMutationOperation.DELETE))
        val order = mutableListOf<String>()
        val orchestrator = orchestrator(
            store,
            prepare = {
                order += "remote-check"
                success(MutationPreparation.UploadAllowed)
            },
            upload = {
                order += "write"
                success(ack())
            },
        )

        val result = orchestrator.drain(ACCOUNT, 1_000)

        assertEquals(listOf("remote-check", "write"), order)
        assertEquals(1, result.uploaded)
        assertEquals(null, store.current)
    }

    @Test
    fun `03-DELETE remote absence converges without a retry`() = runTest {
        val store = FakeMutationStore(command(operation = RemoteMutationOperation.DELETE))
        var uploads = 0
        val orchestrator = orchestrator(
            store,
            prepare = { success(MutationPreparation.UploadAllowed) },
            upload = {
                uploads++
                failure(GatewayFailureCategory.NOT_FOUND)
            },
        )

        val result = orchestrator.drain(ACCOUNT, 1_000)

        assertEquals(1, result.reconciledWithoutUpload)
        assertEquals(1, uploads)
        assertEquals(null, store.current)
    }

    @Test
    fun `03-CANCEL cancellation at both safe write boundaries preserves pending intent`() = runTest {
        listOf(1, 3).forEach { cancelAtCheck ->
            val store = FakeMutationStore(command())
            var checks = 0
            var prepareCalls = 0
            var uploadCalls = 0
            val result = orchestrator(
                store,
                prepare = {
                    prepareCalls++
                    success(MutationPreparation.UploadAllowed)
                },
                upload = {
                    uploadCalls++
                    success(ack())
                },
            ).drain(ACCOUNT, 1_000) { ++checks >= cancelAtCheck }

            assertTrue(result.cancelled)
            assertTrue(store.current != null)
            assertEquals(0, uploadCalls)
            assertEquals(if (cancelAtCheck == 1) 0 else 1, prepareCalls)
        }
    }

    @Test
    fun `03-FAULT rate guidance and attempt ceiling become durable action required`() = runTest {
        val guided = FakeMutationStore(command(attemptCount = 0))
        val policy = MutationRetryPolicy(1_000, 60_000, maximumAutomaticAttempts = 1)
        val gateway = MutationPreparationGateway {
            GatewayOutcome.Failure(GatewayFailureCategory.RATE_LIMITED, retryAfterMillis = 9_000)
        }
        val first = DurableMutationOrchestrator(guided, gateway, { error("NO_UPLOAD") }, policy).drain(ACCOUNT, 10_000)
        assertEquals(1, first.retryWaiting)
        assertEquals(19_000, guided.current!!.nextEligibleEpochMillis)

        val terminal = DurableMutationOrchestrator(guided, gateway, { error("NO_UPLOAD") }, policy)
            .drain(ACCOUNT, 19_000)
        assertEquals(1, terminal.actionRequired)
        assertTrue(guided.actionRequired)
    }

    @Test
    fun `03-FAULT interrupted process state is recovered before enumeration`() = runTest {
        val store = FakeMutationStore(command(), initiallyInFlight = true)
        var preparedReconciliation = false
        val result = orchestrator(
            store,
            prepare = { command ->
                preparedReconciliation = command.requiresReconciliation
                success(MutationPreparation.AlreadyApplied(ack()))
            },
            upload = { error("NO_UPLOAD") },
        ).drain(ACCOUNT, 1_000)

        assertTrue(store.recovered)
        assertTrue(preparedReconciliation)
        assertEquals(1, result.reconciledWithoutUpload)
    }

    @Test
    fun `03-STATUS validation and overlapping delete conflict require action without write`() = runTest {
        listOf(
            MutationPreparationGateway {
                success(
                    MutationPreparation.ActionRequired(
                        MutationPreparationActionRequiredReason.UNSPECIFIED,
                    ),
                )
            },
            MutationPreparationGateway { failure(GatewayFailureCategory.VALIDATION_REJECTED) },
        ).forEach { preparation ->
            val store = FakeMutationStore(command())
            var uploads = 0
            val result = DurableMutationOrchestrator(store, preparation, { uploads++; success(ack()) })
                .drain(ACCOUNT, 1_000)

            assertEquals(1, result.actionRequired)
            assertTrue(store.actionRequired)
            assertEquals(0, uploads)
        }
    }

    @Test
    fun `08-DIAGNOSTIC action-required observer receives closed causes only`() = runTest {
        val preparationDiagnostics = mutableListOf<MutationActionRequiredDiagnostic>()
        val preparationStore = FakeMutationStore(command(operation = RemoteMutationOperation.CREATE))
        DurableMutationOrchestrator(
            preparationStore,
            MutationPreparationGateway {
                success(
                    MutationPreparation.ActionRequired(
                        MutationPreparationActionRequiredReason.REVISION_MISMATCH,
                    ),
                )
            },
            MutationUploadGateway { error("NO_UPLOAD") },
            actionRequiredObserver = MutationActionRequiredObserver(preparationDiagnostics::add),
        ).drain(ACCOUNT, 1_000)

        assertEquals(
            MutationActionRequiredDiagnostic(
                MutationActionRequiredSource.PREPARATION_DECISION,
                RemoteMutationOperation.CREATE,
                failureCategory = null,
                preparationReason = MutationPreparationActionRequiredReason.REVISION_MISMATCH,
            ),
            preparationDiagnostics.single(),
        )

        val uploadDiagnostics = mutableListOf<MutationActionRequiredDiagnostic>()
        val uploadStore = FakeMutationStore(command(operation = RemoteMutationOperation.UPDATE))
        DurableMutationOrchestrator(
            uploadStore,
            MutationPreparationGateway { success(MutationPreparation.UploadAllowed) },
            MutationUploadGateway { failure(GatewayFailureCategory.VALIDATION_REJECTED) },
            actionRequiredObserver = MutationActionRequiredObserver(uploadDiagnostics::add),
        ).drain(ACCOUNT, 1_000)

        assertEquals(
            MutationActionRequiredDiagnostic(
                MutationActionRequiredSource.UPLOAD_FAILURE,
                RemoteMutationOperation.UPDATE,
                failureCategory = GatewayFailureCategory.VALIDATION_REJECTED,
                preparationReason = null,
            ),
            uploadDiagnostics.single(),
        )
    }

    @Test
    fun `08-DIAGNOSTIC observer failure cannot alter durable processing`() = runTest {
        val store = FakeMutationStore(command())
        val result = DurableMutationOrchestrator(
            store,
            MutationPreparationGateway {
                success(
                    MutationPreparation.ActionRequired(
                        MutationPreparationActionRequiredReason.UNSPECIFIED,
                    ),
                )
            },
            MutationUploadGateway { error("NO_UPLOAD") },
            actionRequiredObserver = MutationActionRequiredObserver { error("PRIVATE_REMOTE_TEXT") },
        ).drain(ACCOUNT, 1_000)

        assertEquals(1, result.actionRequired)
        assertTrue(store.actionRequired)
    }

    @Test
    fun `03-FAULT batch is bounded and serial`() = runTest {
        val store = FakeMutationStore(*(1..150).map { command(id = "aggregate-$it") }.toTypedArray())
        var concurrent = 0
        var maximumConcurrent = 0
        val result = DurableMutationOrchestrator(
            store,
            preparationGateway = {
                concurrent++
                maximumConcurrent = maxOf(maximumConcurrent, concurrent)
                concurrent--
                success(MutationPreparation.UploadAllowed)
            },
            uploadGateway = { success(ack()) },
            batchLimit = 100,
        ).drain(ACCOUNT, 1_000)

        assertEquals(100, result.examined)
        assertEquals(50, store.remaining)
        assertEquals(1, maximumConcurrent)
    }

    @Test
    fun `03-FAULT partial aggregate progress becomes an immediate durable follow up`() = runTest {
        val store = FakeMutationStore(command(operation = RemoteMutationOperation.CREATE))
        val result = orchestrator(
            store,
            prepare = { success(MutationPreparation.UploadAllowed) },
            upload = {
                success(
                    RemoteMutationAcknowledgement(
                        "remote",
                        "version",
                        fullyConverged = false,
                        nextOperation = RemoteMutationOperation.ASSIGNMENTS,
                    ),
                )
            },
        ).drain(ACCOUNT, 1_000)

        assertEquals(1, result.progressPending)
        assertEquals(RemoteMutationOperation.ASSIGNMENTS, store.current?.operation)
        assertEquals(0, store.current?.attemptCount)
        assertFalse(store.current?.requiresReconciliation ?: true)
    }

    private fun orchestrator(
        store: FakeMutationStore,
        prepare: suspend (DurableMutationCommand) -> GatewayOutcome<MutationPreparation>,
        upload: suspend (DurableMutationCommand) -> GatewayOutcome<RemoteMutationAcknowledgement>,
    ) = DurableMutationOrchestrator(
        store,
        MutationPreparationGateway(prepare),
        MutationUploadGateway(upload),
        retryPolicy = MutationRetryPolicy(1_000, 60_000, 3),
    )

    private fun command(
        id: String = "aggregate",
        operation: RemoteMutationOperation = RemoteMutationOperation.UPDATE,
        attemptCount: Int = 0,
    ) = DurableMutationCommand(
        accountId = ACCOUNT,
        aggregateType = "CONTACT",
        aggregateId = id,
        revision = 1,
        operation = operation,
        attemptCount = attemptCount,
        nextEligibleEpochMillis = 0,
        requiresReconciliation = false,
    )

    private fun ack() = RemoteMutationAcknowledgement("remote", "version")
    private fun <T> success(value: T): GatewayOutcome<T> = GatewayOutcome.Success(value)
    private fun failure(category: GatewayFailureCategory): GatewayOutcome.Failure = GatewayOutcome.Failure(category)

    private companion object {
        const val ACCOUNT = "account"
    }
}

private class FakeMutationStore(
    vararg initial: DurableMutationCommand,
    initiallyInFlight: Boolean = false,
) : MutationExecutionStore {
    private val commands = initial.associateBy { it.aggregateId }.toMutableMap()
    private val inFlight = mutableSetOf<String>().apply {
        if (initiallyInFlight) addAll(initial.map { it.aggregateId })
    }
    var recovered = false
    var actionRequired = false
    val current: DurableMutationCommand? get() = commands.values.singleOrNull()
    val remaining: Int get() = commands.size

    override suspend fun recoverInterrupted(accountId: String, nowEpochMillis: Long): Int {
        val count = inFlight.size
        if (count > 0) recovered = true
        inFlight.toList().forEach { id ->
            val current = requireNotNull(commands[id])
            commands[id] = current.replacement(
                nextEligibleEpochMillis = nowEpochMillis,
                requiresReconciliation = true,
            )
        }
        inFlight.clear()
        return count
    }

    override suspend fun eligible(accountId: String, nowEpochMillis: Long, limit: Int): List<DurableMutationCommand> =
        commands.values.filter { !actionRequired && it.nextEligibleEpochMillis <= nowEpochMillis }.take(limit)

    override suspend fun claim(command: DurableMutationCommand, nowEpochMillis: Long): Boolean =
        commands[command.aggregateId]?.revision == command.revision && inFlight.add(command.aggregateId)

    override suspend fun recordFailure(
        command: DurableMutationCommand,
        category: GatewayFailureCategory,
        decision: RetryDecision,
    ): Boolean {
        if (!inFlight.remove(command.aggregateId)) return false
        when (decision) {
            is RetryDecision.RetryAt -> commands[command.aggregateId] = command.replacement(
                attemptCount = decision.attemptCount,
                nextEligibleEpochMillis = decision.nextEligibleEpochMillis,
                requiresReconciliation = decision.reconcileBeforeReplay,
            )
            RetryDecision.ActionRequired -> actionRequired = true
            RetryDecision.Cancelled -> Unit
            RetryDecision.DeleteConverged -> commands.remove(command.aggregateId)
        }
        return true
    }

    override suspend fun acknowledgeAndFinish(
        command: DurableMutationCommand,
        acknowledgement: RemoteMutationAcknowledgement,
    ): Boolean = inFlight.remove(command.aggregateId) && commands.remove(command.aggregateId) != null

    override suspend fun supersedeAfterRemoteWinner(command: DurableMutationCommand): Boolean =
        inFlight.remove(command.aggregateId) && commands.remove(command.aggregateId) != null

    override suspend fun continueAfterPartialProgress(
        command: DurableMutationCommand,
        acknowledgement: RemoteMutationAcknowledgement,
        nowEpochMillis: Long,
    ): Boolean {
        if (!inFlight.remove(command.aggregateId)) return false
        commands[command.aggregateId] = command.replacement(
            operation = requireNotNull(acknowledgement.nextOperation),
            nextEligibleEpochMillis = nowEpochMillis,
        )
        return true
    }
}

private fun DurableMutationCommand.replacement(
    operation: RemoteMutationOperation = this.operation,
    attemptCount: Int = this.attemptCount,
    nextEligibleEpochMillis: Long = this.nextEligibleEpochMillis,
    requiresReconciliation: Boolean = this.requiresReconciliation,
) = DurableMutationCommand(
    accountId = accountId,
    aggregateType = aggregateType,
    aggregateId = aggregateId,
    revision = revision,
    operation = operation,
    attemptCount = attemptCount,
    nextEligibleEpochMillis = nextEligibleEpochMillis,
    requiresReconciliation = requiresReconciliation,
)
