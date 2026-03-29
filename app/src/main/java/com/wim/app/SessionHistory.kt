package com.wim.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues
import android.database.Cursor
import android.util.Log

/**
 * Stores session history in SQLite for analytics and profile learning.
 * Each recording session = one row.
 */
class SessionHistory(context: Context) : SQLiteOpenHelper(context, "wim_sessions.db", null, 1) {

    companion object {
        private const val TAG = "WiM"
        private const val TABLE = "sessions"
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
                recon_ms INTEGER NOT NULL,
                falcon_approved INTEGER NOT NULL DEFAULT 1,
                risk_flags TEXT NOT NULL DEFAULT ''
            )
        """)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun record(
        rawText: String,
        cleanText: String,
        tone: String,
        layer: Int,
        situation: String,
        reconMs: Long,
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
                put("recon_ms", reconMs)
                put("falcon_approved", if (falconApproved) 1 else 0)
                put("risk_flags", riskFlags.joinToString(","))
            }
            writableDatabase.insert(TABLE, null, values)
            Log.i(TAG, "Session recorded (${reconMs}ms, falcon=$falconApproved)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record session: ${e.message}")
        }
    }

    data class Session(
        val timestamp: Long,
        val rawText: String,
        val cleanText: String,
        val tone: String,
        val layer: Int,
        val situation: String,
        val reconMs: Long,
        val falconApproved: Boolean,
        val riskFlags: String
    )

    fun getRecentSessions(limit: Int = 50): List<Session> {
        val sessions = mutableListOf<Session>()
        try {
            val cursor: Cursor = readableDatabase.rawQuery(
                "SELECT timestamp, raw_text, clean_text, tone, layer, situation, recon_ms, falcon_approved, risk_flags FROM $TABLE ORDER BY timestamp DESC LIMIT ?",
                arrayOf(limit.toString())
            )
            while (cursor.moveToNext()) {
                sessions.add(Session(
                    timestamp = cursor.getLong(0),
                    rawText = cursor.getString(1),
                    cleanText = cursor.getString(2),
                    tone = cursor.getString(3),
                    layer = cursor.getInt(4),
                    situation = cursor.getString(5),
                    reconMs = cursor.getLong(6),
                    falconApproved = cursor.getInt(7) == 1,
                    riskFlags = cursor.getString(8)
                ))
            }
            cursor.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query sessions: ${e.message}")
        }
        return sessions
    }
}
