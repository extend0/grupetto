package com.spop.poverlay.sensor.v1new

import android.os.IBinder
import android.os.Parcel
import com.spop.poverlay.sensor.BikeData
import timber.log.Timber

class V1NewCombinedSensor(
    private val binder: IBinder,
    private val onReading: (BikeData) -> Unit,
    private val onError: () -> Unit,
) {
    @Volatile private var isRegistered = false
    private val callbackBinder = createCallback()

    companion object {
        private const val INTERFACE_DESCRIPTOR = "com.onepeloton.affernetservice.IV1Interface"
        private const val CALLBACK_DESCRIPTOR = "com.onepeloton.affernetservice.IV1Callback"
        private const val REGISTER_CODE = 1
        private const val UNREGISTER_CODE = 2
    }

    @Synchronized
    fun start() {
        if (isRegistered) {
            Timber.w("V1NewCombinedSensor already started")
            return
        }
        try {
            isRegistered = true
            registerCallback()
            Timber.d("V1NewCombinedSensor started successfully")
        } catch (e: Exception) {
            isRegistered = false
            runCatching { unregisterCallback() }
            throw e
        }
    }

    @Synchronized
    fun stop() {
        if (!isRegistered) return
        isRegistered = false
        try {
            unregisterCallback()
            Timber.d("V1NewCombinedSensor stopped successfully")
        } catch (e: Exception) {
            Timber.e(e, "Failed to stop V1NewCombinedSensor")
        }
    }

    private fun registerCallback() {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(INTERFACE_DESCRIPTOR)
            data.writeStrongBinder(callbackBinder)
            data.writeString("Grupetto")
            
            Timber.d("Registering callback with interface: $INTERFACE_DESCRIPTOR")
            val success = binder.transact(REGISTER_CODE, data, reply, 0)
            if (success) {
                reply.readException()
                Timber.i("Successfully registered callback")
            } else {
                throw Exception("Failed to register callback")
            }
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun unregisterCallback() {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(INTERFACE_DESCRIPTOR)
            data.writeStrongBinder(callbackBinder)
            data.writeString("Grupetto")
            
            val success = binder.transact(UNREGISTER_CODE, data, reply, 0)
            if (success) {
                reply.readException()
                Timber.d("Successfully unregistered callback")
            }
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun createCallback() = object : android.os.Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (!isRegistered) return true
            return when (code) {
                1 -> { // onSensorDataChange
                    try {
                        data.enforceInterface(CALLBACK_DESCRIPTOR)
                        
                        val hasData = data.readInt()
                        
                        val bikeData = if (hasData != 0) {
                            BikeData.CREATOR.createFromParcel(data)
                        } else {
                            null
                        }
                        
                        if (bikeData != null) {
                            onReading(bikeData)
                        }
                        true
                    } catch (e: Exception) {
                        onError()
                        Timber.e(e, "Error processing sensor data")
                        false
                    }
                }
                2 -> { // onSensorError
                    try {
                        data.enforceInterface(CALLBACK_DESCRIPTOR)
                        val errorCode = data.readLong()
                        onError()
                        Timber.w("Sensor error: $errorCode")
                        true
                    } catch (e: Exception) {
                        Timber.e(e, "Error processing sensor error")
                        false
                    }
                }
                3 -> { // onCalibrationStatus
                    try {
                        data.enforceInterface(CALLBACK_DESCRIPTOR)
                        val status = data.readInt()
                        val success = data.readInt() != 0
                        val errorCode = data.readLong()
                        Timber.d("Calibration status: status=$status success=$success error=$errorCode")
                        true
                    } catch (e: Exception) {
                        Timber.e(e, "Error processing calibration status")
                        false
                    }
                }
                else -> {
                    super.onTransact(code, data, reply, flags)
                }
            }
        }
    }
}
