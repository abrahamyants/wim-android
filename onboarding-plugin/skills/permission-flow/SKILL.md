---
name: permission-flow
description: Generates one of the five WiM onboarding permission cards (overlay, accessibility, microphone, battery optimization, Samsung sleep) with the correct intent, deep-link, copy, and "what happens if I skip" recovery state. Triggered when the user asks to add or fix a permission card, request a runtime permission, deep-link into Android settings, or handle the Samsung deep-sleep edge case. Always uses the WiM-specific copy that explains why each permission is required for a voice-input bubble. Knows the canonical order and dependencies between permissions.
---

# permission-flow

Generate a permission card for the WiM onboarding flow.

## The five permissions (canonical order)

| # | Permission | Why WiM needs it | Intent / API |
|---|---|---|---|
| 1 | **Overlay** (`SYSTEM_ALERT_WINDOW`) | Bubble draws on top of other apps. Without this, WiM is invisible. | `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` + `Uri.parse("package:" + packageName)` |
| 2 | **Accessibility service** | Bubble reads the active text field so it can rewrite what you meant. | `Settings.ACTION_ACCESSIBILITY_SETTINGS` (manual nav to "Installed services" → WiM) |
| 3 | **Microphone** (`RECORD_AUDIO`) | Voice in. | `ActivityCompat.requestPermissions` |
| 4 | **Battery optimization** | Bubble survives Doze. Otherwise the OS kills it after a few minutes idle. | `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` |
| 5 | **Samsung deep sleep** (Samsung only) | Samsung's "Deep sleeping apps" list overrides battery optimization. | `Intent("com.samsung.android.sm.ACTION_BATTERY_USAGE")` (deep-link, fallback to general battery settings) |

Order matters: overlay first (so the demo can paint), accessibility next (so the demo can listen), then mic / battery / Samsung as needed.

## Trigger phrases
- "add the overlay permission card"
- "fix the accessibility deep-link"
- "the mic permission isn't working"
- "battery optimization step"
- "Samsung deep sleep card"
- "what's the intent for X permission"

## What to generate

For each card, output:
1. **The card UI** — heading (Limelight), body explaining why WiM needs this, an "Open settings" CTA, and a "skip / I did it" pair.
2. **The intent / API call** — wrapped in a helper function in `app/src/main/java/com/wim/app/onboarding/PermissionLauncher.kt`.
3. **The status check** — function that returns `true` if the permission is already granted, so the card can show a "✓ Done" state on return.
4. **The conditional logic** for Samsung — only show card 5 if `Build.MANUFACTURER.equals("Samsung", ignoreCase = true)`.

## Permission status checks

```kotlin
object PermissionStatus {
    fun overlay(c: Context) = Settings.canDrawOverlays(c)
    fun accessibility(c: Context, serviceClass: Class<*>): Boolean {
        val enabled = Settings.Secure.getString(c.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(":").any { it.endsWith("/${serviceClass.name}") }
    }
    fun microphone(c: Context) =
        ContextCompat.checkSelfPermission(c, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    fun batteryOptimization(c: Context): Boolean {
        val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(c.packageName)
    }
    fun isSamsung() = Build.MANUFACTURER.equals("Samsung", ignoreCase = true)
}
```

## Copy templates (WiM-opinionated)

**Overlay**
> Heading: `LET WIM DRAW`
> Body: WiM is a bubble. It needs permission to float over your other apps so it can sit there when you're texting, emailing, or in any app where you'd rather speak than type.
> CTA: `Open settings`
> Skip: `Not yet`

**Accessibility**
> Heading: `LET WIM LISTEN`
> Body: WiM rewrites what you meant to say in the field you're typing into. To do that, it needs to read the field's contents — which is what Android's accessibility service is for.
> CTA: `Open accessibility settings`
> Caveat: `Tap "Installed services" → WiM → On.`
> Skip: `I did it` / `Not yet`

**Microphone**
> Heading: `VOICE IN`
> Body: WiM takes voice and turns it into clean text. That requires a microphone.
> CTA: `Allow microphone`
> Skip: `Not yet`

**Battery**
> Heading: `KEEP WIM AWAKE`
> Body: Android tries to put bubbles to sleep after a few minutes. WiM needs to be exempt — otherwise it disappears mid-conversation.
> CTA: `Exempt WiM`
> Skip: `Not yet`

**Samsung**
> Heading: `SAMSUNG ONE MORE THING`
> Body: Samsung overrides Android's battery exemption with a "Deep sleeping apps" list. If WiM is on that list, it dies anyway. Tap below and remove WiM.
> CTA: `Open Samsung battery settings`
> Skip: `Not on Samsung` / `Done`

Use this exact copy unless the user provides different copy. Brand voice is direct, no marketing fluff. Never use "stutter."

## Quality bar
- Intent strings exact — Samsung's intent is fragile; if it fails, fall back to `Settings.ACTION_BATTERY_SAVER_SETTINGS`
- Status checks present for all five — needed for the "Done" state
- Heading uses Limelight (Compose: `FontFamily(Font(R.font.limelight))`; HTML: `font-family: 'Limelight'`)
- Brand colors only

## Handoff
After generating cards, run `onboarding-design-reviewer` to verify brand consistency.
