package com.spop.poverlay.sensor.v1new

import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException

class SensorBindingTest {
    private val context = mockk<Context>(relaxed = true)
    private val connection = slot<ServiceConnection>()
    private val binder = mockk<IBinder>()

    @Before fun setup() {
        mockkConstructor(Intent::class)
        every { anyConstructed<Intent>().setPackage(any()) } answers { self as Intent }
        every { context.bindService(any(), capture(connection), any<Int>()) } returns true
    }
    @After fun cleanup() = unmockkAll()

    @Test fun `cancellation releases the binding and repeated connected callbacks are safe`() = runBlocking {
        val values = mutableListOf<IBinder>()
        val job = launch(Dispatchers.Unconfined) { v1Bindings(context).collect { values.add(it) } }
        yield()
        connection.captured.onServiceConnected(null, binder)
        connection.captured.onServiceConnected(null, binder)
        yield()
        assertEquals(2, values.size)
        job.cancelAndJoin()
        verify(exactly = 1) { context.unbindService(connection.captured) }
    }

    @Test fun `service death closes the stream so its owner can retry`() = runBlocking {
        var error: Throwable? = null
        val job = launch(Dispatchers.Unconfined) {
            v1Bindings(context).catch { error = it }.collect()
        }
        yield()
        connection.captured.onBindingDied(null)
        job.join()
        assertTrue(error is IOException)
        verify(exactly = 1) { context.unbindService(connection.captured) }
    }

    @Test fun `rejected binding fails immediately and releases resources`() = runBlocking {
        every { context.bindService(any(), capture(connection), any<Int>()) } returns false
        var error: Throwable? = null
        v1Bindings(context).catch { error = it }.collect()
        assertTrue(error is IOException)
        verify(exactly = 1) { context.unbindService(connection.captured) }
    }
}
