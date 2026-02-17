package com.example.healthanomaly.domain.model

/**
 * Represents heart rate measurement data from BLE device.
 *
 * @property timestampMs Timestamp in milliseconds
 * @property heartRateBpm Heart rate in beats per minute
 * @property deviceAddress BLE device MAC address
 */
data class HeartRateData(
    val timestampMs: Long,
    val heartRateBpm: Int,
    val deviceAddress: String
)