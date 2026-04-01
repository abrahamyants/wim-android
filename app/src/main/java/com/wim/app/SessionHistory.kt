package com.wim.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues
import android.database.Cursor
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Stores session history in SQLite for analytics and profile learning.
 * Each recording session = one row.
 */
class SessionHistory(context: Context) : SQLiteOpenHelper(context, "wim_sessions.db", null, 2) {

    companion object {
        private const val TAG = "WiM"
        private const val TABLE = "sessions"
        private const val MAX_SESSIONS = 10_000
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                raw_text TEXT NOT NULL,
                clean_text TEXT NOT NULL,
                tone TEXT NOT NULL,
                layer INTEGER NOT NULL,
                situation TEXT NOT NULL,
                asr_ms INTEGER NOT NULL DEFAULT 0,
                recon_ms INTEGER NOT NULL,
                total_ms INTEGER NOT NULL DEFAULT 0,
                falcon_approved INTEGER NOT NULL DEFAULT 1,
                risk_flags TEXT NOT NULL DEFAULT ''
            )
        """)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN asr_ms INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN total_ms INTEGER NOT NULL DEFAULT 0")
        }
    }

    fun record(
        rawText: String,
        cleanText: String,
        tone: String,
        layer: Int,
        situation: String,
        asrMs: Long,
        reconMs: Long,
        totalMs: Long,
        falconApproved: Boolean,
        riskFlags: List<String>
    ) {
        try {
            val values = ContentValues().apply {
                put("timestamp", System.currentTimeMillis())
                put("raw_text", rawText)
                put("clean_text", cleanText)
                put("tone", tone)
                put("layer", layer)
                put("situation", situation)
                put("asr_ms", asrMs)
                put("recon_ms", reconMs)
                put("total_ms", totalMs)
                put("falcon_approved", if (falconApproved) 1 else 0)
                put("risk_flags", riskFlags.joinToString(","))
            }
            writableDatabase.insert(TABLE, null, values)
            Log.i(TAG, "Session recorded (asr=${asrMs}ms recon=${reconMs}ms total=${totalMs}ms falcon=$falconApproved)")
            pruneIfNeeded()
            // Backup to Firestore if signed in
            pushToFirestore(values)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record session: ${e.message}")
        }
    }

    private fun pruneIfNeeded() {
        try {
            val db = writableDatabase
            val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE", null)
            cursor.moveToFirst()
            val count = cursor.getInt(0)
            cursor.close()
            if (count > MAX_SESSIONS) {
                val excess = count - MAX_SESSIONS
                db.execSQL("DELETE FROM $TABLE WHERE id IN (SELECT id FROM $TABLE ORDER BY timestamp ASC LIMIT ?)", arrayOf(excess.toString()))
                Log.i(TAG, "Pruned $excess old sessions")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prune sessions: ${e.message}")
        }
    }

    /** Word Error Rate: how much the pipeline changed the text. 0.0 = identical, 1.0 = completely different */
    fun computeWer(raw: String, clean: String): Float {
            val rawWords = raw.lowercase().split("\\s+".toRegex()).filter { it.isNotBlank() }
            val cleanWords = clean.lowercase().split("\\s+".toRegex()).filter { it.isNotBlank() }
            if (rawWords.isEmpty()) return 0f
            val m = rawWords.size
            val n = cleanWords.size
            val dp = Array(m + 1) { IntArray(n + 1) }
            for (i in 0..m) dp[i][0] = i
            for (j in 0..n) dp[0][j] = j
            for (i in 1..m) for (j in 1..n) {
                dp[i][j] = if (rawWords[i - 1] == cleanWords[j - 1]) dp[i - 1][j - 1]
                else 1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
            }
            return dp[m][n].toFloat() / m
    }

    data class Session(
        val timestamp: Long,
        val rawText: String,
        val cleanText: String,
        val tone: String,
        val layer: Int,
        val situation: String,
        val asrMs: Long,
        val reconMs: Long,
        val totalMs: Long,
        val falconApproved: Boolean,
        val riskFlags: String
    )

    private fun pushToFirestore(values: ContentValues) {
        if (!WimApp.prefs.getBoolean("cloud_backup", true)) return
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val doc = hashMapOf(
            "timestamp" to values.getAsLong("timestamp"),
            "raw_text" to values.getAsString("raw_text"),
            "clean_text" to values.getAsString("clean_text"),
            "tone" to values.getAsString("tone"),
            "layer" to values.getAsInteger("layer"),
            "situation" to values.getAsString("situation"),
            "asr_ms" to values.getAsLong("asr_ms"),
            "recon_ms" to values.getAsLong("recon_ms"),
            "total_ms" to values.getAsLong("total_ms"),
            "falcon_approved" to (values.getAsInteger("falcon_approved") == 1),
            "risk_flags" to (values.getAsString("risk_flags") ?: "")
        )
        FirebaseFirestore.getInstance()
            .collection("wim_users").document(uid)
            .collection("sessions").document(values.getAsLong("timestamp").toString())
            .set(doc)
            .addOnSuccessListener { Log.i(TAG, "Session backed up to Firestore") }
            .addOnFailureListener { Log.e(TAG, "Session backup failed: ${it.message}") }
    }

    fun syncFromFirestore() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        Log.i(TAG, "Syncing sessions from Firestore...")
        FirebaseFirestore.getInstance()
            .collection("wim_users").document(uid)
            .collection("sessions")
            .orderBy("timestamp")
            .get()
            .addOnSuccessListener { snapshot ->
                var imported = 0
                for (doc in snapshot.documents) {
                    val ts = doc.getLong("timestamp") ?: continue
                    // Skip if already exists locally
                    val cursor = readableDatabase.rawQuery(
                        "SELECT COUNT(*) FROM $TABLE WHERE timestamp = ?",
                        arrayOf(ts.toString())
                    )
                    cursor.moveToFirst()
                    val exists = cursor.getInt(0) > 0
                    cursor.close()
                    if (exists) continue

                    val values = ContentValues().apply {
                        put("timestamp", ts)
                        put("raw_text", doc.getString("raw_text") ?: "")
                        put("clean_text", doc.getString("clean_text") ?: "")
                        put("tone", doc.getString("tone") ?: "casual")
                        put("layer", (doc.getLong("layer") ?: 1).toInt())
                        put("situation", doc.getString("situation") ?: "default")
                        put("asr_ms", doc.getLong("asr_ms") ?: 0)
                        put("recon_ms", doc.getLong("recon_ms") ?: 0)
                        put("total_ms", doc.getLong("total_ms") ?: 0)
                        put("falcon_approved", if (doc.getBoolean("falcon_approved") != false) 1 else 0)
                        put("risk_flags", doc.getString("risk_flags") ?: "")
                    }
                    writableDatabase.insert(TABLE, null, values)
                    imported++
                }
                Log.i(TAG, "Synced $imported sessions from Firestore")
            }
            .addOnFailureListener { Log.e(TAG, "Session sync failed: ${it.message}") }
    }

    fun getRecentSessions(limit: Int = 50, query: String? = null): List<Session> {
        val sessions = mutableListOf<Session>()
        try {
            val sql: String
            val args: Array<String>
            if (!query.isNullOrBlank()) {
                sql = "SELECT timestamp, raw_text, clean_text, tone, layer, situation, asr_ms, recon_ms, total_ms, falcon_approved, risk_flags FROM $TABLE WHERE clean_text LIKE ? OR raw_text LIKE ? ORDER BY timestamp DESC LIMIT ?"
                val q = "%$query%"
                args = arrayOf(q, q, limit.toString())
            } else {
                sql = "SELECT timestamp, raw_text, clean_text, tone, layer, situation, asr_ms, recon_ms, total_ms, falcon_approved, risk_flags FROM $TABLE ORDER BY timestamp DESC LIMIT ?"
                args = arrayOf(limit.toString())
            }
            val cursor: Cursor = readableDatabase.rawQuery(sql, args)
            while (cursor.moveToNext()) {
                sessions.add(Session(
                    timestamp = cursor.getLong(0),
                    rawText = cursor.getString(1),
                    cleanText = cursor.getString(2),
                    tone = cursor.getString(3),
                    layer = cursor.getInt(4),
                    situation = cursor.getString(5),
                    asrMs = cursor.getLong(6),
                    reconMs = cursor.getLong(7),
                    totalMs = cursor.getLong(8),
                    falconApproved = cursor.getInt(9) == 1,
                    riskFlags = cursor.getString(10)
                ))
            }
            cursor.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query sessions: ${e.message}")
        }
        return sessions
    }
}
