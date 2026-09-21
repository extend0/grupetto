package com.spop.poverlay

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import com.spop.poverlay.ble.BleServer
import com.spop.poverlay.sensor.interfaces.DummySensorInterface
import com.spop.poverlay.sensor.interfaces.PelotonBikePlusSensorInterface
import com.spop.poverlay.sensor.interfaces.PelotonBikeSensorInterfaceV1New
import com.spop.poverlay.sensor.interfaces.SensorInterface
import com.spop.poverlay.util.IsBikePlus
import com.spop.poverlay.util.IsG700CrossTrainer
import com.spop.poverlay.util.IsRunningOnPeloton
import timber.log.Timber
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filterNotNull

class GrupettoApplication : Application() {
    val workoutScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
    lateinit var workouts: com.spop.poverlay.workout.WorkoutRepository
    lateinit var workoutSettings: com.spop.poverlay.workout.WorkoutSettings
    lateinit var strava: com.spop.poverlay.strava.StravaAuthManager
    lateinit var recorder: com.spop.poverlay.workout.WorkoutRecorder
    lateinit var bleServer: BleServer
        private set

    override fun onCreate() {
        super.onCreate()
        workouts = com.spop.poverlay.workout.WorkoutRepository(this)
        workoutSettings = com.spop.poverlay.workout.WorkoutSettings(this)
        strava = com.spop.poverlay.strava.StravaAuthManager(this, workoutScope)
        recorder = com.spop.poverlay.workout.WorkoutRecorder(this, workouts, workoutSettings, strava, workoutScope)
        workoutScope.launch {
            strava.athleteId.filterNotNull().collect { athlete ->
                workouts.dao.reconnectable(athlete).forEach { workout ->
                    if (workout.status == com.spop.poverlay.workout.WorkoutStatus.AUTH_REQUIRED) {
                        workouts.dao.update(workout.copy(status = if (workout.uploadId == null)
                            com.spop.poverlay.workout.WorkoutStatus.QUEUED else com.spop.poverlay.workout.WorkoutStatus.PROCESSING, error = null))
                    }
                    com.spop.poverlay.strava.StravaUploadWorker.enqueue(this@GrupettoApplication, workout.id)
                }
            }
        }
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val sensorInterface = createSensorInterface()
        bleServer = BleServer(this, bluetoothManager, sensorInterface)
    }

    private fun createSensorInterface(): SensorInterface {
        return if (IsRunningOnPeloton) {
            if (IsG700CrossTrainer || IsBikePlus) {
                PelotonBikePlusSensorInterface(this)
            } else {
                PelotonBikeSensorInterfaceV1New(this)
            }
        } else {
            DummySensorInterface()
        }
    }
}
