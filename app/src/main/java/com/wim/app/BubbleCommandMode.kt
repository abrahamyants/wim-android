package com.wim.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Command Mode — user highlights text in any app, drops the bubble onto
 * the highlighted region, and the bubble picks up the selection. After a
 * confirm prompt ("Rewrite this text?"), the next utterance is treated
 * as a TRANSFORM command (formal / professional / shorter / translate /
 * etc.) and the result replaces the original selection in place.
 *
 * Extracted from BubbleService 2026-05-25 as the ninth slice. Cohesive:
 * 4 fields + 6 methods + 2 HTTP transports (backend Cloud Function +
 * direct OpenAI fallback for the dev key path).
 *
 * Callbacks bring side effects back to the service so this class doesn't
 * reach into bubble state directly.
 */
class BubbleCommandMode(
    private val context: Context,
    private val getBubbleView: () -> View?,
    private val getBubbleParams: () -> WindowManager.LayoutParams?,
    private val scope: CoroutineScope,
    private val timerHandler: Handler,
    private val onPipelineStage: (stage: String, detail: String) -> Unit,
    private val onConfirmPrompt: (question: String, contextQ: String, onAnswer: (Boolean?) -> Unit) -> Unit,
    private val onStartListening: () -> Unit,
    private val isRecording: () -> Boolean,
    private val onHideLiveTranscript: () -> Unit,
    private val onIdle: () -> Unit,
) {
    private companion object {
        private const val TAG = "WiM"
        private const val CMD_BACKEND_URL =
            "https://us-central1-bakers-agent.cloudfunctions.net/wim-reconstruct"
    }

    var armed: Boolean = false
        private set

    private var sourceText: String = ""
    private var selectionStart: Int = -1
    private var selectionEnd: Int = -1

    /**
     * Check if the bubble was dropped on a focused text field with
     * highlighted text. If yes, pick up the selection for Command Mode
     * and ask the user to confirm via [onConfirmPrompt].
     */
    fun checkPickup(): Boolean {
        val node = WimAccessibilityService.activeTextField ?: return false
        try {
            if (!node.refresh()) return false
        } catch (_: Exception) { return false }

        val start = node.textSelectionStart
        val end = node.textSelectionEnd
        if (start < 0 || end <= start) return false

        val fullText = node.text?.toString() ?: return false
        if (fullText.isEmpty() || end > fullText.length) return false

        val selectedText = fullText.substring(start.coerceAtLeast(0), end.coerceAtMost(fullText.length))
        if (selectedText.isBlank()) return false

        // Check bubble center is over the field's bounds.
        val fieldRect = Rect()
        node.getBoundsInScreen(fieldRect)
        val params = getBubbleParams() ?: return false
        val view = getBubbleView()
        val bubbleCenterX = params.x + (view?.width ?: 0) / 2
        val bubbleCenterY = params.y + (view?.height ?: 0) / 2
        if (!fieldRect.contains(bubbleCenterX, bubbleCenterY)) return false

        // Pickup! Store selection and show in live transcript, then
        // route through the confirm prompt.
        sourceText = selectedText
        selectionStart = start
        selectionEnd = end

        // Show picked-up text (truncated to 3 lines ≈ ~120 chars).
        val preview = if (selectedText.length > 120) selectedText.substring(0, 117) + "..." else selectedText
        onPipelineStage("📋 Picked up", preview)

        // Ask user to confirm.
        onConfirmPrompt("?", "Rewrite this text?") { answer ->
            if (answer == true) {
                arm()
            } else {
                clear()
            }
        }
        return true
    }

    /** Mark command mode armed; the next utterance becomes the transform
     *  command. Auto-starts listening after a 600 ms beat. */
    fun arm() {
        armed = true
        onPipelineStage(
            "🎤 Say the command",
            "formal · professional · casual · friend · shorter · fix · translate"
        )
        // Start listening immediately after clarifier dismisses.
        timerHandler.postDelayed({
            if (armed && !isRecording()) onStartListening()
        }, 600)
    }

    /** Reset all command-mode state and hide the live transcript bar. */
    fun clear() {
        armed = false
        sourceText = ""
        selectionStart = -1
        selectionEnd = -1
        onHideLiveTranscript()
    }

    /**
     * Process Command Mode voice input — send source text + command to
     * GPT, replace the original selection with the result. Signed-in
     * users go through the wim-reconstruct Cloud Function (backend OpenAI
     * key + tier + rate limiting); dev users with a local apiKey fall
     * back to direct OpenAI.
     */
    fun processCommand(command: String) {
        val source = sourceText
        Log.i(TAG, "CMD: transform '$source' with command: '$command'")
        onPipelineStage("⟳ Transforming...", command)

        scope.launch {
            try {
                val apiKey = WimApp.apiKey()
                val idToken = AuthManager.getIdToken()

                val result: String? = if (idToken != null) {
                    callBackendCommandTransform(idToken, source, command)
                } else if (apiKey.isNotBlank()) {
                    val systemPrompt = "You are a text transformation assistant. The user highlighted some text and spoke a command to modify it. Apply the command and return ONLY the transformed text, nothing else. Preserve the meaning. Do not add explanations, quotes, or prefixes."
                    val userContent = "TEXT:\n$source\n\nCOMMAND: $command"
                    callOpenAiTransform(apiKey, systemPrompt, userContent)
                } else {
                    null
                }

                if (result.isNullOrBlank()) {
                    Log.e(TAG, "CMD: transform failed")
                    onPipelineStage("✕ Failed", "Try again")
                    timerHandler.postDelayed({ onIdle() }, 2000)
                    clear()
                    return@launch
                }

                val replaced = replaceSelection(result)
                if (replaced) {
                    Log.i(TAG, "CMD: replaced selection with '$result'")
                    val wordCount = result.split("\\s+".toRegex()).filter { it.isNotBlank() }.size
                    onPipelineStage("✓ Rewrote $wordCount words", result.take(80))
                } else {
                    Log.e(TAG, "CMD: replaceSelection failed, copying to clipboard")
                    val clip = ClipData.newPlainText("wim", result)
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                    onPipelineStage("✓ Copied", "Paste manually")
                }
                timerHandler.postDelayed({ onIdle() }, 1800)
            } catch (e: Exception) {
                Log.e(TAG, "CMD: exception: ${e.message}")
                onPipelineStage("✕ Error", e.message ?: "")
                timerHandler.postDelayed({ onIdle() }, 2000)
            } finally {
                clear()
            }
        }
    }

    private fun replaceSelection(newText: String): Boolean {
        val node = WimAccessibilityService.activeTextField ?: return false
        try {
            if (!node.refresh()) return false
        } catch (_: Exception) { return false }

        val fullText = node.text?.toString() ?: return false
        val start = selectionStart
        val end = selectionEnd
        if (start < 0 || end < start || end > fullText.length) return false

        val replaced = fullText.substring(0, start) + newText + fullText.substring(end)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, replaced)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /**
     * Signed-in Command Mode path: POST {action: "command", source, command}
     * to the wim-reconstruct Cloud Function. Backend uses its own OpenAI
     * key, applies tier + daily-rate limits, returns {"transformed": "..."}
     * or {"error": "..."}. Returns null on any non-200 / network failure
     * so the caller can show "✕ Failed".
     */
    private suspend fun callBackendCommandTransform(
        idToken: String,
        source: String,
        command: String
    ): String? = withContext(Dispatchers.IO) {
        try {
            val http = OkHttpClient.Builder()
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
            val body = JSONObject().apply {
                put("action", "command")
                put("source", source)
                put("command", command)
            }
            val req = Request.Builder()
                .url(CMD_BACKEND_URL)
                .addHeader("Authorization", "Bearer $idToken")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val resp = http.newCall(req).execute()
            val respBody = resp.body?.string() ?: return@withContext null
            if (!resp.isSuccessful) {
                Log.w(TAG, "CMD backend ${resp.code}: ${respBody.take(200)}")
                return@withContext null
            }
            JSONObject(respBody).optString("transformed").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.e(TAG, "CMD backend exception: ${e.message}")
            null
        }
    }

    private suspend fun callOpenAiTransform(
        apiKey: String,
        systemPrompt: String,
        userContent: String
    ): String? = withContext(Dispatchers.IO) {
        try {
            val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
            val messages = JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemPrompt))
                put(JSONObject().put("role", "user").put("content", userContent))
            }
            val body = JSONObject().apply {
                put("model", "gpt-4o")
                put("messages", messages)
                put("temperature", 0.3)
            }
            val req = Request.Builder()
                .url("https://api.openai.com/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val resp = http.newCall(req).execute()
            val respBody = resp.body?.string() ?: return@withContext null
            if (!resp.isSuccessful) return@withContext null
            JSONObject(respBody)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
        } catch (e: Exception) {
            Log.e(TAG, "callOpenAiTransform failed: ${e.message}", e)
            null
        }
    }
}
