package com.detox.app.service

import android.content.Context
import com.scottyab.rootbeer.RootBeer
import timber.log.Timber

object RootDetectionManager {

    fun isDeviceRooted(context: Context): Boolean {
        val rootBeer = RootBeer(context)
        return rootBeer.isRooted
    }

    fun checkAndWarn(context: Context, onRooted: () -> Unit) {
        if (isDeviceRooted(context)) {
            Timber.w("RootDetection: device appears to be rooted")
            onRooted()
        }
    }

    fun getDetectedRootApps(context: Context): List<String> {
        return try {
            val rootBeer = RootBeer(context)
            // detectPotentiallyDangerousApps checks for known root management packages
            if (rootBeer.detectPotentiallyDangerousApps()) listOf("root_management_apps_detected")
            else emptyList()
        } catch (e: Exception) {
            Timber.w(e, "RootDetection: could not enumerate root management apps")
            emptyList()
        }
    }
}
