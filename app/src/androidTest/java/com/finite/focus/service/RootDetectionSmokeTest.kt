package com.finite.focus.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scottyab.rootbeer.RootBeerNative
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards the RootBeer native root check (anti-cheat invariant #12 — `isRooted` is captured on
 * every Hard Mode create, true AND false, so a silently-dead native check degrades coverage
 * without any visible failure).
 *
 * Added with the 0.1.0 -> 0.1.1 bump that 16 KB-page-aligned libtoolChecker.so for Play's
 * targetSdk 35+ requirement. Run on a real device — it is the only thing that catches a
 * RootBeer upgrade shipping a .so this device cannot map.
 */
@RunWith(AndroidJUnit4::class)
class RootDetectionSmokeTest {

    /**
     * The one that actually proves the 16 KB-aligned libtoolChecker.so loads. RootBeer's static
     * initializer SWALLOWS UnsatisfiedLinkError and just skips the native check, so
     * isDeviceRooted() would pass green even with a broken .so — this asserts the load itself.
     */
    @Test
    fun nativeLibrary_actuallyLoaded() {
        val loaded = RootBeerNative().wasNativeLibraryLoaded()
        println("SMOKE: libtoolChecker.so loaded = $loaded")
        assertTrue("libtoolChecker.so failed to load — RootBeer native root check is dead", loaded)
    }

    @Test
    fun isDeviceRooted_runsWithoutCrashing() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // RootBeer.isRooted() includes checkForRootNative(), which dlopen()s libtoolChecker.so.
        // An UnsatisfiedLinkError or a broken .so would surface here.
        val rooted = RootDetectionManager.isDeviceRooted(context)
        println("SMOKE: RootDetectionManager.isDeviceRooted = $rooted")
        assertNotNull(rooted)
    }

    @Test
    fun getDetectedRootApps_runsWithoutCrashing() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val apps = RootDetectionManager.getDetectedRootApps(context)
        println("SMOKE: detectedRootApps = $apps")
        assertNotNull(apps)
    }
}
