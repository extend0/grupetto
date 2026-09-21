package com.spop.poverlay.workout

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spop.poverlay.sensor.interfaces.SensorInterface
import com.spop.poverlay.strava.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
class WorkoutRecorderTest {
    @Test fun autoEndUsesTwentyFiveMinutesTrimsIdleAndEndsZoneOnce() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "recorder-${java.util.UUID.randomUUID()}.db"
        val repository = WorkoutRepository(context, name)
        val settings = WorkoutSettings(context)
        val oldTimeout = settings.timeoutMinutes; val oldAutomatic = settings.autoUpload
        settings.timeoutMinutes = 25; settings.autoUpload = false
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val vault = CredentialVault(context, "test-recorder-auth")
        val auth = StravaAuthManager(context, scope, vault = vault)
        val time = AtomicLong(1000)
        val recorder = WorkoutRecorder(context, repository, settings, auth, scope,
            wallClock = { 1_700_000_000_000L + time.get() }, elapsedClock = { time.get() })
        val sensors = object : SensorInterface {
            override val power = MutableSharedFlow<Float>(replay = 1)
            override val cadence = MutableSharedFlow<Float>(replay = 1)
            override val resistance = MutableSharedFlow<Float>(replay = 1)
            override val speed = MutableSharedFlow<Float>(replay = 1)
        }
        var starts = 0; var ends = 0
        try {
            auth.initialized.await()
            withContext(Dispatchers.Main) {
                recorder.attach(sensors, start = { starts++ }, end = { ends++ })
                recorder.start()
                // An already queued sensor callback must not expire a just-started workout.
                time.set(999)
                recorder.tick()
                assertNotNull(recorder.current.value)
                time.set(1000)
                sensors.power.emit(200f); sensors.speed.emit(20f); sensors.cadence.emit(80f)
            }
            withTimeout(5_000) { while (recorder.current.value?.lastPedaledAt == null) delay(10) }
            val id = recorder.current.value!!.id
            withContext(Dispatchers.Main) { recorder.tick() }
            time.addAndGet(25 * 60_000L - 1)
            withContext(Dispatchers.Main) { recorder.tick() }
            assertNotNull(recorder.current.value)
            time.incrementAndGet()
            withContext(Dispatchers.Main) { recorder.tick() }
            assertNull(recorder.current.value)
            assertEquals(1, starts); assertEquals(1, ends)
            val saved = repository.dao.get(id)!!
            assertEquals(saved.lastPedaledAt, saved.endedAt)
            assertEquals("Inactivity", saved.reason)
            assertTrue(repository.dao.samples(id).all { it.timeMs <= saved.endedAt!! })
            withContext(Dispatchers.Main) { recorder.tick() }
            assertEquals(1, ends)
        } finally {
            withContext(Dispatchers.Main) { recorder.detach() }
            scope.cancel(); repository.database.close(); vault.clear(); context.deleteDatabase(name)
            settings.timeoutMinutes = oldTimeout; settings.autoUpload = oldAutomatic
        }
    }
}
