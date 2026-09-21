package com.spop.poverlay.zone

import android.content.Context
import android.content.SharedPreferences
import io.mockk.*
import org.junit.Assert.*
import org.junit.Test

class ZonePersistenceTest {
    private val values = mutableMapOf<String, Any>()
    private val editor = mockk<SharedPreferences.Editor>(relaxed = true) {
        every { putLong(any(), any()) } answers { values[firstArg()] = secondArg<Long>(); this@mockk }
        every { putBoolean(any(), any()) } answers { values[firstArg()] = secondArg<Boolean>(); this@mockk }
        every { remove(any()) } answers { values.remove(firstArg<String>()); this@mockk }
    }
    private val prefs = mockk<SharedPreferences> {
        every { edit() } returns editor
        every { getInt(any(), any()) } answers { values[firstArg()] as? Int ?: secondArg() }
        every { getLong(any(), any()) } answers { values[firstArg()] as? Long ?: secondArg() }
        every { getBoolean(any(), any()) } answers { values[firstArg()] as? Boolean ?: secondArg() }
    }
    private val context = mockk<Context> {
        every { getSharedPreferences(any(), any()) } returns prefs
    }
    private val persistence = ZonePersistence(context)
    private val start = 1_000_000L

    @Test fun `repeated idle saves cannot renew credit or orphaned media`() {
        repeat(10) { persistence.save(123_000, true, start) }
        assertEquals(123_000L, persistence.restoreCredit(start + 1_499_999))
        assertTrue(persistence.wasMediaLeftPaused(start + 1_499_999))
        assertEquals(0L, persistence.restoreCredit(start + 1_500_000))
        assertFalse(persistence.wasMediaLeftPaused(start + 1_500_000))
        assertNull(persistence.restoreLastPedaledAt(start + 1_500_000))
    }

    @Test fun `legacy credit without pedal timestamp does not revive an old ride`() {
        values["zoneCreditMs"] = 123_000L
        values["zoneCreditSavedAtMs"] = start
        values["zoneMediaPausedByUs"] = true
        assertEquals(0L, persistence.restoreCredit(start))
        assertFalse(persistence.wasMediaLeftPaused(start))
    }

    @Test fun `ended workout clears saved pedal time`() {
        persistence.save(123_000, true, start)
        persistence.save(0, false, null)
        assertNull(persistence.restoreLastPedaledAt(start))
        assertEquals(0L, persistence.restoreCredit(start))
    }
    @Test fun `persisted sessions honor configured timeout including off`() {
        persistence.save(123_000, true, start)
        values["workoutInactivityMinutes"] = 15
        assertEquals(0L, persistence.restoreCredit(start + 900_000))
        values["workoutInactivityMinutes"] = 0
        assertEquals(123_000L, persistence.restoreCredit(start + 86_400_000))
    }
}
