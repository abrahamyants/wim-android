WiM Android Security Audit -- 2026-05-10b

## Prior Fix Verification

### SEC-001 (API keys in assets) -- PARTIALLY FIXED
WimApp.kt line 33 runtime gate is correct. BUT api_key.txt and anthropic_key.txt exist in assets/ on disk. If present at release build time they are bundled verbatim in the APK regardless of the runtime guard. apktool/unzip extracts them silently.

### SEC-002 (WimTestReceiver exported) -- INCOMPLETE
debug/AndroidManifest.xml: receiver declared with exported=true. main/AndroidManifest.xml: receiver absent. Both correct. HOWEVER WimTestReceiver.kt is in app/src/main/java/ (main source set). The class compiles into every build variant including release. The BuildConfig.DEBUG runtime guard at line 50 is the only release barrier. Correct fix: move .kt to app/src/debug/java/.

### SEC-003 (AgentSocketServer loopback) -- PRESENT AND GATED
AgentSocketServer.kt line 36 gate is correct. All three agent/ .kt files compile into release (main source set). Class is inert in release due to the guard in start(). Structural issue identical to SEC-002.

### Proguard WimTestReceiver keep rule
No keep rule present in proguard-rules.pro. Correct -- rule was successfully removed.

---

## New Findings

### SEC-004 CRITICAL -- Hardcoded keystore password in build.gradle.kts
app/build.gradle.kts lines 23-27: storePassword and keyPassword are plaintext strings (wimstore2026). Committed in source. Anyone with repo read access has the signing credential. Combined with a leaked .jks this enables APK re-signing and impersonation.
Fix: use gradle.properties (excluded from git) or environment variables (System.getenv) for signing credentials. Never commit passwords to build scripts.

### SEC-005 MEDIUM -- API key assets bundled in release APK
api_key.txt and anthropic_key.txt exist at app/src/main/assets/ on disk and are gitignored at the repo level but NOT excluded from the AGP asset packaging step. A release build executed with these files present packages them verbatim into the APK assets/ directory. The BuildConfig.DEBUG guard prevents USE but not INCLUSION. Attacker extracts via: unzip release.apk assets/api_key.txt.
Fix: add a Gradle task to assert the files are absent before release packaging, or use an assets sourceSet override to redirect the path for release builds.

### SEC-006 HIGH -- WimTestReceiver .kt in main source set
WimTestReceiver.kt is at app/src/main/java/com/wim/app/WimTestReceiver.kt. Full command surface (inject_raw, set_pref, export_sessions, dump_a11y_tree, clear_sessions, inject_audio with arbitrary path, trigger_clarifier) ships in release bytecode. Single runtime guard. If the manifest ever acquires a matching receiver entry the guard becomes the only protection against full pipeline control and data exfiltration by any app that can send a broadcast.
Fix: move WimTestReceiver.kt to app/src/debug/java/com/wim/app/.

### SEC-007 HIGH -- AgentCommandHandler and AgentSocketServer in main source set
All three agent/ .kt files compile into release. AgentCommandHandler exposes: arbitrary click/long-click/set_text on any UI node, global actions (lock screen, screenshot, power dialog), raw gesture injection at arbitrary screen coordinates, inject_pipeline feeding arbitrary text into the reconstruction pipeline. Device takeover surface present in release bytecode.
Fix: move app/src/main/java/com/wim/app/agent/ to app/src/debug/java/com/wim/app/agent/.

### SEC-008 MEDIUM -- Path traversal in handleInjectRawFile
WimTestReceiver.kt lines 195-226: arbitrary file path from intent extra, File(path).readText() with no canonicalization or sandbox restriction. In a debug build an attacker with broadcast access (ADB or another app with matching permissions) can read any file accessible to the app process: SharedPreferences, Firestore offline cache, session history DB.
Fix: canonicalize the path and assert it starts with context.filesDir.canonicalPath.

### SEC-009 MEDIUM -- Arbitrary write path in handleExportSessions
WimTestReceiver.kt lines 355-397: output_path from intent extra written without validation. Session data (raw_text, clean_text, risk_flags) written to attacker-controlled path including world-readable external storage.
Fix: same pattern -- canonicalize and restrict to internal storage.

### SEC-010 LOW (informational) -- BuildConfig.DEBUG override risk ruled out
No custom buildConfigField for DEBUG in app/build.gradle.kts. AGP sets it correctly per variant. The runtime gates are mechanically sound. Structural risk is bytecode inclusion not flag manipulation.

### SEC-011 LOW -- Unauthenticated single-slot client ejection (debug only)
AgentSocketServer.kt line 92: new TCP connection silently closes any existing client. No authentication on the socket. In a debug build an attacker with adb forward access immediately evicts the legitimate dev session and takes full agent control.

---

## Summary Table

ID       | Severity | Status          | Location
SEC-001  | Medium   | Partial fix     | WimApp.kt:33, assets/*.txt
SEC-002  | High     | Incomplete fix  | WimTestReceiver.kt (main src set)
SEC-003  | High     | Gated but ships | agent/*.kt (main src set)
SEC-004  | Critical | NEW             | app/build.gradle.kts:23-27
SEC-005  | Medium   | NEW             | app/src/main/assets/api_key.txt
SEC-006  | High     | NEW (detail)    | WimTestReceiver.kt:1
SEC-007  | High     | NEW (detail)    | agent/*.kt:1
SEC-008  | Medium   | NEW             | WimTestReceiver.kt:195
SEC-009  | Medium   | NEW             | WimTestReceiver.kt:382
SEC-010  | Low      | Informational   | app/build.gradle.kts
SEC-011  | Low      | NEW             | AgentSocketServer.kt:92

