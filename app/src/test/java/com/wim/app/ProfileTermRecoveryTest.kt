package com.wim.app

import org.junit.Assert.*
import org.junit.Test

class ProfileTermRecoveryTest {

    // ── Required regression case (spec 2026-07-29) ─────────────────
    // Raw: "Tell count to check the yellow flag in PowerShell."
    // Saved correction: cloth → Claude. Vocabulary: YOLO flag, PowerShell.
    // Result must contain Claude, YOLO flag, and PowerShell.

    @Test fun regression_countBecomesClaude_yellowFlagBecomesYoloFlag_powerShellSurvives() {
        val raw = "Tell count to check the yellow flag in PowerShell."
        val (result, matches) = ProfileTermRecovery.apply(
            text = raw,
            corrections = mapOf("cloth" to "Claude"),
            vocabulary = listOf("YOLO flag", "PowerShell"),
            lowConfTexts = listOf("tell count to check the yellow flag"),
        )
        assertTrue("expected Claude in: $result", result.contains("Claude"))
        assertTrue("expected YOLO flag in: $result", result.contains("YOLO flag"))
        assertTrue("expected PowerShell in: $result", result.contains("PowerShell"))
        assertTrue(matches.isNotEmpty())
    }

    // ── Exact corrections apply first ──────────────────────────────

    @Test fun exactCorrectionApplies() {
        val (result, matches) = ProfileTermRecovery.apply(
            "the cloth wrote the code", mapOf("cloth" to "Claude"), emptyList()
        )
        assertTrue(result.contains("Claude"))
        assertFalse(result.contains("cloth"))
        assertEquals("exact_correction", matches.first().kind)
    }

    // ── Approximate matching only inside low-confidence ASR text ───

    @Test fun approximateRequiresLowConfidence() {
        val (result, _) = ProfileTermRecovery.apply(
            "Tell count to check it",
            mapOf("cloth" to "Claude"),
            emptyList(),
            lowConfTexts = emptyList(), // nothing marked uncertain
        )
        assertFalse(result.contains("Claude"))
    }

    // ── Dangerous common-word replacements are blocked ─────────────

    @Test fun commonWordCouldNeverBecomesClaude() {
        val (result, _) = ProfileTermRecovery.apply(
            "I could check the file",
            mapOf("cloth" to "Claude"),
            emptyList(),
            lowConfTexts = listOf("i could check the file"), // even marked uncertain
        )
        assertFalse(result.contains("Claude"))
        assertTrue(result.contains("could"))
    }

    // ── Multiword vocabulary phrases ───────────────────────────────

    @Test fun multiwordVocabularyPhraseRecovered() {
        val (result, matches) = ProfileTermRecovery.apply(
            "check the yellow flag before merging",
            emptyMap(),
            listOf("YOLO flag"),
        )
        assertTrue("expected YOLO flag in: $result", result.contains("YOLO flag"))
        assertTrue(matches.any { it.kind.startsWith("vocabulary") })
    }

    @Test fun vocabularyLiteralAlreadyPresentIsUntouched() {
        val (result, matches) = ProfileTermRecovery.apply(
            "run it in PowerShell now",
            emptyMap(),
            listOf("PowerShell"),
        )
        assertEquals("run it in PowerShell now", result)
        assertTrue(matches.isEmpty())
    }

    // ── Priorities ─────────────────────────────────────────────────

    @Test fun correctionOutranksVocabularyOnSameSpan() {
        // Both a correction target and a vocabulary term could claim "cloth";
        // the correction must win the span.
        val (result, matches) = ProfileTermRecovery.apply(
            "ask cloth about it",
            mapOf("cloth" to "Claude"),
            listOf("clot"),
        )
        assertTrue(result.contains("Claude"))
        assertTrue(matches.first().kind.startsWith("exact_correction") || matches.first().kind.startsWith("correction"))
    }

    @Test fun longerPhraseOutranksShorterMatch() {
        val (result, _) = ProfileTermRecovery.apply(
            "raise the yellow flag now",
            emptyMap(),
            listOf("YOLO flag", "flag"),
        )
        // The two-word phrase must claim "yellow flag" before bare "flag" can.
        assertTrue("expected YOLO flag in: $result", result.contains("YOLO flag"))
    }

    @Test fun commonVerbCutIsNeverMuggedByVocabulary() {
        // Live 2026-08-05: "some guy just cut me off" pasted as "Git me off".
        // "cut" and "Git" share Metaphone code KT; the low-confidence span
        // made the phrase a recovery target; "cut" was missing from the
        // blocklist. The planted "Git" then became a fake proper-noun anchor
        // and the meaning guard killed every honest rewrite.
        val (result, matches) = ProfileTermRecovery.apply(
            "sorry some guy just cut me off",
            emptyMap(),
            listOf("Git"),
            lowConfTexts = listOf("some guy just cut me off"),
        )
        assertEquals("sorry some guy just cut me off", result)
        assertTrue(matches.isEmpty())
    }

    // ── Phonetic-code edit distance ────────────────────────────────

    @Test fun oneEditOrLess() {
        assertTrue(ProfileTermRecovery.oneEditOrLess("KNT", "KLT"))
        assertTrue(ProfileTermRecovery.oneEditOrLess("KLT", "KLT"))
        assertTrue(ProfileTermRecovery.oneEditOrLess("KLT", "KLTS"))
        assertFalse(ProfileTermRecovery.oneEditOrLess("KNT", "PRS"))
        assertFalse(ProfileTermRecovery.oneEditOrLess("K", "KLTXX"))
    }
}
