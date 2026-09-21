package com.spop.poverlay.zone

import org.junit.Assert.*
import org.junit.Test

class ZonePresentationTest {
    private val snapshot = ZoneEnforcer(EnforcementConfig(enabled = true, penaltyEnabled = true))
        .tick(TickInput(0, 120, 0, listOf(100, 140, 160, 180), true))

    @Test fun `initial in-zone status explains the remaining warmup`() {
        assertTrue(snapshot.ridingStatus()!!.detail.contains("60s in zone to arm"))
    }

    @Test fun `ended enforcement is visible instead of an impending penalty`() {
        val status = snapshot.copy(state = EnforcementState.WARNING, drift = Drift.BELOW,
            enforcementReleased = true).ridingStatus()!!
        assertEquals("Enforcement ended", status.title)
        assertTrue(status.detail.contains("no more pauses"))
    }

    @Test fun `disabled tracking hides the goal strip`() {
        assertNull(snapshot.copy(state = EnforcementState.SUSPENDED, suspendReason = SuspendReason.DISABLED).ridingStatus())
    }

    @Test fun `blind riding and a lost signal explain whether time counts`() {
        val blind = snapshot.copy(state = EnforcementState.RIDING_BLIND, suspendReason = SuspendReason.NO_SIGNAL)
        assertTrue(blind.ridingStatus()!!.title.contains("counting from power"))
        assertTrue(blind.copy(state = EnforcementState.SUSPENDED).ridingStatus()!!.detail.contains("Goal clock paused"))
    }

    @Test fun `goal completion promises no more penalties`() {
        val status = snapshot.copy(state = EnforcementState.COMPLETE).ridingStatus()!!
        assertEquals("Goal complete", status.title)
        assertTrue(status.detail.contains("no more penalties"))
    }

    @Test fun `early hint and penalty countdown are distinct`() {
        val early = snapshot.copy(earlyEffortWarning = true).ridingStatus()!!
        assertEquals("Effort dropping — pick it up", early.title)
        assertTrue(early.detail.contains("Still in"))
        val late = snapshot.copy(state = EnforcementState.WARNING, secondsUntilPenalty = 12).ridingStatus()!!
        assertEquals("PAUSING IN 12s", late.title)
        assertEquals(ZoneStatusTone.URGENT, late.tone)
    }

    @Test fun `recovery shows the inset floor and accepts effort above the ceiling`() {
        assertEquals(102, snapshot.recoveryFloorBpm)
        val recovery = snapshot.copy(state = EnforcementState.PENALTY, drift = Drift.ABOVE,
            recoveryHolding = true, holdRemainingMs = 3_000).recoveryMessage()
        assertEquals("Hold it there", recovery.title)
        assertEquals("102+ bpm for 5s", recovery.target)
        assertTrue(recovery.detail.contains("3s"))
    }

    @Test fun `lost strap does not pretend that a recovery hold is running`() {
        val penalty = snapshot.copy(state = EnforcementState.PENALTY, suspendReason = SuspendReason.STALE)
        assertEquals("Heart-rate signal lost", penalty.recoveryMessage().title)
        assertTrue(penalty.recoveryMessage().detail.contains("end enforcement"))
        assertTrue(penalty.copy(powerVouches = true).recoveryMessage().detail.contains("output"))
    }
}
