package com.detox.app.di

import com.detox.app.data.repository.UsageStatsRepositoryImpl
import com.detox.app.domain.repository.UsageStatsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindUsageStatsRepository(
        impl: UsageStatsRepositoryImpl
    ): UsageStatsRepository
}
