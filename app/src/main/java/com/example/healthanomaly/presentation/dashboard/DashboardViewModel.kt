package com.example.healthanomaly.presentation.dashboard

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.healthanomaly.core.BatchEvaluationManager
import com.example.healthanomaly.data.dataset.BatchEvaluationReport
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private data class DashboardBatchProgress(
    val datasetName: String?,
    val currentSampleName: String?,
    val completedSamples: Int,
    val totalSamples: Int,
    val hasResults: Boolean
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val batchEvaluationManager: BatchEvaluationManager
) : ViewModel() {

    val completedReports: SharedFlow<BatchEvaluationReport> = batchEvaluationManager.completedReports

    private val runtimeState = combine(
        batchEvaluationManager.isRunning,
        batchEvaluationManager.currentHeartRate,
        batchEvaluationManager.currentStepFreq,
        batchEvaluationManager.isSourceActive
    ) { isRunning, heartRate, stepFreq, isSourceActive ->
        DashboardUiState(
            isCollecting = isRunning,
            currentHeartRate = heartRate,
            currentStepFreq = stepFreq,
            isSourceActive = isSourceActive
        )
    }

    private val progressState = combine(
        batchEvaluationManager.datasetName,
        batchEvaluationManager.currentSampleName,
        batchEvaluationManager.completedSamples,
        batchEvaluationManager.totalSamples,
        batchEvaluationManager.latestReport
    ) { datasetName, currentSampleName, completedSamples, totalSamples, latestReport ->
        DashboardBatchProgress(
            datasetName = datasetName,
            currentSampleName = currentSampleName,
            completedSamples = completedSamples,
            totalSamples = totalSamples,
            hasResults = latestReport != null
        )
    }

    val state: StateFlow<DashboardUiState> = combine(
        runtimeState,
        progressState,
        batchEvaluationManager.errorMessage
    ) { runtimeState, progressState, errorMessage ->
        runtimeState.copy(
            datasetName = progressState.datasetName,
            currentSampleName = progressState.currentSampleName,
            completedSamples = progressState.completedSamples,
            totalSamples = progressState.totalSamples,
            hasResults = progressState.hasResults,
            error = errorMessage
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DashboardUiState()
    )

    fun loadDatasetFolder(uri: Uri) {
        viewModelScope.launch {
            batchEvaluationManager.startFromFolder(uri)
        }
    }

    fun loadDatasetZip(uri: Uri) {
        viewModelScope.launch {
            batchEvaluationManager.startFromZip(uri)
        }
    }

    fun stopEvaluation() {
        viewModelScope.launch {
            batchEvaluationManager.stopEvaluation()
        }
    }

    fun clearError() {
        batchEvaluationManager.clearError()
    }
}

data class DashboardUiState(
    val isCollecting: Boolean = false,
    val currentHeartRate: Int? = null,
    val currentStepFreq: Float? = null,
    val isSourceActive: Boolean = false,
    val datasetName: String? = null,
    val currentSampleName: String? = null,
    val completedSamples: Int = 0,
    val totalSamples: Int = 0,
    val hasResults: Boolean = false,
    val error: String? = null
)
