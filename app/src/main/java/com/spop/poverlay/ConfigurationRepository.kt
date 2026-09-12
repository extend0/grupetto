package com.spop.poverlay

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.spop.poverlay.zone.EnforcementConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

class ConfigurationRepository(context: Context, lifecycleOwner: LifecycleOwner) : AutoCloseable {

    enum class Preferences(val key: String) {
        ShowTimerWhenMinimized("showTimerWhenMinimized"),
        BleTxEnabled("bleTxEnabled"),
        DirConEnabled("dirConEnabled"),
        BleFtmsDeviceName("bleFtmsDeviceName"),
        SerialNumber("serialNumber"),
        ZoneEnforcementEnabled("zoneEnforcementEnabled"),
        ZonePenaltyEnabled("zonePenaltyEnabled"),
        ZoneTargetZone("zoneTargetZone"),
        ZoneGoalMinutes("zoneGoalMinutes"),
        ZoneGraceSeconds("zoneGraceSeconds")
    }

    companion object {
        const val SharedPrefsName = "configuration"

        const val DefaultTargetZone = 2
        const val MinTargetZone = 1
        const val MaxTargetZone = 5
        const val DefaultGoalMinutes = 45
        const val MinGoalMinutes = 1
        const val MaxGoalMinutes = 600
        const val DefaultGraceSeconds = 30
        const val MinGraceSeconds = 5
        const val MaxGraceSeconds = 120
        // This workaround is required since SharedPreferences
        // only stores weak references to objects
        val SharedPreferenceListeners =
            mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()
    }

    private val mutableShowTimerWhenMinimized = MutableStateFlow(true)
    private val mutableBleTxEnabled = MutableStateFlow(true)
    private val mutableDirConEnabled = MutableStateFlow(true)
    private val mutableBleFtmsDeviceName = MutableStateFlow("Grupetto FTMS")
    private val mutableSerialNumber = MutableStateFlow("")
    private val mutableZoneEnforcementEnabled = MutableStateFlow(false)
    private val mutableZonePenaltyEnabled = MutableStateFlow(false)
    private val mutableZoneTargetZone = MutableStateFlow(DefaultTargetZone)
    private val mutableZoneGoalMinutes = MutableStateFlow(DefaultGoalMinutes)
    private val mutableZoneGraceSeconds = MutableStateFlow(DefaultGraceSeconds)

    val showTimerWhenMinimized = mutableShowTimerWhenMinimized
    val bleTxEnabled = mutableBleTxEnabled
    val dirConEnabled = mutableDirConEnabled
    val bleFtmsDeviceName = mutableBleFtmsDeviceName
    val serialNumber = mutableSerialNumber
    val zoneEnforcementEnabled = mutableZoneEnforcementEnabled
    val zonePenaltyEnabled = mutableZonePenaltyEnabled
    val zoneTargetZone = mutableZoneTargetZone
    val zoneGoalMinutes = mutableZoneGoalMinutes
    val zoneGraceSeconds = mutableZoneGraceSeconds

    /**
     * The shape zone enforcement actually consumes. Because the repository re-reads on every
     * SharedPreferences change, a settings edit reaches the running overlay service without a
     * restart.
     */
    val zoneEnforcementConfig: Flow<EnforcementConfig> = combine(
        mutableZoneEnforcementEnabled,
        mutableZonePenaltyEnabled,
        mutableZoneTargetZone,
        mutableZoneGoalMinutes,
        mutableZoneGraceSeconds,
    ) { enabled, penaltyEnabled, targetZone, goalMinutes, graceSeconds ->
        EnforcementConfig(
            enabled = enabled,
            penaltyEnabled = penaltyEnabled,
            targetZone = targetZone,
            goalSeconds = goalMinutes * 60L,
            graceSeconds = graceSeconds.toLong(),
        )
    }

    private val sharedPreferences: SharedPreferences

    // Must be kept as reference, unowned lambda would be garbage collected
    private fun createSharedPreferencesListener() =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            updateFromSharedPrefs()
        }

    private val listener : SharedPreferences.OnSharedPreferenceChangeListener

    init {
        sharedPreferences = context.getSharedPreferences(SharedPrefsName, Context.MODE_PRIVATE)
        updateFromSharedPrefs()

        listener = createSharedPreferencesListener()
        SharedPreferenceListeners.add(listener)
        sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
        lifecycleOwner.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                close()
            }
        })
    }

    fun setShowTimerWhenMinimized(isShown: Boolean) {
        mutableShowTimerWhenMinimized.value = isShown
        sharedPreferences.edit {
            putBoolean(Preferences.ShowTimerWhenMinimized.key, isShown)
        }
    }

    fun setBleTxEnabled(enabled: Boolean) {
        mutableBleTxEnabled.value = enabled
        sharedPreferences.edit {
            putBoolean(Preferences.BleTxEnabled.key, enabled)
        }
    }

    fun setDirConEnabled(enabled: Boolean) {
        mutableDirConEnabled.value = enabled
        sharedPreferences.edit {
            putBoolean(Preferences.DirConEnabled.key, enabled)
        }
    }

    fun setBleFtmsDeviceName(name: String) {
        mutableBleFtmsDeviceName.value = name
        sharedPreferences.edit {
            putString(Preferences.BleFtmsDeviceName.key, name)
        }
    }

    fun setZoneEnforcementEnabled(enabled: Boolean) {
        mutableZoneEnforcementEnabled.value = enabled
        sharedPreferences.edit { putBoolean(Preferences.ZoneEnforcementEnabled.key, enabled) }
    }

    fun setZonePenaltyEnabled(enabled: Boolean) {
        mutableZonePenaltyEnabled.value = enabled
        sharedPreferences.edit { putBoolean(Preferences.ZonePenaltyEnabled.key, enabled) }
    }

    fun setZoneTargetZone(zone: Int) {
        val clamped = zone.coerceIn(MinTargetZone, MaxTargetZone)
        mutableZoneTargetZone.value = clamped
        sharedPreferences.edit { putInt(Preferences.ZoneTargetZone.key, clamped) }
    }

    fun setZoneGoalMinutes(minutes: Int) {
        val clamped = minutes.coerceIn(MinGoalMinutes, MaxGoalMinutes)
        mutableZoneGoalMinutes.value = clamped
        sharedPreferences.edit { putInt(Preferences.ZoneGoalMinutes.key, clamped) }
    }

    fun setZoneGraceSeconds(seconds: Int) {
        // Heart rate lags effort by 20-30s; a shorter grace punishes physiology, not slacking.
        val clamped = seconds.coerceIn(MinGraceSeconds, MaxGraceSeconds)
        mutableZoneGraceSeconds.value = clamped
        sharedPreferences.edit { putInt(Preferences.ZoneGraceSeconds.key, clamped) }
    }

    fun setSerialNumber(serial: String) {
        val normalized = serial.trim().uppercase()
        mutableSerialNumber.value = normalized
        sharedPreferences.edit {
            putString(Preferences.SerialNumber.key, normalized)
        }
    }

    private fun generateSerialHex(): String {
        val value = kotlin.random.Random.nextInt(0x10000)
        return value.toString(16).padStart(4, '0').uppercase()
    }

    private fun updateFromSharedPrefs() {
        mutableShowTimerWhenMinimized.value =
            sharedPreferences
                .getBoolean(Preferences.ShowTimerWhenMinimized.key, true)

        mutableBleTxEnabled.value =
            sharedPreferences
                .getBoolean(Preferences.BleTxEnabled.key, true)

        mutableDirConEnabled.value =
            sharedPreferences
                .getBoolean(Preferences.DirConEnabled.key, true)

        mutableBleFtmsDeviceName.value =
            sharedPreferences
                .getString(Preferences.BleFtmsDeviceName.key, "Grupetto FTMS") ?: "Grupetto FTMS"

        mutableZoneEnforcementEnabled.value =
            sharedPreferences.getBoolean(Preferences.ZoneEnforcementEnabled.key, false)

        mutableZonePenaltyEnabled.value =
            sharedPreferences.getBoolean(Preferences.ZonePenaltyEnabled.key, false)

        mutableZoneTargetZone.value =
            sharedPreferences.getInt(Preferences.ZoneTargetZone.key, DefaultTargetZone)
                .coerceIn(MinTargetZone, MaxTargetZone)

        mutableZoneGoalMinutes.value =
            sharedPreferences.getInt(Preferences.ZoneGoalMinutes.key, DefaultGoalMinutes)
                .coerceIn(MinGoalMinutes, MaxGoalMinutes)

        mutableZoneGraceSeconds.value =
            sharedPreferences.getInt(Preferences.ZoneGraceSeconds.key, DefaultGraceSeconds)
                .coerceIn(MinGraceSeconds, MaxGraceSeconds)

        // Ensure a serial number exists and keep it in memory
        val existingSerial = sharedPreferences.getString(Preferences.SerialNumber.key, null)
        val ensuredSerial = if (existingSerial.isNullOrEmpty()) {
            val sn = generateSerialHex()
            sharedPreferences.edit { putString(Preferences.SerialNumber.key, sn) }
            sn
        } else existingSerial
        mutableSerialNumber.value = ensuredSerial
    }

    override fun close() {
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener)
        SharedPreferenceListeners.remove(listener)
    }
}
