package com.example.healthanomaly.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.healthanomaly.core.Constants
import com.example.healthanomaly.core.NotificationHelper
import com.example.healthanomaly.domain.model.HeartRateData
import com.example.healthanomaly.domain.model.ImuData
import com.example.healthanomaly.domain.repository.BleConnectionState
import com.example.healthanomaly.domain.usecase.CollectImuDataUseCase
import com.example.healthanomaly.domain.usecase.DetectAnomalyUseCase
import com.example.healthanomaly.domain.usecase.ProcessWindowUseCase
import com.example.healthanomaly.domain.usecase.StartBleScanUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service for continuous data collection and anomaly detection.
 * Runs in background to collect IMU and BLE heart rate data.
 */
@AndroidEntryPoint
class DataCollectionService : LifecycleService() {
    
    @Inject lateinit var collectImuDataUseCase: CollectImuDataUseCase
    @Inject lateinit var startBleScanUseCase: StartBleScanUseCase
    @Inject lateinit var processWindowUseCase: ProcessWindowUseCase
    @Inject lateinit var detectAnomalyUseCase: DetectAnomalyUseCase
    @Inject lateinit var notificationHelper: NotificationHelper
    
    companion object {
        const val ACTION_START = "com.example.healthanomaly.START_SERVICE"
        const val ACTION_STOP = "com.example.healthanomaly.STOP_SERVICE"
        
        // State flow for UI binding
        private val _isRunning = MutableStateFlow(false)
        val isRunning = _isRunning.asStateFlow()
        
        private val _currentHeartRate = MutableStateFlow<Int?>(null)
        val currentHeartRate = _currentHeartRate.asStateFlow()
        
        private val _currentStepFreq = MutableStateFlow<Float?>(null)
        val currentStepFreq = _currentStepFreq.asStateFlow()
        
        private val _isBleConnected = MutableStateFlow(false)
        val isBleConnected = _isBleConnected.asStateFlow()
    }
    
    private var windowProcessingJob: Job? = null
    private var lastWindowTime = 0L
    private var lastHeartRate: Int? = null
    
    override fun onCreate() {
        super.onCreate()
        observeData()
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
    
    /**
     * Start data collection.
     */
    private fun startCollection() {
        if (_isRunning.value) return
        
        _isRunning.value = true
        
        // Start foreground service
        startForeground(
            Constants.FOREGROUND_NOTIFICATION_ID,
            notificationHelper.showForegroundNotification(
                "Health Monitoring Active",
                "Collecting sensor data..."
            )
        )
        
        // Start IMU collection
        collectImuDataUseCase.startCollection()
        
        // Reset detection state
        detectAnomalyUseCase.reset()
        
        // Start window processing loop
        startWindowProcessing()
    }
    
    /**
     * Stop data collection.
     */
    private fun stopCollection() {
        if (!_isRunning.value) return
        
        _isRunning.value = false
        
        // Stop IMU collection
        collectImuDataUseCase.stopCollection()
        
        // Stop window processing
        windowProcessingJob?.cancel()
        windowProcessingJob = null
        
        // Cancel notification
        notificationHelper.cancelForegroundNotification()
        
        // Stop foreground
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    
    /**
     * Observe incoming data streams.
     */
    private fun observeData() {
        // Observe IMU data
        lifecycleScope.launch {
            collectImuDataUseCase.observeImuData().collectLatest { imuData ->
                updateNotification(imuData)
            }
        }
        
        // Observe heart rate
        lifecycleScope.launch {
            startBleScanUseCase.observeHeartRate().collectLatest { hrData ->
                _currentHeartRate.value = hrData.heartRateBpm
                lastHeartRate = hrData.heartRateBpm
            }
        }
        
        // Observe BLE connection state
        lifecycleScope.launch {
            startBleScanUseCase.observeConnectionState().collectLatest { state ->
                _isBleConnected.value = state == BleConnectionState.CONNECTED
            }
        }
        
        // Observe detected anomalies
        lifecycleScope.launch {
            detectAnomalyUseCase.detectedAnomaly.collectLatest { event ->
                event?.let {
                    notificationHelper.showAnomalyAlert(it)
                }
            }
        }
    }
    
    /**
     * Process sliding windows at regular intervals.
     */
    private fun startWindowProcessing() {
        windowProcessingJob = lifecycleScope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                
                // Process window if enough time has passed
                if (now - lastWindowTime >= ProcessWindowUseCase.STEP_SIZE_MS) {
                    processWindow(now)
                    lastWindowTime = now
                }
                
                delay(100) // Check every 100ms
            }
        }
    }
    
    /**
     * Process a single window of data.
     */
    private suspend fun processWindow(windowEndTime: Long) {
        // Get samples for this window
        val windowStartTime = windowEndTime - ProcessWindowUseCase.WINDOW_SIZE_MS
        val samples = collectImuDataUseCase.getRecentSamples(ProcessWindowUseCase.WINDOW_SIZE_MS)
            .filter { it.timestampMs >= windowStartTime }
        
        if (samples.isEmpty()) return
        
        // Extract features
        val window = processWindowUseCase.extractFeatures(
            samples = samples,
            windowEndTimestamp = windowEndTime,
            heartRateBpm = lastHeartRate
        )
        
        // Update current step frequency for UI
        _currentStepFreq.value = window.stepFreqHz
        
        // Save to database
        processWindowUseCase.saveFeatureWindow(window)
        
        // Run anomaly detection
        detectAnomalyUseCase.analyzeWindow(window)
    }
    
    /**
     * Update foreground notification with current data.
     */
    private fun updateNotification(imuData: ImuData) {
        if (!_isRunning.value) return
        
        val hrText = _currentHeartRate.value?.let { "HR: $it BPM" } ?: "HR: --"
        val stepText = _currentStepFreq.value?.let { 
            "Step: ${"%.1f".format(it)} Hz" 
        } ?: "Step: --"
        
        notificationHelper.showForegroundNotification(
            "Health Monitoring Active",
            "$hrText | $stepText"
        )
    }
    
    override fun onDestroy() {
        stopCollection()
        super.onDestroy()
    }
}
