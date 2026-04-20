# Wake-Lock Audit — Google Play March 2026 Battery Enforcement

**Branch**: `perf-wakelock`
**Base**: `main` at 748d7a1
**Date**: 2026-04-20
**Reviewer**: Claude (automated audit)

## Top-line

**No wake locks are acquired anywhere in this app.** Zero remediation required. The app is already compliant with the Play 2026 partial-wake-lock enforcement rule.

## Scope of search

Search patterns run against the entire worktree (Kotlin, Java, XML, manifest):

- `WakeLock` — 0 matches
- `newWakeLock` — 0 matches
- `.acquire(` — 0 matches
- `PARTIAL_WAKE_LOCK` — 0 matches
- `WAKE_LOCK` (manifest permission) — 0 matches
- `wake_lock` (any case) — 0 matches
- `PowerManager` — 5 matches (all query-only, see below)

## PowerManager usage sites (none require remediation)

Every `PowerManager` reference in the codebase is read-only state inspection. None construct or hold a wake lock.

### 1. `app/src/main/java/com/wim/app/BubbleService.kt:271-273`
```kotlin
val powerManager = getSystemService(android.os.PowerManager::class.java)
val keyguardManager = getSystemService(android.app.KeyguardManager::class.java)
if (!powerManager.isInteractive || keyguardManager.isKeyguardLocked) return
```
- **Purpose**: Shake detector guard — ignore accelerometer input when screen is off or device is locked.
- **Acquires**: Nothing. `isInteractive` is a boolean query.
- **Releases**: N/A.
- **Scope**: N/A.
- **Remediation**: None needed.

### 2. `app/src/main/java/com/wim/app/MainActivity.kt:1449`
```kotlin
val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
if (!pm.isIgnoringBatteryOptimizations(packageName)) { ... }
```
- **Purpose**: Check whether user has granted the battery-optimization exemption before prompting.
- **Acquires**: Nothing. Permission-state query.
- **Remediation**: None needed.

### 3. `app/src/main/java/com/wim/app/OnboardingActivity.kt:71`
```kotlin
(getSystemService(POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(packageName)
```
- **Purpose**: Drives the "Battery Optimization" row in the permissions WebView checklist.
- **Acquires**: Nothing.
- **Remediation**: None needed.

### 4. `app/src/main/java/com/wim/app/BouncerActivity.kt:8,135`
```kotlin
import android.os.PowerManager
...
val battery = (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
```
- **Purpose**: Permission gate — show/hide the "enable battery exemption" call-to-action in the bouncer.
- **Acquires**: Nothing.
- **Remediation**: None needed.

## Manifest permissions (verification)

`app/src/main/AndroidManifest.xml` declares no `android.permission.WAKE_LOCK`. It declares:

- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_SPECIAL_USE`
- `FOREGROUND_SERVICE_MICROPHONE`
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`

The foreground-service-microphone type is the Play-recommended mechanism for keeping audio capture alive without a partial wake lock. Recording remains active while `BubbleService` runs as a foreground service; CPU stays awake via the microphone FGS, not via `PowerManager.WakeLock`.

## Why nothing to remediate

The Play 2026 rule targets apps that keep `PowerManager.PARTIAL_WAKE_LOCK` held for >2h cumulative across >5% of user sessions. Because WiM never calls `newWakeLock()` or `.acquire()`, its contribution to this metric is structurally zero. Android Vitals will report no tagged or untagged wake-lock time for this package.

## Tags inventory

No tags applied (no wake locks exist to tag).

If wake locks are ever added in the future, the naming convention to use is `package:purpose`, e.g.:
- `wim:asr_recording` — active Whisper/ASR capture window
- `wim:audio_capture` — AudioRecord PCM pipe
- `wim:reconstruction` — network round-trip to reconstruct service (only if it ever needs to survive doze, which it shouldn't)

Every lock must be:
1. Acquired immediately before recording starts (not at service start).
2. Released in a `finally` block when recording stops or errors.
3. Given a distinct tag (Android Vitals breaks down by tag starting 2026).

## Verification build

`./gradlew.bat assembleDebug` run against this worktree after audit — see commit message / CI log for result.
