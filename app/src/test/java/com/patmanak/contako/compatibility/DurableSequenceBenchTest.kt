package com.patmanak.contako.compatibility

import com.patmanak.contako.compatibility.CompatibilityGenerator.Action
import com.patmanak.contako.data.android.mapping.AndroidContactSnapshot
import com.patmanak.contako.data.android.mapping.CanonicalAndroidContactMapper
import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.data.gateway.GatewayOutcome
import com.patmanak.contako.data.sync.*
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Exercises the production mutation orchestrator with a transaction-shaped durable test store.
 * Reopening this store is not evidence about SQLite, process death or ContactsProvider. */
class DurableSequenceBenchTest {
    @Test fun generatedSaveClaimEffectLostAckReopenAndDeleteSequencesConvergeWithoutReplay() = runBlocking {
        val profile = CompatibilityGenerator.profile
        val evidence = CompatibilityEvidence("MUTATION_SEQUENCE")
        try {
            CompatibilityGenerator.seeds(profile).forEach { seed ->
                val session = Session()
                val executed = mutableListOf<CompatibilityGenerator.Step>()
                try {
                    CompatibilityGenerator.generate(seed, profile.steps).steps.forEachIndexed { index, step ->
                        evidence.step(seed, index, step)
                        executed += step
                        session.step(step)
                        session.verify()
                        evidence.checkBudget()
                    }
                    session.settle()
                    session.assertConverged()
                    assertTrue(session.livePeerChecks > 0)
                    evidence.checkBudget()
                } catch (failure: SequenceInvariantFailure) {
                    // Replays construct new controlled stores and re-run production transitions.
                    // Invalid shortened command histories are not equivalent failures.
                    val reduced = CompatibilityGenerator.reduce(executed, failure.violation.signature) { candidate ->
                        runBlocking {
                            try {
                                val replay = Session()
                                candidate.forEach { replay.step(it); replay.verify() }
                                replay.settle()
                                replay.assertConverged()
                                null
                            } catch (again: SequenceInvariantFailure) { again.violation }
                            catch (_: IllegalStateException) { null }
                            catch (_: NoSuchElementException) { null }
                            catch (_: AssertionError) { null }
                        }
                    }
                    java.io.File("build/qa-results/compatibility/failure-sequence.txt").also { file ->
                        requireNotNull(file.parentFile).mkdirs()
                        file.writeText("generator_version=${CompatibilityGenerator.VERSION}\nseed=$seed\n" +
                            "layer=MUTATION_SEQUENCE\nresult=FAIL\nboundary=${failure.violation.signature}\n" +
                            "reducer_replays=${reduced.replays}\nreducer_budget_exhausted=${reduced.budgetExhausted}\n" +
                            "original_commands=${executed.size}\nreduced_commands=${reduced.steps.size}\n" +
                            reduced.steps.joinToString("\n") { "${it.action} slot=${it.slot} variant=${it.variant}" } + "\n")
                    }
                    throw failure
                }
                val remoteWrites = session.remote.writes
                val androidWrites = session.androidWrites
                repeat(2) { session.settle(); session.assertConverged() }
                assertEquals(remoteWrites, session.remote.writes)
                assertEquals(androidWrites, session.androidWrites)
            }
            evidence.finish("PASS")
        } catch (failure: Throwable) {
            evidence.finish(if (failure is CompatibilityBudgetExhausted) "NOT RUN" else "FAIL",
                if (failure is CompatibilityBudgetExhausted) "PROFILE_BUDGET" else
                    (failure as? SequenceInvariantFailure)?.violation?.signature ?: "MUTATION_SEQUENCE/UNCLASSIFIED")
            throw failure
        }
    }

    @Test fun injectedReplayRegressionIsDetectedAndMinimizedUsingTheActualOrchestrator() = runBlocking {
        val plan = CompatibilityGenerator.generate(42, 20)
        fun replay(steps: List<CompatibilityGenerator.Step>): CompatibilityGenerator.Violation? = runBlocking {
            // Dropping the recovered reconciliation marker represents the historical blind
            // CREATE replay defect at the test-store seam, not a change to production code.
            val session = Session(dropRecoveryProof = true)
            try {
                steps.forEach { step ->
                    if (step.action != Action.IMPORT && "synthetic-${step.slot}" !in session.store.contacts) return@runBlocking null
                    session.step(step)
                    if (session.remote.maximumCopies() > 1) {
                        return@runBlocking CompatibilityGenerator.Violation("MUTATION_REPLAY", "DUPLICATE_CREATE")
                    }
                }
                null
            } catch (failure: SequenceInvariantFailure) {
                failure.violation.takeIf { it.signature == "MUTATION_REPLAY/DUPLICATE_CREATE" }
            } catch (_: IllegalStateException) { null } catch (_: AssertionError) { null }
        }
        val initial = requireNotNull(replay(plan.steps))
        val reduced = CompatibilityGenerator.reduce(plan.steps, initial.signature, replay = ::replay)
        assertEquals(initial, replay(reduced.steps))
        assertTrue(reduced.steps.size < plan.steps.size)
        assertTrue(reduced.steps.any { it.action == Action.LOST_ACK })
        assertTrue(reduced.steps.any { it.action == Action.RESTART || it.action == Action.SETTLE })
        assertFalse(reduced.budgetExhausted)
        java.io.File("build/qa-results/compatibility/reduced-replay.txt").also { file ->
            requireNotNull(file.parentFile).mkdirs()
            file.writeText("generator_version=${CompatibilityGenerator.VERSION}\nseed=${plan.seed}\n" +
                "layer=MUTATION_SEQUENCE\ninjected_failure=${initial.signature}\nreplays=${reduced.replays}\n" +
                reduced.steps.joinToString("\n") { "${it.action} slot=${it.slot} variant=${it.variant}" } + "\n")
        }
        // The same reduced sequence is safe with the production recovery contract restored.
        val restored = Session()
        reduced.steps.forEach { restored.step(it); restored.verify() }
        restored.settle()
        restored.assertConverged()
    }

    @Test fun persistentRefusalRetainsIntentAndIndependentContactContinues() = runBlocking {
        val s = Session()
        s.step(CompatibilityGenerator.Step(Action.IMPORT, 0, 0))
        s.step(CompatibilityGenerator.Step(Action.IMPORT, 1, 1))
        s.deniedId = "synthetic-0"
        s.settle()
        assertTrue(s.store.commands.containsKey("synthetic-0"))
        assertTrue("synthetic-0" in s.store.blocked)
        assertFalse(s.store.commands.containsKey("synthetic-1"))
        assertEquals(1, s.remote.records.count { it.owner == OWNER && it.id == "synthetic-1" })
        assertEquals(0, s.remote.records.count { it.owner == OWNER && it.id == "synthetic-0" })
        s.verify()
    }

    private class Session(private val dropRecoveryProof: Boolean = false) {
        var store = Store()
        val remote = Remote()
        private val expected = mutableMapOf<String, Ref>()
        private val mapper = CanonicalAndroidContactMapper()
        private val android = mutableMapOf<String, AndroidContactSnapshot>()
        var androidWrites = 0
        var livePeerChecks = 0
        var deniedId: String? = null
        private var crashAfterEffect = false
        private var now = 1_000L
        private var runner = runner()

        private fun runner() = DurableMutationOrchestrator(store,
            preparationGateway = { command ->
                assertTrue(command.aggregateId in store.inFlight)
                val local = store.contacts.getValue(command.aggregateId)
                val matches = remote.records.filter { it.owner == OWNER && it.id == command.aggregateId }
                when {
                    deniedId == command.aggregateId -> GatewayOutcome.Failure(GatewayFailureCategory.VALIDATION_REJECTED)
                    command.requiresReconciliation && matches.singleOrNull()?.contact == local ->
                        GatewayOutcome.Success(MutationPreparation.AlreadyApplied(ack(command.aggregateId)))
                    command.operation == RemoteMutationOperation.DELETE && matches.isEmpty() ->
                        GatewayOutcome.Success(MutationPreparation.AlreadyApplied(ack(command.aggregateId)))
                    else -> GatewayOutcome.Success(MutationPreparation.UploadAllowed)
                }
            }, uploadGateway = { command ->
                assertTrue(command.aggregateId in store.inFlight)
                remote.apply(command, store.contacts.getValue(command.aggregateId))
                if (crashAfterEffect) {
                    crashAfterEffect = false
                    throw CancellationException("Synthetic stop after effect before acknowledgement")
                }
                GatewayOutcome.Success(ack(command.aggregateId))
            }, retryPolicy = MutationRetryPolicy(1_000, 60_000, 3))

        suspend fun step(step: CompatibilityGenerator.Step) {
            val id = "synthetic-${step.slot}"
            when (step.action) {
                Action.IMPORT -> {
                    val f = CompatibilityGenerator.fixture(step.variant)
                    val contact = CanonicalContact(OWNER, id, f.given, f.family, f.display,
                        values = listOf(
                            ContactValue("phone", ContactValueKind.PHONE, f.phone, order = 0),
                            ContactValue("note", ContactValueKind.NOTE, f.note, order = 0),
                            ContactValue("opaque", ContactValueKind.UNKNOWN_VCARD_PROPERTY, f.opaque, order = 0),
                            ContactValue("photo", ContactValueKind.PHOTO, "", order = 0, binaryReference = "synthetic-photo-A"),
                        ), revision = 1)
                    store.save(contact)
                    expected[id] = Ref(f.phone, f.note, f.opaque, 1, false)
                }
                Action.NATIVE_PHONE, Action.LOCAL_NOTE -> {
                    val local = store.contacts.getValue(id)
                    val e = expected.getValue(id)
                    val value = if (step.action == Action.NATIVE_PHONE) "+1202555020${step.variant}" else "Edited note ${step.variant}"
                    val kind = if (step.action == Action.NATIVE_PHONE) ContactValueKind.PHONE else ContactValueKind.NOTE
                    store.save(local.copy(revision = local.revision + 1, values = local.values.map {
                        if (it.kind == kind) it.copy(value = value) else it
                    }))
                    expected[id] = e.copy(phone = if (kind == ContactValueKind.PHONE) value else e.phone,
                        note = if (kind == ContactValueKind.NOTE) value else e.note, revision = e.revision + 1)
                }
                Action.LOST_ACK -> {
                    crashAfterEffect = true
                    try { runner.drain(OWNER, now) } catch (_: CancellationException) { }
                    assertTrue(store.inFlight.isNotEmpty())
                    assertTrue(store.commands.isNotEmpty())
                }
                Action.RESTART -> {
                    // Persisted state is copied, including IN_FLIGHT. No test pre-seeds the
                    // reconciliation marker: recoverInterrupted must derive it from the claim.
                    store = store.reopen(dropRecoveryProof)
                    runner = runner()
                }
                Action.SETTLE -> { settle(); if (deniedId == null) assertConverged() }
                Action.DELETE -> {
                    // Verify real field equality while the contact is still alive, so a final
                    // all-deleted directory cannot make the convergence oracle vacuous.
                    if (deniedId == null) assertConverged()
                    val local = store.contacts.getValue(id)
                    store.save(local.copy(revision = local.revision + 1, isDeleted = true))
                    expected[id] = expected.getValue(id).let { it.copy(revision = it.revision + 1, deleted = true) }
                    settle()
                }
                Action.NO_CHANGE -> { settle(); if (deniedId == null) assertConverged() }
            }
        }

        suspend fun settle() {
            now += 100_000
            runner.drain(OWNER, now)
            store.baselines.forEach { (id, baseline) ->
                if (baseline.isDeleted) {
                    if (android.remove(id) != null) androidWrites++
                } else {
                    val existing = android[id]
                    if (existing == null || mapper.planProjection(existing, baseline).operations.isNotEmpty()) {
                        android[id] = mapper.project(baseline).let { projected -> projected.copy(rows = projected.rows.mapIndexed { index, row ->
                            row.copy(identity = row.identity.copy(providerRowId = index + 1L))
                        }) }
                        androidWrites++
                    }
                }
            }
        }

        fun verify() {
            invariant(expected.keys == store.contacts.keys, "LOCAL", "IDENTITY_SET")
            expected.forEach { (id, ref) ->
                val local = store.contacts.getValue(id)
                invariant(OWNER == local.accountId, "LOCAL", "OWNER")
                invariant(ref.revision == local.revision, "LOCAL", "REVISION")
                invariant(ref.deleted == local.isDeleted, "LOCAL", "DELETION")
                invariant(ref.phone == local.valuesOf(ContactValueKind.PHONE).single().value, "LOCAL", "PHONE")
                invariant(ref.note == local.valuesOf(ContactValueKind.NOTE).single().value, "LOCAL", "NOTE")
                invariant(ref.opaque == local.valuesOf(ContactValueKind.UNKNOWN_VCARD_PROPERTY).single().value, "LOCAL", "OPAQUE")
                invariant("synthetic-photo-A" == local.valuesOf(ContactValueKind.PHOTO).single().binaryReference, "LOCAL", "PHOTO_REFERENCE")
                store.commands[id]?.let {
                    invariant(local.revision == it.revision, "OUTBOX", "REVISION")
                    invariant(OWNER == it.accountId, "OUTBOX", "OWNER")
                }
                store.baselines[id]?.let { invariant(it.revision <= local.revision, "BASELINE", "FUTURE_REVISION") }
            }
            invariant(remote.foreignBefore == remote.records.filter { it.owner != OWNER }, "REMOTE", "FOREIGN_OWNER")
            invariant(store.inFlight.all(store.commands::containsKey), "OUTBOX", "LOST_IN_FLIGHT_INTENT")
            invariant(store.blocked.all(store.commands::containsKey), "OUTBOX", "LOST_BLOCKED_INTENT")
            invariant(remote.maximumCopies() <= 1, "MUTATION_REPLAY", "DUPLICATE_CREATE")
        }

        fun assertConverged() {
            verify()
            invariant(store.commands.isEmpty(), "QUIESCENCE", "OUTBOX_NOT_EMPTY")
            invariant(store.inFlight.isEmpty(), "QUIESCENCE", "IN_FLIGHT_NOT_EMPTY")
            expected.forEach { (id, ref) ->
                val local = store.contacts.getValue(id)
                invariant(local == store.baselines[id], "BASELINE", "CANONICAL_MISMATCH")
                val copies = remote.records.filter { it.owner == OWNER && it.id == id }
                invariant(copies.size == if (ref.deleted) 0 else 1, "REMOTE", "COPY_CARDINALITY")
                if (!ref.deleted) {
                    livePeerChecks++
                    invariant(local == copies.single().contact, "REMOTE", "CANONICAL_MISMATCH")
                    val snapshot = android.getValue(id)
                    invariant(ref.phone == snapshot.rows.single { it.kind.name == "PHONE" }.value, "ANDROID_MAPPING", "PHONE")
                    invariant(ref.note == snapshot.rows.single { it.kind.name == "NOTE" }.value, "ANDROID_MAPPING", "NOTE")
                    invariant(mapper.planProjection(snapshot, local).operations.isEmpty(), "ANDROID_MAPPING", "PENDING_WRITE")
                } else invariant(!android.containsKey(id), "ANDROID_MAPPING", "DELETED_COPY_PRESENT")
            }
        }
    }

    private data class Ref(val phone: String, val note: String, val opaque: String, val revision: Long, val deleted: Boolean)
    private data class Record(val owner: String, val id: String, val contact: CanonicalContact)
    private class Remote {
        val records = mutableListOf(Record("foreign-owner", "synthetic-0",
            CanonicalContact("foreign-owner", "synthetic-0", displayName = "Foreign canary")))
        val foreignBefore = records.toList()
        var writes = 0
        fun apply(command: DurableMutationCommand, contact: CanonicalContact) {
            writes++
            when (command.operation) {
                RemoteMutationOperation.CREATE -> records += Record(OWNER, command.aggregateId, contact)
                RemoteMutationOperation.UPDATE -> {
                    val index = records.indexOfFirst { it.owner == OWNER && it.id == command.aggregateId }
                    check(index >= 0)
                    records[index] = Record(OWNER, command.aggregateId, contact)
                }
                RemoteMutationOperation.DELETE -> records.removeAll { it.owner == OWNER && it.id == command.aggregateId }
                else -> error("Unsupported bench operation")
            }
        }
        fun maximumCopies() = records.filter { it.owner == OWNER }.groupingBy { it.id }.eachCount().values.maxOrNull() ?: 0
    }

    private class Store : MutationExecutionStore {
        val contacts = linkedMapOf<String, CanonicalContact>()
        val commands = linkedMapOf<String, DurableMutationCommand>()
        val baselines = linkedMapOf<String, CanonicalContact>()
        val inFlight = mutableSetOf<String>()
        val blocked = mutableSetOf<String>()
        private var dropRecoveryProof = false

        fun save(contact: CanonicalContact) {
            check(contact.accountId == OWNER && contact.id !in inFlight)
            check(contact.revision > (contacts[contact.id]?.revision ?: 0))
            contacts[contact.id] = contact
            commands[contact.id] = DurableMutationCommand(OWNER, "CONTACT", contact.id, contact.revision,
                if (contact.isDeleted) RemoteMutationOperation.DELETE else if (contact.id in baselines)
                    RemoteMutationOperation.UPDATE else RemoteMutationOperation.CREATE, 0, 0, false)
            blocked.remove(contact.id)
        }

        fun reopen(dropRecoveryProof: Boolean) = Store().also { next ->
            next.contacts.putAll(contacts); next.commands.putAll(commands); next.baselines.putAll(baselines)
            next.inFlight.addAll(inFlight); next.blocked.addAll(blocked); next.dropRecoveryProof = dropRecoveryProof
        }
        override suspend fun remaining(accountId: String) = MutationRemainingWork(actionsRequired = blocked.size,
            pending = commands.size - blocked.size)
        override suspend fun recoverInterrupted(accountId: String, nowEpochMillis: Long): Int {
            check(accountId == OWNER)
            val count = inFlight.size
            inFlight.toList().forEach { id -> commands[id] = copy(commands.getValue(id), reconcile = !dropRecoveryProof) }
            inFlight.clear()
            return count
        }
        override suspend fun eligible(accountId: String, nowEpochMillis: Long, limit: Int) = commands.values.filter {
            it.accountId == accountId && it.aggregateId !in blocked && it.nextEligibleEpochMillis <= nowEpochMillis
        }.take(limit)
        override suspend fun claim(command: DurableMutationCommand, nowEpochMillis: Long) =
            commands[command.aggregateId]?.revision == command.revision && inFlight.add(command.aggregateId)
        override suspend fun recordFailure(command: DurableMutationCommand, category: GatewayFailureCategory, decision: RetryDecision): Boolean {
            if (!inFlight.remove(command.aggregateId)) return false
            when (decision) {
                is RetryDecision.RetryAt -> commands[command.aggregateId] = copy(command, decision.attemptCount,
                    decision.nextEligibleEpochMillis, decision.reconcileBeforeReplay)
                RetryDecision.ActionRequired -> blocked += command.aggregateId
                RetryDecision.Cancelled -> Unit
                RetryDecision.DeleteConverged -> error("No unproved absence is a convergence proof")
            }
            return true
        }
        override suspend fun acknowledgeAndFinish(command: DurableMutationCommand, acknowledgement: RemoteMutationAcknowledgement): Boolean {
            if (commands[command.aggregateId]?.revision != command.revision || !inFlight.remove(command.aggregateId)) return false
            baselines[command.aggregateId] = contacts.getValue(command.aggregateId)
            commands.remove(command.aggregateId)
            return true
        }
        override suspend fun supersedeAfterRemoteWinner(command: DurableMutationCommand) = error("Not used by this profile")
        override suspend fun continueAfterPartialProgress(command: DurableMutationCommand,
            acknowledgement: RemoteMutationAcknowledgement, nowEpochMillis: Long) = error("Not used by this profile")

        private fun copy(c: DurableMutationCommand, attempts: Int = c.attemptCount,
            next: Long = c.nextEligibleEpochMillis, reconcile: Boolean = c.requiresReconciliation) =
            DurableMutationCommand(c.accountId, c.aggregateType, c.aggregateId, c.revision, c.operation, attempts, next, reconcile)
    }

    private companion object {
        const val OWNER = "synthetic-owner"
        fun ack(id: String) = RemoteMutationAcknowledgement("remote-$id", "synthetic-version")
        fun invariant(condition: Boolean, boundary: String, name: String) {
            if (!condition) throw SequenceInvariantFailure(CompatibilityGenerator.Violation(boundary, name))
        }
    }

    private class SequenceInvariantFailure(val violation: CompatibilityGenerator.Violation) : AssertionError(violation.signature)
}
