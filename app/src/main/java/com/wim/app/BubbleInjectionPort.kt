package com.wim.app

/**
 * The set of host-app actions the bubble asks the accessibility service ("the
 * hands") to perform — typing into the focused field, reading pre-cursor
 * context, tapping the host app's Send button, etc.
 *
 * Extracted (finding H8) so [BubbleService] talks to this contract instead of
 * grabbing the [WimAccessibilityService] singleton (`instance`) directly. That
 * lets the recording / reconstruction pipeline be unit-tested with a fake port
 * and removes the `instance!!` crash sites in the bubble.
 *
 * Production implementation: [WimAccessibilityService]. Tests can set
 * [BubbleService.testInjectionPort] to a fake.
 */
interface BubbleInjectionPort {
    fun injectText(text: String): WimAccessibilityService.InjectResult
    fun replaceLastInjected(replacement: String): Boolean
    fun swapWordInLastInjection(rangeStart: Int, rangeEnd: Int, replacement: String): Boolean
    fun getPreCursorContext(maxChars: Int = 200): String?
    fun focusedAppSupportsAutoSend(): Boolean
    fun focusedFieldBounds(): android.graphics.Rect?
    fun clickSendButton(): Boolean
    fun getSendButtonCenter(): Pair<Int, Int>?
}
