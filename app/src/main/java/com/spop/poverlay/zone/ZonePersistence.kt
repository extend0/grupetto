package com.spop.poverlay.zone

import android.content.Context
import androidx.core.content.edit
import com.spop.poverlay.ConfigurationRepository

/**
 * Carries goal credit across a process restart.
 *
 * This is not a nicety: CadenceWatchdog kills the process after 30 minutes without cadence, and
 * OverlayService.restartToOverlay() exits outright. Without this, a 40-minute goal could
 * silently reset to zero.
 *
 * It also records whether we left media paused, so a freshly started service can hand it back
 * rather than leaving the rider staring at a frozen frame.
 */
class ZonePersistence(context: Context) {

    companion object {
        private const val KeyCreditMs = "zoneCreditMs"
        private const val KeySavedAtMs = "zoneCreditSavedAtMs"
        private const val KeyMediaPaused = "zoneMediaPausedByUs"

        /** Credit older than this belongs to a previous ride, not this one. */
        const val StaleCreditMs = 10 * 60 * 1000L
    }

    private val prefs = context.getSharedPreferences(
        ConfigurationRepository.SharedPrefsName,
        Context.MODE_PRIVATE,
    )

    fun save(creditMs: Long, mediaPaused: Boolean) {
        prefs.edit {
            putLong(KeyCreditMs, creditMs)
            putLong(KeySavedAtMs, System.currentTimeMillis())
            putBoolean(KeyMediaPaused, mediaPaused)
        }
    }

    fun restoreCredit(nowMs: Long = System.currentTimeMillis()): Long {
        val savedAt = prefs.getLong(KeySavedAtMs, 0L)
        if (savedAt == 0L || nowMs - savedAt > StaleCreditMs) return 0L
        return prefs.getLong(KeyCreditMs, 0L).coerceAtLeast(0L)
    }

    /** True if a previous process died while holding the media paused. */
    fun wasMediaLeftPaused(): Boolean = prefs.getBoolean(KeyMediaPaused, false)

    fun clear() {
        prefs.edit {
            remove(KeyCreditMs)
            remove(KeySavedAtMs)
            remove(KeyMediaPaused)
        }
    }
}
