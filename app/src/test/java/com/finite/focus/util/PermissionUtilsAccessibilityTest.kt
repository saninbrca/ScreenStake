package com.finite.focus.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA M-03: the Settings PERMISSIONS list showed Accessibility as off on a Samsung Galaxy Note 10+
 * while the service was running, because the old check did a strict `String.equals` against the
 * LONG flattened component form. The platform is free to store the SHORT form instead — that is
 * what a relative `android:name=".service.…"` manifest declaration produces — and Samsung does.
 *
 * The Huawei P30 writes the long form, so it stayed green before and after the fix and proves
 * nothing. These tests are the actual proof.
 */
class PermissionUtilsAccessibilityTest {

    private val pkg = "com.finite.focus"
    private val cls = "com.finite.focus.service.AppDetectionAccessibilityService"

    private val talkback =
        "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService"

    private fun enabled(value: String?) = isAccessibilityServiceEnabledIn(value, pkg, cls)

    // ── The two spellings of the same component ───────────────────────────────

    @Test
    fun `long flattened form is recognised`() {
        assertTrue(enabled("$pkg/$cls"))
    }

    @Test
    fun `short flattened form is recognised — the Samsung case that caused M-03`() {
        assertTrue(enabled("$pkg/.service.AppDetectionAccessibilityService"))
    }

    // ── Shape robustness ──────────────────────────────────────────────────────

    @Test
    fun `surrounding whitespace does not hide the entry`() {
        assertTrue(enabled("  $pkg/$cls  "))
        assertTrue(enabled(" $pkg/.service.AppDetectionAccessibilityService "))
    }

    @Test
    fun `our service is found alongside another vendor's service`() {
        assertTrue(enabled("$talkback:$pkg/$cls"))
        assertTrue(enabled("$pkg/.service.AppDetectionAccessibilityService:$talkback"))
    }

    @Test
    fun `a malformed entry does not swallow a valid one`() {
        assertTrue(enabled("this-entry-has-no-slash:$pkg/$cls"))
        assertTrue(enabled("trailing-slash/:$pkg/.service.AppDetectionAccessibilityService"))
    }

    @Test
    fun `empty entries between separators are skipped`() {
        assertTrue(enabled("::$pkg/$cls::"))
    }

    // ── Negatives ─────────────────────────────────────────────────────────────

    @Test
    fun `only a foreign service enabled is false`() {
        assertFalse(enabled(talkback))
    }

    @Test
    fun `an empty setting is false`() {
        assertFalse(enabled(""))
    }

    @Test
    fun `a null setting is false — the setting is absent until something is enabled once`() {
        assertFalse(enabled(null))
    }

    @Test
    fun `a different service from our own package is not our accessibility service`() {
        // Same package, wrong class: the component comparison rejects it, but the legacy
        // safety net still answers true (see below). Documented, not asserted false.
        assertTrue(enabled("$pkg/$pkg.service.UsageTrackingService"))
    }

    // ── The "never narrower than the old check" guarantee ─────────────────────

    /**
     * The old implementation was `enabledServices.contains(packageName)`. The new one ends with
     * that exact expression, so its answer is `componentMatch || legacyContains` — a strict
     * superset. These are the inputs where only the legacy half fires; each one must stay true,
     * because a false negative here produces a wrong permission-loss verdict at the call sites
     * that already use this helper.
     */
    @Test
    fun `a foreign entry embedding our package name still reports true`() {
        assertTrue(enabled("com.other.app/$pkg.Thing"))
    }

    @Test
    fun `an unparseable entry containing our package still reports true`() {
        assertTrue(enabled("$pkg-garbage-no-separator"))
    }

    /**
     * Substring-package impostor. `com.finite.focus2` contains `com.finite.focus`, so the legacy
     * `contains` answered true and the new check answers true as well — behaviour deliberately
     * unchanged, since narrowing it would break the never-narrower guarantee. Recorded here so
     * the next reader knows it is a decision, not an oversight.
     */
    @Test
    fun `a substring-package impostor reports true — unchanged legacy behaviour`() {
        assertTrue(enabled("com.finite.focus2/com.finite.focus2.service.Foo"))
    }
}
