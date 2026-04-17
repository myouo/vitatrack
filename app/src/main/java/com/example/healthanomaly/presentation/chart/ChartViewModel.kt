package com.example.healthanomaly.presentation.chart

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.healthanomaly.core.BatchEvaluationManager
import com.example.healthanomaly.core.PlaybackSessionManager
import com.example.healthanomaly.domain.model.FeatureWindow
import com.example.healthanomaly.domain.repository.AnomalyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

private data class ChartLiveSnapshot(
    val featureWindows: List<FeatureWindow>,
    val isRunning: Boolean,
    val currentHeartRate: Int?,
    val currentStepFreq: Float?
)

@HiltViewModel
class ChartViewModel @Inject constructor(
    private val anomalyRepository: AnomalyRepository,
    private val playbackSessionManager: PlaybackSessionManager,
    private val batchEvaluationManager: BatchEvaluationManager
) : ViewModel() {

    private val playbackSnapshot = combine(
        playbackSessionManager.recentFeatureWindows,
        playbackSessionManager.isRunning,
        playbackSessionManager.currentHeartRate,
        playbackSessionManager.currentStepFreq
    ) { featureWindows, isRunning, currentHeartRate, currentStepFreq ->
        ChartLiveSnapshot(
            featureWindows = featureWindows,
            isRunning = isRunning,
            currentHeartRate = currentHeartRate,
            currentStepFreq = currentStepFreq
        )
    }

    private val batchSnapshot = combine(
        batchEvaluationManager.recentFeatureWindows,
        batchEvaluationManager.isRunning,
        batchEvaluationManager.currentHeartRate,
        batchEvaluationManager.currentStepFreq
    ) { featureWindows, isRunning, currentHeartRate, currentStepFreq ->
        ChartLiveSnapshot(
            featureWindows = featureWindows,
            isRunning = isRunning,
            currentHeartRate = currentHeartRate,
            currentStepFreq = currentStepFreq
        )
    }

    val state: StateFlow<ChartUiState> = combine(
        anomalyRepository.featureWindowsFlow,
        playbackSnapshot,
        batchSnapshot
    ) { persistedWindows, playbackSnapshot, batchSnapshot ->
        val chartWindows = when {
            batchSnapshot.isRunning || batchSnapshot.featureWindows.isNotEmpty() -> batchSnapshot.featureWindows
            playbackSnapshot.isRunning || playbackSnapshot.featureWindows.isNotEmpty() -> playbackSnapshot.featureWindows
            else -> persistedWindows.asReversed().takeLast(60)
        }

        ChartUiState(
            featureWindows = chartWindows,
            currentHeartRate = if (batchSnapshot.isRunning) {
                batchSnapshot.currentHeartRate
            } else {
                playbackSnapshot.currentHeartRate
            },
            currentStepFreq = if (batchSnapshot.isRunning) {
                batchSnapshot.currentStepFreq
            } else {
                playbackSnapshot.currentStepFreq
            }
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
