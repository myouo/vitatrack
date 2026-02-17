package com.example.healthanomaly.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.healthanomaly.domain.model.AnomalyEvent
import com.example.healthanomaly.domain.model.AnomalyType

/**
 * Room entity for storing anomaly events.
 */
@Entity(tableName = "anomaly_events")
data class AnomalyEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    @ColumnInfo(name = "timestamp_ms")
    val timestampMs: Long,
    
    @ColumnInfo(name = "type")
    val type: String,
    
    @ColumnInfo(name = "severity")
    val severity: Int,
    
    @ColumnInfo(name = "details")
    val details: String,
    
    @ColumnInfo(name = "acknowledged")
    val acknowledged: Boolean = false
) {
    /**
     * Convert to domain model.
     */
    fun toDomain(): AnomalyEvent = AnomalyEvent(
        id = id,
        timestampMs = timestampMs,
        type = try {
            AnomalyType.valueOf(type)
        } catch (e: Exception) {
            AnomalyType.MOTION_INTENSITY_ANOMALY
        },
        severity = severity,
        details = details,
        acknowledged = acknowledged
    )
    
    companion object {
        /**
         * Create from domain model.
         */
        fun fromDomain(event: AnomalyEvent): AnomalyEventEntity = AnomalyEventEntity(
            id = event.id,
            timestampMs = event.timestampMs,
            type = event.type.name,
            severity = event.severity,
            details = event.details,
            acknowledged = event.acknowledged
        )
    }
}
