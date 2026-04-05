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
        speechSeverityMod: Double = 0.0
    ): ReconstructResult {
        // If signed in, use backend proxy (server holds API key)
        val idToken = AuthManager.getIdToken()
        if (idToken != null) {
            return reconstructViaBackend(rawText, tone, layer, situation, vocabulary, corrections, fillerWords, triggerWords, onsetWeights, covertProfile, idToken, whisperLowConf, whisperDisagreements, speechSeverityMod)
        }
        // Fallback: direct OpenAI call (dev mode, requires local API key)
        return reconstructDirect(rawText, tone, layer, situation, vocabulary, corrections, triggerWords, onsetWeights)
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
        speechSeverityMod: Double = 0.0
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
        onsetWeights: Map<String, Double> = emptyMap()
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
                append(" Output ONLY the reconstructed text, nothing else.")

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

                // L4: Full clinical stutter prompt (ported from Лаврентий)
                if (layer >= 4) {
                    append("\n\nThe speaker stutters. Raw transcription is evidence, not truth. ")
                    append("Reconstruct intended meaning, not literal word sequence.")

                    append("\n\nOvert disfluencies — strip and reconstruct:")
                    append("\n- Part-word repetitions: 'b-b-b-buy' → 'buy', 'Ca-ca-ca-can' → 'Can'")
                    append("\n- Whole-word repetitions: 'I I I want' → 'I want'")
                    append("\n- Prolongations: 'mmmmaybe' → 'maybe', 'Sssssscience' → 'Science'")
                    append("\n- Schwa substitution: 'guh-guh-goat' → 'goat'")
                    append("\n- Blocks: silence or frozen onset before a word (locked articulators)")
                    append("\n- False starts and restarts")

                    append("\n\nCovert stuttering — recognize as avoidance, not content:")
                    append("\n- Filler clusters before a content word = delay tactic, not hesitation")
                    append("\n- Synonym substitution = avoiding a feared word")
                    append("\n- Circumlocution = talking around a feared word")
                    append("\n- Sentence abandonment = dropping thought before feared word")
                    append("\n- Mazes/cluttering = rambling run-on filler adding no information")

                    append("\n\nAnticipatory behavior (CRITICAL):")
                    append("\n- A pause or silence BEFORE a content word is likely ANTICIPATORY FEAR, not thinking")
                    append("\n- The speaker scans ahead, detects a feared word, and freezes")
                    append("\n- Treat pre-word pauses on content words as blocks, not natural hesitation")

                    append("\n\nWhisper ASR failure modes on stuttered speech:")
                    append("\n- HALLUCINATION DURING BLOCKS: silence → Whisper invents words")
                    append("\n- SYLLABLE DELETION: repeated syllables collapsed or dropped")
                    append("\n- PHANTOM INSERTIONS: prolongations → Whisper hallucinates similar words")
                    append("\n- SCHWA CORRUPTION: neutral vowel in repeated clusters → transcribed as real word")
                    append("\n- PAUSE HALLUCINATION: long pauses → Whisper generates filler text")

                    append("\n\n=== FEW-SHOT EXAMPLES ===")
                    append("\n\nBLOCKS:")
                    append("\n  IN:  'I need the... [silence]... computer from the office'")
                    append("\n  OUT: 'I need the computer from the office'")
                    append("\n  IN:  'Can you get me the, the, the uh come put her from IT'")
                    append("\n  OUT: 'Can you get me the computer from IT'")

                    append("\n\nSOUND/SYLLABLE REPETITIONS:")
                    append("\n  IN:  'I was g-g-g-going to the st-store'")
                    append("\n  OUT: 'I was going to the store'")
                    append("\n  IN:  'Ca-ca-ca-can you p-p-please send the re-report'")
                    append("\n  OUT: 'Can you please send the report'")

                    append("\n\nWORD REPETITIONS:")
                    append("\n  IN:  'I I I want to to to go to the the meeting'")
                    append("\n  OUT: 'I want to go to the meeting'")

                    append("\n\nPROLONGATIONS:")
                    append("\n  IN:  'I was thinking about the sssssschedule for next week'")
                    append("\n  OUT: 'I was thinking about the schedule for next week'")

                    append("\n\nFILLER STACKING + POSTPONEMENT:")
                    append("\n  IN:  'So um uh like basically uh the thing is we need more time'")
                    append("\n  OUT: 'We need more time'")

                    append("\n\nAVOIDANCE / CIRCUMLOCUTION:")
                    append("\n  IN:  'I need the b-... the document from yesterday'")
                    append("\n  OUT: 'I need the document from yesterday'")

                    append("\n\nWHISPER ARTIFACTS:")
                    append("\n  IN:  'I was trying to come put her the file' (block on 'computer')")
                    append("\n  OUT: 'I was trying to get the computer file'")

                    append("\n\n=== END EXAMPLES ===")
                    append("\n\nDo not mistake disfluency for emphasis. Do not invent meaning beyond what was intended.")
                    append("\nWhen uncertain, prefer conservative cleanup over aggressive rewriting.")

                    // Personal phoneme difficulty map
                    if (onsetWeights.isNotEmpty()) {
                        val topOnsets = onsetWeights.entries
                            .sortedByDescending { it.value }
                            .take(5)
                            .joinToString(", ") { "/${it.key}/ (${(it.value * 100).toInt()}%)" }
                        append("\n\nTHIS SPEAKER'S HARDEST PHONEMES: $topOnsets")
                        append("\nWhisper output near these onsets is unreliable — expect hallucinations, syllable drops, or phantom word insertions.")
                    }

                    // Known trigger words
                    if (triggerWords.isNotEmpty()) {
                        append("\n\nKnown trigger words: ${triggerWords.take(10).joinToString(", ")}")
                    }

                    // Predictive trigger flagging — score all words in this utterance
                    val predicted = PhoneticRisk.predictTriggersInText(rawText, triggerWords, onsetWeights)
                    if (predicted.isNotEmpty()) {
                        val flagged = predicted.take(5).joinToString(", ") { "${it.first}(${it.second})" }
                        append("\n\nPhonetically predicted high-risk words in this utterance: $flagged")
                    }
                }
            }

            val model = if (layer >= 4) "gpt-4o" else "gpt-4o"

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

            val json = JSONObject(responseBody)
            val cleanText = json
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()

            val riskFlags = computeRiskFlags(rawText, cleanText)
            val fastMode = com.wim.app.WimApp.prefs.getBoolean("fast_mode", false)
            val falconOk = if (fastMode) {
                true // FAST mode: skip Falcon validation
            } else if (riskFlags.contains("critical_token_lost")) {
                false
            } else {
                falconValidate(rawText, cleanText, layer, tone)
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

    /** Falcon validation: binary meaning-preservation check via GPT. */
    private suspend fun falconValidate(rawText: String, cleanText: String, layer: Int, tone: String = "casual"): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val apiKey = WimApp.apiKey()
                if (apiKey.isBlank()) return@withContext true // skip if no key

                // Tone-aware rewrite latitude — more permissive tones allow bigger restructuring
                val toneNote = when (tone) {
                    "formal" -> "Target tone is FORMAL — reconstruction should be conservative. Structural rewrites that change sentence formality are concerning."
                    "professional" -> "Target tone is PROFESSIONAL — minor rephrasing for clarity is acceptable but not paraphrasing."
                    "casual" -> "Target tone is CASUAL — contractions, colloquialisms, and minor restructuring for natural speech rhythm are expected and acceptable."
                    "friend" -> "Target tone is FRIEND — fragments, informal phrasing, and free restructuring for conversational feel are expected."
                    else -> ""
                }

                val prompt = if (layer >= 4) {
                    "Speaker stutters. Repeated syllables, prolongations, and blocks are " +
                    "disfluencies, not emphasis. Filler clusters before content words are " +
                    "postponement tactics, not meaningful hesitation. Synonym substitutions " +
                    "and circumlocutions are avoidance behaviors — the reconstruction should " +
                    "recover the intended word. Rambling run-on filler (mazes) should be " +
                    "stripped. $toneNote Does the reconstruction preserve intended meaning? " +
                    "Answer ONLY 'yes' or 'no'."
                } else {
                    "The reconstruction cleans up a voice transcription: removing filler words " +
                    "(um, uh, like, you know), fixing grammar, and improving clarity. " +
                    "Filler removal and grammar fixes are expected and acceptable. " +
                    "$toneNote Does the reconstruction preserve the core content and intent? " +
                    "Answer ONLY 'yes' or 'no'."
                }

                val messages = JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", prompt))
                    put(JSONObject().put("role", "user").put("content",
                        "Raw: $rawText\nReconstructed: $cleanText"))
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
                val responseBody = response.body?.string() ?: return@withContext true
                if (!response.isSuccessful) return@withContext true

                val answer = JSONObject(responseBody)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()
                    .lowercase()

                answer.startsWith("yes")
            } catch (e: Exception) {
                Log.e("WiM", "Falcon validation error: ${e.message}")
                true // on error, trust the reconstruction
            }
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
