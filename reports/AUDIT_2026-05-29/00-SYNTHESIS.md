# WiM-android Comprehensive Audit — 2026-05-29

**Wall time**: ~75 min start to draft. **HEAD**: `49b50a6 feat(bubble): relay injected text to Termux:3133 phone bridge`. **Baseline**: `DEEP_REVIEW_2026-05-12.md` + README `2026-05-12 to 2026-05-13`.

**Agents fired**: 13 in parallel + 3 external-model second opinions + LSP + Firebase MCP + ADB (offline) + build/test verification + manifest + Play Store + patent-claim cross-check + Firestore rules.

**Returned**:
| # | Agent | Status |
|--|------|--------|
| 01 | legacy-analyst | pending |
| 02 | architecture-critic | **CRASHED** (0-byte file; will respawn) |
| 03 | security-auditor | done (28K report) |
| 04 | silent-failure-hunter | done (22K) |
| 05 | type-design-analyzer | done (26K) |
| 06 | code-refactoring:code-reviewer | done (19K) |
| 07 | feature-dev:code-explorer | done (inline; ClarifierController.kt:94 residual found) |
| 08 | play-store-readiness | pending |
| 09 | accessibility-compliance:ui-visual-validator | done (21K) |
| 10 | dev-tools:codex-review | done — thin (sandbox shell-policy blocks; only HEAD commit reviewed) |
| 11 | dev-tools:brains-trust | pending |
| 12 | coderabbit:code-review | **TOOL NOT INSTALLED** (logged) |
| 13 | accessibility-compliance:wcag-audit | done (22K) |

Build verified: `assembleDebug` + `testDebugUnitTest` BUILD SUCCESSFUL, **437 tests, 0 failures, 0 errors** (up from 226 on 5/13). ADB: phone offline — Phase 5 runtime broadcast battery skipped.

---

## State change since 2026-05-13

**56 commits landed since 5/13.** 8+ of the 13 5/13 P1s are fixed in code:

| 5/13 finding | Status | Fix commit |
|---|---|---|
| P0 Clarifier offset misalignment | **FIXED** | `49b575e` (new `ClarifierAlignment.kt`) |
| P0 turboTaxJob ghost loop | **FIXED** | `611ad2a` (child of `currentReconJob`) |
| P0 L4 WakeLock missing | **FIXED** | per 5/13 commit b50d574-class +follow-ups |
| P1 ProfileManager unsync | **FIXED** | `611ad2a` (CopyOnWriteArrayList + ConcurrentHashMap) |
| P1 MultiPassDecoder last-wins | **FIXED** | `60c11a9` (groupBy + min) |
| P1 Hallucination filter "ok/no/you" | **FIXED** | `49b575e` (whitelist) |
| P1 clarifier_sensitivity "off" sticky | **FIXED** | one-time migration shim |
| P1 AudioPlaybackCallback unregister | **FIXED** | unregister at BubbleService.kt:2709 |
| P1 Bluetooth SCO receiver leak | **FIXED** | extracted to BubbleBluetoothSco.kt w/ register+unregister |
| P1 languageCode hardcoded "en" | **FIXED** | `WimApp.appLanguage()` at 3 sites |
| P1 Privacy URL points to lavrentiy | **FIXED** | now points to wim-android privacy_policy.md |
| P2 Anthropic ET extinction | **FIXED** | `60c11a9` (GPT-4o fallback) |
| Dead Moonshine + Gemma engines | **DELETED** | `26cdf00` |
| Dead OnboardingActivity | **DELETED** | `66378e5` |
| BubbleService god-class (4310 LOC) | **PARTIAL** | 4310 → 2715 across 18 extract slices |
| Test coverage | **GREW** | 226 → 437 tests (94% increase) |

**Still open from 5/13 (verified live):**
- `showBubble` `addView` at `BubbleService.kt:1158` lacks try/catch — overlay-revoke race crashes service
- Legacy `BubbleClarifierUI` coexists with FSM `Clarifying` state — touch handler checks both
- `Clarifier.kt:127` allocates `vocabulary.map` per word — ~6000 string allocs per reconstruction
- `BubbleStateBus.forceTo()` + `.reset()` still public escape hatches; called by `setBubbleIdle` unconditionally
- 7+ mutable booleans parallel to FSM (was "6" in 5/13; type-design counted 7+)
- `MainActivity.kt` is now the largest god-class at **3207 LOC** (BubbleService shrunk, MainActivity grew — playbook needs repeating)

---

## NEW findings from this audit

### P0 — User-data-impacting or ship-blocking

1. **Play Store privacy policy + press release have unfilled placeholders** — `play_store/privacy_policy.md` contains `[Date]` and `[Your Support Email Address Placeholder]`; `play_store/press_release_draft.md` has `[CITY, STATE]`, `[Date]`, `[Contact Email]`, `[Website/Linktree]`. Play Store will reject any policy with unresolved placeholders.

2. **Play Store long description names DELETED engines** — `play_store/long_description.md` claims "WiM uses cutting-edge, lightweight local AI models (like Vosk and Moonshine) to process your speech right on your phone." Both engines were deleted in commit `26cdf00`. Live L1 = `WhisperLocalEngine` (whisper-tiny.en via sherpa-onnx). False product claim.

3. **"Offline-first" / "audio not sent to servers" claim conflicts with L2/L3/L4 paths** — `long_description.md`: "Your audio recordings are never sent to a remote server for transcription unless you explicitly opt-in to optional cloud features." Default L1 is local; L2/L3/L4 all hit `api.openai.com` for Whisper + GPT-4o or `api.anthropic.com` for Sonnet. The "explicit opt-in" framing is true for L1 cloud toggle but misleading for L2+ which ship cloud by default.

4. **"10 languages" claim, but code pinned to English at 3 sites** — long_description.md: "WiM currently supports on-device, offline transcription for 10 languages." Per 5/13 README: WiM is "English-only build" with multilingual disabled at 3 client-side call sites (`BubbleService.kt`, `WimApp.kt`, `PhoneticRisk.kt`).

5. **ProfileManager.kt:313-314 + SessionHistory.kt:169-181 direct-write `wim_users/{uid}` denied by Firestore rules** — `bakers-agent` Firestore rules have NO `match /wim_users/{uid}` clause; the default `match /{document=**} { allow read, write: if false }` denies all client-side wim_users access. CF Admin SDK bypasses rules and works, but Android client direct sync listener + session writes are silently denied. Either (a) intentional — these client paths are dead, all sync goes through CF actions, or (b) functional break — profile sync silently failing on every signed-in install. **Verify and remove the dead path OR add the rule.**

6. **`ReconstructClient.kt:261` silently downgrades to raw text on field rename** — `json.optString("clean", rawText)`: any backend change to the response field name returns raw text without surfacing the failure. User sees "reconstruction" that's actually pre-cleaned ASR.

7. **`UserDataClient.kt:79` GDPR delete-data silently reports success on empty body** — compliance-fragile. Treat empty body as failure; surface to user.

8. **`BubbleService.kt:2461` L1-detection thread re-fires forever on network failure** — comment claims fix; code does opposite. After session #5 on a flaky network, the L1-detect HTTP post keeps spawning threads every reconstruction.

9. **`l4DowngradeNotified` permanently silences Sonnet→GPT-4o downgrade after first toast** — `BubbleService.kt:84`. User pays Anthropic for Sonnet ET, gets silently downgraded to GPT-4o after the first ET-extinction event in the process lifetime, never knows again.

10. **`ClarifierController.kt:87` advances cycle on failed `swapWordInLastInjection`** — `next()` runs regardless of return value. User taps approve, swap fails, cycle advances, no edit happened. Patent-claim feature silently broken.

### P1 — Reliability + correctness

1. **`BubbleCommandMode.kt:227,260` doubles the OkHttp leak** — Both backend + direct-OpenAI paths create a fresh `OkHttpClient.Builder().build()` per command transform. The 5/13 P1 about per-call OkHttp client got moved into the slice and doubled instead of fixed. Hoist to `companion object`.

2. **`AudioRecorder.kt:101-119,211-237` fd leak on FileOutputStream + RandomAccessFile** — No try/finally; any throw between `seek(0)` and `close()` (disk full, file deleted underneath) leaks fd per call.

3. **`BubbleService.kt:1301,2470` raw `Thread{}.start()` for fire-and-forget HTTP** — Should be `WimApp.instance.appScope.launch(Dispatchers.IO) { ... }`. Same fire-and-forget semantics, no thread-per-call.

4. **`ClarifierController.kt:94` `applyEdit` uses unpadded coords against padded `lastInjectionText`** — `lastInjectionText = lastInjectedTextSnapshot()` stores the PADDED accessibility-injected string; `applyEdit(lastInjectionText, current.rangeStart, current.rangeEnd, ...)` applies UNPADDED rangeStart/rangeEnd. Field write via `swapWordInLastInjection` is still correct (uses `lastInjectedLeadingPad` at `WimAccessibilityService.kt:425`). **Affects WordHighlightView position after first approve in multi-flag cycle.** Code-explorer agent finding.

5. **Type-design: 7+ booleans parallel to FSM** — `isRecording` (set in 7 sites), `processed`, `isSendReady`, `isRegenInFlight`, `facehuggerActive`, `holdModeActive`, `isWorkMode` in BubbleService. Plus `RecordingStateBus` (3rd source) and `ClarifierController.flagged/cycleIndex` mirroring `BubbleState.Clarifying` (4th source). Collapse to FSM reads.

6. **Primitive obsession** — `tone: String`, `situation: String`, `layer: Int`, `bubbleTheme: String`, `riskFlags: List<String>`, `AudienceContext.Profile.category: String`. Closed vocabulary re-declared at 3-6 callsites with silent `?:` fallthrough. `layer = 7` runs through `>= 2` and `>= 4` branches with no guidance.

7. **`Clarifier.WordConfidence` zero invariants** — negative ranges, positive logprobs, out-of-bound probabilities all accepted. `FlaggedWord` empty candidates silently no-ops approve; degenerate range causes text-doubling.

8. **CLAUDE.md drift** — Lines 23, 37, 76 still reference Moonshine as L1 ASR; actually `WhisperLocalEngine` (whisper-tiny.en) since `26cdf00`. Line 23 lists Moonshine + Vosk + Gemma as "dormant on disk"; all 3 are deleted now.

9. **`build.gradle.kts` orphan dep** — `com.google.mediapipe:tasks-genai:0.10.24` (Gemma local L2/L3) still listed; codebase deleted Gemma in `26cdf00`. APK bloat ~MB.

10. **`ProfileManager.kt:245,256,267` silent empty on parse error** — `trigger_words`, `onset_weights`, `covert_profile` all `catch (_: Exception) { emptyList()/emptyMap() }`. Clinical L4 differentiator silently vanishes on one bad write.

11. **`PredictionEngine.kt:136` silent wipe on parse error** — Per-app sentence-boundary history vanishes on bad blob. Claim-14 "your bubble learned how you talk in Slack" goes to zero. Same template as ProfileManager fix.

12. **`ProfileManager.kt:361-369, 378-386` union sync silently wipes local on cast failure** — Firestore listener `@Suppress("UNCHECKED_CAST")` to `List<String>`; type drift returns empty, union(empty) preserves local but the surrounding logic in mergeRemoteProfile sometimes overwrites — verify.

13. **`WimTileService.kt:29` QS tile silently no-ops** — `startForegroundService` throws on Android 12+ if foreground-service-start-from-background rules block; caught silently. Toast on failure.

14. **OkHttp Response leak — 8 sites** — None of `WhisperClient.kt:166-167`, `ReconstructClient.kt:250-251`, `:703-704`, `:755-756`, `UserDataClient.kt:48-49`, `:78-79`, `BubbleCommandMode.kt:241-242`, `:276-277` use `response.use { }`. Body read happens outside the response close, may leak on body-read exception.

15. **`AudioRecord.startRecording` return ignored** — Possible silent recording start failure.

16. **`HotwordListener` tight-loops on permission revocation** — Permission revoked → SpeechRecognizer error → `start()` re-armed → loops.

17. **No `onTimeout` override for Android 14+ FGS mic time-cap** — `BubbleService` ships FOREGROUND_SERVICE_TYPE_MICROPHONE without overriding `Service.onTimeout(serviceId)`. Android 14 will silently kill the FGS at the time cap.

18. **`ResultListener` silently null on rotation** — `BubbleService.kt:1641-1645, 1657-1661`: `whisperLocalEngine?.start(object : ResultListener { ... })` no-ops if `whisperLocalEngine` is null due to service recreation. User holds bubble, sees recording UI, no result ever fires.

19. **`AuthManager.getIdToken` silently null routes paid backend users to bundled-key direct path** — `ReconstructClient.kt:108` sees `idToken == null` → `reconstructDirect` → consumes the bundled OpenAI/Anthropic key budget instead of the user's paid tier.

20. **`dispatchTapAt` returns before gesture fires** — `WimAccessibilityService.kt:611` returns dispatch-submitted boolean synchronously; gesture completion is async. Auto-send toast "Sent" lies if gesture is cancelled post-return.

21. **`onAccessibilityEvent` doesn't recycle `event.source`** — Node pool exhaustion possible under sustained event load. Same in `findEditableFocusedDescendant`.

22. **`ProfileManager.saveProfile` passes nullable maps to `JSONObject(Map<*,*>)` constructor** — `corrections as Map<*, *>` with null values throws.

### WCAG findings (`13-wcag-audit.md` for full citations)

- **2.5.5 target size**: 9 violations (bubble menu pills 22-26dp; bouncer rows 34dp; profile chips 36-40dp)
- **1.4.3 contrast** (light mode only): 6 violations — worst: Aluminum IDLE label 2.8:1 (need 4.5:1)
- **4.1.2 name/role/value**: 8 violations — **clarifier split-bezel left/right tap entirely invisible to TalkBack** (the patent-claim differentiator)
- **2.4.7 focus visible**: 6 violations — no `state_focused` defined in any drawable selector; keyboard/D-pad traversal invisible
- **2.3.3 motion**: 4 violations — `BubblePulseRings`, `BubbleAffect.breathe`, `ShimmerFx` all infinite ValueAnimators with no `AccessibilityManager.areAnimatorsEnabled()` check
- **3.3.2 labels**: 4 violations — profile EditTexts hint-only; bouncer `qWord1-3` indistinguishable
- **2.1.1 keyboard**: bubble touch handler has no AccessibilityAction equivalent for tap / double-tap / clarifier-half

### Architectural debt (carried)

- **Extract `RecordingSession.kt`** — 5/13 architecture-critic identified as single highest-leverage move. Collapses 4 of the parallel-source-of-truth bugs. NOT yet done.
- **Delete `forceTo()` + `BubbleStateBus.reset()`** — replace with `@VisibleForTesting` legal-chain walker. Still corrosive.
- **Slice `MainActivity.kt` (3207 LOC)** — repeat the BubbleService extract-and-pin playbook.
- **Delete legacy `BubbleClarifierUI`** — FSM Clarifying is authoritative; legacy still coexists.

### Doc + dependency hygiene

- 5 TODOs in source (`L1PackHelper` v2, `OnboardingNav` stub) — fine, but mark deadlines
- 47 empty catch blocks (`catch (_: Exception) {}`) — many lifecycle paths; collectively hide BubbleService onDestroy crashes
- `build.gradle.kts:tasks-genai:0.10.24` dep — orphan
- CLAUDE.md: Moonshine/Vosk/Gemma references stale, BubbleService LOC stale, L1 engine name stale

### Audit infrastructure observations

- **architecture-critic agent crashed mid-run** — 0-byte file after 25+ min idle. Re-fire on synthesis pass.
- **CodeRabbit CLI not installed** — single-line npm or binary install + `coderabbit auth login` to enable. Re-fire after.
- **Codex review is single-issue addendum, not whole-app pass** — sandbox shell-policy blocked exploration. Re-run with pre-staged diff bundle (`codex review --commit <SHA>` per commit) or stdin-piped patch.
- **Play Store + brains-trust + legacy-analyst still in flight at synthesis time** — append on completion.

---

## Recommended fix order

**Tier 1 — Play Store unblocking (~half day)**
1. Replace all `[placeholder]` tokens in `play_store/*.md`
2. Rewrite `long_description.md` + `privacy_policy.md` — remove Vosk/Moonshine, accurately describe what data leaves device, drop "10 languages" until multilingual re-enabled
3. Take 8 screenshots + create 1024×500 feature graphic
4. Bump `versionName` 0.2.0 → 1.0.0

**Tier 2 — Functional bleeders (~1 day)**
1. Firestore rules: add `/wim_users/{uid}` rule with `request.auth.uid == uid` OR delete `ProfileManager.kt:313-314` + `SessionHistory.kt:169-181` direct-client paths (CF-only flow)
2. `ReconstructClient.kt:261` → throw on missing `clean` field instead of silent fallback
3. `BubbleService.kt:1158` `showBubble.addView` → try/catch wrapper
4. `ClarifierController.kt:87` → don't advance cycle on swap fail
5. `ClarifierController.kt:94` → use `lastInjectedLeadingPad` when applying edit to `lastInjectionText`
6. `BubbleService.kt:2461` → fix L1 detection thread re-arm logic; don't spawn after Nth failure
7. `BubbleService.kt:84` → re-enable downgrade toast per-session not per-process-lifetime
8. `UserDataClient.kt:79` → require valid body for GDPR delete success

**Tier 3 — Production hardening (~2 days)**
1. `BubbleCommandMode.kt:227,260` → companion object OkHttpClient
2. `BubbleService.kt:1301,2470` → replace `Thread{}.start()` with `appScope.launch`
3. `AudioRecorder.kt:101-119,211-237` → wrap `FileOutputStream`/`RandomAccessFile` in `.use{}`
4. 8 OkHttp client sites → `response.use { }` body extraction
5. Add `onTimeout(serviceId)` override for Android 14+ FGS time-cap
6. Recycle `AccessibilityNodeInfo` in `findEditableFocusedDescendant` + `onAccessibilityEvent`
7. `WimTileService.kt:29` toast + Log.w on FGS-not-allowed
8. Override `HotwordListener` permission-denied path to disable pref + bail

**Tier 4 — Architecture cleanup (~3 days)**
1. Extract `RecordingSession.kt` — single highest-leverage refactor; collapses 4 race-bug families
2. Delete `forceTo()` + `BubbleStateBus.reset()` (replace with legal-chain walker)
3. Collapse 7+ booleans parallel to FSM into FSM reads
4. Type-design: enum `Tone`, enum `Situation`, sealed `Layer`; add invariants to `WordConfidence` + `FlaggedWord`
5. Slice `MainActivity.kt` (3207 LOC) using BubbleService playbook
6. Delete legacy `BubbleClarifierUI`

**Tier 5 — WCAG + accessibility (~2 days)**
1. Bubble clarifier TalkBack: add `AccessibilityAction` for left/right half tap; make split-bezel announcing
2. `areAnimatorsEnabled()` check before `BubbleAffect.breathe`, `ShimmerFx`, `BubblePulseRings`
3. Light-mode contrast: bump `text_secondary`, `text_dim`, `copper_gold`, Aluminum IDLE label
4. Touch targets: bump bubble menu pills + bouncer rows + profile chips to 48dp
5. Profile EditText: add `android:labelFor` or explicit TextView labels
6. Add `state_focused` selector items to `card_bg_pressable`, `card_bg`, `nameplate_bg`

**Tier 6 — Doc / dep hygiene (~half day)**
1. CLAUDE.md: update L1 engine, BubbleService LOC, drop Moonshine/Vosk/Gemma references
2. `build.gradle.kts`: remove `com.google.mediapipe:tasks-genai:0.10.24`
3. 47 empty catch blocks → at minimum `Log.v(TAG, "...: ${e.javaClass.simpleName}")`
4. Re-run brand-guard hook for any remaining "stutter" instances in user-facing text
5. Delete `.r8-displaced-sherpa-wrappers/` if no longer needed for the release build path

---

## Quantitative deltas vs 5/13

| Metric | 5/13 | 5/29 | Δ |
|---|---|---|---|
| BubbleService.kt LOC | 4310 | 2715 | -1595 (-37%) |
| Total unit tests | 226 | 437 | +211 (+93%) |
| Test failures | 0 | 0 | — |
| Open P1s (from 5/13 set) | 13 | 5 | -8 |
| New code smells | n/a | 32+ | new audit baseline |
| CF env model L4 | (unstated) | `WIM_MODEL_L4=gpt-4o` | confirms Sonnet device-direct path |
| Commits since baseline | — | 56 | extract-and-pin spree + phone bridge |

**Direction**: heavy refactoring + test-pinning + dead-code purge has paid down most of the 5/13 P1 list. New audit baseline is 5x larger (more agents, more rigor); ~half the new findings are issues the 5/13 sweep didn't cover (WCAG depth, type-design invariants, Firestore rules functional break, Play Store copy/code drift).

---

*Reports: `reports/AUDIT_2026-05-29/*.md`. This synthesis dropped security/privacy/confidentiality findings per durable rule; those live unedited in the agent reports.*
