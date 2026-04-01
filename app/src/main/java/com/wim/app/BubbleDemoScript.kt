package com.wim.app

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The bubble's live demo performance for onboarding.
 * Mr. DNA gets shoved off. The bubble takes over and performs every gesture
 * while narrating through the live transcript bar.
 *
 * This runs on the REAL BubbleService — not a simulation.
 */
class BubbleDemoScript(private val onComplete: () -> Unit) {

    private val handler = Handler(Looper.getMainLooper())
    private var cancelled = false
    private val steps = mutableListOf<Pair<Long, () -> Unit>>()

    fun cancel() {
        cancelled = true
        handler.removeCallbacksAndMessages(null)
    }

    fun start() {
        val bubble = BubbleService.instance ?: run { onComplete(); return }

        // Build the script — each step is (delay from start in ms, action)
        steps.clear()
        var t = 0L

        // ── Act 1: Entrance ──
        step(t) { bubble.showPipelineStage("👋", "Hey! I'm your WiM bubble.") }

        t += 2500
        step(t) { bubble.showPipelineStage("😏", "Nah. It's my show. Let me show you around.") }

        // ── Act 2: Tap to record ──
        t += 3000
        step(t) {
            bubble.showPipelineStage("TAP", "Tap me once to start recording.")
            // Simulate recording state briefly
            simulateRecordingPulse(bubble)
        }

        t += 3000
        step(t) {
            bubble.showPipelineStage("TAP", "Tap again to stop. I'll clean up your words.")
            bubble.setBubbleIdlePublic()
        }

        // ── Act 3: Hold to record ──
        t += 3000
        step(t) {
            bubble.showPipelineStage("HOLD", "Or just hold me down. Release when done.")
            simulateRecordingPulse(bubble)
        }

        t += 2500
        step(t) { bubble.setBubbleIdlePublic() }

        // ── Act 4: The magic — reconstruction demo ──
        t += 1000
        step(t) {
            bubble.showPipelineStage("✨ Watch this", "Someone says:")
        }

        t += 2000
        step(t) {
            bubble.showPipelineStage("🎙 Raw", "\"so um I I was trying to to tell him the the project is done\"")
        }

        t += 3500
        step(t) {
            bubble.showPipelineStage("⟳ Cleaning...", "Removing disfluencies & fillers")
        }

        t += 2000
        step(t) {
            bubble.showPipelineStage("✓ Clean", "\"I was trying to tell him the project is done.\"")
        }

        t += 3000
        step(t) {
            bubble.showPipelineStage("💼 → Boss", "Professional: \"The project has been completed.\"")
        }

        t += 3000
        step(t) {
            bubble.showPipelineStage("😎 → Friend", "Casual: \"lol yeah project's done finally\"")
        }

        t += 3000
        step(t) {
            bubble.showPipelineStage("🔄", "Same voice. Right tone. Every time.")
        }

        // ── Act 5: Double-tap menu ──
        t += 3000
        step(t) {
            bubble.showPipelineStage("TAP TAP", "Double-tap me for quick settings.")
        }

        t += 1500
        step(t) {
            // Actually show the menu briefly
            bubble.showMenuPublic()
        }

        t += 2500
        step(t) {
            bubble.dismissMenuPublic()
            bubble.showPipelineStage("⚙️", "Size, opacity, layer — all right here.")
        }

        // ── Act 6: Visual anchors — layers ──
        t += 3000
        step(t) {
            bubble.showPipelineStage("LAYER", "I have 4 layers. You pick what you need.")
        }

        t += 2500
        step(t) {
            bubble.showPipelineStage("I ▸ Transcribe", "Free. Instant. Just cleans up your words.")
        }

        t += 2500
        step(t) {
            bubble.showPipelineStage("II ▸ Reconstruct", "AI rewrites with your tone. ~2 seconds.")
        }

        t += 2500
        step(t) {
            bubble.showPipelineStage("III ▸ Profile", "Learns YOUR vocabulary over time.")
        }

        t += 2500
        step(t) {
            bubble.showPipelineStage("IV ▸ Stutter", "Full clinical mode. Built for people who stutter.")
        }

        // ── Act 7: Situations ──
        t += 3000
        step(t) {
            bubble.showPipelineStage("SITUATION", "Heading into a tough call? Tell me.")
        }

        t += 2500
        step(t) {
            bubble.showPipelineStage("🔥 High Stress", "I'll go full power. DAF on. Layer IV. Everything.")
        }

        t += 2500
        step(t) {
            bubble.showPipelineStage("📖 Reading", "Light touch. You're already fluent here.")
        }

        // ── Act 8: Hide and show ──
        t += 3000
        step(t) {
            bubble.showPipelineStage("DRAG ↓", "Drag me to the bottom to hide.")
        }

        t += 2500
        step(t) {
            bubble.showPipelineStage("📳 SHAKE", "Shake your phone twice to bring me back.")
        }

        // ── Act 9: Clarifier ──
        t += 3000
        step(t) {
            bubble.showPipelineStage("❓", "Sometimes I'll ask a quick question.")
            bubble.showClarifier("WORK?", "Is this for work or casual?") { _ ->
                // Auto-dismiss after demo
            }
        }

        t += 4000
        step(t) {
            bubble.showPipelineStage("✓ / ✕", "Tap left for yes, right for no. Easy.")
        }

        // ── Act 10: Tips ──
        t += 3000
        step(t) {
            bubble.showPipelineStage("💡 Tip", "I'll pop up with reminders sometimes. Like this.")
        }

        t += 2500
        step(t) {
            bubble.showPipelineStage("💡", "You can turn them off in Settings.")
        }

        // ── Act 11: Finale ──
        t += 3000
        step(t) {
            bubble.showPipelineStage("☕", "Spill your coffee. Stutter your words. I don't judge.")
        }

        t += 3000
        step(t) {
            bubble.showPipelineStage("🎬", "That's my show. Your turn now.")
        }

        t += 3000
        step(t) {
            bubble.hideLiveTranscriptPublic()
            bubble.setBubbleIdlePublic()
            onComplete()
        }

        // Run all steps
        for ((delay, action) in steps) {
            handler.postDelayed({
                if (!cancelled) action()
            }, delay)
        }
    }

    private fun step(delayMs: Long, action: () -> Unit) {
        steps.add(Pair(delayMs, action))
    }

    private fun simulateRecordingPulse(bubble: BubbleService) {
        // Briefly show recording state
        bubble.bubbleView?.findViewById<FrameLayout>(R.id.bubbleBezel)
            ?.setBackgroundResource(R.drawable.bubble_bg_recording)
        bubble.bubbleView?.findViewById<FrameLayout>(R.id.bubbleInner)
            ?.setBackgroundResource(R.drawable.bubble_inner_active)
        bubble.bubbleView?.findViewById<TextView>(R.id.bubbleLabel)?.apply {
            text = "REC"
            setTextColor(0xFFEF4444.toInt())
        }
    }
}
