package com.example.healthanomaly.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.healthanomaly.core.Constants
import com.example.healthanomaly.core.NotificationHelper
import com.example.healthanomaly.domain.detection.AnomalyDetectionEngine
import com.example.healthanomaly.domain.detection.DetectionConfig
import com.example.healthanomaly.domain.model.AnomalyEvent
import com.example.healthanomaly.domain.model.FeatureWindow
import com.example.healthanomaly.domain.model.HeartRateData
import com.example.healthanomaly.domain.model.ImuData
import com.example.healthanomaly.domain.repository.AnomalyRepository
import com.example.healthanomaly.domain.repository.BleConnectionState
import com.example.healthanomaly.domain.usecase.CollectImuDataUseCase
import com.example.healthanomaly.domain.usecase.ProcessWindowUseCase
import com.example.healthanomaly.domain.usecase.StartBleScanUseCase
import dagger.hilt.android.AndroidEntryPoint
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
import javax.inject.Inject

/**
 * 数据采集与异常检测服务 - 毕业设计核心服务
 *
 * 功能：
 * 1. IMU 传感器数据采集（50Hz）
 * 2. BLE 心率数据采集
 * 3. 滑动窗口特征提取（2s 窗口，1s 步长）
 * 4. 实时异常检测（规则+模型融合）
 * 5. 批量数据库写入（节流优化）
 * 6. 异常片段缓存（前后 10 秒）
 * 7. 分级通知与震动反馈
 */
@AndroidEntryPoint
class DataCollectionService : LifecycleService() {

    @Inject lateinit var collectImuDataUseCase: CollectImuDataUseCase
    @Inject lateinit var startBleScanUseCase: StartBleScanUseCase
    @Inject lateinit var processWindowUseCase: ProcessWindowUseCase
    @Inject lateinit var anomalyRepository: AnomalyRepository
    @Inject lateinit var notificationHelper: NotificationHelper

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
    }

    private var windowProcessingJob: Job? = null
    private var batchWriteJob: Job? = null
    private var lastWindowTime = 0L
    private var lastHeartRate: Int? = null

    private val featureWindowBuffer = mutableListOf<FeatureWindow>()
    private val anomalyEventBuffer = mutableListOf<AnomalyEvent>()

    private val imuDataRingBuffer = ArrayDeque<ImuData>(1000)
    private val anomalySegmentCache = mutableMapOf<Long, List<ImuData>>()

    override fun onCreate() {
        super.onCreate()
        detectionEngine = AnomalyDetectionEngine(DetectionConfig())
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        observeData()
        startBatchWriteJob()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START -> startCollection()
            ACTION_STOP -> stopCollection()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun startCollection() {
        if (_isRunning.value) return

        _isRunning.value = true

        startForeground(
            Constants.FOREGROUND_NOTIFICATION_ID,
            notificationHelper.showForegroundNotification(
                "运动健康监测中",
                "正在采集传感器数据..."
            )
        )

        collectImuDataUseCase.startCollection()

        lifecycleScope.launch {
            detectionEngine.reset()
        }

        startWindowProcessing()
    }

    private fun stopCollection() {
        if (!_isRunning.value) return

        _isRunning.value = false

        collectImuDataUseCase.stopCollection()

        windowProcessingJob?.cancel()
        windowProcessingJob = null

        batchWriteJob?.cancel()
        batchWriteJob = null

        lifecycleScope.launch {
            flushBuffers()
        }

        notificationHelper.cancelForegroundNotification()

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun observeData() {
        lifecycleScope.launch {
            collectImuDataUseCase.observeImuData()
                .buffer(capacity = 100, onBufferOverflow = BufferOverflow.DROP_OLDEST)
                .collectLatest { imuData ->
                    synchronized(imuDataRingBuffer) {
                        if (imuDataRingBuffer.size >= 1000) {
                            imuDataRingBuffer.removeFirst()
                        }
                        imuDataRingBuffer.addLast(imuData)
                    }
                }
        }

        lifecycleScope.launch {
            startBleScanUseCase.observeHeartRate()
                .debounce(500)
                .collectLatest { hrData ->
                    _currentHeartRate.value = hrData.heartRateBpm
                    lastHeartRate = hrData.heartRateBpm
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
            while (isActive) {
                val now = System.currentTimeMillis()

                if (now - lastWindowTime >= ProcessWindowUseCase.STEP_SIZE_MS) {
                    processWindow(now)
                    lastWindowTime = now
                }

                delay(100)
            }
        }
    }

    private suspend fun processWindow(windowEndTime: Long) {
        val windowStartTime = windowEndTime - ProcessWindowUseCase.WINDOW_SIZE_MS

        val samples = synchronized(imuDataRingBuffer) {
            imuDataRingBuffer.filter { it.timestampMs >= windowStartTime }
        }

        if (samples.isEmpty()) return

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
        val segmentStart = anomalyTimestamp - 10_000
        val segmentEnd = anomalyTimestamp + 10_000

        val segment = synchronized(imuDataRingBuffer) {
            imuDataRingBuffer.filter { it.timestampMs in segmentStart..segmentEnd }
        }

        synchronized(anomalySegmentCache) {
            anomalySegmentCache[anomalyTimestamp] = segment

            if (anomalySegmentCache.size > 50) {
                val oldestKey = anomalySegmentCache.keys.minOrNull()
                oldestKey?.let { anomalySegmentCache.remove(it) }
            }
        }
    }

    private fun triggerNotificationAndVibration(anomaly: AnomalyEvent) {
        val (title, message, channelId) = when (anomaly.severity) {
            in 9..10 -> Triple(
                "严重异常警告",
                "${anomaly.type.displayName}: ${anomaly.details}",
                "anomaly_critical"
            )
            in 7..8 -> Triple(
                "异常警告",
                "${anomaly.type.displayName}: ${anomaly.details}",
                "anomaly_high"
            )
            in 5..6 -> Triple(
                "异常提示",
                "${anomaly.type.displayName}",
                "anomaly_medium"
            )
            else -> Triple(
                "异常检测",
                "${anomaly.type.displayName}",
                "anomaly_low"
            )
        }

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
            val copy = featureWindowBuffer.toList()
            featureWindowBuffer.clear()
            copy
        }

        val anomaliesToWrite = synchronized(anomalyEventBuffer) {
            val copy = anomalyEventBuffer.toList()
            anomalyEventBuffer.clear()
            copy
        }

        if (windowsToWrite.isNotEmpty()) {
            windowsToWrite.forEach { window ->
                anomalyRepository.insertFeatureWindow(window)
            }
        }

        if (anomaliesToWrite.isNotEmpty()) {
            anomaliesToWrite.forEach { anomaly ->
                anomalyRepository.insertAnomalyEvent(anomaly)
            }
        }
    }

    private fun updateNotification() {
        if (!_isRunning.value) return

        val hrText = _currentHeartRate.value?.let { "心率: $it BPM" } ?: "心率: --"
        val stepText = _currentStepFreq.value?.let {
            "步频: ${"%.1f".format(it)} Hz"
        } ?: "步频: --"

        notificationHelper.showForegroundNotification(
            "运动健康监测中",
            "$hrText | $stepText"
        )
    }

    override fun onDestroy() {
        stopCollection()
        super.onDestroy()
    }
}
