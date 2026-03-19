package com.example.healthanomaly.di

import android.content.Context
import androidx.room.Room
import com.example.healthanomaly.data.local.AppDatabase
import com.example.healthanomaly.data.local.dao.AnomalyEventDao
import com.example.healthanomaly.data.local.dao.FeatureWindowDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing database dependencies.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    
    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context
    ): AppDatabase = Room.databaseBuilder(
        context,
        AppDatabase::class.java,
        AppDatabase.DATABASE_NAME
    )
        .addMigrations(AppDatabase.MIGRATION_1_2)
        .build()
    
    @Provides
    fun provideFeatureWindowDao(database: AppDatabase): FeatureWindowDao =
        database.featureWindowDao()
    
    @Provides
    fun provideAnomalyEventDao(database: AppDatabase): AnomalyEventDao =
        database.anomalyEventDao()
}
