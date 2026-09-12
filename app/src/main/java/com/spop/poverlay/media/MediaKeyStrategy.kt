package com.spop.poverlay.media

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import timber.log.Timber

/**
 * The no-permission fallback: simulate a media button press.
 *
 * AudioManager.dispatchMediaKeyEvent is public and carries no permission requirement; it routes
 * to whichever session currently owns media buttons. We cannot read playback state through it,
 * so this strategy is blind - which is exactly why it sends explicit PAUSE and PLAY rather than
 * PLAY_PAUSE. A toggle would desync the moment the user touched the screen.
 */
class MediaKeyStrategy(context: Context) : MediaPenaltyStrategy {

    override val id = "media_key"
    override val displayName = "media key"

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override fun isAvailable() = runCatching { audioManager.isMusicActive }.getOrDefault(false)

    override fun pause() = dispatch(KeyEvent.KEYCODE_MEDIA_PAUSE)

    override fun resume() = dispatch(KeyEvent.KEYCODE_MEDIA_PLAY)

    private fun dispatch(keyCode: Int): Boolean = runCatching {
        // A media button is a down/up pair; sending only one half is ignored by most players.
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        true
    }.onFailure { Timber.w(it, "Media key dispatch failed") }.getOrDefault(false)
}
