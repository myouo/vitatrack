package com.example.healthanomaly.data.repository

import com.example.healthanomaly.data.ble.BleManager
import com.example.healthanomaly.domain.model.HeartRateData
import com.example.healthanomaly.domain.repository.BleConnectionState
import com.example.healthanomaly.domain.repository.BleDevice
import com.example.healthanomaly.domain.repository.BleRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of BleRepository using BleManager.
 */
@Singleton
class BleRepositoryImpl @Inject constructor(
    private val bleManager: BleManager
) : BleRepository {
    
    override val heartRateFlow: Flow<HeartRateData> = bleManager.heartRateFlow
    
    override val scanResultsFlow: Flow<List<BleDevice>> = bleManager.scanResultsFlow
    
    override val connectionStateFlow: Flow<BleConnectionState> = bleManager.connectionStateFlow
    
    override fun startScan() = bleManager.startScan()
    
    override fun stopScan() = bleManager.stopScan()
    
    override fun connect(deviceAddress: String) = bleManager.connect(deviceAddress)
    
    override fun disconnect() = bleManager.disconnect()
    
    override fun isConnected(): Boolean = bleManager.isConnected()
    
    override fun getConnectedDeviceAddress(): String? = bleManager.getConnectedDeviceAddress()
}
