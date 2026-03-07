package com.example.healthanomaly.service

import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.healthanomaly.core.Constants
import com.example.healthanomaly.core.NotificationHelper
import com.example.healthanomaly.core.PreferencesManager
import com.example.healthanomaly.domain.detection.AnomalyDetectionEngine
import com.example.healthanomaly.domain.detection.DetectionConfig
import com.example.healthanomaly.domain.model.AnomalyEvent
import com.example.healthanomaly.domain.model.FeatureWindow
import com.example.healthanomaly.domain.model.ImuData
import com.example.healthanomaly.domain.repository.AnomalyRepository
import com.example.healthanomaly.domain.repository.BleConnectionState
import com.example.healthanomaly.domain.usecase.CollectImuDataUseCase
import com.example.healthanomaly.domain.usecase.ProcessWindowUseCase
import com.example.healthanomaly.domain.usecase.StartBleScanUseCase
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that replays file-based samples as a simulated real-time stream.
 */
@AndroidEntryPoint
class DataCollectionService : LifecycleService() {

    @Inject lateinit var collectImuDataUseCase: CollectImuDataUseCase
    @Inject lateinit var startBleScanUseCase: StartBleScanUseCase
    @Inject lateinit var processWindowUseCase: ProcessWindowUseCase
    @Inject lateinit var anomalyRepository: AnomalyRepository
    @Inject lateinit var notificationHelper: NotificationHelper
    @Inject lateinit var preferencesManager: PreferencesManager

    private lateinit var detectionEngine: AnomalyDetectionEngine
    private lateinit var vibrator: Vibrator

    companion object {
        const val ACTION_START = "com.example.healthanomaly.START_SERVICE"
        const val ACTION_STOP = "com.example.healthanomaly.STOP_SERVICE"

        private val _isRunning = MutableStateFlow(false)
        val isRunning = _isRunning.asStateFlow()

        private val _currentHeartRate = MutableStateFlow<Int?>(null)
        val currentHeartRate = _currentHeartRate.asStateFlow()

        private val _currentStepFreq = MutableStateFlow<Float?>(null)
        val currentStepFreq = _currentStepFreq.asStateFlow()

        private val _isBleConnected = MutableStateFlow(false)
        val isBleConnected = _isBleConnected.asStateFlow()

        private val _detectedAnomalies = MutableSharedFlow<AnomalyEvent>(
            replay = 1,
            extraBufferCapacity = 10,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        val detectedAnomalies = _detectedAnomalies.asSharedFlow()

        fun resetRuntimeState() {
            _isRunning.value = false
            _currentHeartRate.value = null
            _currentStepFreq.value = null
            _isBleConnected.value = false
        }
    }

    private var windowProcessingJob: Job? = null
    private var batchWriteJob: Job? = null
    private var lastWindowTime = 0L
    private var lastHeartRate: Int? = null
    private var latestSampleTimestampMs = 0L

    private val featureWindowBuffer = mutableListOf<FeatureWindow>()
    private val anomalyEventBuffer = mutableListOf<AnomalyEvent>()
    private val imuDataRingBuffer = ArrayDeque<ImuData>(1000)
    private val anomalySegmentCache = mutableMapOf<Long, List<ImuData>>()

    override fun onCreate() {
        super.onCreate()
        detectionEngine = AnomalyDetectionEngine(buildDetectionConfig())
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        observeData()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START -> startCollection()
            ACTION_STOP -> stopCollection()
            null -> {
                clearCollectionState()
                stopSelf()
            }
            else -> {
                clearCollectionState()
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun startCollection() {
        if (_isRunning.value) {
            return
        }

        runCatching {
            _isRunning.value = true
            preferencesManager.setCollectionEnabled(true)
            resetSessionState()
            detectionEngine = AnomalyDetectionEngine(buildDetectionConfig())

            startForeground(
                Constants.FOREGROUND_NOTIFICATION_ID,
                notificationHelper.showForegroundNotification(
                    "VitaTrack playback active",
                    "Streaming simulated samples from file"
                )
            )
            if (batchWriteJob == null) {
                startBatchWriteJob()
            }

            collectImuDataUseCase.startCollection()
            startWindowProcessing()
        }.onFailure {
            clearCollectionState()
            notificationHelper.cancelForegroundNotification()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopCollection() {
        if (!_isRunning.value) {
            collectImuDataUseCase.stopCollection()
            windowProcessingJob?.cancel()
            windowProcessingJob = null
            batchWriteJob?.cancel()
            batchWriteJob = null
            clearCollectionState()
            return
        }

        _isRunning.value = false
        preferencesManager.setCollectionEnabled(false)
        collectImuDataUseCase.stopCollection()

        windowProcessingJob?.cancel()
        windowProcessingJob = null

        batchWriteJob?.cancel()
        batchWriteJob = null

        _currentHeartRate.value = null
        _currentStepFreq.value = null
        _isBleConnected.value = false

        lifecycleScope.launch {
            flushBuffers()
            notificationHelper.cancelForegroundNotification()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun observeData() {
        lifecycleScope.launch {
            collectImuDataUseCase.observeImuData()
                .buffer(capacity = 100, onBufferOverflow = BufferOverflow.DROP_OLDEST)
                .collectLatest { imuData ->
                    latestSampleTimestampMs = imuData.timestampMs

                    synchronized(imuDataRingBuffer) {
                        if (imuDataRingBuffer.size >= 1000) {
                            imuDataRingBuffer.removeFirst()
                        }
                        imuDataRingBuffer.addLast(imuData)
                    }

                    updateNotification()
                }
        }

        lifecycleScope.launch {
            startBleScanUseCase.observeHeartRate()
                .debounce(500)
                .collectLatest { hrData ->
                    _currentHeartRate.value = hrData.heartRateBpm
                    lastHeartRate = hrData.heartRateBpm
                    updateNotification()
                }
        }

        lifecycleScope.launch {
            startBleScanUseCase.observeConnectionState().collectLatest { state ->
                _isBleConnected.value = state == BleConnectionState.CONNECTED
            }
        }
    }

    private fun startWindowProcessing() {
        windowProcessingJob = lifecycleScope.launch {
            val windowSizeMs = ProcessWindowUseCase.WINDOW_SIZE_MS
            val stepSizeMs = ProcessWindowUseCase.STEP_SIZE_MS

            while (isActive) {
                val latestTimestamp = latestSampleTimestampMs

                if (latestTimestamp >= windowSizeMs) {
                    if (lastWindowTime == 0L) {
                        lastWindowTime = windowSizeMs - stepSizeMs
                    }

                    while (latestTimestamp - lastWindowTime >= stepSizeMs) {
                        lastWindowTime += stepSizeMs
                        processWindow(lastWindowTime)
                    }
                }

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

        synchronized(featureWindowBuffer) {
            featureWindowBuffer.add(window)
        }

        val anomalies = detectionEngine.detect(window)
        if (anomalies.isNotEmpty()) {
            handleAnomalies(anomalies, windowEndTime)
        }
    }

    private suspend fun handleAnomalies(anomalies: List<AnomalyEvent>, timestamp: Long) {
        anomalies.forEach { anomaly ->
            synchronized(anomalyEventBuffer) {
                anomalyEventBuffer.add(anomaly)
            }

            _detectedAnomalies.emit(anomaly)
            cacheAnomalySegment(timestamp)
            triggerNotificationAndVibration(anomaly)
        }
    }

    private fun cacheAnomalySegment(anomalyTimestamp: Long) {
        val segmentStart = anomalyTimestamp - 10_000L
        val segmentEnd = anomalyTimestamp + 10_000L

        val segment = synchronized(imuDataRingBuffer) {
            imuDataRingBuffer.filter { it.timestampMs in segmentStart..segmentEnd }
        }

        synchronized(anomalySegmentCache) {
            anomalySegmentCache[anomalyTimestamp] = segment

            if (anomalySegmentCache.size > 50) {
                anomalySegmentCache.keys.minOrNull()?.let { oldestKey ->
                    anomalySegmentCache.remove(oldestKey)
                }
            }
        }
    }

    private fun triggerNotificationAndVibration(anomaly: AnomalyEvent) {
        notificationHelper.showAnomalyAlert(anomaly)

        if (anomaly.severity >= 7 && vibrator.hasVibrator()) {
            val pattern = when (anomaly.severity) {
                10 -> longArrayOf(0, 500, 200, 500, 200, 500)
                9 -> longArrayOf(0, 400, 200, 400)
                8 -> longArrayOf(0, 300, 200, 300)
                else -> longArrayOf(0, 200)
            }

            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }
    }

    private fun startBatchWriteJob() {
        batchWriteJob = lifecycleScope.launch {
            while (isActive) {
                delay(5000)
                flushBuffers()
            }
        }
    }

    private suspend fun flushBuffers() {
        val windowsToWrite = synchronized(featureWindowBuffer) {
            featureWindowBuffer.toList().also { featureWindowBuffer.clear() }
        }

        val anomaliesToWrite = synchronized(anomalyEventBuffer) {
            anomalyEventBuffer.toList().also { anomalyEventBuffer.clear() }
        }

        windowsToWrite.forEach { window ->
            anomalyRepository.insertFeatureWindow(window)
        }

        anomaliesToWrite.forEach { anomaly ->
            anomalyRepository.insertAnomalyEvent(anomaly)
        }
    }

    private fun updateNotification() {
        if (!_isRunning.value) {
            return
        }

        val hrText = _currentHeartRate.value?.let { "HR: $it BPM" } ?: "HR: --"
        val stepText = _currentStepFreq.value?.let { "Step: ${"%.1f".format(it)} Hz" } ?: "Step: --"

        notificationHelper.showForegroundNotification(
            "VitaTrack playback active",
            "$hrText | $stepText"
        )
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

    private fun resetSessionState() {
        lastWindowTime = 0L
        lastHeartRate = null
        latestSampleTimestampMs = 0L
        _currentHeartRate.value = null
        _currentStepFreq.value = null
        _isBleConnected.value = false

        synchronized(featureWindowBuffer) {
            featureWindowBuffer.clear()
        }
        synchronized(anomalyEventBuffer) {
            anomalyEventBuffer.clear()
        }
        synchronized(imuDataRingBuffer) {
            imuDataRingBuffer.clear()
        }
        synchronized(anomalySegmentCache) {
            anomalySegmentCache.clear()
        }
    }

    private fun clearCollectionState() {
        collectImuDataUseCase.stopCollection()
        windowProcessingJob?.cancel()
        windowProcessingJob = null
        batchWriteJob?.cancel()
        batchWriteJob = null
        resetRuntimeState()
        preferencesManager.setCollectionEnabled(false)
        resetSessionState()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopCollection()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopCollection()
        super.onDestroy()
    }
}
