package com.example.healthanomaly.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.healthanomaly.data.local.dao.AnomalyEventDao
import com.example.healthanomaly.data.local.dao.FeatureWindowDao
import com.example.healthanomaly.data.local.entity.AnomalyEventEntity
import com.example.healthanomaly.data.local.entity.FeatureWindowEntity

/**
 * Room database for the Health Anomaly app.
 * Stores feature windows and anomaly events.
 */
@Database(
    entities = [
        FeatureWindowEntity::class,
        AnomalyEventEntity::class
    ],
    version = 1,
    exportSchema = false
)
    abstract class AppDatabase : RoomDatabase() {
    companion object {
        const val DATABASE_NAME = "health_anomaly_db"
    }
    
    abstract fun anomalyEventDao(): AnomalyEventDao
    abstract fun featureWindowDao(): FeatureWindowDao
}
