package com.wim.app

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Disfluency-preservation guard.
 *
 * The L1–L3 pipeline must NOT destroy speech disfluencies that should either
 * (a) reach the user verbatim as a natural emphatic pattern ("bye bye", "sí sí",
 * "そうそう"), or (b) survive into L4's clinical reconstruction prompt as raw
 * evidence of a block, repetition, or prolongation.
 *
 * This test runs DisfluencyFilter against 15 fixture cases spanning English,
 * Russian, Spanish, and Japanese. Each case asserts what MUST survive and what
 * MUST be stripped. The test fails on the first preservation violation — this
 * is the regression guard the design doc (Section 2.8) calls for, and any new
 * component added to the L1–L3 path (punctuation restorer, N-best rescorer,
 * phonetic matcher) must pass it too.
 *
 * TODO: extend with 20 real disfluent-speech clip transcripts pulled from
 * George's ~/.lavrentiy/audio_archive/ ground-truth corpus. Current cases are
 * synthetic but cover the documented failure modes.
 */
class DisfluencyPreservationGuardTest {

    private data class PreservationCase(
        val description: String,
        val input: String,
        val mustSurvive: List<String> = emptyList(),
        val mustStrip: List<String> = emptyList(),
        val naturalRepeats: Set<String>? = null,
        val fillers: List<String> = emptyList()
    )

    private val cases = listOf(
        // ── English emphatic natural repeats — must survive ──
        PreservationCase(
            description = "English emphatic 'really really'",
            input = "really really nice job today",
            mustSurvive = listOf("really really")
        ),
        PreservationCase(
            description = "English farewell 'bye bye'",
            input = "okay bye bye see you later",
            mustSurvive = listOf("bye bye")
        ),
        PreservationCase(
            description = "English exactly-2 'no no' survives",
            input = "no no that's not right",
            mustSurvive = listOf("no no")
        ),

        // ── Russian emphatic repeats — use the hardcoded NATURAL_REPEATS default ──
        PreservationCase(
            description = "Russian 'да да' preserved",
            input = "да да я понимаю",
            mustSurvive = listOf("да да")
        ),

        // ── Spanish natural repeats via lang-pack ──
        PreservationCase(
            description = "Spanish emphatic 'sí sí' (es pack)",
            input = "sí sí me gusta mucho",
            mustSurvive = listOf("sí sí"),
            naturalRepeats = setOf("sí sí", "no no", "ya ya", "claro claro")
        ),

        // ── Japanese natural repeats via lang-pack ──
        PreservationCase(
            description = "Japanese 'そうそう' (ja pack)",
            input = "そうそう that's great food",
            mustSurvive = listOf("そうそう"),
            naturalRepeats = setOf("そうそう", "はいはい")
        ),

        // ── Stuttering patterns — must strip correctly, must preserve the content word ──
        PreservationCase(
            description = "Part-word stutter b-b-b-buy → buy",
            input = "b-b-b-buy me some milk",
            mustSurvive = listOf("buy", "milk"),
            mustStrip = listOf("b-b-b-buy")
        ),
        PreservationCase(
            description = "Word triple-repeat 'I I I' strips",
            input = "I I I want coffee",
            mustSurvive = listOf("want coffee"),
            mustStrip = listOf("I I I")
        ),
        PreservationCase(
            description = "Prolongation 'yessss' → 'yes'",
            input = "yessss it's fine",
            mustSurvive = listOf("yes", "it's fine"),
            mustStrip = listOf("yessss")
        ),
        PreservationCase(
            description = "False start strips, completion survives",
            input = "I was going to-- I went to the store",
            mustSurvive = listOf("I went to the store"),
            mustStrip = listOf("I was going to--")
        ),
        PreservationCase(
            description = "Phrase repetition 'I want I want' strips",
            input = "I want I want to go home",
            mustSurvive = listOf("I want to go home"),
            mustStrip = listOf("I want I want")
        ),

        // ── Filler handling ──
        PreservationCase(
            description = "Fillers strip when not sole word",
            input = "um I want uh a burger",
            mustSurvive = listOf("I want", "burger"),
            mustStrip = listOf(" um ", " uh "),
            fillers = listOf("um", "uh")
        ),
        PreservationCase(
            description = "Filler alone is preserved (don't return empty)",
            input = "um",
            mustSurvive = listOf("um"),
            fillers = listOf("um")
        ),

        // ── Mixed cases ──
        PreservationCase(
            description = "Stutter + natural repeat coexist",
            input = "b-b-buy me some really really good coffee",
            mustSurvive = listOf("buy", "really really", "coffee"),
            mustStrip = listOf("b-b-buy")
        ),
        PreservationCase(
            description = "Prolongation + natural repeat",
            input = "heyyyy no no that was mmmmaybe wrong",
            mustSurvive = listOf("hey", "no no", "maybe", "wrong"),
            mustStrip = listOf("heyyyy", "mmmmaybe")
        )
    )

    @Test
    fun disfluencyPreservationGuard() {
        val failures = mutableListOf<String>()

        for (case in cases) {
            val output = DisfluencyFilter.filter(
                case.input,
                case.fillers,
                case.naturalRepeats
            )

            for (phrase in case.mustSurvive) {
                if (!output.contains(phrase, ignoreCase = true)) {
                    failures += "[${case.description}] expected '$phrase' to survive\n    input:  '${case.input}'\n    output: '$output'"
                }
            }
            for (phrase in case.mustStrip) {
                if (output.contains(phrase, ignoreCase = false)) {
                    failures += "[${case.description}] expected '$phrase' to be stripped\n    input:  '${case.input}'\n    output: '$output'"
                }
            }
        }

        assertTrue(
            "Disfluency preservation guard: ${failures.size} violation(s):\n\n${failures.joinToString("\n\n")}",
            failures.isEmpty()
        )
    }
}
