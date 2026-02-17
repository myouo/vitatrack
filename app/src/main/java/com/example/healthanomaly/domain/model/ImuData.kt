package com.example.healthanomaly.domain.model

/**
 * Represents a single IMU (Inertial Measurement Unit) sensor sample.
 * Contains accelerometer and gyroscope data with a unified timestamp.
 *
 * @property timestampMs Timestamp in milliseconds (SystemClock.elapsedRealtimeNanos() / 1_000_000)
 * @property accelX X-axis acceleration in m/s²
 * @property accelY Y-axis acceleration in m/s²
 * @property accelZ Z-axis acceleration in m/s²
 * @property gyroX X-axis angular velocity in rad/s
 * @property gyroY Y-axis angular velocity in rad/s
 * @property gyroZ Z-axis angular velocity in rad/s
 */
data class ImuData(
    val timestampMs: Long,
    val accelX: Float,
    val accelY: Float,
    val accelZ: Float,
    val gyroX: Float = 0f,
    val gyroY: Float = 0f,
    val gyroZ: Float = 0f
) {
    /**
     * Calculate the magnitude of acceleration vector.
     */
    val accelMagnitude: Float
        get() = kotlin.math.sqrt(accelX * accelX + accelY * accelY + accelZ * accelZ)

    /**
     * Calculate the magnitude of gyroscope vector.
     */
    val gyroMagnitude: Float
        get() = kotlin.math.sqrt(gyroX * gyroX + gyroY * gyroY + gyroZ * gyroZ)
}
