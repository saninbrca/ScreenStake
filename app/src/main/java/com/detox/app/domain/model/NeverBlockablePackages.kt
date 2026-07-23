package com.detox.app.domain.model

/**
 * Static backstop deny-list of package prefixes — system utilities never worth blocking.
 * Kept intentionally narrow so browsers, messengers, and social apps are never accidentally
 * excluded.
 *
 * The PRIMARY safety guard against blocking critical apps is the dynamic, OEM-agnostic role
 * resolution in `CriticalPackageResolver` (home/dialer/SMS/IME/settings/alarm/self); this prefix
 * list only catches obvious non-user-facing system packages the dynamic pass may miss.
 *
 * Framework-free on purpose: shared by the picker (`GetAddictiveAppsUseCase`) and by the
 * enforcement-time guard, so both layers agree on what must never be blocked, and both stay
 * protected even when dynamic role resolution fails.
 */
object NeverBlockablePackages {

    val EXCLUDED_PACKAGE_PREFIXES: List<String> = listOf(
        "com.detox.app",                    // this app itself
        "com.android.phone",                // Phone dialer
        "com.android.contacts",             // Contacts
        "com.android.settings",             // Settings
        "com.android.camera",               // AOSP Camera
        "com.android.systemui",             // System UI
        "com.android.inputmethod",          // Keyboard
        "com.google.android.inputmethod",   // Gboard
        "com.google.android.gms",           // Play Services
        "com.google.android.gsf",           // Google Services Framework
        "com.android.launcher",             // Launcher
        "com.google.android.launcher",
        "com.sec.android.app.launcher",
        "com.miui.home",
        "com.oneplus.launcher",
        "com.huawei.android.launcher",
        "com.android.providers",            // Content providers
        "com.android.server",
        "com.android.shell",
        "com.android.bluetooth",
        "com.android.nfc",
        "com.android.wifi",
        "com.android.calculator",
        "com.android.calendar",
        "com.android.clock",
        "com.android.deskclock",
        "com.google.android.deskclock",
        "com.android.mms",                  // SMS (stock)
        "com.android.messaging",
        "com.android.dialer",
        "com.android.music",
        "com.android.gallery",
        "com.android.email",
        "com.android.packageinstaller",
        "android",
        "com.google.android.packageinstaller",
        // OEM system utilities / device managers (launchable but never sensible block targets).
        "com.google.android.setupwizard",
        "com.android.provision",
        "com.huawei.systemmanager",         // Huawei Optimizer / Phone Manager
        "com.miui.securitycenter",          // MIUI Security
        "com.coloros.safecenter",           // ColorOS / Oppo / Realme
        "com.samsung.android.lool",         // Samsung Device Care
        // ── OEM clock / alarm apps ────────────────────────────────────────────────
        // FALLBACK ONLY. The alarm role is resolved dynamically (ACTION_SHOW_ALARMS /
        // ACTION_SET_ALARM), which covers OEMs not listed here. These prefixes catch the
        // remaining case: an OEM clock that declares neither alarm intent, or a device where
        // role resolution fails. Blocking the alarm clock is real user harm (a ringing alarm
        // can't be dismissed, a new one can't be set), so this list errs toward inclusion.
        "com.sec.android.app.clockpackage", // Samsung
        "com.huawei.deskclock",             // Huawei / Honor
        "com.coloros.alarmclock",           // ColorOS (Oppo / Realme)
        "com.oplus.alarmclock",             // ColorOS 12+ (Oppo / Realme)
        "com.oneplus.deskclock",            // OnePlus
        "com.android.BBKClock",             // vivo / iQOO
        "com.zui.deskclock",                // Lenovo / ZUK
        "com.asus.deskclock",               // Asus
        "com.lge.clock",                    // LG
        "com.motorola.blur.alarmclock",     // Motorola
        "com.transsion.deskclock",          // Tecno / Infinix / itel
    )

    /**
     * True if [packageName] matches any entry in [EXCLUDED_PACKAGE_PREFIXES].
     *
     * Prefix (not exact) matching is deliberate — OEMs ship variants such as
     * `com.huawei.deskclock.overseas` under the same family.
     */
    fun matchesExcludedPrefix(packageName: String): Boolean =
        EXCLUDED_PACKAGE_PREFIXES.any { packageName.startsWith(it) }
}
