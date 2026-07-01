package com.detox.app.domain.model

/**
 * A user-launchable installed app, as enumerated from PackageManager's MAIN/LAUNCHER query.
 * Lightweight on purpose: package name + label only. Icons are loaded lazily per-row in the UI
 * (cached, off the main thread) rather than eagerly here, so the picker stays smooth with 100+ apps.
 */
data class InstalledAppInfo(
    val packageName: String,
    val appName: String,
)
