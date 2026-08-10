package com.finite.focus.service

import com.finite.focus.domain.model.LimitType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins which overlay each limit type resolves to — the decision both the app-open funnel and the
 * two session-timer expiry paths now share (`OverlayManager.dispatchOverlayForLimitType`).
 *
 * The failure this guards against was silent and user-visible: the expiry paths called
 * `handleSessionLimitApp` for EVERY limit type. A TIME challenge carries
 * `limitValueSessions == null`, so `maxOpens` fell to 0, `0 >= 0` selected Stage 2, and a user who
 * took the 5-minute "open anyway" grant at 2 of 60 minutes was shown "Enough for today — you'll get
 * fresh opens tomorrow" while 58 minutes were still available. Wrong screen, wrong sentence.
 *
 * Routing only — nothing here decides whether a challenge is won, lost or charged.
 */
class OverlayRouteTest {

    // ── TIME: the type that was mis-routed ───────────────────────────────────────

    @Test
    fun `time under the limit gets the friction overlay, never the sessions screen`() {
        val route = resolveOverlayRoute(
            limitType = LimitType.TIME,
            limitExceeded = false,
            exceededEarlierToday = false,
        )
        assertEquals(OverlayRoute.TIME_FRICTION, route)
    }

    @Test
    fun `time at or over the limit gets the limit-exceeded screen`() {
        assertEquals(
            OverlayRoute.TIME_LIMIT_EXCEEDED,
            resolveOverlayRoute(LimitType.TIME, limitExceeded = true, exceededEarlierToday = false),
        )
    }

    /** The in-memory latch: once broken today, a later under-limit reading must not free the app. */
    @Test
    fun `time already exceeded earlier today stays on the exceeded screen`() {
        assertEquals(
            OverlayRoute.TIME_LIMIT_EXCEEDED,
            resolveOverlayRoute(LimitType.TIME, limitExceeded = false, exceededEarlierToday = true),
        )
    }

    // ── The other types keep their own handlers ──────────────────────────────────

    @Test
    fun `sessions still routes to the session-limit flow`() {
        // Both stages live behind this route; the open count picks between them.
        for (exceeded in listOf(true, false)) {
            assertEquals(
                OverlayRoute.SESSION_LIMIT,
                resolveOverlayRoute(LimitType.SESSIONS, exceeded, exceededEarlierToday = exceeded),
            )
        }
    }

    @Test
    fun `time budget routes to the budget handler`() {
        assertEquals(
            OverlayRoute.TIME_BUDGET,
            resolveOverlayRoute(LimitType.TIME_BUDGET, limitExceeded = false, exceededEarlierToday = false),
        )
    }

    @Test
    fun `time window routes to the window overlay`() {
        assertEquals(
            OverlayRoute.TIME_WINDOW,
            resolveOverlayRoute(LimitType.TIME_WINDOW, limitExceeded = false, exceededEarlierToday = false),
        )
    }

    /** No limit type may fall through to the SESSIONS screen by accident again. */
    @Test
    fun `only sessions ever resolves to the session-limit screen`() {
        val offenders = LimitType.entries
            .filter { it != LimitType.SESSIONS }
            .flatMap { type ->
                listOf(true to true, true to false, false to true, false to false).map { (a, b) ->
                    type to resolveOverlayRoute(type, a, b)
                }
            }
            .filter { (_, route) -> route == OverlayRoute.SESSION_LIMIT }
        assertEquals(emptyList<Pair<LimitType, OverlayRoute>>(), offenders)
    }
}
