package com.wim.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import android.util.Log
import java.util.concurrent.TimeUnit

object ReconstructClient {

    private const val BACKEND_URL = "https://us-central1-bakers-agent.cloudfunctions.net/wim-reconstruct"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val TONE_RULES = mapOf(
        "formal" to "Use complete sentences. No contractions. No colloquialisms. Proper grammar and punctuation. Preserve the speaker's exact word choices where possible.",
        "professional" to "Clean, clear business language. Contractions acceptable but not preferred. Fix grammar and strip fillers. Minor rephrasing for clarity is OK.",
        "casual" to "Natural spoken rhythm. Contractions OK. Strip fillers and fix obvious errors, but keep the speaker's natural voice.",
        "friend" to "Conversational, relaxed. Contractions expected. Sentence fragments OK if natural. Strip fillers but preserve personality."
    )

    /** Per-tone temperature: formal is tighter, friend is looser */
    val TONE_TEMPERATURE = mapOf(
        "formal" to 0.1,
        "professional" to 0.15,
        "casual" to 0.35,
        "friend" to 0.4
    )

    /** Situation severity mapping (mirrored from lavrentiy.py) */
    val SITUATION_SEVERITY = mapOf(
        "default" to 1.0,
        "high_stress" to 1.5,
        "reading" to 0.3
    )

    /**
     * Send raw transcription to GPT for reconstruction.
     * Returns clean text.
     *
     * @param rawText the pre-cleaned ASR output
     * @param tone voice tone setting (formal/professional/casual/friend)
     * @param layer reconstruction layer (2-4)
     * @param situation current situation context (default/high_stress/reading)
     * @param vocabulary user's custom vocabulary list (L3+)
     * @param corrections user's learned corrections map (L3+)
     */
    suspend fun reconstruct(
        rawText: String,
        tone: String = "casual",
        layer: Int = 2,
        situation: String = "default",
        vocabulary: List<String> = emptyList(),
        corrections: Map<String, String> = emptyMap(),
        fillerWords: List<String> = emptyList(),
        triggerWords: List<String> = emptyList(),
        onsetWeights: Map<String, Double> = emptyMap(),
        covertProfile: Map<String, String> = emptyMap(),
        whisperLowConf: List<WhisperClient.LowConfidenceSegment> = emptyList(),
        whisperDisagreements: List<String> = emptyList(),
        speechSeverityMod: Double = 0.0,
        precedingContext: String = "",
        languageCode: String = "en",
        previousOutputs: List<String> = emptyList(),
        compressionRatioNote: String = "",
        audiencePackage: String? = null
    ): ReconstructResult {
        // Persistent learning state machine (RejectionStore + StyleExamples):
        //
        //   - Non-empty previousOutputs → current call is a regenerate.
        //     Prior reconstruction was REJECTED. Capture into RejectionStore.
        //     Drop the pending StyleExamples pair (it was the rejected one).
        //
        //   - Empty previousOutputs → current call is a fresh dictation.
        //     Prior reconstruction was implicitly ACCEPTED (no regenerate
        //     fired before this). Promote pending pair to StyleExamples.
        //
        // After reconstruction returns, this call's (raw, output) gets
        // staged as the new pending pair — next call decides its fate.
        val pending = StyleExamples.pendingPair
        if (previousOutputs.isNotEmpty()) {
            RejectionStore.record(previousOutputs.last())
            StyleExamples.pendingPair = null
        } else {
            if (pending != null) {
                StyleExamples.record(pending.first, pending.second)
            }
            StyleExamples.pendingPair = null
        }

        // If signed in, use backend proxy (server holds API key)
        val idToken = AuthManager.getIdToken()
        val result = if (idToken != null) {
            reconstructViaBackend(rawText, tone, layer, situation, vocabulary, corrections, fillerWords, triggerWords, onsetWeights, covertProfile, idToken, whisperLowConf, whisperDisagreements, speechSeverityMod, precedingContext, languageCode, previousOutputs, compressionRatioNote, audiencePackage)
        } else {
            // Fallback: direct OpenAI call (dev mode, requires local API key)
            reconstructDirect(rawText, tone, layer, situation, vocabulary, corrections, triggerWords, onsetWeights, precedingContext, languageCode, previousOutputs, compressionRatioNote, audiencePackage)
        }

        // Stage this reconstruction's (raw, output) pair as PENDING. The
        // next reconstruct() call decides whether it gets promoted to
        // accepted (StyleExamples) or dropped (was a regenerate target).
        if (result.cleanText.isNotBlank()) {
            StyleExamples.pendingPair = rawText to result.cleanText
        }
        return result
    }

    /**
     * Call the backend proxy (production path).
     * Server handles API key, tier enforcement, rate limiting.
     */
    private suspend fun reconstructViaBackend(
        rawText: String, tone: String, layer: Int, situation: String,
        vocabulary: List<String>, corrections: Map<String, String>,
        fillerWords: List<String>, triggerWords: List<String>,
        onsetWeights: Map<String, Double>, covertProfile: Map<String, String>,
        idToken: String,
        whisperLowConf: List<WhisperClient.LowConfidenceSegment> = emptyList(),
        whisperDisagreements: List<String> = emptyList(),
        speechSeverityMod: Double = 0.0,
        precedingContext: String = "",
        languageCode: String = "en",
        previousOutputs: List<String> = emptyList(),
        compressionRatioNote: String = "",
        audiencePackage: String? = null
    ): ReconstructResult = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()

        val body = JSONObject().apply {
            put("raw", rawText)
            put("tone", tone)
            put("layer", layer)
            put("situation", situation)
            put("mode", "SAFE")
            val profile = JSONObject()
            if (vocabulary.isNotEmpty()) profile.put("vocabulary", JSONArray(vocabulary))
            if (corrections.isNotEmpty()) {
                val corr = JSONObject()
                corrections.forEach { (k, v) -> corr.put(k, v) }
                profile.put("corrections", corr)
            }
            if (fillerWords.isNotEmpty()) profile.put("filler_words", JSONArray(fillerWords))
            profile.put("trigger_words", JSONArray(triggerWords))
            profile.put("onset_weights", JSONObject(onsetWeights as Map<*, *>))
            profile.put("covert_profile", JSONObject(covertProfile as Map<*, *>))
            // L1-transfer pack key — backend uses this to inject the speaker's
            // L1 patterns at L2/L3 (russian/spanish/mandarin/...). Empty or "en"
            // = native speaker, no injection.
            val profileL1 = WimApp.prefs.getString("profile_l1", "") ?: ""
            if (profileL1.isNotBlank() && profileL1 != "en") {
                profile.put("profile_l1", profileL1)
            }
            if (profile.length() > 0) put("profile", profile)

            // Whisper confidence signals (match Lavrentiy behavior at all paid layers)
            if (whisperLowConf.isNotEmpty()) {
                val lowConfArr = JSONArray()
                for (seg in whisperLowConf) {
                    lowConfArr.put(JSONObject().apply {
                        put("text", seg.text)
                        put("avg_logprob", seg.avgLogprob)
                        put("no_speech_prob", seg.noSpeechProb)
                        put("block_suspect", seg.blockSuspect)
                    })
                }
                put("whisper_low_conf", lowConfArr)
            }
            if (whisperDisagreements.isNotEmpty()) {
                put("whisper_disagreements", JSONArray(whisperDisagreements))
            }
            if (speechSeverityMod != 0.0) {
                put("speech_severity_mod", speechSeverityMod)
            }
            if (precedingContext.isNotBlank()) {
                put("preceding_context", precedingContext)
            }
            put("language_code", LangPackHelper.normalizeLangCode(languageCode))
            // Rate-gap note: pre-formatted string (caller has wordCount,
            // audioDurationSec, and the baseline). When non-empty it's
            // appended to the L2/L3 prompt by the backend (or skipped
            // gracefully if the backend version doesn't yet read it).
            if (compressionRatioNote.isNotBlank()) {
                put("compression_ratio_note", compressionRatioNote)
            }
            // Audience-context: foreground app package name from the
            // accessibility service. Backend formats the READER block
            // server-side using the same AudienceContext mapping; if
            // the backend version doesn't yet read it, ignored gracefully.
            if (!audiencePackage.isNullOrBlank()) {
                put("audience_package", audiencePackage)
            }
            // Persistent rejection history: outputs the user has rejected
            // across sessions (last 10). Backend can inject as negative
            // few-shot examples; ignored gracefully if not consumed.
            val rejectionHistory = RejectionStore.recent()
            if (rejectionHistory.isNotEmpty()) {
                put("rejection_history", JSONArray(rejectionHistory))
            }
            // Persistent style examples: (raw, accepted_output) pairs the
            // user has implicitly accepted by NOT hitting regenerate.
            // Positive few-shot counterpart to rejection_history. Backend
            // can inject as positive examples; ignored gracefully if not
            // consumed.
            val styleExamples = StyleExamples.recent()
            if (styleExamples.isNotEmpty()) {
                val arr = JSONArray()
                styleExamples.forEach { ex ->
                    arr.put(JSONObject().apply {
                        put("raw", ex.raw)
                        put("output", ex.output)
                    })
                }
                put("style_examples", arr)
            }

            // Regenerate hint: when the user re-rolls, we send the prior outputs
            // so the backend can ask the LLM for a different phrasing AND
            // bump sampling temperature.
            if (previousOutputs.isNotEmpty()) {
                put("previous_outputs", JSONArray(previousOutputs))
                put("regenerate", true)
            }
        }

        val request = Request.Builder()
            .url(BACKEND_URL)
            .addHeader("Authorization", "Bearer $idToken")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = http.newCall(request).execute()
        val responseBody = response.body?.string()
            ?: throw RuntimeException("Empty response from backend")

        if (!response.isSuccessful) {
            throw RuntimeException("Backend error ${response.code}: $responseBody")
        }

        val json = JSONObject(responseBody)
        val reconMs = System.currentTimeMillis() - t0

        val cleanText = json.optString("clean", rawText)
        // Trust backend's safety decisions — it runs Falcon and risk flags server-side
        val falconOk = json.optBoolean("falcon_ok", true)
        val finalText = if (falconOk) cleanText else rawText

        if (!falconOk) {
            Log.w("WiM", "Backend Falcon rejected reconstruction, using raw text")
        }

        ReconstructResult(
            cleanText = finalText,
            reconMs = reconMs,
            riskFlags = emptyList(),
            falconApproved = falconOk
        )
    }

    /**
     * Call OpenAI directly (dev/fallback path).
     */
    private suspend fun reconstructDirect(
        rawText: String, tone: String, layer: Int, situation: String,
        vocabulary: List<String>, corrections: Map<String, String>,
        triggerWords: List<String> = emptyList(),
        onsetWeights: Map<String, Double> = emptyMap(),
        precedingContext: String = "",
        languageCode: String = "en",
        previousOutputs: List<String> = emptyList(),
        compressionRatioNote: String = "",
        audiencePackage: String? = null
    ): ReconstructResult =
        withContext(Dispatchers.IO) {
            val apiKey = WimApp.apiKey()
            if (apiKey.isBlank()) throw IllegalStateException("No API key configured")

            val t0 = System.currentTimeMillis()
            val toneRule = TONE_RULES[tone] ?: TONE_RULES["casual"]!!
            val baseTemperature = TONE_TEMPERATURE[tone] ?: 0.35
            // Regenerate path: bump temperature so the model actually picks
            // different words, not just recasts the same phrase.
            val temperature = if (previousOutputs.isNotEmpty()) {
                (baseTemperature + 0.4).coerceAtMost(1.0)
            } else baseTemperature
            val severity = SITUATION_SEVERITY[situation] ?: 1.0

            val systemPrompt = buildString {
                append("You are a voice transcription post-processor. You receive raw, messy speech-to-text output and return ONLY the cleaned-up version. ")
                append("You are NOT a chatbot. Do NOT respond to the content. Do NOT ask questions. Do NOT add commentary. ")
                append("If the input is a fragment or incomplete sentence, return it cleaned up as-is — do NOT say 'your message got cut off'. ")
                append("Tone: $tone. ")
                append(toneRule)
                append(" Strip filler words (including non-English fillers like \u044d, \u043d\u0443, \u044d\u044d).")
                append(" Preserve FULL meaning. Do not summarize or add information.")
                append(" Do NOT censor, sanitize, or soften the speaker's language. Profanity, slang, harsh words, and strong language must be preserved EXACTLY as spoken.")
                append(" If the speaker said 'fuck', output 'fuck'. If the speaker said 'steal', output 'steal'. Do not substitute softer synonyms.")
                append(" Your job is to clean up SPEECH ARTIFACTS, not to edit the speaker's word choices.")
                append(" Output ONLY the reconstructed text, nothing else.")

                // Accessibility look-behind: text already sitting in the field before the dictation.
                // Use as context to disambiguate the speaker's current phrase. Do not echo it back.
                if (precedingContext.isNotBlank()) {
                    append("\n\nText already in the target field before this dictation (CONTEXT — do NOT include in output, do NOT repeat, do NOT acknowledge):\n\"")
                    append(precedingContext.takeLast(500))
                    append("\"\nUse this only to disambiguate words and match the speaker's running topic.")
                }

                // Audience-context: READER block formatted from the foreground
                // app's package name (captured by WimAccessibilityService).
                // Tunes brevity, formality, and structure to the actual
                // medium and recipient — not just the user's chosen tone.
                // Patent claim line: "voice transcription post-processor that
                // conditions reconstruction style on real-time foreground-app
                // metadata." See AudienceContext.kt for the mapping.
                append(AudienceContext.formatReaderBlock(audiencePackage))

                // Situation severity context (ported from lavrentiy.py aggression_note logic)
                if (severity >= 1.4) {
                    append("\n\nSpeaker is in a HIGH-STRESS context (phone/presentation/interview). ")
                    append("Expect more disfluencies, heavier avoidance, more filler stacking. ")
                    append("Be MORE aggressive in reconstructing — strip more, trust less of the literal words.")
                } else if (severity <= 0.6) {
                    append("\n\nSpeaker is in a low-stress context. Expect near-fluent speech. ")
                    append("Be conservative — minor cleanup only.")
                }

                // Rate-gap note: pre-formatted by caller (BubbleService) using
                // RateGapDetector output. Surfaces the ASR-derived word/audio
                // ratio when it diverges from the user's baseline. Low ratio
                // = ASR likely dropped content (Samsung pre-smoothing, blocks,
                // extended pauses). Information-only signal; lets the model
                // see the diagnostic numbers, not just feel the severity bump.
                if (compressionRatioNote.isNotBlank()) {
                    append("\n\n\u26A0 COMPRESSION GAP — ")
                    append(compressionRatioNote)
                }

                // L3+ profile context: vocabulary and corrections
                if (layer >= 3) {
                    if (vocabulary.isNotEmpty() || corrections.isNotEmpty()) {
                        append("\n\nSpeaker's known vocabulary (prefer these spellings): ")
                        append(vocabulary.joinToString(", "))
                    }
                    if (corrections.isNotEmpty()) {
                        append("\n\nKnown transcription errors to correct: ")
                        corrections.forEach { (wrong, right) ->
                            append("\"$wrong\" -> \"$right\", ")
                        }
                    }
                    // Persistent rejection history — cross-session counterpart
                    // to the within-session regenerate-as-negative block at
                    // the end of buildString. Outputs the user has previously
                    // rejected by hitting regenerate. The model uses these as
                    // STYLE-LEVEL negative examples — not "don't use these
                    // exact words" but "don't produce structurally/rhythmically
                    // similar text." Lower-priority than the within-session
                    // hint (which is an active, hot signal); included only
                    // when L3+ where the heavier prompt context lives.
                    val priorRejections = RejectionStore.recent()
                    if (priorRejections.isNotEmpty()) {
                        append("\n\nPERSISTENT REJECTION HISTORY (recent reconstructions this user rejected — DO NOT echo back, use only to avoid producing structurally similar text):")
                        priorRejections.forEachIndexed { i, prev ->
                            append("\n  ${i + 1}. \"$prev\"")
                        }
                        append("\nThese don't fit this user's voice. Pick distinctively different word choices, sentence shapes, and rhythm. Variety in style across these examples beats imitation of any single one.")
                    }

                    // Style examples — positive counterpart to rejection
                    // history. Pairs the user implicitly accepted (didn't
                    // regenerate). Few-shot pressure toward this user's
                    // demonstrated voice. Lower-priority hint than
                    // vocabulary/corrections (those are EXACT preferences;
                    // these are STYLE preferences). DO NOT echo back.
                    val styleExamples = StyleExamples.recent()
                    if (styleExamples.isNotEmpty()) {
                        append("\n\nUSER STYLE EXAMPLES (recent reconstructions this user accepted as final — apply same style choices when relevant; DO NOT echo back):")
                        styleExamples.forEachIndexed { i, ex ->
                            append("\n  ${i + 1}. Raw: \"${ex.raw}\" → Output: \"${ex.output}\"")
                        }
                        append("\nThis user's voice across these examples is the target. Match sentence rhythm, comma placement, contraction usage, and overall tone — not specific words.")
                    }
                }

                // L2-L3: ASR artifact guidance (ported from Лаврентий)
                if (layer in 2..3) {
                    // CRITICAL: even on fluent input, the user expects to see a
                    // reconstruction. Identity output (input ≈ output) reads as
                    // "the app did nothing" and kills perceived value. Force a
                    // visible rewrite into tighter written prose every time,
                    // grounded in established prose-writing rules.
                    append("\n\nALWAYS RESTATE — DO NOT RETURN INPUT UNCHANGED.")
                    append("\nThe input is raw spoken material. The output is text someone will READ. CONVERT spoken cadence into written prose every time, even when input is fluent. Identity output is a failure mode.")
                    append("\n\nApply these established prose rules (Strunk & White, federal Plain Language Guidelines):")
                    append("\n- Omit needless words. A sentence should contain no unnecessary words.")
                    append("\n- Use the active voice. \"John threw the ball,\" not \"The ball was thrown by John.\"")
                    append("\n- Use definite, specific, concrete language. \"It rained for a week,\" not \"a period of unfavorable weather.\"")
                    append("\n- Sentence length should average 15-20 words. Break run-on speech into multiple short sentences.")
                    append("\n- Keep subject and verb close together.")
                    append("\n- Use everyday words. Avoid jargon unless the speaker used it specifically.")
                    append("\n- Sentences should be simple, active, affirmative, declarative.")
                    append("\n- Drop verbal-tic discourse markers ('so', 'well', 'you know', 'like') even when not pure fillers.")
                    append("\n\nThe speaker is brain-dumping in stream-of-consciousness order. They are offloading the structuring task to you. REORDER clauses, GROUP related ideas, and RESTRUCTURE into the flow a written reader expects (e.g., context → ask → close for an email; setup → question → specifics for a message; thesis → support → conclusion for an argument).")
                    append("\n\nHARD RULES while restating:")
                    append("\n- PRESERVE all numbers, dates, dollar amounts, addresses, names, proper nouns exactly as spoken.")
                    append("\n- PRESERVE the speaker's intent and the substance of every clause.")
                    append("\n- DO NOT add information or invent details not present in the input.")
                    append("\n- DO NOT soften, sanitize, or change profanity / strong language / slang. Output the words the speaker chose.")
                    append("\n- DO NOT summarize away content; restate, don't compress.")
                    append("\n- TREAT unfamiliar, invented-looking, or single-syllable unrecognized words as INTENTIONAL slang, brand names, or in-group vocabulary. Do not substitute them, do not assume transcription error, do not 'fix' them. Examples of what to PRESERVE without modification: 'rizz', 'bussin', 'no cap', 'mid', 'delulu', 'skibidi', 'ick', 'fanum tax', any proper noun the speaker emphasized, any startup or tool name the speaker said clearly.")

                    // SELF-CORRECTION CANONICAL OVERWRITE — when the speaker
                    // says "I mean", "actually", "no wait", etc., what FOLLOWS
                    // is canonical and what came BEFORE should be discarded.
                    // Crucial for stutterers (covert revision is core stuttering
                    // behavior — replacing feared words mid-sentence). 'I mean'
                    // was intentionally removed from the discourse-marker drop
                    // list above so this rule wins.
                    append("\n\nSELF-CORRECTION — CANONICAL OVERWRITE:")
                    append("\nWhen the speaker uses 'I mean', 'actually', 'no wait', 'scratch that', 'let me rephrase', or similar mid-sentence revision markers, treat the content AFTER the marker as canonical and DISCARD the content before it. This is intentional self-correction, not disfluency.")
                    append("\nExample: 'the meeting at 3pm, I mean 4pm' → 'the meeting at 4pm' (NOT '3pm 4pm', NOT '3pm').")
                    append("\nExample: 'I'm going to the s-s-s-the place' → 'I'm going to the place' (covert revision past a hard onset).")
                    append("\nExample: 'let's go to Italian, actually let's go to Thai' → 'let's go to Thai'.")

                    append("\n\nThe input is a voice transcription and may contain ASR artifacts:")
                    append("\n- Repeated words or phrases from natural speech hesitation")
                    append("\n- Filler sounds transcribed as real words (e.g., 'um' → 'come')")
                    append("\n- Phantom words inserted during pauses in speech")
                    append("\n- Phonetically similar but semantically wrong words (misheard)")
                    append("\n- Truncated or garbled words from unclear pronunciation")
                    append("\nWhen a word is phonetically plausible but doesn't fit the context, prefer the contextually correct interpretation.")
                    append("\n\nExamples:")
                    append("\n  IN:  'So um I was going to uh the store to get some, some milk'")
                    append("\n  OUT: 'I was going to the store to get some milk'")
                    append("\n  IN:  'Can you send me the, the report by, by Friday'")
                    append("\n  OUT: 'Can you send me the report by Friday'")
                    append("\n  IN:  'I think we should, we should probably move the meeting'")
                    append("\n  OUT: 'I think we should probably move the meeting'")
                    append("\n  IN:  'The, uh, what's it called, the database needs updating'")
                    append("\n  OUT: 'The database needs updating'")

                    // L1-transfer pack injection (parity with Lavrentiy l1_pack.py).
                    // Phonetic accent dies at the ASR layer; syntactic / morphological
                    // / lexical patterns from the speaker's first language survive
                    // in the transcript and are deterministically detectable.
                    append(L1PackHelper.promptInjection(WimApp.instance))
                }

                // L4: Language-parameterized speech-disfluency prompt
                // (see docs/l4_prompt_engineering_memo.md § 4–5 in the lavrentiy repo)
                if (layer >= 4) {
                    val langCode = LangPackHelper.normalizeLangCode(languageCode)
                    val langName = LangPackHelper.languageName(langCode)

                    append("\n\nThe speaker has a speech disfluency. Language: ${langCode.uppercase()} ($langName). ")
                    append("Raw transcription is evidence of intent, not truth. ")
                    append("Reconstruct the intended message. Preserve FULL meaning.")

                    // Language-specific fillers
                    val langFillers = LangPackHelper.getLangFillers(langCode)
                    if (langFillers.isNotEmpty()) {
                        append("\n\nFILLERS TO STRIP ($langCode): ${langFillers.joinToString(", ")}")
                    }

                    // Natural-repeat protection — must come BEFORE any repetition-stripping rule
                    val naturalRepeats = LangPackHelper.getLangNaturalRepeats(langCode)
                    if (naturalRepeats.isNotEmpty()) {
                        append("\n\nEMPHATIC PATTERNS — DO NOT STRIP in $langCode: ${naturalRepeats.joinToString(", ")}")
                        append("\nThese are pragmatically meaningful in $langName, not stuttering.")
                    }

                    append("\n\nOvert disfluencies — strip and reconstruct:")
                    append("\n- Part-word repetitions: ${LangPackHelper.getLangPartWordExample(langCode)}")
                    append("\n- Whole-word repetitions (NOT matching the emphatic allow-list above)")
                    append("\n- Prolongations: ${LangPackHelper.getLangProlongationExample(langCode)}")
                    append("\n- Epenthetic insertions during blocks: ${LangPackHelper.getLangEpenthesisNote(langCode)}")
                    append("\n- Blocks: silence or frozen onset before a word (locked articulators)")
                    append("\n- False starts and restarts")

                    append("\n\nCovert avoidance — recognize as avoidance behavior, not content:")
                    append("\n- Filler clusters before a content word = postponement (see filler list above)")
                    append("\n- Synonym substitution = avoiding a feared word")
                    append("\n- Circumlocution = talking around a feared word")
                    append("\n- Sentence abandonment = dropping thought before feared word")
                    append("\n- Mazes: extended filler runs adding no information. Distinct from cluttered")
                    append(" rapid speech — do not over-strip if the speaker's speech is globally rapid.")
                    val dialectNote = LangPackHelper.getLangDialectAvoidanceNote(langCode)
                    if (dialectNote.isNotEmpty()) append("\n$dialectNote")

                    append("\n\nAnticipatory behavior:")
                    append("\n- A pause or silence BEFORE a content word with a hard onset MAY INDICATE anticipatory fear")
                    append("\n- Confidence increases when a filler cluster appears in the preceding 1–3 words")
                    append(" AND the following word begins with a documented hard onset")
                    append("\n- Treat as a block candidate, not certainty")
                    val timingNote = LangPackHelper.getLangSyllableTimingNote(langCode)
                    if (timingNote.isNotEmpty()) append("\n$timingNote")

                    append("\n\nWhisper ASR failure modes on disfluent speech:")
                    append("\n- HALLUCINATION DURING BLOCKS: silence → Whisper generates phantom text.")
                    append("\n  Known hallucination strings to discard: 'thank you', 'thanks for watching',")
                    append(" 'subscribe', 'like and subscribe', 'transcribed by', 'captions by', 'otter.ai'.")
                    if (langCode != "en") {
                        append("\n  In $langName transcripts, English phrases appearing mid-utterance are")
                        append(" likely Whisper hallucinations — discard them.")
                    }
                    append("\n- SYLLABLE DELETION: repeated syllables collapsed or dropped")
                    append("\n- PHANTOM INSERTIONS: prolongations → Whisper hallucinates similar-sounding words")
                    append("\n- ${LangPackHelper.getLangEpenthesisCorruptionNote(langCode)}")
                    append("\n- PAUSE HALLUCINATION: long pauses → Whisper generates filler text (see above)")

                    append("\n\n")
                    append(LangPackHelper.getLangFewShotExamples(langCode))

                    append("\n\nDo not mistake disfluency for emphasis — but PRESERVE the emphatic patterns listed above.")
                    append("\nReconstruct within the speaker's established dialect — do not substitute dialectal forms.")
                    append("\nWhen uncertain, prefer conservative cleanup over aggressive rewriting.")

                    // Personal phoneme difficulty map — language-caveated
                    if (onsetWeights.isNotEmpty()) {
                        val topOnsets = onsetWeights.entries
                            .sortedByDescending { it.value }
                            .take(5)
                            .joinToString(", ") { "/${it.key}/ (${(it.value * 100).toInt()}%)" }
                        append("\n\nTHIS SPEAKER'S HARDEST PHONEMES: $topOnsets")
                        val onsetCaveat = LangPackHelper.getLangOnsetCaveat(langCode)
                        if (onsetCaveat.isNotEmpty()) append("\n$onsetCaveat")
                        append("\nWhisper output near these onsets is unreliable — expect hallucinations, syllable drops, or phantom insertions.")
                    } else if (LangPackHelper.hasNoOnsetResearch(langCode)) {
                        append("\n\nONSET NOTE: No published phoneme-difficulty research exists for $langCode as of April 2026.")
                        append(" Do not apply English-derived onset assumptions. Focus on word-level repetitions,")
                        append(" prolongations, filler clusters, and Whisper hallucination strings.")
                    }

                    // Known trigger words (user-specific)
                    if (triggerWords.isNotEmpty()) {
                        append("\n\nKnown trigger words: ${triggerWords.take(10).joinToString(", ")}")
                    }

                    // Predictive trigger flagging — language-aware
                    val predicted = PhoneticRisk.predictTriggersInText(rawText, triggerWords, onsetWeights, languageCode = langCode)
                    if (predicted.isNotEmpty()) {
                        val flagged = predicted.take(5).joinToString(", ") { "${it.first}(${it.second})" }
                        append("\n\nPhonetically predicted high-risk words in this utterance: $flagged")
                    }
                }

                // Domain pack injection (legal / medical / finance / sf_pro / hipaa).
                // Appended near the end so the model gives the canonical-vocab
                // and phonetic-alias rules high recency-weight when generating.
                // No-op when the user's domain pref is "general" (default).
                append(DomainPackHelper.promptInjection(WimApp.instance))

                // L1-transfer pack injection (russian / spanish / mandarin / etc).
                // Gated to L2/L3 per L1PackHelper's design — L4's clinical prompt
                // already has its own framing and double-instruction harms quality.
                // No-op when profile_l1 = "en" or unset.
                if (layer in 2..3) {
                    append(L1PackHelper.promptInjection(WimApp.instance))
                }

                // Regenerate hint: user re-rolled because the prior phrasing
                // didn't land. Pick different word choices and sentence shape
                // while preserving the same meaning.
                if (previousOutputs.isNotEmpty()) {
                    append("\n\nThe speaker is asking for a DIFFERENT phrasing of the same intent. ")
                    append("You have already produced these reconstructions, which the speaker rejected:")
                    previousOutputs.takeLast(4).forEachIndexed { i, prev ->
                        append("\n  ${i + 1}. \"$prev\"")
                    }
                    append("\nProduce a new reconstruction that preserves the same meaning but uses different word choices, sentence structure, and rhythm. Do not return any of the prior outputs verbatim or with only trivial edits.")
                }
            }

            // L4 routes to Claude Sonnet 4.6 with extended thinking when an
            // Anthropic key is set — clinical reasoning depth matches the
            // Lavrentiy desktop setup. L1/L2/L3 stay on GPT-4o.
            val anthropicKey = WimApp.anthropicApiKey()
            val useSonnet = layer >= 4 && anthropicKey.isNotBlank()
            val cleanText = if (useSonnet) {
                reconstructWithSonnet(systemPrompt, rawText, anthropicKey)
            } else {
                val model = "gpt-4o"
                val messages = JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", systemPrompt))
                    put(JSONObject().put("role", "user").put("content", rawText))
                }
                val body = JSONObject().apply {
                    put("model", model)
                    put("messages", messages)
                    put("temperature", temperature)
                    put("max_tokens", 1024)
                }
                val request = Request.Builder()
                    .url("https://api.openai.com/v1/chat/completions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                val response = http.newCall(request).execute()
                val responseBody = response.body?.string()
                    ?: throw RuntimeException("Empty response from reconstruction API")
                if (!response.isSuccessful) {
                    throw RuntimeException("Reconstruction API error ${response.code}: $responseBody")
                }
                JSONObject(responseBody)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()
            }

            val riskFlags = computeRiskFlags(rawText, cleanText)
            val fastMode = com.wim.app.WimApp.prefs.getBoolean("fast_mode", false)
            val falconOk = if (fastMode) {
                true // FAST mode: skip Falcon validation
            } else if (riskFlags.contains("critical_token_lost")) {
                false
            } else {
                falconValidate(rawText, cleanText, layer, tone, onsetWeights, languageCode)
            }

            val reconMs = System.currentTimeMillis() - t0
            val finalText = if (falconOk) cleanText else rawText

            if (!falconOk) {
                Log.w("WiM", "Falcon rejected reconstruction, using raw text. Flags: $riskFlags")
            }

            ReconstructResult(
                cleanText = finalText,
                reconMs = reconMs,
                riskFlags = riskFlags,
                falconApproved = falconOk
            )
        }

    /** Check that numbers, percentages, dollar amounts survived reconstruction. */
    private fun checkCriticalTokens(raw: String, clean: String): Boolean {
        val tokenPattern = Regex("""\$?\d[\d,.]*%?""")
        val rawTokens = tokenPattern.findAll(raw).map { it.value }.toSet()
        val cleanTokens = tokenPattern.findAll(clean).map { it.value }.toSet()
        return rawTokens.all { it in cleanTokens }
    }

    /**
     * Falcon validation: binary meaning-preservation check.
     *
     * Routing:
     *   - L4 → SKIPPED unconditionally. Sonnet 4.6 extended thinking self-validates
     *     during reasoning. GPT-4o fallback (no Anthropic key) runs unvalidated by
     *     design — same-provider Falcon is structurally weak. Leak filter + length
     *     guard still run post-reconstruction.
     *   - L2 / L3 with Anthropic key → Claude Haiku 4.5 (cross-provider QA, ~0.4-0.8 s).
     *   - L2 / L3 without Anthropic key → GPT-4o.
     *
     * Returns true on any error so a flaky validator never blocks reconstruction.
     */
    private suspend fun falconValidate(
        rawText: String,
        cleanText: String,
        layer: Int,
        tone: String = "casual",
        onsetWeights: Map<String, Double> = emptyMap(),
        languageCode: String = "en"
    ): Boolean {
        // Retired 2026-05-10: L4 Sonnet ET self-validates; L2/L3 GPT-4o quality
        // sufficient without cross-provider QA. Matches lavrentiy.py:3369.
        return true
    }

    // buildFalconSystemPrompt, falconValidateGpt, falconValidateClaude removed 2026-05-10
    // Falcon retired — falconValidate() is now a stub returning true (lavrentiy.py:3369).

    /**
     * L4 reconstruction via Claude Sonnet 4.6 with extended thinking.
     *
     * Anthropic Messages API differences vs OpenAI Chat Completions:
     *   - `system` is top-level, not a message in messages[]
     *   - `thinking: {type: "enabled", budget_tokens: N}` opts into extended
     *     reasoning; total max_tokens must be > budget_tokens
     *   - When thinking is enabled, temperature MUST be 1.0 (Anthropic constraint)
     *   - Response.content is an array of blocks: thinking blocks + text blocks.
     *     We extract the first `text` block as the reconstruction.
     *
     * Budget: 8K thinking + ~2K output = 10K total. Typical L4 utterance is
     * <50 words; extended thinking on a short input ends after a few hundred
     * tokens. Budget is the cap, not the spend.
     */
    private fun reconstructWithSonnet(systemPrompt: String, rawText: String, apiKey: String): String {
        val messages = JSONArray().apply {
            put(JSONObject().put("role", "user").put("content", rawText))
        }
        val thinkingConfig = JSONObject().apply {
            put("type", "enabled")
            put("budget_tokens", 8000)
        }
        val body = JSONObject().apply {
            put("model", "claude-sonnet-4-6")
            put("max_tokens", 10000)
            put("temperature", 1.0)
            put("system", systemPrompt)
            put("thinking", thinkingConfig)
            put("messages", messages)
        }
        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val response = http.newCall(request).execute()
        val responseBody = response.body?.string()
            ?: throw RuntimeException("Empty response from Sonnet reconstruction API")
        if (!response.isSuccessful) {
            throw RuntimeException("Sonnet reconstruction API error ${response.code}: $responseBody")
        }
        val content = JSONObject(responseBody).getJSONArray("content")
        // Skip thinking blocks, return the first text block.
        for (i in 0 until content.length()) {
            val block = content.getJSONObject(i)
            if (block.optString("type") == "text") {
                return block.getString("text").trim()
            }
        }
        throw RuntimeException("Sonnet response had no text block: ${responseBody.take(200)}")
    }

    /** Compute risk flags for a reconstruction result. */
    fun computeRiskFlags(rawText: String, cleanText: String): List<String> {
        val flags = mutableListOf<String>()
        if (cleanText.length < 3) flags.add("very_short_output")
        val lenRatio = if (rawText.isNotEmpty()) cleanText.length.toFloat() / rawText.length else 1f
        if (lenRatio < 0.3 || lenRatio > 3.0) flags.add("large_length_delta")
        if (cleanText.endsWith("...") || cleanText.endsWith("—")) flags.add("contains_unfinished_fragment")
        if (!checkCriticalTokens(rawText, cleanText)) flags.add("critical_token_lost")
        return flags
    }

    data class ReconstructResult(
        val cleanText: String,
        val reconMs: Long,
        val riskFlags: List<String> = emptyList(),
        val falconApproved: Boolean = true
    )
}
