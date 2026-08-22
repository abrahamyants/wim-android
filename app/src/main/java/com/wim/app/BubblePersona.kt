package com.wim.app

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn

/**
 * Spec §3 — observes [BubbleStateBus.state] and reacts on the bubble's
 * "personality" surface. Phase 1 ships the skeleton: log every state
 * transition + drive the existing pipeline-stage label through
 * [TranscriptBarController]. Phase 3 layers on the affect catalog
 * (RESTING / LEANING_FORWARD / THINKING_SWAY / HEAD_TILT / TA_DA /
 * COMMISERATE).
 *
 * Affects are NOT states — they are decorations. This observer turns
 * state transitions into decoration triggers without ever calling
 * `advance()` itself. (Affects observing state and self-cancelling on
 * the next state is the §1 priority-3 rule.)
 *
 * Single-instance per [BubbleService]; bind in [BubbleService.onCreate]
 * with the service's coroutine scope, unbind in [BubbleService.onDestroy].
 */
class BubblePersona {

    private var observeJob: Job? = null

    /**
     * Start observing. Call once. Idempotent — multiple binds replace
     * the prior observer.
     */
    fun bind(scope: CoroutineScope) {
        // StateFlow already deduplicates equal-value emissions, so no
        // distinctUntilChanged needed.
        observeJob?.cancel()
        observeJob = BubbleStateBus.state
            .onEach { state -> onState(state) }
            .launchIn(scope)
    }

    fun unbind() {
        observeJob?.cancel()
        observeJob = null
    }

    /**
     * Phase 1: log + route stage label through [TranscriptBarController].
     * Visual affects are deferred to Phase 3 — the goal here is to prove
     * the FSM drives the existing UX without UX change.
     */
    private fun onState(state: BubbleState) {
        Log.i(TAG, "state → ${state.javaClass.simpleName}")
        val (label, detail) = stageLabelFor(state) ?: run {
            // Non-pipeline states (Idle / SendReady / Listening / Failed)
            // — leave the bar to its other owners.
            if (state is BubbleState.Idle ||
                state is BubbleState.SendReady ||
                state is BubbleState.Failed
            ) {
                TranscriptBarController.clear(TranscriptBarController.Priority.STAGE)
            }
            return
        }
        TranscriptBarController.show(
            TranscriptBarController.Priority.STAGE,
            "$label · $detail",
        )
    }

    /**
     * Map pipeline states to their on-bar label + detail. Returns null
     * for non-pipeline states. Format mirrors existing `showPipelineStage`
     * conventions so the visual stays unchanged.
     *
     * Min-dwell per stage is enforced by [TranscriptBarController] (Phase
     * 1 step 9 lands the dwell map inside the controller, not here).
     */
    private fun stageLabelFor(state: BubbleState): Pair<String, String>? = when (state) {
        is BubbleState.Transcribing -> "⟳ Transcribing" to "ASR running…"
        is BubbleState.Stripping -> "⛬ Cleaning up" to "Removing disfluencies"
        is BubbleState.ReadingContext -> "⌕ Reading context" to "Detecting recipient + tone"
        is BubbleState.Reconstructing -> "✧ Reconstructing" to "L${state.layer} model running"
        is BubbleState.Verified -> "✓ Meaning verified" to "Integrity check passed"
        is BubbleState.Injected -> "↪ Injected" to cueDetail(state.cue)
        is BubbleState.Clarifying -> "? Clarifying" to "${state.flagged.size} word${if (state.flagged.size == 1) "" else "s"}"
        else -> null
    }

    private fun cueDetail(cue: SuccessCue): String = when (cue) {
        SuccessCue.PASTE -> "pasted"
        SuccessCue.AUTO_SEND -> "ready to send"
        SuccessCue.COMMAND_REPLACE -> "rewritten"
    }

    companion object {
        private const val TAG = "WiM.Persona"
    }
}
