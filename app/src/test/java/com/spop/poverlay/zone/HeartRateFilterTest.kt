package com.spop.poverlay.zone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tau is 10s throughout unless a test says otherwise, matching the shipped default. */
class HeartRateFilterTest {

    private val tau = 10_000L
    private val filter = HeartRateFilter()

    /** Feeds one sample a second, the way a strap reports, and returns the last value. */
    private fun feed(bpm: Int, seconds: Int, fromSecond: Int, tauMs: Long = tau): Int {
        var value = filter.value ?: bpm
        for (second in fromSecond until fromSecond + seconds) {
            value = filter.accept(bpm, second * 1000L, tauMs)
        }
        return value
    }

    @Test
    fun `the first sample seeds rather than ramping up from nothing`() {
        assertNull(filter.value)
        assertEquals(130, filter.accept(130, 0, tau))
    }

    @Test
    fun `a single artifact is discarded rather than averaged in`() {
        filter.accept(130, 0, tau)
        filter.accept(130, 1_000, tau)
        // The median outvotes it outright: the dropout never reaches the average at all.
        assertEquals(130, filter.accept(40, 2_000, tau))
        assertEquals(130, filter.accept(130, 3_000, tau))
    }

    @Test
    fun `two artifacts in a row get past the median but not past the average`() {
        feed(130, 5, 0)
        filter.accept(40, 5_000, tau)
        assertEquals(130, filter.value)

        // Two of three does carry the median - the window is only three wide. The average is
        // the second line of defence, and it is the one that matters: a 90 bpm lie moves the
        // judged value by single digits, nowhere near far enough to drop a zone.
        val afterSecond = filter.accept(40, 6_000, tau)
        assertTrue("was $afterSecond", afterSecond in 118..125)
    }

    @Test
    fun `the average converges on a sustained step`() {
        filter.accept(100, 0, tau)
        val partway = feed(160, 11, 1)
        // One time constant in, most of the way there but unmistakably still lagging.
        assertTrue("was $partway", partway in 130..150)

        val settled = feed(160, 49, 12)
        assertEquals(160, settled)
    }

    @Test
    fun `a gap longer than the time constant snaps instead of blending`() {
        feed(160, 5, 0)
        assertEquals(160, filter.value)
        // Two minutes of silence, then a genuinely different heart rate. Blending a reading
        // from before the gap would hold the rider at a number they left long ago.
        assertEquals(100, filter.accept(100, 125_000, tau))
    }

    @Test
    fun `conditioning off passes the strap through untouched`() {
        filter.accept(130, 0, 0)
        assertEquals(40, filter.accept(40, 1_000, 0))
        assertEquals(130, filter.accept(130, 2_000, 0))
    }

    @Test
    fun `switching conditioning on does not inherit unconditioned history`() {
        filter.accept(130, 0, 0)
        filter.accept(40, 1_000, 0)
        // The raw 40 was never part of a median or an average; the first smoothed sample seeds.
        assertEquals(130, filter.accept(130, 2_000, tau))
    }

    @Test
    fun `reset drops the history`() {
        feed(160, 5, 0)
        filter.reset()
        assertNull(filter.value)
        assertEquals(100, filter.accept(100, 6_000, tau))
    }

    @Test
    fun `a sample that arrives at the same instant does not divide by a zero gap`() {
        filter.accept(130, 1_000, tau)
        assertEquals(130, filter.accept(140, 1_000, tau))
    }
}
