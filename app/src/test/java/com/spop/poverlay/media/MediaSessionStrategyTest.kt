package com.spop.poverlay.media

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MediaSessionStrategyTest {
    private val context = mockk<Context>()
    private val manager = mockk<MediaSessionManager>()
    private lateinit var strategy: MediaSessionStrategy

    @Before fun setup() {
        mockkConstructor(ComponentName::class)
        every { context.getSystemService(Context.MEDIA_SESSION_SERVICE) } returns manager
        strategy = MediaSessionStrategy(context)
    }

    @After fun cleanup() = unmockkAll()

    private fun player(name: String): MediaController = mockk<MediaController>(relaxed = true).also {
        every { it.packageName } returns name
        every { it.playbackState } returns mockk<PlaybackState> {
            every { state } returns PlaybackState.STATE_PLAYING
        }
    }

    private fun paused(player: MediaController) {
        every { player.playbackState } returns mockk<PlaybackState> {
            every { state } returns PlaybackState.STATE_PAUSED
        }
    }

    @Test fun `retry resumes only sessions that failed`() {
        val first = player("first")
        val second = player("second")
        val firstControls = first.transportControls
        val secondControls = second.transportControls
        every { manager.getActiveSessions(any()) } returns listOf(first, second)
        assertTrue(strategy.pause())
        paused(first)
        paused(second)
        every { secondControls.play() } throws IllegalStateException()
        assertFalse(strategy.resume())
        every { secondControls.play() } just Runs
        assertTrue(strategy.resume())
        verify(exactly = 1) { firstControls.play() }
        verify(exactly = 2) { secondControls.play() }
        assertFalse(strategy.resume())
    }

    @Test fun `temporarily missing session remains eligible for retry`() {
        val player = player("video")
        val controls = player.transportControls
        every { manager.getActiveSessions(any()) } returns listOf(player)
        assertTrue(strategy.pause())
        paused(player)
        every { manager.getActiveSessions(any()) } returns emptyList()
        assertFalse(strategy.resume())
        every { manager.getActiveSessions(any()) } returns listOf(player)
        assertTrue(strategy.resume())
        verify(exactly = 1) { controls.play() }
    }
}
