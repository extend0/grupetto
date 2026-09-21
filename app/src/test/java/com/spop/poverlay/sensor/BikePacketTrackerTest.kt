package com.spop.poverlay.sensor

import org.junit.Assert.*
import org.junit.Test

class BikePacketTrackerTest {
    private val riding = BikeReading(192f, 59f, 62f)

    @Test fun `cached packets cannot keep a frozen ride alive`() {
        val tracker = BikePacketTracker()
        assertTrue(tracker.accept("packet-1", riding, 0))
        for (now in 1L..20L) assertFalse(tracker.accept("packet-1", riding, now * 1_000))
        assertTrue(tracker.current(20_000).power.isNaN())
        assertTrue(tracker.current(20_000).cadence.isNaN())
        assertTrue(tracker.current(20_000).resistance.isNaN())
        assertEquals(20_000L, tracker.age(20_000))
    }

    @Test fun `steady effort with new source packets stays live`() {
        val tracker = BikePacketTracker()
        for (now in 0L..120L) {
            assertTrue(tracker.accept("packet-$now", riding, now * 1_000))
            assertEquals(riding, tracker.current(now * 1_000))
        }
    }

    @Test fun `silence expires exactly at deadline and a fresh packet restores metrics`() {
        val tracker = BikePacketTracker()
        tracker.accept("first", riding, 100)
        assertEquals(riding, tracker.current(10_099))
        assertTrue(tracker.current(10_100).power.isNaN())
        tracker.accept("second", riding.copy(power = 200f), 20_000)
        assertEquals(200f, tracker.current(20_000).power)
    }

    @Test fun `rebind cannot refresh cached source data`() {
        val tracker = BikePacketTracker()
        tracker.accept("first", riding, 100)
        tracker.invalidate()
        assertFalse(tracker.accept("first", riding, 200))
        assertTrue(tracker.current(200).power.isNaN())
        assertTrue(tracker.accept("second", riding, 300))
        assertEquals(riding, tracker.current(300))
    }

    @Test fun `firmware without packet timestamps uses callback freshness without rejecting steady effort`() {
        val tracker = BikePacketTracker()
        tracker.accept(null, riding, 0)
        assertTrue(tracker.accept("", riding, 20_000))
        assertEquals(riding, tracker.current(20_000))
        assertTrue(tracker.current(30_000).power.isNaN())
    }
    @Test fun `invalid readings never become live effort`() {
        val tracker = BikePacketTracker()
        tracker.accept("good", riding, 0)
        assertFalse(tracker.accept("bad", riding.copy(power = Float.NaN), 5_000))
        assertTrue(tracker.current(5_000).power.isNaN())
        assertFalse(tracker.accept("bad", riding.copy(cadence = -1f), 6_000))
        assertEquals(6_000L, tracker.age(6_000))
        assertTrue(tracker.accept("recovered", riding, 7_000))
    }

}
