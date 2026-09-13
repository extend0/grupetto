package com.spop.poverlay.zone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Zone 2 is [120, 140) throughout, matching the other zone tests. */
class EffortMonitorTest {

    private val band = ZoneBounds(floor = 120, ceiling = 140)
    private val monitor = EffortMonitor()
    private var now = 0L

    /** Ticks once a second, the way the coordinator will, with conditioning off. */
    private fun feed(seconds: Int, bpm: Int, watts: Float?, inZone: Boolean = true) {
        repeat(seconds) {
            now += 1_000
            monitor.onPower(now, watts)
            monitor.onSample(now, bpm, inZone, smoothingTauMs = 0)
        }
    }

    /** Output only, the way a tick looks once the strap has gone. */
    private fun feedPower(seconds: Int, watts: Float?) {
        repeat(seconds) {
            now += 1_000
            monitor.onPower(now, watts)
        }
    }

    /** Moves [perStep] bpm every [everySeconds] seconds - a steady ramp in either direction. */
    private fun ramp(
        seconds: Int,
        from: Int,
        perStep: Int,
        everySeconds: Int,
        watts: Float? = null,
        inZone: Boolean = true,
    ) {
        for (second in 0 until seconds) {
            now += 1_000
            val bpm = from + (second / everySeconds) * perStep
            monitor.onPower(now, watts)
            monitor.onSample(now, bpm, inZone, smoothingTauMs = 0)
        }
    }

    // --- holding power ----------------------------------------------------------------------

    @Test
    fun `holding power is not claimed until there is enough steady riding behind it`() {
        feed(30, bpm = 130, watts = 150f)
        assertNull(monitor.holdingWatts)

        feed(40, bpm = 130, watts = 150f)
        assertEquals(150f, monitor.holdingWatts!!, 1f)
    }

    @Test
    fun `holding power settles on the least output that held the zone`() {
        feed(120, bpm = 130, watts = 200f)
        feed(120, bpm = 130, watts = 140f)
        // It chased the lower figure down, because that is the one that answers the question.
        assertEquals(140f, monitor.holdingWatts!!, 3f)

        // And it is slow to be talked back up: a minute of working much harder than necessary
        // is not evidence that the harder number is what the zone costs.
        feed(60, bpm = 130, watts = 200f)
        assertTrue("was ${monitor.holdingWatts}", monitor.holdingWatts!! < 170f)
    }

    @Test
    fun `a surge teaches it almost nothing`() {
        feed(70, bpm = 130, watts = 150f)
        assertEquals(150f, monitor.holdingWatts!!, 1f)

        // Heart rate climbing hard at triple the output. None of this is steady state, so
        // almost none of it is allowed to move the estimate.
        ramp(60, from = 130, perStep = 1, everySeconds = 2, watts = 300f)
        assertTrue("was ${monitor.holdingWatts}", monitor.holdingWatts!! < 170f)
    }

    @Test
    fun `riding out of the zone teaches it nothing at all`() {
        feed(120, bpm = 100, watts = 300f, inZone = false)
        assertNull(monitor.holdingWatts)
    }

    @Test
    fun `a gap with nothing arriving at all starts a new session`() {
        feed(70, bpm = 130, watts = 150f)
        assertNotNull(monitor.holdingWatts)

        // Three minutes of silence - no heart rate, and not a pedal stroke either. Whatever
        // comes next is a different ride, quite possibly a different rider.
        now += 180_000
        monitor.onPower(now, 150f)
        monitor.onSample(now, 130, inZone = true, smoothingTauMs = 0)
        assertNull(monitor.holdingWatts)
        assertNull(monitor.trendBpmPerMin)
    }

    // --- trend ------------------------------------------------------------------------------

    @Test
    fun `a flat heart rate trends at nothing`() {
        feed(120, bpm = 130, watts = 150f)
        assertEquals(0f, monitor.trendBpmPerMin!!, 0.5f)
    }

    @Test
    fun `the trend reports the rate of climb`() {
        // One bpm every five seconds is 12 a minute.
        ramp(200, from = 100, perStep = 1, everySeconds = 5)
        assertEquals(12f, monitor.trendBpmPerMin!!, 3f)
    }

    @Test
    fun `the trend reports a fall as a negative rate`() {
        ramp(200, from = 180, perStep = -1, everySeconds = 5)
        assertEquals(-12f, monitor.trendBpmPerMin!!, 3f)
    }

    // --- headroom ---------------------------------------------------------------------------

    @Test
    fun `a rider who is not falling has no countdown`() {
        feed(120, bpm = 130, watts = 150f)
        assertNull(monitor.headroomSeconds(130, band.floor))
    }

    @Test
    fun `a rider who is climbing has no countdown`() {
        ramp(200, from = 100, perStep = 1, everySeconds = 5)
        assertNull(monitor.headroomSeconds(140, band.floor))
    }

    @Test
    fun `headroom is the margin divided by the rate it is being spent at`() {
        // Falling 12 a minute, ending at 160 - forty bpm above the floor, so a little over
        // three minutes of room at this rate.
        ramp(200, from = 200, perStep = -1, everySeconds = 5)
        val headroom = monitor.headroomSeconds(160, band.floor)!!
        assertTrue("was $headroom", headroom in 150..260)
    }

    @Test
    fun `an open-ended zone has no floor to count down to`() {
        ramp(200, from = 200, perStep = -1, everySeconds = 5)
        assertNull(monitor.headroomSeconds(160, null))
    }

    // --- health -----------------------------------------------------------------------------

    @Test
    fun `health tracks where the rider sits in the band`() {
        feed(120, bpm = 130, watts = 150f)
        // Halfway up a twenty bpm band, output exactly at what has been holding it.
        assertEquals(0.5f, monitor.health(130, band), 0.05f)
        assertEquals(0.25f, monitor.health(125, band), 0.05f)
    }

    @Test
    fun `health collapses when the output goes even though the heart rate has not`() {
        feed(120, bpm = 130, watts = 150f)
        val holding = monitor.health(130, band)

        // The rider eases right off. Nothing in the heart rate has happened yet, and will not
        // for another half a minute - the output is the only thing that gives it away.
        feedPower(15, 100f)
        val easedOff = monitor.health(130, band)
        assertTrue("$easedOff should be well under $holding", easedOff < holding / 2f)

        feedPower(20, 60f)
        assertEquals(0f, monitor.health(130, band), 0.01f)
    }

    @Test
    fun `health is full above the band`() {
        feed(120, bpm = 130, watts = 150f)
        assertEquals(1f, monitor.health(150, band), 0.01f)
    }

    @Test
    fun `health is nothing below the band`() {
        feed(120, bpm = 130, watts = 150f)
        assertEquals(0f, monitor.health(110, band), 0.01f)
    }

    @Test
    fun `health ignores output until there is a holding figure to compare against`() {
        feed(20, bpm = 130, watts = 20f)
        assertNull(monitor.holdingWatts)
        // Buffer alone, rather than a score built on a baseline that has not been earned.
        assertEquals(0.5f, monitor.health(130, band), 0.05f)
    }

    // --- vouching for a rider with no strap ---------------------------------------------------

    @Test
    fun `nothing is vouched for before a holding figure exists`() {
        feed(20, bpm = 130, watts = 300f)
        assertNull(monitor.holdingWatts)
        // Pedalling hard is not evidence on its own. Only a rider who has already shown, with
        // a heart rate, what their zone costs can be taken on trust later.
        assertTrue(!monitor.vouchesForEffort())
    }

    @Test
    fun `holding the output that holds the zone vouches for the rider`() {
        feed(70, bpm = 130, watts = 150f)
        assertTrue(monitor.vouchesForEffort())

        // Still working, if a little softer. Output wanders; the bar is not a knife edge.
        feedPower(20, 140f)
        assertTrue(monitor.vouchesForEffort())
    }

    @Test
    fun `easing right off stops vouching for the rider`() {
        feed(70, bpm = 130, watts = 150f)
        feedPower(30, 90f)
        assertTrue(!monitor.vouchesForEffort())
    }

    @Test
    fun `stopping stops vouching for the rider`() {
        feed(70, bpm = 130, watts = 150f)
        feedPower(40, 0f)
        assertTrue(!monitor.vouchesForEffort())
    }

    @Test
    fun `a strap that dies for minutes does not cost the rider what output already taught`() {
        feed(70, bpm = 130, watts = 150f)
        val holding = monitor.holdingWatts!!

        // Three minutes blind, still pedalling. The trend is gone - nobody knows what the
        // heart rate did - but what the zone costs was never in doubt.
        feedPower(180, 150f)
        assertTrue(monitor.vouchesForEffort())

        monitor.onSample(now, 130, inZone = true, smoothingTauMs = 0)
        assertNull(monitor.trendBpmPerMin)
        assertEquals(holding, monitor.holdingWatts!!, 1f)
    }

    @Test
    fun `a short countdown caps health however good the rest looks`() {
        // High in the band and still at holding power, but heading down fast enough that the
        // floor is seconds away. The buffer is real and it is nearly spent.
        ramp(200, from = 240, perStep = -1, everySeconds = 1, watts = 150f)
        val headroom = monitor.headroomSeconds(135, band.floor)!!
        assertTrue("was $headroom", headroom < 30)
        assertTrue(monitor.health(135, band) < 0.6f)
    }
}
