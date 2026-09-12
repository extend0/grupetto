package com.spop.poverlay.zone

/**
 * Everything that shapes how zone enforcement behaves. Pure data - the state machine reads it,
 * nothing here touches Android.
 *
 * [enabled] turns on goal tracking (credit, progress, warnings). [penaltyEnabled] separately
 * arms the media penalty, so the feature can ship - and be trusted - as a zone timer before it
 * is ever allowed to touch someone's video.
 */
data class EnforcementConfig(
    val enabled: Boolean = false,
    val penaltyEnabled: Boolean = false,
    val targetZone: Int = 2,
    val goalSeconds: Long = 45 * 60,
    /**
     * Heart rate lags effort by 20-30s. A grace window shorter than that punishes physiology
     * rather than slacking, so treat ~20s as the practical floor.
     */
    val graceSeconds: Long = 30,
    val warningSeconds: Long = 15,
    val recoveryHoldSeconds: Long = 5,
    /** Re-entry inset. Without it, a bpm sitting on the boundary flaps and the video strobes. */
    val hysteresisBpm: Int = 4,
    /** No fresh sample for this long means the strap is gone; enforcement suspends. */
    val staleHrMs: Long = 10_000,
    /**
     * Whether to stop enforcing when the rider stops pedalling.
     *
     * Defaults to false, and that matters: on a stationary bike the easiest way to fall out of
     * a heart rate zone is to stop pedalling, so exempting it would be the one loophole that
     * defeats the whole feature. Getting off the bike for real is covered by
     * the curtain's release button and the notification action instead.
     */
    val suspendWhenStopped: Boolean = false,
) {
    val goalMs: Long get() = goalSeconds * 1000
    val graceMs: Long get() = graceSeconds * 1000
    val warningMs: Long get() = warningSeconds * 1000
    val recoveryHoldMs: Long get() = recoveryHoldSeconds * 1000
}

enum class EnforcementState {
    /** Nothing decided yet - before the first usable tick. */
    IDLE,
    IN_ZONE,
    /** Out of band, still inside the grace window. No penalty, no credit. */
    GRACE,
    /** Past grace. Scrim ramping. Terminal for over-zone drift and for released enforcement. */
    WARNING,
    /** Media paused, curtain up. */
    PENALTY,
    /** Recovery hold satisfied; media resumed. Brief, for UI feedback. */
    RECOVERING,
    /** No usable heart rate, no zones, not moving, or the feature is off. */
    SUSPENDED,
    COMPLETE,
}

/** Which side of the band the rider is on. */
enum class Drift { BELOW, ABOVE }

enum class SuspendReason { DISABLED, NO_ZONES, NO_SIGNAL, STALE, NOT_MOVING }

sealed interface PenaltyEffect {
    object PauseMedia : PenaltyEffect
    object ResumeMedia : PenaltyEffect
    object ShowCurtain : PenaltyEffect
    object HideCurtain : PenaltyEffect
    data class SetScrimAlpha(val alpha: Float) : PenaltyEffect
    object GoalCompleted : PenaltyEffect
}

data class TickInput(
    val nowMs: Long,
    /** null when the strap is disconnected. */
    val bpm: Int?,
    /** Milliseconds since the last real sample arrived. */
    val hrAgeMs: Long,
    /** HeartRateManager.heartRateZones; null when the user has not configured them. */
    val boundaries: List<Int>?,
    val isMoving: Boolean,
)

data class EnforcementSnapshot(
    val state: EnforcementState,
    /** Edge-triggered. Empty on ticks that changed nothing. */
    val effects: List<PenaltyEffect>,
    val suspendReason: SuspendReason?,
    val creditSeconds: Long,
    val goalSeconds: Long,
    val targetZone: Int,
    val band: ZoneBounds?,
    val bpm: Int?,
    val currentZone: Int?,
    val drift: Drift?,
    val scrimAlpha: Float,
    /** Counts down the in-zone hold that clears a penalty. Drives the recovery ring. */
    val holdRemainingMs: Long,
    val holdRequiredMs: Long,
    val penaltyElapsedMs: Long,
    /**
     * Seconds until the video pauses, or null when no pause is coming.
     *
     * Null for an over-zone warning and when the penalty is switched off, because in those
     * cases nothing is going to happen and a countdown would be a lie.
     */
    val secondsUntilPenalty: Long?,
) {
    val progress: Float
        get() = if (goalSeconds <= 0) 0f else (creditSeconds.toFloat() / goalSeconds).coerceIn(0f, 1f)
    val remainingSeconds: Long get() = (goalSeconds - creditSeconds).coerceAtLeast(0)
}
