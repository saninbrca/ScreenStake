package com.detox.app.domain.model

import com.detox.app.util.DateUtils
import kotlin.math.min

/**
 * The ONE ordering for group-challenge participants.
 *
 * Every surface that ranks participants — the results podium, the detail leaderboard and
 * its rank map, the overlay's "#3 of 5" header, the dashboard card and the Friends hub —
 * must sort through this comparator. They each used to hand-roll their own sort, and they
 * had drifted: the podium ranked on opensToday, the hub on timeUsedMinutes, the overlay on
 * opensToday only. Two surfaces could show the same user a different rank at the same
 * moment. Keeping the ordering in one place is the point; do not re-implement it.
 *
 * Ordering, best first:
 *  1. FEWEST exceeded days (equivalently: most clean days — every participant in a group
 *     has the same elapsed-day count, so the two orderings are identical). This is the
 *     primary axis because it is what the challenge actually asks for: stay under the
 *     limit, every day.
 *  2. LEAST total usage across the whole challenge, as the tiebreak — total conscious
 *     opens for a SESSIONS challenge, total minutes for TIME/TIME_BUDGET. Most
 *     non-quitters tie at zero exceeded days, and this is what separates them.
 *  3. EARLIEST joinedAt, as a deterministic final tiebreak. Podium slots are assigned
 *     positionally, so a genuine all-equal tie must not reshuffle between recompositions.
 *
 * Ranking is COSMETIC. It is computed from self-reported client counters and must never
 * gate money — settlement classifies purely on `status` (failed vs not) and splits the
 * bonus equally among winners. See [Participant.totalOpens].
 *
 * Failed participants are NOT excluded here; callers that want them at the bottom (or
 * dropped) filter on [ParticipantStatus.FAILED] themselves.
 */
fun groupRankingComparator(gc: GroupChallenge): Comparator<Participant> =
    groupRankingMetricComparator(gc).thenBy { it.joinedAt }

/**
 * The ranking METRIC alone — steps 1 and 2 of [groupRankingComparator], without the
 * joinedAt tiebreak. Two participants comparing equal here genuinely performed the same
 * and should SHARE a displayed rank (standard competition ranking: 1, 1, 3).
 *
 * Use this to decide ties; use [groupRankingComparator] to order. Ordering needs the
 * joinedAt tiebreak so podium slots — which are assigned positionally — cannot reshuffle
 * between recompositions, but joinedAt must never be the reason two people are shown
 * different ranks.
 */
fun groupRankingMetricComparator(gc: GroupChallenge): Comparator<Participant> =
    compareBy<Participant> { it.exceededDays }
        .thenBy { it.totalUsageFor(gc.limitType) }

/**
 * The whole-challenge usage figure this challenge's limit is expressed in — conscious
 * opens for a session limit, minutes for a time limit or daily budget. Lower is better.
 */
fun Participant.totalUsageFor(limitType: LimitType): Int = when (limitType) {
    LimitType.SESSIONS -> totalOpens
    else -> totalMinutes
}

/**
 * Days of the challenge that have begun, from day 1 through today (or through the final
 * day once the challenge has ended), clamped to 1..durationDays. Identical for every
 * participant — joining is fenced closed at start — which is why ranking on exceeded days
 * is equivalent to ranking on clean days.
 */
fun GroupChallenge.elapsedDays(now: Long = System.currentTimeMillis()): Int {
    if (startDate <= 0L) return 1
    val until = if (endDate > 0L) min(now, endDate) else now
    val elapsed = ((until - startDate) / DateUtils.MILLIS_PER_DAY).toInt() + 1
    return elapsed.coerceIn(1, durationDays.coerceAtLeast(1))
}

/** Days this participant stayed under the limit, for display next to their rank. */
fun Participant.cleanDays(gc: GroupChallenge, now: Long = System.currentTimeMillis()): Int =
    (gc.elapsedDays(now) - exceededDays).coerceAtLeast(0)
