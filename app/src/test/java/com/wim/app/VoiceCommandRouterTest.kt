package com.wim.app

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [VoiceCommandRouter].
 *
 * Robolectric required: VoiceCommandRouter.dispatch calls android.util.Log.e
 * when a handler throws an exception, which crashes under plain JVM.
 *
 * VoiceCommandRouter is a Kotlin object (singleton), so each test calls
 * reset() in @Before to clear the handler list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class VoiceCommandRouterTest {

    @Before fun clearRouter() {
        VoiceCommandRouter.reset()
    }

    // ── No handlers registered ──────────────────────────

    @Test fun noHandlers_dispatchReturnsNull() {
        val result = VoiceCommandRouter.dispatch("hello")
        assertNull("No handlers → null (nothing claimed the utterance)", result)
    }

    @Test fun noHandlers_blankText_returnsNull() {
        val result = VoiceCommandRouter.dispatch("   ")
        assertNull(result)
    }

    // ── Single handler — claims ─────────────────────────

    @Test fun singleHandler_claims_returnsHandlerName() {
        VoiceCommandRouter.register("alpha") { true }
        val result = VoiceCommandRouter.dispatch("next")
        assertEquals("alpha", result)
    }

    @Test fun singleHandler_declines_returnsNull() {
        VoiceCommandRouter.register("alpha") { false }
        val result = VoiceCommandRouter.dispatch("next")
        assertNull(result)
    }

    // ── Chain stops on first true return ────────────────

    @Test fun firstHandlerClaims_secondHandlerNotCalled() {
        var secondCalled = false
        VoiceCommandRouter.register("first") { true }
        VoiceCommandRouter.register("second") { secondCalled = true; false }
        VoiceCommandRouter.dispatch("hello")
        assertFalse("Second handler must not be called when first claims the utterance", secondCalled)
    }

    @Test fun firstHandlerClaims_dispatchReturnFirstName() {
        VoiceCommandRouter.register("clarifier") { true }
        VoiceCommandRouter.register("dictation") { true }
        val result = VoiceCommandRouter.dispatch("yes")
        assertEquals("clarifier", result)
    }

    // ── Chain continues on false return ─────────────────

    @Test fun firstHandlerDeclines_secondHandlerCalled() {
        var secondCalled = false
        VoiceCommandRouter.register("first") { false }
        VoiceCommandRouter.register("second") { secondCalled = true; true }
        VoiceCommandRouter.dispatch("hello")
        assertTrue("Second handler must be called when first declines", secondCalled)
    }

    @Test fun firstHandlerDeclines_secondClaims_returnsSecondName() {
        VoiceCommandRouter.register("twist") { false }
        VoiceCommandRouter.register("dictation") { true }
        val result = VoiceCommandRouter.dispatch("start recording")
        assertEquals("dictation", result)
    }

    @Test fun allHandlersDecline_returnsNull() {
        VoiceCommandRouter.register("h1") { false }
        VoiceCommandRouter.register("h2") { false }
        VoiceCommandRouter.register("h3") { false }
        val result = VoiceCommandRouter.dispatch("anything")
        assertNull(result)
    }

    // ── Handler throws exception ─────────────────────────

    @Test fun throwingHandler_returnsFalse_doesNotCrash() {
        VoiceCommandRouter.register("buggy") { throw RuntimeException("oops") }
        val result = VoiceCommandRouter.dispatch("hello")
        // Throwing handler is treated as "declined" → null (no handler claimed it)
        assertNull("Throwing handler must be treated as false, not crash", result)
    }

    @Test fun throwingHandler_chainContinuesToNextHandler() {
        var secondCalled = false
        VoiceCommandRouter.register("buggy") { throw IllegalStateException("bad state") }
        VoiceCommandRouter.register("good") { secondCalled = true; true }
        val result = VoiceCommandRouter.dispatch("hello")
        assertTrue("Chain must continue past a throwing handler", secondCalled)
        assertEquals("good", result)
    }

    @Test fun throwingHandler_afterGoodHandler_firstClaims() {
        VoiceCommandRouter.register("good") { true }
        VoiceCommandRouter.register("buggy") { throw RuntimeException("never reached") }
        val result = VoiceCommandRouter.dispatch("yes")
        assertEquals("good", result)
    }

    // ── Duplicate registration ───────────────────────────

    @Test fun duplicateName_secondRegistrationIgnored() {
        var callCount = 0
        VoiceCommandRouter.register("alpha") { callCount++; false }
        VoiceCommandRouter.register("alpha") { callCount++; false } // duplicate — should be ignored
        VoiceCommandRouter.dispatch("hello")
        assertEquals(
            "Duplicate name registration must be ignored; handler called only once",
            1,
            callCount
        )
    }

    @Test fun duplicateName_originalHandlerPreserved() {
        VoiceCommandRouter.register("alpha") { true }   // returns true
        VoiceCommandRouter.register("alpha") { false }  // duplicate — ignored
        val result = VoiceCommandRouter.dispatch("hello")
        assertEquals("Original handler (returns true) must be preserved", "alpha", result)
    }

    // ── unregister ──────────────────────────────────────

    @Test fun unregister_removesHandler() {
        var called = false
        VoiceCommandRouter.register("alpha") { called = true; true }
        VoiceCommandRouter.unregister("alpha")
        VoiceCommandRouter.dispatch("hello")
        assertFalse("Unregistered handler must not be called", called)
    }

    @Test fun unregister_nonExistent_doesNotCrash() {
        // Should not throw
        VoiceCommandRouter.unregister("does_not_exist")
    }

    @Test fun unregister_afterUnregister_returnsNull() {
        VoiceCommandRouter.register("alpha") { true }
        VoiceCommandRouter.unregister("alpha")
        val result = VoiceCommandRouter.dispatch("hello")
        assertNull(result)
    }

    // ── Blank text guard ────────────────────────────────

    @Test fun blankText_returnsNull_handlersNotCalled() {
        var called = false
        VoiceCommandRouter.register("alpha") { called = true; true }
        val result = VoiceCommandRouter.dispatch("")
        assertNull("Blank text must return null without calling any handler", result)
        assertFalse(called)
    }

    @Test fun whitespaceOnlyText_returnsNull() {
        VoiceCommandRouter.register("alpha") { true }
        val result = VoiceCommandRouter.dispatch("   \t  ")
        assertNull(result)
    }

    // ── Text is passed correctly to handler ─────────────

    @Test fun dispatchedText_passedToHandler() {
        var received = ""
        VoiceCommandRouter.register("listener") { text -> received = text; false }
        VoiceCommandRouter.dispatch("hello world")
        assertEquals("hello world", received)
    }

    // ── Priority order (registration order = priority) ──

    @Test fun registrationOrder_definesDispatchPriority() {
        val order = mutableListOf<String>()
        VoiceCommandRouter.register("first")  { order.add("first"); false }
        VoiceCommandRouter.register("second") { order.add("second"); false }
        VoiceCommandRouter.register("third")  { order.add("third"); false }
        VoiceCommandRouter.dispatch("test")
        assertEquals(listOf("first", "second", "third"), order)
    }

    // ── reset() ─────────────────────────────────────────

    @Test fun reset_clearsAllHandlers() {
        VoiceCommandRouter.register("h1") { true }
        VoiceCommandRouter.register("h2") { true }
        VoiceCommandRouter.reset()
        val result = VoiceCommandRouter.dispatch("hello")
        assertNull("After reset, no handlers remain → null", result)
    }
}
