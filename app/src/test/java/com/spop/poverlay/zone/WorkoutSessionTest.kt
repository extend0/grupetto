package com.spop.poverlay.zone

import org.junit.Assert.*
import org.junit.Test

class WorkoutSessionTest {
    private val hour = 60 * 60 * 1000L
    private val start = 1_000_000L

    @Test fun `expires at sixty minutes exactly and only once`() {
        val ride = WorkoutSession()
        ride.onCadence(80f, start, 0)
        assertFalse(ride.expire(start + hour - 1, hour - 1))
        assertTrue(ride.expire(start + hour, hour))
        assertFalse(ride.active)
        assertFalse(ride.expire(start + hour + 1, hour + 1))
    }

    @Test fun `stationary samples do not extend the workout`() {
        val ride = WorkoutSession()
        ride.onCadence(80f, start, 0)
        ride.onCadence(0f, start + hour - 1, hour - 1)
        assertTrue(ride.expire(start + hour, hour))
    }

    @Test fun `sleep without sensor events expires before returning rider starts`() {
        val ride = WorkoutSession()
        ride.onCadence(80f, start, 0)
        assertTrue(ride.expire(start + 8 * hour, 8 * hour))
        ride.onCadence(70f, start + 8 * hour, 8 * hour)
        assertTrue(ride.active)
        assertTrue(ride.isMoving(8 * hour))
        assertFalse(ride.expire(start + 8 * hour + 1, 8 * hour + 1))
    }

    @Test fun `restart preserves remaining time rather than granting another hour`() {
        val ride = WorkoutSession(start)
        assertFalse(ride.expire(start + hour / 2, 0))
        assertTrue(ride.expire(start + hour, hour / 2))
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
        assertFalse(ride.expire(start + 8 * hour, 1_000))
        assertTrue(ride.expire(start - 1_000, hour))
    }

    @Test fun `invalid cadence cannot start a workout`() {
        val ride = WorkoutSession()
        listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY).forEach {
            ride.onCadence(it, start, 0)
            assertFalse(ride.active)
            assertFalse(ride.isMoving(0))
        }
    }
}
