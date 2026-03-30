package com.detox.app.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.detox.app.data.local.db.dao.ChallengeDao
import com.detox.app.data.local.db.dao.DailyLogDao
import com.detox.app.data.local.db.dao.PointTransactionDao
import com.detox.app.data.local.db.entity.ChallengeEntity
import com.detox.app.data.local.db.entity.DailyLogEntity
import com.detox.app.data.local.db.entity.PointTransactionEntity

@Database(
    entities = [
        ChallengeEntity::class,
        DailyLogEntity::class,
        PointTransactionEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class DetoxDatabase : RoomDatabase() {
    abstract fun challengeDao(): ChallengeDao
    abstract fun dailyLogDao(): DailyLogDao
    abstract fun pointTransactionDao(): PointTransactionDao
}
