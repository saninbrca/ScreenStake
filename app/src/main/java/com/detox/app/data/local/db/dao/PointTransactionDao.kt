package com.detox.app.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.detox.app.data.local.db.entity.PointTransactionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PointTransactionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransaction(transaction: PointTransactionEntity)

    @Query("""
        SELECT COALESCE(SUM(
            CASE
                WHEN type = 'earned' THEN amount
                WHEN type IN ('spent', 'penalty') THEN -amount
                ELSE 0
            END
        ), 0) FROM point_transactions
    """)
    fun getTotalPointsBalance(): Flow<Int?>

    @Query("SELECT * FROM point_transactions ORDER BY timestamp DESC")
    fun getAllTransactions(): Flow<List<PointTransactionEntity>>
}
