package com.example.healthanomaly.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    companion object {
        const val DATABASE_NAME = "health_anomaly_db"
        
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE anomaly_events ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
    
    abstract fun anomalyEventDao(): AnomalyEventDao
    abstract fun featureWindowDao(): FeatureWindowDao
}
