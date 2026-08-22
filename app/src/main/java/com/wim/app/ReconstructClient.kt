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

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Register instructions, one per tone.
     *
     * These were abstract ("clean, clear business language", "minor rephrasing
     * is OK") and the models read them as permission to leave the register
     * alone. Measured on device 2026-07-30: professional returned "Hey, so I'm
     * gonna need the report by Friday afternoon" — a greeting and two
     * colloquialisms intact, because nothing in the rule said to remove them
     * and "contractions acceptable" said the opposite.
     *
     * Each rule now names the specific things to change, with examples. Vague
     * register instructions do not survive a prompt that also says "preserve
     * the speaker's words".
     */
    val TONE_RULES = mapOf(
        "formal" to (
            "Formal written register. Complete sentences only. NO contractions — write " +
            "\"I will\", \"do not\", \"cannot\". NO colloquialisms, NO slang, NO " +
            "greetings or address terms — drop \"hey\", \"hey man\", \"hi\", \"yo\", " +
            "\"dude\", \"guys\" entirely. Open on the substance. Full punctuation."
        ),
        "professional" to (
            "Professional business register — the way this would be written to a colleague " +
            "or client, not spoken to a friend. Apply ALL of the following: " +
            "(1) DROP casual greetings and address terms — \"hey\", \"hey man\", \"hi " +
            "there\", \"yo\", \"dude\", \"man\", \"guys\". Start with the substance. " +
            "(2) EXPAND spoken contractions of intent — \"gonna\" to \"going to\", " +
            "\"wanna\" to \"want to\", \"gotta\" to \"need to\", \"kinda\" to " +
            "\"somewhat\", \"lemme\" to \"let me\". Ordinary contractions (\"I'll\", " +
            "\"it's\", \"we're\") are fine. " +
            "(3) DROP discourse openers that carry no content — a leading \"so\", " +
            "\"well\", \"okay\", \"look\", \"I mean\". " +
            "(4) Replace vague filler nouns with the specific one when the speaker made it " +
            "clear — \"the thing\" to what the thing is. " +
            "Example: \"hey man I'm gonna need that report by Friday\" becomes \"I'll need " +
            "the report by Friday.\" " +
            "This is a rewrite of REGISTER only — the request, the facts, the names, the " +
            "numbers, the deadline and the speaker's forcefulness all stay exactly as spoken."
        ),
        "casual" to (
            "Natural spoken rhythm — how this person actually talks, cleaned up. " +
            "Contractions expected. KEEP greetings and address terms the speaker used " +
            "(\"hey\", \"hey man\") — they are part of the voice, not filler. Strip " +
            "disfluencies and fix obvious errors only. Do not formalize, do not add " +
            "business phrasing, do not lengthen."
        ),
        "friend" to (
            "Relaxed and conversational. Contractions expected. Sentence fragments are fine " +
            "when natural. KEEP greetings, address terms, slang, and personality intact — " +
            "\"hey man\", \"yeah\", \"gonna\" all stay. Strip only disfluencies. If this " +
            "reads like a work email, it is wrong."
        ),
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
     * Ceilings on the profile lists injected into the L3 prompt.
     *
     * Every other profile block in the same prompt is bounded — trigger words
     * at 10, rejection history and style examples at their own recent-N — while
     * vocabulary and corrections went in whole, straight from ProfileManager,
     * which caps neither. Both grow for the life of the install and corrections
     * grow on their own, so a long-running profile quietly inflates every L3
     * request and dilutes the instructions the model actually needs to follow.
     *
     * Set high enough that a realistic profile is never touched, and truncation
     * is logged rather than silent — dropping the speaker's own terms without
     * saying so is the worse failure of the two.
     */
    private const val MAX_PROMPT_VOCABULARY = 60
    private const val MAX_PROMPT_CORRECTIONS = 40

    /**
     * The L3 profile-terms block: the speaker's vocabulary and their learned
     * transcription corrections, as the reconstruction prompt sees them.
     *
     * Pulled out of the prompt's buildString so it can be tested. The bug that
     * prompted the extraction was invisible from every test the file had, since
     * the prompt is assembled inside a private suspend function that no test
     * can reach: the vocabulary header was emitted whenever EITHER list had
     * entries, while the body printed only the vocabulary. A speaker with
     * corrections and no vocabulary got a header with nothing under it and the
     * corrections list on the following line.
     *
     * Returns "" when both lists are empty, so the caller can append it blind.
     * Pure — no android.util.Log, which is unmocked in this module's unit
     * tests. Truncation is logged by the caller.
     */
    internal fun buildProfileTermsBlock(
        vocabulary: List<String>,
        corrections: Map<String, String>,
    ): String = buildString {
        if (vocabulary.isNotEmpty()) {
            // "prefer these spellings" licensed vocabulary mugging: the model
            // read "cut me off", saw "Git" on the list, and pasted "Git me
            // off" (live 2026-08-04 14:09, inside a dropped aside no less).
            // The list restores GARBLED words; it never replaces clear ones.
            append("\n\nSpeaker's known vocabulary — use ONLY to restore a garbled or misheard word to its intended form: ")
            append(vocabulary.take(MAX_PROMPT_VOCABULARY).joinToString(", "))
            append("\nNEVER replace a clearly-transcribed ordinary word with a vocabulary word. \"cut\" stays \"cut\" even though \"Git\" is on the list.")
        }
        if (corrections.isNotEmpty()) {
            append("\n\nKnown transcription errors to correct: ")
            append(
                corrections.entries.take(MAX_PROMPT_CORRECTIONS)
                    .joinToString(", ") { (wrong, right) -> "\"$wrong\" -> \"$right\"" }
            )
        }
    }

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
        audiencePackage: String? = null,
        wavFile: java.io.File? = null,
        whisperSegments: List<WhisperClient.TimedSegment> = emptyList(),
        scriptPrepContext: String = ScriptPrepStore.activeContext()
    ): ReconstructResult {
        // The learning state machine that lived here is OFF as of 2026-08-22.
        //
        // It read a fresh dictation as an implicit ACCEPT of the previous
        // reconstruction — "no regenerate fired, so they kept it" — and
        // promoted that pair into StyleExamples as a positive example. That
        // inference is only sound while rejecting is possible. Regenerate was
        // removed the same day at the operator's instruction, so nothing can
        // ever be rejected again: every output, including ones the speaker
        // hated and deleted by hand, would be promoted as a model of their
        // voice and fed back into the next prompt. Silence stops meaning yes
        // the moment no becomes unsayable.
        //
        // RejectionStore and StyleExamples keep their stored contents and are
        // still read into the L3 prompt below — what a speaker accepted or
        // rejected while the button existed is still true. Only the writing
        // stops. Restore both halves together if regenerate ever returns.

        // Build acoustic context before routing so backend and direct L4 share
        // the same personal-baseline behavior.
        var prosodicSummary: ProsodicAnalyzer.SessionSummary? = null
        val prosodicContext = if (layer >= 4 && wavFile != null && whisperSegments.isNotEmpty()) {
            val features = ProsodicAnalyzer.extractFromWav(wavFile, whisperSegments)
            prosodicSummary = ProsodicAnalyzer.summarize(features)
            ProsodicAnalyzer.buildProsodicContext(features, WimApp.profile().prosodicBaseline())
        } else ""

        // Routing: every signed-in layer uses the authenticated backend. The
        // backend supports L4 Sonnet extended thinking, so release builds do
        // not need OpenAI or Anthropic secrets stored on the phone.
        val idToken = AuthManager.getIdToken()
        val anthropicKey = WimApp.anthropicApiKey()
        if (layer >= 4 && idToken == null && anthropicKey.isBlank()) {
            android.util.Log.w("WiM", "L4 requested but no Anthropic key — downgrading to GPT-4o (no Sonnet ET)")
            com.wim.app.ConsoleLog.log("L4 → GPT-4o downgrade (no Anthropic key on this device)", "warn")
        }
        // A build carrying its own keys must never fall into the backend, even
        // when the user happens to be signed in — the backend enforces
        // entitlement and quota, which is exactly what this build exists to
        // bypass. Blank keys everywhere else make this a no-op for the normal
        // release.
        val result = if (idToken != null && !WimApp.hasEmbeddedKeys()) {
            reconstructViaBackend(rawText, tone, layer, situation, vocabulary, corrections, fillerWords, triggerWords, onsetWeights, covertProfile, idToken, whisperLowConf, whisperDisagreements, speechSeverityMod, precedingContext, languageCode, previousOutputs, compressionRatioNote, audiencePackage, scriptPrepContext, prosodicContext)
        } else {
            // Developer-only direct fallback. Release builds return blank keys
            // and require the authenticated backend for every cloud layer.
            if (idToken == null) {
                // M9: surface the reroute. A signed-in user whose token refresh
                // failed silently drops off the backend onto the device path —
                // previously invisible.
                android.util.Log.w("WiM", "No auth token — using device-direct reconstruction (signed out or token refresh failed)")
                com.wim.app.ConsoleLog.log("Auth token unavailable — using device-direct path", "warn")
            }
            reconstructDirect(rawText, tone, layer, situation, vocabulary, corrections, triggerWords, onsetWeights, precedingContext, languageCode, previousOutputs, compressionRatioNote, audiencePackage, scriptPrepContext, prosodicContext, whisperLowConf)
        }

        prosodicSummary?.let { WimApp.profile().recordProsodic(it) }

        // Nothing stages a pending pair any more — see the note above. The
        // pair had no reader left once the accept/reject decision was gone.
        return result
    }

    /**
     * Silent-block bridging: given a partial utterance captured when the speaker
     * froze mid-sentence, return up to [n] completion candidates (full sentences)
     * the bubble can show as a tap row. Mirrors the desktop
     * complete_partial_candidates() and the wim-reconstruct `complete_partial`
     * action. Fast GPT-4o on both paths — the speaker is frozen, latency beats
     * depth. Returns [] on any failure (caller treats empty as a no-op).
     *
     * NOTE: foundation only — not yet consumed by BubbleService. The bubble
     * tap-row overlay + L4 wiring is the remaining piece.
     */
    suspend fun completePartial(
        partialText: String,
        tone: String = "casual",
        languageCode: String = "en",
        n: Int = 3
    ): List<String> = withContext(Dispatchers.IO) {
        val partial = partialText.trim()
        if (partial.isBlank()) return@withContext emptyList()

        val idToken = AuthManager.getIdToken()
        if (idToken != null) {
            return@withContext try {
                completePartialViaBackend(partial, tone, languageCode, idToken, n)
            } catch (e: Exception) {
                Log.w("WiM", "completePartial backend failed: ${e.message?.take(120)}")
                emptyList()
            }
        }

        // Developer-only direct fallback; releases require sign-in/backend.
        val apiKey = WimApp.apiKey()
        if (apiKey.isBlank()) return@withContext emptyList()
        return@withContext try {
            val sys = buildCompletionPrompt(tone, languageCode, n)
            parseCandidates(reconstructWithGpt4o(sys, partial, apiKey, 0.5, maxTokens = 200), n)
        } catch (e: Exception) {
            Log.w("WiM", "completePartial direct failed: ${e.message?.take(120)}")
            emptyList()
        }
    }

    private fun completePartialViaBackend(
        partial: String, tone: String, languageCode: String, idToken: String, n: Int
    ): List<String> {
        val body = JSONObject().apply {
            put("action", "complete_partial")
            put("partial_text", partial)
            put("tone", tone)
            put("language_code", LangPackHelper.normalizeLangCode(languageCode))
        }
        val request = Request.Builder()
            .url(BackendConfig.URL)
            .addHeader("Authorization", "Bearer $idToken")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val responseBody = http.newCall(request).execute().use { response ->
            val b = response.body?.string() ?: throw RuntimeException("Empty response from backend")
            if (!response.isSuccessful) throw RuntimeException("Backend error ${response.code}: $b")
            b
        }
        val arr = JSONObject(responseBody).optJSONArray("candidates") ?: JSONArray()
        return (0 until arr.length()).map { arr.getString(it).trim() }
            .filter { it.isNotBlank() }.take(n)
    }

    private fun buildCompletionPrompt(tone: String, languageCode: String, n: Int): String {
        val toneRule = TONE_RULES[tone] ?: TONE_RULES["casual"]!!
        val langLine = if (languageCode.isNotBlank() && languageCode != "en")
            "\nThe speaker is talking in language code '$languageCode'. Write the completions in that language.\n" else ""
        return "You help someone who just froze mid-sentence (a speech block) finish what " +
            "they were trying to say. You are given the partial utterance they managed to get " +
            "out before freezing. Propose exactly $n natural, distinct ways the sentence could end.\n\n" +
            "Tone: $toneRule\n$langLine" +
            "Hard rules:\n" +
            "- Output exactly $n lines, one option per line.\n" +
            "- Each line is the COMPLETE sentence from the start — include the speaker's own words " +
            "(cleaned of fillers and disfluent repetitions) followed by a natural ending. Do NOT output only the continuation.\n" +
            "- No numbering, no bullets, no quotes, no preamble, no commentary.\n" +
            "- Each line is a sentence the speaker could say verbatim, under ~22 words.\n" +
            "- Stay faithful to the partial intent; do not invent unrelated content."
    }

    private fun parseCandidates(raw: String, n: Int): List<String> =
        raw.lines()
            .map { it.replace(Regex("^[\\s\\-\\d.)]+"), "").trim().trim('"').trim() }
            .filter { it.isNotBlank() }
            .take(n)

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
        audiencePackage: String? = null,
        scriptPrepContext: String = "",
        prosodicContext: String = ""
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
            if (scriptPrepContext.isNotBlank()) {
                put("script_prep", scriptPrepContext.take(ScriptPrepStore.MAX_CHARS))
            }
            if (prosodicContext.isNotBlank()) put("prosodic_context", prosodicContext)
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
            .url(BackendConfig.URL)
            .addHeader("Authorization", "Bearer $idToken")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val responseBody = http.newCall(request).execute().use { response ->
            val body = response.body?.string()
                ?: throw RuntimeException("Empty response from backend")
            if (!response.isSuccessful) {
                throw RuntimeException("Backend error ${response.code}: $body")
            }
            body
        }

        val json = JSONObject(responseBody)
        val reconMs = System.currentTimeMillis() - t0
        Log.i("WiM", "Reconstruction route: backend (wim-reconstruct), L$layer, ${reconMs}ms")

        if (!json.has("clean")) {
            Log.e("WiM", "Backend response missing 'clean' field — keys=${json.keys().asSequence().toList()}")
            throw RuntimeException("Backend response missing 'clean' field — wim-reconstruct contract broken")
        }
        val cleanText = json.getString("clean")
        val backendFalconOk = json.optBoolean("falcon_ok", true)

        // The backend reports its Layer 3 profile-term work and Layer 2 retry
        // count (wim-reconstruct-00032-zit). Parse and surface them instead of
        // dropping them on the floor — the operator reads these in the console.
        val backendAttempts = json.optInt("rewrite_attempts", 1)
        val backendMatches = mutableListOf<String>()
        json.optJSONArray("profile_matches")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                backendMatches.add(
                    "\"${o.optString("heard")}\" -> \"${o.optString("term")}\" [${o.optString("kind")}]"
                )
            }
        }
        if (backendMatches.isNotEmpty()) {
            Log.i("WiM", "Backend L3 profile terms applied: ${backendMatches.joinToString("; ")}")
            com.wim.app.ConsoleLog.log("Profile terms: ${backendMatches.joinToString("; ")}", "info")
        }
        if (backendAttempts > 1) {
            Log.i("WiM", "Backend reconstruction took $backendAttempts attempts")
            com.wim.app.ConsoleLog.log("Reconstruction took $backendAttempts attempts", "info")
        }
        json.optJSONObject("meaning_guard")?.let { mg ->
            if (!mg.optBoolean("ok", true)) {
                Log.w(
                    "WiM",
                    "Backend meaning guard flagged: lost=${mg.optJSONArray("lost")} " +
                        "invented=${mg.optJSONArray("invented")} unchanged=${mg.optBoolean("unchanged", false)}"
                )
            }
        }

        // CRITICAL: the backend (wim-reconstruct on bakers-agent) does NOT
        // return riskFlags in its response — verified by reading the server
        // source at lavrentiy/wim/api/main.py / reconstruct.py 2026-05-31.
        // The earlier "trust backend's safety decisions" comment was wrong;
        // it left the majority signed-in path with zero protection against
        // critical-token drops (numbers, dates, currency). Run the
        // client-side guard here so backend + device-direct paths (:623-631)
        // are in parity.
        val clientRiskFlags = computeRiskFlags(rawText, cleanText)
        val falconOk = if (clientRiskFlags.contains("critical_token_lost")) false else backendFalconOk
        // Same rule as the device-direct path: a refusal falls back to the raw
        // transcript, so strip the caption boilerplate a block leaves behind
        // before it reaches the user's text field.
        val finalText = if (falconOk) cleanText else DisfluencyFilter.stripCaptionArtifacts(rawText)

        if (!falconOk) {
            Log.w("WiM", "Backend reconstruction rejected (server falcon=$backendFalconOk, client flags=$clientRiskFlags), using raw text")
            com.wim.app.ConsoleLog.log(
                "Reconstruction refused (couldn't rebuild without inventing) — pasting your words as heard",
                "warn"
            )
        }

        ReconstructResult(
            cleanText = finalText,
            reconMs = reconMs,
            riskFlags = clientRiskFlags,
            falconApproved = falconOk,
            rewriteAttempts = backendAttempts,
            profileMatches = backendMatches
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
        audiencePackage: String? = null,
        scriptPrepContext: String = "",
        prosodicContext: String = "",
        whisperLowConf: List<WhisperClient.LowConfidenceSegment> = emptyList()
    ): ReconstructResult =
        withContext(Dispatchers.IO) {
            val apiKey = WimApp.apiKey()
            if (apiKey.isBlank()) throw IllegalStateException("No API key configured")

            val t0 = System.currentTimeMillis()
            Log.i("WiM", "Reconstruction route: device-direct, L$layer")

            // L3 profile-term recovery (parity with the backend's
            // profile_terms.apply_profile_terms): exact corrections first, then
            // phonetic recovery of correction pairs inside low-confidence ASR
            // spans and multiword vocabulary phrases. The corrected text is
            // what reconstruction sees and what the guard protects.
            var effectiveRaw = rawText
            var profileMatches: List<ProfileTermRecovery.Match> = emptyList()
            if (layer >= 3) {
                val lowConfTexts = whisperLowConf.mapNotNull { seg -> seg.text.takeIf { it.isNotBlank() } }
                val (recovered, applied) = ProfileTermRecovery.apply(
                    rawText, corrections, vocabulary, lowConfTexts
                )
                effectiveRaw = recovered
                profileMatches = applied
                if (applied.isNotEmpty()) {
                    Log.i("WiM", "L3 profile terms applied: ${applied.joinToString("; ")}")
                    com.wim.app.ConsoleLog.log(
                        "Profile terms: ${applied.joinToString("; ")}", "info"
                    )
                }
            }
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
                // Output-language pin (2026-08-05). Without it the model
                // drifted home to English: an 11-language sweep at L2 came
                // back English for es/ru/it/ar/zh and half-German for de —
                // anchors like "jueves"/"четверг" translated away and lost.
                // The style rules below are written in English; the pin must
                // outrank their pull. Stated for EVERY language, English
                // included, so mixed-language input can't flip the frame.
                run {
                    val langCode = LangPackHelper.normalizeLangCode(languageCode)
                    val langName = LangPackHelper.languageName(langCode)
                    append("THE SPEAKER'S LANGUAGE IS $langName ($langCode). YOUR ENTIRE OUTPUT MUST BE IN $langName. ")
                    append("NEVER translate the message into another language. Every rule below applies IN $langName. ")
                    append("Weekdays, times, and names stay in $langName exactly as the speaker said them. ")
                    append("The DROPPED line's content stays in $langName; only the marker word 'DROPPED:' itself is always English. ")
                }
                append("You are NOT a chatbot. Do NOT respond to the content. Do NOT ask questions. Do NOT add commentary. ")
                append("If the input is a fragment or incomplete sentence, return it cleaned up as-is — do NOT say 'your message got cut off'. ")
                append("Tone: $tone — this is a REQUIREMENT, not a hint. Rewrite the register to match it. ")
                append(toneRule)
                append(" Strip filler words (including non-English fillers like \u044d, \u043d\u0443, \u044d\u044d).")
                append(" Preserve FULL meaning. Do not summarize or add information.")
                // The envelope rule (design call, George 2026-08-04): the axis
                // for profanity is never the word itself — it is who the words
                // are AIMED at. Asides addressed to bystanders are dropped in
                // every tone; heat inside the message follows the tone. The
                // old unconditional "preserve EXACTLY" clause fought the
                // "Tone is a REQUIREMENT" clause and the model coin-flipped.
                // Backend prompt (wim-reconstruct) needs this same change —
                // keep the two in sync, same contract as MeaningGuard.
                append(" INTERRUPTION RULE (applies in EVERY tone): mid-dictation speech aimed at someone OTHER than the message's recipient — a child, a pet, another driver, someone else in the room (\"shut up\", \"not now, honey\", a road-rage outburst) — is NOT message content. Drop it entirely and join the message cleanly around the gap.")
                append(" FORMAT: after the reconstructed message, output ONE final line — the LAST line of your reply, nothing after it — exactly as \"DROPPED: <the interruption words you removed, verbatim>\", or \"DROPPED: none\" if you removed no interruptions. Keep the bin on that single line. The DROPPED line is bookkeeping for an integrity checker; it is never part of the message.")
                if (tone.lowercase() in setOf("professional", "formal")) {
                    append(" Profanity WITHIN the message itself: translate its intensity into this register instead of deleting it — \"this is fucking great\" becomes \"this is genuinely excellent\", \"that's bullshit\" becomes \"that is unacceptable\". The emphasis survives; the wording dresses for the reader.")
                } else {
                    append(" Do NOT censor, sanitize, or soften the speaker's language WITHIN the message. Profanity, slang, harsh words, and strong language must be preserved EXACTLY as spoken. If the speaker said 'fuck', output 'fuck'. Do not substitute softer synonyms.")
                    append(" Your job is to clean up SPEECH ARTIFACTS, not to edit the speaker's word choices.")
                }
                append(" Output ONLY the reconstructed text, nothing else.")

                // Accessibility look-behind: text already sitting in the field before the dictation.
                // Use as context to disambiguate the speaker's current phrase. Do not echo it back.
                if (precedingContext.isNotBlank()) {
                    append("\n\nText already in the target field before this dictation (CONTEXT — do NOT include in output, do NOT repeat, do NOT acknowledge):\n\"")
                    append(precedingContext.takeLast(500))
                    append("\"\nUse this only to disambiguate words and match the speaker's running topic.")
                }
                if (scriptPrepContext.isNotBlank()) {
                    append("\n\nSCRIPT PREP CONTEXT (provided before dictation; use only to resolve names, jargon, topic, and intended phrasing; never repeat or acknowledge unless actually dictated):\n")
                    append(scriptPrepContext.take(ScriptPrepStore.MAX_CHARS))
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
                    // The condition used to be `vocabulary.isNotEmpty() ||
                    // corrections.isNotEmpty()` while the body appended only the
                    // vocabulary. A speaker with corrections and no vocabulary —
                    // the normal early state, since corrections are learned
                    // automatically and vocabulary is typed by hand — got the
                    // header "Speaker's known vocabulary:" followed by nothing,
                    // with the corrections list on the next line. The model has
                    // every reason to read those corrections as the vocabulary.
                    append(buildProfileTermsBlock(vocabulary, corrections))
                    if (vocabulary.size > MAX_PROMPT_VOCABULARY) {
                        Log.i("WiM", "L3 vocabulary truncated for prompt: ${vocabulary.size} → $MAX_PROMPT_VOCABULARY")
                    }
                    if (corrections.size > MAX_PROMPT_CORRECTIONS) {
                        Log.i("WiM", "L3 corrections truncated for prompt: ${corrections.size} → $MAX_PROMPT_CORRECTIONS")
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
                    append("\n- DO NOT soften, sanitize, or censor profanity or strong language. If the speaker swore, the swearing stays.")
                    append("\n- The rule above protects the speaker's CONTENT — what they said and how forcefully. It does NOT override the tone above. Greetings, sign-offs, filler address terms ('hey man', 'dude', 'yo'), and general register ARE yours to change when the tone calls for it. Measured 2026-07-29: this rule was written to stop the model sanitizing profanity, and it was silently blocking every tone change too — 'professional' returned 'Hey man, can you send me that thing' unaltered.")
                    append("\n- DO NOT summarize away content; restate, don't compress.")
                    append("\n- TREAT unfamiliar, invented-looking, or single-syllable unrecognized words as INTENTIONAL slang, brand names, or in-group vocabulary. Do not substitute them, do not assume transcription error, do not 'fix' them. Examples of what to PRESERVE without modification: 'rizz', 'bussin', 'no cap', 'mid', 'delulu', 'skibidi', 'ick', 'fanum tax', any proper noun the speaker emphasized, any startup or tool name the speaker said clearly. This protects unusual NOUNS the speaker chose deliberately — it does not freeze the sentence's register or its greeting.")

                    // SELF-CORRECTION CANONICAL OVERWRITE — when the speaker
                    // says "I mean", "actually", "no wait", etc., what FOLLOWS
                    // is canonical and what came BEFORE should be discarded.
                    // Crucial for stutterers (covert revision is core stuttering
                    // behavior — replacing feared words mid-sentence). 'I mean'
                    // was intentionally removed from the discourse-marker drop
                    // list above so this rule wins.
                    // Emphatic reduplication is meaning, not stuttering: "no no,
                    // that's not what I meant" loses force — and at worst reads
                    // as agreement — when flattened to "That's not what I meant."
                    // Observed at L2 in the 2026-07-29 corpus (case 10); L4 was
                    // already protected because its prompt injects the language
                    // pack's natural-repeat list. L2/L3 get the same protection
                    // here, in-language when a pack exists.
                    append("\n\nEMPHATIC REPETITION — DO NOT STRIP:")
                    append("\nA doubled short word used for emphasis or contradiction ('no no', 'yes yes', 'wait wait') is meaningful speech, not a repetition disfluency. Keep it. Strip only repetitions that carry no emphasis.")
                    val l2NaturalRepeats = LangPackHelper.getLangNaturalRepeats(
                        LangPackHelper.normalizeLangCode(languageCode)
                    )
                    if (l2NaturalRepeats.isNotEmpty()) {
                        append("\nIn this speaker's language these are emphatic, never disfluency: ${l2NaturalRepeats.joinToString(", ")}")
                    }

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

                    // TONE ANCHOR — must be the LAST thing L4 reads.
                    //
                    // The tone requirement is stated once, ~90 lines earlier, at
                    // the top of the shared prompt. Everything after it is
                    // clinical instruction that ends with "prefer conservative
                    // cleanup over aggressive rewriting" — which the model reads
                    // last and applies to the register as well as the meaning.
                    // The scoping rule that fixed this for L2/L3 lives inside an
                    // `if (layer in 2..3)` block, so the clinical layer never saw
                    // it: L4 returned the same sentence for "casual" and
                    // "professional" while L2 obeyed both.
                    //
                    // Restate the requirement here, and scope "conservative" to
                    // what it was written to protect — meaning, numbers, names,
                    // profanity — explicitly NOT the register.
                    append("\n\nTONE — read this last, it outranks the conservatism guidance above.")
                    append("\nRequired tone: $tone. ")
                    append(toneRule)
                    append("\n\"Conservative cleanup\" above governs MEANING: do not invent, do not summarize,")
                    append(" do not soften profanity, do not alter numbers, dates, amounts, or names.")
                    append(" It does NOT govern register. Greetings, sign-offs, address terms ('hey man',")
                    append(" 'dude', 'yo'), contractions, and overall formality ARE yours to change, and you")
                    append(" MUST change them when the required tone calls for it.")
                    append("\nA disfluency-clean sentence in the wrong register is a failed reconstruction.")
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
            //
            // ET-extinction fallback: when Sonnet exhausts its 8K thinking
            // budget without emitting a text block (rare on short utterances
            // but possible on long, ambiguous, or adversarial inputs), the
            // request would otherwise propagate a RuntimeException to the
            // caller and the user would see the raw input as the final
            // output. Fall back to GPT-4o here so an L4 user at least gets
            // an L3-quality reconstruction instead of nothing.
            val anthropicKey = WimApp.anthropicApiKey()
            val useSonnet = layer >= 4 && anthropicKey.isNotBlank()

            val finalSystemPrompt = if (prosodicContext.isNotBlank()) {
                "$systemPrompt\n\n$prosodicContext"
            } else systemPrompt

            var sonnetDowngraded = false
            var rewriteAttempts = 1
            var loopGuard: MeaningGuard.GuardResult? = null
            val cleanText = if (useSonnet) {
                // Layer 4 gets the same guard-and-retry loop as 2/3. It had
                // none until 2026-07-29 — the clinical layer, where the
                // speaker's disfluency is heaviest and reconstruction is most
                // aggressive, was the one layer shipping straight to the text
                // field unchecked. "Smarter model, no checker needed" did not
                // survive contact: on-device it turned "four thousand two
                // hundred" into $1,200 and invented a Tuesday, three runs
                // running.
                try {
                    val messages = JSONArray().apply {
                        put(JSONObject().put("role", "user").put("content", effectiveRaw))
                    }
                    val outcome = MeaningGuard.runRewriteLoop(
                        rawText = effectiveRaw,
                        vocabulary = vocabulary,
                        rewriteOnce = { _ ->
                            reconstructWithSonnetMessages(finalSystemPrompt, messages, anthropicKey)
                        },
                        appendRepair = { candidate, instruction ->
                            messages.put(JSONObject().put("role", "assistant").put("content", candidate))
                            messages.put(JSONObject().put("role", "user").put("content", instruction))
                        },
                        onRetry = { attempt, _, g ->
                            Log.w(
                                "WiM",
                                "L4 rewrite rejected on attempt $attempt: lost=${g.lost} invented=${g.invented} — retrying"
                            )
                            com.wim.app.ConsoleLog.log("L4 rewrite attempt $attempt rejected — retrying", "warn")
                        },
                    )
                    rewriteAttempts = outcome.attempts
                    loopGuard = outcome.guard
                    if (rewriteAttempts > 1) {
                        com.wim.app.ConsoleLog.log("L4 took $rewriteAttempts attempts", "info")
                    }
                    outcome.cleanText
                } catch (e: Exception) {
                    Log.w("WiM", "Sonnet L4 failed (${e.message?.take(120)}), falling back to GPT-4o")
                    sonnetDowngraded = true
                    // GPT-4o gets the guard loop here too. This fallback used to
                    // be a bare single-shot call, so a Sonnet timeout mid-call
                    // dropped the user from the most-checked path to the
                    // least-checked one in a single step.
                    val outcome = gpt4oRewriteLoop(
                        finalSystemPrompt, effectiveRaw, apiKey, temperature, vocabulary, layer
                    )
                    rewriteAttempts = outcome.attempts
                    loopGuard = outcome.guard
                    outcome.cleanText
                }
            } else if (layer >= 2) {
                // L2/L3 must always reconstruct — empty, unchanged, or
                // guard-rejected output is retried with exact feedback, up to
                // MAX_REWRITE_ATTEMPTS. Port of the backend's
                // run_layer2_rewrite (6a0ad9a); short inputs get the same
                // treatment — there is no length gate on this path.
                //
                // The gate was `layer in 2..3`, so an L4 request that could not
                // reach Sonnet — no key and not signed in — fell past it to the
                // bare single-shot call below. That made L4-without-Sonnet the
                // only reconstruction route in the app with no guard and no
                // retry: the user picked the most careful layer and got less
                // checking than L2 gives. It now takes this loop, and
                // sonnetDowngraded is set so the existing "L4 unavailable"
                // toast actually fires instead of the swap happening in silence.
                if (layer >= 4) {
                    sonnetDowngraded = true
                    Log.w("WiM", "L4 requested without Sonnet access — reconstructing with GPT-4o")
                    com.wim.app.ConsoleLog.log("L4 unavailable on this device — using GPT-4o", "warn")
                }
                val outcome = gpt4oRewriteLoop(
                    finalSystemPrompt, effectiveRaw, apiKey, temperature, vocabulary, layer
                )
                rewriteAttempts = outcome.attempts
                loopGuard = outcome.guard
                if (rewriteAttempts > 1) {
                    com.wim.app.ConsoleLog.log("Reconstruction took $rewriteAttempts attempts", "info")
                }
                outcome.cleanText
            } else {
                reconstructWithGpt4o(finalSystemPrompt, effectiveRaw, apiKey, temperature)
            }

            val riskFlags = computeRiskFlags(effectiveRaw, cleanText).toMutableList()
            loopGuard?.let { g ->
                if (g.lost.isNotEmpty()) riskFlags.add("guard_lost")
                if (g.invented.isNotEmpty()) riskFlags.add("guard_invented")
                if (g.unchanged) riskFlags.add("guard_unchanged")
            }
            val fastMode = com.wim.app.WimApp.prefs.getBoolean("fast_mode", false)
            val falconOk = if (fastMode) {
                true // FAST mode: skip integrity rollback
            } else if (riskFlags.contains("critical_token_lost")) {
                false
            } else if (loopGuard != null && !loopGuard.ok && !loopGuard.unchanged) {
                // Retries exhausted with a guard violation (lost/invented
                // anchors) — fall back to the (profile-corrected) raw text.
                // An unchanged-only failure keeps the model output: identical
                // words are safe words, just not the rewrite we wanted.
                false
            } else {
                falconValidate(effectiveRaw, cleanText, layer, tone, onsetWeights, languageCode)
            }

            val reconMs = System.currentTimeMillis() - t0
            // Refusing to fabricate must not mean pasting caption boilerplate.
            // When the guard rejects every candidate — which is what happens
            // when a block destroyed a word and the model kept inventing a
            // replacement — the raw transcript we fall back to is the one that
            // still contains "thanks for watching". Strip it here.
            val finalText = if (falconOk) cleanText else DisfluencyFilter.stripCaptionArtifacts(effectiveRaw)

            if (!falconOk) {
                Log.w("WiM", "Integrity check rejected reconstruction, using raw text. Flags: $riskFlags")
                com.wim.app.ConsoleLog.log(
                    "Reconstruction refused (couldn't rebuild without inventing) — pasting your words as heard",
                    "warn"
                )
            }

            ReconstructResult(
                cleanText = finalText,
                reconMs = reconMs,
                riskFlags = riskFlags,
                falconApproved = falconOk,
                downgradedFromL4 = sonnetDowngraded,
                rewriteAttempts = rewriteAttempts,
                profileMatches = profileMatches.map { it.toString() }
            )
        }

    /**
     * Self-correction markers. When the speaker retracts a value
     * ("meeting at 3pm, I mean 4pm"), the reconstruction is SUPPOSED to drop
     * the retracted token — that is the covert-revision behavior WiM exists to
     * perform. Treating the dropped "3" as a lost critical token and reverting
     * to raw would re-insert the mistake. So when a marker is present we trust
     * the model and skip the critical-token revert.
     */
    private val SELF_CORRECTION_MARKERS = Regex(
        """\b(i mean|i meant|no wait|scratch that|or rather|correction)\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Normalize a numeric token to its bare value so pure reformatting
     * ("$5." ↔ "5", "1,000" ↔ "1000", "90%" ↔ "90") doesn't read as a dropped
     * token. Keeps a decimal point so "3.5" and "35" stay distinct.
     */
    private fun normalizeNumericToken(tok: String): String =
        tok.trim().removePrefix("$").removeSuffix("%").removeSuffix(".").replace(",", "")

    /**
     * Check that numbers, percentages and dollar amounts survived reconstruction.
     *
     * A self-correction legitimately drops the value it replaced — "the meeting
     * is at 3 pm, I mean 4 pm" is allowed to lose the 3. That exemption used to
     * be a blanket `if (SELF_CORRECTION_MARKERS.containsMatchIn(raw)) return
     * true`: one "I mean" anywhere in the utterance switched the numeric guard
     * off for every number in it. Self-corrections are constant in disfluent
     * speech, which is the speech this product exists for, so the guard was
     * absent from a large share of real utterances — and losing an amount is
     * the documented L4 failure that motivated the guard ("four thousand two
     * hundred" arriving as $1,200, three runs running).
     *
     * The exemption is now scoped to the value actually being corrected: for
     * each self-correction, the number immediately preceding it, and only when
     * another number follows the correction to take its place. "Send 5 copies
     * and 10 folders, I mean 12 folders" may lose the 10 and nothing else — the
     * 5 is still protected.
     */
    private fun checkCriticalTokens(raw: String, clean: String): Boolean {
        val tokenPattern = Regex("""\$?\d[\d,.]*%?""")
        val cleanTokens = tokenPattern.findAll(clean)
            .map { normalizeNumericToken(it.value) }.filter { it.isNotEmpty() }.toSet()

        val rawTokens = tokenPattern.findAll(raw)
            .mapNotNull { m ->
                normalizeNumericToken(m.value).takeIf { it.isNotEmpty() }?.let { it to m.range.first }
            }
            .toList()

        val superseded = buildSet {
            for (marker in SELF_CORRECTION_MARKERS.findAll(raw)) {
                val replaced = rawTokens.lastOrNull { it.second < marker.range.first } ?: continue
                if (rawTokens.any { it.second > marker.range.last }) add(replaced.first)
            }
        }

        return rawTokens.all { (value, _) -> value in cleanTokens || value in superseded }
    }

    /**
     * Falcon validation — RETIRED 2026-05-10. Now a stub returning true;
     * the cross-provider validation pass was removed across both this
     * client and lavrentiy.py on the same day (Sonnet ET self-validates
     * at L4; L2/L3 GPT-4o quality was deemed sufficient without
     * cross-provider QA). Matches the lavrentiy.py:3420 stub.
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
        // sufficient without cross-provider QA. Matches lavrentiy.py:3420.
        return true
    }

    // buildFalconSystemPrompt, falconValidateGpt, falconValidateClaude removed 2026-05-10
    // Falcon retired — falconValidate() is now a stub returning true (lavrentiy.py:3420).

    /**
     * GPT-4o reconstruction with the guard-and-retry loop — empty, unchanged,
     * or guard-rejected output is retried with exact feedback, up to
     * MAX_REWRITE_ATTEMPTS. Port of the backend's run_layer2_rewrite. No length
     * gate: short inputs get the same treatment.
     *
     * Every GPT-4o reconstruction goes through here. The two L4 fallbacks —
     * no Sonnet key, and Sonnet throwing mid-call — each used to make a bare
     * single-shot call instead, so choosing the most careful layer and missing
     * Sonnet left you with no guard and no retry at all.
     */
    private fun gpt4oRewriteLoop(
        systemPrompt: String,
        effectiveRaw: String,
        apiKey: String,
        temperature: Double,
        vocabulary: List<String>,
        layer: Int,
    ): MeaningGuard.RewriteOutcome {
        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", systemPrompt))
            put(JSONObject().put("role", "user").put("content", effectiveRaw))
        }
        return MeaningGuard.runRewriteLoop(
            rawText = effectiveRaw,
            vocabulary = vocabulary,
            rewriteOnce = { attemptIndex ->
                // Retries drop to near-greedy sampling so the repair
                // instruction, not randomness, drives the change.
                val attemptTemp = if (attemptIndex == 0) temperature else minOf(temperature, 0.1)
                reconstructWithGpt4oMessages(messages, apiKey, attemptTemp)
            },
            appendRepair = { candidate, instruction ->
                messages.put(JSONObject().put("role", "assistant").put("content", candidate))
                messages.put(JSONObject().put("role", "user").put("content", instruction))
            },
            onDropped = { bin ->
                // The bin is diagnostics for the session console — the user
                // sees what the envelope rule removed, and it never pastes.
                com.wim.app.ConsoleLog.log("Dropped: \"${bin.take(120)}\"", "info")
            },
            onRetry = { attempt, candidate, g ->
                Log.w(
                    "WiM",
                    "L$layer rewrite rejected on attempt $attempt: lost=${g.lost} " +
                        "invented=${g.invented} unchanged=${MeaningGuard.isEffectivelyUnchanged(effectiveRaw, candidate)} — retrying"
                )
                com.wim.app.ConsoleLog.log("Rewrite attempt $attempt rejected — retrying", "warn")
            },
        )
    }

    /**
     * GPT-4o single-pass reconstruction. Kept for callers outside the
     * reconstruction path; the reconstruction path itself goes through
     * [gpt4oRewriteLoop].
     */
    private fun reconstructWithGpt4o(
        systemPrompt: String,
        rawText: String,
        apiKey: String,
        temperature: Double,
        maxTokens: Int = 1024,
    ): String {
        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", systemPrompt))
            put(JSONObject().put("role", "user").put("content", rawText))
        }
        return reconstructWithGpt4oMessages(messages, apiKey, temperature, maxTokens)
    }

    /**
     * GPT-4o call over an explicit message array — the retry loop appends
     * (assistant candidate, repair instruction) pairs to the same
     * conversation so the model sees exactly why its last attempt failed.
     */
    private fun reconstructWithGpt4oMessages(
        messages: JSONArray,
        apiKey: String,
        temperature: Double,
        maxTokens: Int = 1024,
    ): String {
        val body = JSONObject().apply {
            put("model", "gpt-4o-2024-11-20")
            put("messages", messages)
            put("temperature", temperature)
            put("max_tokens", maxTokens)
        }
        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val responseBody = http.newCall(request).execute().use { response ->
            val body = response.body?.string()
                ?: throw RuntimeException("Empty response from reconstruction API")
            if (!response.isSuccessful) {
                throw RuntimeException("Reconstruction API error ${response.code}: $body")
            }
            body
        }
        return JSONObject(responseBody)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
            .trim()
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
        return reconstructWithSonnetMessages(systemPrompt, messages, apiKey)
    }

    /**
     * Sonnet call over an explicit message array, so L4 can carry a repair
     * conversation the same way L2/L3 do.
     *
     * Until 2026-07-29 Layer 4 had no guard at all: the retry loop and the
     * rejection path covered layers 2 and 3 only, and L4 — the clinical layer,
     * where reconstruction is most aggressive and the speaker's disfluency is
     * heaviest — went straight from Sonnet to the user's text field with
     * nothing checking it. Found on-device: "wire four thousand two hundred to
     * Henderson before monday" came back as "$1,200 ... by Tuesday", three runs
     * running, unchallenged.
     */
    private fun reconstructWithSonnetMessages(
        systemPrompt: String,
        messages: JSONArray,
        apiKey: String,
    ): String {
        val thinkingConfig = JSONObject().apply {
            put("type", "enabled")
            put("budget_tokens", 8000)
        }
        val body = JSONObject().apply {
            put("model", "claude-sonnet-4-6")
            put("max_tokens", 16000)
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
        val responseBody = http.newCall(request).execute().use { response ->
            val body = response.body?.string()
                ?: throw RuntimeException("Empty response from Sonnet reconstruction API")
            if (!response.isSuccessful) {
                throw RuntimeException("Sonnet reconstruction API error ${response.code}: $body")
            }
            body
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
        val falconApproved: Boolean = true,
        val downgradedFromL4: Boolean = false,
        /** Model calls the reconstruction took (1 = accepted first try). */
        val rewriteAttempts: Int = 1,
        /** Human-readable L3 profile-term replacements that were applied. */
        val profileMatches: List<String> = emptyList()
    )
}
