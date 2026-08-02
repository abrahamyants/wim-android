package com.wim.app

import org.junit.Assert.assertEquals
import org.junit.Test

class RepunctuateTest {

    private fun repunc(s: String) = Repunctuate.apply(s)

    @Test fun empty_returnsEmpty() {
        assertEquals("", repunc(""))
    }

    @Test fun whitespaceOnly_returnsEmpty() {
        assertEquals("", repunc("   "))
    }

    @Test fun capitalizeFirstLetter() {
        assertEquals("Hello world.", repunc("hello world"))
    }

    @Test fun standaloneI_capitalized() {
        assertEquals("I went home.", repunc("i went home"))
    }

    @Test fun iApostropheM_capitalized() {
        assertEquals("I'm hungry.", repunc("i'm hungry"))
    }

    @Test fun iApostropheLl_capitalized() {
        assertEquals("I'll go.", repunc("i'll go"))
    }

    @Test fun iApostropheVe_capitalized() {
        assertEquals("I've seen it.", repunc("i've seen it"))
    }

    @Test fun iApostropheD_capitalized() {
        assertEquals("I'd like that.", repunc("i'd like that"))
    }

    @Test fun unicodeRightSingleQuote_capitalized() {
        assertEquals("I\u2019m hungry.", repunc("i\u2019m hungry"))
    }

    @Test fun afterPeriod_capitalized() {
        assertEquals("Hello. There.", repunc("hello. there"))
    }

    @Test fun afterQuestionMark_capitalized() {
        assertEquals("Hello? There.", repunc("hello? there"))
    }

    @Test fun afterExclamation_capitalized() {
        assertEquals("Hi! There.", repunc("hi! there"))
    }

    @Test fun questionStarter_what_terminalQuestion() {
        assertEquals("What is your name?", repunc("what is your name"))
    }

    @Test fun questionStarter_who() {
        assertEquals("Who knows?", repunc("who knows"))
    }

    @Test fun questionStarter_how() {
        assertEquals("How are you?", repunc("how are you"))
    }

    @Test fun questionStarter_can() {
        assertEquals("Can you help?", repunc("can you help"))
    }

    @Test fun questionStarter_did() {
        assertEquals("Did you go?", repunc("did you go"))
    }

    @Test fun nonQuestion_terminalPeriod() {
        assertEquals("The dog ran.", repunc("the dog ran"))
    }

    @Test fun idempotent_alreadyPunctuated() {
        assertEquals("Hello world.", repunc("Hello world."))
    }

    @Test fun idempotent_alreadyQuestion() {
        assertEquals("How are you?", repunc("How are you?"))
    }

    @Test fun idempotent_alreadyExclamation() {
        assertEquals("Wow that's cool!", repunc("Wow that's cool!"))
    }

    @Test fun preservesExistingTerminal_whenAlreadyCapitalized() {
        assertEquals("Hi there.", repunc("Hi there."))
    }

    @Test fun trims_leadingWhitespace() {
        assertEquals("Hello world.", repunc("   hello world"))
    }

    @Test fun trims_trailingWhitespace() {
        assertEquals("Hello world.", repunc("hello world   "))
    }

    @Test fun phraseWithI_inMiddle_questionStarterPrefix() {
        // First word "where" is a question starter, so terminal is "?"
        // even though the phrase reads as a fragment, not a question.
        assertEquals("Where I went?", repunc("where i went"))
    }

    @Test fun phraseWithI_inMiddle_nonQuestionPrefix() {
        assertEquals("The place I went.", repunc("the place i went"))
    }

    @Test fun phraseWithI_atEnd() {
        // "that's" starts with "t" → not a question starter → period.
        assertEquals("That's what I said.", repunc("that's what i said"))
    }

    @Test fun doesNotCapitalize_iWithinWord() {
        assertEquals("This is interesting.", repunc("this is interesting"))
    }

    @Test fun questionStarter_realisticDictation() {
        assertEquals(
            "Where is the report?",
            repunc("where is the report")
        )
    }

    // ── The mark belongs to the sentence being closed ───
    //
    // The terminal mark was chosen from the first word of the whole text, so a
    // multi-sentence utterance took its punctuation from a sentence that had
    // already been closed.

    @Test fun statementAfterAQuestionClosesWithAPeriod() {
        assertEquals(
            "Can you send the report. I will wait.",
            repunc("can you send the report. i will wait")
        )
    }

    @Test fun questionAfterAStatementClosesWithAQuestionMark() {
        assertEquals(
            "The report is done. Can you send it?",
            repunc("the report is done. can you send it")
        )
    }

    @Test fun questionAfterTwoStatementsStillClosesWithAQuestionMark() {
        assertEquals(
            "I finished it. It is on your desk. Do you want a copy?",
            repunc("i finished it. it is on your desk. do you want a copy")
        )
    }
}
