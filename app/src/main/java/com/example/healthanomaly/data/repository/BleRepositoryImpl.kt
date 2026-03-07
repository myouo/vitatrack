package com.example.healthanomaly.data.repository

import com.example.healthanomaly.data.stream.FilePlaybackManager
import com.example.healthanomaly.domain.model.HeartRateData
import com.example.healthanomaly.domain.repository.BleConnectionState
import com.example.healthanomaly.domain.repository.BleDevice
import com.example.healthanomaly.domain.repository.BleRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of BleRepository using BleManager.
 */
@Singleton
class BleRepositoryImpl @Inject constructor(
    private val filePlaybackManager: FilePlaybackManager
) : BleRepository {

    override val heartRateFlow: Flow<HeartRateData> = filePlaybackManager.heartRateFlow

    override val scanResultsFlow: Flow<List<BleDevice>> = filePlaybackManager.scanResultsFlow

    override val connectionStateFlow: Flow<BleConnectionState> = filePlaybackManager.connectionStateFlow

    override fun startScan() = filePlaybackManager.publishVirtualSource()

    override fun stopScan() = Unit

    override fun connect(deviceAddress: String) = filePlaybackManager.startPlayback()

    override fun disconnect() = filePlaybackManager.stopPlayback()

    override fun isConnected(): Boolean = filePlaybackManager.isPlaying()

    override fun getConnectedDeviceAddress(): String? =
        if (filePlaybackManager.isPlaying()) "FILE_STREAM_SAMPLE" else null
}
