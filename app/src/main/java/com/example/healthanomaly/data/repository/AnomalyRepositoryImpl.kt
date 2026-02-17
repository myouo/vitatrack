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
    
    override val anomalyEventsFlow: Flow<List<AnomalyEvent>> =
        anomalyEventDao.getAllFlow().map { entities ->
            entities.map { it.toDomain() }
        }
    
    override val featureWindowsFlow: Flow<List<FeatureWindow>> =
        featureWindowDao.getAllFlow().map { entities ->
            entities.map { it.toDomain() }
        }
    
    override suspend fun insertAnomalyEvent(event: AnomalyEvent): Long =
        anomalyEventDao.insert(AnomalyEventEntity.fromDomain(event))
    
    override suspend fun insertFeatureWindow(window: FeatureWindow): Long =
        featureWindowDao.insert(FeatureWindowEntity.fromDomain(window))
    
    override suspend fun getAllAnomalyEvents(): List<AnomalyEvent> =
