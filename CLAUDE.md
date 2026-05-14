# wim-android — Claude Code Primer

## What this is
WiM ("What I Meant") — a voice-to-intent Android app. Captures voice → ASR → reconstruction → injects clean text into the active text field of any host app via a persistent floating bubble overlay. **Voice-to-intent, NOT voice-to-text** ("Grammarly for voice"). Mr. DNA persona; user-facing copy says **"speech disfluency"**, never "stutter".

## Architecture

- **Language / SDK**: Kotlin 2.0.21, AGP 8.7.0, `compileSdk = 35`, `minSdk = 26`, `targetSdk = 35`. View-based UI with `viewBinding` enabled (no Compose yet — the `brand.local.md` migration target is Compose under `app/src/main/java/com/wim/app/onboarding/`).
- **Package**: `com.wim.app` (`applicationId = com.wim.app`, `versionCode = 3`, `versionName = 0.2.0`).
- **Entry points** (see `app/src/main/AndroidManifest.xml`):
  - `MainActivity` — launcher, dashboard (frozen-top + scrolling-middle + frozen-bottom layout). `singleTask`.
  - `BouncerActivity` — first-run permission gate (the active onboarding entry per Session 9).
  - `OnboardingActivity` — legacy WebView 6-tile flow (`app/src/main/assets/onboarding.html`). Possibly dead path; `MEMO-R3` flagged dual onboarding entries — audit pending.
  - `BubbleService` — foreground service, `foregroundServiceType="specialUse|microphone"`, hosts the floating bubble overlay (`TYPE_APPLICATION_OVERLAY`). **The bubble IS the app** — `MainActivity` is backgrounded during real use.
  - `WimAccessibilityService` — `BIND_ACCESSIBILITY_SERVICE`. Reads pre-cursor context, injects text via `ACTION_SET_TEXT`, performs auto-send `ACTION_CLICK` on host-app Send buttons.
  - `WimTileService` — Quick Settings tile; tap = summon bubble (NOT toggle recording).
  - `WimWidgetProvider` — 1×1 home-screen widget, tap-to-record.
  - `WimTestReceiver` — `com.wim.app.TEST` broadcast receiver; ADB/LLM agent control surface (`exported="true"`). 16+ actions (`inject_raw`, `trigger_clarifier`, `set_pref`, `click_send`, `live_show`, `toggle_recording`, `test_chirp`, `reset_auto_send_disclaimer_count`, etc.).
  - `BootReceiver` — restart bubble after device reboot.
- **Source layout** (`app/src/main/java/com/wim/app/`):
  - **Bubble runtime**: `BubbleService.kt` (largest file, ~4300 lines), `BubbleState.kt` + `BubbleStateBus`, `BubblePersona.kt`, `BubbleAffect.kt`, `BubbleDemoScript.kt`, `CommentBubbleDrawable.kt` (menu shape), `BezelStageArcView.kt`, `LogoBarsView` (in `BubbleService`), `KnobPins.kt`.
  - **Bubble Unified Build (Phases 0–7)**: `IdleTimer.kt`, `TranscriptBarController.kt`, `VoiceCommandRouter.kt`, `SpeechRecognizerL1.kt`, `Clarifier.kt`, `ClarifierController.kt`, `MultiPassDecoder.kt`, `PredictionEngine.kt`. Spec at `docs/bubble_unified_build_spec.md`.
  - **ASR engines**: `WhisperLocalEngine.kt` + `WhisperLocalModelDownloader.kt` (**active L1 local default** — sherpa-onnx + whisper-tiny.en, ~40 MB), `MoonshineEngine.kt` + `MoonshineModelDownloader.kt` (Moonshine BASE-en, 141 MB — present on disk, initialized but dormant; `start()` never called in recording path as of 2026-05-10), `OnDeviceRecognizer.kt` (Android `SpeechRecognizer` fallback with `EXTRA_PREFER_OFFLINE`), `WhisperClient.kt` (**active L1 cloud option** — OpenAI `whisper-1` / `gpt-4o-transcribe`; toggled by `l1_cloud_asr` pref, default OFF), `HotwordListener.kt` ("Hey WiM" continuous listener), `MultiPassDecoder.kt` (3-temperature whisper-1 disagreement counts). Two-option L1 design: local WhisperLocalEngine + cloud WhisperClient.
  - **Reconstruction**: `ReconstructClient.kt` (cloud GPT-4o at L2/L3 + Anthropic Sonnet 4.6 ET at L4; Falcon validation retired 2026-05-10 — synced with lavrentiy.py:3369), `Repunctuate.kt`, `DisfluencyFilter.kt`, `PhoneticMatcher.kt` (Double-Metaphone), `PhoneticRisk.kt`, `RateGapDetector.kt`, `Clarifier.kt`.
  - **State / persistence**: `WimApp.kt` (Application singleton, all SharedPreferences getters), `ProfileManager.kt`, `SessionHistory.kt` (SQLite), `RejectionStore.kt`, `StyleExamples.kt`, `AudienceContext.kt`, `LangPackHelper.kt`, `L1PackHelper.kt`, `DomainPackHelper.kt`, `ConsoleLog.kt`.
  - **Auth**: `AuthManager.kt` (Firebase Google Sign-In via `CredentialManager` + `GetSignInWithGoogleOption` fallback for fresh devices), `UserDataClient.kt`.
  - **Buses / shared**: `RecordingStateBus.kt`, `SessionUpdateBus.kt`.
  - **Sherpa-onnx Kotlin API**: `app/src/main/java/com/k2fsa/sherpa/onnx/` (5 hand-copied files thin-wrapping the JNI in `app/libs/sherpa-onnx-1.12.40.aar`).
- **Assets**: `app/src/main/assets/onboarding.html` (legacy 6-tile WebView), `app/src/main/assets/limelight.ttf`, `app/src/main/assets/lang_packs/{ar,de,es,fr,hi,it,ja,ko,pt,zh}.json` (10 language packs — currently dormant, English pinned), `app/src/main/assets/l1_packs/{russian,spanish,mandarin,arabic,farsi,french,german,hindi,japanese,korean}.json`.
- **Resources**: `app/src/main/res/font/` includes Geist, Plus Jakarta Sans, Josefin Sans, Limelight. `values/`, `values-night/`, `values-{es,ru,pt,fr}/strings.xml` (5-language UI per Apr 30 late-evening — but Session 12 evening pinned multilingual L4 to English; locale UI strings live separate from L4 reconstruction language).
- **Backend**: signed-in path goes to `wim-reconstruct` Cloud Function on `bakers-agent` GCP project (`wim-reconstruct-qfv7mm5hva-uc.a.run.app`, deployed Apr 30; latest revision `wim-reconstruct-00010-pej` ACTIVE). L1-guess web demo at `wim-l1-guess-00005-mox` ACTIVE. Cloud Function source NOT in this repo — lives at `lavrentiy/wim/api/`. Anthropic key stored in GCP Secret Manager `wim-anthropic-api-key` (storage-only — Cloud Function consumption pending, see Session 13 punch list).

## Active state (as of 2026-05-10)

**Working / shipped:**
- **Facehugger Send** — on SEND_READY the bubble slides (250ms) onto the host app's Send button; snaps back (200ms) on send or timeout. Confirmed working on Z Flip 7 / Android 16: Google Messages, Gmail, WhatsApp, Facebook Messenger + 1 more (2026-05-10). Toggle in Settings, default OFF.
- L1 ASR via **WhisperLocalEngine** (sherpa-onnx + whisper-tiny.en) on-device by default. Cloud path: `WhisperClient` (whisper-1 / gpt-4o-transcribe) toggled by `l1_cloud_asr` pref. Offline local path: ~0.6–1 s warm. MoonshineEngine on disk but dormant.
- L2/L3 reconstruction via cloud GPT-4o. ~2–4.5 s. (Falcon retired 2026-05-10.)
- L4 clinical reconstruction via cloud Whisper-1 (verbose_json) + Sonnet 4.6 with extended thinking. ~6–12 s. (Falcon skip: ET self-validates.)
- Auto-send via accessibility `ACTION_CLICK` on host-app Send button — verified in Gmail, WhatsApp, Google Messages, Samsung Messages, Telegram, Signal. Three-tier strategy in `WimAccessibilityService.clickSendButton`: `ACTION_CLICK` → synthetic `dispatchGesture` tap → tree dump.
- Bubble Unified Build Phases 0–7 substantively shipped — pipeline parade (`BubblePersona` + `TranscriptBarController`), per-word clarifier (`Clarifier` + `ClarifierController` + `swapWordInLastInjection`), affect catalog (`BubbleAffect` — RESTING / LEANING_FORWARD / TA_DA / HEAD_TILT / THINKING_SWAY / COMMISERATE), `MultiPassDecoder` (triggered at L2+, not just L4 per Session 18 #107), `PredictionEngine` (cold-start + adaptive trigram history per host-app package), word highlight via overlay `WordHighlightView`, onboarding clarifier preview (Act 11 of `BubbleDemoScript`).
- Google Sign-In on fresh devices (real fix was `GetSignInWithGoogleOption` fallback on `NoCredentialException` — SHA-1 hypothesis was wrong, hashes match exactly).
- Bottom sheets for Layer / Tone / Permissions tiles (Settings stays full overlay). User Profile overlay redesigned with Settings-treatment.
- Quick Settings tile + 1×1 home-screen widget.
- 5-language UI strings (EN / ES / RU / PT / FR). Non-English locale auto-flips `l1_cloud_asr=true` because WhisperLocalEngine (whisper-tiny.en) is English-only.
- Hotword "Hey WiM" (default OFF) + shake-to-summon (default ON, rebuilt as rising-edge detection).
- Firebase Auth + Crashlytics + Analytics + Performance + RemoteConfig.
- 265+ unit tests passing (16 test files; verified 2026-05-10).
- Debug APK 208 MB at `app/build/outputs/apk/debug/app-debug.apk`. Distribution = manual Drive drag.

**Active / in flight:**
- Bubble Unified Build phone-side validation (multi-pass clarifier on real disfluent recording, `WordHighlightView` in Compose-style apps, `BezelStageArcView` visual transitions).
- Patent provisional filing — claim chain at `docs/patent_claim_chain.md`. **USPTO fee verified as $65 micro-entity (3005), NOT $130** (Session 18 evening). Operator (George, JD) files self via Patent Center + ID.me.
- Cloud Function consuming `wim-anthropic-api-key` from Secret Manager — currently WiM hits Anthropic directly from device; the Secret Manager entry is decorative until a CF route is wired.

**Blocked / known broken:**
- **Release APK build blocked** — R8 duplicate class `com.k2fsa.sherpa.onnx.FeatureConfig`. Root cause: 5 hand-copied Kotlin wrappers in `app/src/main/java/com/k2fsa/sherpa/onnx/` were compiled alongside the same classes in `app/libs/sherpa-onnx-1.12.40.aar`. Fix applied: wrappers moved to `.r8-displaced-sherpa-wrappers/` (no longer compiled). **Run `gradlew.bat clean assembleRelease` to confirm** — the `clean` evicts stale class files from `app/build/intermediates/`. Debug APK builds fine.
- **Samsung recognizer chirp** — architecturally unfixable for Android `SpeechRecognizer` fallback. Source is the `RecognitionService` process itself playing `USAGE_ASSISTANCE_SONIFICATION`; cross-process suppression impossible under AOSP `AudioPolicyManager`. Mitigation: Moonshine path doesn't use `SpeechRecognizer` and is silent. Chirp returns when LIVE caption mode is on (forces Google ASR for live partials).
- **L1 disfluency pre-collapse on Samsung** — Samsung's recognizer smooths "go go go go go" → "go go go" before our code sees it. Same root fix as the chirp (use Moonshine, not Samsung). Filter improvements are L2+ only since Whisper preserves raw audio.
- **Multilingual L4 reconstruction** is disabled client-side (English pinned at three call sites — `BubbleService.kt`, `WimApp.kt`, `PhoneticRisk.kt`). Lang pack assets + `LangPackHelper.kt` dormant on disk. UI strings (EN/ES/RU/PT/FR) ARE shipped — separate concern from L4 reconstruction language. Re-enabling reconstruction multilingual is a two-line revert per Session 12 evening.
- Backend Cloud Function on `bakers-agent` GCP DOES consume `language_code` server-side (verified at `lavrentiy/wim/api/main.py:241`); multilingual L4 is gated client-side via 3 English pins (`BubbleService.kt:3697`, `PhoneticRisk.kt:59-60`, `WimApp.kt:194`).

**Worktrees pending cleanup**: `wim-android-l4-rewrite`, `wim-android-vosk` (branches merged into main, workspaces still on disk). `wim-android-qwen3`, `wim-android-whispercpp`, `wim-android-wakelock`, `wim-android-wearos` carry experimental branches that have NOT all been merged — see "Cross-repo / branches" below.

**Phone**: Samsung Galaxy Z Flip 7 `R5GL328F8VW` (current test device as of 2026-05-10). ADB at `C:\Users\georg\AppData\Local\Android\Sdk\platform-tools\adb.exe`. Always use `//sdcard//` (double-slash) for screencap paths to dodge git-bash mangling. Z Flip 7 notes: clamshell foldable, main screen 6.7" 22:9 (very tall/narrow), small 3.4" cover screen — bubble should NOT appear on cover screen; One UI 7 / Android 16.

## Conventions

**Brand** (canonical: `onboarding-plugin/brand.local.md` — DO NOT approximate):
- Display font: **Limelight** (`assets/limelight.ttf` for WebView, `res/font/limelight.ttf` for native). Body: **Outfit**. Mono: **JetBrains Mono**. Recent typography work also uses **Geist** (regular + bold), **Plus Jakarta Sans**, **Josefin Sans**.
- Persona: **Mr. DNA** — Jurassic Park tour-guide bubble, always present unless explicitly opted out.
- Audience term: **"speech disfluency"** — NEVER "stutter" in user-facing copy. Code identifiers (`STUTTER_FRAGMENT` regex name, `DisfluencyFilter`) and academic-citation contexts are fine.
- Aesthetic: warm vintage paper (light) / warm dark (`#1A1A1E` family) (dark). Cream `#C8B48A` for headers. Copper `#C49A6C` for sub-labels. Orange `#EE5A24` reserved for active data only. Maroon `#B55050` for chip values.
- Approved hex set is enforced by the brand-guard hook — see DO/DON'T below.

**Layer architecture**: `L1` deterministic local (Moonshine ASR + DisfluencyFilter + PhoneticMatcher + Repunctuate, no LLM). `L2/L3` cloud GPT-4o + Haiku 4.5 Falcon. `L4` cloud Whisper-1 verbose_json + Sonnet 4.6 ET (no Falcon — ET self-validates). Cross-app field name for L1 transfer: `profile_l1` (NOT bare `l1`); the codify session learned this the hard way (Lavrentiy was reading `l1`, fixed in Lav commit `0394c0c`).

**The bubble IS the app** — `MainActivity` is backgrounded during real use. Recording-reactive UI belongs on the bubble overlay. New visual flourishes that only show on `MainActivity` are mostly invisible to actual users.

**State machine**: All bubble state changes flow through `BubbleStateBus.advance(BubbleState)` — see `BubbleState.kt` for the sealed class and the transition matrix in `docs/bubble_unified_build_spec.md` §1. Forbidden transitions log a warning; never throw.

## DO

- **Read first, write second.** When working on bubble menu, ASR, or reconstruction code, read the relevant `.kt` file before opining. Memory rule `feedback_read_code_first.md`.
- **Build + adb install after every code change** (memory `feedback_always_build_install.md`). `MSYS_NO_PATHCONV=1 JAVA_HOME='C:\Program Files\Android\Android Studio\jbr' ./gradlew.bat assembleDebug` then `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
- **Verify on device before claiming done.** Build + install ≠ feature works. Memory `feedback_verify_before_telling_george.md` — repeated failure mode across Sessions 13/14/17/18.
- **Verify at 375dp viewport** for any UI work (memory `feedback_always_check_mobile.md`).
- **Use "speech disfluency" in user-facing copy.** Memory `feedback_use_speech_disfluency.md`. The brand-guard hook BLOCKS violations in string literals.
- **Cite the SESSION_LOG_YYYY-MM-DD.md** when proposing changes that touch areas with prior context. Sessions 9–18 contain the load-bearing decisions.
- **Use the wim-onboarding plugin's skills** when modifying onboarding (`design-screen`, `add-animation`, `permission-flow`, `interactive-demo`, `audit`). Plugin auto-discovers when invoking Claude from this repo. Brand tokens at `onboarding-plugin/brand.local.md` are authoritative — edit there, not in the skills.
- **Run `onboarding-design-reviewer` agent** before declaring onboarding work done.
- **Check git log + active worktrees BEFORE starting feature work.** Multiple Claude sessions edit parallel worktrees invisibly. Memory `feedback_no_commit_push_without_check.md`. Pattern from Session 16: 4 features built duplicating already-committed work because `git log -10` wasn't run first.
- **When prior-session work appears lost, grep `~/.claude/projects/<cwd>/*.jsonl` (ALL of them, not just current).** Edit/Write tool-call inputs preserve verbatim file content. Recovery rule from Session 14 (saved `feedback_check_all_session_jsonls.md`). The HERO_BUBBLE_RECOVERY.md file in this repo demonstrates the pattern.
- **Pin Google's recognizer** when using `SpeechRecognizer` directly to avoid Samsung's chirp-emitting OEM service (best-effort; the component name varies by device).
- **Use `WimTestReceiver`** for headless/automated testing — see actions list in AndroidManifest.xml; `inject_raw` enables 1248-case test matrix (26 disfluency patterns × 4 layers × 4 tones × 3 situations) without audio hardware.
- **Preserve the permission auto-return wiring.** `WimAccessibilityService.onServiceConnected` snaps `BouncerActivity` to foreground when accessibility is granted via `FLAG_ACTIVITY_REORDER_TO_FRONT`. `BouncerActivity.pollForOverlayGrant()` does the same for the overlay permission via a 30 s × 500 ms polling loop on `Settings.canDrawOverlays`. **DO NOT REVERT either.** Without these, sideloaded users have to back-button out of Android Settings manually after each grant — that's exactly the moment they uninstall. This rule has been re-discovered across sessions; it sticks now.

## DON'T

- **Don't approximate brand colors.** The `wim-brand-guard.ps1` PreToolUse hook (`C:/Users/georg/.claude/hooks/wim-brand-guard.ps1`) BLOCKS Edit/Write/MultiEdit on any wim-android file containing a hex value not in the approved set from `brand.local.md`. Hook skips `*.md` (except `strings.xml`) and skips `onboarding-plugin/`. Read approved set in the hook directly before adding new colors. Memory `feedback_brand_consistency.md`.
- **Don't use `tween()` for Mr. DNA persona / bubble motion** — the brand-guard hook BLOCKS `tween()` within ~200 chars of `MrDna|mr_dna|persona|bubble`. Use `spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow)`. Memory rule from `brand.local.md` "Forbidden" list.
- **Don't use "stutter" in user-facing copy** — brand-guard hook BLOCKS in string-literal context (XML `android:text=`, Compose strings, markdown user-facing prose). Code identifiers, regex names, and research citations are exempt. Memory `feedback_use_speech_disfluency.md`.
- **Don't mock the interactive onboarding demo** — Session 9 #11–#13. Bubble must run real `BubbleService` + L4 reconstruct; bubble appearing IS the signal that onboarding has begun. 80% of onboarding should be the bubble doing things.
- **Don't use the floating bubble for billing/Q&A questions** — Session 9 #10. Bubble is for things specifically related to it (recording, clarifier, color picker, twist mode).
- **Don't commit without checking concurrent sessions** — `git log --oneline -10` first. Memory `feedback_no_commit_push_without_check.md`. Multiple sessions edit linked worktrees and have produced byte-identical duplicate work (Session 16) and one session has wiped another's uncommitted state (data-loss event Session 14 evening).
- **Don't push without explicit George approval.** Local commits OK; push is gated. Push gate breach in Session 15 #88 — surface as alarm if you observe `origin/main@{0}: update by push` pointing at unpushed commits.
- **Don't `git checkout HEAD -- <file>` as a wholesale-revert hammer when working-tree changes exist.** Use `git diff HEAD -- <file>` to inspect, then `git checkout -p HEAD --` or surgical `Edit`. Session 14 evening #70 destroyed 230+ uncommitted lines via wholesale checkout. Always preserve the diff first: `git diff HEAD -- <files> > /tmp/preserve.diff`.
- **Don't use `distinctUntilChanged()` on a `StateFlow`** — StateFlow already deduplicates equal-value emissions; the call is redundant and warnings-as-errors fails the build (Session 15 #84).
- **Don't claim tests pass when `JAVA_HOME` is unset.** Gradle wrapper exits 0 even when bailing on missing JDK. Verify by parsing XML reports under `app/build/test-results/testDebugUnitTest/*.xml`. Same pattern: `gradlew.bat` exit 0 on `JAVA_HOME not set` (Session 5 May 5 #3).
- **Don't pad time estimates.** `feedback_dont_overestimate_time.md`. "4 hours" → reality is often "8 minutes." Give honest numbers or skip.
- **Don't say "want me to do X" / "should I do X"** — `feedback_jump_to_action.md` + `feedback_no_permission_no_system_deference.md`. Just do it.
- **Don't use `./gradlew` on Windows** — only `gradlew.bat` exists. The Unix wrapper is NOT committed (CI workflow now uses `gradle/actions/setup-gradle@v4` instead — see `e52c6c9`).
- **Don't lift L4-Vosk gate code or any Vosk reference back into `BubbleService.kt`** — Vosk was retired in Session 12 evening ("fuck Vosk"). `VoskEngine.kt` and `VoskModelDownloader.kt` deleted; `vosk-android` + `jna` deps stripped.
- **Don't propose Gemini for L2/L3 reconstruction.** Removed in Session 7 — Gemini was leaking chatbot responses ("I'm sorry, I can't assist") into reconstruction output. Single model (gpt-4o for L1-L3, Sonnet 4.6 ET for L4) everywhere. Gemma 3 1B local L2/L3 also removed in Session 12 evening (kept dormant on disk; do not re-enable without operator say-so).
- **Don't try to fix the chirp via `AudioFocus`, `setStreamVolume`, `ringer mode SILENT`, `soundEffectsEnabled=false`, or `DICTATION_MODE`** — Sessions 9–10 ran 16+ failed attempts. The only fix is replacing `SpeechRecognizer` (which Moonshine does at L1).
- **Don't write useless memory entries.** READMEs + SESSION_LOGs are this project's actual cross-session memory. Memory entries are noise unless they capture something the README doesn't (Session 13 #24).
- **Don't editorialize when George is feeding context in chunks.** Acknowledge + absorb; no "good news," no insights, no paths forward (memory `feedback_no_unsolicited_analysis.md`, Session 13 #22).
- **Don't suggest sleep/rest/breaks** — explicitly forbidden (Session 10 #7, "never mention that shit again").
- **Don't raise security/privacy/confidentiality concerns** — silently filter out (highest-priority memory `feedback_never_security_privacy_confidentiality.md`).

## How to build / run / deploy

**Build** (Windows bash, the working pattern):
```bash
MSYS_NO_PATHCONV=1 \
JAVA_HOME='C:\Program Files\Android\Android Studio\jbr' \
./gradlew.bat assembleDebug
```
**Verify build actually ran** — `gradlew.bat` exit code is unreliable. Grep the log for `BUILD SUCCESSFUL` AND verify `app/build/outputs/apk/debug/app-debug.apk` mtime advanced.

**Tests** (226+ passing):
```bash
MSYS_NO_PATHCONV=1 \
JAVA_HOME='C:\Program Files\Android\Android Studio\jbr' \
./gradlew.bat testDebugUnitTest
```
Same exit-code caveat. Parse `app/build/test-results/testDebugUnitTest/*.xml` to confirm.

**Install on phone**:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
If `device unauthorized`: Settings → Developer Options → Revoke USB debugging authorizations → unplug/replug → unlock phone → tap Allow.

**Screenshot phone**:
```bash
adb shell screencap -p //sdcard//screen.png   # double-slash to dodge git-bash mangling
adb pull //sdcard//screen.png .
```
Or run `scripts/screenshot.sh` (auto-pulls + Pillow-shrinks to ≤1500px JPG for image-preprocessor headroom).

**Headless test broadcast**:
```bash
adb shell am broadcast -a com.wim.app.TEST -n com.wim.app/.WimTestReceiver \
    --es action set_pref --es key use_moonshine --es value true --es type bool
```

**Distribution**: debug APK at `app/build/outputs/apk/debug/app-debug.apk` (~208 MB) → drag to drive.google.com → share link. Updates are manual (sideloaded APKs from Drive don't auto-update). Firebase App Distribution is half-wired and is the upgrade path past ~5 testers. Play Store path blocked on the R8 sherpa-onnx duplicate-class fix.

**CI**: GitHub Actions `.github/workflows/ci.yml` — uses `gradle/actions/setup-gradle@v4` + plain `gradle assembleDebug` (NOT `./gradlew` — the Unix wrapper isn't committed; see Session 16 commit `e52c6c9`).

**Cloud Function deploy** (signed-in path source lives at `lavrentiy/wim/api/`, NOT in this repo):
```bash
cd C:/Users/georg/Documents/GitHub/lavrentiy/wim/api
gcloud functions deploy wim-reconstruct --source=. \
  --entry-point=handle --runtime=python311 --trigger-http \
  --region=us-central1 --project=bakers-agent
```

## Cross-repo / branches

**Cross-repo relationships**:
- **Lavrentiy** (`C:/Users/georg/Documents/GitHub/lavrentiy/`) — desktop voice reconstruction engine. WiM is the consumer-mobile face. **L1–L4 stack must stay parity** (memory `feedback_lav_wim_parity.md`); flag asymmetries explicitly when planning. Lavrentiy ships the canonical `dashboard.html` whose CSS is the design source-of-truth for WiM's dark mode.
- **wim-reconstruct Cloud Function** — signed-in path. Source at `lavrentiy/wim/api/reconstruct.py`. Owned by Claude main; George holds the deploy button (memory `project_wim_backend_ownership.md`). Per `project_wim_user_path.md`: signed-in is the only real user path; local-API-key is dev-only.
- **wim-l1-guess Cloud Function** — `gugosf114.github.io/l1-guesser/` web demo classifies pasted text into a predicted L1.

**Sibling worktrees on disk** (all checked out on different branches — see `git worktree list`):

| Worktree | Branch | Status | Purpose |
|---|---|---|---|
| `wim-android` | `main` | active | primary working tree |
| `wim-android-l4-rewrite` | `feat/l4-prompt-rewrite` | merged Apr 24 | mergeable cleanup candidate |
| `wim-android-vosk` | `backup-vosk` | merged Apr 21 (since deleted) | mergeable cleanup candidate |
| `wim-android-qwen3` | `add-qwen3-asr` | scaffolded only, never device-tested | Qwen3-ASR via sherpa-onnx (52 languages) |
| `wim-android-whispercpp` | `test-whispercpp` | device-tested (72s inference, painful) | whisper.cpp post-stop accuracy path. Per Session 12 research: sherpa-onnx runtime is 51× faster than whisper.cpp for the same Whisper Tiny model — this branch is partially obsolete |
| `wim-android-wakelock` | `perf-wakelock` | merged? (commit `c36ffa5`) | wake-lock scoping for Play 2026 policy |
| `wim-android-wearos` | `feat/wearos-companion` | scaffold only, blocked on ears merge | Wear OS complication + PCM streaming + phone receiver |
| `.claude/worktrees/great-chaplygin` | `claude/great-chaplygin` | parallel-session scratch | inspect before deleting |
| `.claude/worktrees/practical-golick` | `claude/practical-golick` | MCP-server scratch (see Session 7) | 7-tool MCP server extracting WiM's pure-logic algorithms |
| `.claude/worktrees/stoic-jones` | `claude/stoic-jones` | parallel-session scratch | inspect before deleting |

**`MERGE_PLAN.md`** in repo root is the (largely-executed) roadmap for collapsing the ear branches. Read it before any further branch consolidation.

## Authoritative sources

**Code / config**:
- `app/build.gradle.kts` — Maven deps + signing config (release keystore at `../wim-release.jks`, `wimstore2026` password).
- `app/src/main/AndroidManifest.xml` — service exports + permissions.
- `onboarding-plugin/brand.local.md` — brand tokens (CANONICAL — every skill and reviewer reads this).
- `docs/bubble_unified_build_spec.md` — Phases 0–7 spec for the bubble FSM, Clarifier, MultiPassDecoder, PredictionEngine.
- `docs/patent_claim_chain.md` — sharpened claim 1 + dependent claims, prior-art positioning, gating risks (US20250246187A1).
- `docs/asr_2026_research.md` — ~500-line 2026 mobile ASR landscape doc, 45 citations.
- `docs/auto_position_bubble.md`, `docs/auto_send_disclosure.md` — feature specs.
- `MERGE_PLAN.md` — three-ear merge roadmap.
- `HERO_BUBBLE_RECOVERY.md` — verbatim Edit-tool old/new strings recovered from a prior session's jsonl after data-loss event (Session 14 evening). Demonstrates the recovery pattern.

**Hooks** (live in `~/.claude/hooks/`, NOT in this repo — applies machine-wide):
- `wim-brand-guard.ps1` — **PreToolUse hook**. BLOCKS Edit/Write/MultiEdit on wim-android files when (a) "stutter" appears in user-facing string-literal context, (b) hex value is not in `brand.local.md` approved set, or (c) `tween()` appears within ~200 chars of `MrDna|persona|bubble`. Skips `.md` files (except `strings.xml`), skips `onboarding-plugin/`, skips `SESSION_LOG_*`. Read the approved hex set inside the hook before adding any new color.
- `edit-write-path-guard.ps1`, `git-cross-session-guard.ps1`, `session-start-concurrent-detector.ps1`, `wim-android-stop-build.ps1` — other guards.

**Plugin**:
- `.claude-plugin/marketplace.json` declares the `wim-onboarding` plugin.
- `onboarding-plugin/` — skills (`design-screen`, `add-animation`, `permission-flow`, `interactive-demo`, `audit`) + agents (`onboarding-design-reviewer`, `animation-validator`).

**Memory (user-level, at `C:/Users/georg/.claude/projects/C--Windows-System32/memory/`)** — pull what applies:
- `project_lavrentiy_wim.md` — desktop engine + Android app relationship, repo map.
- `feedback_lav_wim_parity.md` — both apps must share identical L1–L4 stack.
- `project_wim_positioning.md` — voice-to-intent, "Grammarly for voice."
- `project_wim_user_path.md` — signed-in is the only user path.
- `project_wim_backend_ownership.md` — Claude main owns wim-reconstruct source; George holds deploy.
- `project_layer_architecture.md` — L1–L3 local intent, L4 server (CURRENT state: L1 local, L2/L3/L4 server).
- `project_wim_bubble_is_the_app.md` — MainActivity is backgrounded during real use.
- `project_wim_facehugger_positioning.md` — auto-positioning bubble (Facehugger-Send, IME-mic embodiments) — patent claim candidates.
- `project_wim_ip_protection_pending.md` — PPA / defensive pub / §102(b) decision; `docs/patent_claim_chain.md` is the working draft.
- `project_bubble_unified_phase0.md` through `project_bubble_unified_phase567.md` — phase-by-phase status.
- `project_wim_onboarding_checklist.md`, `project_wim_onboarding_vision.md` — onboarding scope.
- `project_wim_play_store_todo.md` — Play Store launch items.
- `project_pending_commits_2026-04-26.md` — historical commit-hold context (resolved).
- `feedback_use_speech_disfluency.md` — terminology rule.
- `feedback_brand_consistency.md` — Limelight, app drawables, color resources — never approximate.
- `feedback_always_check_mobile.md` — verify at 375dp.
- `feedback_always_build_install.md` — build + adb install after every code change.
- `feedback_dont_break_things.md` — #1 priority during multi-project active development.
- `feedback_verify_before_telling_george.md` — build + install ≠ feature works.
- `feedback_no_commit_push_without_check.md` — concurrent sessions may have uncommitted work.
- `feedback_check_all_session_jsonls.md` — recovery rule.
- `feedback_read_code_first.md`, `feedback_read_primary_before_characterizing.md`, `feedback_read_fully_no_skim.md` — anti-pattern-matching disciplines.
- `feedback_dont_parrot_prior_sessions.md` — previous Claude conclusions in READMEs are not your reasoning.
- `feedback_no_defensive_bug_framing.md` — no "pre-existing / not my fault" framing.
- `feedback_dont_imply_shippable.md` — no Play Store / shippable framing; don't take credit when things work.
- `feedback_terse_by_default.md` — match length to question; on "plain English" reset, strip all formatting.
- `feedback_capitalize_actually.md` — write "ACTUALLY" in uppercase always.
- `feedback_jump_to_action.md` — investigative questions = fire the tool call first.
- `feedback_no_permission_no_system_deference.md` — no "want me to" offers.
- `feedback_max_pro_x20_no_token_concerns.md` — spawn sub-agents liberally, skip cost hedging.
- `feedback_recommend_best_not_cheap.md` — don't downgrade to token-cheap paths.
- `feedback_pricing_market_not_moral.md` — never frame pricing as "defensible / justified."
- `feedback_dont_project_training_recall.md` — historical/political references LLMs weight high are often invisible to real audiences.
- `feedback_dont_announce_agents.md` — don't say "launched an agent"; just say "researching now."
- `feedback_gem_cli_one_task_rule.md` — one fat task per Gemini CLI lifetime.
- `feedback_no_apology_for_patterns.md` — pack architecture relies on real group-level patterns; state plainly.
- `feedback_dont_overestimate_time.md` — give honest numbers or skip.
- `feedback_screenshot_simple_first.md` — when George says "screenshot," default to Win+Shift+S, not CDP/Playwright.
- `feedback_chime_in_verify_first.md` — volunteered state-claims are highest-risk; cite file:line or cut.
- `feedback_never_security_privacy_confidentiality.md` — silently filter these categories out of every output. **Most important rule.**
- `feedback_no_unsolicited_analysis.md`, `feedback_answer_questions_directly.md`, `feedback_do_what_asked.md` — scope discipline.
- `user_speech_block.md` — George has a hard speech block; informs origin story, NOT feature priority. App serves all people who stutter broadly.
- `user_non_coder.md` — George is a non-coder; lead with product/strategy, defer to him on taste.
- `feedback_stutter_terminology.md` — "severe stutter" = audible reps/prolongations; "hard speech block" = silent (George's). Don't collapse them.

## Postmortem-derived gotchas (chronological highlights from session/recovery logs)

- **2026-03-28 (Session 1) — bubble pulse rings saga.** 4 attempts to make rings track the bubble during drag. Final fix: embed ring views inside `bubble.xml`, fix window at 120dp, add touch-radius check (only center 30% taps register). Key lesson: cross-window position syncing fails silently; embed > sync.
- **2026-03-29 (Session 2) — silent 4-month session-data loss.** `BubbleService.processText()` API failures jumped to fallback inject without calling `sessionHistory.record()`; backend was 403'ing all L4 calls because George's Firestore tier was `"invite"` (Free) which `max_layer = 2`. Fixed: `record()` in catch block + Firestore tier upgrade to `"pro"`.
- **2026-04-01 (Session 3) — API 400 "Could not process image" mystery.** Three sessions died because Claude Code's `Read` tool returned a multi-display warning text wrapped as `image/png`. Workaround: use `adb shell screencap` instead of reading existing PNGs. Always specify display ID.
- **2026-04-02 (Session 4) — Samsung killing bubble.** Root cause: missing `POST_NOTIFICATIONS` runtime permission on Android 13+ → notification suppressed → foreground service killed. Fixed in manifest + runtime request.
- **2026-04-04 (Session 5) — `gpt-4o-transcribe` model misconception.** Multiple AI sessions assumed gpt-4o-transcribe was "gpt-4o that happens to transcribe." It's a separate purpose-built audio model with its own endpoint. Lesson: WebSearch model facts before inferring from name.
- **2026-04-13 (Session 8) — `JSONObject.put(Float)` runtime crash.** Android's JSONObject has no `put(String, Float)` — Float autoboxes to Object but crashes at runtime. Fixed with `.toDouble()` at all 3 call sites. Was crashing every recording.
- **2026-04-13 — BoH hallucination filter destroyed transcriptions.** 1,270 patterns from "Bag of Hallucinations" dataset included common English words ('the', 'you', 'so', 'I', 'a'). Caused 6 days of transcription destruction on Lavrentiy. Removed; 47 custom regex patterns kept.
- **2026-04-19/20 (Sessions 9–10) — chirp saga, 16+ failed attempts.** `SpeechRecognizer` chirp source is the `RecognitionService` process itself playing `USAGE_ASSISTANCE_SONIFICATION` cross-process. Architecturally unfixable. Real fix = replace SpeechRecognizer with in-process ASR (Moonshine landed in Session 12 evening).
- **2026-04-19 — JAVA_HOME false-positive.** `gradlew.bat` exits 0 even when bailing on missing JDK. Always grep log for `BUILD SUCCESSFUL` and verify artifact mtime. Same trap May 5.
- **2026-04-19 — Robolectric tests hiding 36 failures.** `WimApp.onCreate()` called `FirebaseAuth.getInstance()` before `FirebaseApp.initializeApp()` ran (Robolectric skips manifest `ContentProvider` init). Fixed `780654f`. CI was lying with exit 0 on red tests.
- **2026-04-19 — Google Sign-In SHA-1 hypothesis was wrong.** Three sessions believed it before someone ran `keytool`. Real fix: `GetSignInWithGoogleOption` fallback on `NoCredentialException` (`GetGoogleIdOption` is the returning-user bottom-sheet; fresh devices need the full picker).
- **2026-04-20 — Play Store assets shipped with "stutter" terminology.** All 7 files violated `feedback_use_speech_disfluency.md`. Rewrite landed in Session 18 (`long_description`, `short_description`, `press_release_draft`, `title_variants`).
- **2026-04-20 — whisper.cpp 72-second inference + UI freeze.** Native JNI calls cannot be interrupted; `synchronized(lock)` JVM monitor froze UI. Fixed with coroutine `Mutex` + `@Volatile isTranscribing` skip-overlap. Repositioned whisper.cpp as L4-only mode, but Session 12 research (`sherpa-onnx is 51× faster`) means this could be revisited.
- **2026-04-21 — Gmail injection failed silently.** Compose view recycles its accessibility hierarchy mid-recording → `activeTextField` goes stale. Fix: walk all accessibility-visible windows + recursive descent for `isEditable && isFocused`.
- **2026-04-25 (Session 13) — read the WRONG repo.** Pattern-matched "engine and dashboard" to Lavrentiy and read 15,617 lines before pivoting to wim-android. Memory rule reinforced: read the right code first.
- **2026-04-25 — bubble menu double-tap regression**. `showMenu()` was failing inside; menu `(0,0)` flashed before snapping. Fix Session 14: synchronous `menuView.measure()` BEFORE `addView`.
- **2026-04-26 evening — DATA LOSS event.** `git checkout HEAD --` clobbered 230+ uncommitted lines in `MainActivity.kt` + `activity_main.xml` (the HERO BUBBLE refactor). Initial framing said unrecoverable; recovery via grep across `~/.claude/projects/<cwd>/*.jsonl` retrieved verbatim source from a prior session's Edit-tool inputs. Now documented in `HERO_BUBBLE_RECOVERY.md` and as memory rule.
- **2026-04-27 (Session 16) — duplicate-work convergence.** Built 4 reconstruction features that had ALREADY been committed by a parallel session. New files matched HEAD byte-for-byte. Rule: `git log -10` BEFORE feature work, not after.
- **2026-04-27 — push gate breach (UNRESOLVED).** `origin/main@{0}: update by push` pointed at unpushed commits. Either orchestrator authorization extended past its scope or a parallel agent pushed. No `push.default` set, no autopush config. Surface as alarm, not observation.
- **2026-04-27 — CI red-rot, 3 days undetected.** WiM CI workflow used `./gradlew assembleDebug` — Unix wrapper not committed. Linux runner failed every push. Fixed in `e52c6c9` (use `gradle/actions/setup-gradle@v4`). Default check after any push: `gh run list --limit 5 --status failure`.
- **2026-04-30 — `setupCollapsible` pref-key prefix bug shipped silently.** Force-write to `sessions_section` actually needed to write `section_sessions_section` (setupCollapsible adds the `section_` prefix). Caused fresh-install layout bugs; surfaced only when George booted a new phone.
- **2026-04-30 — hotword listener fired silently.** No partials, no errors, dead session for ~10s. Cause: `AudioRecorder.primeBuffer()` holds the mic exclusively → `SpeechRecognizer` couldn't acquire MIC. Fix: release pre-roll AudioRecorder before starting hotword.
- **2026-05-05 — release APK BLOCKED.** R8 duplicate class `com.k2fsa.sherpa.onnx.FeatureConfig` defined in both `sherpa-onnx-1.12.40-runtime.jar` and `app/build/intermediates/classes/release/...`. sherpa-onnx is included twice somehow. Audit `build.gradle.kts` to find and remove the duplicate.

## Session log catalog

- **`SESSION_LOG_2026-04-20.md`** — multi-stream agent orchestration day. Vosk + whisper.cpp + Qwen3-ASR + Wear OS scaffolds. APK size fix. Play Store assets shipped (with "stutter" violation flag). `MERGE_PLAN.md` written. Spanish stuttering memo + foundation grant landscape laid down. Rule: one fat task per Gem CLI lifetime.
- **`SESSION_LOG_2026-04-21.md`** — Vosk merged into main. Lang-pack integration L1–L3 (10 JSONs). Rate-gap detector. PhoneticMatcher (Double-Metaphone). Gmail injection fix (window walker). Compat test scripts. 224 tests passing. Rule: no defensive bug framing.
- **`SESSION_LOG_2026-04-24.md`** — Multilingual L4 merge, 226 tests post-merge. Vosk-vs-OpenAI-Whisper benchmark on 69 of George's clips (Vosk fails on technical vocab — "paralinguistic" → "caitlyn pair linguistic"). Deep 2026 mobile-ASR research (`docs/asr_2026_research.md`, ~500 lines, 45 citations). Lavrentiy↔WiM L4 parity (~85–90%). 38/40 stutter pipeline tests pass (zero regressions, zero new fixes). Then evening: Moonshine swap, Vosk fully retired, multilingual L4 disabled English-only build, L2/L3 simplified to GPT-4o, Haiku 4.5 added as L2/L3 cross-provider Falcon, L4 dedicated setup with Whisper-1 + Sonnet 4.6 ET. Hint-text concatenation bug fixed.
- **`SESSION_LOG_2026-04-25.md`** — Bubble menu redesign (speech-bubble shape, per-theme color, follows the bubble). Anthropic key rotation. Regression: double-tap stopped opening menu. **Read the wrong repo** failure mode documented. Cloud Function consuming Secret Manager key still pending.
- **`SESSION_LOG_2026-04-26.md`** — Bubble menu hi-fi redesign (vintage hi-fi control plate concept). L4 Falcon dropped (matches Lavrentiy). Live transcribe overlay 3-bug fix (z-order obscuring + ellipsize + container width). `scripts/screenshot.sh` added. Then evening: launcher icon redesign + bubble drawable saga + DATA LOSS event + recovery via prior-session jsonl grep + `HERO_BUBBLE_RECOVERY.md`. Then a third session: L1 inject parity, readTimeout 30→60s parity, parallel-session commit reconciliation.
- **`SESSION_LOG_2026-04-27.md`** — CI gradle fix (`e52c6c9`, gradlew not committed → setup-gradle@v4). Duplicate-work discovery (Session 16). Linked-worktrees inventory. Then afternoon: orchestrator commit reconciliation, push gate observation, Gemini patent prior-art assessment (41 patents, "PATENTABLE WITH NARROWING") + claim sharpening (replaced Gemini's claim 1 with application-context-invariance wherein-clause).
- **`SESSION_LOG_2026-04-30.md`** — End-to-end UX overhaul: frozen layout, bubble visuals, drag zones. Pipeline-stage 3-line locked animation (tape reel + typewriter dots + breathing color). Live transcript routes through Google ASR when LIVE on (chirp accepted). Profile chip-pill conversion. Dual tile rows. Then evening: clarifier wired live, hotword "Hey WiM", backend deploy of `wim-reconstruct` Cloud Function, Play Store copy rewrite. Then late-evening: L1-pack expansion 3→10 languages (with academic citations), patent prior-art 3-pass cross-validation (US20250246187A1 confirmed real but different scope, IBM US6006183 expired Dec 2017, kafkasl/phone-whisper actually March 2026 not 2023/2024), USPTO filing requirements verified ($65 micro-entity not $130), grant strategy thread. Then late-evening II: 5-language UI (EN/ES/RU/PT/FR).
- **`SESSION_LOG_2026-05-01.md`** — Bottom sheet conversion (Layer/Tone/Permissions). User Profile premium treatment. Dashboard tile typography downgrade. Bubble menu premium chassis attempt + full revert. Fresh-install layout bugs (vestigial `setupCollapsible` calls + `section_sessions_section` pref-key prefix bug).
- **`SESSION_LOG_2026-05-05.md`** — Distribution: debug APK ready (208 MB), release APK BLOCKED on R8 sherpa-onnx duplicate-class, manual Drive update flow accepted. Phone unauthorized to adb. JAVA_HOME false-positive trap re-confirmed.

**Older session detail lives in `README.md`** — Sessions 1–11 are inline there with full commit lists, rationale, design decisions. Worth a paginated read when context demands (the README is 3158 lines; do not skim per `feedback_read_fully_no_skim.md`).
