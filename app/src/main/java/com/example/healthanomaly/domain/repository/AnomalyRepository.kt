package com.example.healthanomaly.domain.repository

import com.example.healthanomaly.domain.model.AnomalyEvent
import com.example.healthanomaly.domain.model.FeatureWindow
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for anomaly events and feature windows.
 */
interface AnomalyRepository {
    /**
     * Flow of active (non-archived) anomaly events.
     */
    val anomalyEventsFlow: Flow<List<AnomalyEvent>>
    
    /**
     * Flow of archived anomaly events.
     */
    val archivedEventsFlow: Flow<List<AnomalyEvent>>
    
    /**
     * Flow of recent feature windows.
     */
    val featureWindowsFlow: Flow<List<FeatureWindow>>
    
    /**
     * Insert a new anomaly event.
     * @return The ID of the inserted event
     */
    suspend fun insertAnomalyEvent(event: AnomalyEvent): Long
    
    /**
     * Insert a new feature window.
     * @return The ID of the inserted window
     */
    suspend fun insertFeatureWindow(window: FeatureWindow): Long
    
    /**
     * Get all anomaly events (for export).
     */
    suspend fun getAllAnomalyEvents(): List<AnomalyEvent>
    
    /**
     * Get all feature windows (for export).
     */
    suspend fun getAllFeatureWindows(): List<FeatureWindow>
    
    /**
     * Get recent anomaly events.
     */
    suspend fun getRecentAnomalyEvents(limit: Int): List<AnomalyEvent>
    
    /**
     * Get anomaly events within a time range.
     */
    suspend fun getAnomalyEventsInRange(startMs: Long, endMs: Long): List<AnomalyEvent>
    
    /**
     * Mark an event as acknowledged.
     */
    suspend fun acknowledgeEvent(eventId: Long)
    
    /**
     * Archive an event.
     */
    suspend fun archiveEvent(eventId: Long)
    
    /**
     * Unarchive an event.
     */
    suspend fun unarchiveEvent(eventId: Long)
    
    /**
     * Delete all data.
     */
    suspend fun clearAllData()
}
