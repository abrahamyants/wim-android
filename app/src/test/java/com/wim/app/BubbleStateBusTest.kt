package com.wim.app

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [BubbleStateBus].
 *
 * Robolectric required: [BubbleStateBus.advance] calls android.util.Log.e
 * on forbidden transitions, which crashes under plain JVM.
 *
 * [BubbleStateBus] is a Kotlin object (singleton), so each test resets
 * state to Idle in @Before to prevent cross-test contamination.
 *
 * StateFlow.value is synchronously readable — no coroutine dispatcher
 * needed for these tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class BubbleStateBusTest {

    @Before fun resetBus() {
        BubbleStateBus.reset()
    }

    // ── Helpers ─────────────────────────────────────────

    private fun listening()      = BubbleState.Listening(recordingStartMs = 0L)
    private fun transcribing()   = BubbleState.Transcribing(startMs = 0L)
    private fun stripping()      = BubbleState.Stripping(startMs = 0L)
    private fun readingContext() = BubbleState.ReadingContext(startMs = 0L)
    private fun reconstructing() = BubbleState.Reconstructing(startMs = 0L, layer = 4)
    private fun verified()       = BubbleState.Verified(startMs = 0L)
    private fun injected()       = BubbleState.Injected(startMs = 0L, cue = SuccessCue.PASTE)
    private fun clarifying()     = BubbleState.Clarifying(flagged = emptyList(), cycleIndex = 0, enteredAtMs = 0L)
    private fun failed()         = BubbleState.Failed(reason = FailReason.NETWORK)

    /** Drive the bus through a sequence of states, asserting each advance succeeds. */
    private fun driveThrough(vararg states: BubbleState) {
        for (s in states) {
            val ok = BubbleStateBus.advance(s)
            assertTrue("Expected valid transition to ${s::class.simpleName}, got false", ok)
        }
    }

    // ── Initial state ───────────────────────────────────

    @Test fun initialState_isIdle() {
        assertTrue(BubbleStateBus.current() is BubbleState.Idle)
    }

    // ── reset() ─────────────────────────────────────────

    @Test fun reset_fromListening_goesToIdle() {
        BubbleStateBus.advance(listening())
        BubbleStateBus.reset()
        assertTrue(BubbleStateBus.current() is BubbleState.Idle)
    }

    @Test fun reset_fromInjected_goesToIdle() {
        driveThrough(listening(), transcribing(), stripping(), readingContext(),
            reconstructing(), verified(), injected())
        BubbleStateBus.reset()
        assertTrue(BubbleStateBus.current() is BubbleState.Idle)
    }

    @Test fun reset_fromFailed_goesToIdle() {
        driveThrough(listening(), transcribing(), failed())
        BubbleStateBus.reset()
        assertTrue(BubbleStateBus.current() is BubbleState.Idle)
    }

    // ── forceTo() ───────────────────────────────────────

    @Test fun forceTo_bypasses_matrix_and_sets_state() {
        // Idle → Injected is FORBIDDEN via advance, but forceTo should succeed
        BubbleStateBus.forceTo(injected())
        assertTrue(BubbleStateBus.current() is BubbleState.Injected)
    }

    @Test fun forceTo_fromInjected_toClarifying() {
        driveThrough(listening(), transcribing(), stripping(), readingContext(),
            reconstructing(), verified(), injected())
        BubbleStateBus.forceTo(clarifying())
        assertTrue(BubbleStateBus.current() is BubbleState.Clarifying)
    }

    // ── current() reads correctly ────────────────────────

    @Test fun current_reflectsLastAdvance() {
        BubbleStateBus.advance(listening())
        assertTrue(BubbleStateBus.current() is BubbleState.Listening)
        BubbleStateBus.advance(transcribing())
        assertTrue(BubbleStateBus.current() is BubbleState.Transcribing)
    }

    // ── FORBIDDEN: Idle → anything except Listening ─────

    @Test fun idle_to_transcribing_isForbidden() {
        val ok = BubbleStateBus.advance(transcribing())
        assertFalse(ok)
        assertTrue("State must not change on forbidden transition",
            BubbleStateBus.current() is BubbleState.Idle)
    }

    @Test fun idle_to_stripping_isForbidden() {
        val ok = BubbleStateBus.advance(stripping())
        assertFalse(ok)
        assertTrue(BubbleStateBus.current() is BubbleState.Idle)
    }

    @Test fun idle_to_injected_isForbidden() {
        // THE critical bug guard: Idle → Injected broke the demo clarifier
        val ok = BubbleStateBus.advance(injected())
        assertFalse("Idle → Injected must be FORBIDDEN", ok)
        assertTrue("State must remain Idle after forbidden transition",
            BubbleStateBus.current() is BubbleState.Idle)
    }

    @Test fun idle_to_clarifying_isForbidden() {
        val ok = BubbleStateBus.advance(clarifying())
        assertFalse(ok)
        assertTrue(BubbleStateBus.current() is BubbleState.Idle)
    }

    @Test fun idle_to_verified_isForbidden() {
        val ok = BubbleStateBus.advance(verified())
        assertFalse(ok)
        assertTrue(BubbleStateBus.current() is BubbleState.Idle)
    }

    @Test fun idle_to_sendReady_isForbidden() {
        val ok = BubbleStateBus.advance(BubbleState.SendReady)
        assertFalse(ok)
        assertTrue(BubbleStateBus.current() is BubbleState.Idle)
    }

    @Test fun idle_to_failed_isForbidden() {
        val ok = BubbleStateBus.advance(failed())
        assertFalse(ok)
        assertTrue(BubbleStateBus.current() is BubbleState.Idle)
    }

    // ── ALLOWED: Idle → Listening ───────────────────────

    @Test fun idle_to_listening_isAllowed() {
        val ok = BubbleStateBus.advance(listening())
        assertTrue("Idle → Listening must be allowed", ok)
        assertTrue(BubbleStateBus.current() is BubbleState.Listening)
    }

    // ── Happy path ──────────────────────────────────────

    @Test fun happyPath_listening_to_transcribing() {
        driveThrough(listening())
        assertTrue(BubbleStateBus.advance(transcribing()))
        assertTrue(BubbleStateBus.current() is BubbleState.Transcribing)
    }

    @Test fun happyPath_transcribing_to_stripping() {
        driveThrough(listening(), transcribing())
        assertTrue(BubbleStateBus.advance(stripping()))
        assertTrue(BubbleStateBus.current() is BubbleState.Stripping)
    }

    @Test fun happyPath_stripping_to_readingContext() {
        driveThrough(listening(), transcribing(), stripping())
        assertTrue(BubbleStateBus.advance(readingContext()))
    }

    @Test fun happyPath_readingContext_to_reconstructing() {
        driveThrough(listening(), transcribing(), stripping(), readingContext())
        assertTrue(BubbleStateBus.advance(reconstructing()))
    }

    @Test fun happyPath_reconstructing_to_verified() {
        driveThrough(listening(), transcribing(), stripping(), readingContext(), reconstructing())
        assertTrue(BubbleStateBus.advance(verified()))
    }

    @Test fun happyPath_verified_to_injected() {
        driveThrough(listening(), transcribing(), stripping(), readingContext(),
            reconstructing(), verified())
        assertTrue(BubbleStateBus.advance(injected()))
    }

    // ── Critical clarifier path: Injected → Clarifying ──

    @Test fun injected_to_clarifying_isAllowed() {
        driveThrough(listening(), transcribing(), stripping(), readingContext(),
            reconstructing(), verified(), injected())
        val ok = BubbleStateBus.advance(clarifying())
        assertTrue("Injected → Clarifying must be allowed (critical clarifier path)", ok)
        assertTrue(BubbleStateBus.current() is BubbleState.Clarifying)
    }

    // ── Clarifying exits ────────────────────────────────

    @Test fun clarifying_to_sendReady_isAllowed() {
        driveThrough(listening(), transcribing(), stripping(), readingContext(),
            reconstructing(), verified(), injected(), clarifying())
        val ok = BubbleStateBus.advance(BubbleState.SendReady)
        assertTrue(ok)
        assertTrue(BubbleStateBus.current() is BubbleState.SendReady)
    }

    @Test fun clarifying_to_idle_isAllowed() {
        driveThrough(listening(), transcribing(), stripping(), readingContext(),
            reconstructing(), verified(), injected(), clarifying())
        val ok = BubbleStateBus.advance(BubbleState.Idle)
        assertTrue(ok)
        assertTrue(BubbleStateBus.current() is BubbleState.Idle)
    }

    @Test fun clarifying_to_injected_isForbidden() {
        driveThrough(listening(), transcribing(), stripping(), readingContext(),
            reconstructing(), verified(), injected(), clarifying())
        val ok = BubbleStateBus.advance(injected())
        assertFalse("Clarifying → Injected must be forbidden", ok)
        assertTrue(BubbleStateBus.current() is BubbleState.Clarifying)
    }

    // ── Listening is always allowed (user gesture wins) ─

    @Test fun listening_allowedFromSendReady() {
        driveThrough(listening(), transcribing(), stripping(), readingContext(),
            reconstructing(), verified(), injected())
        BubbleStateBus.advance(BubbleState.SendReady)
        val ok = BubbleStateBus.advance(listening())
        assertTrue("Listening must be allowed from SendReady", ok)
    }

    @Test fun listening_allowedFromFailed() {
        driveThrough(listening(), transcribing(), failed())
        val ok = BubbleStateBus.advance(listening())
        assertTrue("Listening must be allowed from Failed", ok)
    }

    @Test fun listening_allowedFromClarifying() {
        driveThrough(listening(), transcribing(), stripping(), readingContext(),
            reconstructing(), verified(), injected(), clarifying())
        val ok = BubbleStateBus.advance(listening())
        assertTrue("Listening must be allowed from Clarifying", ok)
    }

    @Test fun listening_forbiddenFromReconstructing() {
        driveThrough(listening(), transcribing(), stripping(), readingContext(), reconstructing())
        val ok = BubbleStateBus.advance(listening())
        assertFalse("Listening must be FORBIDDEN from Reconstructing (in-flight ASR)", ok)
    }

    // ── Cancel and fail paths ───────────────────────────

    @Test fun listening_to_idle_cancel_isAllowed() {
        driveThrough(listening())
        val ok = BubbleStateBus.advance(BubbleState.Idle)
        assertTrue("Listening → Idle (cancel) must be allowed", ok)
    }

    @Test fun transcribing_to_failed_isAllowed() {
        driveThrough(listening(), transcribing())
        val ok = BubbleStateBus.advance(failed())
        assertTrue(ok)
        assertTrue(BubbleStateBus.current() is BubbleState.Failed)
    }

    @Test fun failed_to_idle_isAllowed() {
        driveThrough(listening(), transcribing(), failed())
        val ok = BubbleStateBus.advance(BubbleState.Idle)
        assertTrue(ok)
        assertTrue(BubbleStateBus.current() is BubbleState.Idle)
    }

    @Test fun failed_to_transcribing_isForbidden() {
        driveThrough(listening(), transcribing(), failed())
        val ok = BubbleStateBus.advance(transcribing())
        assertFalse(ok)
        assertTrue(BubbleStateBus.current() is BubbleState.Failed)
    }

    // ── SendReady exits ─────────────────────────────────

    @Test fun sendReady_to_idle_isAllowed() {
        driveThrough(listening(), transcribing(), stripping(), readingContext(),
            reconstructing(), verified(), injected())
        BubbleStateBus.advance(BubbleState.SendReady)
        val ok = BubbleStateBus.advance(BubbleState.Idle)
        assertTrue(ok)
        assertTrue(BubbleStateBus.current() is BubbleState.Idle)
    }

    @Test fun sendReady_to_transcribing_isForbidden() {
        driveThrough(listening(), transcribing(), stripping(), readingContext(),
            reconstructing(), verified(), injected())
        BubbleStateBus.advance(BubbleState.SendReady)
        val ok = BubbleStateBus.advance(transcribing())
        assertFalse(ok)
        assertTrue(BubbleStateBus.current() is BubbleState.SendReady)
    }

    // ── State does NOT change on rejected transition ─────

    @Test fun forbiddenTransition_doesNotChangeState() {
        // Start at Transcribing; attempt a forbidden jump to Verified
        driveThrough(listening(), transcribing())
        val before = BubbleStateBus.current()
        val ok = BubbleStateBus.advance(verified())
        assertFalse(ok)
        assertEquals(before::class, BubbleStateBus.current()::class)
    }
}
