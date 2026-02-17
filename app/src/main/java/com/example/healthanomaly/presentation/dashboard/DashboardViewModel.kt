package com.example.healthanomaly.presentation.dashboard

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.healthanomaly.domain.repository.BleConnectionState
import com.example.healthanomaly.domain.repository.BleDevice
import com.example.healthanomaly.domain.usecase.CollectImuDataUseCase
import com.example.healthanomaly.domain.usecase.StartBleScanUseCase
import com.example.healthanomaly.service.DataCollectionService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Dashboard screen.
 */
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val collectImuDataUseCase: CollectImuDataUseCase,
    private val startBleScanUseCase: StartBleScanUseCase
) : ViewModel() {
    
    // UI State
    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()
    
    // Combined state for UI
    val state: StateFlow<DashboardUiState> = combine(
        DataCollectionService.isRunning,
        DataCollectionService.currentHeartRate,
        DataCollectionService.currentStepFreq,
        DataCollectionService.isBleConnected,
        startBleScanUseCase.observeScanResults(),
        startBleScanUseCase.observeConnectionState()
    ) { isRunning, hr, stepFreq, bleConnected, scanResults, connectionState ->
        DashboardUiState(
            isCollecting = isRunning,
            currentHeartRate = hr,
            currentStepFreq = stepFreq,
            isBleConnected = bleConnected,
            scanResults = scanResults,
            connectionState = connectionState
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DashboardUiState()
    )
    
    /**
     * Start data collection service.
     */
    fun startCollection() {
        viewModelScope.launch {
            collectImuDataUseCase.startCollection()
        }
    }
    
    /**
     * Stop data collection service.
     */
    fun stopCollection() {
        viewModelScope.launch {
            collectImuDataUseCase.stopCollection()
        }
    }
    
    /**
     * Start BLE scanning.
     */
    fun startBleScan() {
        startBleScanUseCase.startScan()
    }
    
    /**
     * Stop BLE scanning.
     */
    fun stopBleScan() {
        startBleScanUseCase.stopScan()
    }
    
    /**
     * Connect to a BLE device.
     */
    fun connectToDevice(deviceAddress: String) {
        startBleScanUseCase.connect(deviceAddress)
    }
    
    /**
     * Disconnect from BLE device.
     */
    fun disconnectBle() {
        startBleScanUseCase.disconnect()
    }
}

/**
 * UI State for Dashboard.
 */
data class DashboardUiState(
    val isCollecting: Boolean = false,
    val currentHeartRate: Int? = null,
    val currentStepFreq: Float? = null,
    val isBleConnected: Boolean = false,
    val scanResults: List<BleDevice> = emptyList(),
    val connectionState: BleConnectionState = BleConnectionState.DISCONNECTED,
    val isLoading: Boolean = false,
    val error: String? = null
)
