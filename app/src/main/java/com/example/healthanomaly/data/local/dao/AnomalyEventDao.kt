package com.example.healthanomaly.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.healthanomaly.data.local.entity.AnomalyEventEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for anomaly event database operations.
 */
@Dao
interface AnomalyEventDao {
    
    /**
     * Insert a new anomaly event.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: AnomalyEventEntity): Long
    
    /**
     * Update an event (e.g., mark as acknowledged).
     */
    @Update
    suspend fun update(event: AnomalyEventEntity)
    
    /**
     * Get all events ordered by timestamp descending.
     */
    @Query("SELECT * FROM anomaly_events ORDER BY timestamp_ms DESC")
    fun getAllFlow(): Flow<List<AnomalyEventEntity>>
    
    /**
     * Get recent events.
     */
    @Query("SELECT * FROM anomaly_events ORDER BY timestamp_ms DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<AnomalyEventEntity>
    
    /**
     * Get all events (for export).
     */
    @Query("SELECT * FROM anomaly_events ORDER BY timestamp_ms ASC")
    suspend fun getAll(): List<AnomalyEventEntity>
    
    /**
     * Get events within a time range.
     */
    @Query("SELECT * FROM anomaly_events WHERE timestamp_ms BETWEEN :startMs AND :endMs ORDER BY timestamp_ms ASC")
    suspend fun getInRange(startMs: Long, endMs: Long): List<AnomalyEventEntity>
    
    /**
     * Delete all events.
     */
    @Query("DELETE FROM anomaly_events")
    suspend fun deleteAll()
}
    
