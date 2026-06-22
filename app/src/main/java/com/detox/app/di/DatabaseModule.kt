package com.detox.app.di

import android.content.Context
import androidx.room.Room
import com.detox.app.data.local.db.DatabaseKeyManager
import com.detox.app.data.local.db.DetoxDatabase
import com.detox.app.data.local.db.dao.ChallengeDao
import com.detox.app.data.local.db.dao.DailyLogDao
import com.detox.app.data.local.db.dao.GroupChallengeDao
import com.detox.app.data.local.db.dao.PendingHardChallengeDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import timber.log.Timber
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    private const val DB_NAME = "detox_database"
    private const val SECURITY_PREFS = "detox_db_security"
    private const val FLAG_ENCRYPTED_V1 = "db_encrypted_v1"

    @Provides
    @Singleton
    fun provideDetoxDatabase(
        @ApplicationContext context: Context,
        keyManager: DatabaseKeyManager
    ): DetoxDatabase {
        val passphrase = keyManager.getOrCreatePassphrase()
        val securityPrefs = context.getSharedPreferences(SECURITY_PREFS, Context.MODE_PRIVATE)

        // One-time plaintext → encrypted upgrade. Existing users have an UNENCRYPTED Room DB that
        // SQLCipher cannot open. Firestore is the source of truth (active challenges + today's logs
        // resync on launch), so we drop the old plaintext DB once and let the app repopulate it as
        // an encrypted DB. NOTE: finished-challenge HISTORY is Room-only today and is not re-fetched
        // by the active-only sync — see the SECURITY changelog entry for the recommended follow-up
        // (extend the resync to restore finished challenges).
        if (!securityPrefs.getBoolean(FLAG_ENCRYPTED_V1, false)) {
            val deleted = context.deleteDatabase(DB_NAME)
            securityPrefs.edit().putBoolean(FLAG_ENCRYPTED_V1, true).apply()
            Timber.i("DatabaseModule: plaintext→encrypted migration — old DB deleted=$deleted")
        }

        // Graceful Keystore-invalidation fallback: if the wrapping key was lost the passphrase was
        // regenerated, so the existing encrypted DB is unreadable → drop it and let sync repopulate.
        // The app must never crash on a lost key.
        if (passphrase.wasReset) {
            val deleted = context.deleteDatabase(DB_NAME)
            Timber.w("DatabaseModule: DB key invalidated — encrypted DB dropped=$deleted, will resync")
        }

        // Load the bundled SQLCipher native lib (.so per ABI; no Google Play Services dependency).
        System.loadLibrary("sqlcipher")
        // clearPassphrase = false → keep the passphrase usable across Room reopen cycles.
        val factory = SupportOpenHelperFactory(passphrase.bytes, null, false)

        return Room.databaseBuilder(
            context,
            DetoxDatabase::class.java,
            DB_NAME
        )
            .openHelperFactory(factory)
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
                DetoxDatabase.MIGRATION_23_24,
                DetoxDatabase.MIGRATION_24_25,
                DetoxDatabase.MIGRATION_25_26,
                DetoxDatabase.MIGRATION_26_27
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

    @Provides
    fun providePendingHardChallengeDao(database: DetoxDatabase): PendingHardChallengeDao {
        return database.pendingHardChallengeDao()
    }
}
