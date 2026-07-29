package com.wim.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.FirebaseApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What happens to a user's dictation history when the machinery misbehaves.
 *
 * Everything else in this suite tests bad INPUT — garbled ASR, hallucinations,
 * adversarial text. Nothing tested the app's own storage failing, and the
 * 2026-07-29 schema-v3 migration shipped with two data-loss-shaped holes
 * because of it: a swallowed ALTER TABLE that made a full history render as
 * "No sessions yet", and a missing onDowngrade that crashed every launch after
 * an older APK was sideloaded. Both are locked down here.
 *
 * History is the one thing in this app that cannot be regenerated.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class SessionHistoryMigrationTest {

    private lateinit var context: Context

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        if (FirebaseApp.getApps(context).isEmpty()) FirebaseApp.initializeApp(context)
        context.deleteDatabase("wim_sessions.db")
    }

    /** Build a pre-v3 database by hand and put real rows in it. */
    private fun seedLegacyDatabase(version: Int, rows: Int = 3): String {
        val path = context.getDatabasePath("wim_sessions.db").also { it.parentFile?.mkdirs() }
        val db = SQLiteDatabase.openOrCreateDatabase(path, null)
        db.execSQL(
            """
            CREATE TABLE sessions (
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
            """
        )
        repeat(rows) { i ->
            db.insert("sessions", null, ContentValues().apply {
                put("timestamp", 1_000L + i)
                put("raw_text", "um the the report $i")
                put("clean_text", "The report $i")
                put("tone", "casual")
                put("layer", 2)
                put("situation", "default")
                put("asr_ms", 4_000L)
                put("recon_ms", 900L)
                put("total_ms", 5_000L)
                put("falcon_approved", 1)
                put("risk_flags", "")
            })
        }
        db.version = version
        db.close()
        return path.absolutePath
    }

    // ── Upgrade ───────────────────────────────────────────────────

    @Test fun upgradeFromV2KeepsEveryRow() {
        seedLegacyDatabase(version = 2, rows = 5)
        val history = SessionHistory(context)
        assertEquals(5, history.count())
        val sessions = history.getRecentSessions(50)
        assertEquals(5, sessions.size)
        assertTrue(sessions.all { it.cleanText.startsWith("The report") })
    }

    @Test fun upgradeFromV2LeavesSpeechMsZeroNotGarbage() {
        seedLegacyDatabase(version = 2, rows = 2)
        val sessions = SessionHistory(context).getRecentSessions(50)
        assertTrue(sessions.all { it.speechMs == 0L })
        // And a zero must never be treated as a measured duration.
        assertEquals(0, SessionStats.wpm(sessions))
    }

    @Test fun upgradeFromV1KeepsEveryRow() {
        // The v3 block runs before the v2 block in onUpgrade; prove that
        // ordering doesn't strand a v1 database.
        seedLegacyDatabase(version = 1, rows = 3)
        val history = SessionHistory(context)
        assertEquals(3, history.count())
    }

    @Test fun upgradeIsIdempotentAcrossReopens() {
        seedLegacyDatabase(version = 2, rows = 4)
        assertEquals(4, SessionHistory(context).count())
        assertEquals(4, SessionHistory(context).count())
        assertEquals(4, SessionHistory(context).count())
    }

    // ── Downgrade — an older APK landing on a newer database ──────

    @Test fun downgradeKeepsDataInsteadOfCrashing() {
        // Write with the current schema...
        val current = SessionHistory(context)
        current.record(
            rawText = "um the the invoice",
            cleanText = "The invoice",
            tone = "professional",
            layer = 2,
            situation = "default",
            asrMs = 4_000L,
            reconMs = 800L,
            totalMs = 5_000L,
            falconApproved = true,
            riskFlags = emptyList(),
            speechMs = 3_000L,
        )
        assertEquals(1, current.count())
        current.close()

        // ...then simulate an older build by forcing the stored version back.
        val path = context.getDatabasePath("wim_sessions.db")
        SQLiteDatabase.openDatabase(path.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.version = 99
        }

        // Opening at the lower app version must NOT throw and must keep rows.
        val reopened = SessionHistory(context)
        assertEquals(1, reopened.count())
        assertEquals(1, reopened.getRecentSessions(50).size)
    }

    // ── Reads that must never look like "your history is gone" ────

    @Test fun emptyDatabaseReportsZeroNotFailure() {
        val history = SessionHistory(context)
        assertEquals(0, history.count())
        assertTrue(history.getRecentSessions(50).isEmpty())
    }

    @Test fun searchDoesNotHideRowsFromTheLifetimeCount() {
        val history = SessionHistory(context)
        repeat(3) { i ->
            history.record(
                rawText = "raw $i", cleanText = "clean $i", tone = "casual", layer = 2,
                situation = "default", asrMs = 100L, reconMs = 50L, totalMs = 200L,
                falconApproved = true, riskFlags = emptyList(), speechMs = 1_000L,
            )
        }
        assertEquals(3, history.count())
        // A search narrows the LIST, never the lifetime total.
        assertEquals(1, history.getRecentSessions(50, "clean 1").size)
        assertEquals(3, history.count())
    }

    @Test fun speechMsSurvivesAWriteReadRoundTrip() {
        val history = SessionHistory(context)
        history.record(
            rawText = "one two three four", cleanText = "one two three four",
            tone = "casual", layer = 2, situation = "default",
            asrMs = 9_000L, reconMs = 0L, totalMs = 9_000L,
            falconApproved = true, riskFlags = emptyList(), speechMs = 12_000L,
        )
        val s = history.getRecentSessions(1).single()
        assertEquals(12_000L, s.speechMs)
        // 4 words in 12s = 20 wpm, computed off speech time and not asr time
        // (which would have said 26 and been wrong).
        assertEquals(20, SessionStats.wpm(listOf(s)))
    }
}
