package com.spop.poverlay.zone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The machine is pure, so these drive it with a fake clock and assert on states and effects.
 * Zone 2 is [120, 140) throughout; with a 4 bpm hysteresis, re-entry needs 124..135.
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
        staleHrMs = 10_000,
        suspendWhenStopped = false,
    )

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
    ): EnforcementSnapshot {
        last = enforcer.tick(TickInput(now, bpm, hrAgeMs, boundaries, isMoving))
        seen += last.effects
        return last
    }

    /** Ticks once a second for [ms], the way the coordinator will. */
    private fun advance(
        ms: Long,
        bpm: Int?,
        hrAgeMs: Long = 0,
        isMoving: Boolean = true,
    ): EnforcementSnapshot {
        var remaining = ms
        while (remaining > 0) {
            val step = minOf(1_000L, remaining)
            now += step
            tick(bpm, hrAgeMs, isMoving)
            remaining -= step
        }
        return last
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
}
