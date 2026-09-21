package com.spop.poverlay.sensor.heartrate

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.SharedPreferences
import android.os.ParcelUuid
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class HeartRateDevice(
    val address: String,
    val name: String?,
)

/**
 * Scan, GATT, and retry ownership is serialized on this object's monitor. Android callbacks
 * can arrive after stop/close, so each callback must still own the active scan or GATT.
 */
object HeartRateManager {
    private const val PrefsName = "heart_rate"
    private const val PrefSavedDevices = "hr_saved_devices"
    private const val PrefSelectedDevice = "hr_selected_device"
    private const val PrefNamePrefix = "hr_name_"
    private const val PrefZone12 = "hr_zone_12"
    private const val PrefZone23 = "hr_zone_23"
    private const val PrefZone34 = "hr_zone_34"
    private const val PrefZone45 = "hr_zone_45"
    private const val PrefMatchByName = "hr_match_by_name"

    const val OwnerSettings = "settings"
    const val OwnerOverlay = "overlay"

    private const val ReconnectDelayMs = 3_000L
    private const val AutoReconnectScanMs = 10_000L
    private const val ConnectionTimeoutMs = 15_000L
    private const val StaleHeartRateTimeoutMs = 12_000L

    private val HR_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    private val HR_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    private val CCC_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val _heartRate = MutableStateFlow<Int?>(null)
    val heartRate: StateFlow<Int?> = _heartRate

    /**
     * Milliseconds since the last real sample arrived, or [Long.MAX_VALUE] if there has been
     * none. Read directly rather than derived from [heartRate], which is a StateFlow and so
     * conflates a steady bpm into no emissions at all - a collector timing its own updates
     * would read a rock-steady 132 as stale.
     */
    val heartRateAgeMs: Long
        get() = lastHeartRateAtMs.let {
            if (it == 0L) Long.MAX_VALUE else System.currentTimeMillis() - it
        }

    private val _connectedDevice = MutableStateFlow<HeartRateDevice?>(null)
    val connectedDevice: StateFlow<HeartRateDevice?> = _connectedDevice

    private val _discoveredDevices = MutableStateFlow<List<HeartRateDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<HeartRateDevice>> = _discoveredDevices

    private val _savedDevices = MutableStateFlow<List<HeartRateDevice>>(emptyList())
    val savedDevices: StateFlow<List<HeartRateDevice>> = _savedDevices

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    private val _zone12 = MutableStateFlow<Int?>(null)
    private val _zone23 = MutableStateFlow<Int?>(null)
    private val _zone34 = MutableStateFlow<Int?>(null)
    private val _zone45 = MutableStateFlow<Int?>(null)
    val zone12: StateFlow<Int?> = _zone12
    val zone23: StateFlow<Int?> = _zone23
    val zone34: StateFlow<Int?> = _zone34
    val zone45: StateFlow<Int?> = _zone45
    private val _heartRateZones = MutableStateFlow<List<Int>?>(null)
    val heartRateZones: StateFlow<List<Int>?> = _heartRateZones

    private val _matchByName = MutableStateFlow(false)
    val matchByName: StateFlow<Boolean> = _matchByName

    @Volatile
    private var bluetoothGatt: BluetoothGatt? = null

    @Volatile
    private var discoveryCallback: ScanCallback? = null
    private var discoveryScanner: BluetoothLeScanner? = null
    private var connectionTimeoutJob: kotlinx.coroutines.Job? = null
    private var reconnectGeneration = 0L

    @Volatile
    private var appContext: Context? = null

    private var prefs: SharedPreferences? = null
    private var selectedAddress: String? = null
    private val stopped = AtomicBoolean(true)
    private var autoReconnectJob: kotlinx.coroutines.Job? = null
    private var manageSessionJob: kotlinx.coroutines.Job? = null
    /**
     * Owners that want the staleness watchdog running. Reference counted because the settings
     * dialog and the overlay service both ask for it independently - before this, closing the
     * settings dialog silently switched off the watchdog the overlay was relying on, leaving a
     * stale heart rate on screen (and, now, feeding zone enforcement) forever.
     */
    private val managingOwners = mutableSetOf<String>()
    @Volatile
    private var manualDisconnectRequested = false

    @Volatile
    private var lastHeartRateAtMs: Long = 0L

    @Volatile
    private var lastConnectedAtMs: Long = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Synchronized
    fun start(context: Context) {
        try {
            if (!stopped.get()) return
            appContext = context.applicationContext
            prefs = appContext?.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
            loadHeartRateZones()
            _matchByName.value = prefs?.getBoolean(PrefMatchByName, false) ?: false
            manualDisconnectRequested = false
            stopped.set(false)
            loadSavedDevices()
            selectedAddress = prefs?.getString(PrefSelectedDevice, null)
            if (_savedDevices.value.isNotEmpty()) {
                startAutoReconnectScan()
            } else {
                selectedAddress?.let { connectToAddress(it) }
            }
        } catch (ex: Exception) {
            Timber.w(ex, "HeartRateManager start failed")
        }
    }

    @Synchronized
    fun stop() {
        stopped.set(true)
        cancelReconnect()
        manageSessionJob?.cancel()
        manageSessionJob = null
        managingOwners.clear()
        stopDiscovery()
        closeConnection()
    }

    private fun readIntOrNull(key: String): Int? {
        val sharedPrefs = prefs ?: return null
        return if (sharedPrefs.contains(key)) sharedPrefs.getInt(key, 0) else null
    }

    private fun loadHeartRateZones() {
        _zone12.value = readIntOrNull(PrefZone12)
        _zone23.value = readIntOrNull(PrefZone23)
        _zone34.value = readIntOrNull(PrefZone34)
        _zone45.value = readIntOrNull(PrefZone45)
        updateHeartRateZones()
    }

    fun setHeartRateZones(zone12: Int?, zone23: Int?, zone34: Int?, zone45: Int?) {
        prefs?.edit {
            if (zone12 != null) putInt(PrefZone12, zone12) else remove(PrefZone12)
            if (zone23 != null) putInt(PrefZone23, zone23) else remove(PrefZone23)
            if (zone34 != null) putInt(PrefZone34, zone34) else remove(PrefZone34)
            if (zone45 != null) putInt(PrefZone45, zone45) else remove(PrefZone45)
        }
        _zone12.value = zone12
        _zone23.value = zone23
        _zone34.value = zone34
        _zone45.value = zone45
        updateHeartRateZones()
    }

    fun setMatchByName(enabled: Boolean) {
        _matchByName.value = enabled
        prefs?.edit { putBoolean(PrefMatchByName, enabled) }
    }

    private fun updateHeartRateZones() {
        val z12 = _zone12.value
        val z23 = _zone23.value
        val z34 = _zone34.value
        val z45 = _zone45.value
        _heartRateZones.value = if (
            z12 != null && z23 != null && z34 != null && z45 != null &&
                z12 > 0 && z12 < z23 && z23 < z34 && z34 < z45
        ) {
            listOf(z12, z23, z34, z45)
        } else {
            null
        }
    }

    @Synchronized
    fun startDiscovery() {
        if (stopped.get() || discoveryCallback != null) return
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        val scanner = adapter.bluetoothLeScanner ?: return
        if (_isScanning.value) return

        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(HR_SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                synchronized<Unit>(HeartRateManager) {
                    if (stopped.get() || discoveryCallback !== this) return
                    val device = result.device ?: return
                    val hasHrService = result.scanRecord?.serviceUuids?.any { it.uuid == HR_SERVICE } == true
                    if (!hasHrService) return
                    if (maybeAutoConnectSaved(device)) return
                    addDiscoveredDevice(device)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                synchronized<Unit>(HeartRateManager) {
                    if (discoveryCallback !== this) return
                    discoveryCallback = null
                    discoveryScanner = null
                    _isScanning.value = false
                    Timber.w("HR scan failed: %s", errorCode)
                }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(0, it) }
            }
        }

        discoveryCallback = callback
        discoveryScanner = scanner
        _isScanning.value = true
        try {
            scanner.startScan(listOf(filter), settings, callback)
        } catch (sec: SecurityException) {
            Timber.w(sec, "Failed to start HR scan")
            discoveryCallback = null
            discoveryScanner = null
            _isScanning.value = false
        }
    }

    @Synchronized
    fun stopDiscovery() {
        val callback = discoveryCallback
        val scanner = discoveryScanner
        // Invalidate first: already queued results must not start another connection.
        discoveryCallback = null
        discoveryScanner = null
        _isScanning.value = false
        if (callback != null) {
            try { scanner?.stopScan(callback) } catch (_: SecurityException) {} catch (_: Exception) {}
        }
    }

    @Synchronized
    fun setManaging(active: Boolean, owner: String = OwnerSettings) {
        if (active) managingOwners.add(owner) else managingOwners.remove(owner)
        if (managingOwners.isEmpty()) {
            manageSessionJob?.cancel()
            manageSessionJob = null
            return
        }
        if (manageSessionJob?.isActive == true) return
        manageSessionJob = scope.launch {
            while (true) {
                synchronized<Unit>(HeartRateManager) {
                    if (managingOwners.isEmpty() || stopped.get()) return@launch
                    val lastSignalAtMs = maxOf(lastHeartRateAtMs, lastConnectedAtMs)
                    if (_connectedDevice.value != null && lastSignalAtMs > 0 &&
                        System.currentTimeMillis() - lastSignalAtMs > StaleHeartRateTimeoutMs) {
                        closeConnection()
                        scheduleReconnect()
                    }
                }
                delay(1_000L)
            }
        }
    }

    /**
     * Debug-only heart rate injection, so the zone enforcement state machine can be exercised
     * without wearing a strap and riding for 45 minutes. Callers must gate on BuildConfig.DEBUG.
     */
    @Synchronized
    fun injectDebugHeartRate(bpm: Int?) {
        _heartRate.value = bpm
        lastHeartRateAtMs = if (bpm == null) 0L else System.currentTimeMillis()
        Timber.i("Debug heart rate injected: %s", bpm)
    }

    @Synchronized
    fun connectTo(device: HeartRateDevice) {
        if (stopped.get()) return
        manualDisconnectRequested = false
        cancelReconnect()
        stopDiscovery()
        saveDevice(device)
        selectedAddress = device.address
        connectToAddress(device.address)
    }

    @Synchronized
    fun disconnectCurrent() {
        manualDisconnectRequested = true
        cancelReconnect()
        stopDiscovery()
        selectedAddress = null
        closeConnection()
    }

    @Synchronized
    fun forgetDevice(address: String) {
        val p = prefs ?: return
        val saved = p.getStringSet(PrefSavedDevices, emptySet()).orEmpty().toMutableSet()
        saved.remove(address)
        p.edit {
            putStringSet(PrefSavedDevices, saved)
            remove(PrefNamePrefix + address)
            if (selectedAddress == address) remove(PrefSelectedDevice)
        }
        if (selectedAddress == address) {
            disconnectCurrent()
        }
        loadSavedDevices()
        pruneDiscovered()
    }

    private fun saveDevice(device: HeartRateDevice) {
        val p = prefs ?: return
        val saved = p.getStringSet(PrefSavedDevices, emptySet()).orEmpty().toMutableSet()
        saved.add(device.address)
        p.edit {
            putStringSet(PrefSavedDevices, saved)
            putString(PrefSelectedDevice, device.address)
            if (!device.name.isNullOrBlank()) putString(PrefNamePrefix + device.address, device.name)
        }
        loadSavedDevices()
        pruneDiscovered()
    }

    private fun loadSavedDevices() {
        val p = prefs ?: return
        val saved = p.getStringSet(PrefSavedDevices, emptySet()).orEmpty()
        _savedDevices.value = saved.map { HeartRateDevice(it, p.getString(PrefNamePrefix + it, null)) }
            .sortedBy { it.name ?: it.address }
    }

    private fun deviceName(device: BluetoothDevice): String? = try {
        device.name
    } catch (sec: SecurityException) {
        Timber.w(sec, "Cannot read HR device name")
        null
    }

    private fun addDiscoveredDevice(device: BluetoothDevice) {
        val address = device.address ?: return
        if (address == selectedAddress) return
        if (_savedDevices.value.any { it.address == address }) return
        val current = _discoveredDevices.value.toMutableList()
        if (current.none { it.address == address }) {
            current.add(HeartRateDevice(address, deviceName(device)))
            _discoveredDevices.value = current.sortedBy { it.name ?: it.address }
        }
    }

    private fun maybeAutoConnectSaved(device: BluetoothDevice): Boolean {
        if (manualDisconnectRequested) return false
        if (bluetoothGatt != null) return false
        val address = device.address ?: return false

        // Exact MAC match
        if (_savedDevices.value.any { it.address == address }) {
            selectedAddress = address
            connectToAddress(address)
            stopDiscovery()
            return true
        }

        // Name-only match (handles randomized/changed MAC addresses)
        if (_matchByName.value) {
            val deviceName = deviceName(device)?.takeIf { it.isNotBlank() } ?: return false
            val matched = _savedDevices.value.firstOrNull {
                !it.name.isNullOrBlank() && it.name == deviceName
            } ?: return false
            updateSavedDeviceAddress(oldAddress = matched.address, newAddress = address, name = deviceName)
            selectedAddress = address
            connectToAddress(address)
            stopDiscovery()
            return true
        }

        return false
    }

    private fun updateSavedDeviceAddress(oldAddress: String, newAddress: String, name: String) {
        val p = prefs ?: return
        val saved = p.getStringSet(PrefSavedDevices, emptySet()).orEmpty().toMutableSet()
        saved.remove(oldAddress)
        saved.add(newAddress)
        val wasSelected = p.getString(PrefSelectedDevice, null) == oldAddress
        p.edit {
            putStringSet(PrefSavedDevices, saved)
            remove(PrefNamePrefix + oldAddress)
            putString(PrefNamePrefix + newAddress, name)
            if (wasSelected) putString(PrefSelectedDevice, newAddress)
        }
        Timber.i("HR device name-matched '%s': updated address %s → %s", name, oldAddress, newAddress)
        loadSavedDevices()
        pruneDiscovered()
    }

    private fun pruneDiscovered() {
        val savedAddresses = _savedDevices.value.map { it.address }.toSet()
        _discoveredDevices.value = _discoveredDevices.value.filter {
            it.address != selectedAddress && !savedAddresses.contains(it.address)
        }
    }

    private fun connectToAddress(address: String) {
        if (stopped.get() || manualDisconnectRequested) return
        val context = appContext ?: return
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        try {
            connect(context, adapter.getRemoteDevice(address))
        } catch (ex: IllegalArgumentException) {
            Timber.w(ex, "Invalid HR device address: %s", address)
        }
    }

    private fun connect(context: Context, device: BluetoothDevice) {
        if (bluetoothGatt?.device?.address == device.address) return
        cancelReconnect()
        stopDiscovery()
        closeConnection()
        Timber.i("HR connecting")
        bluetoothGatt = try {
            device.connectGatt(context.applicationContext, false, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    synchronized<Unit>(HeartRateManager) {
                        if (stopped.get() || bluetoothGatt !== gatt) return
                        Timber.i("HR connection state=%s status=%s", newState, status)
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            closeConnection()
                            if (!manualDisconnectRequested) {
                                scheduleReconnect()
                            }
                            return
                        }
                        when (newState) {
                            BluetoothProfile.STATE_CONNECTED -> {
                                connectionTimeoutJob?.cancel()
                                connectionTimeoutJob = null
                                lastConnectedAtMs = System.currentTimeMillis()
                                val name = deviceName(device) ?: prefs?.getString(PrefNamePrefix + device.address, null)
                                _connectedDevice.value = HeartRateDevice(device.address, name)
                                try {
                                    if (!gatt.discoverServices()) {
                                        closeConnection()
                                        scheduleReconnect()
                                    }
                                } catch (sec: SecurityException) {
                                    Timber.w(sec, "HR service discovery permission denied")
                                    closeConnection()
                                }
                            }
                            BluetoothProfile.STATE_DISCONNECTED -> {
                                closeConnection()
                                if (!manualDisconnectRequested) {
                                    scheduleReconnect()
                                }
                            }
                        }
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    synchronized<Unit>(HeartRateManager) {
                        if (stopped.get() || bluetoothGatt !== gatt) return
                        if (status != BluetoothGatt.GATT_SUCCESS) return
                        val service = gatt.getService(HR_SERVICE) ?: return
                        val characteristic = service.getCharacteristic(HR_MEASUREMENT) ?: return
                        try {
                            if (!gatt.setCharacteristicNotification(characteristic, true)) return
                            val desc = characteristic.getDescriptor(CCC_UUID) ?: return
                            desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(desc)
                        } catch (sec: SecurityException) {
                            Timber.w(sec, "HR notification permission denied")
                            closeConnection()
                        }
                    }
                }

                override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                    synchronized<Unit>(HeartRateManager) {
                        if (stopped.get() || bluetoothGatt !== gatt) return
                        if (characteristic.uuid != HR_MEASUREMENT) return
                        val data = characteristic.value ?: return
                        if (data.size < 2) return
                        val flags = data[0].toInt()
                        val format16 = flags and 0x01 != 0
                        val bpm = if (format16) {
                            if (data.size >= 3) ((data[2].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF) else null
                        } else {
                            data[1].toInt() and 0xFF
                        }
                        if (bpm != null && bpm > 0) {
                            _heartRate.value = bpm
                            lastHeartRateAtMs = System.currentTimeMillis()
                        }
                    }
                }
            })
        } catch (sec: SecurityException) {
            Timber.w(sec, "HR connection permission denied")
            return
        }
        val pending = bluetoothGatt
        if (pending == null) {
            scheduleReconnect()
        } else {
            connectionTimeoutJob = scope.launch {
                delay(ConnectionTimeoutMs)
                synchronized<Unit>(HeartRateManager) {
                    if (bluetoothGatt === pending && _connectedDevice.value == null) {
                        Timber.w("HR connection timed out")
                        closeConnection()
                        scheduleReconnect()
                    }
                }
            }
        }
    }

    // All lifecycle operations and callbacks hold the HeartRateManager monitor.
    private fun closeConnection() {
        val previous = bluetoothGatt
        bluetoothGatt = null
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
        _heartRate.value = null
        _connectedDevice.value = null
        lastConnectedAtMs = 0L
        lastHeartRateAtMs = 0L
        try { previous?.disconnect() } catch (_: SecurityException) {} catch (_: Exception) {}
        try { previous?.close() } catch (_: SecurityException) {} catch (_: Exception) {}
    }

    private fun cancelReconnect() {
        reconnectGeneration++
        autoReconnectJob?.cancel()
        autoReconnectJob = null
    }

    private fun scheduleReconnect() {
        if (stopped.get() || manualDisconnectRequested || bluetoothGatt != null) return
        cancelReconnect()
        val generation = reconnectGeneration
        autoReconnectJob = scope.launch {
            delay(ReconnectDelayMs)
            synchronized<Unit>(HeartRateManager) {
                if (generation != reconnectGeneration || stopped.get() || manualDisconnectRequested) return@launch
                if (_savedDevices.value.isNotEmpty()) startAutoReconnectScan()
                else selectedAddress?.let { connectToAddress(it) }
            }
        }
    }

    private fun startAutoReconnectScan() {
        if (stopped.get() || manualDisconnectRequested || bluetoothGatt != null) return
        if (discoveryCallback != null) return
        cancelReconnect()
        startDiscovery()
        val callback = discoveryCallback ?: return
        val generation = reconnectGeneration
        autoReconnectJob = scope.launch {
            delay(AutoReconnectScanMs)
            synchronized<Unit>(HeartRateManager) {
                if (generation == reconnectGeneration && discoveryCallback === callback) {
                    stopDiscovery()
                    scheduleReconnect()
                }
            }
        }
    }
}
