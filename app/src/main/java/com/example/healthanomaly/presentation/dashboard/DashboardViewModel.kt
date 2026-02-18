package com.example.healthanomaly.presentation.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.healthanomaly.domain.repository.BleConnectionState
import com.example.healthanomaly.domain.repository.BleDevice
import com.example.healthanomaly.domain.usecase.CollectImuDataUseCase
import com.example.healthanomaly.domain.usecase.StartBleScanUseCase
import com.example.healthanomaly.service.DataCollectionService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    
    val state: StateFlow<DashboardUiState> = _uiState
    
    init {
        observeData()
    }
    
    private fun observeData() {
        viewModelScope.launch {
            DataCollectionService.isRunning.collect { isRunning ->
                _uiState.value = _uiState.value.copy(isCollecting = isRunning)
            }
        }
        
        viewModelScope.launch {
            DataCollectionService.currentHeartRate.collect { hr ->
                _uiState.value = _uiState.value.copy(currentHeartRate = hr)
            }
        }
        
        viewModelScope.launch {
            DataCollectionService.currentStepFreq.collect { stepFreq ->
                _uiState.value = _uiState.value.copy(currentStepFreq = stepFreq)
            }
        }
        
        viewModelScope.launch {
            DataCollectionService.isBleConnected.collect { bleConnected ->
                _uiState.value = _uiState.value.copy(isBleConnected = bleConnected)
            }
        }
        
        viewModelScope.launch {
            startBleScanUseCase.observeScanResults().collect { scanResults ->
                _uiState.value = _uiState.value.copy(scanResults = scanResults)
            }
        }
        
        viewModelScope.launch {
            startBleScanUseCase.observeConnectionState().collect { connectionState ->
                _uiState.value = _uiState.value.copy(connectionState = connectionState)
            }
        }
    }
    
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
