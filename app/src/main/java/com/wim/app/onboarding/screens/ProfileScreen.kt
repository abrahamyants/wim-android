package com.wim.app.onboarding.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wim.app.R
import com.wim.app.ToneRouting
import com.wim.app.WimApp
import com.wim.app.onboarding.RealBubble
import com.wim.app.onboarding.theme.TitleFamily
import com.wim.app.onboarding.theme.PlusJakartaFamily

/**
 * Profile seed — Calm-dark. Two single-tap questions: "mostly for" (work_setup) and
 * "your hours" (work_hours).
 *
 * "MOSTLY FOR" seeds the starting tone through [ToneRouting.seedTone] — work opens on
 * professional, life on casual, both leaves the default alone. It applies only until
 * the user picks a tone themselves; the dashboard tile stamps `manual_tone_set_ms` and
 * that pick wins from then on.
 *
 * Corrected 2026-08-02: this comment claimed both answers were read by [ToneRouting],
 * "which decides work-vs-casual per app". Per-app routing was retired on 2026-07-29 and
 * its call site became a no-op, which left both prefs written and read by nothing —
 * answering "work" still started you on casual. The seed below is what makes the
 * calibrate-from-message-one claim true. "YOUR HOURS" reaches only
 * [com.wim.app.ToneFromContent.breakTieByClock], behind the retired `auto_tone` flag.
 *
 * Corrected 2026-07-25: this comment used to claim the values matched
 * BouncerActivity's. They did not — Bouncer wrote 9to5/night_owl/shifts/off_clock for
 * hours and office/hybrid/remote/mobile into work_setup, which is a "where do you work"
 * answer, not a "what do you use this for" one. Bouncer's location group now writes
 * work_location; ToneRouting normalises both hour vocabularies.
 * Defaults pre-selected + written, so a fast-tapper still seeds something sane. The deeper
 * profile inputs (industry, vocabulary, corrections) stay in the profile screen — onboarding
 * asks only the two that change the very first message. Per George briefing 2026-06-20.
 */
@Composable
fun ProfileScreen(onBack: () -> Unit, onContinue: () -> Unit) {
    val prefs = WimApp.prefs
    var setup by remember { mutableStateOf(prefs.getString("work_setup", null) ?: "both") }
    var hours by remember { mutableStateOf(prefs.getString("work_hours", null) ?: "flexible") }

    fun writeSetup(v: String) {
        setup = v
        val edit = prefs.edit().putString("work_setup", v)
        // Seed the tone only while the user has never picked one. Any explicit
        // pick — dashboard tile or the bubble's work/casual question — stamps
        // manual_tone_set_ms, and re-running onboarding must not quietly undo it.
        if (prefs.getLong("manual_tone_set_ms", 0L) == 0L) {
            ToneRouting.seedTone(v)?.let { edit.putString("tone", it) }
        }
        edit.apply()
    }
    fun writeHours(v: String) { hours = v; prefs.edit().putString("work_hours", v).apply() }

    CalmDark {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to colorResource(R.color.brand_dark_top),
                        0.45f to colorResource(R.color.bg_base),
                        1f to colorResource(R.color.bg_sunken)
                    )
                )
        ) {
            BackArrow(onBack)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 32.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.weight(0.4f))

                RealBubble()

                Spacer(Modifier.height(24.dp))

                Text(
                    text = "MAKE IT YOURS",
                    fontFamily = TitleFamily,
                    fontSize = 30.sp,
                    color = colorResource(R.color.section_header),
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(12.dp))

                Text(
                    text = "Two taps, so WiM sounds like you from your very first message.",
                    fontFamily = PlusJakartaFamily,
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                    color = colorResource(R.color.text_secondary),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 320.dp)
                )

                Spacer(Modifier.height(30.dp))

                FieldLabel("MOSTLY FOR")
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().widthIn(max = 340.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Chip("Work", setup == "work") { writeSetup("work") }
                    Chip("Life", setup == "life") { writeSetup("life") }
                    Chip("Both", setup == "both") { writeSetup("both") }
                }

                Spacer(Modifier.height(22.dp))

                FieldLabel("YOUR HOURS")
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().widthIn(max = 340.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Chip("Morning", hours == "morning") { writeHours("morning") }
                    Chip("9–5", hours == "nine_to_five") { writeHours("nine_to_five") }
                    Chip("Evening", hours == "evening") { writeHours("evening") }
                    Chip("Flexible", hours == "flexible") { writeHours("flexible") }
                }

                Spacer(Modifier.weight(1f))

                Button(
                    onClick = onContinue,
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 320.dp)
                        .height(56.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colorResource(R.color.copper_gold),
                        contentColor = colorResource(R.color.bg_sunken)
                    )
                ) {
                    Text(
                        text = "DONE",
                        fontFamily = PlusJakartaFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                        letterSpacing = 2.sp
                    )
                }

                Spacer(Modifier.height(14.dp))
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        fontFamily = PlusJakartaFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 1.5.sp,
        color = colorResource(R.color.text_dim)
    )
}

@Composable
private fun RowScope.Chip(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .weight(1f)
            .height(46.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (active) colorResource(R.color.copper_gold).copy(alpha = 0.16f)
                else colorResource(R.color.bg_surface)
            )
            .border(
                width = 1.dp,
                color = if (active) colorResource(R.color.copper_gold) else colorResource(R.color.bg_surface),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontFamily = PlusJakartaFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            color = if (active) colorResource(R.color.copper_gold) else colorResource(R.color.text_secondary)
        )
    }
}
