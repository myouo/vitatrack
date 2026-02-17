package com.example.healthanomaly.domain.model

/**
 * Wrapper for a batch of IMU samples collected within a time window.
 * Used for feature extraction and analysis.
 *
 * @property samples List of IMU data points
 * @property startTimeMs Start timestamp of this window
 * @property endTimeMs End timestamp of this window
 */
data class SensorSample(
    val samples: List<ImuData>,
    val startTimeMs: Long,
    val endTimeMs: Long
) {
    val sampleCount: Int get() = samples.size
    
    val durationMs: Long get() = endTimeMs - startTimeMs
}
