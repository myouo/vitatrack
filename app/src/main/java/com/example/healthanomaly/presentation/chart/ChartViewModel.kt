package com.example.healthanomaly.presentation.chart

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.healthanomaly.domain.model.FeatureWindow
import com.example.healthanomaly.domain.repository.AnomalyRepository
import com.example.healthanomaly.service.DataCollectionService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * ViewModel for the Chart screen.
 */
@HiltViewModel
class ChartViewModel @Inject constructor(
    private val anomalyRepository: AnomalyRepository
) : ViewModel() {
    
    // Combined state for chart data
    val state: StateFlow<ChartUiState> = combine(
        anomalyRepository.featureWindowsFlow,
        DataCollectionService.currentHeartRate,
        DataCollectionService.currentStepFreq
    ) { windows, hr, stepFreq ->
        ChartUiState(
            featureWindows = windows.takeLast(100), // Last 100 windows
            currentHeartRate = hr,
            currentStepFreq = stepFreq
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ChartUiState()
    )
}

/**
 * UI State for Chart.
 */
data class ChartUiState(
    val featureWindows: List<FeatureWindow> = emptyList(),
    val currentHeartRate: Int? = null,
    val currentStepFreq: Float? = null
)
