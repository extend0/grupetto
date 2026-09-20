package com.spop.poverlay.media

import android.content.Context
import timber.log.Timber

/**
 * Pauses and resumes whatever is playing underneath, using the best mechanism available on this
 * particular tablet.
 *
 * The one rule that matters: **never send play unless we sent pause.** A stray resume would
 * start a video the rider deliberately paused, which is far more annoying than a penalty that
 * fails to land.
 */
class MediaPenaltyController(private val strategies: List<MediaPenaltyStrategy>) {

    constructor(context: Context) : this(
        listOf(
            MediaSessionStrategy(context),
            MediaKeyStrategy(context),
            AudioFocusStrategy(context),
        )
    )

    /** Non-null exactly when we are holding something paused. */
    @Volatile
    private var activeStrategy: MediaPenaltyStrategy? = null

    @Volatile
    private var orphanedPause = false

    val pausedByUs: Boolean get() = activeStrategy != null || orphanedPause

    /** Returns the display name of whatever worked, or null if nothing did. */
    @Synchronized
    fun pause(): String? {
        activeStrategy?.let { return it.displayName }
        for (strategy in strategies) {
            val available = runCatching { strategy.isAvailable() }.getOrDefault(false)
            if (!available) continue
            val paused = runCatching { strategy.pause() }
                .onFailure { Timber.w(it, "Strategy %s failed to pause", strategy.id) }
                .getOrDefault(false)
            if (paused) {
                activeStrategy = strategy
                Timber.i("Media paused via %s", strategy.id)
                return strategy.displayName
            }
        }
        Timber.i("Nothing to pause - no strategy succeeded")
        return null
    }

    @Synchronized
    fun resume(): Boolean {
        val strategy = activeStrategy ?: return if (orphanedPause) resumeOrphaned() != null else false
        return runCatching { strategy.resume() }
            .onFailure { Timber.w(it, "Strategy %s failed to resume", strategy.id) }
            .getOrDefault(false)
            .also {
                if (it) {
                    activeStrategy = null
                    orphanedPause = false
                }
                Timber.i("Media resumed via %s (success=%b)", strategy.id, it)
            }
    }

    /**
     * Recovery for media stranded by a process that died mid-penalty.
     *
     * The normal resume path deliberately refuses to act without a matching pause, and a fresh
     * process has no record of which sessions were paused - so this is the one place that sends
     * a blind play. It is only ever called when persisted state says we left something paused.
     */
    @Synchronized
    fun resumeOrphaned(): String? {
        orphanedPause = true
        for (strategy in strategies) {
            val resumed = runCatching { strategy.resume() }
                .onFailure { Timber.w(it, "Strategy %s failed to resume orphaned media", strategy.id) }
                .getOrDefault(false)
            if (resumed) {
                orphanedPause = false
                Timber.i("Resumed orphaned media via %s", strategy.id)
                return strategy.displayName
            }
        }
        return null
    }

    /** Service teardown: give the media back and drop any OS resource we hold. */
    @Synchronized
    fun release() {
        resume()
        strategies.forEach { runCatching { it.release() } }
    }

    fun capabilities(): MediaCapabilities {
        val session = strategies.filterIsInstance<MediaSessionStrategy>().firstOrNull()
        return MediaCapabilities(
            notificationListenerGranted = session?.isListenerEnabled() ?: false,
            playingPackages = session?.playingPackages().orEmpty(),
            availableStrategies = strategies
                .filter { runCatching { it.isAvailable() }.getOrDefault(false) }
                .map { it.displayName },
        )
    }
}
