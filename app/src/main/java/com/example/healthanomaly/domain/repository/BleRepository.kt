package com.example.healthanomaly.domain.repository

import com.example.healthanomaly.domain.model.HeartRateData
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for BLE heart rate monitor.
 */
interface BleRepository {
    /**
     * Flow of heart rate data as it's received.
     */
    val heartRateFlow: Flow<HeartRateData>
    
    /**
     * Flow of BLE scan results.
     */
    val scanResultsFlow: Flow<List<BleDevice>>
    
    /**
     * Flow of connection state.
     */
    val connectionStateFlow: Flow<BleConnectionState>
    
    /**
     * Start scanning for BLE devices with Heart Rate Service.
     */
    fun startScan()
    
    /**
     * Stop scanning.
     */
    fun stopScan()
    
    /**
     * Connect to a BLE device.
     * @param deviceAddress MAC address of the device
     */
    fun connect(deviceAddress: String)
    
    /**
     * Disconnect from current device.
     */
    fun disconnect()
    
    /**
     * Check if connected to a device.
     */
    fun isConnected(): Boolean
    
    /**
     * Get the currently connected device address.
     */
    fun getConnectedDeviceAddress(): String?
}
