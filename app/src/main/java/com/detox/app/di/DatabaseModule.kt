package com.detox.app.di

import android.content.Context
import androidx.room.Room
import com.detox.app.data.local.db.DetoxDatabase
import com.detox.app.data.local.db.dao.ChallengeDao
import com.detox.app.data.local.db.dao.DailyLogDao
import com.detox.app.data.local.db.dao.PointTransactionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDetoxDatabase(
        @ApplicationContext context: Context
    ): DetoxDatabase {
        return Room.databaseBuilder(
            context,
            DetoxDatabase::class.java,
            "detox_database"
        )
            .addMigrations(
                DetoxDatabase.MIGRATION_1_2,
                DetoxDatabase.MIGRATION_2_3,
                DetoxDatabase.MIGRATION_3_4,
                DetoxDatabase.MIGRATION_4_5
            )
            .build()
    }

    @Provides
    fun provideChallengeDao(database: DetoxDatabase): ChallengeDao {
        return database.challengeDao()
    }

    @Provides
    fun provideDailyLogDao(database: DetoxDatabase): DailyLogDao {
        return database.dailyLogDao()
    }

    @Provides
    fun providePointTransactionDao(database: DetoxDatabase): PointTransactionDao {
        return database.pointTransactionDao()
    }
}
