package com.example.healthanomaly.presentation.chart

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.healthanomaly.core.PlaybackSessionManager
import com.example.healthanomaly.domain.model.FeatureWindow
import com.example.healthanomaly.domain.repository.AnomalyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class ChartViewModel @Inject constructor(
    private val anomalyRepository: AnomalyRepository,
    private val playbackSessionManager: PlaybackSessionManager
) : ViewModel() {

    val state: StateFlow<ChartUiState> = combine(
        anomalyRepository.featureWindowsFlow,
        playbackSessionManager.recentFeatureWindows,
        playbackSessionManager.isRunning,
        playbackSessionManager.currentHeartRate,
        playbackSessionManager.currentStepFreq
    ) { persistedWindows, liveWindows, isRunning, heartRate, stepFreq ->
        val chartWindows = when {
            isRunning || liveWindows.isNotEmpty() -> liveWindows
            else -> persistedWindows.asReversed().takeLast(60)
        }

        ChartUiState(
            featureWindows = chartWindows,
            currentHeartRate = heartRate,
            currentStepFreq = stepFreq
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ChartUiState()
    )
}

data class ChartUiState(
    val featureWindows: List<FeatureWindow> = emptyList(),
    val currentHeartRate: Int? = null,
    val currentStepFreq: Float? = null
)
