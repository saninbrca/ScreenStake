package com.detox.app.di

import android.content.Context
import androidx.room.Room
import com.detox.app.data.local.db.DetoxDatabase
import com.detox.app.data.local.db.dao.ChallengeDao
import com.detox.app.data.local.db.dao.DailyLogDao
import com.detox.app.data.local.db.dao.GroupChallengeDao
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
                DetoxDatabase.MIGRATION_4_5,
                DetoxDatabase.MIGRATION_5_6,
                DetoxDatabase.MIGRATION_6_7,
                DetoxDatabase.MIGRATION_7_8,
                DetoxDatabase.MIGRATION_8_9,
                DetoxDatabase.MIGRATION_9_10,
                DetoxDatabase.MIGRATION_10_11,
                DetoxDatabase.MIGRATION_11_12,
                DetoxDatabase.MIGRATION_12_13,
                DetoxDatabase.MIGRATION_13_14,
                DetoxDatabase.MIGRATION_14_15,
                DetoxDatabase.MIGRATION_15_16,
                DetoxDatabase.MIGRATION_16_17,
                DetoxDatabase.MIGRATION_17_18,
                DetoxDatabase.MIGRATION_18_19,
                DetoxDatabase.MIGRATION_19_20,
                DetoxDatabase.MIGRATION_20_21,
                DetoxDatabase.MIGRATION_21_22,
                DetoxDatabase.MIGRATION_22_23,
                DetoxDatabase.MIGRATION_23_24
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
    fun provideGroupChallengeDao(database: DetoxDatabase): GroupChallengeDao {
        return database.groupChallengeDao()
    }
}
