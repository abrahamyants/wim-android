package com.wim.app.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wim.app.R
import com.wim.app.onboarding.theme.PlusJakartaFamily

/**
 * The one fake messaging screen the onboarding uses wherever it shows text
 * landing somewhere (George 10/12: "let's use that same box structure"). A
 * thread with one incoming line, the lines we sent, and the message box with
 * its Send arrow. Anything laid over it (the bubble, a finger) goes in
 * [overlay], which is NOT clipped to the card so it can hang over the edge.
 *
 * [typed] is what sits in the message box. [typedDim] draws it faint and
 * italic — that is the spoken, not-yet-clean stream.
 */
val CHAT_W = 330.dp
val CHAT_H = 470.dp
val SEND_BTN = 40.dp

@Composable
fun FakeChatBox(
    typed: String,
    typedDim: Boolean = false,
    sent: List<String> = emptyList(),
    from: String = "Boss",
    incoming: String = "Where are you?",
    height: Dp = CHAT_H,
    overlay: @Composable BoxScope.() -> Unit = {}
) {
    Box(modifier = Modifier.size(CHAT_W, height)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(22.dp))
                .background(colorResource(R.color.bg_surface))
                .border(1.dp, colorResource(R.color.copper_gold).copy(alpha = 0.35f), RoundedCornerShape(22.dp))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = from,
                    fontFamily = PlusJakartaFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                    letterSpacing = 1.sp,
                    color = colorResource(R.color.copper_gold)
                )
                ChatLine(incoming, mine = false)
                sent.forEach { ChatLine(it, mine = true) }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(colorResource(R.color.bg_base))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = if (typed.isEmpty()) "Message" else typed,
                        fontFamily = PlusJakartaFamily,
                        fontSize = 11.sp,
                        lineHeight = 13.sp,
                        maxLines = 4,
                        fontStyle = if (typedDim && typed.isNotEmpty()) FontStyle.Italic else FontStyle.Normal,
                        color = when {
                            typed.isEmpty() -> colorResource(R.color.text_dim).copy(alpha = 0.6f)
                            typedDim -> colorResource(R.color.text_dim)
                            else -> colorResource(R.color.section_header)
                        }
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(SEND_BTN)
                        .clip(CircleShape)
                        .background(colorResource(R.color.copper_gold)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("➤", fontSize = 16.sp, color = colorResource(R.color.bg_sunken))
                }
            }
        }
        overlay()
    }
}

@Composable
private fun ChatLine(text: String, mine: Boolean) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Text(
            text = text,
            fontFamily = PlusJakartaFamily,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            color = if (mine) colorResource(R.color.bg_sunken) else colorResource(R.color.section_header),
            modifier = Modifier
                .widthIn(max = 200.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    if (mine) colorResource(R.color.copper_gold)
                    else colorResource(R.color.bg_base)
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}
