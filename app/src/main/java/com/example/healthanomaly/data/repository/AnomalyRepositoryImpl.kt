package com.example.healthanomaly.data.repository

import com.example.healthanomaly.data.local.dao.AnomalyEventDao
import com.example.healthanomaly.data.local.dao.FeatureWindowDao
import com.example.healthanomaly.data.local.entity.AnomalyEventEntity
import com.example.healthanomaly.data.local.entity.FeatureWindowEntity
import com.example.healthanomaly.domain.model.AnomalyEvent
import com.example.healthanomaly.domain.model.FeatureWindow
import com.example.healthanomaly.domain.repository.AnomalyRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of AnomalyRepository using Room database.
 */
@Singleton
class AnomalyRepositoryImpl @Inject constructor(
    private val featureWindowDao: FeatureWindowDao,
    private val anomalyEventDao: AnomalyEventDao
) : AnomalyRepository {
    companion object {
        private const val RECENT_WINDOW_LIMIT = 300
    }
    
    override val anomalyEventsFlow: Flow<List<AnomalyEvent>> =
        anomalyEventDao.getAllFlow().map { entities ->
            entities.map { it.toDomain() }
        }
    
    override val featureWindowsFlow: Flow<List<FeatureWindow>> =
        featureWindowDao.getRecentFlow(RECENT_WINDOW_LIMIT).map { entities ->
            entities.map { it.toDomain() }
        }
    
    override suspend fun insertAnomalyEvent(event: AnomalyEvent): Long =
        anomalyEventDao.insert(AnomalyEventEntity.fromDomain(event))
    
    override suspend fun insertFeatureWindow(window: FeatureWindow): Long =
        featureWindowDao.insert(FeatureWindowEntity.fromDomain(window))
    
    override suspend fun getAllAnomalyEvents(): List<AnomalyEvent> =
        anomalyEventDao.getAll().map { it.toDomain() }
    
    override suspend fun getAllFeatureWindows(): List<FeatureWindow> =
        featureWindowDao.getAll().map { it.toDomain() }
    
    override suspend fun getRecentAnomalyEvents(limit: Int): List<AnomalyEvent> =
        anomalyEventDao.getRecent(limit).map { it.toDomain() }
    
    override suspend fun getAnomalyEventsInRange(startMs: Long, endMs: Long): List<AnomalyEvent> =
        anomalyEventDao.getInRange(startMs, endMs).map { it.toDomain() }
    
    override suspend fun acknowledgeEvent(eventId: Long) {
        val events = anomalyEventDao.getRecent(Int.MAX_VALUE)
        val event = events.find { it.id == eventId }
        event?.let {
            anomalyEventDao.update(it.copy(acknowledged = true))
        }
    }
    
    override suspend fun clearAllData() {
        anomalyEventDao.deleteAll()
        featureWindowDao.deleteAll()
    }
}
