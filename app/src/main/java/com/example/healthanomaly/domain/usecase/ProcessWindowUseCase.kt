package com.example.healthanomaly.domain.usecase

import com.example.healthanomaly.domain.model.FeatureWindow
import com.example.healthanomaly.domain.model.ImuData
import com.example.healthanomaly.domain.repository.AnomalyRepository
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Use case for processing sliding windows and extracting features.
 * 
 * Window size: 2 seconds (2000ms)
 * Step size: 1 second (1000ms) - 50% overlap
 */
class ProcessWindowUseCase @Inject constructor(
    private val anomalyRepository: AnomalyRepository
) {
    companion object {
        const val WINDOW_SIZE_MS = 2000L
        const val STEP_SIZE_MS = 1000L
    }
    
    /**
     * Extract features from a window of IMU data.
     * 
     * Features extracted:
     * - Mean, std, RMS, max, min for acceleration
     * - Jerk RMS (derivative of acceleration)
     * - Step frequency (via peak detection)
     * - Gyroscope statistics
     */
    fun extractFeatures(
        samples: List<ImuData>,
        windowEndTimestamp: Long,
        heartRateBpm: Int? = null
    ): FeatureWindow {
        if (samples.isEmpty()) {
            return FeatureWindow(
                timestampMs = windowEndTimestamp,
                sampleCount = 0
            )
        }
        
        val accelX = samples.map { it.accelX }
        val accelY = samples.map { it.accelY }
        val accelZ = samples.map { it.accelZ }
        val accelMag = samples.map { it.accelMagnitude }
        
        val gyroX = samples.map { it.gyroX }
        val gyroY = samples.map { it.gyroY }
        val gyroZ = samples.map { it.gyroZ }
        val gyroMag = samples.map { it.gyroMagnitude }
        
        // Calculate statistics
        val meanAccelX = accelX.average().toFloat()
        val meanAccelY = accelY.average().toFloat()
        val meanAccelZ = accelZ.average().toFloat()
        
        val stdAccelX = calculateStd(accelX, meanAccelX)
        val stdAccelY = calculateStd(accelY, meanAccelY)
        val stdAccelZ = calculateStd(accelZ, meanAccelZ)
        
        val rmsAccel = sqrt(accelMag.map { it * it }.average()).toFloat()
        val maxAccel = accelMag.maxOrNull() ?: 0f
        val minAccel = accelMag.minOrNull() ?: 0f
        
        // Jerk (derivative of acceleration)
        val jerkValues = mutableListOf<Float>()
        for (i in 1 until samples.size) {
            val dt = (samples[i].timestampMs - samples[i-1].timestampMs) / 1000.0
            if (dt > 0) {
                val jerk = (samples[i].accelMagnitude - samples[i-1].accelMagnitude) / dt
                jerkValues.add(jerk.toFloat())
            }
        }
        val jerkRms = if (jerkValues.isNotEmpty()) {
            sqrt(jerkValues.map { it * it }.average()).toFloat()
        } else 0f
        
        // Gyroscope statistics
        val meanGyroX = gyroX.average().toFloat()
        val meanGyroY = gyroY.average().toFloat()
        val meanGyroZ = gyroZ.average().toFloat()
        
        val stdGyroX = calculateStd(gyroX, meanGyroX)
        val stdGyroY = calculateStd(gyroY, meanGyroY)
        val stdGyroZ = calculateStd(gyroZ, meanGyroZ)
        
        val rmsGyro = sqrt(gyroMag.map { it * it }.average()).toFloat()
        
        // Step frequency estimation via peak detection
        val stepFreqHz = estimateStepFrequency(samples)
        
        return FeatureWindow(
            timestampMs = windowEndTimestamp,
            windowSizeMs = WINDOW_SIZE_MS,
            sampleCount = samples.size,
            meanAccelX = meanAccelX,
            meanAccelY = meanAccelY,
            meanAccelZ = meanAccelZ,
            stdAccelX = stdAccelX,
            stdAccelY = stdAccelY,
            stdAccelZ = stdAccelZ,
            rmsAccel = rmsAccel,
            maxAccel = maxAccel,
            minAccel = minAccel,
            jerkRms = jerkRms,
            meanGyroX = meanGyroX,
            meanGyroY = meanGyroY,
            meanGyroZ = meanGyroZ,
            stdGyroX = stdGyroX,
            stdGyroY = stdGyroY,
            stdGyroZ = stdGyroZ,
            rmsGyro = rmsGyro,
            stepFreqHz = stepFreqHz,
            heartRateBpm = heartRateBpm
        )
    }
    
    /**
     * Save feature window to database.
     */
    suspend fun saveFeatureWindow(window: FeatureWindow): Long {
        return anomalyRepository.insertFeatureWindow(window)
    }
    
    private fun calculateStd(values: List<Float>, mean: Float): Float {
        if (values.size < 2) return 0f
        val variance = values.map { (it - mean) * (it - mean) }.average()
        return sqrt(variance).toFloat()
    }
    
    /**
     * Simple peak detection for step frequency estimation.
     * Looks for peaks in acceleration magnitude above a threshold.
     */
    private fun estimateStepFrequency(samples: List<ImuData>): Float {
        if (samples.size < 10) return 0f
        
        val threshold = 10.0f // m/s² - typical walking threshold
        val accelMag = samples.map { it.accelMagnitude }
        
        var peaks = 0
        for (i in 1 until accelMag.size - 1) {
            if (accelMag[i] > threshold &&
                accelMag[i] > accelMag[i-1] &&
                accelMag[i] > accelMag[i+1]) {
                peaks++
            }
        }
        
        val durationSec = (samples.last().timestampMs - samples.first().timestampMs) / 1000.0
        return if (durationSec > 0) (peaks / durationSec).toFloat() else 0f
    }
}
