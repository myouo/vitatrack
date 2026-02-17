package com.example.healthanomaly.core

/**
 * Application-wide constants.
 */
object Constants {
    // Window parameters
    const val WINDOW_SIZE_MS = 2000L       // 2 seconds
    const val WINDOW_STEP_MS = 1000L       // 1 second (50% overlap)
    const val RING_BUFFER_DURATION_MS = 30_000L  // 30 seconds
    
    // Heart rate thresholds
    const val HR_HIGH_THRESHOLD = 150     // BPM
    const val HR_LOW_THRESHOLD = 40        // BPM
    const val HR_SUDDEN_CHANGE_THRESHOLD = 30  // BPM in 5 seconds
    
    // Step frequency thresholds (in Hz)
    const val STEP_FREQ_HIGH_THRESHOLD = 3.0f   // 180 steps/min
    const val STEP_FREQ_LOW_THRESHOLD = 0.5f    // 30 steps/min
    
    // Motion thresholds
    const val IMPACT_THRESHOLD = 20.0f     // m/s² for fall detection
    const val MOTION_INTENSITY_HIGH = 15.0f  // m/s² RMS
    
    // Detection parameters
    const val ANOMALY_COOLDOWN_MS = 60_000L  // 60 seconds
    const val EWMA_WINDOW_MS = 300_000L     // 5 minutes
    const val Z_SCORE_THRESHOLD = 3.0
    
    // BLE
    const val BLE_SCAN_TIMEOUT_MS = 10_000L  // 10 seconds
    
    // Notification
    const val NOTIFICATION_CHANNEL_ID = "health_anomaly_channel"
    const val NOTIFICATION_CHANNEL_NAME = "Health Alerts"
    const val NOTIFICATION_ID = 1001
    const val FOREGROUND_NOTIFICATION_ID = 1002
    
    // Database
    const val MAX_FEATURE_WINDOWS = 10000  // Keep last 10k windows
}
