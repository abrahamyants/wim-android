package com.wim.app

import com.wim.app.WimAccessibilityService.InjectTarget
import com.wim.app.WimAccessibilityService.Companion.pickInjectTarget
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where the words go after a take.
 *
 * George 10/12: "it records, it processes, but it doesn't inject — misses
 * once in a while." The log said ACTION_SET_TEXT ok every time. The
 * remembered field was still alive, so refresh() passed, but the cursor had
 * moved to another field and the words landed where nobody was looking.
 * The rule now: the remembered field wins only while it holds the cursor.
 */
class WimInjectTargetTest {

    @Test fun theRememberedFieldWinsWhileItHoldsTheCursor() {
        assertEquals(InjectTarget.REMEMBERED,
            pickInjectTarget(rememberedAlive = true, rememberedFocused = true, refoundExists = true))
        assertEquals(InjectTarget.REMEMBERED,
            pickInjectTarget(rememberedAlive = true, rememberedFocused = true, refoundExists = false))
    }

    @Test fun aRememberedFieldThatLostTheCursorYieldsToTheOneThatHasIt() {
        // The 10/12 miss: old field alive, cursor elsewhere.
        assertEquals(InjectTarget.REFOUND,
            pickInjectTarget(rememberedAlive = true, rememberedFocused = false, refoundExists = true))
    }

    @Test fun withNothingRememberedTheCursorFieldIsUsed() {
        assertEquals(InjectTarget.REFOUND,
            pickInjectTarget(rememberedAlive = false, rememberedFocused = false, refoundExists = true))
    }

    @Test fun aHostThatNeverReportsFocusKeepsTheOldBehaviour() {
        // No field anywhere claims the cursor: fall back to the remembered one
        // rather than bounce the words to the clipboard.
        assertEquals(InjectTarget.REMEMBERED_UNFOCUSED,
            pickInjectTarget(rememberedAlive = true, rememberedFocused = false, refoundExists = false))
    }

    @Test fun noFieldAtAllMeansClipboard() {
        assertEquals(InjectTarget.NONE,
            pickInjectTarget(rememberedAlive = false, rememberedFocused = false, refoundExists = false))
    }
}
