
package com.example.healthanomaly.domain.usecase

import com.example.healthanomaly.domain.model.AnomalyEvent
import com.example.healthanomaly.domain.model.AnomalyType
import com.example.healthanomaly.domain.model.FeatureWindow
import com.example.healthanomaly.domain.repository.AnomalyRepository
import com.example.healthanomaly.core.PreferencesManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import kotlin.math.abs

/**
 * Use case for detecting anomalies using hybrid rule-based + statistical model approach.
 * 
 * Rules:
 * - HR > 150 or HR < 40: immediate alert
 * - HR change > 30 BPM in 5s: alert
 * - Step freq > 3 Hz or < 0.5 Hz: alert
 * - Impact detection (accel > 20 m/s²) + stationary = fall
 * 
 * Model:
 * - EWMA for HR baseline (5 min window)
 * - Z-score: |z| > 3 triggers alert
 * 
 * Cooldown: 60s between same type alerts
 */
class DetectAnomalyUseCase @Inject constructor(
    private val anomalyRepository: AnomalyRepository,
    private val preferencesManager: PreferencesManager
) {
    companion object {
        const val COOLDOWN_MS = 60_000L // 60 seconds
        const val EWMA_WINDOW_MS = 300_000L // 5 minutes
        const val Z_SCORE_THRESHOLD = 3.0
    }
    
    // EWMA state for heart rate
    private val ewmaAlpha = 0.1 // Smoothing factor
    private var ewmaHr: Double? = null
    private var lastHrTimestamp: Long = 0
    
    // Cooldown tracking
    private val lastAnomalyTime = mutableMapOf<AnomalyType, Long>()
    
    // Previous values for change detection
    private var lastHr: Int? = null
    private var lastHrChangeTime: Long = 0
    private var lastStepFreq: Float? = null
    
    // State flow for detected anomalies
    private val _detectedAnomaly = MutableStateFlow<AnomalyEvent?>(null)
    val detectedAnomaly: StateFlow<AnomalyEvent?> = _detectedAnomaly
    
    /**
     * Analyze a feature window and detect anomalies.
     * 
     * @return List of detected anomalies (may be empty)
     */
    suspend fun analyzeWindow(window: FeatureWindow): List<AnomalyEvent> {
        val anomalies = mutableListOf<AnomalyEvent>()
        val now = System.currentTimeMillis()
        
        // Get thresholds from preferences
        val hrHighThreshold = preferencesManager.getHrHighThreshold()
        val hrLowThreshold = preferencesManager.getHrLowThreshold()
        val stepFreqHighThreshold = preferencesManager.getStepFreqHighThreshold()
        val stepFreqLowThreshold = preferencesManager.getStepFreqLowThreshold()
        val fallDetectionEnabled = preferencesManager.isFallDetectionEnabled()
        
        // Rule 1: Heart rate absolute thresholds
        window.heartRateBpm?.let { hr ->
            if (hr > hrHighThreshold) {
                addAnomalyIfNotCooldown(
                    anomalies, now,
                    AnomalyType.HEART_RATE_HIGH,
                    severity = 8,
                    details = "Heart rate too high: $hr BPM (threshold: $hrHighThreshold)"
                )
            }
            if (hr < hrLowThreshold) {
                addAnomalyIfNotCooldown(
                    anomalies, now,
                    AnomalyType.HEART_RATE_LOW,
                    severity = 9,
                    details = "Heart rate too low: $hr BPM (threshold: $hrLowThreshold)"
                )
            }
            
            // Rule 2: Sudden HR change
            lastHr?.let { prevHr ->
                val timeDiff = now - lastHrChangeTime
                if (timeDiff < 5000) { // Within 5 seconds
                    val change = abs(hr - prevHr)
                    if (change > 30) {
                        addAnomalyIfNotCooldown(
                            anomalies, now,
                            AnomalyType.HEART_RATE_SUDDEN_CHANGE,
                            severity = 7,
                            details = "Sudden HR change: $prevHr → $hr BPM (${change} BPM in ${timeDiff}ms)"
                        )
                    }
                }
            }
            lastHr = hr
            lastHrChangeTime = now
            
            // Model: EWMA + Z-score
            analyzeHrWithModel(hr, now, anomalies)
        }
        
        // Rule 3: Step frequency thresholds
        if (window.stepFreqHz > 0) {
            if (window.stepFreqHz > stepFreqHighThreshold) {
                addAnomalyIfNotCooldown(
                    anomalies, now,
                    AnomalyType.STEP_FREQ_HIGH,
                    severity = 5,
                    details = "Step frequency too high: ${"%.2f".format(window.stepFreqHz)} Hz"
                )
            }
            if (window.stepFreqHz < stepFreqLowThreshold && window.stepFreqHz > 0) {
                addAnomalyIfNotCooldown(
                    anomalies, now,
                    AnomalyType.STEP_FREQ_LOW,
                    severity = 5,
                    details = "Step frequency too low: ${"%.2f".format(window.stepFreqHz)} Hz"
                )
            }
            
            // Rule 4: Sudden gait change
            lastStepFreq?.let { prevFreq ->
                val change = abs(window.stepFreqHz - prevFreq)
                if (change > 1.0) { // Significant change
                    addAnomalyIfNotCooldown(
                        anomalies, now,
                        AnomalyType.GAIT_SUDDEN_CHANGE,
                        severity = 6,
                        details = "Sudden gait change: ${"%.2f".format(prevFreq)} → ${"%.2f".format(window.stepFreqHz)} Hz"
                    )
                }
            }
            lastStepFreq = window.stepFreqHz
        }
        
        // Rule 5: Motion intensity anomaly
        if (window.rmsAccel > 15) {
            addAnomalyIfNotCooldown(
                anomalies, now,
                AnomalyType.MOTION_INTENSITY_ANOMALY,
                severity = 4,
                details = "High motion intensity: RMS ${"%.2f".format(window.rmsAccel)} m/s²"
            )
        }
        
        // Rule 6: Fall detection (impact + stationary)
        if (fallDetectionEnabled && window.maxAccel > 20) {
            // High impact detected - check next window for stationary
            // This is simplified - real implementation would track multiple windows
            addAnomalyIfNotCooldown(
                anomalies, now,
                AnomalyType.FALL_DETECTED,
                severity = 10,
                details = "Fall detected: impact ${"%.1f".format(window.maxAccel)} m/s²"
            )
        }
        
        // Save anomalies to database
        anomalies.forEach { anomaly ->
            anomalyRepository.insertAnomalyEvent(anomaly)
        }
        
        // Update state flow with most severe anomaly
        if (anomalies.isNotEmpty()) {
            val mostSevere = anomalies.maxByOrNull { it.severity }!!
            _detectedAnomaly.value = mostSevere
        }
        
        return anomalies
    }
    
    /**
     * Statistical model analysis using EWMA and z-score.
     */
    private fun analyzeHrWithModel(
        hr: Int,
        timestamp: Long,
        anomalies: MutableList<AnomalyEvent>
    ) {
        // Update EWMA
        ewmaHr = if (ewmaHr == null) {
            hr.toDouble()
        } else {
            ewmaAlpha * hr + (1 - ewmaAlpha) * ewmaHr!!
        }
        
        // Calculate z-score if we have enough data
        ewmaHr?.let { mean ->
            // Simple variance estimation
            val diff = hr - mean
            // Using fixed std for simplicity (real impl would track variance)
            val std = 15.0 
            val zScore = diff / std
            
            if (abs(zScore) > Z_SCORE_THRESHOLD) {
                val now = System.currentTimeMillis()
                addAnomalyIfNotCooldown(
                    anomalies, now,
                    if (zScore > 0) AnomalyType.HEART_RATE_HIGH else AnomalyType.HEART_RATE_LOW,
                    severity = 5,
                    details = "Statistical anomaly: z-score ${"%.2f".format(zScore)} (HR: $hr, baseline: ${"%.1f".format(mean)})"
                )
            }
        }
        
        lastHrTimestamp = timestamp
    }
    
    /**
     * Add anomaly if not in cooldown period.
     */
    private fun addAnomalyIfNotCooldown(
        anomalies: MutableList<AnomalyEvent>,
        now: Long,
        type: AnomalyType,
        severity: Int,
        details: String
    ) {
        val lastTime = lastAnomalyTime[type] ?: 0
        if (now - lastTime > COOLDOWN_MS) {
            anomalies.add(
                AnomalyEvent(
                    timestampMs = now,
                    type = type,
                    severity = severity,
                    details = details
                )
            )
            lastAnomalyTime[type] = now
        }
    }
    
    /**
     * Reset detection state (e.g., when starting new session).
     */
    fun reset() {
        ewmaHr = null
        lastHr = null
        lastStepFreq = null
        lastAnomalyTime.clear()
        _detectedAnomaly.value = null
    }
}
