package com.wim.app.onboarding.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.wim.app.R

/**
 * WiM type families. Per brand.local.md:
 *   Display:  Limelight  (headers, the WiM logo — display-only, never body)
 *   Body:     Plus Jakarta Sans (regular + medium)
 *   Mono:     JetBrains Mono (NOT yet bundled in res/font/ — falls back to FontFamily.Monospace
 *             which Android resolves to Roboto Mono; both are in brand fallback chain)
 *
 * Screens are encouraged to reference MaterialTheme.typography slots rather than passing
 * fontFamily inline. WimTypography (below) wires those slots to the right family per role.
 */
val LimelightFamily = FontFamily(Font(R.font.limelight, FontWeight.Normal))

/** Onboarding slide titles. Dropped Limelight (the WiM logo face) here per George
 *  2026-06-25 — the wordmark isn't on these screens, so using the logo font for
 *  every title added no brand signal and read heavy. Geist Bold: clean, strong,
 *  and already the app's chrome face (see MainActivity). */
val TitleFamily = FontFamily(Font(R.font.geist_bold, FontWeight.Normal))

val PlusJakartaFamily = FontFamily(
    Font(R.font.plus_jakarta_sans, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans_medium, FontWeight.Medium)
)

private val WimTypography = Typography(
    displayLarge   = TextStyle(fontFamily = LimelightFamily, fontSize = 48.sp),
    displayMedium  = TextStyle(fontFamily = LimelightFamily, fontSize = 36.sp),
    displaySmall   = TextStyle(fontFamily = LimelightFamily, fontSize = 28.sp),
    headlineLarge  = TextStyle(fontFamily = LimelightFamily, fontSize = 24.sp),
    headlineMedium = TextStyle(fontFamily = LimelightFamily, fontSize = 22.sp),
    headlineSmall  = TextStyle(fontFamily = LimelightFamily, fontSize = 18.sp),
    titleLarge     = TextStyle(fontFamily = PlusJakartaFamily, fontWeight = FontWeight.Medium, fontSize = 18.sp),
    titleMedium    = TextStyle(fontFamily = PlusJakartaFamily, fontWeight = FontWeight.Medium, fontSize = 16.sp),
    titleSmall     = TextStyle(fontFamily = PlusJakartaFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp),
    bodyLarge      = TextStyle(fontFamily = PlusJakartaFamily, fontSize = 16.sp),
    bodyMedium     = TextStyle(fontFamily = PlusJakartaFamily, fontSize = 14.sp),
    bodySmall      = TextStyle(fontFamily = PlusJakartaFamily, fontSize = 12.sp),
    labelLarge     = TextStyle(fontFamily = PlusJakartaFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp),
    labelMedium    = TextStyle(fontFamily = PlusJakartaFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp),
    labelSmall     = TextStyle(fontFamily = PlusJakartaFamily, fontWeight = FontWeight.Medium, fontSize = 10.sp)
)

/**
 * WiM-branded Material color scheme. Without this override, Material3 defaults
 * primary=purple / secondary=teal, which would leak into any composable that
 * references MaterialTheme.colorScheme.primary (e.g. a Button without explicit
 * colors). Brand explicitly forbids unmodified Material defaults.
 *
 * colorResource() auto-resolves light/dark from system theme + the existing
 * values/ vs values-night/ split, so this single scheme works in both modes.
 */
@Composable
fun WimTheme(content: @Composable () -> Unit) {
    val scheme = lightColorScheme(
        primary           = colorResource(R.color.copper_gold),
        onPrimary         = colorResource(R.color.bg_base),
        primaryContainer  = colorResource(R.color.bg_elevated),
        onPrimaryContainer = colorResource(R.color.section_header),
        secondary         = colorResource(R.color.section_header),
        onSecondary       = colorResource(R.color.bg_base),
        secondaryContainer = colorResource(R.color.bg_surface),
        onSecondaryContainer = colorResource(R.color.text_primary),
        tertiary          = colorResource(R.color.orange_label),
        onTertiary        = colorResource(R.color.bg_base),
        background        = colorResource(R.color.bg_base),
        onBackground      = colorResource(R.color.text_primary),
        surface           = colorResource(R.color.bg_surface),
        onSurface         = colorResource(R.color.text_primary),
        surfaceVariant    = colorResource(R.color.bg_elevated),
        onSurfaceVariant  = colorResource(R.color.text_secondary),
        error             = colorResource(R.color.red_primary),
        onError           = colorResource(R.color.bg_base),
        outline           = colorResource(R.color.border_accent),
        outlineVariant    = colorResource(R.color.copper_gold)
    )
    MaterialTheme(
        colorScheme = scheme,
        typography  = WimTypography,
        content     = content
    )
}
