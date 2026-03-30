package com.detox.app.data.repository

import com.detox.app.data.local.db.dao.PointTransactionDao
import com.detox.app.data.local.db.entity.PointTransactionEntity
import com.detox.app.domain.model.PointTransaction
import com.detox.app.domain.repository.PointsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PointsRepositoryImpl @Inject constructor(
    private val pointTransactionDao: PointTransactionDao
) : PointsRepository {

    override fun getTotalPointsBalance(): Flow<Int> {
        return pointTransactionDao.getTotalPointsBalance().map { it ?: 0 }
    }

    override suspend fun addPointTransaction(transaction: PointTransaction): Result<Unit> {
        return try {
            pointTransactionDao.insertTransaction(transaction.toEntity())
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun getAllTransactions(): Flow<List<PointTransaction>> {
        return pointTransactionDao.getAllTransactions().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    private fun PointTransactionEntity.toDomain(): PointTransaction = PointTransaction(
        id = id,
        type = type,
        amount = amount,
        reason = reason,
        challengeId = challengeId,
        timestamp = timestamp
    )

    private fun PointTransaction.toEntity(): PointTransactionEntity = PointTransactionEntity(
        id = id,
        type = type,
        amount = amount,
        reason = reason,
        challengeId = challengeId,
        timestamp = timestamp
    )
}
