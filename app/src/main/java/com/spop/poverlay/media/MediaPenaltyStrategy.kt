package com.spop.poverlay.media

/**
 * One way of pausing whatever is playing underneath the overlay.
 *
 * There is no single reliable mechanism for controlling a foreign player on Android, so the
 * feature runs a chain of these from most precise to most desperate. Implementations must be
 * safe to call when nothing is playing: return false rather than throwing.
 */
interface MediaPenaltyStrategy {
    val id: String
    val displayName: String

    /** Cheap check - is this worth trying right now? */
    fun isAvailable(): Boolean

    /** True only if something was actually paused. */
    fun pause(): Boolean

    /** Undo [pause]. Only ever called after this strategy's own pause returned true. */
    fun resume(): Boolean

    /** Drop any OS resource held (audio focus, in practice). */
    fun release() {}
}

/** What the settings screen shows the user about media control on this particular tablet. */
data class MediaCapabilities(
    val notificationListenerGranted: Boolean,
    val playingPackages: List<String>,
    val availableStrategies: List<String>,
) {
    val canControlPrecisely: Boolean get() = notificationListenerGranted
    val somethingIsPlaying: Boolean get() = playingPackages.isNotEmpty() || availableStrategies.isNotEmpty()
}
