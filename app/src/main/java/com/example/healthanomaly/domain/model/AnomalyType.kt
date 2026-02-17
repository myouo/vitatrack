 * Enum representing different types of anomalies that can be detected.
 */
enum class AnomalyType {
    /** Heart rate too high (>150 BPM) */
    HEART_RATE_HIGH,
    
    /** Heart rate too low (<40 BPM) */
    HEART_RATE_LOW,
    
    /** Sudden heart rate change (>30 BPM in 5 seconds) */
    HEART_RATE_SUDDEN_CHANGE,
    
    /** Step frequency too high (>180 steps/min = 3 Hz) */
    STEP_FREQ_HIGH,
    
    /** Step frequency too low (<30 steps/min = 0.5 Hz) */
    STEP_FREQ_LOW,
    
    /** Sudden gait change detected */
    GAIT_SUDDEN_CHANGE,
    
    /** Fall detected (impact + stationary pattern) */
    FALL_DETECTED,
    
    /** Motion intensity anomaly */
    MOTION_INTENSITY_ANOMALY
}
