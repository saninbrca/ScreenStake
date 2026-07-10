package com.detox.app.util

import com.detox.app.BuildConfig

/**
 * Build-level kill switch for real-money features (Hard Mode create, Group buy-in, payout/IBAN,
 * Redemption). Layered ON TOP of the fail-open remote flags in `AppConfig`.
 *
 * The gate is ALWAYS `BuildConfig.MONEY_FEATURES_ENABLED && <serverFlag>`, so:
 *  - Release ships with `MONEY_FEATURES_ENABLED = false` → money is unreachable regardless of what
 *    the (fail-open) server config says or whether it loaded at all — this is the legal floor.
 *  - Debug ships with `MONEY_FEATURES_ENABLED = true` → Hard Mode stays testable with Stripe test keys.
 *  - Flipping the build constant back to `true` (and shipping an update) restores the EXACT prior
 *    server-flag behavior with zero other edits — the server flags resume fine-grained control.
 *
 * This ONLY gates NEW creation + money UI surfaces. It never touches money-authority/settlement
 * paths (Stripe capture/refund, workers, reconciliation), which keep running for active challenges.
 */
object FeatureFlags {

    /** Master money switch, baked in per build type. */
    val moneyEnabled: Boolean get() = BuildConfig.MONEY_FEATURES_ENABLED

    /** Hard Mode creation gate: build floor AND the remote `hardModeEnabled` kill-switch. */
    fun hardModeEnabled(serverFlag: Boolean): Boolean = moneyEnabled && serverFlag

    /** Group Challenge creation/join gate: build floor AND the remote `groupChallengeEnabled` flag. */
    fun groupChallengeEnabled(serverFlag: Boolean): Boolean = moneyEnabled && serverFlag
}
