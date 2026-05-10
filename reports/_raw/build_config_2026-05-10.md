# Build Config Audit Raw — wim-android — 2026-05-10

## R8 Duplicate Class — Root Cause and Status

Error: R8 duplicate class com.k2fsa.sherpa.onnx.FeatureConfig defined in multiple files
- Source 1: sherpa-onnx-1.12.40-runtime.jar (classes.jar inside app/libs/sherpa-onnx-1.12.40.aar)
- Source 2: compiled from 5 hand-copied Kotlin files in app/src/main/java/com/k2fsa/sherpa/onnx/

STATUS: Fix PARTIALLY applied.
- 5 hand-copied files moved to .r8-displaced-sherpa-wrappers/ (no longer in compilation path)
- app/src/main/java/com/k2fsa/sherpa/onnx/ is now empty
- app/build.gradle.kts line 95: only flat-file AAR reference, no Maven duplicate

REMAINING ISSUE: Stale .class files in app/build/intermediates/classes/ from before the displacement.
FIX: Run `.\gradlew.bat clean assembleRelease` — the clean is the missing step.

SESSION LOG MISDIAGNOSIS: SESSION_LOG_2026-05-05.md and CLAUDE.md line 56 document a wrong theory
(two Maven declarations). The actual cause was hand-copied Kotlin files + AAR. Update both.

## Signing Config — Correct
app/build.gradle.kts:21-28 — storeFile=../wim-release.jks, storePassword=wimstore2026 (see SEC-005 for plaintext concern)
release buildType correctly references signingConfigs.getByName("release")

## Other Findings
- No distinctUntilChanged() on StateFlow: confirmed clean
- isShrinkResources not set to true alongside isMinifyEnabled=true — size optimization opportunity
- No CI workflow exists at .github/workflows/ — CI is absent, not just broken

## Worktree Divergence
3 .claude/ worktrees (stoic-jones, great-chaplygin, practical-golick) at versionCode=1/0.1.0
Missing Firebase Crashlytics, Perf, mediapipe, commons-compress, sherpa AAR declaration.
Would produce broken APK if built directly.
