package com.wim.app

/**
 * Maps each selector tile (Layer / Tone / Situation) to the Easter-egg
 * "pin" image revealed behind its knob when the user interacts with it.
 *
 * Images live in app/src/main/res/drawable/ as WebP at 512×512:
 *   pin_layer_jr           — Jessica Rabbit
 *   pin_tone_heisenberg    — Walter White / Heisenberg (Breaking Bad)
 *   pin_situation_ducky    — the duck
 *
 * Consumer code (MainActivity tile setup) reads this by knob name and
 * renders the drawable as a small backdrop behind the knob face.
 */
object KnobPins {
    const val LAYER = "layer"
    const val TONE = "tone"
    const val SITUATION = "situation"

    fun drawableFor(knob: String): Int = when (knob) {
        LAYER -> R.drawable.pin_layer_jr
        TONE -> R.drawable.pin_tone_heisenberg
        SITUATION -> R.drawable.pin_situation_ducky
        else -> 0
    }
}
