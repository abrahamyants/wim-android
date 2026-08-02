package com.wim.app

import org.junit.Assert.*
import org.junit.Test

class DisfluencyFilterTest {

    private val defaultFillers = listOf("um", "uh", "like", "you know", "i mean")

    // ── Stutter fragments ──────────────────────────────

    @Test fun stutterFragment_basic() {
        assertEquals("pop", filter("p- p- pop"))
    }

    @Test fun stutterFragment_longer() {
        assertEquals("become", filter("be- be- become"))
    }

    @Test fun stutterFragment_single() {
        assertEquals("stop", filter("s- stop"))
    }

    @Test fun stutterFragment_inSentence() {
        assertEquals("I want to go", filter("I w- w- want to go"))
    }

    // ── Word repetitions ───────────────────────────────

    @Test fun wordRepeat_double() {
        assertEquals("I want", filter("I I want"))
    }

    @Test fun wordRepeat_triple() {
        assertEquals("I want", filter("I I I want"))
    }

    @Test fun wordRepeat_inSentence() {
        assertEquals("the store is closed", filter("the the the the store is closed"))
    }

    // ── Phrase repetitions ─────────────────────────────

    @Test fun phraseRepeat_twoWord() {
        assertEquals("I want to go", filter("I want I want to go"))
    }

    @Test fun phraseRepeat_threeWord() {
        assertEquals("I want to go now", filter("I want to I want to go now"))
    }

    // ── Filler words ───────────────────────────────────

    @Test fun fillers_removed() {
        assertEquals("I want a dog", filter("um I want uh a dog"))
    }

    @Test fun fillers_soleUtterance_kept() {
        assertEquals("um", filter("um"))
    }

    @Test fun fillers_multiple() {
        assertEquals("going to store", filter("like um going to uh store"))
    }

    @Test fun fillers_caseInsensitive() {
        assertEquals("hello", filter("Um hello Uh"))
    }

    // ── False starts ───────────────────────────────────

    @Test fun falseStart_doubleDash() {
        val result = filter("I was going to-- I went to the store")
        assertTrue("Should contain 'I went'", result.contains("I went"))
        assertFalse("Should not contain 'going to--'", result.contains("going to--"))
    }

    @Test fun falseStart_singleDash_preserved() {
        // Single em-dash should NOT be treated as false start
        val input = "The store - which is nearby - is open"
        val result = filter(input)
        assertTrue("Single dashes preserved", result.contains("store"))
    }

    // ── Prolongations ──────────────────────────────────

    @Test fun prolongation_mmmmaybe() {
        assertEquals("maybe", filter("mmmmaybe"))
    }

    @Test fun prolongation_sssso() {
        assertEquals("so", filter("sssso"))
    }

    @Test fun prolongation_ffffine() {
        assertEquals("fine", filter("ffffine"))
    }

    @Test fun prolongation_threeChars() {
        assertEquals("maybe", filter("mmmaybe"))
    }

    @Test fun prolongation_normalWord_untouched() {
        assertEquals("hello", filter("hello"))
    }

    @Test fun prolongation_twoChars_untouched() {
        // "mm" prefix with 2 chars should NOT trigger (need 3+)
        assertEquals("llama", filter("llama"))
    }

    @Test fun prolongation_inSentence() {
        assertEquals("I want to go", filter("I wwwwant to go"))
    }

    // ── Combined ───────────────────────────────────────

    @Test fun combined_stutterAndFiller() {
        assertEquals("I want to go", filter("um I w- w- want to uh go"))
    }

    @Test fun combined_prolongationAndRepeat() {
        assertEquals("so I want", filter("sssso I I want"))
    }

    @Test fun emptyInput() {
        assertEquals("", filter(""))
    }

    @Test fun blankInput() {
        assertEquals("   ", filter("   "))
    }

    @Test fun normalSpeech_untouched() {
        assertEquals("I went to the store and bought groceries",
            filter("I went to the store and bought groceries"))
    }

    // ── ASR system markers ────────────────────────────

    @Test fun systemMarker_music_strippedWithoutConfidenceMetadata() {
        assertEquals(
            "hello world",
            DisfluencyFilter.stripBlockHallucinations("hello [Music] world", emptyList())
        )
    }

    @Test fun systemMarker_unterminatedBlankAudio_strippedWithoutConfidenceMetadata() {
        assertEquals(
            "",
            DisfluencyFilter.stripBlockHallucinations("[BLANK_AUDIO", emptyList())
        )
    }

    @Test fun systemMarker_legitimateBracketedPhrase_preserved() {
        assertEquals(
            "Meet me at [Music Hall] tonight",
            DisfluencyFilter.stripBlockHallucinations(
                "Meet me at [Music Hall] tonight",
                emptyList()
            )
        )
    }

    // ── Hyphenated words are not stutter fragments ─────
    //
    // The whitespace-fragment regex allowed zero repetitions, so a single
    // "word- " was deleted whether or not it was a stutter. A fragment is a
    // prefix of the word the speaker was reaching for; a compound's first half
    // is not. At L1 nothing compares output to input, so a word deleted here is
    // simply gone from the message.

    @Test fun compoundWord_firstHalfSurvives() {
        assertEquals("co- founder of the company", filter("co- founder of the company"))
    }

    @Test fun preExisting_survives() {
        assertEquals("pre- existing condition", filter("pre- existing condition"))
    }

    @Test fun wellKnown_survives() {
        assertEquals("well- known author", filter("well- known author"))
    }

    @Test fun stutterOnSingleLetterWordStillCollapses() {
        assertEquals("I want that", filter("I- I want that"))
    }

    @Test fun repeatedFragmentBeforeItsWordStillCollapses() {
        assertEquals("the meeting", filter("th- th- the meeting"))
    }

    // ── Helper ─────────────────────────────────────────

    private fun filter(text: String, fillers: List<String> = defaultFillers): String {
        return DisfluencyFilter.filter(text, fillers)
    }
}
