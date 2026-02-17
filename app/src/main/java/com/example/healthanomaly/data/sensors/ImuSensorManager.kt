package com.example.healthanomaly.data.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.example.healthanomaly.domain.model.ImuData
import com.example.healthanomaly.domain.repository.SensorRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manager for IMU sensor (accelerometer and gyroscope) data collection.
 * Uses a ring buffer to keep recent 30 seconds of data.
 */
@Singleton
class ImuSensorManager @Inject constructor(
    @ApplicationContext private val context: Context
) : SensorRepository {
    
    companion object {
        // Keep 30 seconds of data
        const val RING_BUFFER_DURATION_MS = 30_000L
        
        // Default values if gyroscope not available
        private val GYRO_DEFAULT = floatArrayOf(0f, 0f, 0f)
    }
    
    private val sensorManager: SensorManager = 
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    
    private val accelerometer: Sensor? = 
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    
    private val gyroscope: Sensor? = 
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    
    // Ring buffer for recent samples
    private val ringBuffer = ConcurrentLinkedQueue<ImuData>()
    
    // State
    private val _imuDataFlow = MutableStateFlow<ImuData?>(null)
    private var isCollecting = false
    
    // Last sensor values
    private var lastAccel = floatArrayOf(0f, 0f, 0f)
    private var lastGyro = floatArrayOf(0f, 0f, 0f)
    private var lastTimestamp = 0L
    
    override val imuDataFlow: Flow<ImuData> = callbackFlow {
        val listener: (ImuData) -> Unit = { data ->
            trySend(data)
        }
        _imuDataFlow.value?.let { listener(it) }
        
        awaitClose { }
    }
    
    /**
     * Start sensor collection.
     */
    override fun startCollection() {
        if (isCollecting) return
        isCollecting = true
        
        // Register accelerometer
        accelerometer?.let { accel ->
            sensorManager.registerListener(
                sensorListener,
                accel,
                SensorManager.SENSOR_DELAY_GAME
            )
        }
        
        // Register gyroscope (if available)
        gyroscope?.let { gyro ->
            sensorManager.registerListener(
                sensorListener,
                gyro,
                SensorManager.SENSOR_DELAY_GAME
            )
        }
    }
    
    /**
     * Stop sensor collection.
     */
    override fun stopCollection() {
        if (!isCollecting) return
        isCollecting = false
        
        sensorManager.unregisterListener(sensorListener)
    }
    
    /**
     * Check if sensors are collecting.
     */
    override fun isCollecting(): Boolean = isCollecting
    
    /**
     * Get recent samples from ring buffer.
     */
    override suspend fun getRecentSamples(durationMs: Long): List<ImuData> {
        val cutoffTime = SystemClock.elapsedRealtimeNanos() / 1_000_000 - durationMs
        return ringBuffer.filter { it.timestampMs >= cutoffTime }
    }
    
    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val timestamp = SystemClock.elapsedRealtimeNanos() / 1_000_000
            
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    lastAccel = event.values.copyOf()
                }
                Sensor.TYPE_GYROSCOPE -> {
                    lastGyro = event.values.copyOf()
                }
            }
            
            // Only emit data when we have both accel and gyro (or accel if gyro unavailable)
            if (lastTimestamp > 0 && timestamp > lastTimestamp) {
