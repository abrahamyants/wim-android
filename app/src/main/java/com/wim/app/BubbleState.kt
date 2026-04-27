package com.wim.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for "what is the bubble doing right now."
 *
 * Spec §1 reconciliation: Spec A's PipelinePhase enum AND Spec B's CLARIFY
 * state collapse into ONE machine. Affects (Spec A's Affect enum) are NOT
 * states — they are decorations rendered on top of the active state.
 * Predictions are NOT states either — they are a sidecar overlay attached
 * to Idle.
 *
 * Transition matrix lives in [BubbleStateBus.advance]. The matrix is the
 * load-bearing lock for the unified build — every later phase observes
 * state via [BubbleStateBus.state] and reacts to transitions.
 */
sealed class BubbleState {
    object Idle : BubbleState()
    data class Listening(val recordingStartMs: Long) : BubbleState()
    data class Transcribing(val startMs: Long) : BubbleState()
    data class Stripping(val startMs: Long) : BubbleState()
    data class ReadingContext(val startMs: Long) : BubbleState()
    data class Reconstructing(val startMs: Long, val layer: Int) : BubbleState()
    data class Verified(val startMs: Long) : BubbleState()
    data class Injected(val startMs: Long, val cue: SuccessCue) : BubbleState()
    data class Clarifying(
        val flagged: List<FlaggedWord>,
        val cycleIndex: Int,
        val enteredAtMs: Long,
    ) : BubbleState()
    object SendReady : BubbleState()
    data class Failed(val reason: FailReason) : BubbleState()
}

enum class SuccessCue { PASTE, AUTO_SEND, COMMAND_REPLACE, REGEN_SUCCESS }
enum class FailReason { NETWORK, HALLUCINATED, FALCON_REJECT, INJECT_FAIL }

/**
 * Forward declaration — full type lives in `Clarifier.kt` (Phase 2).
 * Phase 0 only needs the symbol so the sealed class compiles.
 */
data class FlaggedWord(
    val originalWord: String,
    val candidates: List<String>,
    val rangeStart: Int,
    val rangeEnd: Int,
    val uncertaintyScore: Double,
)

/**
 * Single mutable holder of the current [BubbleState]. Subscribers observe
 * via [state]; mutators MUST go through [advance] so the transition matrix
 * is enforced.
 *
 * Hot-flow semantics — late subscribers receive the current state on
 * subscription, so observers can attach in any order.
 */
object BubbleStateBus {
    private val _state = MutableStateFlow<BubbleState>(BubbleState.Idle)
    val state: StateFlow<BubbleState> = _state.asStateFlow()

    /** Current snapshot — for tap handlers and synchronous reads. */
    fun current(): BubbleState = _state.value

    /**
     * Validate against the transition matrix from spec §1, then commit.
     *
     * Returns true if the transition was applied, false if it was rejected
     * as forbidden. Logs forbidden transitions; never throws — the bubble
     * cannot afford to crash on a bad state advance during a recording.
     */
    fun advance(next: BubbleState): Boolean {
        val from = _state.value
        if (!isValidTransition(from, next)) {
            android.util.Log.e(
                "BubbleStateBus",
                "FORBIDDEN transition: ${stateName(from)} → ${stateName(next)} — ignoring",
            )
            return false
        }
        _state.value = next
        return true
    }

    /**
     * Force-set state without transition validation. Use only in lifecycle
     * teardown / onDestroy paths where we want to slam back to Idle without
     * caring about the FSM.
     */
    fun reset() {
        _state.value = BubbleState.Idle
    }

    private fun isValidTransition(from: BubbleState, to: BubbleState): Boolean {
        // Listening always allowed from Idle / SendReady / Failed / Clarifying
        // (user always wins; recording is never blocked) — except FORBIDDEN
        // from Reconstructing (in-flight ASR cannot be interrupted).
        if (to is BubbleState.Listening) {
            return when (from) {
                is BubbleState.Reconstructing -> false
                is BubbleState.Idle,
                is BubbleState.SendReady,
                is BubbleState.Failed,
                is BubbleState.Clarifying,
                is BubbleState.Listening -> true
                else -> true // permissive — user gesture wins
            }
        }
        return when (from) {
            is BubbleState.Idle -> when (to) {
                is BubbleState.Listening -> true
                else -> false
            }
            is BubbleState.Listening -> when (to) {
                is BubbleState.Transcribing -> true
                is BubbleState.Idle -> true // cancel
                else -> false
            }
            is BubbleState.Transcribing -> when (to) {
                is BubbleState.Stripping -> true
                is BubbleState.Failed -> true
                is BubbleState.Idle -> true // hallucination-bail
                else -> false
            }
            is BubbleState.Stripping -> when (to) {
                is BubbleState.ReadingContext -> true
                is BubbleState.Reconstructing -> true
                is BubbleState.Failed -> true
                else -> false
            }
            is BubbleState.ReadingContext -> when (to) {
                is BubbleState.Reconstructing -> true
                is BubbleState.Failed -> true
                else -> false
            }
            is BubbleState.Reconstructing -> when (to) {
                is BubbleState.Verified -> true
                is BubbleState.Failed -> true
                else -> false
            }
            is BubbleState.Verified -> when (to) {
                is BubbleState.Injected -> true
                is BubbleState.Failed -> true
                else -> false
            }
            is BubbleState.Injected -> when (to) {
                is BubbleState.SendReady -> true
                is BubbleState.Clarifying -> true
                is BubbleState.Idle -> true
                else -> false
            }
            is BubbleState.Clarifying -> when (to) {
                is BubbleState.SendReady -> true
                is BubbleState.Idle -> true
                else -> false
            }
            is BubbleState.SendReady -> when (to) {
                is BubbleState.Idle -> true
                else -> false
            }
            is BubbleState.Failed -> when (to) {
                is BubbleState.Idle -> true
                else -> false
            }
        }
    }

    private fun stateName(s: BubbleState): String = when (s) {
        is BubbleState.Idle -> "Idle"
        is BubbleState.Listening -> "Listening"
        is BubbleState.Transcribing -> "Transcribing"
        is BubbleState.Stripping -> "Stripping"
        is BubbleState.ReadingContext -> "ReadingContext"
        is BubbleState.Reconstructing -> "Reconstructing(L${s.layer})"
        is BubbleState.Verified -> "Verified"
        is BubbleState.Injected -> "Injected(${s.cue})"
        is BubbleState.Clarifying -> "Clarifying(${s.flagged.size}, idx=${s.cycleIndex})"
        is BubbleState.SendReady -> "SendReady"
        is BubbleState.Failed -> "Failed(${s.reason})"
    }
}
