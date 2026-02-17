package com.example.healthanomaly.data.ble

/**
 * Data class representing a BLE device discovered during scan.
 */
data class BleDevice(
    val address: String,
    val name: String?,
    val rssi: Int
) {
    val displayName: String
        get() = name ?: "Unknown Device"
}
