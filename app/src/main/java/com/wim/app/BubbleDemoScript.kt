package com.wim.app

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The bubble's live demo performance for onboarding + dev preview.
 * Mr. DNA gets shoved off. The bubble takes over and performs every gesture
 * while narrating through the comment-bubble bar.
 *
 * Format: every step uses showPipelineStage(title, body) — title goes
 * on line 1 (with a tape-reel glyph + breathing color), body wraps onto
 * lines 2-4 (relative-size 0.85). The bar is locked at 4 lines so the
 * height never jumps between steps.
 *
 * Pacing: ~4 seconds per step on average. Long enough to read; short
 * enough to keep momentum. Tap the bubble at any point to skip ahead.
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

        steps.clear()
        var t = 0L

        // ── Act 1: Entrance ──
        step(t) { bubble.showPipelineStage("👋  HELLO", "I'm your WiM bubble. Pleasure to meet you.") }

        t += 4000
        step(t) { bubble.showPipelineStage("😏  MY SHOW", "Mr. DNA can sit this one out. Let me show you around.") }

        // ── Act 2: Tap to record ──
        t += 4500
        step(t) {
            bubble.showPipelineStage("👆  TAP", "Tap me once to start recording. That's it.")
            simulateRecordingPulse(bubble)
        }

        t += 4000
        step(t) {
            bubble.showPipelineStage("👆  TAP AGAIN", "Tap a second time to stop. I'll clean it up.")
            bubble.setBubbleIdlePublic()
        }

        // ── Act 3: The magic — reconstruction demo ──
        t += 4000
        step(t) {
            bubble.showPipelineStage("✨  WATCH", "Here's what happens between tap and paste.")
        }

        t += 3500
        step(t) {
            bubble.showPipelineStage("🎙  RAW INPUT", "\"so um I I was trying to to tell him the project is done\"")
        }

        t += 4500
        step(t) {
            bubble.showPipelineStage("⟳  CLEANING", "Stripping fillers, repetitions, false starts...")
        }

        t += 3500
        step(t) {
            bubble.showPipelineStage("✓  CLEAN", "\"I was trying to tell him the project is done.\"")
        }

        t += 4500
        step(t) {
            bubble.showPipelineStage("💼  TO BOSS", "Professional: \"The project has been completed.\"")
        }

        t += 4000
        step(t) {
            bubble.showPipelineStage("😎  TO FRIEND", "Casual: \"yeah project's done finally lol\"")
        }

        t += 4000
        step(t) {
            bubble.showPipelineStage("🎯  ONE VOICE", "Same words. Right tone. Every time.")
        }

        // ── Act 4: Pipeline arcs ──
        t += 4000
        step(t) {
            bubble.showPipelineStage("⏳  PIPELINE", "Watch the arcs around me as I work.")
        }
        t += 1200
        step(t) { bubble.setStageArcPublic(0) }
        step(t) { bubble.showPipelineStage("⏳  TRANSCRIBE", "Audio → text. First arc lights up.") }
        t += 2000
        step(t) { bubble.setStageArcPublic(1) }
        step(t) { bubble.showPipelineStage("🪡  RECONSTRUCT", "Cleanup + tone match. Second arc.") }
        t += 2000
        step(t) { bubble.setStageArcPublic(2) }
        step(t) { bubble.showPipelineStage("🛡  VERIFY", "Cross-checked for meaning loss. Third arc.") }
        t += 2000
        step(t) { bubble.setStageArcPublic(3) }
        step(t) { bubble.showPipelineStage("📌  INJECT", "Pasted into your app. Fourth arc.") }
        t += 1800
        step(t) { bubble.setStageArcPublic(-1) }

        // ── Act 5: Double-tap menu ──
        t += 2200
        step(t) {
            bubble.showPipelineStage("👆👆  DOUBLE-TAP", "Two quick taps open my settings.")
        }

        t += 2000
        step(t) {
            bubble.showMenuPublic()
        }

        t += 3500
        step(t) {
            bubble.dismissMenuPublic()
            bubble.showPipelineStage("⚙  QUICK ADJUST", "Size, opacity, layer, color — all right there.")
        }

        // ── Act 6: Layers ──
        t += 4000
        step(t) {
            bubble.showPipelineStage("🪜  LAYERS", "I have four. You pick what you need.")
        }

        t += 4000
        step(t) {
            bubble.showPipelineStage("①  TRANSCRIBE", "Free. Instant. Just cleans your words.")
        }

        t += 4000
        step(t) {
            bubble.showPipelineStage("②  RECONSTRUCT", "AI rewrites with your tone. ~2 seconds.")
        }

        t += 4000
        step(t) {
            bubble.showPipelineStage("③  PROFILE", "Learns YOUR vocabulary over time.")
        }

        t += 4000
        step(t) {
            bubble.showPipelineStage("④  DISFLUENCY", "Full clinical mode. Built for non-fluent speech.")
        }

        // ── Act 7: Situations ──
        t += 4500
        step(t) {
            bubble.showPipelineStage("🎚  SITUATION", "Heading into a tough call? Let me know.")
        }

        t += 4000
        step(t) {
            bubble.showPipelineStage("🔥  HIGH STRESS", "I go full power. DAF on. Layer four.")
        }

        t += 4000
        step(t) {
            bubble.showPipelineStage("📖  READING", "Light touch. You're already fluent here.")
        }

        // ── Act 8: Hide and summon ──
        t += 4500
        step(t) {
            bubble.showPipelineStage("⬇  DRAG ME DOWN", "Drag me to the bottom edge. I'll hide.")
        }

        t += 4000
        step(t) {
            bubble.showPipelineStage("📳  SHAKE", "Shake your phone twice. I'm back.")
        }

        t += 4000
        step(t) {
            bubble.showPipelineStage("🎙  HEY WIM", "Or say \"Hey WiM\" — hands-free trigger.")
        }

        // ── Act 9: Clarifier preview ──
        t += 4500
        step(t) {
            bubble.showPipelineStage("❓  ASK FIRST", "Sometimes I'm not 100% sure of a word.")
        }

        t += 4000
        step(t) {
            bubble.showPipelineStage("👀  WATCH", "I'll flag up to three. Tap left/right or hold to skip.")
            // 3-flag synthetic clarifier — exercises the per-word cycle
            // (claim 11(c) max-3 cap), the clarifier-entry arc flash,
            // and the "↓ hold = skip" hint that teaches long-press defer.
            bubble.triggerClarifierForTest(
                listOf(
                    FlaggedWord(
                        originalWord = "Dunken",
                        candidates = listOf("Dankeschön", "Danke"),
                        rangeStart = 0,
                        rangeEnd = 6,
                        uncertaintyScore = 0.95,
                    ),
                    FlaggedWord(
                        originalWord = "morning",
                        candidates = listOf("warning", "mourning"),
                        rangeStart = 7,
                        rangeEnd = 14,
                        uncertaintyScore = 0.85,
                    ),
                    FlaggedWord(
                        originalWord = "Sarah",
                        candidates = listOf("Sara", "Cera"),
                        rangeStart = 15,
                        rangeEnd = 20,
                        uncertaintyScore = 0.78,
                    ),
                ),
            )
        }
        t += 9000
        step(t) {
            // Tear the cycle down without bouncing through setBubbleIdle —
            // setBubbleIdle hides the live-transcript bar, which made the
            // comment surface visibly disappear between Acts 9 and 10.
            // We immediately overwrite with the next step's content so the
            // bar stays continuous.
            ClarifierController.dismissAll()
            TranscriptBarController.clear(TranscriptBarController.Priority.CLARIFIER)
            bubble.showPipelineStage("✓  GOT IT", "Three taps, three answers. That fast.")
        }

        // ── Act 10: Tips ──
        t += 2500
        step(t) {
            bubble.showPipelineStage("💡  TIPS", "I'll surface helpful reminders. Like this one.")
        }

        t += 4000
        step(t) {
            bubble.showPipelineStage("🔕  YOUR CALL", "Don't like them? Turn off in Settings.")
        }

        // ── Act 11: Finale ──
        t += 4000
        step(t) {
            bubble.showPipelineStage("☕  NO JUDGMENT", "Spill your coffee. Stutter your words. I don't care.")
        }

        t += 4500
        step(t) {
            bubble.showPipelineStage("🎬  YOUR TURN", "That's my show. Now you take over.")
        }

        t += 4000
        step(t) {
            bubble.hideLiveTranscriptPublic()
            bubble.setBubbleIdlePublic()
            onComplete()
        }

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
        bubble.bubbleView?.findViewById<FrameLayout>(R.id.bubbleBezel)
            ?.setBackgroundResource(R.drawable.bubble_bg_recording)
        bubble.bubbleView?.findViewById<FrameLayout>(R.id.bubbleInner)
            ?.setBackgroundResource(R.drawable.bubble_inner_active)
        bubble.bubbleView?.findViewById<TextView>(R.id.bubbleLabel)?.apply {
            text = "REC"
            setTextColor(0xFFFFD580.toInt())
        }
    }
}
