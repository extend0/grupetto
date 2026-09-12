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
        playing.forEach { runCatching { it.transportControls.pause() } }
        pausedPackages = playing.map { it.packageName }
        Timber.i("Paused media sessions: %s", pausedPackages.joinToString())
        return true
    }

    override fun resume(): Boolean {
        val targets = pausedPackages
        pausedPackages = emptyList()
        if (targets.isEmpty()) return false

        val byPackage = controllers().associateBy { it.packageName }
        var resumedAny = false
        targets.forEach { packageName ->
            val controller = byPackage[packageName] ?: return@forEach
            // Never fight the user: if they already hit play, leave it be.
            if (controller.isPlaying()) {
                resumedAny = true
                return@forEach
            }
            runCatching { controller.transportControls.play() }.onSuccess { resumedAny = true }
        }
        return resumedAny
    }

    private fun MediaController.isPlaying() =
        playbackState?.state == PlaybackState.STATE_PLAYING
}
