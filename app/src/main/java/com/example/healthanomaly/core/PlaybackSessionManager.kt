package com.example.healthanomaly.core

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import com.example.healthanomaly.R
import com.example.healthanomaly.data.stream.FilePlaybackManager
import com.example.healthanomaly.domain.detection.AnomalyDetectionEngine
import com.example.healthanomaly.domain.detection.DetectionConfig
import com.example.healthanomaly.domain.model.AnomalyEvent
import com.example.healthanomaly.domain.model.FeatureWindow
import com.example.healthanomaly.domain.model.ImuData
import com.example.healthanomaly.domain.repository.AnomalyRepository
import com.example.healthanomaly.domain.repository.BleConnectionState
import com.example.healthanomaly.domain.usecase.ProcessWindowUseCase
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Singleton
class PlaybackSessionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val filePlaybackManager: FilePlaybackManager,
    private val processWindowUseCase: ProcessWindowUseCase,
    private val anomalyRepository: AnomalyRepository,
    private val notificationHelper: NotificationHelper,
    private val preferencesManager: PreferencesManager
) {
    companion object {
        private const val MAX_LIVE_WINDOWS = 60
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val playbackExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        scope.launch {
            handlePlaybackFailure(throwable)
        }
    }
    private val vibrator: Vibrator? =
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _currentHeartRate = MutableStateFlow<Int?>(null)
    val currentHeartRate: StateFlow<Int?> = _currentHeartRate.asStateFlow()

    private val _currentStepFreq = MutableStateFlow<Float?>(null)
    val currentStepFreq: StateFlow<Float?> = _currentStepFreq.asStateFlow()

    private val _recentFeatureWindows = MutableStateFlow<List<FeatureWindow>>(emptyList())
    val recentFeatureWindows: StateFlow<List<FeatureWindow>> = _recentFeatureWindows.asStateFlow()

    private val _isSourceActive = MutableStateFlow(false)
    val isSourceActive: StateFlow<Boolean> = _isSourceActive.asStateFlow()

    private val _sourceName = MutableStateFlow<String?>(null)
    val sourceName: StateFlow<String?> = _sourceName.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _detectedAnomalies = MutableSharedFlow<AnomalyEvent>(
        replay = 0,
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val detectedAnomalies = _detectedAnomalies.asSharedFlow()

    private var activeDetectionConfig = buildDetectionConfig()
    private var detectionEngine = AnomalyDetectionEngine(activeDetectionConfig)
    private var windowProcessingJob: Job? = null
    private var batchWriteJob: Job? = null
    private var lastWindowTime = 0L
    private var lastHeartRate: Int? = null
    private var latestSampleTimestampMs = 0L

    private val featureWindowBuffer = mutableListOf<FeatureWindow>()
    private val imuDataRingBuffer = ArrayDeque<ImuData>(1000)

    init {
        observePlaybackStreams()
    }

    suspend fun loadAndStart(uri: Uri, displayName: String?) {
        stopPlayback()

        val loadResult = runCatching {
            filePlaybackManager.loadStream(uri, displayName)
        }.getOrElse { error ->
            _errorMessage.value = error.message ?: "Unable to load stream file"
            return
        }

        runCatching {
            anomalyRepository.clearAllData()
            resetSessionState()
            refreshDetectionEngine(force = true)
            _sourceName.value = loadResult.displayName
            _errorMessage.value = null
            _isRunning.value = true

            startBatchWriteJob()
            filePlaybackManager.startPlayback()
            startWindowProcessing()
        }.onFailure { throwable ->
            handlePlaybackFailure(throwable)
        }
    }

    suspend fun stopPlayback() {
        runCatching {
            internalStopPlayback(flushRemaining = true)
        }.onFailure { throwable ->
            _errorMessage.value = throwable.message ?: context.getString(R.string.playback_failed)
            filePlaybackManager.stopPlayback()
            resetSessionState()
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    private fun observePlaybackStreams() {
        scope.launch(playbackExceptionHandler) {
            filePlaybackManager.imuDataFlow
                .buffer(capacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST)
                .collectLatest { imuData ->
                    if (!_isRunning.value) {
                        return@collectLatest
                    }

                    latestSampleTimestampMs = imuData.timestampMs
                    synchronized(imuDataRingBuffer) {
                        if (imuDataRingBuffer.size >= 1000) {
                            imuDataRingBuffer.removeFirst()
                        }
                        imuDataRingBuffer.addLast(imuData)
                    }
                }
        }

        scope.launch(playbackExceptionHandler) {
            filePlaybackManager.heartRateFlow.collectLatest { heartRateData ->
                if (!_isRunning.value) {
                    return@collectLatest
                }

                _currentHeartRate.value = heartRateData.heartRateBpm
                lastHeartRate = heartRateData.heartRateBpm
            }
        }

        scope.launch(playbackExceptionHandler) {
            filePlaybackManager.connectionStateFlow.collectLatest { state ->
                _isSourceActive.value = state == BleConnectionState.CONNECTED
            }
        }

        scope.launch(playbackExceptionHandler) {
            filePlaybackManager.playbackCompletedFlow.collectLatest {
                if (_isRunning.value) {
                    delay(75)
                    finalizeCompletedPlayback()
                }
            }
        }

        scope.launch(playbackExceptionHandler) {
            filePlaybackManager.playbackErrorFlow.collectLatest { message ->
                if (_isRunning.value) {
                    handlePlaybackFailure(IllegalStateException(message))
                } else {
                    _errorMessage.value = message
                }
            }
        }
    }

    private fun startWindowProcessing() {
        windowProcessingJob?.cancel()
        windowProcessingJob = scope.launch(playbackExceptionHandler) {
            while (isActive && _isRunning.value) {
                processAvailableWindows(latestSampleTimestampMs)
                delay(50)
            }
        }
    }

    private suspend fun processWindow(windowEndTime: Long) {
        val windowStartTime = windowEndTime - ProcessWindowUseCase.WINDOW_SIZE_MS
        val samples = synchronized(imuDataRingBuffer) {
            imuDataRingBuffer.filter { it.timestampMs in windowStartTime..windowEndTime }
        }
        if (samples.isEmpty()) {
            return
        }

        val window = processWindowUseCase.extractFeatures(
            samples = samples,
            windowEndTimestamp = windowEndTime,
            heartRateBpm = lastHeartRate
        )

        _currentStepFreq.value = window.stepFreqHz
        appendRecentWindow(window)
        synchronized(featureWindowBuffer) {
            featureWindowBuffer.add(window)
        }

        refreshDetectionEngine()
        val anomalies = detectionEngine.detect(window)
        if (anomalies.isEmpty()) {
            return
        }

        anomalies.forEach { anomaly ->
            persistAndAlert(anomaly)
        }
    }

    private fun startBatchWriteJob() {
        batchWriteJob?.cancel()
        batchWriteJob = scope.launch(playbackExceptionHandler) {
            while (isActive && _isRunning.value) {
                delay(5000)
                flushBuffers()
            }
        }
    }

    private suspend fun flushBuffers() {
        val windowsToWrite = synchronized(featureWindowBuffer) {
            featureWindowBuffer.toList().also { featureWindowBuffer.clear() }
        }

        if (windowsToWrite.isEmpty()) {
            return
        }

        windowsToWrite.forEach { window ->
            anomalyRepository.insertFeatureWindow(window)
        }
    }

    private suspend fun persistAndAlert(anomaly: AnomalyEvent) {
        val persistedEvent = withContext(Dispatchers.IO) {
            val eventWithSystemTime = anomaly.copy(timestampMs = System.currentTimeMillis())
            val eventId = anomalyRepository.insertAnomalyEvent(eventWithSystemTime)
            eventWithSystemTime.copy(id = eventId)
        }

        _detectedAnomalies.emit(persistedEvent)
        triggerAlert(persistedEvent)
    }

    private fun triggerAlert(anomaly: AnomalyEvent) {
        runCatching {
            notificationHelper.showAnomalyAlert(anomaly)

            val localVibrator = vibrator ?: return
            if (!localVibrator.hasVibrator()) {
                return
            }

            val pattern = when (anomaly.severity) {
                10 -> longArrayOf(0, 500, 200, 500, 200, 500)
                9 -> longArrayOf(0, 400, 200, 400)
                8 -> longArrayOf(0, 300, 200, 300)
                6, 7 -> longArrayOf(0, 220, 120, 220)
                else -> longArrayOf(0, 180, 100, 180)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                localVibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                localVibrator.vibrate(pattern, -1)
            }
        }.onFailure { throwable ->
            _errorMessage.value = throwable.message ?: context.getString(R.string.playback_failed)
        }
    }

    private fun buildDetectionConfig(): DetectionConfig {
        return DetectionConfig(
            hrHighThreshold = preferencesManager.getHrHighThreshold(),
            hrLowThreshold = preferencesManager.getHrLowThreshold(),
            stepFreqHighThreshold = preferencesManager.getStepFreqHighThreshold(),
            stepFreqLowThreshold = preferencesManager.getStepFreqLowThreshold(),
            fallDetectionEnabled = preferencesManager.isFallDetectionEnabled()
        )
    }

    private fun refreshDetectionEngine(force: Boolean = false) {
        val latestConfig = buildDetectionConfig()
        if (!force && latestConfig == activeDetectionConfig) {
            return
        }

        activeDetectionConfig = latestConfig
        detectionEngine = AnomalyDetectionEngine(activeDetectionConfig)
    }

    private fun resetSessionState() {
        lastWindowTime = 0L
        lastHeartRate = null
        latestSampleTimestampMs = 0L

        synchronized(featureWindowBuffer) {
            featureWindowBuffer.clear()
        }
        synchronized(imuDataRingBuffer) {
            imuDataRingBuffer.clear()
        }

        clearRuntimeState()
    }

    private fun clearRuntimeState() {
        _currentHeartRate.value = null
        _currentStepFreq.value = null
        _isSourceActive.value = false
        _recentFeatureWindows.value = emptyList()
    }

    private fun appendRecentWindow(window: FeatureWindow) {
        _recentFeatureWindows.value = (_recentFeatureWindows.value + window).takeLast(MAX_LIVE_WINDOWS)
    }

    private suspend fun processAvailableWindows(latestTimestamp: Long) {
        val windowSizeMs = ProcessWindowUseCase.WINDOW_SIZE_MS
        val stepSizeMs = ProcessWindowUseCase.STEP_SIZE_MS

        if (latestTimestamp < windowSizeMs) {
            return
        }

        if (lastWindowTime == 0L) {
            lastWindowTime = windowSizeMs - stepSizeMs
        }

        while (latestTimestamp - lastWindowTime >= stepSizeMs) {
            lastWindowTime += stepSizeMs
            processWindow(lastWindowTime)
        }
    }

    private suspend fun internalStopPlayback(flushRemaining: Boolean) {
        _isRunning.value = false
        filePlaybackManager.stopPlayback()

        windowProcessingJob?.cancelAndJoin()
        windowProcessingJob = null

        batchWriteJob?.cancelAndJoin()
        batchWriteJob = null

        if (flushRemaining) {
            flushBuffers()
        }
        resetSessionState()
    }

    private suspend fun finalizeCompletedPlayback() {
        processAvailableWindows(latestSampleTimestampMs)
        internalStopPlayback(flushRemaining = true)
    }

    private suspend fun handlePlaybackFailure(throwable: Throwable) {
        _errorMessage.value = throwable.message ?: context.getString(R.string.playback_failed)
        runCatching {
            internalStopPlayback(flushRemaining = false)
        }.onFailure {
            filePlaybackManager.stopPlayback()
            resetSessionState()
        }
    }
}
