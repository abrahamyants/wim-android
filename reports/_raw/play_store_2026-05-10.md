# Play Store Readiness Raw — wim-android — 2026-05-10

## Status Dashboard

[RED] API keys baked into release APK — app/src/main/assets/api_key.txt (OpenAI), anthropic_key.txt (Anthropic). Both in main source set, packaged into every APK/AAB. WimApp.kt:41-53 reads them at runtime. Extractable in <60s with apktool.

[RED] WimTestReceiver exported=true — main manifest AndroidManifest.xml:114. Debug manifest directory (app/src/debug/) does not exist. Runtime BuildConfig.DEBUG guard at WimTestReceiver.kt:50 works, but class is visible to Play's automated scanner.

[RED] Release AAB never built — app/build/outputs/bundle/debug/app-debug.aab exists; no release equivalent. Play Store requires AAB for new submissions (since Aug 2021).

[RED] R8 fix unconfirmed — clean assembleRelease not run after hand-copy displacement. Fix appears correct on disk but stale intermediates from before displacement may still trigger error.

[RED] Privacy policy URL points to Lavrentiy — MainActivity.kt:818 links github.com/gugosf114/lavrentiy/blob/main/PRIVACY.md. Wrong product. Requires WiM-specific hosted policy page.

[RED] Play Store listing assets missing — No curated screenshots, no feature graphic (1024x500), no hi-res 512x512 icon (full square with background). Root-level PNGs are dev snapshots, not submission assets.

[RED] Play Store listing text missing — No title, short description, full description file found.

[YELLOW] versionName = 0.2.0 — Memory TODO says bump to 1.0.0 before submission.

[YELLOW] firebase-perf-ktx unconditional — Instruments startup via bytecode in all builds. Play pre-launch report may flag. Disable-in-debug or make conditional.

[YELLOW] WimTestReceiver in ProGuard — proguard-rules.pro:56 keeps class unobfuscated in release binary. Remove after moving to debug manifest.

[YELLOW] Data safety form — RECORD_AUDIO, INTERNET, BLUETOOTH_CONNECT all require declarations. Not filed.

[GREEN] Signing keystore present and wired — wim-release.jks exists, build.gradle.kts:21-28 correct.
[GREEN] targetSdk 35 — compliant with current Play requirements.
[GREEN] Adaptive icon present — ic_launcher.xml across mipmap densities.
[GREEN] allowBackup=false — correct.
[GREEN] foregroundServiceType="specialUse|microphone" + PROPERTY_SPECIAL_USE_FGS_SUBTYPE — declared.
[GREEN] R8 enabled — isMinifyEnabled=true, proguard-rules.pro covers all major deps.

## Next 3 Blockers (in order)
1. Remove bundled API keys from assets — gate direct-OpenAI to user-entered keys in release; Cloud Function path already works for signed-in users
2. Move WimTestReceiver to debug manifest — create app/src/debug/AndroidManifest.xml
3. Run `gradlew.bat clean bundleRelease` — confirms R8 fix AND produces the AAB Play requires
