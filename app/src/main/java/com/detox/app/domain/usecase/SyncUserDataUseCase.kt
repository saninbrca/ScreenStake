package com.detox.app.domain.usecase

import com.detox.app.domain.repository.SyncRepository
import javax.inject.Inject

class SyncUserDataUseCase @Inject constructor(
    private val syncRepository: SyncRepository
) {
    suspend operator fun invoke(): Result<Unit> = syncRepository.syncUserData()
}
