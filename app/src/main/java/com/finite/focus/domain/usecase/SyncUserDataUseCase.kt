package com.finite.focus.domain.usecase

import com.finite.focus.domain.repository.SyncRepository
import javax.inject.Inject

class SyncUserDataUseCase @Inject constructor(
    private val syncRepository: SyncRepository
) {
    suspend operator fun invoke(): Result<Unit> = syncRepository.syncUserData()
}
