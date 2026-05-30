# WiM Android — Security Audit (2026-05-29)

Auditor: Claude Opus 4.7 (1M context) — adversarial review.
Scope: `C:\Users\georg\Documents\GitHub\wim-android\` only. Lavrentiy explicitly excluded.
Framework: OWASP Mobile Top 10 (2024) + CWE + Android-specific surface.

Tooling notes
- No SAST was invoked (no `npm`/`pip` manifest; `gradlew dependencyCheck` not present). Findings derive from manual read of `app/src/main/AndroidManifest.xml`, `app/build.gradle.kts`, every Kotlin source under `app/src/main/java/com/wim/app/`, and the `assets/` tree.
- Dependency CVEs flagged via version-string comparison against publicly known CVE databases as of 2026-01 (assistant cutoff).

---

## 1. CRITICAL

### SEC-001 — Live OpenAI production API key bundled in APK assets
| Field | Content |
|---|---|
| CWE | CWE-798 Use of Hard-Coded Credentials |
| OWASP Mobile | M1: Improper Credential Usage |
| Severity | CRITICAL |
| Location | `app/src/main/assets/api_key.txt` (full file); referenced by `app/src/main/java/com/wim/app/WimApp.kt:39-58` (`bundledKey()` / `apiKey()`) |
| Exploit | Anyone with the published APK runs `unzip app.apk assets/api_key.txt` and obtains an `sk-proj-...` OpenAI key with billing on the developer's account; drains quota or runs abusive completions until OpenAI auto-revokes. |
| Fix | (1) IMMEDIATELY revoke the key in the OpenAI console — must be considered compromised the moment this file existed in a built artifact. (2) Delete `api_key.txt` and `anthropic_key.txt` from `assets/` and from `WimApp.bundledKey()`. (3) Make signed-in + backend path the ONLY path; remove the "device-direct" branch in `ReconstructClient.reconstruct` (`ReconstructClient.kt:108-115`) and `WhisperClient.transcribe` (`WhisperClient.kt:143-144`). (4) The current `BuildConfig.DEBUG`-only guard at `WimApp.kt:40` does NOT save the release path because the file is in the repo working tree; the key is leaked regardless of `DEBUG`. |

Key prefix verified in repo: `sk-proj-v-sKoOgR5MRTJK...`.

### SEC-002 — Live Anthropic production API key bundled in APK assets
| Field | Content |
|---|---|
| CWE | CWE-798 |
| OWASP Mobile | M1 |
| Severity | CRITICAL |
| Location | `app/src/main/assets/anthropic_key.txt`; referenced by `WimApp.anthropicApiKey()` (`WimApp.kt:66-71`) |
| Exploit | Same as SEC-001 — `unzip … assets/anthropic_key.txt` yields an `sk-ant-api03-...` key with billing on developer's Anthropic account. |
| Fix | Revoke at Anthropic console. Remove file. Remove `anthropicApiKey()` plus the device-direct `reconstructWithSonnet` path (`ReconstructClient.kt:732-770`); route Sonnet through the `wim-reconstruct` Cloud Function. |

Key prefix verified: `sk-ant-api03-jij4CfyD2...`.

### SEC-003 — Release keystore password hardcoded in Gradle build script
| Field | Content |
|---|---|
| CWE | CWE-798 |
| OWASP Mobile | M1 |
| Severity | CRITICAL |
| Location | `app/build.gradle.kts:34-41` (`storePassword = "wimstore2026"`, `keyPassword = "wimstore2026"`) |
| Exploit | Anyone reading `app/build.gradle.kts` plus access to `wim-release.jks` (gitignored but on disk at `C:\Users\georg\Documents\GitHub\wim-android\wim-release.jks`) signs a hostile APK with the same certificate as Play Store WiM. Sideloaded users have data signed by what looks like the legitimate developer; auto-update / `signedBy` checks pass. Loss of upload key = permanent expulsion from Play Console signing identity. |
| Fix | Move both passwords out of source. Read from `~/.gradle/gradle.properties` or env (`val storePassword: String by project`). If the JKS has ever been pushed to a private repo or backup, rotate via Play App Signing key reset before Play Store launch. |

### SEC-004 — Bundled keys cached in static companion fields; survive sign-out
| Field | Content |
|---|---|
| CWE | CWE-922 Insecure Storage of Sensitive Information |
| OWASP Mobile | M9 |
| Severity | CRITICAL (data-exposure pathway) |
| Location | `WimApp.kt:36-71` — `_bundledOpenAiKey`, `_bundledAnthropicKey` are companion-object fields holding bundled keys for process lifetime. |
| Exploit | (a) On a rooted or `run-as` debuggable device, `/proc/<pid>/maps` and heap dump expose cached strings. (b) Any app with the test receiver (debug builds, SEC-006) calls `set_pref --es key api_key --es value <attacker-key>` to overwrite, then `get_state` to read back. |
| Fix | Path as SEC-001/002 — delete the bundled fallback entirely. If retained for development, gate strictly on `BuildConfig.DEBUG` AT FIELD ACCESS, not just at the loader, and zero-fill on backgrounding. |

### SEC-005 — Plaintext API keys in SharedPreferences (`MODE_PRIVATE` is not encryption)
| Field | Content |
|---|---|
| CWE | CWE-312 Cleartext Storage of Sensitive Information |
| OWASP Mobile | M9 |
| Severity | CRITICAL |
| Location | `WimApp.kt:54` (`prefs.getString("api_key", "")`), `WimApp.kt:67` (`prefs.getString("anthropic_api_key", "")`), set via `WimTestReceiver.handleSetPref()` (`WimTestReceiver.kt:399-423`) and MainActivity settings UI. |
| Exploit | XML lives at `/data/data/com.wim.app/shared_prefs/wim.xml`. On a rooted device, ADB backup, or USB-debugging-on dev device, key is plaintext. |
| Fix | Migrate to `androidx.security:security-crypto` `EncryptedSharedPreferences`. Better: push the user key flow through Play Billing + server-side key management — never store an OpenAI/Anthropic key on-device. |

### SEC-006 — Debug-only `WimTestReceiver` exposes total app control via ADB broadcast; gated on `BuildConfig.DEBUG` only
| Field | Content |
|---|---|
| CWE | CWE-925 Improper Verification of Intent by Broadcast Receiver |
| OWASP Mobile | M4 |
| Severity | CRITICAL on any debug build that lands on a real user's device; HIGH otherwise |
| Location | `app/src/debug/AndroidManifest.xml:5-10` (declares receiver `android:exported="true"`, no permission); `WimTestReceiver.kt:39-588`. |
| Exploit | `BuildConfig.DEBUG` is the only guard. Any other app on a debug-build device runs `Context.sendBroadcast(new Intent("com.wim.app.TEST").setPackage("com.wim.app").putExtra("action","set_pref").putExtra("key","api_key").putExtra("value","<attacker-key>"))`. Actions like `inject_raw_file --es path /sdcard/Download/<attacker-file>` cause arbitrary text injection through full pipeline. `dump_a11y_tree` writes every visible screen's text content (including focused field) to `files/a11y_tree.json`. `export_sessions --es output_path /sdcard/x.json` writes user's full reconstruction history to external storage with no scoped-storage gate. |
| Fix | (1) Add `android:permission="com.wim.app.permission.TEST_RECEIVE"` declared `protectionLevel="signature"`. (2) Set `exported=false` and require signature-protected permission OR explicit package check `intent.component.packageName == BuildConfig.APPLICATION_ID`. (3) Verify caller via `Binder.getCallingUid()` against `Process.SHELL_UID` for ADB-only operations. (4) Verify Play Store / CI release pipelines NEVER produce a debug APK that ships — gate via `assertReleaseExcludesTestReceiver` Gradle task. |

Sub-findings inside this receiver:
- `handleExportSessions` (`WimTestReceiver.kt:355-397`) writes user's full session history to caller-supplied path with NO path validation — caller passes `/sdcard/Android/data/<attacker_pkg>/cache/x.json`. Path-traversal class.
- `handleInjectRawFile` (`WimTestReceiver.kt:195-226`) reads any path caller supplies — confused-deputy file disclosure (WiM reads files the calling app couldn't, then echoes first 80 chars to logcat and runs through reconstruction pipeline, leaking them off-device via OpenAI/Anthropic calls).

### SEC-007 — Debug-only AgentSocketServer on TCP loopback exposes accessibility (full device control) to any local app
| Field | Content |
|---|---|
| CWE | CWE-306 Missing Authentication for Critical Function + CWE-285 Improper Authorization |
| OWASP Mobile | M3 |
| Severity | CRITICAL on debug builds |
| Location | `app/src/main/java/com/wim/app/agent/AgentSocketServer.kt:25-178`; started from `WimAccessibilityService.onServiceConnected()` (`WimAccessibilityService.kt:82`). |
| Exploit | TCP listener on `127.0.0.1:9222` with ZERO authentication. Any local app with `INTERNET` permission (implicit-default on every app) does `connect("127.0.0.1", 9222)` and calls `click`, `set_text`, `gesture`, `global_action screenshot`, `inject_pipeline`, `snapshot` — full accessibility-service control plus full screen scrape. `AgentCommandHandler.handleGesture` (`AgentCommandHandler.kt:306-351`) lets attacker synthesize any touch gesture; `handleSetText` writes attacker-supplied text into user's focused field; `handleGlobalAction("screenshot")` takes a screenshot. Debug-build privilege escalation worse than rooting. |
| Fix | Replace TCP with `LocalServerSocket` (UNIX-domain abstract namespace) so only processes with matching signature-checked UID can connect (verify via `getpeercred`); OR keep TCP but HMAC-token handshake from a token written to protected files dir (chmod 600); OR drop the agent entirely from APK builds and only enable via a separate debug-only flavor source set. "Loopback-only" comment in `AgentSocketServer.kt:17` is misleading — loopback is NOT a security boundary on Android. |

### SEC-008 — Accessibility service exported with no signature-permission lock and overly broad event subscription
| Field | Content |
|---|---|
| CWE | CWE-732 Incorrect Permission Assignment for Critical Resource |
| OWASP Mobile | M3 |
| Severity | CRITICAL (architectural) |
| Location | `AndroidManifest.xml:137-147`; config `res/xml/accessibility_config.xml:1-10`. |
| Exploit | Accessibility service has `flagRetrieveInteractiveWindows`, `canRetrieveWindowContent=true`, `canPerformGestures=true`, listens on every package. The static `WimAccessibilityService.instance` companion ref (`WimAccessibilityService.kt:16-17`) means anything in the WiM process that gets compromised (e.g., via SEC-006 or SEC-007) instantly has full accessibility powers across every app. No internal capability segregation — `injectText`, `clickSendButton`, `swapWordInLastInjection`, `getPreCursorContext` are all unprotected fun calls. |
| Fix | (1) Drop `typeViewTextChanged` from `accessibility_config.xml:3` if not used — streaming every keystroke from every app into the WiM process and via SEC-007 into the agent socket. (2) Internal API surface should require `BubbleService`-only callers — refactor or gate. (3) `currentForegroundPackage` is published as a `var` on the companion (`WimAccessibilityService.kt:30`) — compromised in-process actor reads every app the user uses; make it `private`. |

### SEC-009 — Accessibility text-change event payload (incl. password fields) forwarded to agent socket
| Field | Content |
|---|---|
| CWE | CWE-359 Exposure of Private Personal Information |
| OWASP Mobile | M9 |
| Severity | CRITICAL |
| Location | `agent/AgentEventBridge.kt:23-62`. `event.text?.joinToString(" ")` is pushed verbatim. The `password` boolean is included but text content is NOT suppressed for password fields. |
| Exploit | When subscribed to `text` events via the agent socket (SEC-007), attacker receives every text-changed event from every app in real time. Code reads `source.isPassword` but only reports it as a boolean — `put("text", event.text?.joinToString(" "))` still includes raw characters of password-marked fields. Combined with SEC-007, this is a real-time keylogger for every other app on the device. |
| Fix | Hard branch: if `source.isPassword` OR `event.eventType == TYPE_VIEW_TEXT_CHANGED` and source is password, drop the event entirely. Same protection needed in `handleDumpA11yTree` (`WimTestReceiver.kt:469-494`). |

### SEC-010 — Test receiver `dump_a11y_tree` writes every visible app's text content to disk (incl. password fields)
| Field | Content |
|---|---|
| CWE | CWE-200 |
| OWASP Mobile | M9 |
| Severity | CRITICAL on debug builds |
| Location | `WimTestReceiver.kt:440-494`, `dumpNode` line 472 (`put("text", node.text?.toString() ?: "")`) and line 479 (records `isPassword` but does NOT redact). |
| Exploit | Triggered by any local app (SEC-006). Captures full screen tree text — bank balances, Signal messages, password manager values — into `files/a11y_tree.json`, then chained with another receiver action to exfiltrate. |
| Fix | Always redact when `node.isPassword`. Enforce caller-package check (SEC-006 fix covers). |

---

## 2. HIGH

### SEC-011 — `relayToTermux` POSTs every reconstructed text to plain HTTP localhost
| Field | Content |
|---|---|
| CWE | CWE-319 Cleartext Transmission of Sensitive Information |
| OWASP Mobile | M5: Insecure Communication |
| Severity | HIGH |
| Location | `BubbleService.kt:1892-1906` (`relayToTermux`), called at `:2197` (L1) and `:2430` (L2+). |
| Exploit | Every reconstruction (incl. user's full intended message after disfluency strip, profile-injected vocabulary, and any password-pre-cursor context) is HTTP-POSTed to `http://localhost:3133/wim`. (a) Any app the user installs that opens port 3133 silently captures every dictation. (b) Cleartext means any on-device traffic-capturing app (debug bridge, VPN-proxy app) intercepts. 500ms timeout makes the leak nearly silent. There is NO settings UI toggle — this runs for every user, every dictation. |
| Fix | (1) Gate on `BuildConfig.DEBUG` AND explicit pref (`developer_termux_relay`). (2) If kept, switch to `LocalServerSocket` abstract namespace OR localhost HTTPS with a self-signed pinned cert. (3) Default OFF. |

### SEC-012 — No TLS certificate pinning on production OkHttp clients carrying API keys + user voice transcripts
| Field | Content |
|---|---|
| CWE | CWE-295 (absence-of-pinning class) |
| OWASP Mobile | M5 |
| Severity | HIGH |
| Location | `WhisperClient.kt:27-30`, `ReconstructClient.kt:18-21`, `UserDataClient.kt:26-29`, `BubbleCommandMode.kt:226-229` — all `OkHttpClient.Builder().build()` with no `.certificatePinner()`. Endpoints carry `Authorization: Bearer sk-…`. |
| Exploit | MITM via user-trust-store CA (corporate laptop hotspot, public-Wi-Fi attacker tricking user into captive-portal CA install). Captures hosted Anthropic/OpenAI keys, Firebase ID tokens, and every voice transcript in transit. `WhisperClient.transcribe` audio includes raw mic content. |
| Fix | Pin `api.openai.com`, `api.anthropic.com`, `us-central1-bakers-agent.cloudfunctions.net` SPKI hashes via OkHttp `CertificatePinner`. Bundle pins for current + next cert. Kill-switch via Firebase Remote Config. Add `networkSecurityConfig` XML to additionally restrict user-CA trust on Android 7+. |

### SEC-013 — No `network_security_config.xml`; user-installed CAs trusted by default
| Field | Content |
|---|---|
| CWE | CWE-295 |
| OWASP Mobile | M5 |
| Severity | HIGH |
| Location | `AndroidManifest.xml` has no `android:networkSecurityConfig`; no `res/xml/network_security_config.xml` file exists. |
| Exploit | On Android 7+ (compileSdk 35), apps that don't declare a NSC trust user-added CAs. User lured into installing attacker CA (or hostile MDM) becomes MITM-able for all WiM API traffic. |
| Fix | Add `res/xml/network_security_config.xml` excluding user CAs from production trust; reference from `<application android:networkSecurityConfig="@xml/network_security_config">`. Forbid cleartext to all known hosts. |

### SEC-014 — Voice transcripts (potentially PII / passwords typed via dictation in error / financial info) logged at `Log.i` and forwarded to Crashlytics on uncaught coroutine exception
| Field | Content |
|---|---|
| CWE | CWE-532 Insertion of Sensitive Information into Log File |
| OWASP Mobile | M9 |
| Severity | HIGH |
| Location | `BubbleService.kt:1566` (`Log.i(TAG, "Partial: $text")`), `:1571` (`Log.i(TAG, "Final ASR: $text")`), `:2067` (`Log.i(TAG, "Processing (asr=…): $rawText")`); `WhisperClient.kt:208-210`; `OnDeviceRecognizer.kt:83`; `ReconstructClient.kt:610`. Crashlytics catches in `BubbleService.kt:205-212` and attaches exception messages potentially containing user content. |
| Exploit | (a) logcat readable by apps with `READ_LOGS` (Shell on manufacturer apps; dev devices over ADB). (b) Crashlytics payloads upload to Firebase; the `e.message` on certain HTTP errors includes response body (`WhisperClient.kt:171` and `ReconstructClient.kt:707` interpolate `responseBody` into the thrown message). User voice content lands in the Firebase Crashlytics console. |
| Fix | (1) Wrap every `Log.i/d` that interpolates `rawText`, `cleanText`, `text`, `responseBody` in `if (BuildConfig.DEBUG)`. (2) Redaction helper that truncates and replaces alphanumeric runs with `*` before throwing. (3) Disable Crashlytics in debug builds; require explicit user consent before enabling Crashlytics on first launch per GDPR Art. 6(1)(a). |

### SEC-015 — Reconstructed text always copied to clipboard on injection-failure path; no IS_SENSITIVE flag, no auto-clear
| Field | Content |
|---|---|
| CWE | CWE-200 |
| OWASP Mobile | M9 |
| Severity | HIGH |
| Location | `WimAccessibilityService.kt:248-253`, `:314-316`, `:328-331`. `BubbleCommandMode.kt:181-184`. |
| Exploit | Any IME, accessibility service, or `READ_LOGS`-holding app reads `ClipboardManager.getPrimaryClip()`. User just dictated something for one app — it now sits in clipboard until overwritten. |
| Fix | Set `PersistableBundle` with `ClipDescription.EXTRA_IS_SENSITIVE=true` so Gboard / system clipboard managers hide it. Auto-clear after 60s. Refuse clipboard fallback entirely if `WimAccessibilityService.isPasswordField` was true on most recent field. |

### SEC-016 — Firestore session backup pushes raw + reconstructed text plaintext; rules not in repo
| Field | Content |
|---|---|
| CWE | CWE-311 Missing Encryption of Sensitive Data |
| OWASP Mobile | M9 |
| Severity | HIGH (depending on Firestore rules — not in repo) |
| Location | `SessionHistory.kt:153-175` (`pushToFirestore`), `:177-220` (`syncFromFirestore`). Pref `cloud_backup` defaults to `true` (line 154). |
| Exploit | Every dictation lands in `wim_users/{uid}/sessions/{ts}` as plaintext. If Firestore rules are too loose (cross-user reads, public-read fields), or if `wim_users` collection isn't strictly UID-scoped, any signed-in WiM user enumerates others' transcripts. Repo doesn't contain `firestore.rules` — unverifiable from here. |
| Fix | (1) AUDIT `firestore.rules` for exact `match /wim_users/{uid}/{document=**} { allow read, write: if request.auth.uid == uid; }`. (2) Default `cloud_backup` to `false` and require explicit opt-in. (3) Client-side envelope encryption with a per-user key. (4) Add `data_extraction_rules` to ensure backups don't leak via Google's auto-backup of the sqlite db. |

### SEC-017 — Local model download lacks SHA-256 verification of the tar.bz2 (tar-traversal guard is correct)
| Field | Content |
|---|---|
| CWE | CWE-494 Download of Code Without Integrity Check |
| OWASP Mobile | M2 |
| Severity | HIGH |
| Location | `WhisperLocalModelDownloader.kt:35-153`. URL is `https://github.com/k2-fsa/...tar.bz2` (HTTPS OK). Tar-traversal check at line 120 is correct. No checksum verification of downloaded blob. |
| Exploit | If the GitHub release ever gets taken over (account compromise, repo transfer, malicious release-asset replacement), WiM happily downloads and extracts ONNX model files that sherpa-onnx runs in the WiM process. ONNX models can carry custom-op code paths and have had RCE-class CVEs historically. |
| Fix | Bundle expected SHA-256 of `tiny.en-encoder.int8.onnx`, `tiny.en-decoder.int8.onnx`, `tiny.en-tokens.txt` as constants. After unpack, compute and compare before reporting `isInstalled()=true`. Optionally pin GitHub's SPKI on this client too. |

### SEC-018 — Vulnerable / aging dependencies (manual version check; full SCA not run)
| Field | Content |
|---|---|
| CWE | CWE-1395 |
| OWASP Mobile | M2 |
| Severity | HIGH (case-by-case) |
| Location | `app/build.gradle.kts:90-166`. |

Versions to verify via `gradlew dependencyCheckAnalyze` or `osv-scanner` before release:
- `commons-compress:1.27.1` — past CVEs CVE-2024-25710, CVE-2024-26308 fixed in 1.26.0. 1.27.1 should be clear, but this library is the path through which the bz2/tar of SEC-017 is parsed; future CVE in `BZip2CompressorInputStream` becomes remote-decompress vuln. Subscribe to advisories.
- `commons-codec:1.17.1` — fine as of cutoff.
- `okhttp:4.12.0` — older 4.x line in maintenance, 5.x is current. No critical CVEs known at cutoff but verify.
- `firebase-bom:33.7.0`, `play-services-auth:21.3.0`, `credentials:1.3.0` — review against Google's security bulletin.
- `mediapipe:tasks-genai:0.10.24` — preview-class library; rapid release; verify current.
- `lottie-compose:6.6.0` — JSON-parsing-heavy; historic CVEs in animation parsers (zip-slip in 5.x); 6.6.0 should be clear.
- `org.json:20240303` — keep current; older versions have stack-overflow DoS via deep nesting.
- `androidx.compose:compose-bom:2024.12.01` — verify December 2024 advisory.
- `sherpa-onnx-1.12.40.aar` — native code, bundled flat-file. Cannot be SCA-scanned via Gradle. Verify upstream advisories at https://github.com/k2-fsa/sherpa-onnx/security/advisories before each release.

Fix: run `./gradlew dependencyUpdates` and `osv-scanner scan --recursive .` as a pre-release gate. Wire into CI.

---

## 3. MEDIUM

### SEC-019 — `allowBackup="false"` set (good) but no `data_extraction_rules` / `backup_rules`
- Severity: MEDIUM. Location: `AndroidManifest.xml:35`.
- `android:allowBackup="false"` correctly stops auto-backup, but for Android 12+ explicit `android:dataExtractionRules` would ensure no transfer happens via device-to-device migration even if a later edit re-enables backup.
- Fix: add `android:dataExtractionRules="@xml/data_extraction_rules"` with `<cloud-backup disableIfNoEncryptionCapabilities="true">` and `<device-transfer>` excluding `<exclude domain="sharedpref" />` and `<exclude domain="database" path="wim_sessions.db" />`.

### SEC-020 — `MainActivity` is `exported="true"` (LAUNCHER) without origin check
- Severity: MEDIUM. Location: `AndroidManifest.xml:72-80`. Standard launcher pattern; verify no MainActivity `onCreate` intent-extra-driven shortcut writes prefs the launcher can't see. Quick scan showed no such path.

### SEC-021 — `WimWidgetProvider` exported; `BootReceiver` exported=false (good)
- Severity: LOW-MEDIUM. Location: `AndroidManifest.xml:108-126`.
- Widget Provider is `exported="true"` (required for app widgets) but only responds to `APPWIDGET_UPDATE`. The `PendingIntent` it creates targets WiM's own BubbleService and is `FLAG_IMMUTABLE` (`WimWidgetProvider.kt:25-27`). No exploit path confirmed.

### SEC-022 — WAV file lifecycle: single shared filename in cacheDir, persists on crash
- Severity: MEDIUM. Location: `AudioRecorder.kt:99` (`cacheDir/wim_recording.wav`); cleanup at `BubbleService.kt:1703,1834`.
- Single shared filename means WAV gets overwritten each session, but on crash mid-session the file persists. If user's adb-debugging is on, the WAV is recoverable.
- Fix: unique-named temp WAV per session, `deleteOnExit()`, finalizer that overwrites with zeros before delete.

### SEC-023 — `GuideActivity` WebView correctly hardens (positive finding)
- Severity: LOW. Location: `GuideActivity.kt:16-37`.
- `javaScriptEnabled=false`, `domStorageEnabled=false`, `allowFileAccess=false`, `allowContentAccess=false`. Keep these settings. Verify `BouncerActivity` onboarding (now Compose-based per comments) has no parallel WebView.

### SEC-024 — Settings screen lacks `FLAG_SECURE`
- Severity: MEDIUM. Location: `MainActivity` (settings UI) has the API key entry field; no `WindowManager.LayoutParams.FLAG_SECURE` set.
- Screen recording / unprivileged screenshots captured while the user types their API key get it in cleartext.
- Fix: `window.setFlags(FLAG_SECURE, FLAG_SECURE)` in MainActivity `onCreate` when API key card is in view; also on any "show last reconstruction" UI.

### SEC-025 — Sign-in logs user email at info level
- Severity: MEDIUM. Location: `AuthManager.kt:56` (`Log.i(TAG, "Signed in as: ${result.user?.email}")`).
- PII in logcat. Debug builds / ADB readable.
- Fix: `Log.i(TAG, "Signed in (uid=${user.uid.take(6)}…)")` — never log email.

### SEC-026 — Profile JSON (learned corrections, L1-transfer detection, trigger words) stored unencrypted; mirrored to Firestore
- Severity: MEDIUM. Location: `ProfileManager.kt` (whole), sync at `:311-326`.
- Vocabulary + corrections + trigger words are personal speech-pattern data and may constitute special-category data under GDPR Art. 9 (speech-disfluency identifier). Encrypted-at-rest on-device + scoped Firestore rules both required.
- Fix: see SEC-005 + SEC-016 combined; ensure GDPR Art. 6 lawful-basis documented and user-visible (privacy policy, in-app disclosure).

### SEC-027 — `WimApp.setAppLanguage()` writes locale prefs without input validation
- Severity: LOW. Location: `WimApp.kt:175-190`. Internal-only callers today; preserve as invariant.

### SEC-028 — `inject_audio` action reads any file path
- Severity: MEDIUM (debug-build). Location: `WimTestReceiver.kt:228-252`. Covered by SEC-006 remediation.

### SEC-029 — `dispatchTapAt` has no rate-limit; combined with SEC-007 enables low-and-slow clickfraud
- Severity: LOW-MEDIUM. Location: `WimAccessibilityService.kt:611-629`.

### SEC-030 — `BubbleService.processTextForTest`/`processAudioForTest` exposed as public methods on a static `BubbleService.instance`
- Severity: MEDIUM (architecture). Location: `BubbleService.kt`.
- Static service refs are an Android Lint warning class. They make IPC isolation impossible because anything in-process can grab the service and call its public methods.
- Fix: make `instance` `internal` and gate test entry points on `BuildConfig.DEBUG && Build.IS_DEBUGGABLE`.

---

## 4. HYGIENE

- `BubbleService` ~3000+ LOC monolith with `WimTestReceiver`-driven side doors interleaved with production logic — every refactor risks a debug-only branch escaping to release. Move all test plumbing into `app/src/debug/`.
- `lastInjectedExact` snapshot held in static memory in the accessibility service (`WimAccessibilityService.kt:49`) — survives until process death. If user dictates "my SSN is …", value sits in process memory until GC. Wipe on screen-off and on field-change.
- `WimTestReceiver.kt:421` logs `"Pref set: $key=$value"` — innocuous for most prefs, dangerous if invoked with `api_key` or `anthropic_api_key`. Block these keys explicitly in `handleSetPref` or redact.
- `relayToTermux` (SEC-011) is an "easter egg" relay with no setting toggle — drop it from release builds.
- `proguard-rules.pro` keeps app classes by reflection (`-keep class com.wim.app.*`) which is overly broad — defeats minification's secondary benefit of class-name obfuscation that helps slow down reverse-engineering of the bundled-key extraction.
- `compileSdk=35`, `targetSdk=35` ✓; `minSdk=26` exposes pre-API-29 file access patterns but isn't critical.
- No `data_extraction_rules.xml` (see SEC-019).
- `AuthManager.getIdToken()` is `suspend` and is called from `ReconstructClient.reconstruct` (`ReconstructClient.kt:105`) — verified that `reconstruct` itself is a suspend function so the call is correct (no blocking misuse).

---

## Appendix: Confirmed-not-vulnerable

- `GuideActivity` WebView (`GuideActivity.kt:16-37`) — JS off, file/content access off.
- Password field detection wired into `BubbleService.startListening` (`BubbleService.kt:1479-1490`) — refuses to record. Good. (Note SEC-009/SEC-010 — same protection NOT applied to a11y event forwarding or test-receiver tree dump.)
- Tar extraction (`WhisperLocalModelDownloader.kt:120-122`) — canonical-path traversal check is correct.
- `Intent.FLAG_IMMUTABLE` on widget PendingIntent (`WimWidgetProvider.kt:25-27`) — correct.
- BootReceiver checks permissions before starting service — correct.
- `signingConfigs.release` exists (vs. accidentally signing release with debug key) — but see SEC-003 for the password issue.
- `AuthManager.signInWithGoogle` catches specific Firebase exceptions appropriately.
- `Whisper hallucination patterns` stripped before logging (`WhisperClient.kt:108-118`) — but raw text is still logged at `:208-210`.

