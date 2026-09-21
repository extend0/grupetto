package com.spop.poverlay.zone

import org.junit.Assert.*
import org.junit.Test

class EarlyEffortWarningTest {
    private val warning = EarlyEffortWarning()

    @Test fun `power decline must persist and improvement must settle`() {
        assertFalse(warning.update(0, true, 0.7f, 0f, null))
        assertFalse(warning.update(4_999, true, 0.7f, 0f, null))
        assertTrue(warning.update(5_000, true, 0.7f, 0f, null))
        assertTrue(warning.update(6_000, true, 1f, 0f, null))
        assertTrue(warning.update(8_999, true, 1f, 0f, null))
        assertFalse(warning.update(9_000, true, 1f, 0f, null))
    }

    @Test fun `one dip cannot accumulate time across good readings`() {
        warning.update(0, true, 0.7f, null, null)
        warning.update(4_000, true, 1f, null, null)
        assertFalse(warning.update(6_000, true, 0.7f, null, null))
        assertFalse(warning.update(10_000, true, 0.7f, null, null))
    }

    @Test fun `a falling heart rate near the boundary works without a power baseline`() {
        warning.update(0, true, null, -3f, 40)
        assertTrue(warning.update(5_000, true, null, -3f, 35))
    }

    @Test fun `steady heart rate near the boundary is not a warning`() {
        warning.update(0, true, null, 0f, null)
        assertFalse(warning.update(60_000, true, null, 0f, null))
    }

    @Test fun `leaving eligibility clears warning immediately and resets its timer`() {
        warning.update(0, true, 0.7f, null, null)
        assertTrue(warning.update(5_000, true, 0.7f, null, null))
        assertFalse(warning.update(5_001, false, 0.7f, null, null))
        assertFalse(warning.update(9_000, true, 0.7f, null, null))
    }
}
