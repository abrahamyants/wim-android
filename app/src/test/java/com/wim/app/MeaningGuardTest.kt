package com.wim.app

import org.junit.Assert.*
import org.junit.Test

class MeaningGuardTest {

    // ── Retention: anchors that must survive ───────────────────────

    @Test fun retention_properNounLost() {
        val lost = MeaningGuard.checkRetention("call Henderson tomorrow", "call him tomorrow")
        assertTrue(lost.contains("Henderson"))
    }

    @Test fun retention_numberLost() {
        val lost = MeaningGuard.checkRetention("send 42 units", "send the units")
        assertTrue(lost.contains("42"))
    }

    @Test fun retention_dateWordLost() {
        val lost = MeaningGuard.checkRetention("meet on friday", "let's meet soon")
        assertTrue(lost.any { it.equals("friday", ignoreCase = true) })
    }

    @Test fun retention_amountPreservedReformatting() {
        // $ token survives as a substring match, case-insensitive
        val lost = MeaningGuard.checkRetention("wire \$4,200 today", "Wire \$4,200 today.")
        assertTrue(lost.isEmpty())
    }

    @Test fun retention_vocabularyTermLowercaseInput() {
        // ASR output is lowercase — vocabulary closes the capitalization blind spot
        val lost = MeaningGuard.checkRetention(
            "tell powershell to run it", "tell it to run", vocabulary = listOf("PowerShell")
        )
        assertTrue(lost.contains("PowerShell"))
    }

    @Test fun retention_negationDropped() {
        val lost = MeaningGuard.checkRetention("do not send the file", "send the file")
        assertTrue(lost.contains("<negation>"))
    }

    @Test fun retention_negationKept() {
        val lost = MeaningGuard.checkRetention("do not send the file", "Don't send the file.")
        assertFalse(lost.contains("<negation>"))
    }

    @Test fun retention_whetherOrNotReduction_allowed() {
        // "whether X or not" → "whether X" keeps the uncertainty; not a lost negation
        val lost = MeaningGuard.checkRetention(
            "ask whether the office is open or not",
            "Ask whether the office is open."
        )
        assertFalse(lost.contains("<negation>"))
    }

    // ── Fabrication: anchors that must not appear from nothing ─────

    @Test fun fabrication_inventedProperNoun() {
        val invented = MeaningGuard.checkFabrication(
            "please forward the file to subscribe at the london office",
            "Please forward the file to Henderson at the London office."
        )
        assertTrue(invented.contains("Henderson"))
    }

    @Test fun fabrication_sentenceOpeningOkayIsNotAName() {
        val invented = MeaningGuard.checkFabrication(
            "okay let's move the meeting", "Okay, let's move the meeting."
        )
        assertTrue(invented.isEmpty())
    }

    @Test fun fabrication_numberFromNumberWordsIsReformatting() {
        val invented = MeaningGuard.checkFabrication(
            "four thousand two hundred dollars", "\$4,200"
        )
        assertTrue(invented.isEmpty())
    }

    @Test fun fabrication_numberFromNothingIsInvented() {
        val invented = MeaningGuard.checkFabrication(
            "send the report", "send the 3 reports"
        )
        assertTrue(invented.contains("3"))
    }

    @Test fun fabrication_contentWordGatedOnHallucinationArtifact() {
        // Without an artifact, rephrasing is allowed
        val without = MeaningGuard.checkFabrication(
            "i need to pick up the thing from the store",
            "I need to pick up the package from the store."
        )
        assertFalse(without.contains("package"))
        // With an artifact (a silent span), a confident new noun is flagged
        val with = MeaningGuard.checkFabrication(
            "i need to pick up the thanks for watching from the store",
            "I need to pick up the prescription from the store."
        )
        assertTrue(with.contains("prescription"))
    }

    // ── Unchanged detection ────────────────────────────────────────

    @Test fun unchanged_punctuationOnlyIsUnchanged() {
        assertTrue(
            MeaningGuard.isEffectivelyUnchanged(
                "can you send me the report by friday",
                "Can you send me the report by Friday?"
            )
        )
    }

    @Test fun unchanged_realRewriteIsChanged() {
        assertFalse(
            MeaningGuard.isEffectivelyUnchanged(
                "can you send me the report by friday",
                "Please send me the report by Friday."
            )
        )
    }

    // ── Repair instruction: exact feedback ─────────────────────────

    @Test fun repair_namesUnchangedOutput() {
        val g = MeaningGuard.GuardResult(ok = true, lost = emptyList(), invented = emptyList())
        val msg = MeaningGuard.buildRepairInstruction("send the report", "send the report", g)
        assertTrue(msg.contains("merely repeated the transcript"))
    }

    @Test fun repair_namesLostAnchorsExactly() {
        val g = MeaningGuard.GuardResult(ok = false, lost = listOf("Henderson", "42"), invented = emptyList())
        val msg = MeaningGuard.buildRepairInstruction("raw", "clean", g)
        assertTrue(msg.contains("'Henderson'"))
        assertTrue(msg.contains("'42'"))
        assertTrue(msg.contains("dropped protected content"))
    }

    @Test fun repair_namesNegationSeparately() {
        val g = MeaningGuard.GuardResult(ok = false, lost = listOf("<negation>"), invented = emptyList())
        val msg = MeaningGuard.buildRepairInstruction("do not go", "go", g)
        assertTrue(msg.contains("changed negative meaning"))
        assertFalse(msg.contains("'<negation>'"))
    }

    @Test fun repair_namesInventedItems() {
        val g = MeaningGuard.GuardResult(ok = false, lost = emptyList(), invented = listOf("Henderson"))
        val msg = MeaningGuard.buildRepairInstruction("raw", "clean", g)
        assertTrue(msg.contains("invented protected content"))
        assertTrue(msg.contains("'Henderson'"))
    }

    @Test fun repair_emptyOutput() {
        val g = MeaningGuard.GuardResult(ok = true, lost = emptyList(), invented = emptyList())
        val msg = MeaningGuard.buildRepairInstruction("send it", "", g)
        assertTrue(msg.contains("previous answer was empty"))
    }

    // ── A title must survive as a title, not as letters ────────
    //
    // Retention tested every anchor with a plain substring check. The short
    // titles hide inside ordinary words — "dr" is in "address", "hundred" and
    // "children"; "ms" is in "terms", "items" and "problems" — so the guard
    // reported the title retained in exactly the sentences where it had been
    // dropped. meaning_guard.py matches titles in the output with the same
    // regex it uses on the input; this is that behaviour, ported.

    @Test fun retention_drIsNotRetainedByTheWordAddress() {
        val lost = MeaningGuard.checkRetention(
            "tell dr williams to send the address",
            "Tell Williams to send the address."
        )
        assertTrue(lost.any { it.equals("dr", ignoreCase = true) })
    }

    @Test fun retention_msIsNotRetainedByTheWordTerms() {
        val lost = MeaningGuard.checkRetention(
            "tell ms taylor about the terms",
            "Tell Taylor about the terms."
        )
        assertTrue(lost.any { it.equals("ms", ignoreCase = true) })
    }

    @Test fun retention_drIsNotRetainedByTheWordHundred() {
        val lost = MeaningGuard.checkRetention(
            "ask dr chen how many hundred",
            "Ask Chen how many hundred."
        )
        assertTrue(lost.any { it.equals("dr", ignoreCase = true) })
    }

    @Test fun retention_keptTitleStillPasses() {
        val lost = MeaningGuard.checkRetention(
            "tell dr williams to send the address",
            "Tell Dr. Williams to send the address."
        )
        assertFalse(lost.any { it.equals("dr", ignoreCase = true) })
    }

    @Test fun retention_titleSpelledOutInFullStillPasses() {
        val lost = MeaningGuard.checkRetention(
            "tell doctor williams about the appointment",
            "Please tell Doctor Williams about the appointment."
        )
        assertFalse(lost.any { it.equals("doctor", ignoreCase = true) })
    }
}
