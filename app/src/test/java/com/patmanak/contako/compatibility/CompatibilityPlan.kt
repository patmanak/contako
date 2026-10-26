package com.patmanak.contako.compatibility

import com.patmanak.contako.BuildConfig
import java.io.File
import java.util.Random

/** Test-only, versioned protocol. Changing generation or scheduling requires a version bump. */
internal object CompatibilityGenerator {
    const val VERSION = 3
    const val MAX_CONTACTS = 12

    enum class Action { IMPORT, NATIVE_PHONE, LOCAL_NOTE, LOST_ACK, RESTART, SETTLE, DELETE, NO_CHANGE }
    data class Step(val action: Action, val slot: Int, val variant: Int)
    data class Plan(val seed: Long, val steps: List<Step>)
    data class Profile(val name: String, val seeds: Int, val steps: Int, val budgetMillis: Long)

    val profile: Profile get() = when (System.getProperty("compatibility.profile") ?: "quick") {
        "quick" -> Profile("quick", 20, 30, 120_000)
        "deep" -> Profile("deep", 100, 100, 900_000)
        else -> error("Unknown compatibility profile")
    }

    fun seeds(profile: Profile): List<Long> = System.getProperty("compatibility.seed")?.let {
        listOf(it.toLong())
    } ?: List(profile.seeds) { 0x434f4e54414b4fL + it * 104_729L }

    fun generate(seed: Long, steps: Int): Plan {
        require(steps in 1..100)
        val random = Random(seed)
        val result = mutableListOf<Step>()
        val imported = mutableSetOf<Int>()
        val live = linkedSetOf<Int>()
        // Linked ordering matches the transaction-shaped store's durable command queue.
        val pending = linkedSetOf<Int>()
        var inFlight: Int? = null
        var restartRequired = false
        var interruptionCommands = 0
        fun emit(action: Action, slot: Int) {
            when (action) {
                Action.IMPORT -> {
                    check(imported.add(slot))
                    live += slot
                    pending += slot
                }
                Action.NATIVE_PHONE, Action.LOCAL_NOTE -> {
                    check(slot in live && slot != inFlight)
                    pending += slot
                }
                Action.LOST_ACK -> {
                    check(inFlight == null && pending.isNotEmpty())
                    inFlight = pending.first()
                    check(slot == inFlight)
                    restartRequired = true
                    interruptionCommands = 0
                }
                Action.RESTART -> restartRequired = false
                Action.SETTLE -> {
                    check(!restartRequired)
                    pending.clear()
                    inFlight = null
                }
                Action.NO_CHANGE -> check(live.isNotEmpty() && pending.isEmpty())
                Action.DELETE -> {
                    // DELETE performs the real live-field oracle before its immediate settle.
                    check(slot in live && pending.isEmpty())
                    live -= slot
                }
            }
            if (restartRequired && action != Action.LOST_ACK) interruptionCommands++
            result += Step(action, slot, random.nextInt(6))
        }
        fun edit() = if (random.nextBoolean()) Action.NATIVE_PHONE else Action.LOCAL_NOTE
        fun choose(slots: Collection<Int>) = slots.elementAt(random.nextInt(slots.size))

        // Guarantee a sensitive CREATE/lost-ack/reopen case and two live no-change passes
        // even in the quick profile. Independent imports/edits and reopen are interleaved,
        // rather than repeating one ten-action spine for every contact.
        val first = random.nextInt(MAX_CONTACTS)
        val second = choose((0 until MAX_CONTACTS).filter { it != first })
        emit(Action.IMPORT, first)
        if (random.nextBoolean()) {
            emit(Action.IMPORT, second)
            emit(edit(), first)
        } else {
            emit(edit(), first)
            emit(Action.IMPORT, second)
        }
        emit(edit(), choose(live))
        emit(Action.LOST_ACK, pending.first())
        val independent = live.single { it != inFlight }
        if (random.nextBoolean()) {
            emit(Action.RESTART, first)
            emit(edit(), independent)
        } else {
            emit(edit(), independent)
            emit(Action.RESTART, first)
        }
        emit(Action.SETTLE, first)
        emit(Action.NO_CHANGE, first)

        // Online bounded scheduler: only actions admitted by the durable causal state
        // compete. Seeds change their order, target contacts and payloads. SETTLE is an
        // account-wide barrier; no edit overwrites a claimed mutation, and deletion never
        // bypasses convergence or makes the mandatory no-change oracle vacuous.
        while (result.size < steps) {
            val unused = (0 until MAX_CONTACTS).filter { it !in imported }
            val editable = live.filter { it != inFlight }
            val choices = mutableListOf<Pair<Action, List<Int>>>()
            if (unused.isNotEmpty()) choices += Action.IMPORT to unused
            if (editable.isNotEmpty()) {
                choices += Action.NATIVE_PHONE to editable
                choices += Action.LOCAL_NOTE to editable
            }
            if (inFlight == null && pending.isNotEmpty()) {
                choices += Action.LOST_ACK to listOf(pending.first())
            }
            choices += Action.RESTART to listOf(live.firstOrNull() ?: 0)
            if (!restartRequired) choices += Action.SETTLE to listOf(live.firstOrNull() ?: 0)
            if (live.isNotEmpty() && pending.isEmpty()) {
                choices += Action.NO_CHANGE to live.toList()
                if (live.size > 1 || unused.isNotEmpty()) choices += Action.DELETE to live.toList()
            }
            // A crash can leave many independent writes admissible. Bound the interruption
            // interval so the generated prefix always reaches recovery, not just more edits.
            val selected = if (restartRequired && interruptionCommands >= 2) {
                Action.RESTART to listOf(inFlight!!)
            } else choices[random.nextInt(choices.size)]
            emit(selected.first, choose(selected.second))
        }
        return Plan(seed, result.take(steps))
    }

    /** Values are authored independently of every production codec and mapper. */
    data class Fixture(val given: String, val family: String, val note: String, val street: String,
        val date: String, val opaque: String, val phone: String, val replacementPhone: String) {
        val display: String get() = "$given $family"
    }
    fun fixture(variant: Int) = Fixture(
        given = listOf("Chloë", "Zoë", "A\u0308nne", "李", "İpek", "François")[variant % 6],
        family = listOf("Groß", "Müller", "O’Connor", "王", "Åström", "Dvořák")[variant % 6],
        note = "First line, with semicolon; and slash\\\nSecond line $variant",
        street = "12 Straße des Érables, bâtiment $variant",
        date = if (variant % 2 == 0) "--02-29" else "2000-02-29",
        opaque = "X-COMPAT-KEEP;X-ORIGIN=synthetic:opaque-$variant\\, exact",
        phone = "+1202555010$variant", replacementPhone = "+1202555020$variant",
    )

    data class Violation(val boundary: String, val invariant: String) {
        val signature: String get() = "$boundary/$invariant"
    }
    data class Reduction(val steps: List<Step>, val replays: Int, val budgetExhausted: Boolean)

    /** Only the *same* closed causal signature may justify removing a command. */
    fun reduce(original: List<Step>, signature: String, maximumReplays: Int = 200,
        budgetMillis: Long = 60_000, replay: (List<Step>) -> Violation?): Reduction {
        require(maximumReplays >= 0 && budgetMillis >= 0)
        val deadline = System.nanoTime() + budgetMillis * 1_000_000
        var reduced = original.toList()
        var replays = 0
        var index = 0
        while (index < reduced.size && replays < maximumReplays && System.nanoTime() < deadline) {
            val candidate = reduced.filterIndexed { position, _ -> position != index }
            replays++
            if (replay(candidate)?.signature == signature) {
                reduced = candidate
                index = 0
            } else index++
        }
        return Reduction(reduced, replays,
            index < reduced.size && (replays >= maximumReplays || System.nanoTime() >= deadline))
    }
}

/** No input payloads or exception messages are persisted, even though every fixture is invented. */
internal class CompatibilityEvidence(private val layer: String,
    private val budgetMillis: Long = CompatibilityGenerator.profile.budgetMillis,
    private val nanoTime: () -> Long = System::nanoTime) {
    private val profile = CompatibilityGenerator.profile
    private val started = nanoTime()
    private val output = File("build/qa-results/compatibility/$layer-${profile.name}.txt")
    private var executed = 0
    private val lines = mutableListOf(
        "generator_version=${CompatibilityGenerator.VERSION}", "layer=$layer",
        "source=${System.getProperty("compatibility.source")?.takeIf {
            it.matches(Regex("[a-fA-F0-9]{7,64}(-dirty)?"))
        } ?: "UNRECORDED"}",
        "app_version=${BuildConfig.VERSION_NAME}", "profile=${profile.name}",
        "simulated_peers=true", "crypto=NOT_RUN", "room=NOT_RUN", "oem=NOT_RUN", "proton=NOT_RUN",
    )

    fun step(seed: Long, index: Int, step: CompatibilityGenerator.Step) {
        checkBudget()
        executed++
        lines += "seed=$seed step=$index action=${step.action} slot=${step.slot} variant=${step.variant}"
    }

    fun checkBudget() {
        if ((nanoTime() - started) / 1_000_000 >= budgetMillis) {
            finish("NOT RUN", "PROFILE_BUDGET")
            throw CompatibilityBudgetExhausted()
        }
    }

    fun finish(result: String, boundary: String = "COMPLETE") {
        if (result == "PASS") checkBudget()
        requireNotNull(output.parentFile).mkdirs()
        output.writeText((lines + listOf("executed=$executed", "result=$result", "boundary=$boundary"))
            .joinToString("\n", postfix = "\n"))
    }
}

internal class CompatibilityBudgetExhausted : AssertionError("Compatibility profile budget exhausted; remaining steps NOT RUN")
