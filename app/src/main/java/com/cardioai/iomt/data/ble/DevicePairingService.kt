package com.cardioai.iomt.data.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import com.cardioai.iomt.data.network.ApiClient
import com.cardioai.iomt.data.storage.SecureKey
import com.cardioai.iomt.data.storage.SecureStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

private const val TAG = "DevicePairing"

// Standard Bluetooth SIG GATT service UUIDs — same ones used on iOS.
// Despite common naming, these are NOT proprietary "CardioAI" services;
// any compliant off-the-shelf BLE heart rate/BP/pulse-ox device works.
object BLEServiceUUIDs {
    val HEART_RATE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    val BLOOD_PRESSURE: UUID = UUID.fromString("00001810-0000-1000-8000-00805f9b34fb")
    val PULSE_OXIMETER: UUID = UUID.fromString("00001822-0000-1000-8000-00805f9b34fb")
    val HEART_RATE_MEASUREMENT_CHAR: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
}

data class DiscoveredDevice(
    val address: String,
    val name: String,
    val rssi: Int,
)

sealed class PairingState {
    object Idle : PairingState()
    object Scanning : PairingState()
    data class Discovered(val devices: List<DiscoveredDevice>) : PairingState()
    object Connecting : PairingState()
    object Connected : PairingState()
    data class Failed(val reason: String) : PairingState()
}

class DevicePairingService(
    private val context: Context,
    private val apiClient: ApiClient,
    private val secureStorage: SecureStorage,
) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var bluetoothGatt: BluetoothGatt? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    private val _pairingState = MutableStateFlow<PairingState>(PairingState.Idle)
    val pairingState: StateFlow<PairingState> = _pairingState.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _lastHeartRate = MutableStateFlow<Int?>(null)
    val lastHeartRate: StateFlow<Int?> = _lastHeartRate.asStateFlow()

    private val _framesSynced = MutableStateFlow(0)
    val framesSynced: StateFlow<Int> = _framesSynced.asStateFlow()

    private val discoveredMap = mutableMapOf<String, DiscoveredDevice>()
    private var patientId: String = ""

    init {
        patientId = secureStorage.read(SecureKey.PATIENT_ID) ?: ""
    }

    // MARK: - Scanning
    //
    // NOTE ON TESTING WITHOUT REAL HARDWARE: restrictToHealthServices below
    // mirrors the same flag from the iOS app — set true for production so
    // only compliant health devices show up; false discovers any nearby
    // BLE peripheral, useful for validating the scan/connect UI without
    // owning a compliant device yet. See DevicePairingService.swift for
    // the full rationale — same tradeoff applies here.

    private val restrictToHealthServices = false // set true for production

    fun startScanning() {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            _pairingState.value = PairingState.Failed("Bluetooth is not enabled. Please turn on Bluetooth in Settings.")
            return
        }
        discoveredMap.clear()
        _pairingState.value = PairingState.Scanning

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            _pairingState.value = PairingState.Failed("Bluetooth LE scanning is not available on this device.")
            return
        }

        val filters = if (restrictToHealthServices) {
            listOf(
                android.bluetooth.le.ScanFilter.Builder()
                    .setServiceUuid(android.os.ParcelUuid(BLEServiceUUIDs.HEART_RATE)).build(),
                android.bluetooth.le.ScanFilter.Builder()
                    .setServiceUuid(android.os.ParcelUuid(BLEServiceUUIDs.BLOOD_PRESSURE)).build(),
                android.bluetooth.le.ScanFilter.Builder()
                    .setServiceUuid(android.os.ParcelUuid(BLEServiceUUIDs.PULSE_OXIMETER)).build(),
            )
        } else emptyList()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(filters, settings, scanCallback)
        } catch (e: SecurityException) {
            _pairingState.value = PairingState.Failed("Bluetooth permission not granted.")
            return
        }

        scope.launch {
            delay(15_000)
            if (_pairingState.value is PairingState.Scanning) stopScanning()
        }
    }

    fun stopScanning() {
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            Log.w(TAG, "stopScan denied: ${e.message}")
        }
        if (discoveredMap.isNotEmpty()) {
            _pairingState.value = PairingState.Discovered(discoveredMap.values.toList())
        } else {
            _pairingState.value = PairingState.Idle
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = try { device.name } catch (e: SecurityException) { null } ?: return
            discoveredMap[device.address] = DiscoveredDevice(device.address, name, result.rssi)
            _pairingState.value = PairingState.Discovered(discoveredMap.values.toList())
        }

        override fun onScanFailed(errorCode: Int) {
            _pairingState.value = PairingState.Failed("Scan failed with error code $errorCode")
        }
    }

    // MARK: - Connect

    fun connect(address: String, deviceName: String) {
        val adapter = bluetoothAdapter ?: return
        stopScanning()
        _pairingState.value = PairingState.Connecting

        val device: BluetoothDevice = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            _pairingState.value = PairingState.Failed("Invalid device address")
            return
        }

        try {
            bluetoothGatt = device.connectGatt(context, false, gattCallback)
        } catch (e: SecurityException) {
            _pairingState.value = PairingState.Failed("Bluetooth permission not granted.")
        }
    }

    fun disconnect() {
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (e: SecurityException) {
            // ignore
        }
        bluetoothGatt = null
        _isStreaming.value = false
        _pairingState.value = PairingState.Idle
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _pairingState.value = PairingState.Connected
                    try {
                        gatt.discoverServices()
                    } catch (e: SecurityException) {
                        _pairingState.value = PairingState.Failed("Bluetooth permission not granted.")
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _isStreaming.value = false
                    _pairingState.value = PairingState.Idle
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val hrService = gatt.getService(BLEServiceUUIDs.HEART_RATE) ?: return
            val hrChar = hrService.getCharacteristic(BLEServiceUUIDs.HEART_RATE_MEASUREMENT_CHAR) ?: return
            try {
                gatt.setCharacteristicNotification(hrChar, true)
                val descriptor = hrChar.descriptors.firstOrNull()
                if (descriptor != null) {
                    descriptor.value = BluetoothGattDescriptorCompat.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)
                }
                _isStreaming.value = true
            } catch (e: SecurityException) {
                _pairingState.value = PairingState.Failed("Bluetooth permission not granted.")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val bpm = parseHeartRateMeasurement(characteristic) ?: return
            _lastHeartRate.value = bpm
            _framesSynced.value += 1

            scope.launch {
                try {
                    apiClient.registerDevice(
                        deviceId = gatt.device.address,
                        deviceType = "ecg_monitor",
                        patientId = patientId,
                        deviceName = KnownBLEDevices.match(gatt.device.name ?: "")?.displayLabel ?: (gatt.device.name ?: "BLE Device"),
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "device sync failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Parses the standard Bluetooth Heart Rate Measurement characteristic
     * format (same bit layout every compliant BLE heart rate device uses —
     * this is a documented Bluetooth SIG standard, not vendor-specific).
     */
    private fun parseHeartRateMeasurement(characteristic: BluetoothGattCharacteristic): Int? {
        val data = characteristic.value ?: return null
        if (data.isEmpty()) return null
        val flag = data[0].toInt()
        val isFormatUInt16 = (flag and 0x01) != 0
        return if (isFormatUInt16) {
            if (data.size < 3) return null
            ((data[2].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        } else {
            if (data.size < 2) return null
            data[1].toInt() and 0xFF
        }
    }
}

private object BluetoothGattDescriptorCompat {
    val ENABLE_NOTIFICATION_VALUE: ByteArray = byteArrayOf(0x01, 0x00)
}
