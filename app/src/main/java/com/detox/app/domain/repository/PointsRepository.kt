package com.detox.app.domain.repository

import com.detox.app.domain.model.PointTransaction
import kotlinx.coroutines.flow.Flow

interface PointsRepository {
    fun getTotalPointsBalance(): Flow<Int>
    suspend fun addPointTransaction(transaction: PointTransaction): Result<Unit>
    fun getAllTransactions(): Flow<List<PointTransaction>>
}
