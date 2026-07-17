package com.detox.app.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verifies the subdomain-aware host matching of [AdultDomains.isDomainBlocked]
 * (the same `hostMatches` used by [AdultDomains.isBlocked] after Uri host extraction).
 *
 * Contract: a host matches when it EQUALS a listed domain or ENDS WITH "." + a listed
 * domain (every subdomain depth). Never a bare substring match.
 */
class AdultDomainsMatchTest {

    @Before
    fun setUp() {
        AdultDomains.setDomainsForTest(setOf("pornhub.com", "xvideos.com"))
    }

    // ── Positive: exact + any subdomain depth ────────────────────────────────────

    @Test
    fun `exact domain matches`() {
        assertTrue(AdultDomains.isDomainBlocked("pornhub.com"))
    }

    @Test
    fun `country subdomain matches`() {
        assertTrue(AdultDomains.isDomainBlocked("de.pornhub.com"))
    }

    @Test
    fun `www and m subdomains match`() {
        assertTrue(AdultDomains.isDomainBlocked("www.pornhub.com"))
        assertTrue(AdultDomains.isDomainBlocked("m.pornhub.com"))
        assertTrue(AdultDomains.isDomainBlocked("rt.pornhub.com"))
    }

    @Test
    fun `deep multi-level subdomain matches`() {
        assertTrue(AdultDomains.isDomainBlocked("a.b.c.pornhub.com"))
    }

    @Test
    fun `uppercase and trailing dot are normalized`() {
        assertTrue(AdultDomains.isDomainBlocked("DE.PORNHUB.COM"))
        assertTrue(AdultDomains.isDomainBlocked("de.pornhub.com."))
    }

    // ── Negative: suffix must sit on a dot boundary, never substring ─────────────

    @Test
    fun `notpornhub_com does not match`() {
        assertFalse(AdultDomains.isDomainBlocked("notpornhub.com"))
    }

    @Test
    fun `xpornhub_com does not match`() {
        assertFalse(AdultDomains.isDomainBlocked("xpornhub.com"))
    }

    @Test
    fun `listed domain as subdomain of an evil host does not match`() {
        // Substring matching would wrongly block-flag this; dot-boundary suffixes
        // are "com.evil.com", "evil.com", "com" — none listed.
        assertFalse(AdultDomains.isDomainBlocked("pornhub.com.evil.com"))
    }

    @Test
    fun `unrelated and truncated hosts do not match`() {
        assertFalse(AdultDomains.isDomainBlocked("google.com"))
        assertFalse(AdultDomains.isDomainBlocked("pornhub.co"))
    }
}
