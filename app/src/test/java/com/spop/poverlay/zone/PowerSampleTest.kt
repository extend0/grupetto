package com.spop.poverlay.zone

import org.junit.Assert.*
import org.junit.Test

class PowerSampleTest {
    @Test fun `repeated reads do not refresh a sample`() {
        val sample = PowerSample(150f, 1_000L)
        assertEquals(150f, sample.freshWatts(11_000L))
        assertNull(sample.freshWatts(11_001L))
        assertNull(sample.freshWatts(500L))
        assertEquals(150f, PowerSample(150f, 11_001L).freshWatts(11_002L))
    }

    @Test fun `invalid output is unavailable`() {
        for (watts in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f)) {
            assertNull(PowerSample(watts, 0L).freshWatts(0L))
        }
        assertEquals(0f, PowerSample(0f, 0L).freshWatts(0L))
    }
}
