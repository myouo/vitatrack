package com.example.healthanomaly.di

import com.example.healthanomaly.data.repository.AnomalyRepositoryImpl
import com.example.healthanomaly.data.repository.BleRepositoryImpl
import com.example.healthanomaly.data.repository.SensorRepositoryImpl
import com.example.healthanomaly.domain.repository.AnomalyRepository
import com.example.healthanomaly.domain.repository.BleRepository
import com.example.healthanomaly.domain.repository.SensorRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module binding repository implementations to interfaces.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    
    @Binds
    @Singleton
    abstract fun bindSensorRepository(
        impl: SensorRepositoryImpl
    ): SensorRepository
    
    @Binds
    @Singleton
    abstract fun bindBleRepository(
        impl: BleRepositoryImpl
    ): BleRepository
    
    @Binds
    @Singleton
    abstract fun bindAnomalyRepository(
        impl: AnomalyRepositoryImpl
    ): AnomalyRepository
}
