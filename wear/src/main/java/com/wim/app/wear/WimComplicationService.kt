package com.wim.app.wear

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.core.graphics.drawable.IconCompat

/**
 * Watch-face complication data source. Renders a "WiM" tile on the watchface;
 * tapping it launches MainActivity with EXTRA_START_NOW=true so recording starts
 * immediately without an extra tap.
 */
class WimComplicationService : ComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        buildData(type)

    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationRequestListener
    ) {
        listener.onComplicationData(buildData(request.complicationType))
    }

    private fun buildData(type: ComplicationType): ComplicationData? {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            component = ComponentName(this@WimComplicationService, MainActivity::class.java)
            putExtra(MainActivity.EXTRA_START_NOW, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val tapPending = PendingIntent.getActivity(
            this,
            0,
            tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val label = PlainComplicationText.Builder("WiM").build()
        val description = PlainComplicationText.Builder("Tap to record").build()

        return when (type) {
            ComplicationType.SHORT_TEXT ->
                ShortTextComplicationData.Builder(
                    text = label,
                    contentDescription = description
                )
                    .setTapAction(tapPending)
                    .build()

            ComplicationType.MONOCHROMATIC_IMAGE,
            ComplicationType.SMALL_IMAGE -> {
                val icon = IconCompat.createWithResource(this, android.R.drawable.ic_btn_speak_now)
                MonochromaticImageComplicationData.Builder(
                    monochromaticImage = MonochromaticImage.Builder(icon.toIcon(this)).build(),
                    contentDescription = description
                )
                    .setTapAction(tapPending)
                    .build()
            }

            else -> null
        }
    }
}
