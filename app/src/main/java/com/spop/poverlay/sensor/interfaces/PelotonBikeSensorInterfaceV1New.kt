package com.spop.poverlay.sensor.interfaces

import android.content.Context
import android.os.SystemClock
import com.spop.poverlay.sensor.BikePacketTracker
import com.spop.poverlay.sensor.BikeReading
import com.spop.poverlay.sensor.v1new.V1NewCombinedSensor
import com.spop.poverlay.sensor.v1new.v1Bindings
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.io.IOException

/** One application-owned binding, with bounded reconnects and source-packet freshness. */
class PelotonBikeSensorInterfaceV1New(context: Context) : SensorInterface {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tracker = BikePacketTracker()
    private val readings = MutableSharedFlow<BikeReading>(replay = 1, extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val lock = Any()
    private var session = 0L
    private val resistanceWindow = ArrayDeque<Float>()

    init {
        readings.tryEmit(BikeReading.Unavailable)
        scope.launch {
            while (isActive) {
                try {
                    v1Bindings(context.applicationContext).collectLatest { binder ->
                        val started = SystemClock.elapsedRealtime()
                        val generation = synchronized(lock) { ++session }
                        val sensor = V1NewCombinedSensor(binder, { data ->
                            synchronized(lock) {
                                if (generation == session) {
                                    val now = SystemClock.elapsedRealtime()
                                    val value = BikeReading(data.power.toFloat() / 100f, data.rpm.toFloat(), data.currentResistance.toFloat())
                                    if (tracker.accept(data.packetTime, value, now)) {
                                        resistanceWindow.addLast(value.resistance)
                                        if (resistanceWindow.size > 3) resistanceWindow.removeFirst()
                                        readings.tryEmit(value.copy(resistance = resistanceWindow.minOrNull()!!))
                                    }
                                }
                            }
                        }, {
                            synchronized(lock) {
                                if (generation == session) invalidate()
                            }
                        })
                        try {
                            sensor.start()
                            while (isActive) {
                                delay(1_000)
                                synchronized(lock) {
                                    val now = SystemClock.elapsedRealtime()
                                    if (!tracker.current(now).power.isFinite()) invalidate()
                                    if (now - started >= 30_000 && tracker.age(now) >= 30_000) {
                                        throw IOException("Peloton sensor packets stopped; rebinding")
                                    }
                                }
                            }
                        } finally {
                            synchronized(lock) { session++; invalidate() }
                            sensor.stop()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Reconnecting Peloton sensors")
                }
                synchronized(lock) { invalidate() }
                delay(3_000)
            }
        }
    }

    private fun invalidate() {
        tracker.invalidate()
        resistanceWindow.clear()
        readings.tryEmit(BikeReading.Unavailable)
    }

    fun stop() { scope.cancel() }
    override val power: Flow<Float> = readings.map { it.power }
    override val cadence: Flow<Float> = readings.map { it.cadence }
    override val resistance: Flow<Float> = readings.map { it.resistance }
}
