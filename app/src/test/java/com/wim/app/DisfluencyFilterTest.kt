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

    // ── Prolongation must not touch digits ─────────────
    //
    // The regex was `(\w)\1{2,200}`, and \w includes digits, so any run of three
    // identical digits collapsed to one: "wire 1000 dollars" became "wire 10
    // dollars". filter() runs at L1, L2 and L3, and it runs BEFORE the numeric
    // guard, so computeRiskFlags compared an already-mangled raw against the
    // output and could never flag it. At L1 there is no guard at all.

    @Test fun prolongation_leavesRoundThousandsAlone() {
        assertEquals("wire 1000 dollars", filter("wire 1000 dollars"))
    }

    @Test fun prolongation_leavesLargeAmountsAlone() {
        assertEquals("transfer 10000 today", filter("transfer 10000 today"))
        assertEquals("the balance is 2000", filter("the balance is 2000"))
    }

    @Test fun prolongation_leavesCommaFormattedAmountsAlone() {
        assertEquals("account 1,000", filter("account 1,000"))
    }

    @Test fun prolongation_leavesPhoneNumbersAlone() {
        assertEquals("call 5551000", filter("call 5551000"))
    }

    @Test fun prolongation_stillCollapsesLetters() {
        assertEquals("yes", filter("yessss"))
        assertEquals("hey", filter("heyyyy"))
    }

    // ── Repetition collapse must not touch digits ──────
    //
    // Same defect as the prolongation one above, in the three regexes either
    // side of it. STUTTER_FRAGMENT_NOSPACE, WORD_REPEAT and PHRASE_REPEAT all
    // match on `\w`, which includes digits, so a spoken number came out short:
    //
    //     "my account is 5 5 5 1234"  -> "my account is 5 1234"
    //     "call me at 4 1 5 5 5 5 1 2 1 2" -> "call me at 4 1 5 1 2 1 2"
    //     "my zip is 9-4-1-1-0"       -> "my zip is 9-4-0"
    //     "the pin is 1 2 1 2"        -> "the pin is 1 2"
    //
    // Whisper renders spoken digits as numerals, and a speaker reading out an
    // account, PIN, phone number or zip repeats digits constantly. Like the
    // prolongation case, this runs inside filter() before the numeric guard,
    // so computeRiskFlags never sees the original — and at L1 there is no
    // guard at all.

    @Test fun wordRepeat_leavesRepeatedDigitsAlone() {
        assertEquals("my account is 5 5 5 1234", filter("my account is 5 5 5 1234"))
        assertEquals("the code is 8 8 8", filter("the code is 8 8 8"))
    }

    @Test fun wordRepeat_leavesSpokenPhoneNumbersAlone() {
        assertEquals(
            "call me at 4 1 5 5 5 5 1 2 1 2",
            filter("call me at 4 1 5 5 5 5 1 2 1 2")
        )
    }

    @Test fun wordRepeat_leavesRepeatedAmountsAlone() {
        assertEquals("wire 100 100 dollars", filter("wire 100 100 dollars"))
    }

    @Test fun stutterFragment_leavesHyphenatedDigitsAlone() {
        assertEquals("call 5-5-5-1234", filter("call 5-5-5-1234"))
        assertEquals("my zip is 9-4-1-1-0", filter("my zip is 9-4-1-1-0"))
    }

    @Test fun phraseRepeat_leavesRepeatedDigitPairsAlone() {
        assertEquals("the pin is 1 2 1 2", filter("the pin is 1 2 1 2"))
    }

    // ── A suspect segment must not take other copies with it ──
    //
    // Regex.replace removes EVERY match, and Whisper reports a block-suspect
    // segment by time, not by character offset, so there is no way to tell
    // which copy it meant. A speaker who said "the report" twice lost both:
    //
    //     "send the report and then send the report again"
    //         -> "send and then send again"
    //
    // Short phrases are exactly the ones that repeat, and this runs before the
    // filter and before every guard, so nothing downstream saw the loss.

    private fun suspect(text: String) = WhisperClient.LowConfidenceSegment(
        text = text, avgLogprob = -1.0, noSpeechProb = 0.9, blockSuspect = true
    )

    @Test fun blockStrip_leavesARepeatedPhraseAlone() {
        assertEquals(
            "send the report and then send the report again",
            DisfluencyFilter.stripBlockHallucinations(
                "send the report and then send the report again",
                listOf(suspect("the report"))
            )
        )
    }

    @Test fun blockStrip_leavesARepeatedWordAlone() {
        assertEquals(
            "i said yes and he said yes too",
            DisfluencyFilter.stripBlockHallucinations(
                "i said yes and he said yes too",
                listOf(suspect("yes"))
            )
        )
    }

    @Test fun blockStrip_stillRemovesASingleOccurrence() {
        assertEquals(
            "call me tomorrow",
            DisfluencyFilter.stripBlockHallucinations(
                "call me thanks for watching tomorrow",
                listOf(suspect("thanks for watching"))
            )
        )
    }

    @Test fun repetitionCollapse_stillWorksOnWords() {
        assertEquals("I need to reschedule", filter("I I I need to reschedule"))
        assertEquals("the dog", filter("the the the dog"))
        assertEquals("I want to go", filter("I want I want to go"))
        assertEquals("meeting", filter("m-m-m-meeting"))
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

    // ── Fillers made of two words ──────────────────────
    //
    // Step 4 split on whitespace and compared single tokens, so a two-word
    // filler could never equal a one-word token. "you know" and "i mean" are
    // in DEFAULT_FILLERS and had never been stripped, along with 9 multi-word
    // entries across the Spanish, French, Portuguese and Russian packs.
    //
    // Only L1 was affected. From L2 up the prompt says "Strip these fillers:"
    // and the model handles a phrase unaided — which is why this survived:
    // the one layer with no model is also the one with no guard.

    @Test fun filler_stripsTwoWordFillers() {
        // defaultFillers holds um, uh, like, "you know", "i mean" — no "so",
        // so "so" is expected to survive here.
        assertEquals(
            "so the report is due friday",
            filter("um so you know i mean the report is like due friday")
        )
    }

    @Test fun filler_stripsTwoWordFillerWithTrailingComma() {
        assertEquals("the meeting moved", filter("you know, the meeting moved"))
    }

    @Test fun filler_twoWordFillerAloneIsKept() {
        assertEquals("you know", filter("you know"))
    }

    @Test fun filler_leavesCleanSentenceAlone() {
        assertEquals("send the report on friday", filter("send the report on friday"))
    }

    @Test fun filler_stripsNonEnglishPhraseFillers() {
        assertEquals(
            "el informe es para el viernes",
            filter("o sea el informe es para el viernes", listOf("o sea", "es que", "pues"))
        )
    }

    private fun filter(text: String, fillers: List<String> = defaultFillers): String {
        return DisfluencyFilter.filter(text, fillers)
    }

    // ── Parenthesized caption markers (observed on-device 2026-08-04) ──

    @Test fun parenMarker_chucklesAloneStripsToEmpty() {
        assertEquals("", DisfluencyFilter.stripBlockHallucinations("(chuckles).", emptyList()))
    }

    @Test fun parenMarker_strippedMidSentence() {
        assertEquals(
            "okay send it",
            DisfluencyFilter.stripBlockHallucinations("okay (mumbling) send it", emptyList())
        )
    }

    @Test fun parenMarker_muffledSpeakingStripped() {
        assertEquals(
            "",
            DisfluencyFilter.stripBlockHallucinations("(muffled speaking)", emptyList())
        )
    }

    @Test fun parenMarker_legitimateParenthesesKept() {
        assertEquals(
            "see the file (attached) for details",
            DisfluencyFilter.stripBlockHallucinations("see the file (attached) for details", emptyList())
        )
    }

    @Test fun parenMarker_refusalPathAlsoStripsParens() {
        assertEquals(
            "send it now",
            DisfluencyFilter.stripCaptionArtifacts("send it now (chuckles).")
        )
    }

    // ── Insane-run cap: Whisper's repetition-loop failure (2026-08-05) ──

    @Test fun insaneRun_avalancheCollapsesToEight() {
        val run = List(112) { "to" }.joinToString(", ")
        val out = DisfluencyFilter.capInsaneRuns("I am going $run and then some")
        val kept = Regex("\\bto\\b").findAll(out).count()
        assertEquals(8, kept)
        assertTrue(out.endsWith("and then some"))
    }

    @Test fun insaneRun_shortRepetitionUntouched() {
        val text = "I am going to to to to the place"
        assertEquals(text, DisfluencyFilter.capInsaneRuns(text))
    }

    @Test fun insaneRun_digitRunsAreData() {
        val text = "code " + List(12) { "5" }.joinToString(" ")
        assertEquals(text, DisfluencyFilter.capInsaneRuns(text))
    }

    // ── Clock-broom: timed risky fillers ──

    private fun words(vararg spec: Pair<String, Double>): List<WhisperClient.TimedWord> {
        // Each word 0.2s long, starting at the given time.
        return spec.map { (w, t) -> WhisperClient.TimedWord(t, t + 0.2, w) }
    }

    @Test fun timed_pauseBoundedYouKnowIsStripped() {
        val tw = words(
            "so" to 0.0, "you" to 1.0, "know" to 1.25, "I" to 2.2, "was" to 2.45, "thinking" to 2.7
        )
        assertEquals(
            "so, I was thinking",
            DisfluencyFilter.stripTimedFillers("so, you know, I was thinking", tw)
        )
    }

    @Test fun timed_tightYouKnowIsKept() {
        val tw = words(
            "You" to 0.0, "know" to 0.22, "what" to 0.44, "I" to 0.66, "want" to 0.88
        )
        assertEquals(
            "You know what I want",
            DisfluencyFilter.stripTimedFillers("You know what I want", tw)
        )
    }

    @Test fun timed_verbLikeIsKept() {
        val tw = words(
            "I" to 0.0, "like" to 0.22, "how" to 0.44, "it" to 0.66, "looks" to 0.88
        )
        assertEquals(
            "I like how it looks",
            DisfluencyFilter.stripTimedFillers("I like how it looks", tw)
        )
    }

    @Test fun timed_fillerLikeBetweenPausesIsStripped() {
        val tw = words(
            "it" to 0.0, "was" to 0.22, "like" to 1.2, "huge" to 2.2
        )
        assertEquals(
            "it was huge",
            DisfluencyFilter.stripTimedFillers("it was like huge", tw)
        )
    }

    @Test fun timed_noTimingsMeansNoChange() {
        assertEquals(
            "you know what I want",
            DisfluencyFilter.stripTimedFillers("you know what I want", emptyList())
        )
    }
}
