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

    fun emitAppOpen(packageName: String) {
        _appOpenEvents.tryEmit(packageName)
    }

    fun updateTrackedPackages(packages: Set<String>) {
        _trackedPackages.value = packages
    }
}
