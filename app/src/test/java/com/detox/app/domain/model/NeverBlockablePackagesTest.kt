package com.detox.app.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeverBlockablePackagesTest {

    @Test
    fun `OEM clock packages match the fallback prefixes`() {
        listOf(
            "com.huawei.deskclock",
            "com.sec.android.app.clockpackage",
            "com.coloros.alarmclock",
            "com.oplus.alarmclock",
            "com.oneplus.deskclock",
            "com.android.BBKClock",
            "com.zui.deskclock",
            "com.asus.deskclock",
            "com.lge.clock",
            "com.motorola.blur.alarmclock",
            "com.transsion.deskclock",
        ).forEach {
            assertTrue("$it must never be blockable", NeverBlockablePackages.matchesExcludedPrefix(it))
        }
    }

    @Test
    fun `AOSP and Google clock packages still match`() {
        listOf("com.android.clock", "com.android.deskclock", "com.google.android.deskclock")
            .forEach { assertTrue(it, NeverBlockablePackages.matchesExcludedPrefix(it)) }
    }

    @Test
    fun `prefix matching covers OEM package variants`() {
        assertTrue(NeverBlockablePackages.matchesExcludedPrefix("com.huawei.deskclock.overseas"))
    }

    @Test
    fun `pre-existing entries are preserved`() {
        // This fix only ever ADDS to the deny-list. Spot-check one entry per original group so a
        // future refactor can't quietly drop them.
        listOf(
            "com.detox.app",
            "com.android.phone",
            "com.android.settings",
            "com.android.systemui",
            "com.google.android.inputmethod.latin",
            "com.huawei.android.launcher",
            "com.android.providers.media",
            "com.huawei.systemmanager",
            "com.samsung.android.lool",
        ).forEach { assertTrue(it, NeverBlockablePackages.matchesExcludedPrefix(it)) }
    }

    @Test
    fun `ordinary time-sink apps are not excluded`() {
        listOf(
            "com.instagram.android",
            "com.zhiliaoapp.musically",
            "com.google.android.youtube",
            "com.whatsapp",
            "com.reddit.frontpage",
        ).forEach {
            assertFalse("$it must stay blockable", NeverBlockablePackages.matchesExcludedPrefix(it))
        }
    }
}
