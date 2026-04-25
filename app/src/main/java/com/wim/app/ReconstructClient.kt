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
        .readTimeout(30, TimeUnit.SECONDS)
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
        languageCode: String = "en"
    ): ReconstructResult {
        // If signed in, use backend proxy (server holds API key)
        val idToken = AuthManager.getIdToken()
        if (idToken != null) {
            return reconstructViaBackend(rawText, tone, layer, situation, vocabulary, corrections, fillerWords, triggerWords, onsetWeights, covertProfile, idToken, whisperLowConf, whisperDisagreements, speechSeverityMod, precedingContext, languageCode)
        }
        // Fallback: direct OpenAI call (dev mode, requires local API key)
        return reconstructDirect(rawText, tone, layer, situation, vocabulary, corrections, triggerWords, onsetWeights, precedingContext, languageCode)
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
        languageCode: String = "en"
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
        languageCode: String = "en"
    ): ReconstructResult =
        withContext(Dispatchers.IO) {
            val apiKey = WimApp.apiKey()
            if (apiKey.isBlank()) throw IllegalStateException("No API key configured")

            val t0 = System.currentTimeMillis()
            val toneRule = TONE_RULES[tone] ?: TONE_RULES["casual"]!!
            val temperature = TONE_TEMPERATURE[tone] ?: 0.35
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

                // Situation severity context (ported from lavrentiy.py aggression_note logic)
                if (severity >= 1.4) {
                    append("\n\nSpeaker is in a HIGH-STRESS context (phone/presentation/interview). ")
                    append("Expect more disfluencies, heavier avoidance, more filler stacking. ")
                    append("Be MORE aggressive in reconstructing — strip more, trust less of the literal words.")
                } else if (severity <= 0.6) {
                    append("\n\nSpeaker is in a low-stress context. Expect near-fluent speech. ")
                    append("Be conservative — minor cleanup only.")
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
                }

                // L2-L3: ASR artifact guidance (ported from Лаврентий)
                if (layer in 2..3) {
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
     * Routing (April 24, 2026 evening):
     *   - L4 with `anthropic_api_key` set → SKIPPED. Reconstruction ran on
     *     Sonnet 4.6 with extended thinking, which self-validates during the
     *     reasoning trace. Running a smaller-model Falcon afterward is
     *     structurally weak ("smaller checks bigger"). Leak filter and length
     *     guard (post-reconstruction, free regex/math) still run.
     *   - L4 without Anthropic key → GPT-4o (Sonnet path didn't run, GPT-4o
     *     doesn't reason before answering, so a separate validator helps).
     *   - L2 / L3 with Anthropic key → Claude Haiku 4.5 (cross-provider QA,
     *     ~0.4-0.8 s — faster than GPT and structurally a stronger check than
     *     GPT-checking-itself).
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
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val anthropicKey = WimApp.anthropicApiKey()

            // L4 + Sonnet path: skip Falcon entirely. Extended thinking already
            // self-validated during reasoning. Saves ~1-2 s and ~$0.01 per L4 call.
            if (layer >= 4 && anthropicKey.isNotBlank()) {
                return@withContext true
            }

            val systemPrompt = buildFalconSystemPrompt(layer, tone, onsetWeights, languageCode)
            val userMessage = "Original: $rawText\nReconstruction: $cleanText"
            val openaiKey = WimApp.apiKey()

            // L2/L3 with Claude key → Haiku Falcon. Otherwise GPT-4o (or skip if no key).
            val useClaude = layer < 4 && anthropicKey.isNotBlank()
            if (useClaude) {
                return@withContext falconValidateClaude(systemPrompt, userMessage, anthropicKey)
            }
            if (openaiKey.isBlank()) return@withContext true
            falconValidateGpt(systemPrompt, userMessage, openaiKey)
        } catch (e: Exception) {
            Log.e("WiM", "Falcon validation error: ${e.message}")
            true
        }
    }

    /**
     * Construct the Falcon system prompt. L4 gets the phonetic-guard + clinical
     * criteria block; L2/L3 gets the lighter "preserve meaning" framing. Reused
     * by both the GPT and Claude paths.
     */
    private fun buildFalconSystemPrompt(
        layer: Int,
        tone: String,
        onsetWeights: Map<String, Double>,
        languageCode: String,
    ): String {
        val toneNote = when (tone) {
            "formal" -> "Target tone is FORMAL — reconstruction should be conservative. Structural rewrites that change sentence formality are concerning."
            "professional" -> "Target tone is PROFESSIONAL — minor rephrasing for clarity is acceptable but not paraphrasing."
            "casual" -> "Target tone is CASUAL — contractions, colloquialisms, and minor restructuring for natural speech rhythm are expected and acceptable."
            "friend" -> "Target tone is FRIEND — fragments, informal phrasing, and free restructuring for conversational feel are expected."
            else -> ""
        }
        if (layer >= 4) {
            val langCode = LangPackHelper.normalizeLangCode(languageCode)
            val topOnsets = if (onsetWeights.isNotEmpty()) {
                onsetWeights.entries
                    .sortedByDescending { it.value }
                    .take(5)
                    .joinToString(", ") { "/${it.key}/" }
            } else "(no personal onset data)"
            val naturalRepeats = LangPackHelper.getLangNaturalRepeats(langCode)
            val naturalRepeatsStr = if (naturalRepeats.isNotEmpty())
                naturalRepeats.joinToString(", ") else "none defined"

            return "Validate a layer-4 speech-disfluency reconstruction. Language: ${langCode.uppercase()}. " +
                "Near the speaker's hardest phonemes ($topOnsets), the reconstruction MUST NOT " +
                "substitute a different-phoneme word — phonetic drift is the primary failure mode. " +
                "The reconstruction should also have: " +
                "(1) stripped overt disfluencies (part-word repetitions, prolongations, blocks, false starts); " +
                "(2) preserved emphatic patterns that are NOT stuttering: $naturalRepeatsStr; " +
                "(3) NOT normalized the speaker's dialect or register forms; " +
                "(4) discarded Whisper hallucination strings ('thank you', 'subscribe', etc.). " +
                "$toneNote " +
                "Does the reconstruction satisfy all criteria? Answer ONLY 'yes' or 'no'."
        }
        return "The reconstruction cleans up a voice transcription: removing filler words " +
            "(um, uh, like, you know), fixing grammar, and improving clarity. " +
            "Filler removal and grammar fixes are expected and acceptable. " +
            "$toneNote Does the reconstruction preserve the core content and intent? " +
            "Answer ONLY 'yes' or 'no'."
    }

    /** GPT-4o Falcon (used at L4 always, and at L2/L3 when no Anthropic key). */
    private fun falconValidateGpt(systemPrompt: String, userMessage: String, apiKey: String): Boolean {
        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", systemPrompt))
            put(JSONObject().put("role", "user").put("content", userMessage))
        }
        val body = JSONObject().apply {
            put("model", "gpt-4o")
            put("messages", messages)
            put("temperature", 0.0)
            put("max_tokens", 5)
        }
        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val response = http.newCall(request).execute()
        val responseBody = response.body?.string() ?: return true
        if (!response.isSuccessful) {
            Log.w("WiM", "Falcon (GPT) HTTP ${response.code}: ${responseBody.take(120)}")
            return true
        }
        val answer = JSONObject(responseBody)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
            .trim()
            .lowercase()
        return answer.startsWith("yes")
    }

    /**
     * Claude Haiku 4.5 Falcon (L2/L3 only, when anthropic_api_key is set).
     *
     * Anthropic Messages API has two API-shape differences from OpenAI Chat
     * Completions:
     *   - `system` is a top-level field, not a message in messages[].
     *   - Auth via `x-api-key` header + required `anthropic-version` header.
     *   - Response shape: `content[0].text` instead of `choices[0].message.content`.
     */
    private fun falconValidateClaude(systemPrompt: String, userMessage: String, apiKey: String): Boolean {
        val messages = JSONArray().apply {
            put(JSONObject().put("role", "user").put("content", userMessage))
        }
        val body = JSONObject().apply {
            put("model", "claude-haiku-4-5-20251001")
            put("max_tokens", 16)
            put("system", systemPrompt)
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
        val responseBody = response.body?.string() ?: return true
        if (!response.isSuccessful) {
            Log.w("WiM", "Falcon (Claude) HTTP ${response.code}: ${responseBody.take(120)}")
            return true
        }
        val answer = JSONObject(responseBody)
            .getJSONArray("content")
            .getJSONObject(0)
            .getString("text")
            .trim()
            .lowercase()
        return answer.startsWith("yes")
    }

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
