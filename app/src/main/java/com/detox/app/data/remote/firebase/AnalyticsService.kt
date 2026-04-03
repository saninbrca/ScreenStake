package com.detox.app.data.remote.firebase

import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Typed wrapper around FirebaseAnalytics.
 * All event logging goes through here — no raw string bundles at call sites.
 */
@Singleton
class AnalyticsService @Inject constructor(
    private val analytics: FirebaseAnalytics
) {

    fun logChallengeCreated(mode: String, limitType: String, durationDays: Int) {
        analytics.logEvent("challenge_created", Bundle().apply {
            putString("mode", mode)
            putString("limit_type", limitType)
            putInt("duration_days", durationDays)
        })
    }

    fun logChallengeCompleted(mode: String, durationDays: Int, totalPoints: Int) {
        analytics.logEvent("challenge_completed", Bundle().apply {
            putString("mode", mode)
            putInt("duration_days", durationDays)
            putInt("total_points", totalPoints)
        })
    }

    fun logChallengeFailed(mode: String) {
        analytics.logEvent("challenge_failed", Bundle().apply {
            putString("mode", mode)
        })
    }

    fun logChallengeAbandoned(mode: String) {
        analytics.logEvent("challenge_abandoned", Bundle().apply {
            putString("mode", mode)
        })
    }

    fun logLimitExceeded(mode: String, packageName: String) {
        analytics.logEvent("limit_exceeded", Bundle().apply {
            putString("mode", mode)
            putString("package_name", packageName)
        })
    }

    fun logRewardRedeemed(rewardId: String, pointsCost: Int) {
        analytics.logEvent("reward_redeemed", Bundle().apply {
            putString("reward_id", rewardId)
            putInt("points_cost", pointsCost)
        })
    }

    /**
     * @param action "skipped" or "opened_anyway"
     */
    fun logBlockingScreenAction(action: String) {
        analytics.logEvent("blocking_screen_action", Bundle().apply {
            putString("action", action)
        })
    }
}
