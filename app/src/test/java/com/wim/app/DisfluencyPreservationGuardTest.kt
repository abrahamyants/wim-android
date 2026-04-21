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

        // ── French natural repeats via lang-pack ──
        PreservationCase(
            description = "French emphatic 'non non' (fr pack)",
            input = "non non ce n'est pas correct",
            mustSurvive = listOf("non non"),
            naturalRepeats = setOf("non non", "oui oui", "si si", "et et")
        ),
        PreservationCase(
            description = "French emphatic 'oui oui' (fr pack)",
            input = "oui oui bien sur",
            mustSurvive = listOf("oui oui"),
            naturalRepeats = setOf("non non", "oui oui", "si si", "et et")
        ),

        // ── German natural repeats via lang-pack ──
        PreservationCase(
            description = "German emphatic 'nein nein' (de pack)",
            input = "nein nein das ist falsch",
            mustSurvive = listOf("nein nein"),
            naturalRepeats = setOf("nein nein", "ja ja", "doch doch", "und und")
        ),
        PreservationCase(
            description = "German emphatic 'ja ja' (de pack)",
            input = "ja ja ich verstehe",
            mustSurvive = listOf("ja ja"),
            naturalRepeats = setOf("nein nein", "ja ja", "doch doch", "und und")
        ),

        // ── Italian natural repeats via lang-pack ──
        PreservationCase(
            description = "Italian emphatic 'sì sì' (it pack)",
            input = "sì sì certamente",
            mustSurvive = listOf("sì sì"),
            naturalRepeats = setOf("no no", "sì sì", "dai dai", "e e")
        ),
        PreservationCase(
            description = "Italian emphatic 'dai dai' (it pack)",
            input = "dai dai andiamo",
            mustSurvive = listOf("dai dai"),
            naturalRepeats = setOf("no no", "sì sì", "dai dai", "e e")
        ),

        // ── Portuguese natural repeats via lang-pack ──
        PreservationCase(
            description = "Portuguese emphatic 'não não' (pt pack)",
            input = "não não isso está errado",
            mustSurvive = listOf("não não"),
            naturalRepeats = setOf("não não", "sim sim", "já já", "e e")
        ),
        PreservationCase(
            description = "Portuguese emphatic 'sim sim' (pt pack)",
            input = "sim sim está bem",
            mustSurvive = listOf("sim sim"),
            naturalRepeats = setOf("não não", "sim sim", "já já", "e e")
        ),

        // ── Mandarin natural repeats via lang-pack ──
        // Chinese reduplication has no internal whitespace; the WORD_REPEAT regex
        // doesn't fire on single-token 对对, so protection is moot but the text
        // must still survive intact through the pipeline.
        PreservationCase(
            description = "Mandarin '对对' preserved (zh pack)",
            input = "对对 this is correct",
            mustSurvive = listOf("对对"),
            naturalRepeats = setOf("对对", "是是", "行行", "好好")
        ),
        PreservationCase(
            description = "Mandarin aspectual '看看' (take-a-look) preserved",
            input = "我去看看 the new store",
            mustSurvive = listOf("看看"),
            naturalRepeats = setOf("对对", "是是", "行行", "好好")
        ),

        // ── Hindi natural repeats via lang-pack ──
        PreservationCase(
            description = "Hindi emphatic 'नहीं नहीं' (hi pack)",
            input = "नहीं नहीं यह सही नहीं है",
            mustSurvive = listOf("नहीं नहीं"),
            naturalRepeats = setOf("नहीं नहीं", "हाँ हाँ", "ठीक ठीक", "और और")
        ),
        PreservationCase(
            description = "Hindi emphatic 'हाँ हाँ' (hi pack)",
            input = "हाँ हाँ बिल्कुल",
            mustSurvive = listOf("हाँ हाँ"),
            naturalRepeats = setOf("नहीं नहीं", "हाँ हाँ", "ठीक ठीक", "और और")
        ),

        // ── Arabic natural repeats via lang-pack ──
        PreservationCase(
            description = "Arabic emphatic 'لا لا' (ar pack)",
            input = "لا لا هذا غير صحيح",
            mustSurvive = listOf("لا لا"),
            naturalRepeats = setOf("لا لا", "اه اه", "ايوه ايوه", "و و")
        ),
        PreservationCase(
            description = "Arabic emphatic 'ايوه ايوه' (ar pack)",
            input = "ايوه ايوه صحيح",
            mustSurvive = listOf("ايوه ايوه"),
            naturalRepeats = setOf("لا لا", "اه اه", "ايوه ايوه", "و و")
        ),

        // ── Korean natural repeats via lang-pack ──
        PreservationCase(
            description = "Korean emphatic '아니 아니' (ko pack)",
            input = "아니 아니 그게 맞지 않아요",
            mustSurvive = listOf("아니 아니"),
            naturalRepeats = setOf("아니 아니", "네 네", "맞아 맞아", "그리고 그리고")
        ),
        PreservationCase(
            description = "Korean emphatic '네 네' (ko pack)",
            input = "네 네 알겠습니다",
            mustSurvive = listOf("네 네"),
            naturalRepeats = setOf("아니 아니", "네 네", "맞아 맞아", "그리고 그리고")
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
