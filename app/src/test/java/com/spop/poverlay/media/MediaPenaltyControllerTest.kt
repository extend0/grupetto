package com.spop.poverlay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPenaltyControllerTest {

    private class FakeStrategy(
        override val id: String,
        var available: Boolean = true,
        var pauseSucceeds: Boolean = true,
        var throwOnPause: Boolean = false,
        var resumeSucceeds: Boolean = true,
        var throwOnResume: Boolean = false,
    ) : MediaPenaltyStrategy {
        override val displayName = id
        var pauseCalls = 0
        var resumeCalls = 0
        var released = false

        override fun isAvailable() = available

        override fun pause(): Boolean {
            pauseCalls++
            if (throwOnPause) throw IllegalStateException("boom")
            return pauseSucceeds
        }

        override fun resume(): Boolean {
            resumeCalls++
            if (throwOnResume) throw IllegalStateException("resume failed")
            return resumeSucceeds
        }

        override fun release() {
            released = true
        }
    }

    @Test
    fun `ending workout drops pause ownership without playing old media`() {
        val strategy = FakeStrategy("session")
        val controller = MediaPenaltyController(listOf(strategy))
        controller.pause()
        controller.endWorkout()
        controller.release()
        assertFalse(controller.pausedByUs)
        assertEquals(0, strategy.resumeCalls)
        assertTrue(strategy.released)
    }

    @Test
    fun `stops at the first strategy that works`() {
        val first = FakeStrategy("first")
        val second = FakeStrategy("second")
        val controller = MediaPenaltyController(listOf(first, second))

        assertEquals("first", controller.pause())
        assertEquals(1, first.pauseCalls)
        assertEquals(0, second.pauseCalls)
    }

    @Test
    fun `skips unavailable strategies`() {
        val unavailable = FakeStrategy("unavailable", available = false)
        val usable = FakeStrategy("usable")
        val controller = MediaPenaltyController(listOf(unavailable, usable))

        assertEquals("usable", controller.pause())
        assertEquals(0, unavailable.pauseCalls)
    }

    @Test
    fun `falls through a strategy that finds nothing to pause`() {
        val nothingPlaying = FakeStrategy("nothing", pauseSucceeds = false)
        val usable = FakeStrategy("usable")
        val controller = MediaPenaltyController(listOf(nothingPlaying, usable))

        assertEquals("usable", controller.pause())
    }

    @Test
    fun `a throwing strategy does not break the chain`() {
        val broken = FakeStrategy("broken", throwOnPause = true)
        val usable = FakeStrategy("usable")
        val controller = MediaPenaltyController(listOf(broken, usable))

        assertEquals("usable", controller.pause())
    }

    @Test
    fun `reports failure when nothing can be paused`() {
        val controller = MediaPenaltyController(
            listOf(FakeStrategy("a", pauseSucceeds = false), FakeStrategy("b", available = false))
        )

        assertNull(controller.pause())
        assertFalse(controller.pausedByUs)
    }

    @Test
    fun `never resumes what it did not pause`() {
        val strategy = FakeStrategy("only")
        val controller = MediaPenaltyController(listOf(strategy))

        assertFalse(controller.resume())
        assertEquals(0, strategy.resumeCalls)
    }

    @Test
    fun `resumes with the same strategy that paused`() {
        val first = FakeStrategy("first", pauseSucceeds = false)
        val second = FakeStrategy("second")
        val controller = MediaPenaltyController(listOf(first, second))

        controller.pause()
        assertTrue(controller.resume())
        assertEquals(0, first.resumeCalls)
        assertEquals(1, second.resumeCalls)
    }

    @Test
    fun `a second pause is a no-op while already paused`() {
        val strategy = FakeStrategy("only")
        val controller = MediaPenaltyController(listOf(strategy))

        controller.pause()
        controller.pause()
        assertEquals(1, strategy.pauseCalls)
    }

    @Test
    fun `resume clears the latch so the next penalty starts fresh`() {
        val strategy = FakeStrategy("only")
        val controller = MediaPenaltyController(listOf(strategy))

        controller.pause()
        controller.resume()
        assertFalse(controller.pausedByUs)

        controller.pause()
        assertEquals(2, strategy.pauseCalls)
    }

    @Test
    fun `release hands the media back and frees every strategy`() {
        val first = FakeStrategy("first")
        val second = FakeStrategy("second")
        val controller = MediaPenaltyController(listOf(first, second))

        controller.pause()
        controller.release()

        assertEquals(1, first.resumeCalls)
        assertTrue(first.released)
        assertTrue(second.released)
        assertFalse(controller.pausedByUs)
    }

    @Test
    fun `failed resumes retain ownership until retry succeeds`() {
        for (throws in listOf(true, false)) {
            val strategy = FakeStrategy("only", resumeSucceeds = false, throwOnResume = throws)
            val controller = MediaPenaltyController(listOf(strategy))
            controller.pause()
            assertFalse(controller.resume())
            assertTrue(controller.pausedByUs)
            strategy.resumeSucceeds = true
            strategy.throwOnResume = false
            assertTrue(controller.resume())
            assertFalse(controller.pausedByUs)
            assertFalse(controller.resume())
            assertEquals(2, strategy.resumeCalls)
        }
    }

    @Test
    fun `failed teardown keeps the persisted recovery flag`() {
        val strategy = FakeStrategy("only", resumeSucceeds = false)
        val controller = MediaPenaltyController(listOf(strategy))
        controller.pause()
        controller.release()
        assertTrue(controller.pausedByUs)
        assertTrue(strategy.released)
    }

    @Test
    fun `failed orphan recovery stays pending and can be retried`() {
        val strategy = FakeStrategy("only", resumeSucceeds = false)
        val controller = MediaPenaltyController(listOf(strategy))
        assertNull(controller.resumeOrphaned())
        assertTrue(controller.pausedByUs)
        strategy.resumeSucceeds = true
        assertTrue(controller.resume())
        assertFalse(controller.pausedByUs)
    }
}
