package com.example.healthanomaly.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.healthanomaly.domain.model.FeatureWindow

/**
 * Room entity for storing feature window data.
 */
@Entity(tableName = "feature_windows")
data class FeatureWindowEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    @ColumnInfo(name = "timestamp_ms")
    val timestampMs: Long,
    
    @ColumnInfo(name = "window_size_ms")
    val windowSizeMs: Long = 2000L,
    
    @ColumnInfo(name = "sample_count")
    val sampleCount: Int = 0,
    
    // Accelerometer statistics
    @ColumnInfo(name = "mean_accel_x")
    val meanAccelX: Float = 0f,
    
    @ColumnInfo(name = "mean_accel_y")
    val meanAccelY: Float = 0f,
    
    @ColumnInfo(name = "mean_accel_z")
    val meanAccelZ: Float = 0f,
    
    @ColumnInfo(name = "std_accel_x")
    val stdAccelX: Float = 0f,
    
    @ColumnInfo(name = "std_accel_y")
    val stdAccelY: Float = 0f,
    
    @ColumnInfo(name = "std_accel_z")
    val stdAccelZ: Float = 0f,
    
    @ColumnInfo(name = "rms_accel")
    val rmsAccel: Float = 0f,
    
    @ColumnInfo(name = "max_accel")
    val maxAccel: Float = 0f,
    
    @ColumnInfo(name = "min_accel")
    val minAccel: Float = 0f,
    
    @ColumnInfo(name = "jerk_rms")
    val jerkRms: Float = 0f,
    
    // Gyroscope statistics
    @ColumnInfo(name = "mean_gyro_x")
    val meanGyroX: Float = 0f,
    
    @ColumnInfo(name = "mean_gyro_y")
    val meanGyroY: Float = 0f,
    
    @ColumnInfo(name = "mean_gyro_z")
    val meanGyroZ: Float = 0f,
    
    @ColumnInfo(name = "std_gyro_x")
    val stdGyroX: Float = 0f,
    
    @ColumnInfo(name = "std_gyro_y")
    val stdGyroY: Float = 0f,
    
    @ColumnInfo(name = "std_gyro_z")
    val stdGyroZ: Float = 0f,
    
    @ColumnInfo(name = "rms_gyro")
    val rmsGyro: Float = 0f,
    
    // Derived features
    @ColumnInfo(name = "step_freq_hz")
    val stepFreqHz: Float = 0f,
    
    @ColumnInfo(name = "heart_rate_bpm")
    val heartRateBpm: Int? = null
) {
    /**
     * Convert to domain model.
     */
    fun toDomain(): FeatureWindow = FeatureWindow(
        id = id,
        timestampMs = timestampMs,
        windowSizeMs = windowSizeMs,
        sampleCount = sampleCount,
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
    
    companion object {
        /**
         * Create from domain model.
         */
        fun fromDomain(window: FeatureWindow): FeatureWindowEntity = FeatureWindowEntity(
            id = window.id,
            timestampMs = window.timestampMs,
            windowSizeMs = window.windowSizeMs,
            sampleCount = window.sampleCount,
            meanAccelX = window.meanAccelX,
            meanAccelY = window.meanAccelY,
            meanAccelZ = window.meanAccelZ,
            stdAccelX = window.stdAccelX,
            stdAccelY = window.stdAccelY,
            stdAccelZ = window.stdAccelZ,
            rmsAccel = window.rmsAccel,
            maxAccel = window.maxAccel,
            minAccel = window.minAccel,
            jerkRms = window.jerkRms,
            meanGyroX = window.meanGyroX,
            meanGyroY = window.meanGyroY,
            meanGyroZ = window.meanGyroZ,
            stdGyroX = window.stdGyroX,
            stdGyroY = window.stdGyroY,
            stdGyroZ = window.stdGyroZ,
            rmsGyro = window.rmsGyro,
            stepFreqHz = window.stepFreqHz,
            heartRateBpm = window.heartRateBpm
        )
    }
}
