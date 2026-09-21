package com.spop.poverlay.media

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import timber.log.Timber

/**
 * The precise strategy: talk to the player's own MediaSession.
 *
 * Requires the user to have enabled [GrupettoNotificationListenerService] (Settings, or
 * `adb shell cmd notification allow_listener ...`). It is the only strategy that can tell
 * whether a video is actually playing and whether we were the one who paused it, which is why
 * it goes first in the chain.
 */
class MediaSessionStrategy(private val context: Context) : MediaPenaltyStrategy {

    override val id = "media_session"
    override val displayName = "media session"

    private val component = ComponentName(context, GrupettoNotificationListenerService::class.java)

    /** Packages this strategy paused, so resume touches exactly those and nothing else. */
    private var pausedPackages: List<String> = emptyList()

    fun isListenerEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ) ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == component }
    }

    private fun controllers(): List<MediaController> = runCatching {
        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        manager.getActiveSessions(component)
    }.getOrElse {
        // Throws SecurityException until the listener is granted; that is expected, not an error.
        Timber.d("Media sessions unavailable: %s", it.message)
        emptyList()
    }

    fun playingPackages(): List<String> =
        controllers().filter { it.isPlaying() }.map { it.packageName }

    override fun isAvailable() = isListenerEnabled()

    override fun pause(): Boolean {
        val playing = controllers().filter { it.isPlaying() }
        if (playing.isEmpty()) return false
        pausedPackages = playing.filter {
            runCatching { it.transportControls.pause() }.isSuccess
        }.map { it.packageName }
        Timber.i("Paused media sessions: %s", pausedPackages.joinToString())
        return pausedPackages.isNotEmpty()
    }

    override fun resume(): Boolean {
        if (pausedPackages.isEmpty()) return false
        val byPackage = controllers().associateBy { it.packageName }
        // Keep only unresolved targets. A retry must not replay an already released session.
        pausedPackages = pausedPackages.filter { packageName ->
            val controller = byPackage[packageName]
            controller == null || runCatching {
                if (!controller.isPlaying()) controller.transportControls.play()
            }.isFailure
        }
        return pausedPackages.isEmpty()
    }

    override fun endWorkout() {
        pausedPackages = emptyList()
    }

    private fun MediaController.isPlaying() =
        playbackState?.state == PlaybackState.STATE_PLAYING
}
