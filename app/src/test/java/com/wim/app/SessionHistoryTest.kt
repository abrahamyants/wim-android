package com.wim.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class SessionHistoryTest {

    private lateinit var history: SessionHistory

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Delete old DB to start fresh
        context.deleteDatabase("wim_sessions.db")
        history = SessionHistory(context)
    }

    @After fun teardown() {
        history.close()
    }

    // ── Basic insert + query ───────────────────────────

    @Test fun insertAndQuery_single() {
        history.record("raw text", "clean text", "casual", 2, "default",
            asrMs = 500, reconMs = 800, totalMs = 1300,
            falconApproved = true, riskFlags = emptyList())

        val sessions = history.getRecentSessions(10)
        assertEquals(1, sessions.size)
        assertEquals("clean text", sessions[0].cleanText)
        assertEquals("raw text", sessions[0].rawText)
        assertEquals("casual", sessions[0].tone)
        assertEquals(2, sessions[0].layer)
        assertEquals("default", sessions[0].situation)
        assertEquals(500L, sessions[0].asrMs)
        assertEquals(800L, sessions[0].reconMs)
        assertEquals(1300L, sessions[0].totalMs)
        assertTrue(sessions[0].falconApproved)
        assertEquals("", sessions[0].riskFlags)
    }

    @Test fun insertAndQuery_multiple() {
        repeat(5) { i ->
            history.record("raw $i", "clean $i", "casual", 1, "default",
                asrMs = 100, reconMs = 0, totalMs = 100,
                falconApproved = true, riskFlags = emptyList())
        }
        val sessions = history.getRecentSessions(10)
        assertEquals(5, sessions.size)
    }

    @Test fun query_orderedByTimestampDesc() {
        history.record("first", "first clean", "casual", 1, "default",
            asrMs = 100, reconMs = 0, totalMs = 100,
            falconApproved = true, riskFlags = emptyList())
        Thread.sleep(10) // ensure different timestamp
        history.record("second", "second clean", "casual", 1, "default",
            asrMs = 100, reconMs = 0, totalMs = 100,
            falconApproved = true, riskFlags = emptyList())

        val sessions = history.getRecentSessions(10)
        assertEquals("second clean", sessions[0].cleanText)
        assertEquals("first clean", sessions[1].cleanText)
    }

    @Test fun query_limitRespected() {
        repeat(10) { i ->
            history.record("raw $i", "clean $i", "casual", 1, "default",
                asrMs = 100, reconMs = 0, totalMs = 100,
                falconApproved = true, riskFlags = emptyList())
        }
        val sessions = history.getRecentSessions(3)
        assertEquals(3, sessions.size)
    }

    @Test fun query_emptyDatabase() {
        val sessions = history.getRecentSessions(10)
        assertTrue(sessions.isEmpty())
    }

    // ── Field types ────────────────────────────────────

    @Test fun falconRejected() {
        history.record("raw", "clean", "formal", 4, "high_stress",
            asrMs = 200, reconMs = 1500, totalMs = 1700,
            falconApproved = false, riskFlags = listOf("critical_token_lost"))

        val s = history.getRecentSessions(1)[0]
        assertFalse(s.falconApproved)
        assertEquals("critical_token_lost", s.riskFlags)
    }

    @Test fun multipleRiskFlags() {
        history.record("raw", "x", "casual", 2, "default",
            asrMs = 100, reconMs = 500, totalMs = 600,
            falconApproved = false,
            riskFlags = listOf("very_short_output", "large_length_delta", "critical_token_lost"))

        val s = history.getRecentSessions(1)[0]
        assertTrue(s.riskFlags.contains("very_short_output"))
        assertTrue(s.riskFlags.contains("large_length_delta"))
        assertTrue(s.riskFlags.contains("critical_token_lost"))
    }

    @Test fun allLayers() {
        for (layer in 1..4) {
            history.record("raw", "clean", "casual", layer, "default",
                asrMs = 100, reconMs = if (layer > 1) 500 else 0, totalMs = 600,
                falconApproved = true, riskFlags = emptyList())
        }
        val sessions = history.getRecentSessions(10)
        assertEquals(4, sessions.size)
        assertEquals(listOf(4, 3, 2, 1), sessions.map { it.layer })
    }

    @Test fun allTones() {
        for (tone in listOf("casual", "professional", "friend", "formal")) {
            history.record("raw", "clean", tone, 2, "default",
                asrMs = 100, reconMs = 500, totalMs = 600,
                falconApproved = true, riskFlags = emptyList())
        }
        val sessions = history.getRecentSessions(10)
        assertEquals(4, sessions.size)
    }

    @Test fun allSituations() {
        for (sit in listOf("default", "high_stress", "reading")) {
            history.record("raw", "clean", "casual", 2, sit,
                asrMs = 100, reconMs = 500, totalMs = 600,
                falconApproved = true, riskFlags = emptyList())
        }
        val sessions = history.getRecentSessions(10)
        assertEquals(3, sessions.size)
    }

    // ── Timing fields ──────────────────────────────────

    @Test fun timing_l1_noRecon() {
        history.record("raw", "clean", "casual", 1, "default",
            asrMs = 3000, reconMs = 0, totalMs = 3000,
            falconApproved = true, riskFlags = emptyList())

        val s = history.getRecentSessions(1)[0]
        assertEquals(3000L, s.asrMs)
        assertEquals(0L, s.reconMs)
        assertEquals(3000L, s.totalMs)
    }

    @Test fun timing_l4_full() {
        history.record("raw", "clean", "casual", 4, "high_stress",
            asrMs = 2000, reconMs = 3000, totalMs = 5500,
            falconApproved = true, riskFlags = emptyList())

        val s = history.getRecentSessions(1)[0]
        assertEquals(2000L, s.asrMs)
        assertEquals(3000L, s.reconMs)
        assertEquals(5500L, s.totalMs)
    }

    // ── Unicode content ────────────────────────────────

    @Test fun unicodeContent() {
        history.record("я хочу пойти", "я хочу пойти в магазин", "casual", 2, "default",
            asrMs = 100, reconMs = 500, totalMs = 600,
            falconApproved = true, riskFlags = emptyList())

        val s = history.getRecentSessions(1)[0]
        assertEquals("я хочу пойти", s.rawText)
        assertEquals("я хочу пойти в магазин", s.cleanText)
    }

    // ── Schema migration v1 → v2 ─────────────────────

    @Test fun migration_v1ToV2_addsColumns() {
        // Create a v1 schema manually
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("wim_migration_test.db")
        val db = context.openOrCreateDatabase("wim_migration_test.db", Context.MODE_PRIVATE, null)
        db.execSQL("""
            CREATE TABLE sessions (
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
        // Insert a v1 row (no asr_ms or total_ms columns)
        db.execSQL("INSERT INTO sessions (timestamp, raw_text, clean_text, tone, layer, situation, recon_ms) VALUES (1000, 'old raw', 'old clean', 'casual', 2, 'default', 500)")
        db.close()

        // Now run the v2 migration
        val db2 = context.openOrCreateDatabase("wim_migration_test.db", Context.MODE_PRIVATE, null)
        db2.execSQL("ALTER TABLE sessions ADD COLUMN asr_ms INTEGER NOT NULL DEFAULT 0")
        db2.execSQL("ALTER TABLE sessions ADD COLUMN total_ms INTEGER NOT NULL DEFAULT 0")

        // Verify old data survived and new columns have defaults
        val cursor = db2.rawQuery("SELECT raw_text, asr_ms, total_ms FROM sessions", null)
        assertTrue("Should have 1 row", cursor.moveToFirst())
        assertEquals("old raw", cursor.getString(0))
        assertEquals(0L, cursor.getLong(1)) // asr_ms default
        assertEquals(0L, cursor.getLong(2)) // total_ms default
        cursor.close()
        db2.close()
        context.deleteDatabase("wim_migration_test.db")
    }

    @Test fun emojiContent() {
        history.record("hello 😀", "hello", "casual", 1, "default",
            asrMs = 100, reconMs = 0, totalMs = 100,
            falconApproved = true, riskFlags = emptyList())

        val s = history.getRecentSessions(1)[0]
        assertTrue(s.rawText.contains("😀"))
    }
}
