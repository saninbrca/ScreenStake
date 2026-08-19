package com.finite.focus.data.remote.firebase

import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Date

/**
 * The reinstall-restore path: a finished challenge coming back from Firestore must bring its REAL
 * end date with it, whichever of the four server stamps recorded it and whichever shape Firestore
 * hands it back in.
 *
 * The shape half is the reason this test exists at all. The surrounding parser uses
 * `d["field"] as? Long ?: 0L`, which yields 0 for a Timestamp-shaped field — and a 0 that reaches
 * the UI renders as 1 Jan 1970, i.e. a wrong date that looks like real data. Unparseable must mean
 * null, never 0.
 */
class ParseEndedAtTest {

    private val millis = 1_755_000_000_000L // 2025-08-12

    // ── Shape robustness ──────────────────────────────────────────────────────

    @Test
    fun `parses a Long`() {
        assertEquals(millis, parseEpochMillis(millis))
    }

    @Test
    fun `parses a Double — the same number after a floating-point round trip`() {
        assertEquals(millis, parseEpochMillis(millis.toDouble()))
    }

    @Test
    fun `parses an Int`() {
        assertEquals(2_000_000_000L, parseEpochMillis(2_000_000_000))
    }

    @Test
    fun `parses a Firestore Timestamp — the shape the naive as-Long cast drops`() {
        val ts = Timestamp(Date(millis))
        assertEquals(millis, parseEpochMillis(ts))
    }

    @Test
    fun `parses a Date`() {
        assertEquals(millis, parseEpochMillis(Date(millis)))
    }

    @Test
    fun `a missing field is null, not zero`() {
        assertNull(parseEpochMillis(null))
    }

    @Test
    fun `a zero is null — it is the absent-field artefact, not an instant at the epoch`() {
        assertNull(parseEpochMillis(0L))
        assertNull(parseEpochMillis(0.0))
    }

    @Test
    fun `a negative value is null`() {
        assertNull(parseEpochMillis(-1L))
    }

    @Test
    fun `a non-date type is null`() {
        assertNull(parseEpochMillis("2025-08-12"))
        assertNull(parseEpochMillis(mapOf("seconds" to 1)))
    }

    // ── Precedence across the four server stamps ──────────────────────────────

    @Test
    fun `failedAt wins — every loss path writes it`() {
        val d = mapOf<String, Any?>(
            "failedAt" to millis,
            "settledAt" to millis + 5_000,
            "payoutDate" to millis + 9_000,
        )
        assertEquals(millis, parseEndedAt(d))
    }

    @Test
    fun `settledAt is used for a soft solo win or unverified outcome`() {
        assertEquals(millis, parseEndedAt(mapOf("settledAt" to millis)))
    }

    @Test
    fun `payoutDate is used for a hard solo win`() {
        assertEquals(millis, parseEndedAt(mapOf("payoutDate" to millis)))
    }

    @Test
    fun `completedAt is the last resort`() {
        assertEquals(millis, parseEndedAt(mapOf("completedAt" to millis)))
    }

    @Test
    fun `precedence skips an unparseable higher-priority field rather than giving up`() {
        // failedAt present but garbage → fall through to the next usable stamp, do not return null.
        val d = mapOf<String, Any?>("failedAt" to 0L, "payoutDate" to Timestamp(Date(millis)))
        assertEquals(millis, parseEndedAt(d))
    }

    @Test
    fun `a doc with no stamps at all yields null, leaving endedAt unset`() {
        assertNull(parseEndedAt(mapOf("status" to "completed")))
    }
}
