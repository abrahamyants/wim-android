# Deep Review — wim-android — 2026-05-10

Agents fired: CodeRabbit, Security Auditor, Lav-WiM Parity Checker, Onboarding Design Reviewer, Silent Failure Hunter, Test Coverage Analyzer, PR Code Reviewer, Play Store Readiness, Accessibility/UI Design, Project Health, Build Config.  
Raw outputs: `reports/_raw/`.

---

## TOP 10 MUST-FIX (P0/P1)

| # | Title | Severity | File:line | Agent | One-line fix |
|---|---|---|---|---|---|
| 1 | **Live API keys baked into APK assets** | P0 | `app/src/main/assets/api_key.txt:1`, `anthropic_key.txt:1`, `WimApp.kt:33-55` | Security, Play Store | Revoke both keys now; remove `bundledKey()` from WimApp; release builds must use signed-in Cloud Function path only |
| 2 | **CLAUDE.md documents wrong active L1 ASR** | P0 | `BubbleService.kt:1662-1665`, `WimApp.kt:220-221` | Project Health | Active engine is `WhisperLocalEngine` (whisper-tiny.en); Moonshine `start()` is never called; update CLAUDE.md line 37 and all memory entries referencing Moonshine as default |
| 3 | **Release APK blocked — `clean` not run after sherpa fix** | P0 | `app/build.gradle.kts:95`, `.r8-displaced-sherpa-wrappers/` | Build Config, Play Store | Hand-copied files moved to `.r8-displaced-sherpa-wrappers/` (correct) but stale `app/build/intermediates/classes/` still exists; run `gradlew.bat clean assembleRelease` to confirm |
| 4 | **"Stutter" in user-facing onboarding text** | P0 | `app/src/main/assets/onboarding.html:429` | Onboarding | `<div class="layer-name">Stutter</div>` → `"Speech Disfluency"` (brand-guard hook BLOCKS this if not fixed) |
| 5 | **WimTestReceiver exported=true in production manifest** | P0 | `AndroidManifest.xml:113-118`, `WimTestReceiver.kt:50-53` | Security, CodeRabbit, Play Store | Move receiver declaration to `app/src/debug/AndroidManifest.xml`; remove from main manifest; remove from `proguard-rules.pro:56` |
| 6 | **PredictionEngine.recordCompletion permanently freezes adaptive learning** | P1 | `PredictionEngine.kt:115-118` | Code Reviewer, CodeRabbit | `history.entries.take(64)` keeps OLDEST entries (LinkedHashMap insertion order); change to `.takeLast(64)` |
| 7 | **Falcon retired in Lavrentiy but still live in WiM** | P1 | `lavrentiy.py:3369-3377`, `ReconstructClient.kt:668-697` | Parity Checker | Intentional asymmetry or synced retirement? Requires George decision — currently WiM users get an extra validation pass Lavrentiy users don't |
| 8 | **triggerClarifierForTest / demo clarifier hits forbidden FSM transition** | P1 | `BubbleService.kt:243-249`, `BubbleState.kt:113-116` | Code Reviewer | `Idle → Injected` is a forbidden transition; `enterClarifierCycle` silently fails; demo clarifier tap handler at `BubbleService.kt:1031` checks FSM state → false → user cannot interact |
| 9 | **Bubble root has zero accessibility labeling** | P1 | `app/src/main/res/layout/bubble.xml:2` | Accessibility | Add `android:contentDescription="@string/bubble_description"` and `android:importantForAccessibility="yes"` to `bubbleRoot` |
| 10 | **MultiPassDecoder word-position alignment breaks on punctuation** | P1 | `MultiPassDecoder.kt:80-90` | Code Reviewer | Disagreement walker skips punctuation but primary walker doesn't; off-by-one attaches wrong word to clarifier; skip punctuation-only tokens in primary walker without incrementing `idx` |

---

## Findings by Category

### Code Quality

#### P0
- **BubbleService.kt is 4126 lines** (CLAUDE.md says ~3000, that's 37% off) — confirmed by Project Health agent, line count by `wc -l`.
- **`callOpenAiTransform()` catch block is empty** — `BubbleService.kt:3438-3440`. Exception type, message, stack trace all silently destroyed. Log at minimum. *(Silent Failure)*
- **L2+ reconstruction failure catch gives user zero notification** — `BubbleService.kt:3917`. Catch covers API failure, `learnFromReconstruction`, `recordPredictions`, `Clarifier.evaluate`, `MultiPassDecoder.run` — any of these crashing is indistinguishable. Add a Toast. *(Silent Failure)*

#### P1
- **Whisper transcription failure does NOT call `sessionHistory.record()`** — `BubbleService.kt:1778`. Same structural gap as the historical 4-month data loss bug, one layer higher. *(Silent Failure)*
- **`turboTaxJob` not cancelled in error path** — `BubbleService.kt:3670-3683/3917`. L4 animation loop keeps firing stale pipeline stage labels after `setBubbleIdle()`. Add `turboTaxJob?.cancel()` as first line of catch. *(Silent Failure)*
- **Bluetooth SCO BroadcastReceiver registered but never unregistered** — `BubbleService.kt:456-471`. `scoReceiver` is a local variable; reference discarded; no `unregisterReceiver` in `onDestroy`. Store as member, unregister. *(CodeRabbit)*
- **New `OkHttpClient` created per command transform call** — `BubbleService.kt:3381-3384`. Unreleased thread pools accumulate. Reuse `ReconstructClient.http` or a singleton. *(CodeRabbit, Silent Failure)*
- **`AudioPlaybackCallback` registered but never unregistered** — `BubbleService.kt:397-406`. Holds implicit BubbleService ref; fires indefinitely post-`onDestroy`. *(CodeRabbit)*
- **`processTextForTest` can fire during active recording** — `BubbleService.kt:311-318`. No `isRecording` guard. Races on `lastRawText`, `finalizedSegments`, `processed`. *(CodeRabbit)*
- **Legacy clarifier and FSM clarifier can be active simultaneously** — `BubbleService.kt:134/2306` vs FSM path. Reachable via command-mode pickup → reconstruction race. *(Code Reviewer)*
- **`lastWavFile` race during MultiPassDecoder run** — `BubbleService.kt:3878-3893` vs `BubbleService.kt:1727`. Re-record while clarifier evaluating → WAV deleted under 3 in-flight Whisper-1 calls. *(Code Reviewer)*
- **Falcon silently passes on all exceptions** — `ReconstructClient.kt:694-697`. Every exception returns `true` — Falcon is invisible when systematically broken. Add consecutive-failure counter. *(CodeRabbit)*
- **`ClarifierController.approve()` advances cycle on failed word swap** — `ClarifierController.kt:83-97`. `next()` called regardless of `swapWordInLastInjection` return value. *(CodeRabbit)*
- **`BubbleService.instance` TOCTOU** — Multiple call sites. Service can be destroyed between null check and method dispatch. Add `isRunning` flag set false at top of `onDestroy`. *(CodeRabbit)*
- **`sessionHistory.record()` logs incorrect delivery when `WimAccessibilityService.instance == null`** — `BubbleService.kt:3808-3821`. Records `cleanText = outputText` as if injection happened when it was actually skipped. *(Silent Failure)*
- **`showBubble` main `addView` lacks try/catch** — `BubbleService.kt:1176`. All other overlay sites have guards; permission flip between check and add crashes the foreground service. *(Code Reviewer)*
- **`Clarifier.evaluate` allocates `vocabulary.map` per word in hot path** — `Clarifier.kt:127`. ~6000 string allocs per reconstruction at 200-word vocab × 30-word utterance. Hoist to a Set. *(Code Reviewer)*
- **`languageCode` hardcoded to `"en"` in production processText path** — `BubbleService.kt:3697`. Ignores `WimApp.appLanguage()`. Language-specific filler lists, natural-repeat protections, and Falcon run with wrong language. *(CodeRabbit)*

#### P2
- **`L1PackHelper` injected twice into system prompt for L2/L3** — `ReconstructClient.kt:455,567`. Remove second call. *(CodeRabbit)*
- **`findEditableFocusedDescendant` has unbounded recursion depth** — `WimAccessibilityService.kt:448-456`. Add depth cap ~40 like `dumpAccessibilityTree`. *(CodeRabbit)*
- **400ms on-device-fallback `runnable` not cancelled on ASR error** — `BubbleService.kt:1796-1805`. After error, `processText(lastRawText)` fires on stale content 400ms later. *(CodeRabbit)*
- **`timerHandler` not cancelled in `onDestroy`** — `BubbleService.kt:3778-3783,4101-4125`. PROC-state runnables and legacy clarifier timeout fire post-destruction. *(CodeRabbit)*
- **`activeTextField` holds live `AccessibilityNodeInfo` beyond event scope** — `WimAccessibilityService.kt:18-20,82-119`. Framework nodes are pooled; holding past event callback is fragile; no clear on focus loss. *(CodeRabbit)*
- **`authManager.getIdToken()` failure silently falls through to direct path** — `ReconstructClient.kt:100-106`. Signed-in user with transient Firebase error bypasses tier enforcement, rate limiting, server key. *(Silent Failure, CodeRabbit)*

#### P3
- **No ABI filter — ships all 4 sherpa-onnx ABIs** — `app/build.gradle.kts`. Add `abiFilters += listOf("arm64-v8a")`. *(CodeRabbit)*
- **`isShrinkResources` not set alongside `isMinifyEnabled`** — `app/build.gradle.kts:33`. Optimization opportunity on already-large APK. *(Build Config)*
- **`firebase-bom:33.7.0` outdated; `firebase-perf-ktx` startup overhead** — `app/build.gradle.kts:75`. Evaluate whether Performance data is being consumed; remove if not. *(CodeRabbit, Play Store)*
- **`ReconstructPromptTest` tests constant values not behavior** — 7 of 17 tests assert named constants equal floats. Will fail on any tuning change; no regression value. *(Test Coverage)*
- **`ProfileLearning*` tests test a copy of the algorithm not the shipped code** — Use `ProfileManagerTest` pattern instead. *(Test Coverage)*

---

### Design / UX

#### P0
- **Wrong font on onboarding.html headers** — `onboarding.html:103`. `.tile-title` uses Outfit (body font) for headings; Limelight required per brand.local.md. *(Onboarding)*
- **Wrong font on BouncerActivity page headings** — `activity_bouncer.xml:346,448,576,800,933,1054`. All 6 headings use `android:fontFamily="sans-serif-light"` instead of `@font/limelight`. *(Onboarding, Accessibility)*
- **Mr. DNA persona entirely absent from both onboarding paths** — Neither `onboarding.html` nor `BouncerActivity` has any persona element. brand.local.md: "Always present unless explicitly excluded." *(Onboarding)*
- **`onboarding.html` bubble float uses `ease-in-out` tween** — `onboarding.html:243-245`. Brand rule: tween forbidden for persona motion. Use spring physics. *(Onboarding)*

#### P1
- **Samsung deep-sleep battery gate missing** — Neither `BouncerActivity` nor `onboarding.html` implements the Samsung `Battery → Background usage limits` deep-sleep intent. Users on Samsung (George's own device) who don't manually configure this will have bubble killed overnight. *(Onboarding)*
- **`OnboardingActivity` dead code remains in manifest** — `AndroidManifest.xml:43-46`. Confirmed dead: no code starts it. Writes `onboarding_complete=true` without correct prefs if ever reached. *(Onboarding, Project Health)*
- **Privacy policy URL points to Lavrentiy** — `MainActivity.kt:818`. `github.com/gugosf114/lavrentiy/.../PRIVACY.md` — wrong product. *(Play Store)*

#### P2/P3
- **Text wrapping risk at 375dp** — `.tile-title` (22px, no `max-width`) and bouncer headings (22sp, no `maxWidth`) will wrap on smallest supported phones. *(Onboarding)*
- **`manual_accordion_item.xml` uses `@color/red_bright` (#DC2626) for Guide body text** — `manual_accordion_item.xml:49,79`. Red is reserved for errors/destructive actions per brand. *(Accessibility)*
- **`bubble_color_picker.xml:18` hardcodes `"PICK A BUBBLE"`** — Should use `@string` resource for localization. *(Accessibility)*

---

### Accessibility

#### P0/P1
- **Bubble overlay window may be architecturally invisible to TalkBack** — `TYPE_APPLICATION_OVERLAY` windows exist in a separate layer; without `FLAG_RETRIEVE_INTERACTIVE_WINDOWS` or `AccessibilityNodeProvider` in `BubbleService.kt`, TalkBack cannot reach the entire app surface. Requires code-level verification. *(Accessibility)*
- **`bubbleRoot` has zero accessibility labeling** — `bubble.xml:2`. No `contentDescription`, no `importantForAccessibility`. Even if the overlay is reachable, TalkBack has nothing to announce. *(Accessibility)*
- **`bubbleLabel` emits no `accessibilityLiveRegion` on state change** — `bubble.xml:151`. Recording users get no TalkBack announcement when state changes REC → PROC → DONE. *(Accessibility)*

#### P1
- **`bezelRegen` tap target is 14×14dp** — `bubble.xml:113`. 34dp below Android's 48dp minimum. *(Accessibility)*
- **Color picker options have no `contentDescription`** — `bubble_color_picker.xml:42,100,155`. String resources exist but aren't referenced. *(Accessibility)*
- **Profile overlay close button (✕) has no `contentDescription`** — `profile_overlay.xml:39-47`. TalkBack reads Unicode glyph name. *(Accessibility)*
- **Theme swatches are 26×26dp** — `bubble_menu.xml:192-211`. Below 48dp minimum. *(Accessibility)*
- **Live transcript toggle lacks `labelFor`** — `bubble_menu.xml:61-75`. Switch reads with no context. *(Accessibility)*
- **`BezelStageArcView` has no `contentDescription`** — `bubble.xml:50-55`. Pipeline stage (Transcribing → Reconstructing → etc.) conveyed only visually. *(Accessibility)*

---

### Performance

#### P1/P2
- **`activity_main.xml` nests LinearLayouts 6 levels deep** — Over 1100 lines. Exponential measure cost. Outer shells should be ConstraintLayout. *(Accessibility)*
- **`Clarifier.evaluate` hot-path allocation** — 6000 string allocs per reconstruction. *(Code Reviewer)*
- **Three `scope.launch` blocks create OkHttpClient instances per call** — Thread pool accumulation. *(CodeRabbit)*
- **`firebase-perf-ktx` instruments startup unconditionally** — Can trigger Play Store performance flags. Consider disabling in debug or making conditional. *(Play Store)*

---

### Build / CI

#### P0
- **Release build never confirmed after sherpa-onnx fix** — Run `gradlew.bat clean assembleRelease`. The `clean` is mandatory to evict stale `.class` files from intermediates. *(Build Config, Play Store)*
- **Release AAB (required for Play) has never been built** — `app/build/outputs/bundle/debug/app-debug.aab` exists; no release equivalent. *(Play Store)*

#### P1
- **SESSION_LOG_2026-05-05 and CLAUDE.md propagate wrong R8 cause** — Both document "two Maven declarations" theory. Actual cause: hand-copied `.kt` files + AAR. Update to prevent agents re-hunting nonexistent Maven artifact. *(Build Config)*
- **Keystore password `"wimstore2026"` committed in plaintext** — `app/build.gradle.kts:22-28`. Move to `keystore.properties` + `.gitignore`. *(CodeRabbit, Security)*
- **CI workflow conflict** — Project Health agent found `.github/workflows/ci.yml` healthy (gradle/actions/setup-gradle@v4). Build Config agent found no CI workflow. **Needs direct verification.** *(Conflicting)*
- **3 `.claude/` worktrees (stoic-jones, great-chaplygin, practical-golick) at versionCode=1** — Missing Firebase Crashlytics, Perf, mediapipe, sherpa AAR. Would produce broken APK if built from worktree. *(Project Health)*
- **2 stale merged worktrees** — `wim-android-l4-rewrite` (feat/l4-prompt-rewrite, merged Apr 24) and `wim-android-vosk` (backup-vosk, merged Apr 21 then Vosk retired). Safe to prune. *(Project Health)*

---

### Docs vs Code Drift

| Claim | Actual | Gap |
|---|---|---|
| "Moonshine BASE-en is current L1 default" (CLAUDE.md:37) | WhisperLocalEngine (whisper-tiny.en) is active; Moonshine `start()` never called | Critical — all agent decisions based on this claim are wrong |
| "BubbleService.kt ~3000 lines" (CLAUDE.md:21) | 4126 lines | 37% off |
| "226+ unit tests" (CLAUDE.md:47) | 265+ tests across 16 files | Stale by ~39 |
| "R8 sherpa-onnx duplicate" cause: two Maven decls | Actual: hand-copied Kotlin files + AAR | Wrong theory propagated to future agents |
| versionCode=3, versionName=0.2.0 | Confirmed correct | ✓ |
| Multilingual L4 disabled at 3 sites | Confirmed: BubbleService.kt:3697, PhoneticRisk.kt:59-60, WimApp.kt:194 | ✓ |
| OnboardingActivity is "possibly dead path" | Confirmed dead — no Intent targeting it anywhere | Safe to remove |
| "Falcon: Haiku 4.5 at L2/L3" | Lavrentiy's Falcon is a stub returning `True`; WiM's is live | Parity gap |

---

### Repo-Specific: Brand Drift & Parity

#### Brand Drift
- **`onboarding.html:429`** — "Stutter" in user-facing div text (brand-guard BLOCKS this) *(P0)*
- **`onboarding.html:103`** — `.tile-title` uses Outfit not Limelight *(P0)*
- **`activity_bouncer.xml:346,448,576,800,933,1054`** — `sans-serif-light` not Limelight *(P0)*
- **`onboarding.html:243-245`** — `ease-in-out` tween on persona motion *(P1)*
- **`manual_accordion_item.xml:49,79`** — `red_bright` on informational body text *(P2)*
- **68 of 80 drawables missing night-mode variants** — `drawable-night/` only covers 12 drawables; bubble_bg_idle.xml, bubble_inner_knob.xml, clarifier_half_yes/no.xml, all bubble variants unprotected *(P1)*
- **`HIGH-C1/C2`** — Light-mode `text_dim` (2.40:1) and `text_secondary` (3.93:1) fail WCAG AA on 40+ TextViews *(P1)*

#### Parity Gaps (Lavrentiy vs WiM)

| Gap | Lav | WiM | Impact |
|---|---|---|---|
| Falcon L2/L3 | RETIRED (stub, lavrentiy.py:3369) | LIVE (ReconstructClient.kt:668) | WiM users get extra validation; decision needed |
| L1 local model | faster-whisper large-v3-turbo | whisper-tiny.en | Accuracy diverges on disfluent speech |
| L1_CLOUD_ASR default | ON | OFF | Different behavior out of box |
| L2/L3 ASR fallback | whisper-1 | gpt-4o-transcribe | WiM self-flags as parity gap (BubbleService.kt:1745) |
| MoonshineEngine | Retired Apr 30 | Initialized but dormant (start() never called) | Dead code cleanup |

---

### Test Coverage Gaps

| Component | Tests | Risk |
|---|---|---|
| `MultiPassDecoder.kt` | **ZERO** | Word-swap silently garbles text; punctuation alignment broken |
| `BubbleStateBus` FSM matrix | **ZERO** | Illegal transitions silently log instead of alerting; no regression guard |
| `RateGapDetector.kt` | **ZERO** | Cold-start path fires for all new users; untested boundary |
| `VoiceCommandRouter.kt` | **ZERO** | Handler exception silently swallows user dictation |
| `PredictionEngine.kt` | **ZERO** (needs Robolectric) | Confidence math boundary untested; overflow bug (CRITICAL-2) was undetected |
| `AudienceContext.kt` | **ZERO** | Package→profile mapping regression invisible |
| New Phase 0-7 components broadly | Sparse | 7 new classes, 0 new test files |

**CI status**: Runs `gradle testDebugUnitTest` on every push (per Project Health); no JaCoCo coverage report generated.

---

## Cross-Cutting Patterns

**1. Service lifecycle cleanup is incomplete throughout BubbleService.kt.**  
`onDestroy` does not cancel: `timerHandler` runnables, `turboTaxJob`, `scoReceiver`, `AudioPlaybackCallback`, legacy `clarifierTimeout`. Across 4 independent agents (CodeRabbit, Silent Failure Hunter, Code Reviewer, Accessibility). BubbleService is so large that lifecycle gaps hide in the noise.

**2. Silent failure is the dominant failure mode.**  
8 distinct catch blocks either (a) swallow with no log, (b) swallow with only a ConsoleLog that users never see, or (c) fall back to raw text injection without recording the failure. The historical 4-month data-loss bug (Session 2) has been fixed but the structural pattern that caused it persists at higher layers. Session history is still systematically undercounting.

**3. The "~3000 line BubbleService" mental model is dangerously wrong.**  
At 4126 lines, BubbleService is 37% larger than documented. The file now contains a foreground service, state machine, ASR pipeline, reconstruction pipeline, clarifier FSM, prediction engine integration, command transform, bubble menu, voice command routing, accessibility injection logic, and session management. Code Reviewer found 3 critical-severity bugs in this file alone that stemmed from cross-concern interference (demo/test paths violating FSM, clarifier/command-mode race, WAV file race). The file needs a bounded split into focused controllers.

**4. Phase 0-7 components shipped without tests.**  
Seven new classes were added across Phases 0-7 (MultiPassDecoder, PredictionEngine, Clarifier, ClarifierController, VoiceCommandRouter, RateGapDetector, PredictionEngine). Zero test files were added for any of them. Three of the four CRITICAL bugs found in this audit are in these Phase 0-7 classes.

**5. Documentation lag is creating active technical debt.**  
The wrong L1 ASR engine (Moonshine vs WhisperLocal) is documented in CLAUDE.md and propagated to all cross-session memory. Agents reading this primer plan around Moonshine. The wrong R8 cause is documented in both CLAUDE.md and SESSION_LOG_2026-05-05. Every future agent hunting the "two Maven declarations" will waste session budget on a phantom.

---

## What Looks GOOD

- **226→265 test suite is genuinely solid on the components it covers.** `DisfluencyFilter`, `RiskFlags`, `PhoneticMatcher`, `Repunctuate`, `SessionHistory`, `ProfileManager` all have stress tests + happy-path tests. `SessionHistory` and `ProfileManager` using Robolectric correctly is a good pattern. Zero regressions on the historical pipeline through 18+ sessions of active development.
- **L4 reconstruction architecture is clean and well-isolated.** Sonnet 4.6 ET + Whisper-1 verbose_json pipeline in `ReconstructClient.kt` follows the same pattern as Lavrentiy. The signed-in vs direct-key path branching is clear. Extended thinking budget (8000) is correctly set. L4 Falcon skip (ET self-validates) matches Lavrentiy.
- **Authentication architecture is correct.** Google Sign-In via `CredentialManager` + `GetSignInWithGoogleOption` fallback for fresh devices is the right implementation. The signed-in → Cloud Function path is the only real user path and it's correctly wired. The Fresh-device fix (Sessions 9+) is sound.
- **Brand color discipline in strings.xml is perfect.** Zero instances of "stutter" in any string resource (EN, ES, RU, PT, FR). The onboarding.html violation is isolated to one div element.
- **CI pipeline fix (e52c6c9) is correctly applied.** `gradle/actions/setup-gradle@v4` pattern works around the missing Unix gradlew. Test run is blocking on push to main.
- **WimTestReceiver's runtime `BuildConfig.DEBUG` guard is correct defense-in-depth.** The manifest exposure is the real issue; the code-level guard would stop execution in release even if someone somehow triggered the receiver. Both layers need to exist; right now only the code layer is working.
- **Layer architecture field key `profile_l1` is correct** throughout — the hard-learned fix from `lavrentiy` commit `0394c0c` is consistently applied. No regression to bare `l1`.
- **foregroundServiceType="specialUse|microphone"** with `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` explanation is correctly declared. Required for targetSdk 34+ and frequently missed.
- **`allowBackup=false`** set. Correct for an app that handles audio and keys.
- **Firebase BOM 33.7.0, OkHttp 4.12.0, commons-compress 1.27.1** — all current, no relevant CVEs.

---

## Open Questions for George

1. **Falcon parity decision**: Lavrentiy retired Falcon L2/L3 on 2026-04-30 (`lavrentiy.py:3369-3377` is a stub). WiM still runs live Haiku-4.5 Falcon at L2/L3. Is this intentional differentiation (WiM gets extra validation) or drift to sync? If syncing, it's a 2-line change in `ReconstructClient.kt`.

2. **MoonshineEngine fate**: `MoonshineEngine` is initialized at `BubbleService.kt:409` and `stop()`/`destroy()` called, but `start()` is never called. It is dead code in the recording path. Per CLAUDE.md, "Moonshine BASE-en is the current L1 default" — but ACTUALLY whisper-tiny.en is live. Is Moonshine intentionally shelved (→ delete), or is it a regression (→ wire `start()` back in)?

3. **CI workflow conflict**: Project Health agent found `ci.yml` healthy; Build Config agent found no workflow at all. One is wrong. Run `git ls-files .github/` to determine ground truth before acting on either finding.

4. **L1 local model target**: Lavrentiy uses faster-whisper large-v3-turbo; WiM uses whisper-tiny.en. WiM's L1 is intentionally smaller for mobile (latency/size), but the accuracy gap on heavy disfluency is real. Is `whisper-small.en` (91 MB) a viable middle ground for L1?

5. **OnboardingActivity**: Confirmed dead code — no call site. Delete the class and remove from manifest, or keep as intentional legacy placeholder? Session 9 memo says BouncerActivity is the active path but didn't explicitly say to delete the old one.

6. **API key assets in debug builds**: `api_key.txt` and `anthropic_key.txt` in `app/src/main/assets/` are `.gitignored` (correct) but packaged into every APK regardless of build type. The correct fix removes `bundledKey()` from `WimApp.kt` entirely and makes direct-OpenAI usage require a user-entered key. Is there a dev workflow that depends on the bundled key that would break?

7. **Play Store version**: Memory says bump to 1.0.0 before submission; `build.gradle.kts` still shows `versionName = "0.2.0"`. Confirm target version before first Play upload.

8. **Agent socket server**: `AgentSocketServer.kt` (not mentioned in CLAUDE.md architecture section) listens on TCP 9222. Is this a new feature or leftover from a parallel session? CLAUDE.md should document it if it's intentional.

---

*Raw outputs in `reports/_raw/` — one file per agent. Deduplicated findings: same bug from multiple agents appears once above with all sourcing cited.*
