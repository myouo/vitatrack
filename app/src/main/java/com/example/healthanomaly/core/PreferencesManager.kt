package com.example.healthanomaly.core

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manager for app preferences using SharedPreferences.
 */
@Singleton
class PreferencesManager @Inject constructor(
    @ApplicationContext context: Context
) {
    companion object {
        private const val PREFS_NAME = "health_anomaly_prefs"
        
        // Keys
        private const val KEY_HR_HIGH_THRESHOLD = "hr_high_threshold"
        private const val KEY_HR_LOW_THRESHOLD = "hr_low_threshold"
        private const val KEY_STEP_FREQ_HIGH = "step_freq_high"
        private const val KEY_STEP_FREQ_LOW = "step_freq_low"
        private const val KEY_WINDOW_SIZE_MS = "window_size_ms"
        private const val KEY_FALL_DETECTION_ENABLED = "fall_detection_enabled"
        private const val KEY_COLLECTION_ENABLED = "collection_enabled"
        private const val KEY_CONNECTED_DEVICE_ADDRESS = "connected_device_address"
    }
    
    private val prefs: SharedPreferences = 
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    // Heart rate thresholds
    fun getHrHighThreshold(): Int = 
        prefs.getInt(KEY_HR_HIGH_THRESHOLD, Constants.HR_HIGH_THRESHOLD)
    
    fun setHrHighThreshold(value: Int) {
        prefs.edit().putInt(KEY_HR_HIGH_THRESHOLD, value).apply()
    }
    
    fun getHrLowThreshold(): Int = 
        prefs.getInt(KEY_HR_LOW_THRESHOLD, Constants.HR_LOW_THRESHOLD)
    
    fun setHrLowThreshold(value: Int) {
        prefs.edit().putInt(KEY_HR_LOW_THRESHOLD, value).apply()
    }
    
    // Step frequency thresholds
    fun getStepFreqHighThreshold(): Float = 
        prefs.getFloat(KEY_STEP_FREQ_HIGH, Constants.STEP_FREQ_HIGH_THRESHOLD)
    
    fun setStepFreqHighThreshold(value: Float) {
        prefs.edit().putFloat(KEY_STEP_FREQ_HIGH, value).apply()
    }
    
    fun getStepFreqLowThreshold(): Float = 
        prefs.getFloat(KEY_STEP_FREQ_LOW, Constants.STEP_FREQ_LOW_THRESHOLD)
    
    fun setStepFreqLowThreshold(value: Float) {
        prefs.edit().putFloat(KEY_STEP_FREQ_LOW, value).apply()
    }
    
    // Window size
    fun getWindowSizeMs(): Long = 
        prefs.getLong(KEY_WINDOW_SIZE_MS, Constants.WINDOW_SIZE_MS)
    
    fun setWindowSizeMs(value: Long) {
        prefs.edit().putLong(KEY_WINDOW_SIZE_MS, value).apply()
    }
    
    // Fall detection
    fun isFallDetectionEnabled(): Boolean = 
        prefs.getBoolean(KEY_FALL_DETECTION_ENABLED, true)
    
    fun setFallDetectionEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FALL_DETECTION_ENABLED, enabled).apply()
    }
    
    // Collection state
    fun isCollectionEnabled(): Boolean = 
        prefs.getBoolean(KEY_COLLECTION_ENABLED, false)
    
    fun setCollectionEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_COLLECTION_ENABLED, enabled).apply()
    }
    
    // Connected device
    fun getConnectedDeviceAddress(): String? = 
        prefs.getString(KEY_CONNECTED_DEVICE_ADDRESS, null)
    
    fun setConnectedDeviceAddress(address: String?) {
        prefs.edit().putString(KEY_CONNECTED_DEVICE_ADDRESS, address).apply()
    }
}
