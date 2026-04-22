package com.wim.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Test automation bridge — gives ADB/LLM agents full programmatic control.
 *
 * DEBUG BUILDS ONLY. Stripped from release APKs via BuildConfig.DEBUG guard.
 *
 * Usage from ADB:
 *   adb shell am broadcast -a com.wim.app.TEST -n com.wim.app/.WimTestReceiver --es action <action> [extras]
 *
 * Actions:
 *   set_tone          --es tone casual|professional|formal|friend
 *   set_layer         --ei layer 1|2|3|4
 *   set_situation     --es situation default|high_stress|reading
 *   inject_raw        --es raw_text "I I I w-want to go"   (feeds text into pipeline, skips mic)
 *   inject_audio      --es audio_path /sdcard/test.wav      (feeds WAV into Whisper pipeline)
 *   get_state         (writes current state to files/test_result.json)
 *   get_last_result   (writes last reconstruction result to files/test_result.json)
 *   trigger_bubble    --es bubble_action show|hide|record|stop|menu|dismiss_menu
 *   reset_profile     (clears learned vocabulary, corrections, triggers)
 *   export_sessions   --es output_path /sdcard/sessions.json  (optional, default: files/sessions_export.json)
 *   set_pref          --es key <pref_key> --es value <value> --es type string|int|bool|float
 *   toggle_daf        --ez enabled true --ei delay_ms 150
 *   dump_a11y_tree    (writes accessibility tree to files/a11y_tree.json)
 *   clear_sessions    (wipes session history DB)
 *   ping              (writes {"status":"ok"} — verifies receiver is alive)
 *
 * All results written to: files/test_result.json (readable via run-as)
 *   adb shell run-as com.wim.app cat files/test_result.json
 */
class WimTestReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "WiM-Test"
        const val ACTION = "com.wim.app.TEST"

        /** Last reconstruction result — set by BubbleService.processTextForTest() */
        @Volatile var lastResult: JSONObject? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) {
            Log.w(TAG, "Test receiver blocked in release build")
            return
        }

        val action = intent.getStringExtra("action")
        if (action.isNullOrBlank()) {
            writeResult(context, JSONObject().put("error", "Missing 'action' extra"))
            return
        }

        Log.i(TAG, "Received: $action")

        try {
            when (action) {
                "ping"              -> handlePing(context)
                "set_tone"          -> handleSetTone(context, intent)
                "set_layer"         -> handleSetLayer(context, intent)
                "set_situation"     -> handleSetSituation(context, intent)
                "inject_raw"        -> handleInjectRaw(context, intent)
                "inject_audio"      -> handleInjectAudio(context, intent)
                "get_state"         -> handleGetState(context)
                "get_last_result"   -> handleGetLastResult(context)
                "trigger_bubble"    -> handleTriggerBubble(context, intent)
                "test_chirp"        -> handleTestChirp()
                "toggle_recording"  -> {
                    val toggleIntent = android.content.Intent(context, BubbleService::class.java)
                    toggleIntent.setAction("TOGGLE_RECORDING")
                    context.startForegroundService(toggleIntent)
                    Log.i(TAG, "toggle_recording: sent TOGGLE_RECORDING intent")
                }
                "reset_profile"     -> handleResetProfile(context)
                "export_sessions"   -> handleExportSessions(context, intent)
                "set_pref"          -> handleSetPref(context, intent)
                "toggle_daf"        -> handleToggleDaf(context, intent)
                "dump_a11y_tree"    -> handleDumpA11yTree(context)
                "clear_sessions"    -> handleClearSessions(context)
                "click_send"        -> {
                    // Test-only entry to fire the auto-send click directly,
                    // bypassing the bubble. Returns status so we can verify
                    // without device-side interaction.
                    val ok = WimAccessibilityService.instance?.clickSendButton() == true
                    writeResult(context, JSONObject()
                        .put("status", if (ok) "ok" else "failed"))
                    Log.i(TAG, "click_send: ${if (ok) "ok" else "failed"}")
                }
                else -> writeResult(context, JSONObject().put("error", "Unknown action: $action"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Action '$action' failed: ${e.message}", e)
            writeResult(context, JSONObject()
                .put("error", e.message)
                .put("action", action))
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Action handlers
    // ═══════════════════════════════════════════════════════════════════════════

    private fun handlePing(context: Context) {
        writeResult(context, JSONObject()
            .put("status", "ok")
            .put("timestamp", System.currentTimeMillis())
            .put("bubble_running", BubbleService.instance != null)
            .put("a11y_running", WimAccessibilityService.instance != null)
            .put("recording", RecordingStateBus.isRecording))
    }

    private fun handleSetTone(context: Context, intent: Intent) {
        val tone = intent.getStringExtra("tone") ?: "casual"
        val valid = setOf("casual", "professional", "formal", "friend")
        if (tone !in valid) {
            writeResult(context, JSONObject().put("error", "Invalid tone: $tone. Valid: $valid"))
            return
        }
        WimApp.prefs.edit().putString("tone", tone).apply()
        BubbleService.instance?.updateIdleDisplay()
        Log.i(TAG, "Tone set: $tone")
        writeResult(context, JSONObject().put("tone", tone).put("status", "ok"))
    }

    private fun handleSetLayer(context: Context, intent: Intent) {
        val layer = intent.getIntExtra("layer", -1)
        if (layer !in 1..4) {
            writeResult(context, JSONObject().put("error", "Invalid layer: $layer. Valid: 1-4"))
            return
        }
        WimApp.prefs.edit().putInt("layer", layer).apply()
        BubbleService.instance?.updateIdleDisplay()
        Log.i(TAG, "Layer set: $layer")
        writeResult(context, JSONObject().put("layer", layer).put("status", "ok"))
    }

    private fun handleSetSituation(context: Context, intent: Intent) {
        val situation = intent.getStringExtra("situation") ?: "default"
        val valid = setOf("default", "high_stress", "reading")
        if (situation !in valid) {
            writeResult(context, JSONObject().put("error", "Invalid situation: $situation. Valid: $valid"))
            return
        }
        WimApp.prefs.edit().putString("situation", situation).apply()
        Log.i(TAG, "Situation set: $situation")
        writeResult(context, JSONObject().put("situation", situation).put("status", "ok"))
    }

    private fun handleInjectRaw(context: Context, intent: Intent) {
        val rawText = intent.getStringExtra("raw_text")
        if (rawText.isNullOrBlank()) {
            writeResult(context, JSONObject().put("error", "Missing raw_text"))
            return
        }
        val bubble = BubbleService.instance
        if (bubble == null) {
            writeResult(context, JSONObject().put("error", "BubbleService not running"))
            return
        }
        // Clear last result before injection
        lastResult = null
        Log.i(TAG, "Injecting raw text: ${rawText.take(80)}...")
        // processTextForTest runs the full pipeline (disfluency filter → reconstruct → inject)
        // and writes the result to lastResult when complete
        bubble.processTextForTest(rawText)
        writeResult(context, JSONObject()
            .put("status", "injected")
            .put("raw_text", rawText)
            .put("note", "Result will be available via get_last_result after pipeline completes (~1-5s)"))
    }

    private fun handleInjectAudio(context: Context, intent: Intent) {
        val audioPath = intent.getStringExtra("audio_path")
        if (audioPath.isNullOrBlank()) {
            writeResult(context, JSONObject().put("error", "Missing audio_path"))
            return
        }
        val file = File(audioPath)
        if (!file.exists() || file.length() <= 44) {
            writeResult(context, JSONObject().put("error", "Audio file not found or too small: $audioPath"))
            return
        }
        val bubble = BubbleService.instance
        if (bubble == null) {
            writeResult(context, JSONObject().put("error", "BubbleService not running"))
            return
        }
        lastResult = null
        Log.i(TAG, "Injecting audio: $audioPath (${file.length()} bytes)")
        bubble.processAudioForTest(file)
        writeResult(context, JSONObject()
            .put("status", "injected")
            .put("audio_path", audioPath)
            .put("audio_size", file.length())
            .put("note", "Result will be available via get_last_result after Whisper + pipeline completes (~3-10s)"))
    }

    private fun handleGetState(context: Context) {
        val profile = WimApp.profile()
        writeResult(context, JSONObject().apply {
            put("tone", WimApp.tone())
            put("layer", WimApp.layer())
            put("situation", WimApp.situation())
            put("daf_enabled", WimApp.dafEnabled())
            put("daf_delay", WimApp.dafDelay())
            put("bubble_running", BubbleService.instance != null)
            put("bubble_recording", RecordingStateBus.isRecording)
            put("a11y_running", WimAccessibilityService.instance != null)
            put("is_password_field", WimAccessibilityService.isPasswordField)
            put("bubble_opacity", WimApp.bubbleOpacity())
            put("bubble_size", WimApp.bubbleSize())
            put("fast_mode", WimApp.prefs.getBoolean("fast_mode", false))
            put("instant_mode", WimApp.prefs.getBoolean("instant_mode", false))
            put("quiet_mode", WimApp.prefs.getBoolean("quiet_mode", false))
            put("auto_tone", WimApp.prefs.getBoolean("auto_tone", true))
            put("vocabulary_count", profile.getVocabulary().size)
            put("corrections_count", profile.getCorrections().size)
            put("trigger_words_count", profile.getTriggerWords().size)
            put("session_count", SessionHistory(context).count())
            put("timestamp", System.currentTimeMillis())
        })
    }

    private fun handleGetLastResult(context: Context) {
        val result = lastResult
        if (result == null) {
            writeResult(context, JSONObject()
                .put("status", "no_result")
                .put("note", "No result available yet. Either nothing was injected, or pipeline is still running."))
            return
        }
        writeResult(context, result)
    }

    private fun handleTriggerBubble(context: Context, intent: Intent) {
        val bubbleAction = intent.getStringExtra("bubble_action") ?: "show"
        val bubble = BubbleService.instance

        when (bubbleAction) {
            "show" -> {
                val svcIntent = Intent(context, BubbleService::class.java).apply { action = "SHOW" }
                context.startForegroundService(svcIntent)
                writeResult(context, JSONObject().put("status", "ok").put("bubble_action", "show"))
            }
            "hide" -> {
                bubble?.setBubbleIdlePublic()
                val svcIntent = Intent(context, BubbleService::class.java)
                context.stopService(svcIntent)
                writeResult(context, JSONObject().put("status", "ok").put("bubble_action", "hide"))
            }
            "menu" -> {
                if (bubble == null) {
                    writeResult(context, JSONObject().put("error", "BubbleService not running"))
                    return
                }
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    bubble.showMenuPublic()
                }
                writeResult(context, JSONObject().put("status", "ok").put("bubble_action", "menu"))
            }
            "dismiss_menu" -> {
                if (bubble == null) {
                    writeResult(context, JSONObject().put("error", "BubbleService not running"))
                    return
                }
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    bubble.dismissMenuPublic()
                }
                writeResult(context, JSONObject().put("status", "ok").put("bubble_action", "dismiss_menu"))
            }
            else -> writeResult(context, JSONObject()
                .put("error", "Unknown bubble_action: $bubbleAction")
                .put("valid", "show, hide, menu, dismiss_menu"))
        }
    }

    private fun handleResetProfile(context: Context) {
        val profile = WimApp.profile()
        // Save current counts for the response
        val vocabCount = profile.getVocabulary().size
        val corrCount = profile.getCorrections().size
        val trigCount = profile.getTriggerWords().size

        // Clear all profile data
        WimApp.prefs.edit()
            .remove("profile_json")
            .apply()
        // Reload fresh profile
        profile.loadProfile()

        Log.i(TAG, "Profile reset: cleared $vocabCount vocab, $corrCount corrections, $trigCount triggers")
        writeResult(context, JSONObject()
            .put("status", "ok")
            .put("cleared_vocabulary", vocabCount)
            .put("cleared_corrections", corrCount)
            .put("cleared_triggers", trigCount))
    }

    private fun handleExportSessions(context: Context, intent: Intent) {
        val outputPath = intent.getStringExtra("output_path")
        val history = SessionHistory(context)
        val sessions = history.getRecentSessions(limit = 10000)

        val arr = JSONArray()
        for (s in sessions) {
            arr.put(JSONObject().apply {
                put("timestamp", s.timestamp)
                put("raw_text", s.rawText)
                put("clean_text", s.cleanText)
                put("tone", s.tone)
                put("layer", s.layer)
                put("situation", s.situation)
                put("asr_ms", s.asrMs)
                put("recon_ms", s.reconMs)
                put("total_ms", s.totalMs)
                put("falcon_approved", s.falconApproved)
                put("risk_flags", s.riskFlags)
                put("wer", history.computeWer(s.rawText, s.cleanText).toDouble())
            })
        }

        val json = JSONObject()
            .put("count", sessions.size)
            .put("sessions", arr)

        if (outputPath != null) {
            File(outputPath).writeText(json.toString(2))
            writeResult(context, JSONObject()
                .put("status", "ok")
                .put("count", sessions.size)
                .put("output_path", outputPath))
        } else {
            // Write to internal files dir
            val file = File(context.filesDir, "sessions_export.json")
            file.writeText(json.toString(2))
            writeResult(context, JSONObject()
                .put("status", "ok")
                .put("count", sessions.size)
                .put("internal_path", file.absolutePath))
        }
    }

    private fun handleSetPref(context: Context, intent: Intent) {
        val key = intent.getStringExtra("key")
        val value = intent.getStringExtra("value")
        val type = intent.getStringExtra("type") ?: "string"

        if (key.isNullOrBlank() || value == null) {
            writeResult(context, JSONObject().put("error", "Missing key or value"))
            return
        }

        val editor = WimApp.prefs.edit()
        when (type) {
            "string" -> editor.putString(key, value)
            "int"    -> editor.putInt(key, value.toInt())
            "bool"   -> editor.putBoolean(key, value.toBoolean())
            "float"  -> editor.putFloat(key, value.toFloat())
            else -> {
                writeResult(context, JSONObject().put("error", "Unknown type: $type. Valid: string, int, bool, float"))
                return
            }
        }
        editor.apply()
        Log.i(TAG, "Pref set: $key=$value ($type)")
        writeResult(context, JSONObject().put("status", "ok").put("key", key).put("value", value).put("type", type))
    }

    private fun handleToggleDaf(context: Context, intent: Intent) {
        val enabled = intent.getBooleanExtra("enabled", false)
        val delay = intent.getIntExtra("delay_ms", -1)

        WimApp.prefs.edit().putBoolean("daf_enabled", enabled).apply()
        if (delay in 30..300) {
            WimApp.prefs.edit().putInt("daf_delay", delay).apply()
        }

        writeResult(context, JSONObject()
            .put("status", "ok")
            .put("daf_enabled", enabled)
            .put("daf_delay", if (delay in 30..300) delay else WimApp.dafDelay()))
    }

    private fun handleDumpA11yTree(context: Context) {
        val a11y = WimAccessibilityService.instance
        if (a11y == null) {
            writeResult(context, JSONObject().put("error", "Accessibility service not running"))
            return
        }

        val windows = a11y.windows
        val tree = JSONArray()
        for (window in windows) {
            val root = window.root ?: continue
            tree.put(dumpNode(root, 0))
        }

        val result = JSONObject()
            .put("status", "ok")
            .put("window_count", windows.size)
            .put("tree", tree)

        // Write to dedicated file (can be large)
        val file = File(context.filesDir, "a11y_tree.json")
        file.writeText(result.toString(2))

        writeResult(context, JSONObject()
            .put("status", "ok")
            .put("window_count", windows.size)
            .put("path", file.absolutePath))
    }

    private fun dumpNode(node: android.view.accessibility.AccessibilityNodeInfo, depth: Int): JSONObject {
        val obj = JSONObject().apply {
            put("class", node.className?.toString() ?: "")
            put("text", node.text?.toString() ?: "")
            put("content_desc", node.contentDescription?.toString() ?: "")
            put("resource_id", node.viewIdResourceName ?: "")
            put("clickable", node.isClickable)
            put("editable", node.isEditable)
            put("enabled", node.isEnabled)
            put("focused", node.isFocused)
            put("password", node.isPassword)
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            put("bounds", "${rect.left},${rect.top},${rect.right},${rect.bottom}")
        }

        if (node.childCount > 0 && depth < 10) {
            val children = JSONArray()
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                children.put(dumpNode(child, depth + 1))
            }
            obj.put("children", children)
        }
        return obj
    }

    private fun handleClearSessions(context: Context) {
        val history = SessionHistory(context)
        val count = history.count()
        history.writableDatabase.execSQL("DELETE FROM sessions")
        Log.i(TAG, "Cleared $count sessions")
        writeResult(context, JSONObject()
            .put("status", "ok")
            .put("cleared_count", count))
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Result file
    // ═══════════════════════════════════════════════════════════════════════════

    private fun writeResult(context: Context, json: JSONObject) {
        try {
            val file = File(context.filesDir, "test_result.json")
            file.writeText(json.toString(2))
            Log.i(TAG, "Result written: ${json.optString("status", json.optString("error", ""))}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write result: ${e.message}")
        }
    }

    private fun handleTestChirp() {
        val bubble = BubbleService.instance
        if (bubble == null) {
            Log.w(TAG, "test_chirp: BubbleService not running")
            return
        }
        val view = bubble.bubbleView
        if (view == null) {
            Log.w(TAG, "test_chirp: bubble view not inflated")
            return
        }
        Log.i(TAG, "test_chirp: calling playSoundEffect on bubble view")
        view.playSoundEffect(android.view.SoundEffectConstants.CLICK)
        Log.i(TAG, "test_chirp: calling playSoundEffect on bezel")
        view.findViewById<android.view.View>(R.id.bubbleBezel)?.playSoundEffect(android.view.SoundEffectConstants.CLICK)
        Log.i(TAG, "test_chirp: calling playSoundEffect on inner knob")
        view.findViewById<android.view.View>(R.id.bubbleInner)?.playSoundEffect(android.view.SoundEffectConstants.CLICK)
        Log.i(TAG, "test_chirp: done — check CHIRP_MONITOR logs for audio playback")
    }
}
