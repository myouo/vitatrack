package com.example.healthanomaly.data.repository

import com.example.healthanomaly.data.stream.FilePlaybackManager
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
    private val filePlaybackManager: FilePlaybackManager
) : SensorRepository {

    override val imuDataFlow: Flow<ImuData> = filePlaybackManager.imuDataFlow

    override suspend fun getRecentSamples(durationMs: Long): List<ImuData> =
        filePlaybackManager.getRecentSamples(durationMs)

    override fun startCollection() = filePlaybackManager.startPlayback()

    override fun stopCollection() = filePlaybackManager.stopPlayback()

    override fun isCollecting(): Boolean = filePlaybackManager.isPlaying()
}
