package com.example.healthanomaly.domain.usecase

import com.example.healthanomaly.domain.model.ImuData
import com.example.healthanomaly.domain.repository.SensorRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Use case for collecting IMU sensor data.
 */
class CollectImuDataUseCase @Inject constructor(
    private val sensorRepository: SensorRepository
) {
    /**
     * Get flow of real-time IMU data.
     */
    fun observeImuData(): Flow<ImuData> = sensorRepository.imuDataFlow
    
    /**
     * Start IMU sensor collection.
     */
    fun startCollection() = sensorRepository.startCollection()
    
    /**
     * Stop IMU sensor collection.
     */
    fun stopCollection() = sensorRepository.stopCollection()
    
    /**
     * Get recent samples for analysis.
     */
    suspend fun getRecentSamples(durationMs: Long): List<ImuData> = 
        sensorRepository.getRecentSamples(durationMs)
    
    /**
     * Check if currently collecting.
     */
    fun isCollecting(): Boolean = sensorRepository.isCollecting()
}
