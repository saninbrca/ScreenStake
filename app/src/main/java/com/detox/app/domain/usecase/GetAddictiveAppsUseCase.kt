package com.detox.app.domain.usecase

import com.detox.app.domain.model.AppUsageInfo
import com.detox.app.domain.model.ProofOfAddictionResult
import com.detox.app.domain.repository.UsageStatsRepository
import java.text.Collator
import javax.inject.Inject

/**
 * Static backstop deny-list of package prefixes — system utilities never worth blocking.
 * Kept intentionally narrow so browsers, messengers, and social apps are never accidentally
 * excluded. The PRIMARY safety guard against blocking critical apps is the dynamic, OEM-agnostic
 * set from [UsageStatsRepository.getNeverBlockablePackages] (home/dialer/SMS/IME/settings/self);
 * this prefix list only catches obvious non-user-facing system packages the dynamic pass may miss.
 */
private val EXCLUDED_PACKAGE_PREFIXES = listOf(
    "com.detox.app",                    // this app itself
    "com.android.phone",                // Phone dialer
    "com.android.contacts",             // Contacts
    "com.android.settings",             // Settings
    "com.android.camera",               // AOSP Camera
    "com.android.systemui",             // System UI
    "com.android.inputmethod",          // Keyboard
    "com.google.android.inputmethod",   // Gboard
    "com.google.android.gms",           // Play Services
    "com.google.android.gsf",           // Google Services Framework
    "com.android.launcher",             // Launcher
    "com.google.android.launcher",
    "com.sec.android.app.launcher",
    "com.miui.home",
    "com.oneplus.launcher",
    "com.huawei.android.launcher",
    "com.android.providers",            // Content providers
    "com.android.server",
    "com.android.shell",
    "com.android.bluetooth",
    "com.android.nfc",
    "com.android.wifi",
    "com.android.calculator",
    "com.android.calendar",
    "com.android.clock",
    "com.android.deskclock",
    "com.google.android.deskclock",
    "com.android.mms",                  // SMS (stock)
    "com.android.messaging",
    "com.android.dialer",
    "com.android.music",
    "com.android.gallery",
    "com.android.email",
    "com.android.packageinstaller",
    "android",
    "com.google.android.packageinstaller",
    // OEM system utilities / device managers (launchable but never sensible block targets).
    "com.google.android.setupwizard",
    "com.android.provision",
    "com.huawei.systemmanager",         // Huawei Optimizer / Phone Manager
    "com.miui.securitycenter",          // MIUI Security
    "com.coloros.safecenter",           // ColorOS / Oppo / Realme
    "com.samsung.android.lool",         // Samsung Device Care
)

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
     * apps first (launcher/dialer/SMS/IME/settings/self) — the hard guarantee that a user can never
     * trap their device — then the static system-utility prefixes.
     */
    private fun shouldInclude(packageName: String, neverBlockable: Set<String>): Boolean {
        if (packageName in neverBlockable) return false
        if (EXCLUDED_PACKAGE_PREFIXES.any { packageName.startsWith(it) }) return false
        return true
    }
}
