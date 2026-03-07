package com.example.healthanomaly.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.healthanomaly.data.local.entity.FeatureWindowEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for feature window database operations.
 */
@Dao
interface FeatureWindowDao {
    
    /**
     * Insert a new feature window.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(window: FeatureWindowEntity): Long
    
    /**
     * Get all feature windows ordered by timestamp descending.
     */
    @Query("SELECT * FROM feature_windows ORDER BY timestamp_ms DESC")
    fun getAllFlow(): Flow<List<FeatureWindowEntity>>

    /**
     * Get recent feature windows ordered by timestamp descending.
     */
    @Query("SELECT * FROM feature_windows ORDER BY timestamp_ms DESC LIMIT :limit")
    fun getRecentFlow(limit: Int): Flow<List<FeatureWindowEntity>>
    
    /**
     * Get recent feature windows.
     */
    @Query("SELECT * FROM feature_windows ORDER BY timestamp_ms DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<FeatureWindowEntity>
    
    /**
     * Get all feature windows (for export).
     */
    @Query("SELECT * FROM feature_windows ORDER BY timestamp_ms ASC")
    suspend fun getAll(): List<FeatureWindowEntity>
    
    /**
     * Get windows within a time range.
     */
    @Query("SELECT * FROM feature_windows WHERE timestamp_ms BETWEEN :startMs AND :endMs ORDER BY timestamp_ms ASC")
    suspend fun getInRange(startMs: Long, endMs: Long): List<FeatureWindowEntity>
    
    /**
     * Get count of windows.
     */
    @Query("SELECT COUNT(*) FROM feature_windows")
    suspend fun getCount(): Int
    
    /**
     * Delete all windows.
     */
    @Query("DELETE FROM feature_windows")
    suspend fun deleteAll()
    
    /**
     * Delete old windows (keep only recent N).
     */
    @Query("DELETE FROM feature_windows WHERE id NOT IN (SELECT id FROM feature_windows ORDER BY timestamp_ms DESC LIMIT :keepCount)")
    suspend fun deleteOld(keepCount: Int)
}
