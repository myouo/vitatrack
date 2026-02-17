package com.example.healthanomaly.data.repository

import com.example.healthanomaly.data.sensors.ImuSensorManager
import com.example.healthanomaly.domain.model.ImuData
import com.example.healthanomaly.domain.repository.SensorRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of SensorRepository using ImuSensorManager.
 */
@Singleton
class SensorRepositoryImpl @Inject constructor(
    private val imuSensorManager: ImuSensorManager
) : SensorRepository {
    
    override val imuDataFlow: Flow<ImuData> = imuSensorManager.imuDataFlow
    
    override suspend fun getRecentSamples(durationMs: Long): List<ImuData> =
        imuSensorManager.getRecentSamples(durationMs)
    
    override fun startCollection() = imuSensorManager.startCollection()
    
    override fun stopCollection() = imuSensorManager.stopCollection()
    
    override fun isCollecting(): Boolean = imuSensorManager.isCollecting()
}
