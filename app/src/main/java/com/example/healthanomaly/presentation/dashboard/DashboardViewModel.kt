package com.example.healthanomaly.presentation.dashboard

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.healthanomaly.core.PlaybackSessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val playbackSessionManager: PlaybackSessionManager
) : ViewModel() {

    val state: StateFlow<DashboardUiState> = combine(
        combine(
            playbackSessionManager.isRunning,
            playbackSessionManager.currentHeartRate,
            playbackSessionManager.currentStepFreq,
            playbackSessionManager.isSourceActive,
            playbackSessionManager.sourceName
        ) { isRunning, heartRate, stepFreq, isSourceActive, sourceName ->
            DashboardUiState(
                isCollecting = isRunning,
                currentHeartRate = heartRate,
                currentStepFreq = stepFreq,
                isSourceActive = isSourceActive,
                sourceName = sourceName
            )
        },
        playbackSessionManager.errorMessage
    ) { uiState, errorMessage ->
        uiState.copy(error = errorMessage)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DashboardUiState()
    )

    fun loadStreamFile(uri: Uri, displayName: String?) {
        viewModelScope.launch {
            playbackSessionManager.loadAndStart(uri, displayName)
        }
    }

    fun stopPlayback() {
        viewModelScope.launch {
            playbackSessionManager.stopPlayback()
        }
    }

    fun clearError() {
        playbackSessionManager.clearError()
    }
}

data class DashboardUiState(
    val isCollecting: Boolean = false,
    val currentHeartRate: Int? = null,
    val currentStepFreq: Float? = null,
    val isSourceActive: Boolean = false,
    val sourceName: String? = null,
    val error: String? = null
)
