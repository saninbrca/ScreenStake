package com.detox.app.domain.usecase

import com.detox.app.domain.model.AppUsageInfo
import com.detox.app.domain.model.NeverBlockablePackages
import com.detox.app.domain.model.ProofOfAddictionResult
import com.detox.app.domain.repository.UsageStatsRepository
import java.text.Collator
import javax.inject.Inject

/**
 * Source of truth for the app picker. Enumerates EVERY user-launchable app (opened or not),
 * removes packages that must never be blockable, joins in usage history for ranking, and returns a
 * two-tier ordering: most-used apps first, then never-used apps alphabetically.
 *
 * Free of Android framework access — all PackageManager / role resolution lives in
 * [UsageStatsRepository], keeping this use case unit-testable and the list decoupled from
 * PACKAGE_USAGE_STATS (it populates even when usage access is off; usage just reads as zero).
 */
class GetAddictiveAppsUseCase @Inject constructor(
    private val usageStatsRepository: UsageStatsRepository,
) {
    suspend operator fun invoke(): Result<ProofOfAddictionResult> {
        return try {
            val launchable = usageStatsRepository.getLaunchableApps()
            val neverBlockable = usageStatsRepository.getNeverBlockablePackages()
            val usageByPackage = usageStatsRepository.getUsageByPackage(days = 14)

            val apps = launchable
                .filter { shouldInclude(it.packageName, neverBlockable) }
                .map { info ->
                    val (avgMinutes, avgOpens) = usageByPackage[info.packageName] ?: (0L to 0)
                    AppUsageInfo(
                        packageName = info.packageName,
                        appName = info.appName,
                        avgDailyMinutes = avgMinutes,
                        avgDailyOpens = avgOpens,
                        isTrackable = true,
                    )
                }

            // Strictly alphabetical (A–Z) by display name, case-insensitive and locale-aware. Fast
            // lookup is handled by the picker's search bar, so apps are listed predictably rather
            // than usage-ranked. PRIMARY strength ignores case and accents, so German umlauts order
            // naturally (e.g. "Ä" near "A"). The usage join above is kept — it feeds the per-row
            // usage summary and the wizard limit prefill — it just no longer drives ordering.
            val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
            val ordered = apps.sortedWith { a, b -> collator.compare(a.appName, b.appName) }

            Result.success(
                ProofOfAddictionResult(
                    trackableApps = ordered,
                    nonTrackableApps = emptyList(),
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * True if [packageName] may appear in the picker. Excludes the dynamically-resolved critical
     * apps first (launcher/dialer/SMS/IME/settings/alarm/self) — the hard guarantee that a user can
     * never trap their device — then the static system-utility prefixes in
     * [NeverBlockablePackages].
     *
     * Pick-time filtering is only the first of two layers: the same critical set is re-consulted at
     * enforcement time (`CriticalPackageResolver.isNeverBlockable`), so a package that becomes
     * critical AFTER a challenge was created is still never blocked.
     */
    private fun shouldInclude(packageName: String, neverBlockable: Set<String>): Boolean {
        if (packageName in neverBlockable) return false
        if (NeverBlockablePackages.matchesExcludedPrefix(packageName)) return false
        return true
    }
}
