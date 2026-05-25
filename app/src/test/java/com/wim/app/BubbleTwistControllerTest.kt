package com.wim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Locks in the strict-prefix trigger detection so the voice command
 * recognizer doesn't drift into either false-positive territory ("the
 * settings size on this phone is wrong" should NOT enter twist mode) or
 * false-negative territory ("knob size" with a trailing punctuation
 * should still trigger).
 */
class BubbleTwistControllerTest {

    @Test fun emptyStringReturnsNull() {
        assertNull(BubbleTwistController.detectTrigger(""))
    }

    @Test fun unrelatedTextReturnsNull() {
        assertNull(BubbleTwistController.detectTrigger("hello world"))
        assertNull(BubbleTwistController.detectTrigger("change the knob"))
    }

    @Test fun knobSizeTriggers() {
        assertEquals(BubbleTwistController.Mode.SIZE, BubbleTwistController.detectTrigger("knob size"))
    }

    @Test fun settingsSizeTriggers() {
        assertEquals(BubbleTwistController.Mode.SIZE, BubbleTwistController.detectTrigger("settings size"))
    }

    @Test fun knobOpacityTriggers() {
        assertEquals(BubbleTwistController.Mode.OPACITY, BubbleTwistController.detectTrigger("knob opacity"))
    }

    @Test fun settingsOpacityTriggers() {
        assertEquals(BubbleTwistController.Mode.OPACITY, BubbleTwistController.detectTrigger("settings opacity"))
    }

    @Test fun trailingPunctuationIsStripped() {
        assertEquals(BubbleTwistController.Mode.SIZE, BubbleTwistController.detectTrigger("knob size."))
        assertEquals(BubbleTwistController.Mode.SIZE, BubbleTwistController.detectTrigger("knob size!"))
        assertEquals(BubbleTwistController.Mode.SIZE, BubbleTwistController.detectTrigger("knob size?"))
        assertEquals(BubbleTwistController.Mode.SIZE, BubbleTwistController.detectTrigger("knob size,"))
    }

    @Test fun caseInsensitive() {
        assertEquals(BubbleTwistController.Mode.SIZE, BubbleTwistController.detectTrigger("KNOB SIZE"))
        assertEquals(BubbleTwistController.Mode.OPACITY, BubbleTwistController.detectTrigger("Settings Opacity"))
    }

    @Test fun leadingWhitespaceIsTrimmed() {
        assertEquals(BubbleTwistController.Mode.SIZE, BubbleTwistController.detectTrigger("  knob size"))
        assertEquals(BubbleTwistController.Mode.OPACITY, BubbleTwistController.detectTrigger("\n knob opacity \t"))
    }

    @Test fun strictPrefixOnly_noMidSentenceMatch() {
        // Critical: "the settings size on this phone" must NOT trigger,
        // otherwise normal conversation could enter twist mode.
        assertNull(BubbleTwistController.detectTrigger("the settings size on this phone is wrong"))
        assertNull(BubbleTwistController.detectTrigger("change the knob size"))
    }

    @Test fun extraWordsAfterTriggerAllowed() {
        // "knob size please" — still starts with trigger phrase, should fire.
        assertEquals(BubbleTwistController.Mode.SIZE, BubbleTwistController.detectTrigger("knob size please"))
    }
}
