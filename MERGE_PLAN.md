# WiM Android — Three-Ear Merge Plan

**Goal:** collapse `backup-vosk` (Vosk live-streaming ear) and `test-whispercpp` (whisper.cpp post-stop accuracy ear) into `main` to produce ONE APK that can invoke, per user toggle:

1. Existing cloud Whisper + Android `SpeechRecognizer` / `OnDeviceRecognizer` (baseline)
2. Vosk live-streaming engine (replaces the live ASR path, no chirp)
3. whisper.cpp post-stop accuracy pass (runs AFTER recording, compared against cloud result)

---

## TL;DR (read this first)

- **Merge `backup-vosk` into `main` FIRST**, then `test-whispercpp`. Vosk is ~442 lines of pure-JVM code with no native build; whisper.cpp is ~500 lines of app code PLUS a vendored whisper.cpp source tree, NDK config, and a JNI bridge. Start with the lower-risk one.
- **Total first-party conflict surface: 5 files.** All five are trivially resolvable by hand (mostly "keep both blocks" — additive changes landing in adjacent regions). No destructive overwrites; no deleted code on either branch.
- **Pre-merge refactor on `main` is RECOMMENDED but OPTIONAL.** A small refactor (extract the live-ASR callback object in `BubbleService.startRecording()` into a shared helper) cuts conflict risk in the biggest file. It's ~30 minutes of work. If skipped, the conflict still resolves cleanly — just slightly messier.
- **Estimated total operator effort: 2–4 hours** (merges + conflict resolution + one Gradle build + one manual smoke with all three engines). Most of that is the first full `./gradlew assembleDebug` on `test-whispercpp` — whisper.cpp + ggml compile across two ABIs is the slow part.
- **Top risk: native build + APK size.** Vendoring the full whisper.cpp source + `armeabi-v7a` ABI filter means this merge can triple the debug APK size and add ~5 min to first build. Mitigation details below.

Branch commits at the moment this plan was produced:
- `main` = `748d7a1`
- `origin/backup-vosk` = `87b874a`
- `origin/test-whispercpp` = `4e46539`

---

## 1. Branch Commit Inventory

### `main..backup-vosk` (2 commits, both feature-complete)
```
87b874a  Vosk phase 2: downloader + settings UI + engine selector
1e1385c  Scaffold Vosk backup ASR engine (compiles, not wired)
```

### `main..test-whispercpp` (3 commits, feature-complete)
```
4e46539  Phase-2 wiring: whisper.cpp as post-stop accuracy path, settings UI, model downloader
e12129d  Language param + CPU-count n_threads for whisper.cpp
aabbc97  whisper.cpp integration scaffolding — builds clean
```

### Shared ancestry check
`git log backup-vosk..test-whispercpp` returns the same three whisper.cpp commits — confirms **the two branches have no shared commits beyond `main`**. They are fully independent, and neither depends on the other. Order is flexible, but a recommendation is given below.

---

## 2. Per-Branch File Change Summary

### `backup-vosk` vs `main` (442 insertions / 29 deletions across 7 files)
```
 app/build.gradle.kts                                     |   6 +
 app/src/main/java/com/wim/app/BubbleService.kt          |  87 ++++---   <-- MODIFIES live-ASR region
 app/src/main/java/com/wim/app/MainActivity.kt           |  66 +++++     <-- adds setupVoskSettings()
 app/src/main/java/com/wim/app/VoskEngine.kt             | 130 +++++++++ NEW
 app/src/main/java/com/wim/app/VoskModelDownloader.kt    | 115 +++++++++ NEW
 app/src/main/java/com/wim/app/WimApp.kt                 |   2 +
 app/src/main/res/layout/activity_main.xml                |  65 +++++
```
**Native code: NO.** Vosk ships a precompiled AAR. Just a Gradle dep bump.

### `test-whispercpp` vs `main` (first-party only — excludes vendored whisper.cpp source)
```
 app/build.gradle.kts                                     |  18 +     <-- NDK + CMake config
 app/src/main/cpp/CMakeLists.txt                          |  26 +  NEW
 app/src/main/cpp/whisper_jni.cpp                         |  94 +  NEW
 app/src/main/java/com/wim/app/BubbleService.kt          |  91 +--   <-- MODIFIES post-stop region
 app/src/main/java/com/wim/app/LocalWhisperClient.kt     | 103 +  NEW
 app/src/main/java/com/wim/app/MainActivity.kt           |  94 +     <-- adds setupLocalWhisperSettings()
 app/src/main/java/com/wim/app/WhisperModelDownloader.kt | 133 +  NEW
 app/src/main/java/com/wim/app/WimApp.kt                 |   1 +
 app/src/main/res/layout/activity_main.xml                |  56 +
```
**PLUS** the entire `app/src/main/cpp/whisper/` tree (upstream whisper.cpp source vendored in — 70+ `.cpp` files, mostly examples/bindings that are compile-disabled via CMake cache flags, plus `src/whisper.cpp` and `ggml/src/*.cpp` which ARE compiled).

**Native code: YES.** High-risk items:
- NDK toolchain (`ndkVersion = "26.3.11579264"`)
- ABI filter: `arm64-v8a` + `armeabi-v7a`
- Externalnative build via CMake 3.22.1
- `c++_shared` STL, `-O3`, `-std=c++17`

---

## 3. Conflict Map — Files Touched by Both Branches

Exactly **5 first-party files** overlap. No branch creates a file the other also creates; no branch deletes a file the other modifies.

| File | `main` | `backup-vosk` change | `test-whispercpp` change | Conflict severity |
|---|---|---|---|---|
| `app/build.gradle.kts` | baseline | adds 2 deps (Vosk AAR, JNA) in `dependencies` block, line ~78 | adds `ndkVersion`, `ndk.abiFilters`, `externalNativeBuild { cmake { ... } }` inside `android { ... }` block (lines 7–17 and ~56), no dep changes | **TRIVIAL.** Both insertions land in different sections. Keep both; no ambiguity. |
| `app/src/main/java/com/wim/app/WimApp.kt` | baseline | `+ fun useVosk(): Boolean = prefs.getBoolean("use_vosk", false)` | `+ fun useLocalWhisper(): Boolean = prefs.getBoolean("use_local_whisper", false)` | **TRIVIAL.** Both add one getter to the same `companion object`. Keep both adjacent. |
| `app/src/main/java/com/wim/app/BubbleService.kt` | 2097 lines | Modifies `startRecording()` live-ASR block (around line 720-740) — wraps `onDeviceRecognizer?.start(...)` in an `if (wantVosk) { voskEngine.start(...) } else { onDeviceRecognizer.start(...) }` switch. Also adds 2 field decls and `voskEngine?.destroy()` in `onDestroy()`. | Modifies `stopAndProcess()` post-recording block (around line 790-830) — reworks the cloud Whisper try/catch into a dual-path (cloud + local) pipeline with a `when` block picking `finalText`. Also adds 1 field decl and a warm-load `scope.launch { ... }` in `onCreate()`. | **LOW-TO-MEDIUM.** Regions **do not overlap** — Vosk touches the live-recording block, whisper.cpp touches the post-recording block. Field declarations both land near the top; both `onCreate()` additions land in the same method but at different anchors. Probability of clean 3-way merge: high. |
| `app/src/main/java/com/wim/app/MainActivity.kt` | 1733 lines | One-line call `setupVoskSettings()` injected at line ~465 in `onCreate`. Entire `setupVoskSettings()` method (63 lines) appended at end of class just before the final `}`. | One-line call `setupLocalWhisperSettings()` injected at line ~489 in `onCreate`. Entire `setupLocalWhisperSettings()` method (92 lines) appended at end of class. | **LOW.** The injection points are ~24 lines apart (both in `onCreate`, different sections). The class-end append will produce a `<<<<<<<` block that's resolved by "keep both, append one after the other." Standard mechanical resolve. |
| `app/src/main/res/layout/activity_main.xml` | baseline | Inserts a 65-line `<LinearLayout>` "Vosk ASR" settings card right before the `<!-- DAF toggle -->` comment (line 1400) | Inserts a 56-line `<LinearLayout>` "Local whisper.cpp" settings card **at the same line 1400**, right before the same DAF comment | **MEDIUM (textual), LOW (semantic).** Both changes use the IDENTICAL anchor line. `git merge` will conflict. Resolution: keep both blocks, one after the other, in either order. No layout logic conflict — they are sibling `LinearLayout`s inside a vertical parent. |

### Files with native-code footprint (only `test-whispercpp`)
- `app/src/main/cpp/CMakeLists.txt` — NEW, 26 lines. No conflict.
- `app/src/main/cpp/whisper_jni.cpp` — NEW, 94 lines. No conflict.
- `app/src/main/cpp/whisper/**` — NEW vendored submodule-as-source, ~70+ files. No conflict but big.

### AndroidManifest.xml
**Neither branch modifies it.** No new `<service>`, no new permissions, no new exports. (Engines reuse existing `BubbleService` plumbing.)

---

## 4. Recommended Merge Order + Justification

### Recommendation: `backup-vosk` FIRST, then `test-whispercpp`

**Why Vosk first:**
1. **Smaller blast radius.** 442 lines of Kotlin + 1 AAR dep. No NDK, no CMake, no ABI filter. If it breaks, you know exactly which lines broke it.
2. **Shorter build cycle.** Vosk merge introduces no native compile. First post-merge `./gradlew assembleDebug` finishes in seconds after incremental. Lets you validate the merge-into-main mechanics cheaply before you add the harder one.
3. **Ear independence.** Vosk is a LIVE-path engine (pre-stop). Whisper.cpp is a POST-path engine (post-stop). Merging Vosk first means the `stopAndProcess()` region — which is what whisper.cpp rewrites — is untouched when you start the second merge. No compounding of diffs in the highest-value method.
4. **Both branches touch the same XML anchor (line 1400).** Merging Vosk first means the whisper.cpp merge will conflict against a DIFFERENT anchor (the newly-merged Vosk card ends ~65 lines lower). Either order works; Vosk-first is no harder than the reverse.

### Trade-off acknowledged
Running Vosk first means the final `test-whispercpp` merge happens on a `main` that already has a new file (`VoskEngine.kt`), new settings UI, and a modified `startRecording()`. But `test-whispercpp` doesn't touch any of those regions, so the second merge's conflict surface shrinks to just: `WimApp.kt` (1 line), `MainActivity.kt` (2 injection points, end-of-class append), `activity_main.xml` (neighboring sibling card), `BubbleService.kt` field decls + `onDestroy` (both near the top). No worsening.

---

## 5. Pre-Merge Refactor Checklist (OPTIONAL, on `main`)

Only one of these is worth the 30-minute investment. The others are over-engineered for a 5-file conflict surface.

### Recommended: extract the live-ASR callback object (`main` only, before any merge)
In `BubbleService.startRecording()` there is a 30-line anonymous `OnDeviceRecognizer.ResultListener` object. Both branches would benefit if that listener were a top-level private method or a named class member. Vosk's branch ALREADY de-duplicates it into `handlePartial/handleFinal/handleSound/handleError` lambdas. Landing that exact de-duplication on `main` FIRST makes Vosk's diff vanish in that block.

**Action:**
1. On `main`, extract `onPartialResult/onFinal/onSound/onError` into four private `fun` members of `BubbleService` that take a `String` (or `Int`).
2. Replace the anonymous object in `startRecording()` with one that delegates to those four methods.
3. Commit `refactor: extract live-ASR callbacks into named methods`.

**Benefit:** Vosk's `BubbleService.kt` merge becomes essentially additive — a new `if (wantVosk) { voskEngine.start(sharedCallback) } else { onDeviceRecognizer.start(sharedCallback) }`. Halves the review time on the biggest-risk file.

**If skipped:** Vosk's merge still works — you just resolve a 30-line replace-with-callback-refactor block manually. Not a blocker.

### NOT recommended (over-engineered for this scale)
- ~~Extracting settings-row XML to a reusable `<include>`~~ — Would require defining a row layout with `<attr>` data-binding, changing two existing rows, AND being generic enough for Vosk's two-row vertical and whisper's horizontal. Design-time cost > merge-time saved.
- ~~Feature-flag getter object~~ — The getters are 1-line each. No abstraction payoff for 3–5 flags total.

---

## 6. Merge Execution Runbook

**All steps run on George's machine, from git-bash, in the existing checkout. No worktree, no remote pushes until George approves.**

### Column A — Operations in an ISOLATED integration branch (safe to throw away)

```
Step 1. Sync local refs
   git checkout main
   git pull

Step 2. (OPTIONAL) Land pre-merge refactor on main
   (see Section 5 above — extract callback methods, commit, continue)
   # Skip if you want the simpler but slightly messier merge experience.

Step 3. Create Vosk integration branch
   git checkout -b merge/vosk-into-main

Step 4. Merge backup-vosk
   git merge origin/backup-vosk
   # Expected conflicts:
   #   - app/build.gradle.kts: NONE (Vosk only touches dependencies block on this branch)
   #   - app/src/main/java/com/wim/app/WimApp.kt: NONE if only Vosk lands here
   #   - app/src/main/java/com/wim/app/BubbleService.kt: NONE if refactor (Step 2) landed; otherwise a clean additive 60-line block in startRecording()
   #   - app/src/main/java/com/wim/app/MainActivity.kt: NONE
   #   - app/src/main/res/layout/activity_main.xml: NONE
   # If there are conflicts at this stage, something unexpected changed on main after this plan was written.

Step 5. Build and smoke-test
   ./gradlew assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   # Manual: enable Vosk toggle, download model, record, verify live transcription works and no chirp.
   # Manual: toggle Vosk OFF, record — confirm original SpeechRecognizer path still works.

Step 6. Fast-forward main to integration branch
   git checkout main
   git merge --ff-only merge/vosk-into-main
   # DO NOT push yet. Keep main local-only until second merge validates.

Step 7. Create whisper.cpp integration branch
   git checkout -b merge/whispercpp-into-main

Step 8. Merge test-whispercpp
   git merge origin/test-whispercpp
   # Expected conflicts (all mechanical, all "keep both"):
   #   - app/build.gradle.kts: POSSIBLE conflict if Vosk's new dep line is adjacent to whisper.cpp's externalNativeBuild block.
   #     Resolution: keep Vosk's 2 dep lines in `dependencies { }`, keep whisper.cpp's ndkVersion/abiFilters/externalNativeBuild in `android { }`. Sections are different; just accept both.
   #   - WimApp.kt: keep both `useVosk()` and `useLocalWhisper()` getters. Trivial.
   #   - BubbleService.kt:
   #       * Field declarations (voskEngine, useVoskForCurrentSession, whisperModelFile) — keep all three. Adjacent lines.
   #       * onCreate() — keep both `voskEngine = VoskEngine(this)` and the whisper warm-load scope.launch block. They sit in different sub-regions.
   #       * startRecording() — UNTOUCHED by whisper.cpp. No conflict.
   #       * stopAndProcess() — UNTOUCHED by Vosk. No conflict.
   #       * onDestroy() — keep both `voskEngine?.destroy()` (Vosk) and no destroy needed for whisper (but LocalWhisperClient.nativeRelease() would ideally be called; verify on code review if the branch omitted it intentionally).
   #   - MainActivity.kt:
   #       * Two separate one-line calls into onCreate — keep both.
   #       * Two separate private methods appended at end-of-class — keep both, in the order Vosk-then-whisper or vice versa. No functional difference.
   #   - activity_main.xml:
   #       * Both branches insert at the same anchor. Resolution: keep both sibling <LinearLayout> cards, Vosk's first then whisper's second (matches merge order visually). Verify no duplicate android:id values (there aren't any — IDs are distinct: voskToggle, voskStatus, voskDownloadBtn vs localWhisperToggle, localWhisperStatus, localWhisperDownloadBtn).

Step 9. Build — this is the slow one
   ./gradlew assembleDebug
   # First build: CMake configures whisper.cpp for arm64-v8a + armeabi-v7a, compiles ggml + whisper. Expect 3-6 minutes.
   # If this fails, you almost certainly have a CMake option conflict or missing include. Read the gradle log carefully; CMake errors bubble up clearly.

Step 10. Install and smoke-test all three engines
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   # Matrix below (Section 7).

Step 11. If smoke passes, fast-forward main
   git checkout main
   git merge --ff-only merge/whispercpp-into-main

Step 12. (Only after George confirms) push
   git push origin main
```

### Column B — Steps that directly touch `main`
- Step 1 (git pull)
- Step 2 (optional refactor commit — IF taken, it is the only code commit landing directly on main before the feature merges)
- Step 6 (fast-forward after Vosk validated)
- Step 11 (fast-forward after whisper validated)
- Step 12 (push, after George's approval)

Everything else happens on throwaway `merge/*` branches. If any validation fails, just delete that branch and retry — `main` is never dirty.

---

## 7. Post-Merge Validation Checklist

### Build-level
- [ ] `./gradlew clean assembleDebug` completes with no errors.
- [ ] APK size check: `ls -la app/build/outputs/apk/debug/app-debug.apk`. Expected:
  - Pre-merge baseline: ~25–35 MB debug.
  - After Vosk: +~5 MB (JNA + Vosk AAR native libs for 2 ABIs).
  - After whisper.cpp: +~15–25 MB debug (ggml + whisper native libs for 2 ABIs, debug symbols included). Release APK with stripping should be +~6–10 MB.
  - **Target: debug APK under 75 MB.** If >100 MB, investigate what's being packaged.
- [ ] `apkanalyzer apk summary app-debug.apk` — confirm `libwim_whisper.so`, `libwhisper.so` (or statically linked), and `libvosk.so` or `libvosk_jni.so` all present for both `arm64-v8a` and `armeabi-v7a`.
- [ ] No duplicate native lib name collisions between Vosk and whisper.cpp (unlikely — distinct namespaces).

### UI-level
- [ ] Launch app, open Settings. Confirm both new cards render without pushing other settings off-screen:
  - "Vosk ASR (no chirp, offline)" card with toggle + status + DOWNLOAD button.
  - "On-device whisper" (or similar) card with toggle + status + DOWNLOAD button.
- [ ] Mobile check (375px-equivalent narrow device / split-screen): neither card wraps ugly or hides the download button. Per George's rule — ALWAYS check mobile.
- [ ] Both toggles disabled by default (models not yet downloaded) and enable after model install.

### Runtime — recording matrix
The minimum viable validation matrix is 4 combinations. The 2x2x2 full matrix is 8; most cells are degenerate. Run these four in order:

| # | Vosk | Local whisper | Expected behavior | Priority |
|---|---|---|---|---|
| 1 | OFF | OFF | Exactly `main`'s current behavior — cloud Whisper + OnDeviceRecognizer live path. | MUST |
| 2 | ON  | OFF | Live-streaming via Vosk (no chirp); post-stop cloud Whisper runs and output pastes. | MUST |
| 3 | OFF | ON  | Live path unchanged (SpeechRecognizer); post-stop runs BOTH cloud + local whisper.cpp; picks the longer/better. | MUST |
| 4 | ON  | ON  | Live path = Vosk; post-stop = cloud + local whisper.cpp dual-check. This is "all three ears active." | MUST |

Additional spot-checks (defer if time-constrained):
- [ ] Toggle Vosk ON without model installed — should show toast "Download the Vosk model first" and leave toggle OFF.
- [ ] Toggle Local Whisper ON without model installed — same pattern.
- [ ] Record with Airplane mode ON + Vosk ON + Local whisper ON — should still produce a transcription (fully offline path).
- [ ] Kill BubbleService (swipe away) and restart — confirm no crash on `onDestroy` with both `voskEngine?.destroy()` and the (intentional?) lack of a `LocalWhisperClient.release()` call.

### Service-level
- [ ] `adb logcat -s wim_whisper_jni WiM BubbleService` during a Local-Whisper-ON recording. Confirm:
  - "LocalWhisperClient initialized from ..." at BubbleService creation.
  - "Starting transcription with N samples..." at post-stop.
  - "Transcription complete: ..." with non-empty result.
- [ ] With Vosk ON: confirm no audible system chirp at recording start (the whole point of the Vosk path).

---

## 8. Risk Register — Top 3

### Risk 1. Native build fails on first `./gradlew assembleDebug` after whisper.cpp merge
**Likelihood:** MEDIUM-HIGH. Vendored whisper.cpp source trees frequently have CMake options or include paths that drift between Android NDK versions.
**Impact:** HIGH. A broken merge commit on `main` is worse than no merge.
**Mitigation:**
- The runbook runs the merge on a `merge/whispercpp-into-main` branch, NOT on `main` directly. Build fails → delete the branch → no harm.
- Before the second merge even starts, build `origin/test-whispercpp` STANDALONE (`git checkout origin/test-whispercpp && ./gradlew assembleDebug`) to confirm it builds on George's current NDK install. If that fails, the merge isn't the problem — the branch is. Fix the branch first.
- Keep `main` local-only between Step 6 and Step 12 (no push). If Step 9 explodes and you need to back out, you've only touched local refs.

### Risk 2. APK size balloons past Play Store / comfort limits
**Likelihood:** MEDIUM. Whisper.cpp vendored source + two ABIs + debug symbols can easily add 50+ MB to a debug APK. Release with stripping/minification recovers most of it, but George has not yet validated the release flavor with this payload.
**Impact:** MEDIUM. Debug APK size doesn't block Play Store; release does. But debug-install friction over USB also slows iteration.
**Mitigation:**
- After the merge, run `./gradlew assembleRelease` (with existing ProGuard/R8 + native strip rules) and check `apkanalyzer apk summary`. Target: release APK under 50 MB. If over, narrow ABI filter to `arm64-v8a` only (drops `armeabi-v7a`, loses only very old 32-bit devices — acceptable per George's prior Play Store TODO note).
- Longer-term: move the whisper.cpp vendored tree to a `git submodule` at a pinned commit. Not required for this merge; noted as a follow-up cleanup.

### Risk 3. Three ears running simultaneously consume too much RAM / CPU on a mid-range device
**Likelihood:** MEDIUM. Case 4 in the validation matrix (Vosk live + whisper.cpp post + cloud post) means: Vosk model loaded in RAM (~50 MB) + whisper.cpp ggml model loaded in RAM (~140 MB for base.en) + audio buffers + normal app state. On a 4 GB RAM device this is fine; on 2 GB it could OOM.
**Impact:** MEDIUM. A crash during post-stop transcription loses the user's current utterance.
**Mitigation:**
- Toggle UX already gates both ears behind explicit user opt-in + model download — the default user never hits this.
- Add a deferred follow-up: `LocalWhisperClient.release()` (exists as a native JNI method but appears unused) called when the app drops below a low-memory threshold, or on Settings toggle-OFF. Not a merge blocker; add to the WiM Play Store launch TODO list George already maintains.
- For case 4 validation, test on the lowest-end device George has — ideally the same class of device Play Store reviewers use. If it OOMs, restrict the Local Whisper toggle to devices with `>= 3 GB RAM` at runtime.

---

## 9. Summary Table

| Dimension | Value |
|---|---|
| Branches merging | 2 (`backup-vosk`, `test-whispercpp`) |
| Total commits to merge | 5 (2 + 3) |
| First-party files overlapping both branches | 5 |
| Conflict severity | 1 medium (XML anchor collision), 4 low/trivial |
| Native code introduced | YES (by `test-whispercpp` only) — JNI bridge + vendored whisper.cpp + NDK/CMake config |
| Pre-merge refactor recommended | Optional (30 min) — extract ASR callbacks in `BubbleService.startRecording()` |
| Recommended merge order | `backup-vosk` first, `test-whispercpp` second |
| Estimated operator time (merge + resolve + build + smoke) | 2–4 hours, gated by first `assembleDebug` after whisper merge |
| Can be done without a worktree | YES — runbook uses throwaway `merge/*` branches on the existing checkout |
| Push to remote required during planning | NO |
| Final deliverable state | `main` contains all three ears behind toggle + model-download UX; `backup-vosk` and `test-whispercpp` remain as reference branches (do not delete until `main` has shipped and been smoke-tested on at least one external device) |
