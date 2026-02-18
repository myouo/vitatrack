package com.example.healthanomaly.domain.usecase

import com.example.healthanomaly.domain.model.HeartRateData
import com.example.healthanomaly.domain.repository.BleConnectionState
import com.example.healthanomaly.domain.repository.BleDevice
import com.example.healthanomaly.domain.repository.BleRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Use case for BLE heart rate scanning and connection.
 */
class StartBleScanUseCase @Inject constructor(
    private val bleRepository: BleRepository
) {
    /**
     * Observe heart rate data stream.
     */
    fun observeHeartRate(): Flow<HeartRateData> = bleRepository.heartRateFlow
    
    /**
     * Observe BLE scan results.
     */
    fun observeScanResults(): Flow<List<BleDevice>> = bleRepository.scanResultsFlow
    
    /**
     * Observe connection state.
     */
    fun observeConnectionState(): Flow<BleConnectionState> = bleRepository.connectionStateFlow
    
    /**
     * Start scanning for BLE heart rate monitors.
     */
    fun startScan() = bleRepository.startScan()
    
    /**
     * Stop scanning.
     */
    fun stopScan() = bleRepository.stopScan()
    
    /**
     * Connect to a device.
     */
    fun connect(deviceAddress: String) = bleRepository.connect(deviceAddress)
    
    /**
     * Disconnect from device.
     */
    fun disconnect() = bleRepository.disconnect()
    
    /**
     * Check if connected.
     */
    fun isConnected(): Boolean = bleRepository.isConnected()
}
