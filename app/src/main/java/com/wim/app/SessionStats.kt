package com.wim.app

/**
 * The dashboard dials, as arithmetic rather than as a view.
 *
 * Extracted 2026-07-29 after the operator asked whether the dials measure
 * anything real. They did read live data — but two were wrong:
 *
 *  - SESSIONS showed `sessions.size` from a query capped at 50 rows, against a
 *    gauge whose maximum is 500. Past fifty sessions it pinned at fifty
 *    forever. The true total was one `COUNT(*)` away.
 *  - WPM divided words by `asr_ms`, which is recording time PLUS transcription
 *    latency, so it always under-reported the speaker's real rate — by
 *    whatever the model took, which on cloud Whisper is seconds.
 */
object SessionStats {

    /**
     * Words per minute of actual speech.
     *
     * Only rows carrying a real [SessionHistory.Session.speechMs] count: rows
     * written before schema v3 hold 0, and including them would divide by a
     * duration that was never measured. Returns 0 when nothing qualifies,
     * which the dial renders as "—" rather than a fabricated number.
     */
    fun wpm(sessions: List<SessionHistory.Session>): Int {
        var words = 0
        var ms = 0L
        for (s in sessions) {
            if (s.speechMs <= 0L) continue
            words += wordCount(s.cleanText)
            ms += s.speechMs
        }
        if (ms <= 0L) return 0
        return (words * 60_000.0 / ms).toInt()
    }

    /**
     * Mean percentage of words removed between raw ASR and final text.
     *
     * Reconstruction can legitimately ADD words (a block leaves a gap that gets
     * filled from context), which produced negative "cleanup" on the old
     * arithmetic. Per-session values are floored at 0 so the dial reads as
     * "how much was stripped", never as a negative amount removed.
     */
    fun averageCleanupPercent(sessions: List<SessionHistory.Session>): Int {
        if (sessions.isEmpty()) return 0
        var total = 0
        for (s in sessions) {
            val raw = wordCount(s.rawText)
            if (raw <= 0) continue
            val clean = wordCount(s.cleanText)
            total += (((raw - clean).toDouble() / raw) * 100).toInt().coerceAtLeast(0)
        }
        return total / sessions.size
    }

    fun totalWords(sessions: List<SessionHistory.Session>): Int =
        sessions.sumOf { wordCount(it.cleanText) }

    private fun wordCount(text: String): Int =
        text.split("\\s+".toRegex()).count { it.isNotBlank() }
}
