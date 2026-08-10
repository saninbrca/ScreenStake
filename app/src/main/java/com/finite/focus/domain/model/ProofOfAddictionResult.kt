package com.finite.focus.domain.model

data class ProofOfAddictionResult(
    val trackableApps: List<AppUsageInfo>,
    val nonTrackableApps: List<AppUsageInfo>
)
