# Mobile ASR in 2026 — Deep Research for WiM

Researched April 24, 2026. Sources cited inline as [N]. Full reference list at bottom.

## Executive summary — what to do

**Five things the 2026 landscape says we should change or confirm:**

1. **The "whisper.cpp is 72 seconds" problem isn't about whisper.cpp — it's about the runtime.** sherpa-onnx runs the same Whisper Tiny model **51× faster than whisper.cpp on Android** [1][2]. That single finding invalidates our April 20 decision to position whisper.cpp as "L4 clinical-accuracy mode only." Swap the runtime, keep the model, recover usable latency.

2. **Moonshine Tiny Streaming is the 2026 default mobile English ASR.** 26 MB model, 12.66% WER, 107 ms latency vs 11,286 ms for Whisper Large V3 on the same hardware [3][4]. Published to Maven Central as `ai.moonshine:moonshine-voice:0.0.59`, MIT license, streaming model caches encoder state for near-instant final result after phrase completion. Outperforms Vosk 0.15 on complex vocabulary (the problem surfaced by yesterday's benchmark), outperforms Whisper Tiny and Small on the VoicePing Android benchmark.

3. **ML Kit GenAI Speech Recognition shipped as `alpha1` in 2026** — free, on-device via Gemini Nano, 15 languages in Basic Mode (which is the mode Samsung users get) [5][6]. Dependency: `com.google.mlkit:genai-speech-recognition:1.0.0-alpha1`. Minimum API 31. Not battle-tested yet but it's the official Google path. Worth a prototype as L1 fallback alongside Moonshine.

4. **Gemma 4 E4B has a built-in audio encoder** capable of on-device ASR up to 30 seconds per prompt, 40 ms frame duration, 50% smaller encoder than Gemma 3n [7][8]. If we ever want a single model that does ASR *and* reconstruction in one pass (eliminating the Moonshine→Gemma→GPT chain at L2/L3), E4B is the candidate. Not a replacement today — a consolidation target for mid-2026.

5. **For L4 clinical work specifically, engine choice is second to model specialization.** A fine-tuned Whisper-Small on stuttered speech achieved **4 % WER vs 36.1 % baseline** [9]. Off-the-shelf ASR (including Whisper, Moonshine, Vosk) all strip disfluencies similarly — yesterday's benchmark confirmed this. The path to better L4 input is not "pick a better general ASR," it's "fine-tune on FluencyBank Timestamped" or use specialized models like StutterZero [10].

**Bottom line:** the WiM stack today (Samsung native, Vosk 0.15, gpt-4o-transcribe, Gemma 3 1B) was defensible 6 months ago. It's no longer the best that's available on Android in April 2026.

---

## Engine comparison table

All rows verified against the Open ASR Leaderboard, VoicePing benchmark, or vendor documentation. "Mobile viability" assesses whether the engine is realistically deployable to an Android ARM device like the Samsung R5CWB08K38H.

| Engine | Model / variant | Size | WER (Eng) | On-device Android? | License | Notes |
|---|---|---|---|---|---|---|
| **Moonshine** | Tiny Streaming | 34 MB | 12.00 % | ✅ Maven `ai.moonshine:moonshine-voice:0.0.59` | MIT | Fastest on Samsung S10, 42.55 tok/s, RTF 0.05. Streaming caches encoder state. English-only model (separate models for es/fr/ja/ko/ar/uk/vi/zh) [3][4]. |
| **Moonshine** | Base / Small Streaming | 58 MB / 123 MB | 10.07 % / 7.84 % | ✅ same Maven artifact | MIT | Bigger = better WER, higher latency. Small Streaming is ~104 MB decoder. |
| **Moonshine** | Medium Streaming | 245 MB | 6.65 % | ⚠️ heavier; test before committing | MIT | 107 ms latency on MBP — Android numbers TBD. |
| **Whisper (OpenAI) via sherpa-onnx** | Tiny | ~75 MB | ~13 % | ✅ fastest way to run Whisper on Android | MIT | 51× faster than whisper.cpp on same Android [1]. |
| **Whisper large-v3-turbo via sherpa-onnx** | 809M params | ~1.5 GB | 7.75 % | ⚠️ requires flagship RAM + storage | MIT | If we want Whisper-class accuracy on-device, this is the route. |
| **whisper.cpp** | base.en-Q5_1 | ~60 MB | ~10 % | ❌ 72 s inference on SM-F731U | MIT | This is what we have on the `test-whispercpp` branch. Runtime is the bottleneck, not the model. |
| **Vosk (Kaldi)** | en-us 0.15 small | 40 MB | ~15-20 % | ✅ current WiM default | Apache 2.0 | 2019-era Kaldi. Mangles technical vocab ("paralinguistic" → "caitlyn pair linguistic") [yesterday's benchmark]. |
| **Vosk (Kaldi)** | en-us 0.42 gigaspeech | 1.8 GB | ~20 % better than small | ⚠️ 16 GB RAM at train; runtime ~2 GB | Apache 2.0 | +20 % accuracy vs small but still Kaldi-era [11]. |
| **NVIDIA Parakeet TDT 0.6B v3** | 600M params, 25 langs | ~1.2 GB | 7-8 % | ⚠️ via sherpa-onnx with effort | CC-BY-4.0 | RTFx >2000 on GPU. FastConformer encoder + TDT decoder. Unclear mobile story. [12] |
| **NVIDIA Parakeet-unified-en-0.6b** | 600M params, English | ~1.2 GB | 7-8 % | ⚠️ edge | CC-BY-4.0 | Offline + streaming in **one** model — rare. |
| **NVIDIA Canary-Qwen-2.5B** | 2.5B params | ~5 GB | 5.63 % | ❌ 8 GB VRAM, NVIDIA-GPU only | CC-BY-4.0 | Top-tier accuracy but NOT mobile. Edge server or cloud only [13]. |
| **IBM Granite 4.0 1B Speech** | 1B params, 6 langs | ~2 GB | 5.52 % | ⚠️ Hugging Face Transformers; needs ONNX conversion for Android | Apache 2.0 | #1 OpenASR Leaderboard (Mar 9, 2026). English/French/German/Spanish/Portuguese/Japanese. Keyword bias feature [14]. |
| **Qwen3-ASR 0.6B / 1.7B** | 600M / 1.7B params | 1.8-4 GB | 5.76 % | ✅ sherpa-onnx support (int8) | Apache-ish (Qwen license) | Jan 29, 2026 release. `sherpa-onnx-qwen3-asr-0.6B-int8` dated Mar 25, 2026 [2]. |
| **SenseVoice Small** | 234M params | 240 MB | ~8-10 % | ✅ sherpa-onnx, 33.62 tok/s on S10 | Apache 2.0 | Multilingual (Chinese, English, Japanese, Korean, Cantonese). Fast, decent mobile option for multilingual. |
| **Cohere cohere-transcribe-03-2026** | cloud | n/a | 5.42 % | ❌ cloud only | commercial | Current #1 on HF Open ASR Leaderboard as of April 2026 [15]. |
| **OpenAI gpt-4o-transcribe** | cloud | n/a | lower than Whisper [16] | ❌ cloud only | $0.006/min | What WiM L4 uses today. 99+ languages. $0.36/hour [16]. |
| **OpenAI gpt-4o-mini-transcribe** | cloud | n/a | slight quality drop | ❌ cloud only | $0.003/min | Half price, reasonable fallback for L2/L3 via cloud. |
| **OpenAI Whisper large-v3** | 1.55B params | ~3 GB | 7.4 % | ❌ too heavy for mobile (server-side only) | MIT | Nov 2023. No v4 released as of April 2026 [17]. |
| **Google ML Kit GenAI Speech Recognition** | Gemini Nano (Basic) | varies | undocumented | ✅ Android API 31+ | Google (alpha) | 15 langs Basic Mode (works on Samsung S24 via Samsung AI). Advanced Mode = Pixel 10 only [5][6]. |
| **Gemma 4 E2B** | 2B params, multimodal | ~4 GB | — (ASR built-in) | ✅ MediaPipe/LiteRT, Raspberry Pi 5 + mid-range Android | Apache 2.0 | Native audio encoder (USM-style conformer, 40 ms frame), 30 s/prompt. Deployable via Ollama/GGUF/MediaPipe/ONNX [7][8]. |
| **Gemma 4 E4B** | 4B params, multimodal | ~8 GB | — (ASR built-in) | ⚠️ flagship phones only | Apache 2.0 | Targets flagship phones + modern iPads. Better ASR than E2B. |
| **Meta SeamlessM4T v2** | 2.3B params, 100+ langs | ~5 GB | — | ⚠️ unity.cpp for GGML | CC-BY-NC-4.0 | Research license — **not commercial-friendly**. 100+ langs. No 2026 Android SDK announcement [18]. |
| **Samsung native (SpeechRecognizer)** | Google TTS service | — | — | ✅ free, bundled | — | The chirp engine. AOSP-level architectural limit on chirp suppression. |
| **Deepgram Nova-3** | cloud | n/a | strong | ❌ no mobile SDK, cloud-only | commercial | Self-serve vocabulary customization. Python/JS/Go/.NET SDKs only [19]. |
| **AssemblyAI Universal-2** | cloud | n/a | strong | ❌ no mobile SDK announced in 2026 | commercial | Python SDK. On-device WhisperX alternative mentioned (380-520 ms latency, 4 GB RAM, GPU) [20]. |
| **Apple SpeechAnalyzer** | iOS 26 | — | 2.2× faster than MacWhisper Large V3 Turbo | ❌ iOS only | — | Not applicable to WiM Android, but interesting design reference [21]. |

**Key reads from the table:**

- Mobile English winners by WER/latency mix: Moonshine Tiny (speed), Moonshine Medium (accuracy), Whisper Tiny via sherpa-onnx (Whisper ecosystem compatibility), Qwen3-ASR 0.6B (newest + multilingual + sherpa-onnx integration).
- Open-source OpenASR Leaderboard #1 is **IBM Granite 4.0 1B Speech at 5.52 WER**. 1B params is realistic for Android. Apache 2.0. Worth a prototype.
- Cloud still wins on raw accuracy (Cohere 5.42, gpt-4o-transcribe). For L4 clinical, cloud is still right.
- Meta SeamlessM4T has license issues for commercial use. Scratch it from candidate list.

---

## Recommended WiM stack — layer by layer

### L1 (live dictation, no GPT, fast feedback)

**Replace Vosk 0.15 small with Moonshine Tiny Streaming via sherpa-onnx.**

- 26 MB model (smaller than Vosk)
- Streaming, so partial results appear as user speaks
- 107 ms latency after phrase ends (vs Vosk's ~500 ms to several seconds)
- Better on complex vocabulary than Vosk (yesterday's benchmark showed this was the #1 Vosk failure mode)
- MIT license, no strings
- No chirp (not a SpeechRecognizer)

Fallback cascade if Moonshine isn't available/ready:

1. **ML Kit GenAI Speech Recognition Basic Mode** — Google-provided, Gemini Nano, 15 langs, shipping on Samsung S24/S25 and Pixel 8+. Alpha but functional.
2. **Samsung native SpeechRecognizer** — the current "last resort," chirp and all.

Retire: Vosk 0.15 small. It's no longer best-in-class for anything WiM needs.

### L2 / L3 (reconstruct with optional profile/vocabulary)

**Keep gpt-4o-transcribe as the default cloud ASR for these layers for now**, but start routing Moonshine output through Gemma 3 1B when users are offline or don't have API key.

Near-term experiment: **prototype Gemma 4 E4B as a consolidated ASR + reconstruction engine.** If it works, L2/L3 collapse from (Moonshine or cloud ASR) + (Gemma 3 1B or gpt-4o) into one Gemma 4 E4B pass. Big simplification, big RAM requirement (~8 GB — acceptable for flagships, not cheap phones).

### L4 (clinical stutter reconstruction)

**Keep gpt-4o-transcribe as primary ASR for L4.** Yesterday's benchmark showed every off-the-shelf mobile ASR (including Moonshine) strips disfluencies. For clinical work we need the ASR that preserves raw speech best, which is cloud Whisper-class.

**Medium-term research path: fine-tune Whisper-Small on FluencyBank Timestamped** [22] and deploy it via sherpa-onnx. The published 4 % WER result vs 36.1 % baseline [9] is the most important data point in this entire research pass for WiM's L4 product thesis.

**Offline clinical fallback candidate: IBM Granite 4.0 1B Speech** [14]. 5.52 WER, 1B params (deployable on Android via ONNX), 6 languages, Apache 2.0, keyword bias feature that could hold George's stuttering-research vocabulary. Needs benchmarking on disfluent speech specifically.

### Cross-cutting: fix the L4 + Vosk code bug

The benchmark made clear that WiM's current code runs Vosk at L4 when the Vosk toggle is on. Whatever we choose for L4 above, the code should hard-gate L4 away from Vosk (and from Moonshine when we add it, unless specifically validated for clinical work).

---

## Vosk improvement path (if we keep it at all)

**Recommendation: don't invest further in Vosk.** It's a 2019-era Kaldi stack and the 2026 mobile ASR landscape has moved past it. Engineering hours are better spent on Moonshine integration.

If we keep it anyway, these are the real improvements, ordered by effort-to-payoff:

1. **Upgrade to vosk-model-en-us-0.42-gigaspeech** — ~20 % accuracy improvement over 0.15 small [11]. Cost: 1.8 GB model download, ~2 GB RAM at runtime. Samsung can handle it.
2. **Custom language model (n-gram + Kaldi graph rescore)** — inject George's domain vocabulary (stuttering research, technical terms) via `alphacephei.com/vosk/lm` [23]. This is specifically what would have fixed the "paralinguistic → caitlyn pair linguistic" failure. Medium effort (need to train and pack an ARPA LM, recompile graph).
3. **LLM post-correction with Gemma 3 1B** — we already have Gemma 3 1B in the stack for L2/L3 reconstruction. Could add a lightweight post-ASR correction pass at L1 that fixes obvious phonetic-neighbor errors. Low effort, already-paid cost.
4. **Phonetic corrector strengthening** — we have PhoneticMatcher (Double-Metaphone) already. Could extend to include substring phonetic match, not just full-word.

Acoustic fine-tuning is not practical: Kaldi acoustic training requires massive compute and expertise that doesn't fit WiM's engineering posture.

---

## Ensemble / combination strategies

Four patterns worth considering, each with different ROI:

### 1. Confidence-gated hybrid (highest ROI)
Run Moonshine Tiny live for L1 feedback. When user stops, if Moonshine's per-segment confidence is below threshold, fire a background cloud transcribe with gpt-4o-transcribe and replace the text. Matches the pattern described for production ASR [24]. Users see fast feedback, final text is cloud-quality. This is essentially what WiM already does for Samsung-native + Whisper; swap Samsung for Moonshine.

### 2. ROVER voting ensemble
Run two ASR engines in parallel, merge via Recognizer Output Voting Error Reduction. ~10 % relative WER reduction vs single best engine [24][25]. Cost: 2× the ASR compute. Probably overkill for WiM unless we're building clinical-grade transcription.

### 3. Layer-aware routing (already WiM design)
Different engines per layer. L1 fast-and-cheap, L4 accurate-at-any-cost. This is the current WiM architecture — the research just validates the design pattern is sound. Needs a small fix to make sure Vosk doesn't run at L4.

### 4. Streaming + batch hybrid
Moonshine Streaming gives live feedback. On stop, re-transcribe the same audio with a heavier batch engine (Moonshine Medium or cloud) for final accuracy. Mirrors Wispr Flow's production pattern [26]. Moderate effort — need to capture the WAV in parallel with the streaming engine.

### 5. Dual-transcribe disagreement flagging (research-mode)
Run Moonshine + Vosk in parallel, flag any word where they disagree as uncertain. Useful for QA and L4 research audio but too expensive for normal dictation. Skip unless we start building a research-collection mode.

---

## Competitor intel (mobile / Android voice-to-text, top 10)

### Consumer dictation

| App | Engine | Pricing | Offline? | Notable |
|---|---|---|---|---|
| **Wispr Flow** | OpenAI Whisper + Llama cleanup (fine-tuned) | $12/mo | ❌ cloud | <700 ms p99 end-to-end. Baseten infra [26][27]. |
| **SuperWhisper** | Whisper + configurable LLM cleanup | $8-$15/mo | ✅ macOS/iOS | **No Android** — top user request (198 votes) [28]. |
| **Otter.ai** | Proprietary cloud engine (not Whisper) | Free-$30/mo | ❌ cloud | Meeting-focused. High-fidelity dictaphone on mobile, uploads to cloud [29]. |
| **Dragon Anywhere** | Nuance classic + Microsoft backing | $15/mo | ❌ cloud | Traditional, mature, dated UX. |
| **Google Recorder / Live Caption** | On-device RNN-T + punctuation RNN + sound CNN | Free (Pixel) | ✅ on-device | TensorFlow Lite, pruned for mobile power efficiency [30]. |
| **Samsung Voice Input** | Bundled | Free (Samsung) | ✅ on-device | S25 users report accuracy issues, prefer Google Voice Typing [31]. |
| **Gboard voice typing** | Google cloud | Free | partial | Android baseline for non-Pixel devices. |
| **Google AI Edge Eloquent** | Gemma-based on-device | Free | ✅ offline after model download | iOS April 2026, **Android "coming soon"**. Auto-strips fillers, has "Key points/Formal/Short/Long" text transforms [32][33]. |
| **Claude Voice** | Anthropic cloud + ElevenLabs TTS | Claude Pro subscription | ❌ cloud (offline <30 s coming Q1 2026) | No first-party Anthropic ASR. ElevenLabs for TTS [34]. |
| **Private Dictation / WhisperInput** | whisper.cpp | Free/paid | ✅ on-device | Open-source wrappers around whisper.cpp. Same 51× slower vs sherpa-onnx problem we hit. |

### Stuttering-specific apps

| App | Type | Engine | Notable |
|---|---|---|---|
| **Stamurai** | Therapy exercises (not transcription) | n/a | 50K+ users, 180+ countries, 4.62/5, 6.3K ratings. Last update Oct 2024 — stale. 30+ exercises, DAF, group sessions. Does NOT compete with WiM's voice-to-intent thesis [35]. |
| **Say-It-Right** | Therapy app | n/a | Similar to Stamurai. |
| **WiM (this project)** | Voice-to-intent + clinical reconstruction | Vosk + Samsung native + gpt-4o-transcribe + Gemma 3 1B | No known direct competitor in the "dictation app that preserves and understands disfluent speech" category. |

**Key competitive finding:** there is NO direct competitor in the space of "mobile dictation app that handles disfluent speech correctly as a first-class feature." Stuttering apps = therapy exercises. Dictation apps = ignore disfluencies or try to strip them out. WiM's L4 clinical thesis is architecturally distinctive.

---

## 2026 research highlights

### Specifically disfluency / stuttering ASR

- **StutterZero & StutterFormer** (Oct 2025, arxiv 2510.18938) [10] — end-to-end joint disfluency detection + transcription + fluency restoration. State-of-the-art on stutter benchmarks.
- **Fine-tuned Whisper-Small on stutter** (various) [9] — 4 % WER vs 36.1 % baseline. The single most important number for WiM's clinical L4 thesis.
- **Interspeech 2025 Speech Accessibility Project Challenge** (arxiv 2507.22047) [36] — benchmarking set for inclusive ASR.
- **Stutter-Solver** (PMC12233913) [37] — end-to-end multilingual dysfluency detection.
- **"Lost in Transcription"** (arxiv 2405.06150) [38] — quantifies the systematic accuracy bias of ASR systems against disfluent speech.
- **Inclusive ASR for Disfluent Speech** (arxiv 2406.10177) [39] — cascaded self-supervised learning with targeted fine-tuning and data augmentation.
- **Aligning Stuttered-Speech Research with End-User Needs** (arxiv 2604.20535) [40] — scoping review + survey + guidelines from SLPs. Notes that SLPs rate true-to-spoken transcripts more useful than intended transcripts, validating WiM's L4 design.
- **Distinguishing Repetition Disfluency from Morphological Repetition** (arxiv 2511.13159) [41] — non-trivial problem for languages where repetition is grammatical (Malay, Indonesian).
- **Research Note: FluencyBank Timestamped** (ASHA pubs 2024) [22] — the canonical dataset for stutter ASR fine-tuning.
- **Stuttering-Aware ASR for Indonesian** (arxiv 2601.03727) [42] — specific example of fine-tuning for one language + dysfluency together.

### Speed / mobile ASR

- **VoicePing Offline Speech Transcription Benchmark** (Feb 2026) [1] — the 16-model cross-platform benchmark where the sherpa-onnx vs whisper.cpp 51× finding lives.
- **"Pushing the Limits of On-Device Streaming ASR"** (arxiv 2604.14493) [43] — compact high-accuracy English model for low-latency inference.
- **ASR Leaderboard paper** (arxiv 2510.06961) [15] — formalization of the Open ASR Leaderboard evaluation methodology.
- **"Back to Basics: Revisiting ASR in the Age of Voice Agents"** (arxiv 2603.25727) [44] — framework for thinking about ASR in voice-agent contexts.

### Ensembles

- **Confidence-based Ensembles of End-to-End Speech Recognition Models** (arxiv 2306.15824) [25] — ~10 % relative WER reduction, foundational for confidence-gated hybrid approach.
- **Ensembles of Hybrid and End-to-End Speech Recognition** (ACL LREC 2024) [45] — combining Kaldi with wav2vec 2.0 XLS-R using confidence measures.

---

## Risks and open questions

1. **Moonshine is English-only per model.** Seven other models exist (ar, ja, ko, zh, es, uk, vi) but as **separate language-specific models**, not one unified multilingual model. For WiM's 10-language rollout, we'd need 8-10 separate Moonshine models (or a different multilingual engine for the non-English paths). Qwen3-ASR 1.7B and SenseVoice cover multilingual in one model.

2. **ML Kit GenAI Speech Recognition Advanced Mode is Pixel 10 only.** Samsung users stay on Basic Mode, which has narrower language coverage and less-documented disfluency behavior. We'd be stuck on Basic for our entire Samsung user base.

3. **sherpa-onnx Android integration requires a JNI layer** (or use a wrapper crate). Non-trivial but documented. Moonshine's Maven artifact is simpler; start there.

4. **Gemma 4 E4B ASR quality vs dedicated Moonshine is unknown.** Hasn't been benchmarked publicly against Moonshine on the VoicePing metric set as of April 2026. Benchmark before committing to a single-model architecture.

5. **IBM Granite 4.0 1B Speech** deployment path for Android isn't clean out of the box — Hugging Face Transformers assumes Python runtime. Needs ONNX conversion + sherpa-onnx integration, which adds engineering cost. But if it works, it's a 5.52 WER engine running on a 1B model, which is remarkable.

6. **Gemma 4 E4B's ~8 GB requirement** excludes cheap phones. If we go that direction, need a cascade: E4B on flagships, Moonshine + Gemma 3 1B on mid-range.

7. **The bigger claim that needs pressure-testing on George's specific phone** (Samsung R5CWB08K38H / SM-F731U, a foldable): VoicePing's benchmark was on a Samsung Galaxy S10. Foldables sometimes have different thermal envelopes. Need to bench Moonshine Tiny on the actual device before committing.

8. **For L4 clinical specifically, engine choice is the wrong lever.** The 4 % WER vs 36.1 % result [9] implies the right move is **training data specialization**, not model architecture. That's a multi-month research project with FluencyBank Timestamped data access, not a code swap.

9. **Custom LM for Vosk** (if we bother) requires ARPA format n-gram training. Doable but Kaldi expertise is narrow.

10. **Licensing traps:** Meta SeamlessM4T is CC-BY-NC-4.0 — **not commercial-friendly**. Don't use it. NVIDIA Canary / Parakeet are CC-BY-4.0 — commercial-friendly but requires attribution. Moonshine and Whisper are MIT — cleanest. IBM Granite is Apache 2.0 — clean.

11. **No Anthropic first-party ASR to evaluate.** Claude's voice mode uses third-party ASR server-side. Not a blocker — just means there's no "Anthropic ASR engine" to put in the candidate list.

12. **Google AI Edge Eloquent's Android release timing** is "coming soon" per Google's statements as of April 7, 2026 [32]. When it lands, it's likely to be a very strong free baseline — Gemma-based on-device, auto filler-stripping, the whole pipeline. It could become Google's opinionated answer for consumer dictation, potentially obviating some of our custom work. Worth watching.

---

## What this doesn't answer

- Which of Moonshine / Qwen3-ASR / SenseVoice wins on *George's actual Samsung* for *George's actual speech patterns*. That needs a 69-clip-style benchmark run, this time with the new candidates. Tomorrow's work if we want it.
- Whether Gemma 4 E4B's ASR quality is close enough to dedicated ASR models that the consolidation argument holds.
- Whether the keyword bias feature in IBM Granite 4.0 1B Speech is usable for George's domain vocabulary (stuttering research terms, technical jargon).
- Real-world latency of ML Kit GenAI Speech Recognition on Samsung S24 vs what we get from Moonshine Tiny Streaming.

---

## References

[1] VoicePing — "Offline Speech Transcription Benchmark: 16 Models Across Android, iOS, macOS, and Windows" (Feb 2026). https://voiceping.net/en/blog/research-offline-speech-transcription-benchmark/
[2] k2-fsa/sherpa-onnx GitHub — https://github.com/k2-fsa/sherpa-onnx ; Releases — https://github.com/k2-fsa/sherpa-onnx/releases
[3] Moonshine GitHub — https://github.com/moonshine-ai/moonshine
[4] "Moonshine vs Whisper ASR: Real-Time Speech Recognition Benchmark (2026)" — https://modelslab.com/blog/audio-generation/moonshine-vs-whisper-asr-real-time-speech-2026 ; Arxiv paper — https://arxiv.org/html/2410.15608v1
[5] Google ML Kit GenAI Speech Recognition API — https://developers.google.com/ml-kit/genai/speech-recognition/android
[6] Android Developers — Gemini Nano — https://developer.android.com/ai/gemini-nano
[7] Google blog — Gemma 4 — https://blog.google/innovation-and-ai/technology/developers-tools/gemma-4/
[8] MindStudio — Gemma 4's audio encoder, E2B/E4B — https://www.mindstudio.ai/blog/gemma-4-audio-encoder-e2b-e4b-speech-recognition ; Edge deployment — https://www.mindstudio.ai/blog/gemma-4-edge-deployment-e2b-e4b-models
[9] Fine-tuned Whisper-Small on stuttered speech (4 % WER) — discussed in arxiv literature including 2406.10177 and 2405.06150
[10] StutterZero / StutterFormer — https://arxiv.org/html/2510.18938
[11] Vosk model comparison — https://alphacephei.com/vosk/models ; Towards Data Science evaluation — https://towardsdatascience.com/vosk-for-efficient-enterprise-grade-speech-recognition-an-evaluation-and-implementation-guide-87a599217a6c/
[12] NVIDIA Parakeet / Canary — https://docs.nvidia.com/nim/speech/latest/reference/support-matrix/asr.html ; https://build.nvidia.com/nvidia/parakeet-1_1b-rnnt-multilingual-asr
[13] NVIDIA Canary-Qwen-2.5B — https://huggingface.co/nvidia/canary-qwen-2.5b
[14] IBM Granite 4.0 1B Speech — https://huggingface.co/ibm-granite/granite-4.0-1b-speech ; https://community.ibm.com/community/user/blogs/matthias-blomme/2026/01/16/running-granite-40-1b-locally-on-android
[15] HuggingFace Open ASR Leaderboard — https://huggingface.co/spaces/hf-audio/open_asr_leaderboard ; methodology paper arxiv 2510.06961 — https://arxiv.org/html/2510.06961v4
[16] OpenAI gpt-4o-transcribe — https://developers.openai.com/api/docs/models/gpt-4o-transcribe ; pricing — https://costgoat.com/pricing/openai-transcription
[17] OpenAI Whisper releases — https://github.com/openai/whisper/releases
[18] Meta SeamlessM4T — https://huggingface.co/docs/transformers/main/model_doc/seamless_m4t ; https://github.com/facebookresearch/seamless_communication
[19] Deepgram Nova-3 — https://deepgram.com/learn/introducing-nova-3-speech-to-text-api ; models overview — https://developers.deepgram.com/docs/models-languages-overview
[20] AssemblyAI Universal-2 — https://www.assemblyai.com/universal-2
[21] Apple SpeechAnalyzer (iOS 26) — https://developer.apple.com/videos/play/wwdc2025/277/ ; https://www.macstories.net/stories/hands-on-how-apples-new-speech-apis-outpace-whisper-for-lightning-fast-transcription/
[22] FluencyBank Timestamped (ASHA pubs 2024) — https://pubs.asha.org/doi/pdf/10.1044/2024_JSLHR-24-00070?download=true
[23] Vosk language model adaptation — https://alphacephei.com/vosk/lm ; adaptation guide — https://alphacephei.com/vosk/adaptation ; custom-LM paper arxiv 2503.21025 — https://arxiv.org/html/2503.21025v1
[24] Confidence gating / cascade patterns — summarized in ACL LREC 2024 — https://aclanthology.org/2024.lrec-main.547/
[25] Confidence-Based Ensembles of E2E ASR (arxiv 2306.15824) — https://arxiv.org/html/2306.15824
[26] Wispr Flow on Baseten — https://www.baseten.co/resources/customers/wispr-flow/ ; design writeup — https://wisprflow.ai
[27] Wispr Flow review 2026 — https://www.getvoibe.com/resources/wispr-flow-review/
[28] SuperWhisper platform status 2026 — https://www.getvoibe.com/resources/superwhisper-platform-support/
[29] Otter.ai guide 2026 — https://aitoolsdevpro.com/ai-tools/otter-ai-guide/
[30] Google Live Caption architecture — https://blog.research.google/2019/10/on-device-captioning-with-live-caption.html ; Android authority — https://www.androidauthority.com/how-live-caption-works-android-10-1048376/
[31] Samsung vs Google Voice Typing on S25 — https://us.community.samsung.com/t5/Galaxy-S25/I-just-got-the-25-Ultra-and-my-Talk-to-Text-Option-on-my/td-p/3183309
[32] Google AI Edge Eloquent launch TechCrunch — https://techcrunch.com/2026/04/07/google-quietly-releases-an-offline-first-ai-dictation-app-on-ios/
[33] Google AI Edge Eloquent Android Gadget Hacks — https://android.gadgethacks.com/news/google-ai-edge-eloquent-dictation-app-offline-ai-that-edits-your-speech/ ; Google docs — https://ai.google.dev/edge/eloquent
[34] Claude Voice features 2026 — https://weesperneonflow.ai/en/blog/2026-02-23-claude-ai-voice-mode-2026-features-vs-dedicated-dictation/
[35] Stamurai app — https://play.google.com/store/apps/details?id=com.stamurai.stamurai ; https://www.stamurai.com/
[36] Interspeech 2025 Speech Accessibility Project Challenge — https://arxiv.org/html/2507.22047
[37] Stutter-Solver multilingual — https://pmc.ncbi.nlm.nih.gov/articles/PMC12233913/
[38] Lost in Transcription — https://arxiv.org/html/2405.06150v1
[39] Inclusive ASR for Disfluent Speech — https://arxiv.org/html/2406.10177v1
[40] Aligning Stuttered-Speech Research with End-User Needs — https://arxiv.org/html/2604.20535
[41] Repetition Disfluency vs Morphological Repetition — https://arxiv.org/pdf/2511.13159
[42] Stuttering-Aware ASR for Indonesian — https://arxiv.org/html/2601.03727
[43] Pushing the Limits of On-Device Streaming ASR — https://arxiv.org/html/2604.14493
[44] Back to Basics: Revisiting ASR in the Age of Voice Agents — https://arxiv.org/html/2603.25727v1
[45] Ensembles of Hybrid and E2E ASR (ACL LREC 2024) — https://aclanthology.org/2024.lrec-main.547/

Additional consulted sources (not directly cited above but informed the research):
- Northflank 2026 STT benchmarks — https://northflank.com/blog/best-open-source-speech-to-text-stt-model-in-2026-benchmarks
- Gladia 2026 open-source STT — https://www.gladia.io/blog/best-open-source-speech-to-text-models
- Deepgram best STT APIs 2026 — https://deepgram.com/learn/best-speech-to-text-apis-2026
- zackproser voice AI comparison 2026 — https://zackproser.com/blog/voice-ai-comparison-2026
- AssemblyAI best real-time STT 2026 — https://www.assemblyai.com/blog/best-api-models-for-real-time-speech-recognition-and-transcription
- Anthropic Claude Code voice mode — https://mlq.ai/news/anthropic-launches-voice-mode-for-claude-code/
- IBM Granite Hugging Face leaderboard — https://research.ibm.com/blog/granite-speech-recognition-hugging-face-chart
- VoicePing Android offline transcribe repo — https://github.com/voiceping-ai/android-offline-transcribe
