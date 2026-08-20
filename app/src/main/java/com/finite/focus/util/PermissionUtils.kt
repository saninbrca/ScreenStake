package com.finite.focus.util

import android.content.Context
import android.provider.Settings

/**
 * Canonical permission checks — the go-forward single source for permission state queries.
 *
 * Historical note: MainActivity, WelcomeOnboardingScreen, DashboardScreen, PermissionCheckWorker
 * and UsageTrackingService still carry private copies of the accessibility check; new call sites
 * must use this helper instead of adding another copy.
 *
 * Consolidating those five onto this helper is STILL PENDING and was deliberately left out of the
 * M-03 fix: every one of them uses the lenient `contains(packageName)` form, which answered
 * *correctly* on the device that reported M-03, and they sit on the enforcement and
 * permission-loss paths. Rewriting five working call sites to fix one broken one is a trade for a
 * separate, deliberate task.
 */
object PermissionUtils {

    /** Fully-qualified name of [com.finite.focus.service.AppDetectionAccessibilityService]. */
    private const val ACCESSIBILITY_SERVICE_CLASS =
        "com.finite.focus.service.AppDetectionAccessibilityService"

    /** True when [com.finite.focus.service.AppDetectionAccessibilityService] is enabled in system settings. */
    fun isAccessibilityServiceEnabled(context: Context): Boolean =
        isAccessibilityServiceEnabledIn(
            enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ),
            packageName = context.packageName,
            className = ACCESSIBILITY_SERVICE_CLASS
        )
}

/** Separator between entries in [Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES]. */
private const val ENABLED_SERVICES_SEPARATOR = ":"

/**
 * Whether `packageName/className` appears in a raw
 * [Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES] value.
 *
 * ## Why this is not a string comparison (QA M-03)
 * The same component can be written two equally valid ways, and which one you get is up to the
 * OEM's Settings app:
 *
 *  - long form:  `com.finite.focus/com.finite.focus.service.AppDetectionAccessibilityService`
 *  - short form: `com.finite.focus/.service.AppDetectionAccessibilityService`
 *
 * The short form is what a *relative* `android:name=".service.…"` manifest declaration naturally
 * produces. A Huawei P30 writes the long form; the Samsung Galaxy Note 10+ that reported M-03 does
 * not. A strict `String.equals` against the long form therefore reported "Accessibility off" while
 * the service was running. Entries are compared component-wise instead, with the leading dot
 * expanded, so both spellings resolve to the same component.
 *
 * ## Why the unflattening is reimplemented rather than delegated to ComponentName
 * `ComponentName.unflattenFromString` is the natural tool here, but it is a stubbed framework
 * method on the JVM ("Method unflattenFromString in android.content.ComponentName not mocked") and
 * this module's unit tests are all plain-JVM JUnit with no Robolectric. Delegating would leave the
 * short-form case — the entire point of the fix — unprovable in a unit test. [unflattenComponent]
 * therefore mirrors the documented AOSP contract exactly, and is covered directly by
 * `PermissionUtilsAccessibilityTest`.
 *
 * ## Never narrower than the check it replaces
 * A false negative here is worse than a false positive: it is what produces a wrong
 * permission-loss verdict at the call sites that already use this helper. So the last expression
 * is verbatim the previous implementation (`enabledServices.contains(packageName)`), making the
 * result `componentMatch || legacyContains` — a strict superset of the old answer by construction.
 * Tolerance beats strictness. This is also why an unparseable entry falls back to the lenient test
 * instead of being discarded.
 */
internal fun isAccessibilityServiceEnabledIn(
    enabledServices: String?,
    packageName: String,
    className: String
): Boolean {
    if (enabledServices.isNullOrEmpty()) return false

    for (rawEntry in enabledServices.split(ENABLED_SERVICES_SEPARATOR)) {
        val entry = rawEntry.trim()
        if (entry.isEmpty()) continue

        val component = unflattenComponent(entry)
        if (component == null) {
            // An OEM wrote a shape we do not recognise. Keep the entry in play with the lenient
            // legacy test rather than dropping it on the floor.
            if (entry.contains(packageName)) return true
        } else if (component.packageName == packageName && component.className == className) {
            return true
        }
    }

    // Legacy safety net — see "Never narrower" above. Also covers the exotic case of a foreign
    // entry that embeds our package name somewhere the component comparison cannot see it.
    return enabledServices.contains(packageName)
}

/** A `package/class` pair parsed out of a flattened component string. */
private data class FlattenedComponent(val packageName: String, val className: String)

/**
 * Mirrors `android.content.ComponentName.unflattenFromString`, including its edge cases:
 * the separator is the FIRST `/`; a missing separator or an empty class yields `null`; and a class
 * of at least two characters starting with `.` is relative and gets the package prepended.
 */
private fun unflattenComponent(entry: String): FlattenedComponent? {
    val separator = entry.indexOf('/')
    if (separator < 0 || separator + 1 >= entry.length) return null

    val packageName = entry.substring(0, separator)
    val rawClassName = entry.substring(separator + 1)
    val className = if (rawClassName.length >= 2 && rawClassName[0] == '.') {
        packageName + rawClassName
    } else {
        rawClassName
    }
    return FlattenedComponent(packageName, className)
}
