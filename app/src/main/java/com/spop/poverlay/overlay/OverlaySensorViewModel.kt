@file:OptIn(kotlinx.coroutines.InternalCoroutinesApi::class, kotlin.time.ExperimentalTime::class)

package com.spop.poverlay.overlay

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spop.poverlay.MainActivity
import com.spop.poverlay.sensor.DeadSensorDetector
import com.spop.poverlay.sensor.heartrate.HeartRateManager
import com.spop.poverlay.sensor.interfaces.SensorInterface
import com.spop.poverlay.util.smoothSensorValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

private const val MphToKph = 1.60934

enum class MetricType {
    POWER, CADENCE, RESISTANCE, SPEED, HEART_RATE
}

/**
 * Calorie calculation constants using Gross Mechanical Efficiency (GME) method:
 * 
 * 1. Calculate mechanical work: Power (W) × Time (s) = Energy in Joules
 * 2. Convert to mechanical kcal: Joules / 4184
 * 3. Account for body efficiency: Metabolic kcal = Mechanical kcal / Efficiency
 * 
 * Cycling efficiency represents the ratio of mechanical power output to metabolic power input.
 * Research shows typical values:
 * - Recreational cyclists: ~20%
 * - Average trained cyclists: ~22% (used here)
 * - Well-trained/elite cyclists: ~25%
 * 
 * This matches industry-standard calculations used by Garmin, Wahoo, and other cycling computers.
 */
private const val CyclingEfficiency = 0.22 // 22% efficiency (typical for cycling)
private const val CaloriesPerJoule = 4184.0 // Joules per kcal (thermochemical calorie definition)

class OverlaySensorViewModel(
    application: Application,
    private val sensorInterface: SensorInterface,
    private val deadSensorDetector: DeadSensorDetector,
    private val timerViewModel: OverlayTimerViewModel
) : AndroidViewModel(application) {

    companion object {
        // The sensor does not necessarily return new value this quickly
        val UiUpdatePeriod = 500.milliseconds

        // Max number of points before data starts to shift
        const val GraphMaxDataPoints = 300

    }


    //TODO: Move this logic to dialog view model
    private val mutableIsMinimized = MutableStateFlow(false)
    val isMinimized = mutableIsMinimized.asStateFlow()

    private val mutableErrorMessage = MutableStateFlow<String?>(null)
    val errorMessage = mutableErrorMessage.asStateFlow()

    private val mutableSelectedMetric = MutableStateFlow(MetricType.POWER)
    val selectedMetric = mutableSelectedMetric.asStateFlow()

    fun onDismissErrorPressed() {
        mutableErrorMessage.tryEmit(null)
    }

    fun onOverlayPressed() {
        mutableIsMinimized.apply { value = !value }
    }

    fun minimizeOverlay() {
        mutableIsMinimized.value = true
    }

    fun setMinimized(minimized: Boolean) {
        mutableIsMinimized.value = minimized
    }

    fun onOverlayDoubleTap() {
        getApplication<Application>().apply {
            val intent = Intent(this, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }
    }

    fun onMetricSelected(metric: MetricType) {
        viewModelScope.launch {
            mutableSelectedMetric.emit(metric)
        }
    }

    private fun onDeadSensor() {
        mutableErrorMessage
            .tryEmit(
                "Bike sensor data is unavailable. Reconnecting. " +
                        "If readings do not return, finish and save your workout before " +
                        "power-cycling the bike."
            )
    }

    private var useMph = MutableStateFlow(true)

    // Max value tracking
    private val mutableMaxPower = MutableStateFlow(0f)
    private val mutableMaxCadence = MutableStateFlow(0f)
    private val mutableMaxResistance = MutableStateFlow(0f)
    private val mutableMaxSpeed = MutableStateFlow(0f)
    private val mutableMaxHeartRate = MutableStateFlow(0f)

    // Session totals tracking
    private val mutableTotalEnergy = MutableStateFlow(0f) // kilojoules
    private val mutableTotalDistance = MutableStateFlow(0f) // miles
    private var lastUpdateTime = System.currentTimeMillis()

    // Session averages tracking (time-weighted)
    private var totalActiveTime = 0f // seconds
    private var sumSpeed = 0f // speed × time accumulator
    private var sumResistance = 0f // resistance × time accumulator
    private var sumCadence = 0f // cadence × time accumulator
    private var sumHeartRate = 0f // heart rate × time accumulator
    private var totalHeartRateTime = 0f // seconds with valid heart rate while moving
    private val mutableAvgSpeed = MutableStateFlow(0f)
    private val mutableAvgResistance = MutableStateFlow(0f)
    private val mutableAvgCadence = MutableStateFlow(0f)
    private val mutableAvgHeartRate = MutableStateFlow(0f)

    // Movement tracking for timer auto-start/pause
    private val mutableIsMoving = MutableStateFlow(false)
    val isMoving = mutableIsMoving.asStateFlow()

    val maxPower = mutableMaxPower.asStateFlow()
    val maxCadence = mutableMaxCadence.asStateFlow()
    val maxResistance = mutableMaxResistance.asStateFlow()
    val maxSpeed = mutableMaxSpeed.asStateFlow()
    val maxHeartRate = mutableMaxHeartRate.asStateFlow()

    val totalEnergy = mutableTotalEnergy.asStateFlow() // kilojoules
    val totalDistance = mutableTotalDistance.asStateFlow() // miles

    val avgSpeed = mutableAvgSpeed.asStateFlow() // mph
    val avgResistance = mutableAvgResistance.asStateFlow()
    val avgCadence = mutableAvgCadence.asStateFlow()
    val avgHeartRate = mutableAvgHeartRate.asStateFlow()

    /** Movement is based on fresh cadence samples, not latched speed or resistance. */
    fun setMoving(moving: Boolean) {
        if (mutableIsMoving.value != moving) timerViewModel.onMovementChanged(moving)
        mutableIsMoving.value = moving
    }

    /** Called once when the workout expires, even when no sensor events are arriving. */
    fun resetWorkout() {
        mutableIsMoving.value = false
        timerViewModel.resetTimer()
        mutableMaxPower.value = 0f
        mutableMaxCadence.value = 0f
        mutableMaxResistance.value = 0f
        mutableMaxSpeed.value = 0f
        mutableMaxHeartRate.value = 0f
        mutableTotalEnergy.value = 0f
        mutableTotalDistance.value = 0f
        accumulatedEnergy.value = 0.0
        totalActiveTime = 0f
        totalHeartRateTime = 0f
        sumSpeed = 0f
        sumResistance = 0f
        sumCadence = 0f
        sumHeartRate = 0f
        mutableAvgSpeed.value = 0f
        mutableAvgResistance.value = 0f
        mutableAvgCadence.value = 0f
        mutableAvgHeartRate.value = 0f
        lastUpdateTime = System.currentTimeMillis()
        powerGraph.clear()
        cadenceGraph.clear()
        resistanceGraph.clear()
        speedGraph.clear()
        heartRateGraph.clear()
    }

    private fun updateSessionStats(power: Float, cadence: Float, resistance: Float, speed: Float, heartRate: Float) {
        val currentTime = System.currentTimeMillis()
        val deltaSeconds = (currentTime - lastUpdateTime).coerceIn(0L, 5_000L) / 1000f
        lastUpdateTime = currentTime

        // Heart-rate events can arrive while bike telemetry is unavailable.
        if (!power.isFinite() || !cadence.isFinite() || !resistance.isFinite() || !speed.isFinite()) return
        mutableErrorMessage.value = null
        val isCurrentlyMoving = mutableIsMoving.value
        if (isCurrentlyMoving) {
            // Update maxima while pedalling
            if (power > mutableMaxPower.value) mutableMaxPower.value = power
            if (cadence > mutableMaxCadence.value) mutableMaxCadence.value = cadence
            if (resistance > mutableMaxResistance.value) mutableMaxResistance.value = resistance
            if (speed > mutableMaxSpeed.value) mutableMaxSpeed.value = speed
            if (heartRate > mutableMaxHeartRate.value) mutableMaxHeartRate.value = heartRate

            // Only accumulate totals and averages when actively moving
            if (isCurrentlyMoving) {
                // Accumulate session totals
                // Energy: power (watts) × time (seconds) = joules, divide by 1000 for kJ
                mutableTotalEnergy.value += (power * deltaSeconds) / 1000f
                accumulatedEnergy.value += power * deltaSeconds
                // Distance: speed (mph) × time (hours) = miles
                mutableTotalDistance.value += speed * (deltaSeconds / 3600f)

                // Accumulate time-weighted sums for averages
                totalActiveTime += deltaSeconds
                sumSpeed += speed * deltaSeconds
                sumResistance += resistance * deltaSeconds
                sumCadence += cadence * deltaSeconds
                if (heartRate > 0f) {
                    sumHeartRate += heartRate * deltaSeconds
                    totalHeartRateTime += deltaSeconds
                }

                // Update averages
                if (totalActiveTime > 0f) {
                    mutableAvgSpeed.value = sumSpeed / totalActiveTime
                    mutableAvgResistance.value = sumResistance / totalActiveTime
                    mutableAvgCadence.value = sumCadence / totalActiveTime
                }
                if (totalHeartRateTime > 0f) {
                    mutableAvgHeartRate.value = sumHeartRate / totalHeartRateTime
                }
            }
        }
    }

    val powerValue = sensorInterface.power
        .sample(UiUpdatePeriod)
        .map { if (it.isFinite()) "%.0f".format(it) else "--" }
    val rpmValue = sensorInterface.cadence
        .sample(UiUpdatePeriod)
        .map { if (it.isFinite()) "%.0f".format(it) else "--" }

    val resistanceValue = sensorInterface.resistance
        .sample(UiUpdatePeriod)
        .map { if (it.isFinite()) "%.0f".format(it) else "--" }

    val speedValue = combine(
        sensorInterface.speed, useMph
    ) { speed, isMph ->
        val value = if (isMph) {
            speed.toDouble()
        } else {
            speed * MphToKph
        }
        if (value.isFinite()) "%.1f".format(value) else "--"
    }.sample(UiUpdatePeriod)
    val speedLabel = useMph.map {
        if (it) {
            "mph"
        } else {
            "kph"
        }
    }

    fun onClickedSpeedUnit() {
        viewModelScope.launch {
            useMph.emit(!useMph.value)
        }
    }

    // Calculate calories burned by accumulating energy over time
    // Calories (kcal) = Total Energy (Joules) / 4184 / Efficiency
    private val accumulatedEnergy = MutableStateFlow(0.0)
    
    val caloriesValue = accumulatedEnergy
        .sample(UiUpdatePeriod)
        .map { totalJoules ->
            val calories = totalJoules / CaloriesPerJoule / CyclingEfficiency
            "%.0f".format(calories)
        }
    
    val powerGraph = mutableStateListOf<Float>()
    val cadenceGraph = mutableStateListOf<Float>()
    val resistanceGraph = mutableStateListOf<Float>()
    val speedGraph = mutableStateListOf<Float>()
    val heartRateGraph = mutableStateListOf<Float>()

    fun getGraphForMetric(metric: MetricType): List<Float> {
        return when (metric) {
            MetricType.POWER -> powerGraph
            MetricType.CADENCE -> cadenceGraph
            MetricType.RESISTANCE -> resistanceGraph
            MetricType.SPEED -> speedGraph
            MetricType.HEART_RATE -> heartRateGraph
        }
    }

    private fun setupGraphData() {
        // Power graph
        viewModelScope.launch(Dispatchers.IO) {
            sensorInterface.power.smoothSensorValue()
                .sample(UiUpdatePeriod)
                .collect(object : FlowCollector<Float> {
                    override suspend fun emit(value: Float) {
                        withContext(Dispatchers.Main) {
                            if (!value.isFinite()) {
                                powerGraph.clear()
                                return@withContext
                            }
                            powerGraph.add(value)
                            if (powerGraph.size > GraphMaxDataPoints) {
                                powerGraph.removeFirst()
                            }
                        }
                    }
                })
        }

        // Cadence graph
        viewModelScope.launch(Dispatchers.IO) {
            sensorInterface.cadence.smoothSensorValue()
                .sample(UiUpdatePeriod)
                .collect(object : FlowCollector<Float> {
                    override suspend fun emit(value: Float) {
                        withContext(Dispatchers.Main) {
                            if (!value.isFinite()) {
                                cadenceGraph.clear()
                                return@withContext
                            }
                            cadenceGraph.add(value)
                            if (cadenceGraph.size > GraphMaxDataPoints) {
                                cadenceGraph.removeFirst()
                            }
                        }
                    }
                })
        }

        // Resistance graph
        viewModelScope.launch(Dispatchers.IO) {
            sensorInterface.resistance.smoothSensorValue()
                .sample(UiUpdatePeriod)
                .collect(object : FlowCollector<Float> {
                    override suspend fun emit(value: Float) {
                        withContext(Dispatchers.Main) {
                            if (!value.isFinite()) {
                                resistanceGraph.clear()
                                return@withContext
                            }
                            resistanceGraph.add(value)
                            if (resistanceGraph.size > GraphMaxDataPoints) {
                                resistanceGraph.removeFirst()
                            }
                        }
                    }
                })
        }

        // Speed graph
        viewModelScope.launch(Dispatchers.IO) {
            sensorInterface.speed.smoothSensorValue()
                .sample(UiUpdatePeriod)
                .collect(object : FlowCollector<Float> {
                    override suspend fun emit(value: Float) {
                        withContext(Dispatchers.Main) {
                            if (!value.isFinite()) {
                                speedGraph.clear()
                                return@withContext
                            }
                            speedGraph.add(value)
                            if (speedGraph.size > GraphMaxDataPoints) {
                                speedGraph.removeFirst()
                            }
                        }
                    }
                })
        }

        // Heart rate graph
        viewModelScope.launch(Dispatchers.IO) {
            HeartRateManager.heartRate
                .map { (it ?: 0).toFloat() }
                .smoothSensorValue()
                .sample(UiUpdatePeriod)
                .collect(object : FlowCollector<Float> {
                    override suspend fun emit(value: Float) {
                        withContext(Dispatchers.Main) {
                            heartRateGraph.add(value)
                            if (heartRateGraph.size > GraphMaxDataPoints) {
                                heartRateGraph.removeFirst()
                            }
                        }
                    }
                })
        }
    }

    private fun setupMaxTracking() {
        viewModelScope.launch(Dispatchers.IO) {
            combine(
                sensorInterface.power,
                sensorInterface.cadence,
                sensorInterface.resistance,
                sensorInterface.speed,
                HeartRateManager.heartRate.map { (it ?: 0).toFloat() }
            ) { power, cadence, resistance, speed, heartRate ->
                floatArrayOf(power, cadence, resistance, speed, heartRate)
            }.collect(object : FlowCollector<FloatArray> {
                override suspend fun emit(value: FloatArray) {
                    withContext(Dispatchers.Main) {
                        updateSessionStats(value[0], value[1], value[2], value[3], value[4])
                    }
                }
            })
        }
    }

    // Happens last to ensure initialization order is correct
    init {
        setupGraphData()
        setupMaxTracking()
        viewModelScope.launch(Dispatchers.IO) {
            deadSensorDetector.deadSensorDetected.collect(object : FlowCollector<Unit> {
                override suspend fun emit(value: Unit) {
                    onDeadSensor()
                }
            })
        }

        viewModelScope.launch(Dispatchers.IO) {
            errorMessage.collect(object : FlowCollector<String?> {
                override suspend fun emit(value: String?) {
                    // Leave minimized state if we're showing an error message
                    if (value != null && mutableIsMinimized.value) {
                        mutableIsMinimized.value = false
                    }
                }
            })
        }
    }
}
