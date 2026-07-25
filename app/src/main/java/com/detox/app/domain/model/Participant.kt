package com.detox.app.domain.model

data class Participant(
    val userId: String,
    val displayName: String,
    val paymentIntentId: String,
    val amountCents: Int,
    val status: ParticipantStatus,
    /** TODAY's conscious opens — 0 once the stat doc's day stamp is no longer today. */
    val opensToday: Int = 0,
    /** TODAY's tracked minutes — 0 once the stat doc's day stamp is no longer today. */
    val timeUsedMinutes: Int = 0,
    /**
     * Whole-challenge totals used for ranking (see `groupRankingComparator`). Summed
     * across every day of the challenge, including the current one.
     *
     * SELF-REPORTED, client-written numbers: each participant writes only their own stat
     * doc, and a participant who simply stops writing stops accumulating. Firestore rules
     * enforce monotonicity so a total can never be lowered, but these values are NOT
     * trustworthy enough to gate money and MUST never do so. Ranking is cosmetic —
     * settlement classifies purely on `status` (failed vs not), and the bonus is an equal
     * split among winners. Do not wire a payout to any of these.
     */
    val totalOpens: Int = 0,
    val totalMinutes: Int = 0,
    /** Days on which this participant blew the limit. Clean days = elapsed − this. */
    val exceededDays: Int = 0,
    /** Unix epoch ms when this participant joined (or 0 if unknown). */
    val joinedAt: Long = 0L,
    /** "completed" | "pending_payout" | "refund_failed" | "lost" | "" — set after challenge ends. */
    val payoutStatus: String = "",
    /** Total payout in cents (buyIn refund + bonus for winners, 0 for losers). */
    val finalPayout: Int = 0,
    /**
     * Cents the service still owes this participant because a refund/transfer failed during
     * settlement (`payoutStatus == "refund_failed"`). 0 otherwise. Server-derived — the client
     * never writes it. See completeGroupChallenge's payout gate.
     */
    val payoutOwedCents: Int = 0,
)
