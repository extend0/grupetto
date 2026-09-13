package com.spop.poverlay.zone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The machine is pure, so these drive it with a fake clock and assert on states and effects.
 * Zone 2 is [120, 140) throughout; with a 4 bpm hysteresis, re-entry needs 124..135.
 *
 * Signal conditioning is off in [baseConfig] so that a bpm handed to a tick is the bpm the
 * machine judges. That keeps these tests about state transitions; the filter has its own tests,
 * and the handful of cases below that care about the two together switch it on explicitly.
 */
class ZoneEnforcerTest {

    private val boundaries = listOf(120, 140, 160, 180)

    private val baseConfig = EnforcementConfig(
        enabled = true,
        penaltyEnabled = true,
        targetZone = 2,
        goalSeconds = 600,
        graceSeconds = 30,
        warningSeconds = 15,
        recoveryHoldSeconds = 5,
        hysteresisBpm = 4,
        smoothingTauSeconds = 0,
        armAfterZoneSeconds = 0,
        staleHrMs = 10_000,
        suspendWhenStopped = false,
    )

    /** Conditioning on, at the shipped time constant. */
    private val smoothed = baseConfig.copy(smoothingTauSeconds = 10)

    /** Warm-up on: a minute of held zone before anything can pause a video. */
    private val warmUp = baseConfig.copy(armAfterZoneSeconds = 60)

    private lateinit var enforcer: ZoneEnforcer
    private var now = 0L
    private val seen = mutableListOf<PenaltyEffect>()
    private lateinit var last: EnforcementSnapshot

    private fun start(config: EnforcementConfig = baseConfig): EnforcementSnapshot {
        enforcer = ZoneEnforcer(config)
        now = 0L
        seen.clear()
        return tick(130)
    }

    private fun tick(
        bpm: Int?,
        hrAgeMs: Long = 0,
        isMoving: Boolean = true,
        watts: Float? = null,
        overlaySuppressed: Boolean = false,
    ): EnforcementSnapshot {
        last = enforcer.tick(
            TickInput(now, bpm, hrAgeMs, boundaries, isMoving, watts, overlaySuppressed)
        )
        seen += last.effects
        return last
    }

    /** Ticks once a second for [ms], the way the coordinator will. */
    private fun advance(
        ms: Long,
        bpm: Int?,
        hrAgeMs: Long = 0,
        isMoving: Boolean = true,
        watts: Float? = null,
        overlaySuppressed: Boolean = false,
    ): EnforcementSnapshot {
        var remaining = ms
        while (remaining > 0) {
            val step = minOf(1_000L, remaining)
            now += step
            tick(bpm, hrAgeMs, isMoving, watts, overlaySuppressed)
            remaining -= step
        }
        return last
    }

    /**
     * Starts where a real ride starts - off the pace - instead of with [start]'s in-zone tick.
     */
    private fun startCold(
        config: EnforcementConfig = warmUp,
        bpm: Int = 70,
    ): EnforcementSnapshot {
        enforcer = ZoneEnforcer(config)
        now = 0L
        seen.clear()
        return tick(bpm)
    }

    private fun countOf(effect: PenaltyEffect) = seen.count { it == effect }

    /** Drives all the way from in-zone to a live penalty. */
    private fun reachPenalty(): EnforcementSnapshot {
        start()
        advance(31_000, 100)
        advance(16_000, 100)
        assertEquals(EnforcementState.PENALTY, last.state)
        return last
    }

    // --- credit -----------------------------------------------------------------------------

    @Test
    fun `credit accrues only while inside the band`() {
        start()
        advance(10_000, 130)
        assertEquals(10, last.creditSeconds)

        advance(20_000, 100)
        assertEquals(10, last.creditSeconds)

        // Five more in-band ticks, but the first of them is the one that discovers the rider is
        // back, so the interval it covers is not paid out. Credit is only ever granted for
        // intervals observed in-zone at both ends - an excursion costs at most one tick.
        advance(5_000, 130)
        assertEquals(14, last.creditSeconds)
    }

    @Test
    fun `credit is frozen through grace warning and penalty`() {
        start()
        advance(10_000, 130)
        reachPenaltyFromHere()
        assertEquals(10, last.creditSeconds)
    }

    private fun reachPenaltyFromHere() {
        advance(31_000, 100)
        advance(16_000, 100)
        assertEquals(EnforcementState.PENALTY, last.state)
    }

    @Test
    fun `a late tick cannot gift more than the clamp`() {
        start()
        // Process frozen for ten minutes, then one tick.
        now += 600_000
        tick(130)
        assertEquals(ZoneEnforcer.MaxTickDeltaMs / 1000, last.creditSeconds)
    }

    @Test
    fun `a backwards clock credits nothing`() {
        start()
        advance(5_000, 130)
        now -= 60_000
        tick(130)
        assertEquals(5, last.creditSeconds)
    }

    // --- escalation -------------------------------------------------------------------------

    @Test
    fun `dropping below the floor does not penalize before grace expires`() {
        start()
        advance(29_000, 100)
        assertEquals(EnforcementState.GRACE, last.state)
        assertEquals(Drift.BELOW, last.drift)
        assertEquals(0, countOf(PenaltyEffect.PauseMedia))
    }

    @Test
    fun `returning to the zone during grace emits nothing`() {
        start()
        advance(20_000, 100)
        advance(1_000, 130)
        assertEquals(EnforcementState.IN_ZONE, last.state)
        assertTrue(seen.none { it is PenaltyEffect.PauseMedia || it is PenaltyEffect.ShowCurtain })
    }

    @Test
    fun `grace then warning then penalty pauses media exactly once`() {
        start()
        advance(31_000, 100)
        assertEquals(EnforcementState.WARNING, last.state)
        assertEquals(0, countOf(PenaltyEffect.PauseMedia))

        advance(16_000, 100)
        assertEquals(EnforcementState.PENALTY, last.state)
        assertEquals(1, countOf(PenaltyEffect.PauseMedia))
        assertEquals(1, countOf(PenaltyEffect.ShowCurtain))
    }

    @Test
    fun `effects are edge triggered and never repeat`() {
        reachPenalty()
        advance(120_000, 100)
        assertEquals(1, countOf(PenaltyEffect.PauseMedia))
        assertEquals(1, countOf(PenaltyEffect.ShowCurtain))
        assertEquals(0, countOf(PenaltyEffect.ResumeMedia))
    }

    @Test
    fun `the warning scrim ramps up and clears on return`() {
        start()
        advance(31_000, 100)
        val atStart = last.scrimAlpha
        advance(7_000, 100)
        assertTrue("scrim should ramp", last.scrimAlpha > atStart)

        advance(1_000, 130)
        assertEquals(0f, last.scrimAlpha, 0.001f)
        assertTrue(seen.contains(PenaltyEffect.SetScrimAlpha(0f)))
    }

    @Test
    fun `the countdown reports how long until the video pauses`() {
        start()
        advance(31_000, 100)
        assertEquals(EnforcementState.WARNING, last.state)
        assertEquals(15L, last.secondsUntilPenalty)

        advance(10_000, 100)
        assertEquals(5L, last.secondsUntilPenalty)
    }

    @Test
    fun `there is no countdown when nothing is going to happen`() {
        // Over the ceiling: warns, but never pauses, so a countdown would be a lie.
        start()
        advance(60_000, 150)
        assertEquals(EnforcementState.WARNING, last.state)
        assertNull(last.secondsUntilPenalty)

        // Same when the penalty is switched off entirely.
        start(baseConfig.copy(penaltyEnabled = false))
        advance(60_000, 100)
        assertEquals(EnforcementState.WARNING, last.state)
        assertNull(last.secondsUntilPenalty)
    }

    @Test
    fun `the countdown is absent outside the warning stage`() {
        start()
        assertNull(last.secondsUntilPenalty)

        advance(20_000, 100)
        assertEquals(EnforcementState.GRACE, last.state)
        assertNull(last.secondsUntilPenalty)

        reachPenaltyFromHere()
        assertNull(last.secondsUntilPenalty)
    }

    // --- hysteresis -------------------------------------------------------------------------

    @Test
    fun `staying in the zone uses the raw band`() {
        start()
        advance(2_000, 120)
        assertEquals(EnforcementState.IN_ZONE, last.state)
    }

    @Test
    fun `getting back in has to clear the hysteresis inset`() {
        reachPenalty()
        // Inside the raw band but not the strict one: the hold must not start.
        advance(10_000, 122)
        assertEquals(EnforcementState.PENALTY, last.state)
        assertEquals(config().recoveryHoldMs, last.holdRemainingMs)
    }

    @Test
    fun `hovering on the boundary does not flap the media`() {
        start()
        repeat(40) {
            now += 1_000
            tick(if (it % 2 == 0) 119 else 121)
        }
        // Sixty-odd seconds of oscillation with no grace fully served: one transition, not ten.
        assertEquals(0, countOf(PenaltyEffect.PauseMedia))
        assertEquals(0, countOf(PenaltyEffect.ResumeMedia))
    }

    private fun config() = baseConfig

    // --- ceiling ----------------------------------------------------------------------------

    @Test
    fun `riding above the zone warns but never penalizes`() {
        start()
        advance(600_000, 150)
        assertEquals(EnforcementState.WARNING, last.state)
        assertEquals(Drift.ABOVE, last.drift)
        assertEquals(0, countOf(PenaltyEffect.PauseMedia))
        assertEquals(0, countOf(PenaltyEffect.ShowCurtain))
    }

    @Test
    fun `credit is frozen above the ceiling`() {
        start()
        advance(10_000, 130)
        advance(60_000, 150)
        assertEquals(10, last.creditSeconds)
    }

    @Test
    fun `the over zone scrim tops out dimmer than a real countdown`() {
        start()
        advance(600_000, 150)
        assertEquals(ZoneEnforcer.MaxNagScrim, last.scrimAlpha, 0.001f)
    }

    // --- recovery ---------------------------------------------------------------------------

    @Test
    fun `recovery needs five continuous seconds in the band`() {
        reachPenalty()
        // The hold clock starts at the first in-band sample, so at 1 Hz it takes six ticks to
        // span five seconds of observed effort.
        advance(5_000, 130)
        assertEquals(EnforcementState.PENALTY, last.state)
        assertEquals(0, countOf(PenaltyEffect.ResumeMedia))

        advance(1_000, 130)
        assertEquals(EnforcementState.RECOVERING, last.state)
        assertEquals(1, countOf(PenaltyEffect.ResumeMedia))
        assertEquals(1, countOf(PenaltyEffect.HideCurtain))
    }

    @Test
    fun `a single sample outside the band restarts the hold`() {
        reachPenalty()
        advance(5_000, 130)
        advance(1_000, 100)
        assertEquals(EnforcementState.PENALTY, last.state)

        // Back in, but the hold is starting over from scratch.
        advance(5_000, 130)
        assertEquals(EnforcementState.PENALTY, last.state)
        assertEquals(0, countOf(PenaltyEffect.ResumeMedia))

        advance(1_000, 130)
        assertEquals(EnforcementState.RECOVERING, last.state)
    }

    @Test
    fun `the hold countdown drives the recovery ring`() {
        reachPenalty()
        // First in-band tick starts the clock at zero; the second is one second in.
        advance(2_000, 130)
        assertEquals(4_000L, last.holdRemainingMs)
    }

    @Test
    fun `recovery settles back into the zone and resumes credit`() {
        reachPenalty()
        advance(6_000, 130)
        advance(5_000, 130)
        assertEquals(EnforcementState.IN_ZONE, last.state)
        assertTrue("credit should resume after recovery", last.creditSeconds > 0)
    }

    // --- escape hatches ---------------------------------------------------------------------

    @Test
    fun `a dead strap during a penalty keeps the media paused`() {
        reachPenalty()
        advance(30_000, null)
        assertEquals(EnforcementState.PENALTY, last.state)
        assertEquals(0, countOf(PenaltyEffect.ResumeMedia))
        assertEquals(0, countOf(PenaltyEffect.HideCurtain))
    }

    @Test
    fun `a stale heart rate during a penalty keeps the media paused`() {
        reachPenalty()
        advance(30_000, 100, hrAgeMs = 20_000)
        assertEquals(EnforcementState.PENALTY, last.state)
        assertEquals(0, countOf(PenaltyEffect.ResumeMedia))
    }

    @Test
    fun `putting the strap back on lets a held penalty recover normally`() {
        reachPenalty()
        advance(30_000, null)
        advance(5_000, 130)
        assertEquals(EnforcementState.PENALTY, last.state)

        advance(1_000, 130)
        assertEquals(EnforcementState.RECOVERING, last.state)
        assertEquals(1, countOf(PenaltyEffect.ResumeMedia))
    }

    @Test
    fun `a penalty never expires on its own`() {
        reachPenalty()
        // Half an hour below the zone: the video waits rather than playing to an empty room.
        advance(1_800_000, 100)
        assertEquals(EnforcementState.PENALTY, last.state)
        assertEquals(0, countOf(PenaltyEffect.ResumeMedia))
    }

    @Test
    fun `the user release button ends the penalty and stops enforcing`() {
        reachPenalty()
        enforcer.releaseByUser()
        now += 1_000
        tick(100)
        assertNotEquals(EnforcementState.PENALTY, last.state)
        assertEquals(1, countOf(PenaltyEffect.ResumeMedia))

        // And it stays released even after a full trip back through the zone.
        advance(2_000, 130)
        advance(31_000, 100)
        advance(60_000, 100)
        assertEquals(1, countOf(PenaltyEffect.PauseMedia))
    }

    @Test
    fun `turning the penalty setting off mid penalty releases the media`() {
        reachPenalty()
        enforcer.updateConfig(baseConfig.copy(penaltyEnabled = false))
        now += 1_000
        tick(100)
        assertNotEquals(EnforcementState.PENALTY, last.state)
        assertEquals(1, countOf(PenaltyEffect.ResumeMedia))
    }

    @Test
    fun `turning the whole feature off mid penalty releases the media`() {
        reachPenalty()
        enforcer.updateConfig(baseConfig.copy(enabled = false))
        now += 1_000
        tick(100)
        assertEquals(EnforcementState.SUSPENDED, last.state)
        assertEquals(1, countOf(PenaltyEffect.ResumeMedia))
        assertEquals(1, countOf(PenaltyEffect.HideCurtain))
    }

    // --- suspension -------------------------------------------------------------------------

    @Test
    fun `unconfigured zones suspend everything`() {
        enforcer = ZoneEnforcer(baseConfig)
        now = 0
        seen.clear()
        last = enforcer.tick(TickInput(now, 130, 0, null, true))
        assertEquals(EnforcementState.SUSPENDED, last.state)
        assertEquals(SuspendReason.NO_ZONES, last.suspendReason)
    }

    @Test
    fun `a disabled feature never leaves suspension`() {
        start(baseConfig.copy(enabled = false))
        advance(600_000, 100)
        assertEquals(EnforcementState.SUSPENDED, last.state)
        assertEquals(SuspendReason.DISABLED, last.suspendReason)
        assertEquals(0, countOf(PenaltyEffect.PauseMedia))
    }

    @Test
    fun `stepping off the bike suspends when configured to`() {
        start(baseConfig.copy(suspendWhenStopped = true))
        advance(5_000, 100, isMoving = false)
        assertEquals(EnforcementState.SUSPENDED, last.state)
        assertEquals(SuspendReason.NOT_MOVING, last.suspendReason)
    }

    @Test
    fun `leaving suspension grants a fresh grace period`() {
        start()
        advance(20_000, 100)
        tick(null)
        assertEquals(EnforcementState.SUSPENDED, last.state)

        // Back on the strap, still below the zone: grace restarts rather than resuming.
        advance(29_000, 100)
        assertEquals(EnforcementState.GRACE, last.state)
    }

    // --- goal -------------------------------------------------------------------------------

    @Test
    fun `reaching the goal completes once and stops enforcing`() {
        start(baseConfig.copy(goalSeconds = 10))
        advance(11_000, 130)
        assertEquals(EnforcementState.COMPLETE, last.state)
        assertEquals(1, countOf(PenaltyEffect.GoalCompleted))

        advance(10_000, 130)
        assertEquals(1, countOf(PenaltyEffect.GoalCompleted))
    }

    @Test
    fun `cooling down after the goal is never penalized`() {
        start(baseConfig.copy(goalSeconds = 10))
        advance(11_000, 130)
        advance(300_000, 90)
        assertEquals(EnforcementState.COMPLETE, last.state)
        assertEquals(0, countOf(PenaltyEffect.PauseMedia))
    }

    @Test
    fun `progress reports against the goal`() {
        start(baseConfig.copy(goalSeconds = 100))
        advance(25_000, 130)
        assertEquals(0.25f, last.progress, 0.02f)
        assertEquals(75, last.remainingSeconds)
    }

    // --- session lifecycle ------------------------------------------------------------------

    @Test
    fun `reset clears credit and releases an active penalty`() {
        reachPenalty()
        enforcer.reset()
        now += 1_000
        tick(130)
        assertEquals(0, last.creditSeconds)
        assertEquals(1, countOf(PenaltyEffect.ResumeMedia))
        assertEquals(1, countOf(PenaltyEffect.HideCurtain))
    }

    @Test
    fun `restored credit survives a process restart`() {
        enforcer = ZoneEnforcer(baseConfig)
        enforcer.restoreCredit(1_200_000)
        now = 0
        seen.clear()
        tick(130)
        advance(5_000, 130)
        assertEquals(1_205, last.creditSeconds)
    }

    @Test
    fun `editing the goal mid ride keeps the credit already earned`() {
        start()
        advance(30_000, 130)
        enforcer.updateConfig(baseConfig.copy(goalSeconds = 1_200))
        advance(1_000, 130)
        assertEquals(31, last.creditSeconds)
        assertEquals(1_200, last.goalSeconds)
    }

    @Test
    fun `the snapshot reports the live zone for tinting`() {
        start()
        assertEquals(2, last.currentZone)
        assertNull(last.drift)

        advance(1_000, 170)
        assertEquals(4, last.currentZone)
    }

    // --- warm-up ----------------------------------------------------------------------------

    @Test
    fun `a cold start warms up instead of escalating`() {
        startCold()
        assertEquals(EnforcementState.WARMUP, last.state)

        // Ten minutes of honest warming up. Nothing counts against a rider who has not yet
        // been given the chance to reach their zone.
        advance(600_000, 90)
        assertEquals(EnforcementState.WARMUP, last.state)
        assertEquals(0, countOf(PenaltyEffect.PauseMedia))
        assertEquals(0, last.creditSeconds)
        assertNull(last.secondsUntilPenalty)
    }

    @Test
    fun `warm-up shows no drift and no scrim`() {
        startCold()
        advance(120_000, 90)
        assertNull(last.drift)
        assertEquals(0f, last.scrimAlpha, 0f)
    }

    @Test
    fun `reaching the zone leaves warm-up but does not yet arm a penalty`() {
        startCold()
        advance(60_000, 90)
        assertEquals(EnforcementState.WARMUP, last.state)

        advance(5_000, 130)
        assertEquals(EnforcementState.IN_ZONE, last.state)

        // Five seconds of held zone is not a warm-up. The escalation runs, but it has no
        // teeth: there is nothing to count down to and the video is never touched.
        advance(31_000, 100)
        advance(30_000, 100)
        assertEquals(EnforcementState.WARNING, last.state)
        assertNull(last.secondsUntilPenalty)
        assertEquals(0, countOf(PenaltyEffect.PauseMedia))
    }

    @Test
    fun `holding the zone for long enough arms the penalty`() {
        startCold()
        advance(30_000, 90)
        // A full minute of the zone, which is what the warm-up asks for.
        advance(62_000, 130)
        assertEquals(EnforcementState.IN_ZONE, last.state)

        advance(31_000, 100)
        assertEquals(EnforcementState.WARNING, last.state)
        assertNotNull(last.secondsUntilPenalty)
        advance(16_000, 100)
        assertEquals(EnforcementState.PENALTY, last.state)
    }

    @Test
    fun `a wobble while settling in does not restart the warm-up`() {
        startCold()
        advance(40_000, 130)
        // Out for a moment, then back. The warm-up counts total time in the zone, not an
        // unbroken streak, so this costs a few seconds rather than the whole minute.
        advance(5_000, 100)
        advance(25_000, 130)

        advance(31_000, 100)
        assertNotNull(last.secondsUntilPenalty)
    }

    @Test
    fun `warm-up entry does not demand the re-entry overshoot`() {
        // 120 is the floor exactly. Someone who has never been in the zone should be let in on
        // touching it, not made to clear the anti-flap inset first.
        startCold()
        advance(30_000, 90)
        advance(1_000, 120)
        assertEquals(EnforcementState.IN_ZONE, last.state)
    }

    @Test
    fun `losing the strap after warm-up does not hand back another one`() {
        startCold()
        advance(30_000, 90)
        advance(5_000, 130)
        assertEquals(EnforcementState.IN_ZONE, last.state)

        advance(15_000, null)
        assertEquals(EnforcementState.SUSPENDED, last.state)

        // Back on the bike, off the pace. Enforcement resumes where it left off.
        advance(1_000, 100)
        assertEquals(EnforcementState.GRACE, last.state)
    }

    @Test
    fun `restored credit skips the warm-up`() {
        enforcer = ZoneEnforcer(warmUp)
        enforcer.restoreCredit(600_000)
        now = 0
        seen.clear()
        // A rider mid-ride whose process died. They are warm; do not give them a free window.
        tick(90)
        assertEquals(EnforcementState.GRACE, last.state)
    }

    @Test
    fun `the warm-up can be switched off`() {
        startCold(baseConfig)
        assertEquals(EnforcementState.GRACE, last.state)
        advance(31_000, 90)
        advance(16_000, 90)
        assertEquals(EnforcementState.PENALTY, last.state)
    }

    @Test
    fun `a reset returns the rider to warm-up`() {
        startCold()
        advance(30_000, 90)
        advance(5_000, 130)
        enforcer.reset()
        now += 1_000
        tick(90)
        assertEquals(EnforcementState.WARMUP, last.state)
    }

    // --- signal conditioning ----------------------------------------------------------------

    @Test
    fun `a single artifact sample cannot start the escalation`() {
        start(smoothed)
        advance(5_000, 130)

        now += 1_000
        tick(40)
        assertEquals(EnforcementState.IN_ZONE, last.state)
        assertNull(last.drift)
    }

    @Test
    fun `a sustained drop still escalates, just later`() {
        start(smoothed)
        advance(5_000, 130)

        // The raw signal is below the floor from the first of these ticks; the conditioned one
        // takes a few seconds to follow, which is the lag being paid for.
        advance(3_000, 100)
        assertEquals(EnforcementState.IN_ZONE, last.state)

        advance(60_000, 100)
        assertEquals(EnforcementState.PENALTY, last.state)
    }

    @Test
    fun `the snapshot reports the raw reading and the judged one separately`() {
        start(smoothed)
        advance(5_000, 130)
        advance(2_000, 150)

        assertEquals(150, last.bpm)
        val judged = last.smoothedBpm!!
        assertTrue("was $judged", judged in 131..145)
    }

    @Test
    fun `conditioning off leaves the judged value equal to the raw one`() {
        start()
        advance(5_000, 133)
        assertEquals(133, last.bpm)
        assertEquals(133, last.smoothedBpm)
    }

    @Test
    fun `repeated ticks between samples do not drag the average`() {
        start(smoothed)
        advance(5_000, 130)

        // One sample, then four ticks that carry the same ageing reading. Feeding those would
        // pull the average toward a value the heart never held.
        now += 1_000
        tick(150, hrAgeMs = 0)
        val sampleAt = now
        val afterSample = last.smoothedBpm
        repeat(4) {
            now += 250
            tick(150, hrAgeMs = now - sampleAt)
        }
        assertEquals(afterSample, last.smoothedBpm)
    }

    // --- effort health ----------------------------------------------------------------------

    @Test
    fun `a collapse in output shows in health before the heart rate moves`() {
        start(smoothed)
        // Two minutes holding the zone at a steady 150 W teaches what the zone costs today.
        advance(120_000, 130, watts = 150f)
        val holding = last.effortHealth!!
        assertTrue("was $holding", holding > 0.4f)

        // The rider eases right off. Their heart rate has not moved and will not for another
        // half a minute - as far as the band is concerned they are still comfortably in the
        // zone - but the effort behind it has already gone, and the score says so.
        advance(12_000, 130, watts = 60f)
        assertEquals(EnforcementState.IN_ZONE, last.state)
        assertNull(last.drift)
        assertTrue("was ${last.effortHealth}", last.effortHealth!! < holding / 2f)
    }

    @Test
    fun `health is reported through the warm-up`() {
        startCold()
        advance(30_000, 90, watts = 60f)
        assertEquals(EnforcementState.WARMUP, last.state)
        // Below the band, so nothing yet - but a figure, not a blank.
        assertEquals(0f, last.effortHealth!!, 0.01f)
    }

    @Test
    fun `there is no health to report while suspended`() {
        start()
        advance(15_000, null)
        assertEquals(EnforcementState.SUSPENDED, last.state)
        assertNull(last.effortHealth)
        assertNull(last.headroomSeconds)
    }

    @Test
    fun `health needs no output at all to be useful`() {
        start(smoothed)
        advance(60_000, 130)
        // Buffer alone: halfway up the band with nothing known about the bike.
        assertEquals(0.5f, last.effortHealth!!, 0.05f)
        assertNull(last.holdingWatts)
    }

    // --- staying out of the settings --------------------------------------------------------

    @Test
    fun `covering the settings takes the curtain down without letting the rider off`() {
        reachPenalty()
        assertEquals(1, countOf(PenaltyEffect.ShowCurtain))
        assertEquals(1, countOf(PenaltyEffect.PauseMedia))

        advance(2_000, 100, overlaySuppressed = true)
        assertEquals(1, countOf(PenaltyEffect.HideCurtain))
        // The penalty itself is untouched: the video stays where it was put, and walking into
        // the settings is not a way out of it.
        assertEquals(EnforcementState.PENALTY, last.state)
        assertEquals(0, countOf(PenaltyEffect.ResumeMedia))

        advance(2_000, 100)
        assertEquals(2, countOf(PenaltyEffect.ShowCurtain))
    }

    @Test
    fun `the warning scrim stays off the settings`() {
        start()
        advance(31_000, 100)
        advance(5_000, 100)
        assertEquals(EnforcementState.WARNING, last.state)
        assertTrue(last.scrimAlpha > 0f)

        advance(1_000, 100, overlaySuppressed = true)
        assertEquals(0f, last.scrimAlpha, 0f)

        advance(1_000, 100)
        assertTrue(last.scrimAlpha > 0f)
    }

    @Test
    fun `recovering in the settings still hands the media back`() {
        reachPenalty()
        advance(2_000, 100, overlaySuppressed = true)
        assertEquals(0, countOf(PenaltyEffect.ResumeMedia))

        // Earning it back while the settings happen to be open works exactly as it would
        // anywhere else - the drawing stood down, the machine did not.
        advance(6_000, 130, overlaySuppressed = true)
        assertEquals(EnforcementState.RECOVERING, last.state)
        assertEquals(1, countOf(PenaltyEffect.ResumeMedia))
    }

    // --- recovering from a penalty ----------------------------------------------------------

    @Test
    fun `sprinting clear above the zone clears a penalty`() {
        reachPenalty()
        // 150 is over the top of zone 2 entirely. A rider who overshoots getting out of
        // trouble has done more than was asked, not less - the video has to come back.
        advance(6_000, 150)
        assertEquals(EnforcementState.RECOVERING, last.state)
        assertEquals(1, countOf(PenaltyEffect.ResumeMedia))
        assertEquals(1, countOf(PenaltyEffect.HideCurtain))
    }

    @Test
    fun `a penalty still holds while the rider is below the floor`() {
        reachPenalty()
        advance(60_000, 100)
        assertEquals(EnforcementState.PENALTY, last.state)
        assertEquals(0, countOf(PenaltyEffect.ResumeMedia))
    }

    @Test
    fun `falling back below the floor restarts the recovery hold`() {
        reachPenalty()
        advance(3_000, 150)
        assertEquals(EnforcementState.PENALTY, last.state)
        // Back under, so the hold starts again from nothing.
        advance(1_000, 100)
        advance(3_000, 150)
        assertEquals(EnforcementState.PENALTY, last.state)
        advance(3_000, 150)
        assertEquals(EnforcementState.RECOVERING, last.state)
    }

    // --- riding blind -----------------------------------------------------------------------

    /** A minute of steady in-zone riding, which is what earns a holding figure. */
    private fun establishHolding(watts: Float = 150f): EnforcementSnapshot {
        start()
        advance(70_000, 130, watts = watts)
        assertNotNull(last.holdingWatts)
        return last
    }

    @Test
    fun `a strap that dies while the rider works keeps the clock running`() {
        establishHolding()
        val earned = last.creditSeconds

        advance(20_000, null, watts = 150f)
        assertEquals(EnforcementState.RIDING_BLIND, last.state)
        assertTrue("credit went from $earned to ${last.creditSeconds}", last.creditSeconds > earned)
        assertNull(last.drift)
        assertEquals(0, countOf(PenaltyEffect.PauseMedia))
    }

    @Test
    fun `a strap that dies while the rider stops suspends as before`() {
        establishHolding()
        advance(40_000, null, watts = 0f)
        assertEquals(EnforcementState.SUSPENDED, last.state)
    }

    @Test
    fun `riding blind can finish the goal`() {
        start(baseConfig.copy(goalSeconds = 80))
        advance(70_000, 130, watts = 150f)
        advance(20_000, null, watts = 150f)
        assertEquals(EnforcementState.COMPLETE, last.state)
        assertEquals(1, countOf(PenaltyEffect.GoalCompleted))
    }

    @Test
    fun `the strap coming back picks up from wherever the rider is`() {
        establishHolding()
        advance(10_000, null, watts = 150f)
        assertEquals(EnforcementState.RIDING_BLIND, last.state)

        advance(2_000, 130, watts = 150f)
        assertEquals(EnforcementState.IN_ZONE, last.state)
    }

    @Test
    fun `a penalty lifts when the strap dies but the rider keeps working`() {
        establishHolding()
        // Out of the zone and off the pace, so the penalty is earned honestly.
        advance(31_000, 100, watts = 60f)
        advance(16_000, 100, watts = 60f)
        assertEquals(EnforcementState.PENALTY, last.state)

        // The strap goes, and the rider gets back on it. Nobody can read their heart rate, but
        // the bike can see the work - so the video comes back.
        advance(30_000, null, watts = 150f)
        assertEquals(1, countOf(PenaltyEffect.ResumeMedia))
        assertEquals(1, countOf(PenaltyEffect.HideCurtain))
    }

    @Test
    fun `a penalty still holds when the strap dies and the rider does not`() {
        establishHolding()
        advance(31_000, 100, watts = 60f)
        advance(16_000, 100, watts = 60f)
        assertEquals(EnforcementState.PENALTY, last.state)

        advance(60_000, null, watts = 40f)
        assertEquals(EnforcementState.PENALTY, last.state)
        assertEquals(0, countOf(PenaltyEffect.ResumeMedia))
    }

    // --- output overruling a strap ------------------------------------------------------------

    @Test
    fun `output still at holding power stops a penalty the strap asked for`() {
        establishHolding()

        // The strap says the rider fell out of their zone; the bike says they are working
        // exactly as hard as they were. A slipped strap must not pause anybody's video.
        advance(31_000, 100, watts = 150f)
        assertEquals(EnforcementState.WARNING, last.state)
        assertNull(last.secondsUntilPenalty)

        advance(60_000, 100, watts = 150f)
        assertEquals(EnforcementState.WARNING, last.state)
        assertEquals(0, countOf(PenaltyEffect.PauseMedia))
    }

    @Test
    fun `a rider who has genuinely eased off is still penalized`() {
        establishHolding()
        advance(31_000, 100, watts = 70f)
        advance(16_000, 100, watts = 70f)
        assertEquals(EnforcementState.PENALTY, last.state)
    }
}
