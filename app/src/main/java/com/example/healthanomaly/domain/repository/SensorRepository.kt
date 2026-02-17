package com.example.healthanomaly.domain.repository

import com.example.healthanomaly.domain.model.ImuData
import com.example.healthanomaly.domain.model.SensorSample
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for IMU sensor data collection.
 */
interface SensorRepository {
    /**
     * Flow of IMU data as it's collected in real-time.
     */
    val imuDataFlow: Flow<ImuData>
    
    /**
     * Get recent IMU samples from ring buffer.
     * @param durationMs How many milliseconds of recent data to retrieve
     * @return List of IMU data points
     */
    suspend fun getRecentSamples(durationMs: Long): List<ImuData>
    
    /**
     * Start sensor collection.
     */
    fun startCollection()
    
    /**
     * Stop sensor collection.
     */
    fun stopCollection()
    
    /**
     * Check if sensors are currently collecting.
     */
    fun isCollecting(): Boolean
}

