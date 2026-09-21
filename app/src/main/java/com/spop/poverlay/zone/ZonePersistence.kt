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
        private const val KeyLastPedaledAtMs = "zoneLastPedaledAtMs"
        private const val KeyMediaPaused = "zoneMediaPausedByUs"

    }

    val workoutSettings = com.spop.poverlay.workout.WorkoutSettings(context)

    private val prefs = context.getSharedPreferences(
        ConfigurationRepository.SharedPrefsName,
        Context.MODE_PRIVATE,
    )

    fun save(creditMs: Long, mediaPaused: Boolean, lastPedaledAtMs: Long?) {
        prefs.edit {
            putLong(KeyCreditMs, creditMs)
            if (lastPedaledAtMs == null) remove(KeyLastPedaledAtMs)
            else putLong(KeyLastPedaledAtMs, lastPedaledAtMs)
            putBoolean(KeyMediaPaused, mediaPaused)
        }
    }

    fun restoreLastPedaledAt(nowMs: Long = System.currentTimeMillis()): Long? =
        prefs.getLong(KeyLastPedaledAtMs, 0L).takeIf {
            it > 0 && WorkoutSession.isRecent(it, nowMs, workoutSettings.timeoutMs)
        }

    fun restoreCredit(nowMs: Long = System.currentTimeMillis()): Long {
        if (restoreLastPedaledAt(nowMs) == null) return 0L
        return prefs.getLong(KeyCreditMs, 0L).coerceAtLeast(0L)
    }

    /** Never restart yesterday's video when restoring an expired workout. */
    fun wasMediaLeftPaused(nowMs: Long = System.currentTimeMillis()): Boolean =
        restoreLastPedaledAt(nowMs) != null && prefs.getBoolean(KeyMediaPaused, false)

    fun clear() {
        prefs.edit {
            remove(KeyCreditMs)
            remove(KeyLastPedaledAtMs)
            remove(KeyMediaPaused)
        }
    }
}
