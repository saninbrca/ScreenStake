"use strict";

/**
 * Characterization tests for `runGroupChallengeSettlement`.
 *
 * These pin CURRENT behaviour, not desired behaviour. Where the current behaviour looks
 * wrong it is still encoded exactly as-is and marked with a PINS-A-QUIRK comment — the
 * point of this file is that a later refactor cannot change money outcomes silently.
 *
 * Money surface under pin: winner/loser classification, the pot split and fee maths,
 * the Stripe idempotency keys, capture-before-refund ordering (invariant #1), Stripe
 * staying outside the transactions (#28), and the settleLockAt fence.
 */

const { test, describe, beforeEach } = require("node:test");
const assert = require("node:assert/strict");

const { runGroupChallengeSettlement, setDb } = require("./helpers/load");
const { FakeFirestore } = require("./helpers/fake-firestore");
const { makeStripe, setStripe } = require("./helpers/fake-stripe");

const GID = "g1";
const PATH = `groupChallenges/${GID}`;
const PAST = Date.now() - 86_400_000;
const FUTURE = Date.now() + 86_400_000;

let db;
let log;
let stripe;

/** A participant entry as it lives in the CF-only participants array. */
function P(userId, status = "active", extra = {}) {
  return {
    userId,
    displayName: userId.toUpperCase(),
    paymentIntentId: `pi_${userId}`,
    amountCents: 1000,
    status,
    ...extra,
  };
}

function seedGroup(overrides = {}) {
  const participants = overrides.participants ?? [P("u1"), P("u2")];
  db.seed(PATH, {
    status: "active",
    creatorUserId: "u1",
    participantUserIds: participants.map((p) => p.userId),
    buyInCents: 1000,
    endDate: PAST,
    ...overrides,
    participants,
  });
  return participants;
}

/** Group buy-ins are captured at START in production, so "succeeded" is the real-world default. */
function allSucceeded(participants) {
  const status = {};
  for (const p of participants) status[p.paymentIntentId] = "succeeded";
  return status;
}

function useStripe(cfg = {}) {
  stripe = makeStripe({ log, ...cfg });
  setStripe(stripe);
  return stripe;
}

function participantsAfter() {
  return db.read(PATH).participants;
}
function byId(userId) {
  return participantsAfter().find((p) => p.userId === userId);
}
function opsOf(name) {
  return log.filter((e) => e.op === name);
}
function indexOfOp(pred) {
  return log.findIndex(pred);
}

beforeEach(() => {
  db = new FakeFirestore();
  log = [];
  db.log = log;
  setDb(db);
  useStripe();
});

// ─────────────────────────────────────────────────────────────────────────────
describe("winner / loser classification", () => {
  test('a participant left "active" is settled as a WINNER and refunded', async () => {
    // PINS A QUIRK, deliberately. Settlement classifies on `status` ALONE (invariant #29):
    // "active" means only "never self-failed", NOT "met the limit". Nothing in this body
    // consults usage, dailyLogs or the leaderboard stats. Combined with group challenges
    // never auto-failing, a participant who blew the limit every single day is paid out
    // exactly like one who never opened the app. That is the current contract.
    const ps = seedGroup({ participants: [P("u1"), P("u2", "failed")] });
    useStripe({ status: allSucceeded(ps) });
    db.seed("users/u1", { stripeConnectedAccountId: "acct_1" });

    const res = await runGroupChallengeSettlement(GID);

    assert.equal(res.success, true);
    assert.equal(byId("u1").status, "success");
    assert.equal(byId("u1").payoutStatus, "completed");
    assert.ok(opsOf("stripe.refund.create").some((r) => r.pi === "pi_u1"));
  });

  test('a participant already marked "completed" by a prior run also counts as a winner', async () => {
    const ps = seedGroup({ participants: [P("u1", "completed"), P("u2", "failed")] });
    useStripe({ status: allSucceeded(ps) });
    db.seed("users/u1", { stripeConnectedAccountId: "acct_1" });

    await runGroupChallengeSettlement(GID);

    assert.equal(byId("u1").status, "success");
    assert.equal(byId("u1").payoutStatus, "completed");
  });

  test('a "failed" participant is recorded lost, paid nothing, and gets no Stripe call', async () => {
    const ps = seedGroup({ participants: [P("u1"), P("u2", "failed")] });
    useStripe({ status: allSucceeded(ps) });

    await runGroupChallengeSettlement(GID);

    assert.equal(byId("u2").status, "failed");
    assert.equal(byId("u2").payoutStatus, "lost");
    assert.equal(byId("u2").finalPayout, 0);
    assert.equal(log.filter((e) => String(e.pi) === "pi_u2").length, 0);
  });

  test("nobody failed → full refunds, zero app fee, nobodyFailed flag on the doc", async () => {
    const ps = seedGroup({ participants: [P("u1"), P("u2")] });
    useStripe({ status: allSucceeded(ps) });

    const res = await runGroupChallengeSettlement(GID);

    assert.deepEqual(res, { success: true, nobodyFailed: true, payoutIncomplete: false });
    const doc = db.read(PATH);
    assert.equal(doc.status, "completed");
    assert.equal(doc.nobodyFailed, true);
    assert.equal(doc.appFee, 0);
    assert.equal(doc.prizePool, 0);
    // Full refund, no amount → the whole stake back.
    for (const r of opsOf("stripe.refund.create")) assert.equal(r.amount, undefined);
    for (const p of participantsAfter()) {
      assert.equal(p.status, "completed");
      assert.equal(p.finalPayout, 1000);
    }
  });
});

// ─────────────────────────────────────────────────────────────────────────────
describe("pot split and fee maths", () => {
  test("app fee is 10% of the failed pot, floored (1999 → 199, never 200)", async () => {
    const ps = seedGroup({
      participants: [P("u1"), P("u2", "failed", { amountCents: 1999 })],
    });
    useStripe({ status: allSucceeded(ps) });

    await runGroupChallengeSettlement(GID);

    const doc = db.read(PATH);
    assert.equal(doc.appFee, 199); // Math.floor(1999 * 0.10) — invariant #9
    assert.equal(doc.prizePool, 1800); // 1999 - 199
  });

  test("per-winner bonus is floored and the remainder is left UNDISTRIBUTED", async () => {
    // failedPot 1000 → appFee 100 → distributable 900, split across 7 winners.
    // floor(900/7) = 128; 7 × 128 = 896; 4 cents are never paid to anyone.
    const winners = [1, 2, 3, 4, 5, 6, 7].map((i) => P(`w${i}`));
    const ps = seedGroup({ participants: [...winners, P("L", "failed")] });
    useStripe({ status: allSucceeded(ps) });
    // Connected accounts so the bonus is actually transferred rather than parked in the
    // pending-payout ledger — this test is about the split, not the payout rail.
    for (const w of winners) db.seed(`users/${w.userId}`, { stripeConnectedAccountId: `acct_${w.userId}` });

    await runGroupChallengeSettlement(GID);

    const doc = db.read(PATH);
    assert.equal(doc.appFee, 100);
    assert.equal(doc.prizePool, 900);
    assert.equal(doc.prizePerWinner, 128);
    const paidBonus = opsOf("stripe.transfer.create").reduce((s, t) => s + t.amount, 0);
    assert.equal(paidBonus, 896); // PINS: 4 cents of the pot are retained, not distributed
  });

  test("a winner's own stake comes back at 80%, floored (999 → 799)", async () => {
    const ps = seedGroup({
      participants: [P("u1", "active", { amountCents: 999 }), P("u2", "failed")],
    });
    useStripe({ status: allSucceeded(ps) });

    await runGroupChallengeSettlement(GID);

    const refund = opsOf("stripe.refund.create").find((r) => r.pi === "pi_u1");
    assert.equal(refund.amount, 799); // Math.floor(999 * 0.80) — the 20% haircut
  });

  test("every participant failed → no winners, no bonus, no transfers", async () => {
    const ps = seedGroup({
      participants: [P("u1", "failed"), P("u2", "failed")],
      endDate: FUTURE, // allFailed short-circuits the expiry gate
    });
    useStripe({ status: allSucceeded(ps) });

    const res = await runGroupChallengeSettlement(GID);

    assert.equal(res.success, true);
    assert.equal(db.read(PATH).prizePerWinner, 0);
    assert.equal(opsOf("stripe.transfer.create").length, 0);
    assert.equal(opsOf("stripe.refund.create").length, 0);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
describe("Stripe idempotency keys", () => {
  test("nobody-failed refunds carry refund_<groupId>_<userId>", async () => {
    const ps = seedGroup({ participants: [P("u1"), P("u2")] });
    useStripe({ status: allSucceeded(ps) });

    await runGroupChallengeSettlement(GID);

    const keys = opsOf("stripe.refund.create").map((r) => r.idempotencyKey).sort();
    assert.deepEqual(keys, [`refund_${GID}_u1`, `refund_${GID}_u2`]);
  });

  test("winner stake refunds carry refund_<groupId>_<userId> on BOTH PI branches", async () => {
    const ps = seedGroup({ participants: [P("u1"), P("u2"), P("u3", "failed")] });
    // u1 already captured (production shape), u2 still a live hold (capture-then-refund).
    useStripe({ status: { pi_u1: "succeeded", pi_u2: "requires_capture", pi_u3: "succeeded" } });

    await runGroupChallengeSettlement(GID);

    const keys = opsOf("stripe.refund.create").map((r) => r.idempotencyKey).sort();
    assert.deepEqual(keys, [`refund_${GID}_u1`, `refund_${GID}_u2`]);
  });

  test("prize transfers carry prize_<groupId>_<userId>", async () => {
    const ps = seedGroup({ participants: [P("u1"), P("u2", "failed")] });
    useStripe({ status: allSucceeded(ps) });
    db.seed("users/u1", { stripeConnectedAccountId: "acct_1" });

    await runGroupChallengeSettlement(GID);

    const t = opsOf("stripe.transfer.create");
    assert.equal(t.length, 1);
    assert.equal(t[0].idempotencyKey, `prize_${GID}_u1`);
  });

  test("the pending-payout ledger doc is created once, keyed by groupId", async () => {
    const ps = seedGroup({ participants: [P("u1"), P("u2", "failed")] });
    useStripe({ status: allSucceeded(ps) });
    // No connected account → the bonus is written to the ledger instead of transferred.

    await runGroupChallengeSettlement(GID);

    const created = opsOf("create");
    assert.equal(created.length, 1);
    assert.equal(created[0].path, `users/u1/pendingPayouts/${GID}`);
    assert.equal(created[0].data.amount, 900);
    assert.equal(byId("u1").payoutStatus, "pending_payout");
  });

  test("a second settlement pass cannot double-write the ledger doc (create throws)", async () => {
    const ps = seedGroup({ participants: [P("u1"), P("u2", "failed")] });
    useStripe({ status: allSucceeded(ps) });
    db.seed(`users/u1/pendingPayouts/${GID}`, { amount: 900, status: "requested" });

    await runGroupChallengeSettlement(GID);

    // create() rejected; the pre-existing ledger entry is untouched, and the failure is
    // swallowed as best-effort rather than aborting settlement.
    assert.equal(db.read(`users/u1/pendingPayouts/${GID}`).status, "requested");
    assert.equal(byId("u1").payoutStatus, "pending_payout");
  });
});

// ─────────────────────────────────────────────────────────────────────────────
describe("ordering: invariant #1 (Stripe before Firestore) and #28 (Stripe outside tx)", () => {
  test("a live hold is CAPTURED before it is refunded", async () => {
    const ps = seedGroup({ participants: [P("u1"), P("u2", "failed")] });
    useStripe({ status: { pi_u1: "requires_capture", pi_u2: "succeeded" } });

    await runGroupChallengeSettlement(GID);

    const cap = indexOfOp((e) => e.op === "stripe.pi.capture" && e.id === "pi_u1");
    const ref = indexOfOp((e) => e.op === "stripe.refund.create" && e.pi === "pi_u1");
    assert.ok(cap >= 0 && ref >= 0, "expected both a capture and a refund");
    assert.ok(cap < ref, "capture must precede the refund");
  });

  test("the settleLockAt fence is stamped before any Stripe call", async () => {
    const ps = seedGroup({ participants: [P("u1"), P("u2", "failed")] });
    useStripe({ status: allSucceeded(ps) });

    await runGroupChallengeSettlement(GID);

    const fence = indexOfOp((e) => e.op === "tx.update" && "settleLockAt" in (e.data || {}));
    const firstStripe = indexOfOp((e) => String(e.op).startsWith("stripe."));
    assert.ok(fence >= 0, "expected the fence stamp");
    assert.ok(fence < firstStripe, "fence must be stamped before money moves");
  });

  test("every Stripe call completes before the settlement commit transaction opens", async () => {
    const ps = seedGroup({ participants: [P("u1"), P("u2", "failed")] });
    useStripe({ status: allSucceeded(ps) });
    db.seed("users/u1", { stripeConnectedAccountId: "acct_1" });

    await runGroupChallengeSettlement(GID);

    const lastStripe = log.map((e) => e.op).lastIndexOf("stripe.transfer.create");
    // tx #2 is commitSettlement (tx #1 is the fence).
    const commitBegin = indexOfOp((e) => e.op === "tx.begin" && e.n === 2);
    assert.ok(lastStripe >= 0 && commitBegin >= 0);
    assert.ok(lastStripe < commitBegin, "Stripe must not run inside the merge transaction");
  });

  test("the participants merge is transactional — read and write share one transaction", async () => {
    const ps = seedGroup({ participants: [P("u1"), P("u2", "failed")] });
    useStripe({ status: allSucceeded(ps) });

    await runGroupChallengeSettlement(GID);

    const read = log.find((e) => e.op === "tx.get" && e.n === 2);
    const write = log.find((e) => e.op === "tx.update" && e.n === 2 && "participants" in (e.data || {}));
    assert.ok(read, "commitSettlement must re-read inside the transaction");
    assert.ok(write, "commitSettlement must write the merged array inside the same transaction");
  });
});

// ─────────────────────────────────────────────────────────────────────────────
describe("fence and control flow", () => {
  test("not yet expired and nobody failed → refuses, releases the fence, moves no money", async () => {
    const ps = seedGroup({ participants: [P("u1"), P("u2")], endDate: FUTURE });
    useStripe({ status: allSucceeded(ps) });

    const res = await runGroupChallengeSettlement(GID);

    assert.deepEqual(res, { success: false, reason: "not_expired" });
    assert.equal(log.filter((e) => String(e.op).startsWith("stripe.")).length, 0);
    assert.equal(db.read(PATH).settleLockAt, undefined, "fence must be released");
    assert.equal(db.read(PATH).status, "active");
  });

  test("a fresh settleLockAt makes a concurrent run back off without touching Stripe", async () => {
    const ps = seedGroup({ participants: [P("u1"), P("u2", "failed")], settleLockAt: Date.now() });
    useStripe({ status: allSucceeded(ps) });

    const res = await runGroupChallengeSettlement(GID);

    assert.deepEqual(res, { success: false, reason: "settlement_in_progress" });
    assert.equal(log.filter((e) => String(e.op).startsWith("stripe.")).length, 0);
  });

  test("an already-completed group is idempotent and never re-pays", async () => {
    const ps = seedGroup({ participants: [P("u1"), P("u2", "failed")], status: "completed" });
    useStripe({ status: allSucceeded(ps) });

    const res = await runGroupChallengeSettlement(GID);

    assert.deepEqual(res, { success: true, reason: "already_completed" });
    assert.equal(log.filter((e) => String(e.op).startsWith("stripe.")).length, 0);
  });

  test("a missing group document throws rather than settling silently", async () => {
    await assert.rejects(() => runGroupChallengeSettlement("does-not-exist"), /not found/i);
  });

  test("a single-participant group still settles (there is no <2 guard at settlement)", async () => {
    // PINS: the <2 guard lives in runGroupChallengeStart, not here. A group that somehow
    // reaches settlement with one participant is refunded, not rejected.
    const ps = seedGroup({ participants: [P("solo")] });
    useStripe({ status: allSucceeded(ps) });

    const res = await runGroupChallengeSettlement(GID);

    assert.equal(res.nobodyFailed, true);
    assert.equal(byId("solo").finalPayout, 1000);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
describe("payout failure handling", () => {
  test("a failed stake refund marks the winner unpaid and flags the doc for follow-up", async () => {
    const ps = seedGroup({ participants: [P("u1"), P("u2", "failed")] });
    useStripe({ status: allSucceeded(ps), failRefund: new Set(["pi_u1"]) });

    const res = await runGroupChallengeSettlement(GID);

    assert.equal(byId("u1").payoutStatus, "refund_failed");
    assert.equal(byId("u1").payoutOwedCents, 800); // floor(1000 * 0.80), still owed
    const doc = db.read(PATH);
    assert.equal(doc.payoutIncomplete, true);
    assert.deepEqual(doc.payoutFailedUserIds, ["u1"]);
    assert.equal(doc.status, "completed", "settlement still completes — the debt is recorded, not retried");
    assert.equal(res.success, true);
  });

  test("a failed transfer falls back to the pending-payout ledger so the bonus is not lost", async () => {
    const ps = seedGroup({ participants: [P("u1"), P("u2", "failed")] });
    useStripe({ status: allSucceeded(ps), failTransfer: new Set(["acct_1"]) });
    db.seed("users/u1", { stripeConnectedAccountId: "acct_1" });

    await runGroupChallengeSettlement(GID);

    assert.equal(byId("u1").payoutStatus, "pending_payout");
    assert.ok(opsOf("create").some((c) => c.path === `users/u1/pendingPayouts/${GID}`));
  });

  test("a connected account with payouts disabled routes to the ledger instead of transferring", async () => {
    const ps = seedGroup({ participants: [P("u1"), P("u2", "failed")] });
    useStripe({ status: allSucceeded(ps), accounts: { acct_1: { payouts_enabled: false } } });
    db.seed("users/u1", { stripeConnectedAccountId: "acct_1" });

    await runGroupChallengeSettlement(GID);

    assert.equal(opsOf("stripe.transfer.create").length, 0);
    assert.equal(byId("u1").payoutStatus, "pending_payout");
  });
});

// ─────────────────────────────────────────────────────────────────────────────
describe("fail-vs-settle photo finish", () => {
  test("a participant who fails during the Stripe phase keeps the failed record", async () => {
    const ps = seedGroup({ participants: [P("u1"), P("u2", "failed")] });
    useStripe({ status: allSucceeded(ps) });

    // Flip u1 to "failed" between the fenced snapshot and commitSettlement's re-read —
    // i.e. exactly when failParticipant lands underneath a settlement already in flight.
    db.onTransaction = (n, d) => {
      if (n !== 2) return;
      const gc = d.read(PATH);
      d.seed(PATH, {
        ...gc,
        participants: gc.participants.map((p) =>
          p.userId === "u1" ? { ...p, status: "failed", failedAt: Date.now() } : p
        ),
      });
    };

    await runGroupChallengeSettlement(GID);

    // The failed record WINS — money truth is that their stake was captured. The 80%
    // refund already went out, which is why this is logged for manual review.
    assert.equal(byId("u1").status, "failed");
    assert.ok(opsOf("stripe.refund.create").some((r) => r.pi === "pi_u1"));
  });

  test("a participant absent from the fenced snapshot is never erased by the merge", async () => {
    const ps = seedGroup({ participants: [P("u1"), P("u2", "failed")] });
    useStripe({ status: allSucceeded(ps) });

    db.onTransaction = (n, d) => {
      if (n !== 2) return;
      const gc = d.read(PATH);
      d.seed(PATH, { ...gc, participants: [...gc.participants, P("late", "active")] });
    };

    await runGroupChallengeSettlement(GID);

    const late = byId("late");
    assert.ok(late, "a late joiner must survive the merge");
    assert.equal(late.status, "active", "and must not be given a settlement outcome");
  });
});

// ─────────────────────────────────────────────────────────────────────────────
describe("winner set and payout divisor are the same set (isPaidWinner)", () => {
  test('a "success" participant is counted in the divisor, so the pot is never over-distributed', async () => {
    // Was the pinned over-distribution: the payout loop paid every non-"failed" participant
    // while the divisor counted only active|completed, so a "success" entry — a status this
    // very path writes — took a share the division never accounted for (900-cent pot → 1800
    // paid). Both sites now gate on isPaidWinner, so "success" is paid AND divided by.
    const ps = seedGroup({
      participants: [P("u1", "active"), P("u2", "success"), P("u3", "failed")],
    });
    useStripe({ status: allSucceeded(ps) });

    await runGroupChallengeSettlement(GID);

    const doc = db.read(PATH);
    assert.equal(doc.appFee, 100); // unchanged: 10% of the 1000 failed pot
    assert.equal(doc.prizePool, 900);
    assert.equal(doc.prizePerWinner, 450, "divisor now counts u1 AND u2");

    const bonuses = opsOf("create").map((c) => c.data.amount);
    assert.deepEqual(bonuses, [450, 450]);
    const distributed = bonuses.reduce((s, b) => s + b, 0);
    assert.ok(distributed <= doc.prizePool, `distributed ${distributed} must not exceed pot ${doc.prizePool}`);
  });

  test('a "refunded" participant is neither paid nor counted — no second payout', async () => {
    // "refunded" is written by terminalizeWaitingGroupChallenge when a WAITING challenge is
    // cancelled: the stake is already back. It sat in the same gap as "success", but paying
    // it would have been a SECOND payout. It is now left untouched.
    const ps = seedGroup({
      participants: [P("u1", "active"), P("u2", "refunded"), P("u3", "failed")],
    });
    useStripe({ status: allSucceeded(ps) });

    await runGroupChallengeSettlement(GID);

    const doc = db.read(PATH);
    assert.equal(doc.prizePerWinner, 900, "only u1 is a payable winner");
    assert.equal(byId("u2").status, "refunded", "left exactly as it was");
    assert.equal(byId("u2").payoutStatus, undefined, "no payout recorded");
    assert.equal(byId("u2").finalPayout, undefined);
    assert.equal(
      opsOf("stripe.refund.create").filter((r) => r.pi === "pi_u2").length,
      0,
      "no second refund for an already-refunded participant"
    );
  });

  test("the paid set and the divisor set are identical for a mixed roster", async () => {
    // Property check: whatever the roster, the number of participants that end up with a
    // settlement payout must equal the divisor the bonus was computed from.
    const ps = seedGroup({
      participants: [
        P("a", "active"),
        P("b", "completed"),
        P("c", "success"),
        P("d", "refunded"),
        P("e", "failed"),
      ],
    });
    useStripe({ status: allSucceeded(ps) });

    await runGroupChallengeSettlement(GID);

    const doc = db.read(PATH);
    const paid = participantsAfter().filter((p) => p.status === "success");
    assert.equal(paid.length, 3, "a, b and c are paid");
    assert.equal(doc.prizePerWinner, Math.floor(doc.prizePool / paid.length));
    const distributed = opsOf("create").reduce((s, c) => s + c.data.amount, 0);
    assert.ok(distributed <= doc.prizePool, `distributed ${distributed} must not exceed pot ${doc.prizePool}`);
  });
});
