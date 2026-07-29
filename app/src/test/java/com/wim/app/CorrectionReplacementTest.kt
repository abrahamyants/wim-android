package com.wim.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A saved correction whose target contains a dollar amount must not kill the
 * pipeline.
 *
 * Kotlin's `Regex.replace(input, replacement)` treats `$1` in the REPLACEMENT
 * as a capture-group reference. WiM learns corrections from its own
 * reconstructions, so the moment it learned one pointing at "$1,200" — which
 * happened for real on 2026-07-29, from reconstruction output — every
 * subsequent dictation threw IndexOutOfBoundsException: No group 1 in
 * L1Pipeline.transform and L2CorrectionsStage.apply. Not a test-only failure:
 * the operator's live dictation was dead until the correction was removed.
 */
class CorrectionReplacementTest {

    private fun applyCorrection(text: String, wrong: String, right: String): String {
        val pattern = Regex("\\b${Regex.escape(wrong)}\\b", RegexOption.IGNORE_CASE)
        return pattern.replace(text, Regex.escapeReplacement(right))
    }

    @Test fun dollarAmountTargetDoesNotThrow() {
        assertEquals(
            "wire \$1,200 today",
            applyCorrection("wire twelvehundred today", "twelvehundred", "\$1,200"),
        )
    }

    @Test fun bareDollarOneDoesNotThrow() {
        assertEquals("cost \$1", applyCorrection("cost onedollar", "onedollar", "\$1"))
    }

    @Test fun backslashInTargetIsLiteral() {
        assertEquals("path C:\\temp", applyCorrection("path ctemp", "ctemp", "C:\\temp"))
    }

    @Test fun ordinaryCorrectionStillWorks() {
        assertEquals("ask Claude about it", applyCorrection("ask cloth about it", "cloth", "Claude"))
    }

    @Test fun caseInsensitiveMatchPreservesReplacement() {
        assertEquals("Ask Claude", applyCorrection("Ask CLOTH", "cloth", "Claude"))
    }
}
