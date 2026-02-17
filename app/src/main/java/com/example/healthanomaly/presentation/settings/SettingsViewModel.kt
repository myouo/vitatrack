package com.example.healthanomaly.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.healthanomaly.core.Constants
import com.example.healthanomaly.core.PreferencesManager
import com.example.healthanomaly.domain.usecase.ExportDataUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Settings screen.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencesManager: PreferencesManager,
    private val exportDataUseCase: ExportDataUseCase
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()
    
    init {
        loadSettings()
    }
    
    /**
     * Load current settings.
     */
    private fun loadSettings() {
        _uiState.value = SettingsUiState(
            hrHighThreshold = preferencesManager.getHrHighThreshold(),
            hrLowThreshold = preferencesManager.getHrLowThreshold(),
            stepFreqHighThreshold = preferencesManager.getStepFreqHighThreshold(),
            stepFreqLowThreshold = preferencesManager.getStepFreqLowThreshold(),
            windowSizeMs = preferencesManager.getWindowSizeMs(),
            fallDetectionEnabled = preferencesManager.isFallDetectionEnabled()
        )
    }
    
    /**
     * Update HR high threshold.
     */
    fun setHrHighThreshold(value: Int) {
        preferencesManager.setHrHighThreshold(value)
        _uiState.value = _uiState.value.copy(hrHighThreshold = value)
    }
    
    /**
     * Update HR low threshold.
     */
    fun setHrLowThreshold(value: Int) {
        preferencesManager.setHrLowThreshold(value)
        _uiState.value = _uiState.value.copy(hrLowThreshold = value)
    }
    
    /**
     * Update step frequency high threshold.
     */
    fun setStepFreqHighThreshold(value: Float) {
        preferencesManager.setStepFreqHighThreshold(value)
        _uiState.value = _uiState.value.copy(stepFreqHighThreshold = value)
    }
    
    /**
     * Update step frequency low threshold.
     */
    fun setStepFreqLowThreshold(value: Float) {
        preferencesManager.setStepFreqLowThreshold(value)
        _uiState.value = _uiState.value.copy(stepFreqLowThreshold = value)
    }
    
    /**
     * Toggle fall detection.
     */
    fun setFallDetectionEnabled(enabled: Boolean) {
        preferencesManager.setFallDetectionEnabled(enabled)
        _uiState.value = _uiState.value.copy(fallDetectionEnabled = enabled)
    }
    
    /**
     * Export all data to CSV.
     */
    fun exportData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isExporting = true)
            
            val (eventsPath, featuresPath) = exportDataUseCase.exportAll()
            
            _uiState.value = _uiState.value.copy(
                isExporting = false,
                lastExportEvents = eventsPath,
                lastExportFeatures = featuresPath,
                exportError = if (eventsPath == null && featuresPath == null) {
                    "No data to export"
                } else null
            )
        }
    }
    
    /**
     * Clear export state.
     */
    fun clearExportState() {
        _uiState.value = _uiState.value.copy(
            lastExportEvents = null,
            lastExportFeatures = null,
            exportError = null
        )
    }
}

/**
 * UI State for Settings.
 */
data class SettingsUiState(
    val hrHighThreshold: Int = Constants.HR_HIGH_THRESHOLD,
    val hrLowThreshold: Int = Constants.HR_LOW_THRESHOLD,
    val stepFreqHighThreshold: Float = Constants.STEP_FREQ_HIGH_THRESHOLD,
    val stepFreqLowThreshold: Float = Constants.STEP_FREQ_LOW_THRESHOLD,
    val windowSizeMs: Long = Constants.WINDOW_SIZE_MS,
    val fallDetectionEnabled: Boolean = true,
    val isExporting: Boolean = false,
    val lastExportEvents: String? = null,
    val lastExportFeatures: String? = null,
    val exportError: String? = null
)
