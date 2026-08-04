package com.wim.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Approving one flagged word must not break the flags that follow it.
 *
 * A flag's range is a character offset into the injected text. Accepting a
 * swap whose replacement is a different length moves everything after it, and
 * the remaining flags were measured before that happened. Left unshifted the
 * next approval writes over the wrong characters:
 *
 *     "call teh clint about teh reprot"
 *     approve clint -> client
 *     "call teh client about teh reprot"
 *        the flag on "teh"    now points at " te"
 *        the flag on "reprot" now points at " repro"
 *
 * Clarifier returns flags sorted by uncertainty, not by position, so the next
 * flag is as likely to sit after the edit as before it.
 */
class ClarifierFlagShiftTest {

    private val text = "call teh clint about teh reprot"

    private fun flag(start: Int, end: Int, word: String) = FlaggedWord(
        originalWord = word,
        candidates = listOf("x"),
        rangeStart = start,
        rangeEnd = end,
        uncertaintyScore = 0.9,
    )

    private fun shifted(
        flags: List<FlaggedWord>,
        cycleIndex: Int,
        editStart: Int,
        editEnd: Int,
        replacement: String,
    ): List<FlaggedWord> {
        ClarifierController.setFlagsForTest(flags, cycleIndex)
        ClarifierController.shiftFlagsAfterEdit(editStart, editEnd, replacement.length)
        return ClarifierController.flagsForTest()
    }

    @Test fun rangesAfterTheEditMoveWithIt() {
        val flags = listOf(flag(9, 14, "clint"), flag(21, 24, "teh"), flag(25, 31, "reprot"))
        // sanity: the offsets describe the text we think they do
        assertEquals("clint", text.substring(9, 14))
        assertEquals("teh", text.substring(21, 24))
        assertEquals("reprot", text.substring(25, 31))

        val after = text.substring(0, 9) + "client" + text.substring(14)
        val out = shifted(flags, cycleIndex = 0, editStart = 9, editEnd = 14, replacement = "client")

        assertEquals("teh", after.substring(out[1].rangeStart, out[1].rangeEnd))
        assertEquals("reprot", after.substring(out[2].rangeStart, out[2].rangeEnd))
    }

    @Test fun rangesBeforeTheEditDoNotMove() {
        val flags = listOf(flag(21, 24, "teh"), flag(5, 8, "teh"))
        val out = shifted(flags, cycleIndex = 0, editStart = 21, editEnd = 24, replacement = "the")
        assertEquals(5, out[1].rangeStart)
        assertEquals(8, out[1].rangeEnd)
    }

    @Test fun aFlagOverlappingTheEditIsDropped() {
        val flags = listOf(flag(9, 14, "clint"), flag(10, 13, "lin"))
        val out = shifted(flags, cycleIndex = 0, editStart = 9, editEnd = 14, replacement = "client")
        assertEquals(1, out.size)
    }

    @Test fun aShorterReplacementShiftsBackwards() {
        val flags = listOf(flag(9, 14, "clint"), flag(21, 24, "teh"))
        val after = text.substring(0, 9) + "cli" + text.substring(14)
        val out = shifted(flags, cycleIndex = 0, editStart = 9, editEnd = 14, replacement = "cli")
        assertEquals("teh", after.substring(out[1].rangeStart, out[1].rangeEnd))
    }
}
