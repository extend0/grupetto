package com.spop.poverlay.zone

import kotlin.math.abs

/**
 * The zone enforcement state machine.
 *
 * Deliberately free of Android: every clock reading arrives in [TickInput.nowMs], so the whole
 * thing can be driven by a fake clock in a unit test. All the interesting behaviour - grace
 * windows, hysteresis, the recovery hold, every escape hatch - lives here rather than in the
 * service that applies its effects.
 *
 * Effects are edge-triggered: ticking a thousand times deep inside a penalty emits exactly one
 * [PenaltyEffect.PauseMedia]. The caller can therefore apply them blindly.
 */
class ZoneEnforcer(config: EnforcementConfig) {

    companion object {
        /**
         * Wall-clock deltas above this are clamped. A frozen or dozing process must not be able
         * to hand back ten minutes of credit in a single tick.
         */
        const val MaxTickDeltaMs = 5_000L

        /** How long RECOVERING lingers before settling into IN_ZONE, purely for UI feedback. */
        const val RecoveringLingerMs = 1_000L

        const val MaxWarningScrim = 0.6f

        /** Over-zone and released-enforcement warnings are a nag, not a threat. Dimmer. */
        const val MaxNagScrim = 0.35f

        private const val ScrimQuantum = 0.05f
    }

    var config: EnforcementConfig = config
        private set

    private var state = EnforcementState.IDLE
    private var stateEnteredAtMs = 0L
    private var lastTickMs: Long? = null
    private var creditMs = 0L
    private var holdStartedAtMs: Long? = null
    private var penaltyStartedAtMs: Long? = null
    private var recoveringUntilMs = 0L
    private var drift: Drift? = null
    private var suspendReason: SuspendReason? = null

    /** Set by the curtain's "End enforcement" button. Survives until [reset]. */
    private var releasedByUser = false

    private var mediaPausedLatch = false
    private var curtainLatch = false
    private var scrimLatch = 0f
    private var completedLatch = false

    fun updateConfig(config: EnforcementConfig) {
        this.config = config
    }

    /** New session: forget the credit and every latch except what the caller already applied. */
    fun reset() {
        state = EnforcementState.IDLE
        stateEnteredAtMs = 0L
        lastTickMs = null
        creditMs = 0L
        holdStartedAtMs = null
        penaltyStartedAtMs = null
        recoveringUntilMs = 0L
        drift = null
        suspendReason = null
        releasedByUser = false
        completedLatch = false
        // The media/curtain latches are deliberately kept: they track what the caller has
        // already applied, and the next tick emits whatever is needed to undo it.
    }

    /** Restores credit carried across a process restart. */
    fun restoreCredit(millis: Long) {
        creditMs = millis.coerceAtLeast(0)
    }

    val creditMillis: Long get() = creditMs

    /**
     * The curtain's escape hatch. Stops enforcement escalating for the rest of the session;
     * the next tick emits the resume/hide effects.
     */
    fun releaseByUser() {
        releasedByUser = true
    }

    fun tick(input: TickInput): EnforcementSnapshot {
        val now = input.nowMs
        val deltaMs = lastTickMs?.let { (now - it).coerceIn(0L, MaxTickDeltaMs) } ?: 0L
        lastTickMs = now

        val bounds = zoneBounds(config.targetZone, input.boundaries)
        suspendReason = suspendReasonFor(input, bounds)

        if (suspendReason != null) {
            // A live penalty outlives the loss of the signal. No heart rate usually means the
            // strap came off with the rider, so the video should stay put; only the feature
            // being switched off or its zones going away can release it here.
            val penaltyOutlivesSuspension = state == EnforcementState.PENALTY &&
                (suspendReason == SuspendReason.NO_SIGNAL || suspendReason == SuspendReason.STALE)
            if (!penaltyOutlivesSuspension) {
                drift = null
                enter(EnforcementState.SUSPENDED, now)
            }
        } else {
            advance(now, deltaMs, input.bpm!!, bounds!!)
        }

        return snapshot(now, input.bpm, bounds, input.boundaries)
    }

    private fun suspendReasonFor(input: TickInput, bounds: ZoneBounds?): SuspendReason? = when {
        !config.enabled -> SuspendReason.DISABLED
        bounds == null -> SuspendReason.NO_ZONES
        input.bpm == null || input.bpm <= 0 -> SuspendReason.NO_SIGNAL
        input.hrAgeMs > config.staleHrMs -> SuspendReason.STALE
        config.suspendWhenStopped && !input.isMoving -> SuspendReason.NOT_MOVING
        else -> null
    }

    private fun advance(now: Long, deltaMs: Long, bpm: Int, bounds: ZoneBounds) {
        // Staying in the zone uses the raw band; getting back in has to clear the inset.
        val wasInside = state == EnforcementState.IN_ZONE ||
            state == EnforcementState.RECOVERING ||
            state == EnforcementState.COMPLETE
        val band = effectiveBand(bounds, config.hysteresisBpm, strict = !wasInside)
        val inside = isInBand(bpm, band)

        drift = when {
            inside -> null
            band.floor != null && bpm < band.floor -> Drift.BELOW
            else -> Drift.ABOVE
        }

        when (state) {
            EnforcementState.IDLE,
            EnforcementState.SUSPENDED ->
                enter(if (inside) EnforcementState.IN_ZONE else EnforcementState.GRACE, now)

            EnforcementState.IN_ZONE -> {
                // Credit the elapsed interval only if this sample is still in the band -
                // the tick that discovers the rider has dropped out must not pay them for it.
                if (inside) creditMs += deltaMs
                when {
                    creditMs >= config.goalMs -> enter(EnforcementState.COMPLETE, now)
                    !inside -> enter(EnforcementState.GRACE, now)
                }
            }

            EnforcementState.RECOVERING -> {
                if (inside) creditMs += deltaMs
                when {
                    creditMs >= config.goalMs -> enter(EnforcementState.COMPLETE, now)
                    !inside -> enter(EnforcementState.GRACE, now)
                    now >= recoveringUntilMs -> enter(EnforcementState.IN_ZONE, now)
                }
            }

            EnforcementState.GRACE -> when {
                inside -> enter(EnforcementState.IN_ZONE, now)
                now - stateEnteredAtMs >= config.graceMs -> enter(EnforcementState.WARNING, now)
                else -> Unit
            }

            EnforcementState.WARNING -> when {
                inside -> enter(EnforcementState.IN_ZONE, now)
                // Over-zone never escalates: pausing would reward someone riding too hard.
                canPenalize() && drift == Drift.BELOW &&
                    now - stateEnteredAtMs >= config.warningMs -> enter(EnforcementState.PENALTY, now)
                else -> Unit
            }

            EnforcementState.PENALTY -> {
                when {
                    // The curtain's release button, or the setting being switched off
                    // mid-penalty. A penalty never expires on its own: if you have stepped off
                    // the bike, the video should be waiting for you rather than playing to an
                    // empty room. The notification action is always there to let you out.
                    !canPenalize() -> enter(
                        if (inside) EnforcementState.IN_ZONE else EnforcementState.WARNING,
                        now,
                    )

                    inside -> {
                        // The clock starts at the first in-band sample, not at the sample
                        // before it - so the hold is a full recoveryHoldMs of observed effort.
                        val holdSince = holdStartedAtMs ?: now.also { holdStartedAtMs = it }
                        if (now - holdSince >= config.recoveryHoldMs) {
                            recoveringUntilMs = now + RecoveringLingerMs
                            enter(EnforcementState.RECOVERING, now)
                        }
                    }

                    // Any single sample outside the band restarts the whole hold.
                    else -> holdStartedAtMs = null
                }
            }

            // Terminal. Someone who hit their goal must never be penalized for cooling down.
            EnforcementState.COMPLETE -> if (inside) creditMs += deltaMs
        }
    }

    private fun canPenalize() = config.penaltyEnabled && !releasedByUser

    private fun enter(next: EnforcementState, now: Long) {
        if (next == state) return
        if (state == EnforcementState.PENALTY) holdStartedAtMs = null
        if (next == EnforcementState.PENALTY) penaltyStartedAtMs = now
        if (next != EnforcementState.PENALTY) penaltyStartedAtMs = null
        state = next
        stateEnteredAtMs = now
    }

    private fun scrimFor(now: Long): Float {
        if (state != EnforcementState.WARNING) return 0f
        val span = config.warningMs.coerceAtLeast(1)
        val progress = ((now - stateEnteredAtMs).toFloat() / span).coerceIn(0f, 1f)
        // A warning that can't escalate tops out dimmer - it's a nag, not a countdown.
        val ceiling = if (drift == Drift.BELOW && canPenalize()) MaxWarningScrim else MaxNagScrim
        return progress * ceiling
    }

    /** Only counts down when a pause is genuinely coming - see the field's documentation. */
    private fun secondsUntilPenalty(now: Long): Long? {
        if (state != EnforcementState.WARNING) return null
        if (drift != Drift.BELOW || !canPenalize()) return null
        val remaining = (config.warningMs - (now - stateEnteredAtMs)).coerceAtLeast(0)
        // Round up, so a countdown reads 1 until the moment it actually fires.
        return (remaining + 999) / 1000
    }

    private fun snapshot(
        now: Long,
        bpm: Int?,
        bounds: ZoneBounds?,
        boundaries: List<Int>?,
    ): EnforcementSnapshot {
        val effects = mutableListOf<PenaltyEffect>()

        val wantPaused = state == EnforcementState.PENALTY
        if (wantPaused != mediaPausedLatch) {
            effects.add(if (wantPaused) PenaltyEffect.PauseMedia else PenaltyEffect.ResumeMedia)
            mediaPausedLatch = wantPaused
        }

        val wantCurtain = state == EnforcementState.PENALTY
        if (wantCurtain != curtainLatch) {
            effects.add(if (wantCurtain) PenaltyEffect.ShowCurtain else PenaltyEffect.HideCurtain)
            curtainLatch = wantCurtain
        }

        val scrim = scrimFor(now)
        if (abs(scrim - scrimLatch) >= ScrimQuantum || (scrim == 0f && scrimLatch != 0f)) {
            effects.add(PenaltyEffect.SetScrimAlpha(scrim))
            scrimLatch = scrim
        }

        if (state == EnforcementState.COMPLETE && !completedLatch) {
            effects.add(PenaltyEffect.GoalCompleted)
            completedLatch = true
        }

        val holdRemaining = if (state == EnforcementState.PENALTY) {
            holdStartedAtMs
                ?.let { (config.recoveryHoldMs - (now - it)).coerceAtLeast(0) }
                ?: config.recoveryHoldMs
        } else {
            0L
        }

        return EnforcementSnapshot(
            state = state,
            effects = effects,
            suspendReason = suspendReason,
            creditSeconds = creditMs / 1000,
            goalSeconds = config.goalSeconds,
            targetZone = config.targetZone,
            band = bounds,
            bpm = bpm,
            currentZone = bpm?.let { zoneFor(it, boundaries) },
            drift = drift,
            scrimAlpha = scrimLatch,
            holdRemainingMs = holdRemaining,
            holdRequiredMs = config.recoveryHoldMs,
            penaltyElapsedMs = penaltyStartedAtMs?.let { now - it } ?: 0L,
            secondsUntilPenalty = secondsUntilPenalty(now),
        )
    }
}
