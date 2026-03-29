package com.detox.app.domain.usecase

import com.detox.app.domain.model.ProofOfAddictionResult
import com.detox.app.domain.repository.UsageStatsRepository
import javax.inject.Inject

class GetAddictiveAppsUseCase @Inject constructor(
    private val usageStatsRepository: UsageStatsRepository
) {
    suspend operator fun invoke(): Result<ProofOfAddictionResult> {
        return try {
            val allApps = usageStatsRepository.getAppUsageStats(days = 14)
            val (trackable, nonTrackable) = allApps.partition { it.isTrackable }
            Result.success(
                ProofOfAddictionResult(
                    trackableApps = trackable,
                    nonTrackableApps = nonTrackable
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
