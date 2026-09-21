package com.spop.poverlay.zone

import org.junit.Assert.*
import org.junit.Test

class WorkoutSessionTest {
    private val timeout = 25 * 60 * 1000L
    private val start = 1_000_000L

    @Test fun `expires at twenty five minutes exactly and only once`() {
        val ride = WorkoutSession()
        ride.onCadence(80f, start, 0)
        assertFalse(ride.expire(start + timeout - 1, timeout - 1))
        assertTrue(ride.expire(start + timeout, timeout))
        assertFalse(ride.active)
        assertFalse(ride.expire(start + timeout + 1, timeout + 1))
    }

    @Test fun `stationary samples do not extend the workout`() {
        val ride = WorkoutSession()
        ride.onCadence(80f, start, 0)
        ride.onCadence(0f, start + timeout - 1, timeout - 1)
        assertTrue(ride.expire(start + timeout, timeout))
    }

    @Test fun `sleep without sensor events expires before returning rider starts`() {
        val ride = WorkoutSession()
        ride.onCadence(80f, start, 0)
        assertTrue(ride.expire(start + 8 * timeout, 8 * timeout))
        ride.onCadence(70f, start + 8 * timeout, 8 * timeout)
        assertTrue(ride.active)
        assertTrue(ride.isMoving(8 * timeout))
        assertFalse(ride.expire(start + 8 * timeout + 1, 8 * timeout + 1))
    }

    @Test fun `restart preserves remaining time rather than granting another timeout period`() {
        val ride = WorkoutSession(start)
        assertFalse(ride.expire(start + timeout / 2, 0))
        assertTrue(ride.expire(start + timeout, timeout / 2))
    }

    @Test fun `stale cadence stops movement without prematurely ending ride`() {
        val ride = WorkoutSession()
        ride.onCadence(80f, start, 0)
        assertFalse(ride.isMoving(10_001))
        assertFalse(ride.expire(start + 10_001, 10_001))
    }

    @Test fun `live timeout uses monotonic time despite wall clock changes`() {
        val ride = WorkoutSession()
        ride.onCadence(80f, start, 0)
        assertFalse(ride.expire(start + 8 * timeout, 1_000))
        assertTrue(ride.expire(start - 1_000, timeout))
    }

    @Test fun `invalid cadence cannot start a workout`() {
        val ride = WorkoutSession()
        listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY).forEach {
            ride.onCadence(it, start, 0)
            assertFalse(ride.active)
            assertFalse(ride.isMoving(0))
        }
    }
    @Test fun `timeout setting is shared and takes effect during a session`() {
        var timeout = 25 * 60_000L
        val ride = WorkoutSession(timeoutMs = { timeout })
        ride.onCadence(80f, start, 0)
        assertFalse(ride.expire(start + 15 * 60_000L, 15 * 60_000L))
        timeout = 15 * 60_000L
        assertTrue(ride.expire(start + 15 * 60_000L, 15 * 60_000L))
    }

    @Test fun `disabled timeout survives inactivity and persisted restore`() {
        val ride = WorkoutSession(timeoutMs = { 0 })
        ride.onCadence(80f, start, 0)
        assertFalse(ride.expire(start + 24 * 60 * 60_000L, 24 * 60 * 60_000L))
        assertTrue(WorkoutSession.isRecent(start, start + 24 * 60 * 60_000L, 0))
        assertEquals(25 * 60_000L, WorkoutSession.InactivityTimeoutMs)
    }
}
