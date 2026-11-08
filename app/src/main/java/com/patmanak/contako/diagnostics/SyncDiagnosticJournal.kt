package com.patmanak.contako.diagnostics

import com.patmanak.contako.data.proton.ProtonContactEncodingStage
import com.patmanak.contako.data.proton.ProtonContactUpdateStage
import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.data.sync.RemoteImportPhase
import com.patmanak.contako.data.sync.SyncPassStage
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.sync.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class JournalStorage { INITIALIZING, AVAILABLE, UNAVAILABLE }

/** Closed event vocabulary shared with the legacy investigation report, never arbitrary text. */
internal object SyncJournalCodes {
    fun from(event: SanitizedDiagnosticEvent): String? = when (event) {
        is SanitizedDiagnosticEvent.GatewayFailure -> "gateway:${event.boundary.name}.${event.category.name}." +
            "${event.operation?.name ?: "NONE"}.${event.malformed?.name ?: "NONE"}.${event.hydration?.name ?: "NONE"}." +
            "${event.httpStatusCode ?: "NONE"}.${event.protonResponseCode ?: "NONE"}.${event.retryDelay.name}.${event.recoveryBlockerCategory?.name ?: "NONE"}"
        is SanitizedDiagnosticEvent.Prerequisite -> "prerequisite:${event.result.name}"
        is SanitizedDiagnosticEvent.AndroidPreflight -> "preflight:${event.reason?.name ?: "NONE"}"
        is SanitizedDiagnosticEvent.SyncRequest ->
            "request:${event.trigger.name}.${event.scope.name}.${event.disposition.name}"
        is SanitizedDiagnosticEvent.PassStarted -> "started:${event.scope.name}"
        is SanitizedDiagnosticEvent.PassFinished -> "finished:${event.outcome.name}"
        is SanitizedDiagnosticEvent.ContactUpdateFailure ->
            "update:${event.stage.name}.${event.category.name}.${event.encoding?.name ?: "NONE"}.${event.field?.name ?: "NONE"}"
        is SanitizedDiagnosticEvent.GroupReadFailure -> "groups:${event.category.name}"
        is SanitizedDiagnosticEvent.AndroidCallback -> "android_callback:${event.result.name}"
        is SanitizedDiagnosticEvent.CanonicalRejection -> "canonical:${event.reason.name}"
        else -> InvestigationTrace.codeFor(event)
    }

    fun allowed(code: String): Boolean {
        if (code in setOf("journal:PROCESS_START", "journal:CLEARED", "journal:RESTORE_FAILED", "journal:WRITE_FAILED")) return true
        val parts = code.split(':')
        if (parts.size == 2) {
            val details = parts[1].split('.')
            if (parts[0] == "gateway") return details.size == 9 &&
                details[0] in names(com.patmanak.contako.data.sync.GatewayDiagnosticBoundary.entries) &&
                details[1] in names(GatewayFailureCategory.entries) &&
                details[2] in names(com.patmanak.contako.data.sync.RemoteMutationOperation.entries) + "NONE" &&
                details[3] in names(com.patmanak.contako.data.gateway.GatewayMalformedResponseCategory.entries) + "NONE" &&
                details[4] in names(com.patmanak.contako.data.gateway.GatewayContactHydrationCategory.entries) + "NONE" &&
                numericOrNone(details[5], 100..599) && numericOrNone(details[6], 1_000..9_999) &&
                details[7] in names(DiagnosticRetryDelay.entries) &&
                details[8] in names(GatewayFailureCategory.entries) + "NONE"
            val vocabulary = when (parts[0]) {
                "prerequisite" -> listOf(names(com.patmanak.contako.data.sync.SyncPrerequisiteState.entries))
                "preflight" -> listOf(names(com.patmanak.contako.data.sync.AndroidInteroperabilityRepairReason.entries) + "NONE")
                "request" -> listOf(names(SyncTrigger.entries), names(SyncScope.entries), names(SyncRequestDisposition.entries))
                "started" -> listOf(names(SyncScope.entries))
                "finished" -> listOf(names(SyncPassOutcome.entries))
                "update" -> listOf(names(ProtonContactUpdateStage.entries), names(GatewayFailureCategory.entries),
                    names(ProtonContactEncodingStage.entries) + "NONE", names(ContactValueKind.entries) + "NONE")
                "groups" -> listOf(names(GatewayFailureCategory.entries))
                "android_callback" -> listOf(names(DiagnosticAndroidCallback.entries))
                "canonical" -> listOf(names(com.patmanak.contako.data.local.AndroidCanonicalContactRejectionReason.entries))
                else -> null
            }
            if (vocabulary != null) return details.size == vocabulary.size &&
                details.indices.all { details[it] in vocabulary[it] }
        }
        return InvestigationTrace.isAllowedCode(code)
    }

    fun failure(code: String): Boolean = (code.startsWith("gateway:") && code.substringAfter(':').split('.').getOrNull(1) != "CANCELLED") ||
        (code.startsWith("prerequisite:") && code != "prerequisite:READY") ||
        (code.startsWith("preflight:") && code != "preflight:NONE") || code.substringBefore(':') in setOf(
        "failure", "remote", "mutation", "update", "ingest_failure", "existing_plan",
        "ingest_codec", "ingest_baseline", "projection_repair", "projection_row", "binding", "photo_write", "canonical",
    ) || (code.startsWith("groups:") && code.substringAfter(':') !in setOf("CANCELLED", "PERMISSION_OR_PLAN_DENIED")) ||
        (code.startsWith("android:") && code.substringAfterLast(':') in
        setOf("ACTION_REQUIRED", "LOCAL_PERSISTENCE_FAILURE")) ||
        (code.substringBefore(':') in setOf("outcome", "finished") &&
            code.substringAfter(':') in setOf("ACTION_REQUIRED", "FAILED"))

    private fun names(values: List<Enum<*>>) = values.map { it.name }.toSet()
    private fun numericOrNone(value: String, range: IntRange) = value == "NONE" ||
        (value.matches(Regex("[1-9][0-9]{2,3}")) && value.toIntOrNull()?.let { it in range } == true)

    fun progress(code: String): Boolean = code in setOf("import:CARD_READ_START", "import:CARD_READ_DONE",
        "import:LOCAL_COMMIT_START", "import:LOCAL_COMMIT_DONE")
}

/** Wall time is UTC; elapsed time is relative to this process, never device uptime or an identifier. */
@ConsistentCopyVisibility
data class SyncJournalEntry internal constructor(internal val encoded: String) {
    companion object {
        private val utc = DateTimeFormatterBuilder().appendInstant(3).toFormatter()
        internal fun create(wallMillis: Long, elapsedMillis: Long, stage: SyncPassStage?,
            phase: RemoteImportPhase?, code: String): SyncJournalEntry {
            require(wallMillis in 0..253402300799999L && elapsedMillis >= 0 && SyncJournalCodes.allowed(code))
            return SyncJournalEntry("${utc.format(Instant.ofEpochMilli(wallMillis))},$elapsedMillis," +
                "${stage?.name ?: "NONE"},${phase?.name ?: "NONE"},$code")
        }

        internal fun valid(value: String): Boolean = runCatching {
            if (value.length > 512) return false
            val parts = value.split(',')
            if (parts.size != 5 || !Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z").matches(parts[0])) return false
            if (Instant.parse(parts[0]).toEpochMilli() !in 0..253402300799999L) return false
            if (!Regex("0|[1-9][0-9]{0,18}").matches(parts[1]) || parts[1].toLong() < 0) return false
            (parts[2] == "NONE" || SyncPassStage.entries.any { it.name == parts[2] }) &&
                (parts[3] == "NONE" || RemoteImportPhase.entries.any { it.name == parts[3] }) &&
                SyncJournalCodes.allowed(parts[4])
        }.getOrDefault(false)
    }
}

@ConsistentCopyVisibility
data class SyncJournalSnapshot internal constructor(
    internal val events: List<SyncJournalEntry>,
    internal val lastFailure: SyncJournalEntry?,
    internal val storage: JournalStorage,
)

/** Small, strictly validated private file. Atomic replacement preserves the previous complete log. */
internal class SyncJournalFile(private val file: File) {
    fun read(): Pair<List<SyncJournalEntry>, SyncJournalEntry?> {
        if (!file.exists()) return emptyList<SyncJournalEntry>() to null
        require(file.length() <= MAX_BYTES)
        val lines = file.readLines(Charsets.UTF_8)
        require(lines.size in 2..SyncDiagnosticBuffer.MAX_EVENTS + 2 && lines[0] == "contako_sync_journal=1")
        val failure = lines[1].removePrefix("last_failure=")
        require(lines[1].startsWith("last_failure="))
        require(failure == "none" || SyncJournalEntry.valid(failure))
        val events = lines.drop(2)
        require(events.all(SyncJournalEntry::valid))
        return events.map(::SyncJournalEntry) to failure.takeUnless { it == "none" }?.let(::SyncJournalEntry)
    }

    fun write(snapshot: SyncJournalSnapshot) {
        require(snapshot.events.size <= SyncDiagnosticBuffer.MAX_EVENTS)
        val lines = listOf("contako_sync_journal=1", "last_failure=${snapshot.lastFailure?.encoded ?: "none"}") +
            snapshot.events.map { it.encoded }
        require(snapshot.events.all { SyncJournalEntry.valid(it.encoded) } &&
            (snapshot.lastFailure == null || SyncJournalEntry.valid(snapshot.lastFailure.encoded)))
        val bytes = (lines.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        check(file.parentFile?.isDirectory == true || file.parentFile?.mkdirs() == true)
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.outputStream().use { it.write(bytes) }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    companion object { const val MAX_BYTES = 96 * 1024 }
}

internal class SyncDiagnosticBuffer(
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val monotonicClock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val startedAt = monotonicClock()
    private val events = ArrayDeque<SyncJournalEntry>()
    private var lastFailure: SyncJournalEntry? = null
    private var failureRecordedInPass = false
    private var stage: SyncPassStage? = null
    private var lastWorkStage: SyncPassStage? = null
    private var phase: RemoteImportPhase? = null

    @Synchronized fun record(event: SanitizedDiagnosticEvent) {
        val code = SyncJournalCodes.from(event) ?: return
        if (event is SanitizedDiagnosticEvent.PassStarted) {
            stage = null; lastWorkStage = null; phase = null; failureRecordedInPass = false
        }
        if (event is SanitizedDiagnosticEvent.SyncStage) { stage = event.stage; phase = null }
        if (event is SanitizedDiagnosticEvent.SyncStage && event.stage !in setOf(
                SyncPassStage.STATUS_PUBLICATION, SyncPassStage.FULL_REPAIR_FINALIZATION, SyncPassStage.COMPLETE)) {
            lastWorkStage = event.stage
        }
        if (event is SanitizedDiagnosticEvent.ImportPhase) phase = event.phase
        val request = event is SanitizedDiagnosticEvent.SyncRequest || event is SanitizedDiagnosticEvent.AndroidCallback
        val terminal = code.substringBefore(':') in setOf("outcome", "finished")
        val entry = SyncJournalEntry.create(wallClock(), (monotonicClock() - startedAt).coerceAtLeast(0),
            if (request) null else if (terminal) lastWorkStage else stage, if (request) null else phase, code)
        add(entry)
        // A later generic Android/ingestion banner must not hide the latest detailed failure.
        val generic = code.startsWith("android:") || code.startsWith("ingest_failure:") || terminal
        if (SyncJournalCodes.failure(code) && (!generic || !failureRecordedInPass)) {
            lastFailure = entry
            failureRecordedInPass = true
        }
    }

    @Synchronized fun restore(previous: Pair<List<SyncJournalEntry>, SyncJournalEntry?>) {
        val live = events.toList()
        val liveFailure = lastFailure
        events.clear()
        previous.first.forEach(::add)
        lastFailure = previous.second
        live.forEach(::add)
        if (liveFailure != null) lastFailure = liveFailure
    }

    @Synchronized fun clear() {
        events.clear(); lastFailure = null; stage = null; lastWorkStage = null; phase = null; failureRecordedInPass = false
        marker("journal:CLEARED")
    }

    @Synchronized fun marker(code: String) = add(SyncJournalEntry.create(wallClock(),
        (monotonicClock() - startedAt).coerceAtLeast(0), null, null, code))

    @Synchronized fun snapshot(storage: JournalStorage) = SyncJournalSnapshot(events.toList(), lastFailure, storage)

    private fun add(entry: SyncJournalEntry) {
        // Repetitive per-card success cannot evict the preceding request, phase or failures.
        fun lane(value: SyncJournalEntry): Int {
            val code = value.encoded.substringAfterLast(',')
            return if (SyncJournalCodes.failure(code)) 0 else if (SyncJournalCodes.progress(code)) 2 else 1
        }
        val entryLane = lane(entry)
        val quota = when (entryLane) { 0 -> 64; 1 -> 48; else -> 16 }
        if (events.count { lane(it) == entryLane } >= quota) {
            events.remove(events.first { lane(it) == entryLane })
        }
        events.addLast(entry)
    }

    companion object { const val MAX_EVENTS = 128 }
}

/** Conflated asynchronous persistence: diagnostics never wait on disk in the synchronization path. */
internal object SyncDiagnosticJournal {
    private val buffer = SyncDiagnosticBuffer()
    private val changes = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var storage = JournalStorage.INITIALIZING
    private var writer: Job? = null
    private var persistence: SyncJournalFile? = null
    private val persistenceLock = Mutex()

    @Synchronized fun initialize(file: File, scope: CoroutineScope) {
        if (writer != null) return
        persistence = SyncJournalFile(file)
        writer = scope.launch(Dispatchers.IO) {
            persistenceLock.withLock {
                storage = try {
                    buffer.restore(requireNotNull(persistence).read())
                    JournalStorage.AVAILABLE
                } catch (_: Exception) {
                    runCatching { buffer.marker("journal:RESTORE_FAILED") }
                    JournalStorage.UNAVAILABLE
                }
                runCatching { buffer.marker("journal:PROCESS_START") }
            }
            changes.trySend(Unit)
            for (ignored in changes) {
                delay(250) // Coalesce bursts; no scheduler, network, unbounded queue or extra worker.
                persistenceLock.withLock {
                    storage = try {
                        requireNotNull(persistence).write(buffer.snapshot(storage))
                        JournalStorage.AVAILABLE
                    } catch (_: Exception) {
                        if (storage != JournalStorage.UNAVAILABLE) runCatching { buffer.marker("journal:WRITE_FAILED") }
                        JournalStorage.UNAVAILABLE
                    }
                }
            }
        }
    }

    fun record(event: SanitizedDiagnosticEvent) {
        runCatching { buffer.record(event) }
        changes.trySend(Unit)
    }

    fun snapshot(): SyncJournalSnapshot = buffer.snapshot(storage)
    suspend fun clear(): Boolean = withContext(Dispatchers.IO) {
        persistenceLock.withLock {
            buffer.clear()
            storage = runCatching { requireNotNull(persistence).write(buffer.snapshot(storage)); JournalStorage.AVAILABLE }
                .getOrDefault(JournalStorage.UNAVAILABLE)
            storage == JournalStorage.AVAILABLE
        }
    }
}
