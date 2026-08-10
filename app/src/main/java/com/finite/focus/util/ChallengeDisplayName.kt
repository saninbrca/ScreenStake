package com.finite.focus.util

import android.content.Context
import com.finite.focus.R
import com.finite.focus.domain.model.Challenge

/**
 * Human-readable name for the apps a challenge tracks.
 *
 * A challenge's limit is SHARED across its apps, so anything addressing the user about that limit
 * has to name the whole set: `appDisplayName` alone is just the FIRST package, which makes a
 * two-app challenge look like it is about one app and hides the rest.
 *
 * One app → its display name. Two → both labels, resolved live from the PackageManager (falling
 * back to the last path segment when the app has since been uninstalled). Three or more → a count,
 * because the labels no longer fit a notification title.
 *
 * Lifted out of `OverlayManager` (where it was private) once the 80 % warning needed the same name
 * from `UsageTrackingService` for the `TIME_BUDGET` seam — the two must never name a challenge
 * differently. Touches only [Context], so it is safe from any component.
 */
fun Challenge.resolveAppsDisplayName(context: Context): String {
    val names = appPackageNames
    return when {
        names.size <= 1 -> appDisplayName
        names.size == 2 -> names.joinToString(", ") { pkg ->
            try {
                val info = context.packageManager.getApplicationInfo(pkg, 0)
                context.packageManager.getApplicationLabel(info).toString()
            } catch (e: Exception) { pkg.substringAfterLast('.') }
        }
        else -> context.getString(R.string.challenge_apps_count, names.size)
    }
}
