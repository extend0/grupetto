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
    /**
     * Re-entry inset: how far *past* the boundary the rider has to climb to be counted back in.
     *
     * It exists to stop a bpm sitting on the boundary from flapping. Smoothing now absorbs most
     * of that wobble ([smoothingTauSeconds]), so this is deliberately small - every bpm here is
     * a bpm of overshoot demanded of someone who has just been told to work harder, and a wide
     * inset is what turns getting back into the zone into a sprint.
     */
    val hysteresisBpm: Int = 2,
    /**
     * Time constant of the smoothing applied to the strap signal before the machine judges it.
     *
     * Roughly the lag it adds, so it is a direct tax on how fast a real drop-out is noticed.
     * Ten seconds is enough to kill the wobble and stays far inside [graceSeconds]. Zero feeds
     * raw samples straight through, which is what the state-machine tests want.
     */
    val smoothingTauSeconds: Long = 10,
    /**
     * Warm-up: how long the rider must hold their zone, in total, before a penalty becomes
     * possible at all. 0 enforces from the first sample.
     *
     * Without it the machine starts judging immediately, which on a cold start means a resting
     * heart rate is already out of band: grace and warning burn while the rider is simply
     * warming up, and the video pauses within a minute of pressing play. The only way to beat
     * that clock is to sprint from a standstill - the app would be demanding exactly the spiky
     * riding it is supposed to smooth out.
     *
     * Counted cumulatively rather than as an unbroken streak, so a wobble while settling in
     * does not send the rider back to the start of their own warm-up.
     *
     * A rider who never reaches their zone is therefore never enforced. That is the right
     * failure: a zone untouched all ride is a zone that was set wrong, and silently doing
     * nothing beats holding someone's video hostage to a bad number.
     */
    val armAfterZoneSeconds: Long = 60,
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
    val smoothingTauMs: Long get() = smoothingTauSeconds * 1000
    val armAfterZoneMs: Long get() = armAfterZoneSeconds * 1000
    val graceMs: Long get() = graceSeconds * 1000
    val warningMs: Long get() = warningSeconds * 1000
    val recoveryHoldMs: Long get() = recoveryHoldSeconds * 1000
}

enum class EnforcementState {
    /** Nothing decided yet - before the first usable tick. */
    IDLE,
    /**
     * Before the rider has reached their zone at all this session, so nothing escalates.
     * See [EnforcementConfig.armAfterZoneSeconds].
     */
    WARMUP,
    IN_ZONE,
    /**
     * The strap has gone, but the bike can still see the rider working hard enough to hold
     * their zone. Credit runs; nothing escalates. See [EffortMonitor.vouchesForEffort].
     */
    RIDING_BLIND,
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
    /**
     * Output in watts, or null when the bike is not reporting it.
     *
     * The only input here that does not lag: it is what the rider is doing right now, rather
     * than what their heart has caught up to.
     */
    val powerWatts: Float? = null,
    /**
     * Whether the curtain must stay off whatever is on screen - Grupetto's own settings, or a
     * home screen the rider is picking an app from.
     *
     * Enforcement keeps running - the penalty is not a loophole - but it stops *drawing*, so
     * it cannot cover the settings someone opened it to change or the launcher they are trying
     * to use.
     */
    val overlaySuppressed: Boolean = false,
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
    /** Raw, as the strap reported it. What the rider sees on the strip. */
    val bpm: Int?,
    /**
     * What the machine actually judged, after conditioning. Equal to [bpm] with smoothing off.
     *
     * Worth showing wherever the rider is waiting on a threshold - during a penalty especially,
     * where a raw number flickering over the line while nothing happens reads as a broken app.
     */
    val smoothedBpm: Int?,
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
    /**
     * How sustainable the current effort is: 1 comfortably, 0 about to lose the zone. Null
     * whenever there is nothing to judge - suspended, or no zones configured.
     *
     * Unlike [secondsUntilPenalty] this is continuous and always present, which makes it the
     * signal a persistent indicator should be driven from. It is deliberately quantized: this
     * rides on top of someone's video, and a value jittering in its third decimal would repaint
     * on every tick of a ride.
     */
    val effortHealth: Float?,
    /** Seconds before the current decline reaches the floor; null when not heading there. */
    val headroomSeconds: Long?,
    /** The output that has been holding this rider's zone. Null until enough steady riding. */
    val holdingWatts: Float?,
    /** Rate of change of the judged heart rate. Positive while climbing. */
    val trendBpmPerMin: Float?,
) {
    val progress: Float
        get() = if (goalSeconds <= 0) 0f else (creditSeconds.toFloat() / goalSeconds).coerceIn(0f, 1f)
    val remainingSeconds: Long get() = (goalSeconds - creditSeconds).coerceAtLeast(0)
}
