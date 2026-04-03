package com.detox.app.di

import com.detox.app.data.repository.ChallengeRepositoryImpl
import com.detox.app.data.repository.DailyLogRepositoryImpl
import com.detox.app.data.repository.PaymentRepositoryImpl
import com.detox.app.data.repository.PointsRepositoryImpl
import com.detox.app.data.repository.SyncRepositoryImpl
import com.detox.app.data.repository.UsageStatsRepositoryImpl
import com.detox.app.domain.repository.ChallengeRepository
import com.detox.app.domain.repository.DailyLogRepository
import com.detox.app.domain.repository.PaymentRepository
import com.detox.app.domain.repository.PointsRepository
import com.detox.app.domain.repository.SyncRepository
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

    @Binds
    @Singleton
    abstract fun bindChallengeRepository(
        impl: ChallengeRepositoryImpl
    ): ChallengeRepository

    @Binds
    @Singleton
    abstract fun bindDailyLogRepository(
        impl: DailyLogRepositoryImpl
    ): DailyLogRepository

    @Binds
    @Singleton
    abstract fun bindPointsRepository(
        impl: PointsRepositoryImpl
    ): PointsRepository

    @Binds
    @Singleton
    abstract fun bindPaymentRepository(
        impl: PaymentRepositoryImpl
    ): PaymentRepository

    @Binds
    @Singleton
    abstract fun bindSyncRepository(
        impl: SyncRepositoryImpl
    ): SyncRepository
}
