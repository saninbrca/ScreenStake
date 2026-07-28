package com.detox.app.domain.usecase

import com.detox.app.domain.model.AppDailyUsage
import com.detox.app.domain.repository.UsageStatsRepository

/**
 * Today's usage for a whole challenge: the per-package figures of ALL its tracked packages,
 * summed.
 *
 * A challenge's limit is SHARED across its apps — two apps at 40 min under a 60 min limit is a
 * breach, not two compliant apps. `TIME_BUDGET` (one accumulator per `challengeId`) and `SESSIONS`
 * (conscious opens per `challengeId`) already work that way by construction; `TIME` reads
 * `UsageStats` per package and therefore has to sum explicitly.
 *
 * Extracted because the same sum has to happen at three sites that must never disagree — the 23:59
 * settlement, the dashboard card, and the live overlay gate. They previously each rolled their own
 * read: the worker summed, the dashboard read only the FIRST package, and the gate read only the
 * FOREGROUND one, so a multi-app challenge showed green all day and then lost at midnight, and each
 * app effectively got the full limit to itself.
 *
 * `opens` is summed alongside `minutes` for parity with the settlement fold, but it is display-only
 * for `TIME`: conscious opens (the `SESSIONS` money input) are counted per challenge in Room and
 * never come from `UsageStats` (invariant #15).
 *
 * Overlay-paused time is deliberately NOT handled here. It is tracked once per challenge
 * (`dailyLogs.overlayPausedMs`), so callers subtract it from this total ONCE — never per package.
 *
 * Empty list (website / adult-only challenges) → zero, matching the previous `getTodayUsageForApp("")`.
 */
suspend fun UsageStatsRepository.getTodayUsageForChallenge(
    packageNames: List<String>
): AppDailyUsage = packageNames.fold(AppDailyUsage(0, 0)) { acc, pkg ->
    val usage = getTodayUsageForApp(pkg)
    AppDailyUsage(
        minutes = acc.minutes + usage.minutes,
        opens = acc.opens + usage.opens
    )
}
