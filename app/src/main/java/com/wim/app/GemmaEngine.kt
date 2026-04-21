package com.wim.app

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import java.io.File

/**
 * On-device LLM reconstruction via MediaPipe's tasks-genai API running a
 * Gemma 3 1B IT .task model.
 *
 * When [use_gemma_local] is true AND the model file is present, the L2/L3
 * reconstruction step in `BubbleService.processText()` routes here instead of
 * the cloud GPT-4o call in `ReconstructClient`. Same input/output shape —
 * raw ASR text in, reconstructed text out — so the downstream pipeline
 * (PhoneticMatcher, injection) doesn't care which engine did the work.
 *
 * Model distribution: downloaded to `filesDir/gemma-models/<name>.task` on
 * first enable. Not bundled in the APK. Interface is deliberately parallel
 * to VoskEngine so BubbleService can toggle between them with minimal churn.
 */
object GemmaEngine {

    private const val TAG = "WiM.Gemma"
    private const val MODEL_SUBDIR = "gemma-models"
    const val DEFAULT_MODEL_NAME = "gemma3-1b-it-int4.task"

    @Volatile private var llm: LlmInference? = null
    @Volatile private var modelLoadedPath: String? = null

    fun modelFile(context: Context): File =
        File(context.filesDir, "$MODEL_SUBDIR/$DEFAULT_MODEL_NAME")

    fun isReady(context: Context): Boolean {
        val f = modelFile(context)
        return f.exists() && f.isFile && f.length() > 1024
    }

    /**
     * Lazy-load the model. Returns true if ready (either already loaded or
     * successfully loaded now), false if model is missing or init failed.
     */
    @Synchronized
    fun ensureLoaded(context: Context): Boolean {
        val f = modelFile(context)
        if (!isReady(context)) {
            Log.w(TAG, "Model missing at ${f.absolutePath}")
            return false
        }
        if (llm != null && modelLoadedPath == f.absolutePath) return true
        try {
            val t0 = System.currentTimeMillis()
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(f.absolutePath)
                .setMaxTokens(512)
                .build()
            llm = LlmInference.createFromOptions(context, options)
            modelLoadedPath = f.absolutePath
            Log.i(TAG, "Loaded ${f.name} in ${System.currentTimeMillis() - t0}ms")
            return true
        } catch (e: Throwable) {
            Log.e(TAG, "Load failed: ${e.message}", e)
            llm = null
            modelLoadedPath = null
            return false
        }
    }

    /**
     * Run reconstruction on [rawText] with a short system-style prompt
     * matching the direct-path style from ReconstructClient. Synchronous —
     * caller is responsible for putting this on a background thread.
     *
     * Returns the reconstructed text, or null on failure (caller can fall
     * back to cloud GPT-4o).
     */
    fun reconstruct(
        context: Context,
        rawText: String,
        tone: String,
        layer: Int,
        situation: String,
        vocabulary: List<String> = emptyList(),
        corrections: Map<String, String> = emptyMap()
    ): String? {
        if (!ensureLoaded(context)) return null
        val inference = llm ?: return null

        val prompt = buildPrompt(rawText, tone, layer, situation, vocabulary, corrections)

        return try {
            val t0 = System.currentTimeMillis()
            val result = inference.generateResponse(prompt)
            val elapsedMs = System.currentTimeMillis() - t0
            Log.i(TAG, "Gemma reconstruct in ${elapsedMs}ms (${rawText.length}→${result.length} chars)")
            result.trim().ifBlank { null }
        } catch (e: Throwable) {
            Log.e(TAG, "Inference failed: ${e.message}", e)
            null
        }
    }

    /** Release native resources. Called from BubbleService.onDestroy. */
    fun release() {
        try { llm?.close() } catch (_: Throwable) {}
        llm = null
        modelLoadedPath = null
    }

    /**
     * Build a compact system+user prompt matching the style of the cloud
     * direct-path prompt. Terse because small LLMs handle short prompts
     * better than long ones.
     */
    private fun buildPrompt(
        rawText: String,
        tone: String,
        layer: Int,
        situation: String,
        vocabulary: List<String>,
        corrections: Map<String, String>
    ): String {
        val sb = StringBuilder()
        sb.append("You are a voice dictation cleaner. Input is raw speech-to-text. ")
        sb.append("Output ONLY the cleaned text — no explanations, no quotes, no labels. ")
        sb.append("Tone: $tone. ")
        when (tone) {
            "formal" -> sb.append("Complete sentences. No contractions. No slang. ")
            "professional" -> sb.append("Clean business language. Contractions acceptable. ")
            "casual" -> sb.append("Natural conversational rhythm. Contractions OK. ")
            "friend" -> sb.append("Relaxed, fragments OK. Keep personality. ")
        }
        sb.append("Strip fillers (um, uh, like, you know). Fix grammar. Preserve full meaning. ")
        sb.append("Do NOT censor profanity or soften language. ")
        if (situation == "high_stress") {
            sb.append("Speaker is under high stress — be more aggressive stripping stacked fillers. ")
        }
        if (layer >= 3 && vocabulary.isNotEmpty()) {
            sb.append("\nPreferred vocabulary: ${vocabulary.take(20).joinToString(", ")}. ")
        }
        if (layer >= 3 && corrections.isNotEmpty()) {
            sb.append("\nKnown corrections (input → output): ")
            corrections.entries.take(10).forEach { (wrong, right) ->
                sb.append("$wrong→$right; ")
            }
        }
        sb.append("\n\nInput: \"$rawText\"\n\nOutput:")
        return sb.toString()
    }
}
