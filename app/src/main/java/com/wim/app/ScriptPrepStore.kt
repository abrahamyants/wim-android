package com.wim.app

/**
 * User-provided context for a prepared talk, difficult sentence, names, or jargon.
 * It is sent as model context only; it is never inserted into the dictated text.
 * Context stays active until the user clears or disables it so a whole practice
 * session can reuse the same preparation.
 */
object ScriptPrepStore {
    private const val KEY_TEXT = "script_prep_text"
    private const val KEY_ENABLED = "script_prep_enabled"
    private const val KEY_UPDATED = "script_prep_updated_ms"
    const val MAX_CHARS = 6000

    fun text(): String = WimApp.prefs.getString(KEY_TEXT, "")?.trim().orEmpty()

    fun isEnabled(): Boolean = WimApp.prefs.getBoolean(KEY_ENABLED, false) && text().isNotBlank()

    fun activeContext(): String = if (isEnabled()) text() else ""

    fun updatedAtMs(): Long = WimApp.prefs.getLong(KEY_UPDATED, 0L)

    fun save(value: String, enabled: Boolean = true) {
        val clean = value.trim().take(MAX_CHARS)
        WimApp.prefs.edit()
            .putString(KEY_TEXT, clean)
            .putBoolean(KEY_ENABLED, enabled && clean.isNotBlank())
            .putLong(KEY_UPDATED, System.currentTimeMillis())
            .apply()
    }

    fun setEnabled(enabled: Boolean) {
        WimApp.prefs.edit().putBoolean(KEY_ENABLED, enabled && text().isNotBlank()).apply()
    }

    fun clear() {
        WimApp.prefs.edit()
            .remove(KEY_TEXT)
            .remove(KEY_UPDATED)
            .putBoolean(KEY_ENABLED, false)
            .apply()
    }
}
