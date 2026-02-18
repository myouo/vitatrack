package com.example.healthanomaly.domain.usecase

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.example.healthanomaly.domain.model.AnomalyEvent
import com.example.healthanomaly.domain.model.AnomalyType
import com.example.healthanomaly.domain.model.FeatureWindow
import com.example.healthanomaly.domain.repository.AnomalyRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Use case for exporting data to CSV files.
 * Uses MediaStore API for Android 10+ (SAF).
 */
class ExportDataUseCase @Inject constructor(
    private val anomalyRepository: AnomalyRepository,
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val EXPORT_FOLDER = "HealthAnomaly"
        private const val EVENTS_FILENAME = "anomaly_events"
        private const val FEATURES_FILENAME = "feature_windows"
        private const val CSV_HEADER_EVENTS = "id,timestamp,type,severity,details,acknowledged"
        private const val CSV_HEADER_FEATURES = "id,timestamp_ms,window_size_ms,sample_count," +
                "mean_accel_x,mean_accel_y,mean_accel_z,std_accel_x,std_accel_y,std_accel_z," +
                "rms_accel,max_accel,min_accel,jerk_rms,mean_gyro_x,mean_gyro_y,mean_gyro_z," +
                "std_gyro_x,std_gyro_y,std_gyro_z,rms_gyro,step_freq_hz,heart_rate_bpm"
    }
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    
    /**
     * Export all anomaly events to CSV.
     * 
     * @return File path if successful, null otherwise
     */
    suspend fun exportAnomalyEvents(): String? {
        return try {
            val events = anomalyRepository.getAllAnomalyEvents()
            if (events.isEmpty()) return null
            
            val timestamp = dateFormat.format(Date())
            val filename = "${EVENTS_FILENAME}_$timestamp.csv"
            
            val content = buildString {
                appendLine(CSV_HEADER_EVENTS)
                events.forEach { event ->
                    appendLine("${event.id},${event.timestampMs},${event.type}," +
                            "${event.severity},\"${event.details.replace("\"", "\"\"")}\"," +
                            "${event.acknowledged}")
                }
            }
            
            saveToDownloads(filename, content)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * Export all feature windows to CSV.
     * 
     * @return File path if successful, null otherwise
     */
    suspend fun exportFeatureWindows(): String? {
        return try {
            val windows = anomalyRepository.getAllFeatureWindows()
            if (windows.isEmpty()) return null
            
            val timestamp = dateFormat.format(Date())
            val filename = "${FEATURES_FILENAME}_$timestamp.csv"
            
            val content = buildString {
                appendLine(CSV_HEADER_FEATURES)
                windows.forEach { window ->
                    appendLine("${window.id},${window.timestampMs},${window.windowSizeMs}," +
                            "${window.sampleCount},${window.meanAccelX},${window.meanAccelY}," +
                            "${window.meanAccelZ},${window.stdAccelX},${window.stdAccelY}," +
                            "${window.stdAccelZ},${window.rmsAccel},${window.maxAccel}," +
                            "${window.minAccel},${window.jerkRms},${window.meanGyroX}," +
                            "${window.meanGyroY},${window.meanGyroZ},${window.stdGyroX}," +
                            "${window.stdGyroY},${window.stdGyroZ},${window.rmsGyro}," +
                            "${window.stepFreqHz},${window.heartRateBpm ?: ""}")
                }
            }
            
            saveToDownloads(filename, content)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * Export both events and features.
     * 
     * @return Pair of file paths (events, features) or nulls
     */
    suspend fun exportAll(): Pair<String?, String?> {
        val eventsPath = exportAnomalyEvents()
        val featuresPath = exportFeatureWindows()
        return Pair(eventsPath, featuresPath)
    }
    
    /**
     * Save content to Downloads folder using MediaStore.
     */
    private fun saveToDownloads(filename: String, content: String): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + EXPORT_FOLDER)
            }
            
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                contentValues
            )
            
            uri?.let { outputUri ->
                context.contentResolver.openOutputStream(outputUri)?.use { outputStream ->
                    outputStream.write(content.toByteArray())
                }
                outputUri.toString()
            }
        } else {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val exportDir = File(downloadsDir, EXPORT_FOLDER)
            if (!exportDir.exists()) {
                exportDir.mkdirs()
            }
            val file = File(exportDir, filename)
            FileOutputStream(file).use { fos ->
                fos.write(content.toByteArray())
            }
            file.absolutePath
        }
    }
}
