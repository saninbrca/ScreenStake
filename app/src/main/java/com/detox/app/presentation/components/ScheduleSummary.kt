package com.detox.app.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.detox.app.R

/**
 * Shared schedule wording for the challenge detail screen and the wizard review (Step 7),
 * so the two surfaces can never drift apart.
 *
 * Path rule (see ChallengeCreationViewModel.submissionFields): block-path challenges
 * (blockingType == WEBSITE) reuse LimitType.TIME_WINDOW as a 24/7 sentinel with a NULL
 * schedule — so "does a window exist" must key on scheduleStartTime != null, never on
 * the limit type. Callers suppress these rows entirely on the block path.
 */

/** Canonical Mon-first order of the stored weekday keys ("MON".."SUN"). */
val SCHEDULE_WEEKDAYS = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")

/** Localized short label ("Mon"/"Mo") for a stored weekday key; unknown keys pass through. */
@Composable
fun weekdayShortLabel(day: String): String {
    val res = when (day) {
        "MON" -> R.string.weekday_short_mon
        "TUE" -> R.string.weekday_short_tue
        "WED" -> R.string.weekday_short_wed
        "THU" -> R.string.weekday_short_thu
        "FRI" -> R.string.weekday_short_fri
        "SAT" -> R.string.weekday_short_sat
        "SUN" -> R.string.weekday_short_sun
        else -> null
    }
    return if (res != null) stringResource(res) else day
}

/** "07:00 – 22:00" when a window is configured, otherwise the "always active" fallback. */
@Composable
fun timeWindowSummary(startTime: String?, endTime: String?): String =
    if (startTime != null && endTime != null) {
        stringResource(R.string.detail_info_time_window_range, startTime, endTime)
    } else {
        stringResource(R.string.detail_info_time_window_always)
    }

/** "Mon, Wed, Fri" in canonical order; empty (or all seven) selection reads "every day". */
@Composable
fun activeDaysSummary(days: Collection<String>): String {
    if (days.isEmpty() || days.containsAll(SCHEDULE_WEEKDAYS)) {
        return stringResource(R.string.detail_info_active_days_every_day)
    }
    val labels = mutableListOf<String>()
    for (day in SCHEDULE_WEEKDAYS) {
        if (day in days) labels += weekdayShortLabel(day)
    }
    return labels.joinToString(", ")
}
