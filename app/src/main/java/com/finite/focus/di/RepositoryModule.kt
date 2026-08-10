package com.finite.focus.di

import com.finite.focus.data.repository.ChallengeRepositoryImpl
import com.finite.focus.data.repository.DailyLogRepositoryImpl
import com.finite.focus.data.repository.GroupChallengeRepositoryImpl
import com.finite.focus.data.repository.PaymentRepositoryImpl
import com.finite.focus.data.repository.SyncRepositoryImpl
import com.finite.focus.data.repository.UsageStatsRepositoryImpl
import com.finite.focus.domain.repository.ChallengeRepository
import com.finite.focus.domain.repository.DailyLogRepository
import com.finite.focus.domain.repository.GroupChallengeRepository
import com.finite.focus.domain.repository.PaymentRepository
import com.finite.focus.domain.repository.SyncRepository
import com.finite.focus.domain.repository.UsageStatsRepository
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
    abstract fun bindPaymentRepository(
        impl: PaymentRepositoryImpl
    ): PaymentRepository

    @Binds
    @Singleton
    abstract fun bindSyncRepository(
        impl: SyncRepositoryImpl
    ): SyncRepository

    @Binds
    @Singleton
    abstract fun bindGroupChallengeRepository(
        impl: GroupChallengeRepositoryImpl
    ): GroupChallengeRepository
}
