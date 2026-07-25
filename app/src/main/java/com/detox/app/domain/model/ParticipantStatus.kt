package com.detox.app.domain.model

/**
 * A participant's standing in a group challenge.
 *
 * [COMPLETED] is what `completeGroupChallenge` actually writes for a settled winner
 * (`status: "completed"`, on both the nobody-failed and the someone-failed path).
 * Without it `valueOf("COMPLETED")` threw and the parser's `getOrDefault(ACTIVE)`
 * silently mapped every settled winner back to [ACTIVE] — which made [SUCCESS]
 * unreachable in production and every `== SUCCESS` branch dead code.
 *
 * [SUCCESS] is retained because older documents may still carry `status: "success"`;
 * it means the same thing as [COMPLETED]. Use [hasWon] rather than comparing against
 * either constant directly.
 */
enum class ParticipantStatus { ACTIVE, FAILED, SUCCESS, COMPLETED }

/**
 * True when this participant finished the challenge without failing — i.e. settlement
 * classified them a winner. Covers both the current server spelling
 * ([ParticipantStatus.COMPLETED]) and the legacy one ([ParticipantStatus.SUCCESS]).
 *
 * Display only. Money paths deliberately compare the raw status string against
 * "FAILED" and must stay that way — the server is the authority on who was paid.
 */
val ParticipantStatus.hasWon: Boolean
    get() = this == ParticipantStatus.SUCCESS || this == ParticipantStatus.COMPLETED
