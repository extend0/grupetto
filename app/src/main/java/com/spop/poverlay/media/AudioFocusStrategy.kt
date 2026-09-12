package com.spop.poverlay.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import timber.log.Timber

/**
 * Last resort: take transient audio focus and hold it for the length of the penalty.
 *
 * Well-behaved players pause on transient focus loss and resume themselves when it is abandoned,
 * which makes this the tidiest mechanism when it works. It is last in the chain because the
 * system cannot *force* compliance on Android 11 and below - a player may simply keep going.
 * Even then it is not useless: combined with the curtain, losing the audio is most of the effect.
 */
class AudioFocusStrategy(context: Context) : MediaPenaltyStrategy {

    override val id = "audio_focus"
    override val displayName = "audio focus"

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var request: AudioFocusRequest? = null
    private var held = false

    override fun isAvailable() = true

    override fun pause(): Boolean {
        if (held) return true
        val result = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setWillPauseWhenDucked(true)
                    .build()
                request = focusRequest
                audioManager.requestAudioFocus(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
                )
            }
        }.getOrElse {
            Timber.w(it, "Audio focus request failed")
            AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }
        held = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return held
    }

    override fun resume(): Boolean {
        if (!held) return false
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                request?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        }.onFailure { Timber.w(it, "Abandoning audio focus failed") }
        request = null
        held = false
        return true
    }

    /** Never leave focus held when the service goes away, or the tablet stays muted. */
    override fun release() {
        resume()
    }
}
