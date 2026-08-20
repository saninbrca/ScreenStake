package com.finite.focus.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [LogRedaction] — the scrubber that stands between a log message and Sentry.
 *
 * Two duties are tested separately and both matter:
 *  - it must catch what it claims to catch (a leak here is a GDPR problem, not a cosmetic one);
 *  - it must NOT eat ordinary diagnostic text, because a scrubber that mangles messages gets
 *    turned off, and then it protects nothing at all.
 */
class LogRedactionTest {

    // ── Email addresses ──────────────────────────────────────────────────────

    @Test
    fun `redacts a plain email address`() {
        assertEquals(
            "Failed to send password reset to <email>",
            LogRedaction.redact("Failed to send password reset to user@example.com")
        )
    }

    @Test
    fun `redacts addresses with plus tags, dots and subdomains`() {
        val out = LogRedaction.redact("a.b+tag@mail.co.uk and x_y%z@sub.domain.example.org")
        assertEquals("<email> and <email>", out)
    }

    @Test
    fun `redacts an address embedded in surrounding punctuation`() {
        assertEquals(
            "Signed in as (<email>) uid=abc123",
            LogRedaction.redact("Signed in as (jane.doe@example.com) uid=abc123")
        )
    }

    @Test
    fun `leaves a uid untouched`() {
        // uid is the identifier we deliberately keep — Sentry already has it as User.id.
        val msg = "Registered new user uid=Xy7Kp2qLmN0aBcDeFgHiJkL"
        assertEquals(msg, LogRedaction.redact(msg))
    }

    // ── JWTs / Firebase ID tokens ────────────────────────────────────────────

    @Test
    fun `redacts a three segment JWT`() {
        val jwt = "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NSJ9.SflKxwRJSMeKKF2QT4"
        assertEquals("idToken was <token>", LogRedaction.redact("idToken was $jwt"))
    }

    @Test
    fun `redacts a two segment JWT and one with an empty signature`() {
        assertTrue(LogRedaction.redact("eyJhbGciOiJub25lIn0.eyJzdWIiOiIxIn0").contains("<token>"))
        assertTrue(LogRedaction.redact("eyJhbGciOiJub25lIn0.eyJzdWIiOiIxIn0.").contains("<token>"))
    }

    @Test
    fun `does not mistake a dotted package or class name for a JWT`() {
        val msg = "at com.finite.focus.data.remote.firebase.FirebaseAuthService.signInWithGoogle"
        assertEquals(msg, LogRedaction.redact(msg))
    }

    // ── Credential key-value pairs ───────────────────────────────────────────

    @Test
    fun `redacts the value but keeps the key`() {
        // Keeping the key is the point: "an idToken was present but wrong" is the diagnosis.
        assertEquals("idToken=<redacted>", LogRedaction.redact("idToken=ya29AbCdEf123456"))
        assertEquals("accessToken: <redacted>", LogRedaction.redact("accessToken: abc123def456"))
        assertEquals("password=<redacted>", LogRedaction.redact("password=hunter2"))
    }

    @Test
    fun `matches credential keys case insensitively and in snake case`() {
        assertEquals("ID_TOKEN=<redacted>", LogRedaction.redact("ID_TOKEN=abc123"))
        assertEquals("refresh_token=<redacted>", LogRedaction.redact("refresh_token=abc123"))
        assertEquals("Client_Secret=<redacted>", LogRedaction.redact("Client_Secret=shhh"))
    }

    @Test
    fun `redacts a whole quoted credential value including spaces`() {
        assertEquals("""password=<redacted> next""", LogRedaction.redact("""password="a b c" next"""))
    }

    @Test
    fun `leaves prose containing the word password alone`() {
        // No separator, so no value to redact — this is the Firebase failure text we want to read.
        val msg = "The password is invalid or the user does not have a password"
        assertEquals(msg, LogRedaction.redact(msg))
    }

    // ── Google OAuth shapes ──────────────────────────────────────────────────

    @Test
    fun `redacts google access tokens, refresh tokens and api keys`() {
        assertEquals("<token>", LogRedaction.redact("ya29.a0AfH6SMBx-1_2abcDEF"))
        assertEquals("<token>", LogRedaction.redact("1//0abcdefghijklmnop"))
        assertEquals("<token>", LogRedaction.redact("AIzaSyA1b2C3d4E5f6G7h8I9j0KlMnOpQrStUv"))
    }

    @Test
    fun `redacts a standalone bearer token but keeps the scheme`() {
        assertEquals(
            "sending Bearer <token> upstream",
            LogRedaction.redact("sending Bearer abc123.def456-ghi upstream")
        )
    }

    @Test
    fun `redacts scheme and credential together when behind an authorization key`() {
        // Regression guard. The key-value rule used to consume only the word "Bearer" as the
        // value of `Authorization:` and leave the credential itself in the message.
        val out = LogRedaction.redact("Authorization: Bearer abc123.def456-ghi")
        assertFalse(out.contains("abc123"))
        assertEquals("Authorization: <redacted>", out)
    }

    // ── Diagnostic text must survive ─────────────────────────────────────────

    @Test
    fun `leaves the google sign-in status code message intact`() {
        // The whole point of the diagnostics work — this must reach Sentry verbatim.
        val msg = "Google Sign-In failed with ApiException status 10"
        assertEquals(msg, LogRedaction.redact(msg))
    }

    @Test
    fun `leaves ordinary messages and an empty string unchanged`() {
        assertEquals("", LogRedaction.redact(""))
        val msg = "Daily evaluation scheduled — initial delay: 431 min (fires at ~23:59)"
        assertEquals(msg, LogRedaction.redact(msg))
    }

    // ── Combined ─────────────────────────────────────────────────────────────

    @Test
    fun `redacts every secret in a message carrying several at once`() {
        val out = LogRedaction.redact(
            "signin user@example.com idToken=eyJhbGciOiJIUzI1NiJ9.eyJhIjoxfQ.sig key=AIzaSyA1b2C3d4E5f6G7h8I9j0KlMnOpQrStUv"
        )
        assertFalse(out.contains("user@example.com"))
        assertFalse(out.contains("eyJ"))
        assertFalse(out.contains("AIzaSy"))
        assertTrue(out.contains("idToken="))
    }
}
