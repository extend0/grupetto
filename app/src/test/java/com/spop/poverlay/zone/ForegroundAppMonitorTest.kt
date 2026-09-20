package com.spop.poverlay.zone

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.SystemClock
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ForegroundAppMonitorTest {
    private val context = mockk<Context>()
    private val manager = mockk<UsageStatsManager>()
    private lateinit var monitor: ForegroundAppMonitor
    private val changes = mutableListOf<Pair<Long, String>>()

    @Before fun setup() {
        mockkStatic(SystemClock::class)
        mockkConstructor(UsageEvents.Event::class)
        var currentPackage = ""
        every { anyConstructed<UsageEvents.Event>().eventType } returns UsageEvents.Event.MOVE_TO_FOREGROUND
        every { anyConstructed<UsageEvents.Event>().packageName } answers { currentPackage }
        every { SystemClock.elapsedRealtime() } returns 600_000L
        every { context.getSystemService(Context.USAGE_STATS_SERVICE) } returns manager
        every { manager.queryEvents(any(), any()) } answers {
            val start = firstArg<Long>()
            val end = secondArg<Long>()
            val entries = changes.filter { it.first in start..end }.iterator()
            mockk<UsageEvents>().also { events ->
                every { events.hasNextEvent() } answers { entries.hasNext() }
                every { events.getNextEvent(any()) } answers {
                    currentPackage = entries.next().second
                    true
                }
            }
        }
        monitor = ForegroundAppMonitor(context)
    }

    @After fun cleanup() = unmockkAll()

    @Test fun `startup finds an app opened more than a minute ago`() {
        changes += 500_000L to "launcher"
        monitor.refresh(1_000_000L)
        assertEquals("launcher", monitor.foregroundPackage)
    }

    @Test fun `resuming polling covers switches during the entire gap`() {
        changes += 900_000L to "launcher"
        monitor.refresh(1_000_000L)
        changes += 1_100_000L to "video"
        monitor.refresh(1_600_000L)
        assertEquals("video", monitor.foregroundPackage)
        changes += 1_700_000L to "launcher"
        monitor.refresh(2_200_000L)
        assertEquals("launcher", monitor.foregroundPackage)
        monitor.refresh(2_201_000L)
        assertEquals("launcher", monitor.foregroundPackage)
    }

    @Test fun `failed query does not discard the uncovered interval`() {
        changes += 900_000L to "video"
        monitor.refresh(1_000_000L)
        every { manager.queryEvents(any(), 1_600_000L) } throws IllegalStateException()
        changes += 1_100_000L to "launcher"
        monitor.refresh(1_600_000L)
        monitor.refresh(1_700_000L)
        assertEquals("launcher", monitor.foregroundPackage)
    }
}
