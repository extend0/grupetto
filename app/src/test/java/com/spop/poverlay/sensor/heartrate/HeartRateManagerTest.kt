package com.spop.poverlay.sensor.heartrate

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.SharedPreferences
import android.os.ParcelUuid
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class HeartRateManagerTest {
    private val context = mockk<Context>()
    private val prefs = mockk<SharedPreferences>(relaxed = true)
    private val editor = mockk<SharedPreferences.Editor>(relaxed = true)
    private val adapter = mockk<BluetoothAdapter>()
    private val scanner = mockk<BluetoothLeScanner>(relaxed = true)
    private val device = mockk<BluetoothDevice>()
    private val gatt = mockk<BluetoothGatt>(relaxed = true)
    private val callbacks = mutableListOf<BluetoothGattCallback>()
    private val scans = mutableListOf<ScanCallback>()
    private val address = "AA:BB:CC:DD:EE:01"
    private val saved = mutableSetOf<String>()

    @Before fun setup() {
        mockkStatic(BluetoothAdapter::class)
        mockkConstructor(ScanFilter.Builder::class, ScanSettings.Builder::class, ParcelUuid::class)
        every { anyConstructed<ScanFilter.Builder>().setServiceUuid(any()) } answers { self as ScanFilter.Builder }
        every { anyConstructed<ScanFilter.Builder>().build() } returns mockk()
        every { anyConstructed<ScanSettings.Builder>().setScanMode(any()) } answers { self as ScanSettings.Builder }
        every { anyConstructed<ScanSettings.Builder>().build() } returns mockk()
        every { BluetoothAdapter.getDefaultAdapter() } returns adapter
        every { adapter.bluetoothLeScanner } returns scanner
        every { adapter.getRemoteDevice(address) } returns device
        every { context.applicationContext } returns context
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { prefs.getStringSet(any(), any()) } answers { saved.toSet() }
        every { prefs.getString(any(), any()) } returns null
        every { prefs.edit() } returns editor
        every { editor.putStringSet(any(), any()) } answers {
            saved.clear()
            saved.addAll(secondArg<Set<String>>())
            editor
        }
        every { device.address } returns address
        every { device.name } returns "Test strap"
        every { gatt.device } returns device
        every { gatt.discoverServices() } returns true
        every { device.connectGatt(context, false, capture(callbacks)) } returns gatt
        every { scanner.startScan(any<List<ScanFilter>>(), any<ScanSettings>(), capture(scans)) } just Runs
        HeartRateManager.start(context)
    }

    @After fun cleanup() {
        HeartRateManager.stop()
        unmockkAll()
    }

    private fun result(): ScanResult {
        val uuid = mockk<ParcelUuid>()
        every { uuid.uuid } returns UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val record = mockk<ScanRecord>()
        every { record.serviceUuids } returns listOf(uuid)
        return mockk<ScanResult>().also {
            every { it.device } returns device
            every { it.scanRecord } returns record
        }
    }

    @Test fun `concurrent discovery starts register only one scanner`() {
        every { scanner.startScan(any<List<ScanFilter>>(), any<ScanSettings>(), capture(scans)) } answers {
            Thread.sleep(50)
        }
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        val workers = List(2) {
            Thread {
                try {
                    ready.countDown()
                    start.await()
                    HeartRateManager.startDiscovery()
                } catch (t: Throwable) { error.set(t) }
            }.also { it.start() }
        }
        assertTrue(ready.await(2, TimeUnit.SECONDS))
        start.countDown()
        workers.forEach { it.join(2_000) }
        error.get()?.let { throw it }
        assertTrue(workers.none { it.isAlive })
        assertEquals(1, scans.size)
        HeartRateManager.stopDiscovery()
        verify(exactly = 1) { scanner.stopScan(scans.single()) }
        assertFalse(HeartRateManager.isScanning.value)
    }

    @Test fun `connect taps and queued scan results do not restart pending connection`() {
        HeartRateManager.startDiscovery()
        val oldScan = scans.single()
        HeartRateManager.connectTo(HeartRateDevice(address, "Test strap"))
        repeat(3) {
            HeartRateManager.connectTo(HeartRateDevice(address, "Test strap"))
            oldScan.onScanResult(0, result())
        }
        verify(exactly = 1) { device.connectGatt(context, false, any()) }
        verify(exactly = 0) { gatt.disconnect() }
        verify(exactly = 1) { scanner.stopScan(oldScan) }
        callbacks.single().onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        assertEquals(address, HeartRateManager.connectedDevice.value?.address)
    }

    @Test fun `old connection callbacks cannot clear replacement connection or update its heart rate`() {
        HeartRateManager.connectTo(HeartRateDevice(address, "Test strap"))
        val oldCallback = callbacks.single()
        HeartRateManager.disconnectCurrent()
        val replacement = mockk<BluetoothGatt>(relaxed = true)
        every { replacement.device } returns device
        every { replacement.discoverServices() } returns true
        every { device.connectGatt(context, false, capture(callbacks)) } returns replacement
        HeartRateManager.connectTo(HeartRateDevice(address, "Test strap"))
        callbacks.last().onConnectionStateChange(replacement, 0, BluetoothProfile.STATE_CONNECTED)
        HeartRateManager.injectDebugHeartRate(130)
        oldCallback.onConnectionStateChange(gatt, 0, BluetoothProfile.STATE_DISCONNECTED)
        oldCallback.onConnectionStateChange(gatt, 133, BluetoothProfile.STATE_DISCONNECTED)
        oldCallback.onConnectionStateChange(gatt, 0, BluetoothProfile.STATE_CONNECTED)
        oldCallback.onServicesDiscovered(gatt, 0)
        val measurement = mockk<BluetoothGattCharacteristic>()
        every { measurement.uuid } returns UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        every { measurement.value } returns byteArrayOf(0, 90)
        oldCallback.onCharacteristicChanged(gatt, measurement)
        assertEquals(address, HeartRateManager.connectedDevice.value?.address)
        assertEquals(130, HeartRateManager.heartRate.value)
        verify(exactly = 1) { gatt.close() }
        verify(exactly = 0) { gatt.discoverServices() }
        verify(exactly = 0) { replacement.disconnect() }
    }

    @Test fun `failed scan permits a new scan and old failure cannot stop it`() {
        HeartRateManager.startDiscovery()
        val first = scans.single()
        first.onScanFailed(ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED)
        assertFalse(HeartRateManager.isScanning.value)
        HeartRateManager.startDiscovery()
        first.onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR)
        assertTrue(HeartRateManager.isScanning.value)
        assertEquals(2, scans.size)
    }

    @Test fun `stop rejects queued scan and connection callbacks`() {
        HeartRateManager.startDiscovery()
        val scan = scans.single()
        HeartRateManager.connectTo(HeartRateDevice(address, "Test strap"))
        val callback = callbacks.single()
        HeartRateManager.stop()
        scan.onScanResult(0, result())
        callback.onConnectionStateChange(gatt, 0, BluetoothProfile.STATE_CONNECTED)
        HeartRateManager.startDiscovery()
        assertNull(HeartRateManager.connectedDevice.value)
        assertFalse(HeartRateManager.isScanning.value)
        verify(exactly = 1) { gatt.close() }
        verify(exactly = 1) { device.connectGatt(context, false, any()) }
    }

    @Test fun `revoked connect permission does not crash or retain a client`() {
        every { device.connectGatt(context, false, any()) } throws SecurityException("revoked")
        HeartRateManager.connectTo(HeartRateDevice(address, "Test strap"))
        assertNull(HeartRateManager.connectedDevice.value)
        every { device.connectGatt(context, false, capture(callbacks)) } returns gatt
        HeartRateManager.connectTo(HeartRateDevice(address, "Test strap"))
        callbacks.last().onConnectionStateChange(gatt, 0, BluetoothProfile.STATE_CONNECTED)
        assertEquals(address, HeartRateManager.connectedDevice.value?.address)
    }

    @Test fun `revoked service discovery permission closes the connection`() {
        HeartRateManager.connectTo(HeartRateDevice(address, "Test strap"))
        every { gatt.discoverServices() } throws SecurityException("revoked")
        callbacks.single().onConnectionStateChange(gatt, 0, BluetoothProfile.STATE_CONNECTED)
        assertNull(HeartRateManager.connectedDevice.value)
        verify(exactly = 1) { gatt.close() }
    }
}
