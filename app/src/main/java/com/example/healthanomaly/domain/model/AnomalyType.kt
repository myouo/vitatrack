package com.example.healthanomaly.domain.model

/**
 * Enum representing different types of anomalies that can be detected.
 */
enum class AnomalyType(val displayName: String) {
    HEART_RATE_HIGH("心率过高"),
    HEART_RATE_LOW("心率过低"),
    HEART_RATE_SUDDEN_CHANGE("心率突变"),
    STEP_FREQ_HIGH("步频过高"),
    STEP_FREQ_LOW("步频过低"),
    GAIT_SUDDEN_CHANGE("步态突变"),
    FALL_DETECTED("跌倒检测"),
    MOTION_INTENSITY_ANOMALY("运动强度异常")
}
