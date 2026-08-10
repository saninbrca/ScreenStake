package com.finite.focus.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the usage-evidence gate that decides whether prolonged permission loss actually FAILS a Soft
 * challenge (`PermissionCheckWorker.failAllSoftChallenges` → [hasUsageEvidence]).
 *
 * The rule this guards is a fairness rule, and it cuts both ways:
 *  - The exploit ("turn the permission off, use the blocked app freely, still win") REQUIRES usage,
 *    so evidence of use must fail the challenge.
 *  - On EMUI/Huawei the OS kills the accessibility service on its own. An honest user whose service
 *    died and who never touched the blocked apps must keep their challenge — that user has no stake
 *    and did nothing wrong, and failing them is the failure mode this gate exists to prevent.
 */
class SoftPermissionEvidenceGateTest {

    private val since = 1_000_000L
    private val now = since + 24 * 3_600_000L

    private fun foregrounded(vararg packages: String): () -> Sequence<String> =
        { packages.asSequence() }

    // ── Evidence present ⇒ the challenge fails ───────────────────────────────────

    @Test
    fun `opening a blocked app during the window is evidence`() {
        assertTrue(
            hasUsageEvidence(setOf("com.tiktok"), since, now, foregrounded("com.tiktok"))
        )
    }

    @Test
    fun `any one of several blocked apps is enough`() {
        assertTrue(
            hasUsageEvidence(
                setOf("com.tiktok", "com.instagram.android"),
                since, now,
                foregrounded("com.whatsapp", "com.instagram.android"),
            )
        )
    }

    // ── No evidence ⇒ the challenge survives ─────────────────────────────────────

    @Test
    fun `permission merely off, blocked apps never opened, is NOT evidence`() {
        // The EMUI case: the service died, the user carried on with unrelated apps.
        assertFalse(
            hasUsageEvidence(
                setOf("com.tiktok"),
                since, now,
                foregrounded("com.whatsapp", "com.android.deskclock", "com.spotify.music"),
            )
        )
    }

    @Test
    fun `an empty window produces no evidence`() {
        assertFalse(hasUsageEvidence(setOf("com.tiktok"), since, now, foregrounded()))
    }

    @Test
    fun `a challenge with no observable package can never produce evidence here`() {
        // Website / adult-block challenges persist no packages. They do NOT reach this gate at all
        // (failAllSoftChallenges applies the time-only rule to them); if one ever did, it must not
        // be failed by accident on some other app's usage.
        assertFalse(hasUsageEvidence(emptySet(), since, now, foregrounded("com.tiktok")))
    }

    @Test
    fun `a failed usage query reads as no evidence, never as a loss`() {
        // queryForegroundedPackages returns an empty sequence when the query throws or the
        // PACKAGE_USAGE_STATS grant is gone. Unreadable evidence must never manufacture a loss.
        assertFalse(hasUsageEvidence(setOf("com.tiktok"), since, now) { emptySequence() })
    }

    // ── Window guards ────────────────────────────────────────────────────────────

    @Test
    fun `an unusable window is never queried and never produces evidence`() {
        var queried = false
        val source: () -> Sequence<String> = { queried = true; sequenceOf("com.tiktok") }

        assertFalse("inverted window", hasUsageEvidence(setOf("com.tiktok"), now, since, source))
        assertFalse("zero-length window", hasUsageEvidence(setOf("com.tiktok"), since, since, source))
        assertFalse("absent lostAt", hasUsageEvidence(setOf("com.tiktok"), 0L, now, source))
        assertFalse("the usage query must not run for an unusable window", queried)
    }
}
