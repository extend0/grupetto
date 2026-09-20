package com.spop.poverlay.zone

import android.os.SystemClock
import com.spop.poverlay.media.MediaPenaltyController
import com.spop.poverlay.sensor.heartrate.HeartRateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * Drives [ZoneEnforcer] against live data and applies the effects it asks for.
 *
 * This is the only place in the feature that touches Android. Everything it decides has already
 * been decided by the pure machine; the job here is scheduling, plumbing and side effects.
 */
class ZoneEnforcementCoordinator(
    private val scope: CoroutineScope,
    private val configFlow: Flow<EnforcementConfig>,
    private val isMovingFlow: StateFlow<Boolean>,
    /** Output in watts. The one input that does not lag the rider's effort. */
    private val powerFlow: Flow<Float>,
    private val media: MediaPenaltyController,
    private val persistence: ZonePersistence,
    /** Optional: without it the curtain simply never stands down for another app. */
    private val foreground: ForegroundAppMonitor? = null,
    private val onShowCurtain: () -> Unit,
    private val onHideCurtain: () -> Unit,
) {
    companion object {
        /** Fast enough for a smooth recovery ring. */
        private const val ActivePeriodMs = 250L

        /** Matches the overlay's own refresh rate the rest of the time. */
        private const val IdlePeriodMs = 500L

        private const val PersistIntervalMs = 15_000L
        private const val ResumeRetryMs = 5_000L

        /**
         * Effort health drives no side effect yet, so the log is the only way to watch it
         * behave on a real ride before anything is built on top of it.
         */
        private const val EffortLogIntervalMs = 15_000L

        /** Foreground changes are a human switching apps; once a second is plenty. */
        private const val ForegroundPollMs = 1_000L

        /** How long the escalation gives us to notice a foreground change before drawing. */
        private const val ForegroundIdlePollMs = 5_000L
    }

    private val enforcer = ZoneEnforcer(EnforcementConfig())
    private val mutex = Mutex()
    private var jobs = mutableListOf<Job>()
    private var lastPersistedAtMs = 0L
    private var lastLoggedState: EnforcementState? = null

    // Read synchronously from ticks on another coroutine, so it must be volatile rather than
    // collected into the tick itself - the tick must never wait on the bike.
    @Volatile
    private var latestPower: PowerSample? = null

    private var lastResumeAttemptMs = 0L

    private var lastEffortLogMs = 0L

    fun start() {
        if (jobs.isNotEmpty()) return

        // A previous process may have died holding the media paused. Hand it back before doing
        // anything else - a frozen video is not something to make the rider figure out.
        val restoredCredit = persistence.restoreCredit()
        if (persistence.wasMediaLeftPaused()) {
            Timber.w("Previous session died holding media paused; handing it back")
            lastResumeAttemptMs = SystemClock.elapsedRealtime()
            media.resumeOrphaned()
            persistence.save(restoredCredit, media.pausedByUs)
        }
        enforcer.restoreCredit(restoredCredit)

        jobs += scope.launch {
            configFlow.collect { config ->
                mutex.withLock { enforcer.updateConfig(config) }
            }
        }
        jobs += scope.launch {
            powerFlow.collect { latestPower = PowerSample(it, SystemClock.elapsedRealtime()) }
        }
        foreground?.let { monitor ->
            if (!monitor.hasPermission()) {
                Timber.w(
                    "No Usage access grant; the curtain cannot tell what it is covering. " +
                        "Grant it under Settings > Apps > Special app access > Usage access."
                )
            } else {
                jobs += scope.launch {
                    while (isActive) {
                        // The answer is only ever used to decide whether to draw a curtain or
                        // a scrim, and neither exists outside an escalation - which on a good
                        // ride is no time at all. Asking the system every second through a
                        // clean forty-five minutes would be thousands of binder calls for a
                        // question nobody asked.
                        val drawing = isDrawing()
                        if (drawing) monitor.refresh()
                        delay(if (drawing) ForegroundPollMs else ForegroundIdlePollMs)
                    }
                }
            }
        }
        // Heart rate arrives at roughly 1 Hz; ticking on arrival keeps recovery snappy.
        jobs += scope.launch {
            HeartRateManager.heartRate.collect { tick() }
        }
        // The loop matters more than the kick: timeouts must fire when *no* sample arrives.
        jobs += scope.launch {
            while (isActive) {
                tick()
                delay(if (isEscalated()) ActivePeriodMs else IdlePeriodMs)
            }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        // Release first, then record the outcome: if handing the media back somehow failed, the
        // next process needs to know it is still paused.
        media.release()
        persistence.save(enforcer.creditMillis, media.pausedByUs)
        onHideCurtain()
        ZoneRuntime.publish(null)
    }

    /** New ride: drop the accumulated credit. */
    fun resetSession() {
        scope.launch {
            mutex.withLock {
                enforcer.reset()
                persistence.save(enforcer.creditMillis, media.pausedByUs)
            }
            tick()
        }
    }

    /** The curtain's escape hatch. */
    fun releaseByUser() {
        scope.launch {
            mutex.withLock { enforcer.releaseByUser() }
            tick()
        }
    }

    private fun isEscalated(): Boolean {
        val state = ZoneRuntime.snapshot.value?.state
        return state == EnforcementState.PENALTY || state == EnforcementState.RECOVERING
    }

    /** Whether anything of ours is on screen, or about to be. */
    private fun isDrawing(): Boolean {
        val state = ZoneRuntime.snapshot.value?.state
        return state == EnforcementState.WARNING || state == EnforcementState.PENALTY
    }

    private suspend fun tick() {
        val snapshot = mutex.withLock {
            enforcer.tick(
                TickInput(
                    // Monotonic: a wall-clock correction mid-ride must not skew a 45 minute goal.
                    nowMs = SystemClock.elapsedRealtime(),
                    bpm = HeartRateManager.heartRate.value,
                    hrAgeMs = HeartRateManager.heartRateAgeMs,
                    boundaries = HeartRateManager.heartRateZones.value,
                    isMoving = isMovingFlow.value,
                    powerWatts = latestPower?.freshWatts(SystemClock.elapsedRealtime()),
                    // Our own settings report themselves; anything else has to be observed.
                    overlaySuppressed = ZoneRuntime.settingsVisible ||
                        foreground?.isLauncherForeground == true,
                )
            )
        }

        if (snapshot.state != lastLoggedState) {
            Timber.i(
                "Zone enforcement %s -> %s (bpm=%s zone=%s drift=%s credit=%ds reason=%s)",
                lastLoggedState, snapshot.state, snapshot.bpm, snapshot.currentZone,
                snapshot.drift, snapshot.creditSeconds, snapshot.suspendReason,
            )
            lastLoggedState = snapshot.state
        }

        logEffortIfDue(snapshot)
        val wasPaused = media.pausedByUs
        snapshot.effects.forEach(::apply)
        val now = SystemClock.elapsedRealtime()
        // Effects are edge-triggered; a failed resume still needs another attempt.
        if (snapshot.state != EnforcementState.PENALTY && media.pausedByUs &&
            now - lastResumeAttemptMs >= ResumeRetryMs
        ) {
            lastResumeAttemptMs = now
            media.resume()
        }
        ZoneRuntime.publish(snapshot)
        persistIfDue(snapshot, wasPaused != media.pausedByUs)
    }

    private fun logEffortIfDue(snapshot: EnforcementSnapshot) {
        val health = snapshot.effortHealth ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastEffortLogMs < EffortLogIntervalMs) return
        lastEffortLogMs = now
        Timber.i(
            "Zone effort health=%.2f bpm=%s trend=%s/min watts=%s holding=%s headroom=%ss",
            health, snapshot.smoothedBpm, snapshot.trendBpmPerMin?.let { "%.1f".format(it) },
            latestPower?.freshWatts(now)?.let { "%.0f".format(it) },
            snapshot.holdingWatts?.let { "%.0f".format(it) }, snapshot.headroomSeconds,
        )
    }

    private fun apply(effect: PenaltyEffect) {
        when (effect) {
            PenaltyEffect.PauseMedia -> {
                val via = media.pause()
                if (via == null) {
                    Timber.w("Penalty engaged but nothing could be paused")
                } else {
                    Timber.i("Penalty paused media via %s", via)
                }
            }

            PenaltyEffect.ResumeMedia -> {
                lastResumeAttemptMs = SystemClock.elapsedRealtime()
                media.resume()
            }
            PenaltyEffect.ShowCurtain -> onShowCurtain()
            PenaltyEffect.HideCurtain -> onHideCurtain()
            // Carried to the UI on the snapshot rather than pushed.
            is PenaltyEffect.SetScrimAlpha -> Unit
            PenaltyEffect.GoalCompleted -> Timber.i("Zone goal complete")
        }
    }

    private fun persistIfDue(snapshot: EnforcementSnapshot, mediaStateChanged: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val dueByTime = now - lastPersistedAtMs >= PersistIntervalMs
        // Always record the moment media state changes, so a crash cannot strand it.
        val mediaChanged = snapshot.effects.any {
            it == PenaltyEffect.PauseMedia || it == PenaltyEffect.ResumeMedia
        }
        if (!dueByTime && !mediaChanged && !mediaStateChanged) return
        lastPersistedAtMs = now
        persistence.save(enforcer.creditMillis, media.pausedByUs)
    }
}
