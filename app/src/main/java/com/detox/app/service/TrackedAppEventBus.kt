package com.detox.app.service

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

object TrackedAppEventBus {

    private val _appOpenEvents = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val appOpenEvents: SharedFlow<String> = _appOpenEvents.asSharedFlow()

    private val _trackedPackages = MutableStateFlow<Set<String>>(emptySet())
    val trackedPackages: StateFlow<Set<String>> = _trackedPackages.asStateFlow()

    /**
     * The package name currently visible in the foreground, updated on every
     * [TYPE_WINDOW_STATE_CHANGED] event regardless of whether the package is tracked.
     * Used by [OverlayManager] to cancel the session countdown timer when the
     * tracked app leaves the foreground.
     */
    private val _currentForegroundPackage = MutableStateFlow<String?>(null)
    val currentForegroundPackage: StateFlow<String?> = _currentForegroundPackage.asStateFlow()

    /**
     * Packages that have been freed for the rest of the day — either because the user
     * accepted the Stage 2 consequence (session-limit challenges) or the challenge
     * evaluation ended. No overlay will be shown for these packages until midnight.
     */
    private val _freedPackagesToday = MutableStateFlow<Set<String>>(emptySet())
    val freedPackagesToday: StateFlow<Set<String>> = _freedPackagesToday.asStateFlow()

    fun emitAppOpen(packageName: String) {
        _appOpenEvents.tryEmit(packageName)
    }

    fun updateTrackedPackages(packages: Set<String>) {
        _trackedPackages.value = packages
    }

    fun updateForegroundPackage(packageName: String) {
        _currentForegroundPackage.value = packageName
    }

    fun markPackageFreeForToday(packageName: String) {
        _freedPackagesToday.value = _freedPackagesToday.value + packageName
    }

    fun clearFreePackages() {
        _freedPackagesToday.value = emptySet()
    }
}
