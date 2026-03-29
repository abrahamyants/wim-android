package com.wim.app

import org.junit.Assert.*
import org.junit.Test

/**
 * Clinical disfluency tests — real-world stuttered speech patterns
 * ported from Лаврентий test_integration.py and test_clinical.py.
 *
 * These simulate what Whisper actually outputs when transcribing
 * stuttered speech, then verify DisfluencyFilter handles it.
 */
class ClinicalDisfluencyTest {

    private val fillers = listOf(
        "um", "uh", "uhm", "umm", "erm", "er", "ah", "hm", "hmm",
        "like", "you know", "i mean", "so", "well", "right",
        "э", "ээ", "эм", "ну", "нуу", "это", "вот", "типа", "как бы",
        "значит", "короче"
    )

    // ── Лаврентий parity tests (from test_integration.py) ──

    @Test fun lavrentiy_wordRep() {
        val r = filter("I I I want to go")
        assertTrue("Should contain 'I want'", r.contains("I want"))
        assertEquals("Only one 'I'", 1, r.split(" ").count { it.equals("I", ignoreCase = true) })
    }

    @Test fun lavrentiy_wordRep_the() {
        assertEquals("the dog", filter("the the dog").trim())
    }

    @Test fun lavrentiy_stutterFragment() {
        val r = filter("p- p- pop")
        assertTrue("Contains 'pop'", r.contains("pop"))
        assertFalse("No 'p-' remains", r.contains("p-"))
    }

    @Test fun lavrentiy_phraseRep() {
        assertEquals("I want to go", filter("I want I want to go").trim())
    }

    @Test fun lavrentiy_fillers() {
        val r = filter("um I uh want to er go")
        assertFalse("No 'um'", r.lowercase().split(" ").contains("um"))
        assertFalse("No 'uh'", r.lowercase().split(" ").contains("uh"))
        assertFalse("No 'er'", r.lowercase().split(" ").contains("er"))
        assertTrue("'want' preserved", r.contains("want"))
        assertTrue("'go' preserved", r.contains("go"))
    }

    @Test fun lavrentiy_russianFillers() {
        val r = filter("э я ну хочу ээ пойти")
        val words = r.split(" ")
        assertFalse("No 'э'", words.contains("э"))
        assertFalse("No 'ну'", words.contains("ну"))
        assertFalse("No 'ээ'", words.contains("ээ"))
    }

    @Test fun lavrentiy_singleFiller_kept() {
        assertEquals("um", filter("um"))
    }

    @Test fun lavrentiy_cleanText_unchanged() {
        assertEquals("I want to go to the store", filter("I want to go to the store"))
    }

    @Test fun lavrentiy_mixedDisfluencies() {
        val r = filter("um I I want to uh p- p- pick up the the groceries")
        assertTrue("Contains 'I want'", r.contains("I want"))
        assertTrue("Contains 'pick'", r.contains("pick"))
        assertTrue("Contains 'groceries'", r.contains("groceries"))
        assertFalse("No 'um'", r.split(" ").contains("um"))
        assertFalse("No 'uh'", r.split(" ").contains("uh"))
    }

    // ── Real stuttered speech patterns (Whisper output) ────

    @Test fun realSpeech_partWordRepWithBlock() {
        // Whisper often transcribes blocks as repeated syllables
        val r = filter("I b- b- b- bought some groceries")
        assertTrue("Contains 'bought'", r.contains("bought"))
        assertFalse("No 'b-'", r.contains("b-"))
    }

    @Test fun realSpeech_prolongedS() {
        val r = filter("I sssssaid hello to my friend")
        assertTrue("Contains 'said'", r.contains("said"))
        assertFalse("No 'sssss'", r.contains("sssss"))
    }

    @Test fun realSpeech_fillerStacking() {
        // High-stress speech: multiple fillers stacked
        val r = filter("um uh like I um need to uh like go")
        assertTrue("Contains 'need'", r.contains("need"))
        assertTrue("Contains 'go'", r.contains("go"))
    }

    @Test fun realSpeech_falseStartWithRestart() {
        val r = filter("I was going to the-- I went to the store")
        assertTrue("Contains 'store'", r.contains("store"))
    }

    @Test fun realSpeech_multipleBlocks() {
        // Multiple blocks in one utterance
        val r = filter("c- c- can you p- p- please help me")
        assertTrue("Contains 'can'", r.contains("can"))
        assertTrue("Contains 'please'", r.contains("please"))
        assertTrue("Contains 'help'", r.contains("help"))
    }

    @Test fun realSpeech_prolongedMWithWord() {
        assertEquals("my name is George", filter("mmmmmy name is George"))
    }

    @Test fun realSpeech_wholeWordRepInConversation() {
        val r = filter("and and and then I I went to to the store")
        assertTrue("Contains 'then'", r.contains("then"))
        assertTrue("Contains 'went'", r.contains("went"))
        assertTrue("Contains 'store'", r.contains("store"))
    }

    // ── Bilingual stuttering (EN/RU mix) ───────────────

    @Test fun bilingual_russianWithFillers() {
        val r = filter("ну я это хочу вот пойти в магазин")
        assertTrue("Contains 'хочу'", r.contains("хочу"))
        assertTrue("Contains 'магазин'", r.contains("магазин"))
    }

    @Test fun bilingual_mixedLanguageFillers() {
        val r = filter("um я want uh пойти like домой")
        assertTrue("Contains 'want'", r.contains("want"))
        assertTrue("Contains 'домой'", r.contains("домой"))
    }

    // ── Edge: Whisper hallucination patterns ───────────

    @Test fun whisperHallucination_trailingFillers() {
        // Whisper sometimes adds trailing fillers during silence
        val r = filter("I went to the store um um um")
        assertTrue("Core content preserved", r.contains("store"))
    }

    @Test fun whisperHallucination_repeatedPhrase() {
        // Whisper can repeat phrases when audio loops
        val r = filter("hello how are you hello how are you")
        assertTrue("Contains 'hello'", r.contains("hello"))
    }

    // ── Severity-scaled expectations ───────────────────

    @Test fun highStress_heavyDisfluency() {
        // Simulates high-stress scenario: heavy stuttering + fillers
        val input = "um I I I w- w- w- want to uh p- p- please um like tell you about the the the project"
        val r = filter(input)
        assertTrue("Core: 'want'", r.contains("want"))
        assertTrue("Core: 'tell'", r.contains("tell"))
        assertTrue("Core: 'project'", r.contains("project"))
        assertFalse("No 'um'", r.split(" ").any { it.lowercase() == "um" })
    }

    @Test fun reading_nearFluent() {
        // Reading aloud: mostly fluent, maybe one hesitation
        val r = filter("The quick brown fox um jumps over the lazy dog")
        assertEquals("The quick brown fox jumps over the lazy dog", r)
    }

    // ── Helper ─────────────────────────────────────────

    private fun filter(text: String): String = DisfluencyFilter.filter(text, fillers)
}
