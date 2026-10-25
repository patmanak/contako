package com.patmanak.contako.compatibility

import com.patmanak.contako.BuildConfig
import java.io.File
import java.util.Random

/** Test-only, versioned protocol. Changing generation or scheduling requires a version bump. */
internal object CompatibilityGenerator {
    const val VERSION = 2
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
        return Plan(seed, List(steps) { index ->
            // Fixed ten-command causal spine, bounded contact slots and randomized payload
            // variants. Verify the settled live directory before deleting its contact.
            // Each unit contains save -> lost ack -> restart -> reconciliation.
            val action = when (index % 10) {
                0 -> Action.IMPORT
                1 -> Action.NATIVE_PHONE
                2 -> Action.LOCAL_NOTE
                3 -> Action.LOST_ACK
                4 -> Action.RESTART
                5 -> Action.SETTLE
                6 -> Action.LOCAL_NOTE
                7 -> Action.SETTLE
                8 -> Action.NO_CHANGE
                else -> Action.DELETE
            }
            Step(action, (index / 10) % MAX_CONTACTS, random.nextInt(6))
        })
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
