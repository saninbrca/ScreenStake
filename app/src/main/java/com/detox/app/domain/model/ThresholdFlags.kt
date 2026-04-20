package com.detox.app.domain.model

/**
 * Tracks which usage-threshold notifications (50 / 75 / 90 %) have already been shown
 * for a given challenge on a given calendar day.  Persisted in [daily_logs] so the
 * "fire once per day" guarantee survives service restarts.
 */
data class ThresholdFlags(
    val notified50: Boolean = false,
    val notified75: Boolean = false,
    val notified90: Boolean = false
)
