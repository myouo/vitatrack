package com.example.healthanomaly.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import com.example.healthanomaly.domain.model.HeartRateData
import com.example.healthanomaly.domain.repository.BleConnectionState
import com.example.healthanomaly.domain.repository.BleDevice
import com.example.healthanomaly.domain.repository.BleRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BLE Manager for scanning, connecting, and receiving heart rate data.
 * Implements standard Heart Rate Service (0x180D) and Heart Rate Measurement (0x2A37).
 */
@Singleton
class BleManager @Inject constructor(
    @ApplicationContext private val context: Context
) : BleRepository {
    
    companion object {
        // Heart Rate Service UUID
        val HEART_RATE_SERVICE_UUID: java.util.UUID = 
            java.util.UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        
        // Heart Rate Measurement Characteristic UUID
        val HEART_RATE_MEASUREMENT_UUID: java.util.UUID = 
            java.util.UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        
        // Client Characteristic Configuration Descriptor UUID
        val CLIENT_CONFIG_DESCRIPTOR_UUID: java.util.UUID = 
            java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
    
    private val bluetoothManager: BluetoothManager? = 
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    
    private val bluetoothAdapter: BluetoothAdapter? = 
        bluetoothManager?.adapter
    
    private val bleScanner = bluetoothAdapter?.bluetoothLeScanner
    
    // State
    private val _scanResultsFlow = MutableStateFlow<List<BleDevice>>(emptyList())
    private val _heartRateFlow = MutableStateFlow<HeartRateData?>(null)
    private val _connectionStateFlow = MutableStateFlow(BleConnectionState.DISCONNECTED)
    
    private var bluetoothGatt: BluetoothGatt? = null
    private var isScanning = false
    
    // Recent heart rate values for smoothing
    private val heartRateHistory = mutableListOf<Int>()
    private val maxHistorySize = 5
    
    override val scanResultsFlow: Flow<List<BleDevice>> = _scanResultsFlow.asStateFlow()
    
    override val heartRateFlow: Flow<HeartRateData> = callbackFlow {
        val listener: (HeartRateData) -> Unit = { data ->
            trySend(data)
        }
        _heartRateFlow.value?.let { listener(it) } // Send last value to new collectors
        
        awaitClose { }
    }
    
    override val connectionStateFlow: Flow<BleConnectionState> = _connectionStateFlow.asStateFlow()
    
    /**
     * Start scanning for BLE devices with Heart Rate Service.
     */
    @SuppressLint("MissingPermission")
    override fun startScan() {
        if (isScanning) return
        
        _scanResultsFlow.value = emptyList()
        
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        
        bleScanner?.startScan(null, scanSettings, scanCallback)
        isScanning = true
    }
    
    /**
     * Stop scanning.
     */
    @SuppressLint("MissingPermission")
    override fun stopScan() {
        if (!isScanning) return
        
        bleScanner?.stopScan(scanCallback)
        isScanning = false
    }
    
    /**
     * Connect to a BLE device.
     */
    @SuppressLint("MissingPermission")
    override fun connect(deviceAddress: String) {
        if (_connectionStateFlow.value == BleConnectionState.CONNECTING ||
            _connectionStateFlow.value == BleConnectionState.CONNECTED) {
            return
        }
        
        _connectionStateFlow.value = BleConnectionState.CONNECTING
        
        val device = bluetoothAdapter?.getRemoteDevice(deviceAddress)
        device?.let { btDevice ->
            bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                btDevice.connectGatt(context, true, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                btDevice.connectGatt(context, true, gattCallback)
            }
        }
    }
    
    /**
     * Disconnect from current device.
     */
    @SuppressLint("MissingPermission")
    override fun disconnect() {
        _connectionStateFlow.value = BleConnectionState.DISCONNECTING
        bluetoothGatt?.disconnect()
    }
    
    override fun isConnected(): Boolean = 
        _connectionStateFlow.value == BleConnectionState.CONNECTED
    
    override fun getConnectedDeviceAddress(): String? = bluetoothGatt?.device?.address
    
    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val services = result.scanRecord?.serviceUuids
            
            val hasHeartRateService = services?.any { it.uuid == HEART_RATE_SERVICE_UUID } == true
            
            if (hasHeartRateService || services.isNullOrEmpty()) {
                val bleDevice = BleDevice(
                    address = device.address,
                    name = device.name,
                    rssi = result.rssi
                )
                
                val current = _scanResultsFlow.value.toMutableList()
                val existingIndex = current.indexOfFirst { it.address == bleDevice.address }
                if (existingIndex >= 0) {
                    current[existingIndex] = bleDevice
                } else {
                    current.add(bleDevice)
                }
                _scanResultsFlow.value = current
            }
        }
        
        override fun onScanFailed(errorCode: Int) {
            isScanning = false
        }
    }
    
    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _connectionStateFlow.value = BleConnectionState.CONNECTED
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _connectionStateFlow.value = BleConnectionState.DISCONNECTED
                    bluetoothGatt?.close()
                    bluetoothGatt = null
                    
                    // Auto-reconnect (simplified - could be more sophisticated)
                }
            }
        }
        
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // Enable heart rate notifications
                val heartRateService = gatt.getService(HEART_RATE_SERVICE_UUID)
                heartRateService?.getCharacteristic(HEART_RATE_MEASUREMENT_UUID)?.let { characteristic ->
                    gatt.setCharacteristicNotification(characteristic, true)
                    characteristic.getDescriptor(CLIENT_CONFIG_DESCRIPTOR_UUID)?.let { descriptor ->
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                    }
                }
            }
        }
        
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == HEART_RATE_MEASUREMENT_UUID) {
                val heartRate = parseHeartRate(value)
                val data = HeartRateData(
                    heartRateBpm = heartRate,
                    timestampMs = System.currentTimeMillis(),
                    deviceAddress = gatt.device?.address ?: ""
                )
                _heartRateFlow.value = data
            }
        }
        
        private fun parseHeartRate(value: ByteArray): Int {
            return if (value.isNotEmpty()) {
                val flags = value[0].toInt()
                if (flags and 0x01 != 0) {
                    // Heart Rate Value Format is UINT16
                    if (value.size >= 3) {
                        (value[1].toInt() and 0xFF) or ((value[2].toInt() and 0xFF) shl 8)
                    } else {
                        0
                    }
                } else {
                    // Heart Rate Value Format is UINT8
                    if (value.size >= 2) {
                        value[1].toInt() and 0xFF
                    } else {
                        0
                    }
                }
            } else {
                0
            }
        }
    }
}
