package com.wim.app

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View

/**
 * Three concentric pulse rings that animate behind the bubble during
 * active recording — staggered scale + alpha fade, looping forever until
 * stopped. Pure animation cluster.
 *
 * Extracted from BubbleService 2026-05-25 as the twelfth slice. The
 * ring views (R.id.ring1/2/3) live inside the bubble layout; this class
 * just owns the animator lifecycle.
 */
class BubblePulseRings(
    private val getBubbleView: () -> View?,
) {
    private val animators = mutableListOf<AnimatorSet>()

    fun start() {
        stop()
        val view = getBubbleView() ?: return
        val rings = listOf(
            view.findViewById<View>(R.id.ring1),
            view.findViewById<View>(R.id.ring2),
            view.findViewById<View>(R.id.ring3),
        )

        rings.forEachIndexed { i, ring ->
            ring ?: return@forEachIndexed
            val delay = (i * 300).toLong()
            // Scale grows ring-by-ring: 1.4 / 1.7 / 2.0 — fits inside the
            // 120dp bubble window without clipping at max scale.
            val maxScale = 1.4f + (i * 0.3f)

            val sx = ObjectAnimator.ofFloat(ring, "scaleX", 1.0f, maxScale).apply {
                duration = 1200; repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART; startDelay = delay
            }
            val sy = ObjectAnimator.ofFloat(ring, "scaleY", 1.0f, maxScale).apply {
                duration = 1200; repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART; startDelay = delay
            }
            val a = ObjectAnimator.ofFloat(ring, "alpha", 0.9f, 0.0f).apply {
                duration = 1200; repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART; startDelay = delay
            }

            val set = AnimatorSet()
            set.playTogether(sx, sy, a)
            set.start()
            animators.add(set)
        }
    }

    fun stop() {
        animators.forEach { it.cancel() }
        animators.clear()
        val view = getBubbleView() ?: return
        listOf(R.id.ring1, R.id.ring2, R.id.ring3).forEach { id ->
            view.findViewById<View>(id)?.apply {
                scaleX = 1f; scaleY = 1f; alpha = 0f
            }
        }
    }
}
