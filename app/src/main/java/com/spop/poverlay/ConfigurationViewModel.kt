package com.spop.poverlay

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.spop.poverlay.overlay.OverlayService
import com.spop.poverlay.releases.Release
import com.spop.poverlay.releases.ReleaseChecker
import com.spop.poverlay.sensor.heartrate.HeartRateDevice
import com.spop.poverlay.media.MediaPenaltyController
import com.spop.poverlay.sensor.heartrate.HeartRateManager
import com.spop.poverlay.zone.ZoneRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

class ConfigurationViewModel(
    application: Application,
    private val configurationRepository: ConfigurationRepository,
    private val releaseChecker: ReleaseChecker,
) : AndroidViewModel(application) {
    companion object {
        private const val TestPauseHoldMs = 1_500L
    }

    val finishActivity = MutableLiveData<Unit>()
    val requestOverlayPermission = MutableLiveData<Unit>()
    val requestRestart = MutableLiveData<Unit>()
    val requestQuit = MutableLiveData<Unit>()
    val requestBluetoothPermissions = MutableLiveData<Array<String>>()
    val requestIgnoreBatteryOptimizations = MutableLiveData<Unit>()
    val requestNotificationListenerAccess = MutableLiveData<Unit>()
    val showPermissionInfo = mutableStateOf(false)
    val infoPopup = MutableLiveData<String>()

    val hrConnectedDevice = HeartRateManager.connectedDevice
    val hrDiscoveredDevices = HeartRateManager.discoveredDevices
    val hrSavedDevices = HeartRateManager.savedDevices
    val hrIsScanning = HeartRateManager.isScanning
    val hrMatchByName = HeartRateManager.matchByName
    val hrZones = HeartRateManager.heartRateZones
    val zoneGoalSnapshot = ZoneRuntime.snapshot
    val isOverlayRunning: StateFlow<Boolean> = OverlayService.isRunning

    var latestRelease = mutableStateOf<Release?>(null)

    val showTimerWhenMinimized
        get() = configurationRepository.showTimerWhenMinimized

    val bleTxEnabled
        get() = configurationRepository.bleTxEnabled

    val dirConEnabled
        get() = configurationRepository.dirConEnabled

    val bleFtmsDeviceName
        get() = configurationRepository.bleFtmsDeviceName

    val zoneEnforcementEnabled
        get() = configurationRepository.zoneEnforcementEnabled

    val zonePenaltyEnabled
        get() = configurationRepository.zonePenaltyEnabled

    val zoneTargetZone
        get() = configurationRepository.zoneTargetZone

    val zoneGoalMinutes
        get() = configurationRepository.zoneGoalMinutes

    val zoneGraceSeconds
        get() = configurationRepository.zoneGraceSeconds

    private val bleServer = (application as GrupettoApplication).bleServer
    private val mediaPenaltyController by lazy { MediaPenaltyController(application) }
    private var batteryOptimizationPromptShownThisSession = false

    init {
        updatePermissionState()
        HeartRateManager.start(getApplication())
        syncOutboundTransports()
    }

    private fun updatePermissionState() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            showPermissionInfo.value = !Settings.canDrawOverlays(getApplication())
        } else {
            showPermissionInfo.value = false
        }
    }

    fun onShowTimerWhenMinimizedClicked(isChecked: Boolean) {
        configurationRepository.setShowTimerWhenMinimized(isChecked)
    }

    fun onBleTxEnabledClicked(isChecked: Boolean) {
        configurationRepository.setBleTxEnabled(isChecked)
        if (isChecked) {
            if (hasBluetoothPermissions()) {
                syncOutboundTransports()
                requestBatteryOptimizationExemptionIfNeeded()
            } else {
                syncOutboundTransports()
                requestBluetoothPermissions.value = getRequiredBluetoothPermissions()
            }
        } else {
            syncOutboundTransports()
            batteryOptimizationPromptShownThisSession = false
        }
    }

    fun onDirConEnabledClicked(isChecked: Boolean) {
        configurationRepository.setDirConEnabled(isChecked)
        syncOutboundTransports()
        requestBatteryOptimizationExemptionIfNeeded()
    }

    fun onZoneEnforcementEnabledClicked(isChecked: Boolean) {
        configurationRepository.setZoneEnforcementEnabled(isChecked)
    }

    fun onZonePenaltyEnabledClicked(isChecked: Boolean) {
        configurationRepository.setZonePenaltyEnabled(isChecked)
    }

    fun onZoneTargetZoneSelected(zone: Int) {
        configurationRepository.setZoneTargetZone(zone)
    }

    fun onZoneGoalMinutesChanged(minutes: Int) {
        configurationRepository.setZoneGoalMinutes(minutes)
    }

    fun onZoneGraceSecondsChanged(seconds: Int) {
        configurationRepository.setZoneGraceSeconds(seconds)
    }

    fun onGrantNotificationAccessClicked() {
        requestNotificationListenerAccess.value = Unit
    }

    /**
     * Pauses whatever is playing, holds it briefly, then hands it back.
     *
     * The point is to let the rider find out which mechanism works on their tablet *before*
     * trusting it mid-ride, rather than discovering the penalty does nothing at minute 30.
     */
    fun onTestMediaPauseClicked() {
        viewModelScope.launch(Dispatchers.IO) {
            val via = mediaPenaltyController.pause()
            if (via == null) {
                infoPopup.postValue(
                    "Nothing is playing, or no media control is available. " +
                        "Start a video first, then try again."
                )
                return@launch
            }
            delay(TestPauseHoldMs)
            val resumed = mediaPenaltyController.resume()
            infoPopup.postValue(
                if (resumed) "Paused and resumed using $via." else "Paused using $via, but resuming failed."
            )
        }
    }

    fun mediaCapabilities() = mediaPenaltyController.capabilities()

    fun onBluetoothPermissionsResult(granted: Boolean) {
        if (granted) {
            syncOutboundTransports()
            requestBatteryOptimizationExemptionIfNeeded()
            infoPopup.postValue("Bluetooth permissions granted. BLE service started.")
        } else {
            configurationRepository.setBleTxEnabled(false)
            syncOutboundTransports()
            infoPopup.postValue("Bluetooth permissions are required for BLE functionality.")
        }
    }

    private fun syncOutboundTransports() {
        val shouldRunBle = bleTxEnabled.value && hasBluetoothPermissions()
        if (shouldRunBle) {
            bleServer.setDirConTransportEnabled(dirConEnabled.value)
            bleServer.start()
        } else {
            bleServer.stop()
            bleServer.setDirConTransportEnabled(dirConEnabled.value)
        }
    }

    private fun getRequiredBluetoothPermissions(): Array<String> {
        val permissions = mutableListOf<String>()

        // Always required permissions
        permissions.add(android.Manifest.permission.BLUETOOTH)
        permissions.add(android.Manifest.permission.BLUETOOTH_ADMIN)
        permissions.add(android.Manifest.permission.ACCESS_FINE_LOCATION)

        // Android 12+ permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(android.Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(android.Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(android.Manifest.permission.BLUETOOTH_SCAN)
        }

        return permissions.toTypedArray()
    }

    private fun hasBluetoothPermissions(): Boolean {
        val context = getApplication<Application>()

        val bluetoothPermission = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.BLUETOOTH
        ) == PackageManager.PERMISSION_GRANTED

        val bluetoothAdminPermission = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.BLUETOOTH_ADMIN
        ) == PackageManager.PERMISSION_GRANTED

        val locationPermission = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        // Check for Android 12+ permissions
        var bluetoothAdvertisePermission = true
        var bluetoothConnectPermission = true
        var bluetoothScanPermission = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            bluetoothAdvertisePermission = ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.BLUETOOTH_ADVERTISE
            ) == PackageManager.PERMISSION_GRANTED

            bluetoothConnectPermission = ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED

            bluetoothScanPermission = ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        }

        return bluetoothPermission && bluetoothAdminPermission && locationPermission &&
                bluetoothAdvertisePermission && bluetoothConnectPermission && bluetoothScanPermission
    }

    fun onStartServiceClicked() {
        Timber.i("Starting service")
        ContextCompat.startForegroundService(
            getApplication(),
            Intent(getApplication(), OverlayService::class.java)
        )
        finishActivity.value = Unit
    }

    fun onGrantPermissionClicked() {
        requestOverlayPermission.value = Unit
    }

    fun onRestartClicked() {
        requestRestart.value = Unit
    }

    fun onQuitClicked() {
        requestQuit.value = Unit
    }

    fun onClickedRelease(release: Release) {
        val browserIntent = Intent(Intent.ACTION_VIEW, release.url)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        getApplication<Application>().startActivity(browserIntent)
    }

    fun startHeartRateDiscovery() {
        HeartRateManager.startDiscovery()
    }

    fun stopHeartRateDiscovery() {
        HeartRateManager.stopDiscovery()
    }

    fun connectHeartRateDevice(device: HeartRateDevice) {
        HeartRateManager.connectTo(device)
    }

    fun forgetHeartRateDevice(address: String) {
        HeartRateManager.forgetDevice(address)
    }

    fun disconnectHeartRateDevice() {
        HeartRateManager.disconnectCurrent()
    }

    fun setHrMatchByName(enabled: Boolean) {
        HeartRateManager.setMatchByName(enabled)
    }

    fun onResume() {
        updatePermissionState()
        viewModelScope.launch(Dispatchers.IO) {
            releaseChecker.getLatestRelease()
                .onSuccess { release ->
                    latestRelease.value = release
                }
                .onFailure {
                    Timber.e(it, "failed to fetch release info")
                }
        }
    }

    fun onAppResumed() {
        if (isOverlayRunning.value) {
            ContextCompat.startForegroundService(
                getApplication(),
                Intent(getApplication(), OverlayService::class.java).apply {
                    action = OverlayService.ActionMinimizeOverlay
                }
            )
        }
        syncOutboundTransports()
        if (bleTxEnabled.value && !hasBluetoothPermissions()) {
            val permissions = getRequiredBluetoothPermissions()
            requestBluetoothPermissions.value = permissions
        } else if ((bleTxEnabled.value || dirConEnabled.value) &&
            (!bleTxEnabled.value || hasBluetoothPermissions())
        ) {
            requestBatteryOptimizationExemptionIfNeeded()
        }
    }

    fun onAppStopped() {
        if (isOverlayRunning.value) {
            ContextCompat.startForegroundService(
                getApplication(),
                Intent(getApplication(), OverlayService::class.java).apply {
                    action = OverlayService.ActionRestoreOverlay
                }
            )
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true
        }

        val context = getApplication<Application>()
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
    }

    private fun requestBatteryOptimizationExemptionIfNeeded() {
        if ((!bleTxEnabled.value && !dirConEnabled.value) || batteryOptimizationPromptShownThisSession) {
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !isIgnoringBatteryOptimizations()) {
            batteryOptimizationPromptShownThisSession = true
            requestIgnoreBatteryOptimizations.postValue(Unit)
        }
    }

    fun onBatteryOptimizationRequestCompleted() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return
        }

        val prompt = if (isIgnoringBatteryOptimizations()) {
            "Battery optimization disabled for Grupetto. BLE reliability should improve while idle."
        } else {
            "Battery optimization is still enabled. If BLE drops after idle, set Grupetto battery to Unrestricted."
        }
        infoPopup.postValue(prompt)
    }

    fun onOverlayPermissionRequestCompleted(wasGranted: Boolean) {
        updatePermissionState()
        val prompt = if (wasGranted) {
            "Permission granted, click 'Start Overlay' to get started"
        } else {
            "Without this permission the app cannot function"
        }
        infoPopup.postValue(prompt)
    }

}
