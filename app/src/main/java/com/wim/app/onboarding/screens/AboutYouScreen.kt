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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wim.app.R
import com.wim.app.WimApp
import com.wim.app.onboarding.RealBubble
import com.wim.app.onboarding.theme.TitleFamily
import com.wim.app.onboarding.theme.PlusJakartaFamily

/**
 * About-you seed — Calm-dark, follows [ProfileScreen] in the flow. Two
 * questions the operator promoted from the Profile overlay into onboarding
 * (per George 2026-07-29): industry, and native-English / first language.
 *
 * Writes the SAME prefs the Profile overlay reads, so the overlay shows
 * these answers and can edit them later — no parallel keys:
 *   - `profile_industry` + free-typed `profile_industry_other`
 *   - `profile_l1`: "en" when native, else the language value ("other" +
 *     free-typed `profile_l1_other` when their language isn't listed).
 *     L1PackHelper loads a pack only for the ten known values, so "other"
 *     and "en" behave identically downstream — informational only.
 *
 * Nothing is pre-selected: an unanswered screen writes nothing, exactly
 * like a user who never opened the Profile overlay. Answers commit on tap.
 */
@Composable
fun AboutYouScreen(onBack: () -> Unit, onContinue: () -> Unit) {
    val prefs = WimApp.prefs
    var industry by remember { mutableStateOf(prefs.getString("profile_industry", "") ?: "") }
    var industryOther by remember { mutableStateOf(prefs.getString("profile_industry_other", "") ?: "") }
    val savedL1 = prefs.getString("profile_l1", "") ?: ""
    var native by remember {
        mutableStateOf(
            when {
                savedL1 == "en" -> "yes"
                savedL1.isNotBlank() -> "no"
                else -> ""
            }
        )
    }
    var l1 by remember { mutableStateOf(if (savedL1 == "en") "" else savedL1) }
    var l1Other by remember { mutableStateOf(prefs.getString("profile_l1_other", "") ?: "") }

    fun writeIndustry(v: String) {
        industry = v
        prefs.edit().putString("profile_industry", v).apply()
    }

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
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(horizontal = 32.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(18.dp))

                RealBubble()

                Spacer(Modifier.height(20.dp))

                Text(
                    text = "ABOUT YOU",
                    fontFamily = TitleFamily,
                    fontSize = 30.sp,
                    color = colorResource(R.color.section_header),
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(12.dp))

                Text(
                    text = "So WiM speaks your language — your field's words, and your first language's patterns.",
                    fontFamily = PlusJakartaFamily,
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                    color = colorResource(R.color.text_secondary),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 320.dp)
                )

                Spacer(Modifier.height(26.dp))

                AboutFieldLabel("YOUR INDUSTRY")
                Spacer(Modifier.height(10.dp))
                val industryRows = listOf(
                    listOf("finance" to "Finance", "legal" to "Legal", "medical" to "Medical"),
                    listOf("tech" to "Tech", "sales" to "Sales", "trades" to "Trades"),
                    listOf("food" to "Food", "education" to "Education", "other" to "Other"),
                )
                industryRows.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth().widthIn(max = 340.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        row.forEach { (value, label) ->
                            AboutChip(label, industry == value) { writeIndustry(value) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (industry == "other") {
                    Spacer(Modifier.height(2.dp))
                    AboutTextField(
                        value = industryOther,
                        placeholder = "Type your industry",
                        onChange = {
                            industryOther = it
                            prefs.edit().putString("profile_industry_other", it.trim()).apply()
                        }
                    )
                }

                Spacer(Modifier.height(22.dp))

                AboutFieldLabel("NATIVE ENGLISH SPEAKER?")
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().widthIn(max = 340.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AboutChip("Yes", native == "yes") {
                        native = "yes"
                        l1 = ""
                        prefs.edit().putString("profile_l1", "en").apply()
                    }
                    AboutChip("No", native == "no") {
                        native = "no"
                        // profile_l1 stays untouched until a language is picked,
                        // so no pack loads for a language the user never chose.
                    }
                }

                if (native == "no") {
                    Spacer(Modifier.height(16.dp))
                    AboutFieldLabel("YOUR FIRST LANGUAGE")
                    Spacer(Modifier.height(10.dp))
                    val langRows = listOf(
                        listOf("russian" to "Russian", "spanish" to "Spanish", "mandarin" to "Mandarin"),
                        listOf("hindi" to "Hindi", "arabic" to "Arabic", "farsi" to "Farsi"),
                        listOf("french" to "French", "german" to "German", "korean" to "Korean"),
                        listOf("japanese" to "Japanese", "other" to "Other"),
                    )
                    langRows.forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth().widthIn(max = 340.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            row.forEach { (value, label) ->
                                AboutChip(label, l1 == value) {
                                    l1 = value
                                    prefs.edit().putString("profile_l1", value).apply()
                                }
                            }
                            // Keep the 2-chip last row the same chip width as 3-chip rows.
                            if (row.size == 2) Spacer(Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    if (l1 == "other") {
                        Spacer(Modifier.height(2.dp))
                        AboutTextField(
                            value = l1Other,
                            placeholder = "Type your first language",
                            onChange = {
                                l1Other = it
                                prefs.edit().putString("profile_l1_other", it.trim()).apply()
                            }
                        )
                    }
                }

                Spacer(Modifier.height(30.dp))

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
                        text = "CONTINUE",
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
private fun AboutFieldLabel(text: String) {
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
private fun RowScope.AboutChip(label: String, active: Boolean, onClick: () -> Unit) {
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

@Composable
private fun AboutTextField(value: String, placeholder: String, onChange: (String) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 340.dp)
            .height(46.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colorResource(R.color.bg_surface))
            .border(1.dp, colorResource(R.color.copper_gold), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                fontFamily = PlusJakartaFamily,
                fontSize = 13.sp,
                color = colorResource(R.color.text_dim)
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(
                fontFamily = PlusJakartaFamily,
                fontSize = 13.sp,
                color = colorResource(R.color.text_secondary)
            ),
            cursorBrush = SolidColor(colorResource(R.color.copper_gold)),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
