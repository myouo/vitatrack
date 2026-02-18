package com.example.healthanomaly.domain.repository

data class BleDevice(
    val address: String,
    val name: String?,
    val rssi: Int
)
