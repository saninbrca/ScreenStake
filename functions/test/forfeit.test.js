"use strict";

/**
 * Characterization tests for `forfeitParticipant` — the body behind the failParticipant
 * handler, extracted so a server-initiated forfeit (Step 7 / B5) can call it too.
 *
 * These pin the money contract the quit flow depends on: the capture gate refuses to
 * write "failed" for a stake it could not collect, the settlement fence blocks a forfeit
 * landing under a settlement in flight, and the participant merge is transactional.
 * The machine-readable error codes are part of the contract — the client maps
 * capture_failed / capture_not_possible to `group_quit_capture_failed`.
 */

const { test, describe, beforeEach } = require("node:test");
const assert = require("node:assert/strict");

const { forfeitParticipant, setDb } = require("./helpers/load");
const { FakeFirestore } = require("./helpers/fake-firestore");
const { makeStripe, setStripe } = require("./helpers/fake-stripe");

const GID = "g1";
const PATH = `groupChallenges/${GID}`;

let db;
let log;

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
    ...overrides,
    participants,
  });
  return participants;
}

function byId(userId) {
  return db.read(PATH).participants.find((p) => p.userId === userId);
}
function opsOf(name) {
  return log.filter((e) => e.op === name);
}
function stripeCalls() {
  return log.filter((e) => String(e.op).startsWith("stripe."));
}

beforeEach(() => {
  db = new FakeFirestore();
  log = [];
  db.log = log;
  setDb(db);
  setStripe(makeStripe({ log }));
});

// ─────────────────────────────────────────────────────────────────────────────
describe("capture gate — never mark failed for money we did not collect", () => {
  test("a live hold is captured, then the participant is marked failed", async () => {
    seedGroup();
    setStripe(makeStripe({ log, status: { pi_u1: "requires_capture" } }));

    const res = await forfeitParticipant(GID, "u1", "self_quit");

    assert.deepEqual(res, { success: true });
    const cap = log.findIndex((e) => e.op === "stripe.pi.capture" && e.id === "pi_u1");
    const write = log.findIndex((e) => e.op === "tx.update");
    assert.ok(cap >= 0, "the stake must be captured");
    assert.ok(cap < write, "capture must precede the status write (invariant #1)");
    assert.equal(byId("u1").status, "failed");
    assert.equal(typeof byId("u1").failedAt, "number");
  });

  test("an already-captured PI (the production group shape) forfeits without re-capturing", async () => {
    // startGroupChallenge captures every buy-in up front, so a real group forfeit always
    // meets status "succeeded" — nothing new is collected, the stake is already in.
    seedGroup();
    setStripe(makeStripe({ log, status: { pi_u1: "succeeded" } }));

    const res = await forfeitParticipant(GID, "u1", "self_quit");

    assert.deepEqual(res, { success: true });
    assert.equal(opsOf("stripe.pi.capture").length, 0, "must not re-capture a settled PI");
    assert.equal(byId("u1").status, "failed");
  });

  test("a non-capturable PI refuses with capture_not_possible and leaves the participant ACTIVE", async () => {
    seedGroup();
    setStripe(makeStripe({ log, status: { pi_u1: "canceled" } }));

    await assert.rejects(() => forfeitParticipant(GID, "u1", "self_quit"), /capture_not_possible/);
    assert.equal(byId("u1").status, "active", "no forfeit without a collected stake");
    assert.equal(opsOf("tx.update").length, 0);
  });

  test("a capture failure refuses with capture_failed and leaves the participant ACTIVE", async () => {
    seedGroup();
    const s = makeStripe({ log, status: { pi_u1: "requires_capture" } });
    s.paymentIntents.capture = async () => { throw new Error("stripe down"); };
    setStripe(s);

    await assert.rejects(() => forfeitParticipant(GID, "u1", "self_quit"), /capture_failed/);
    assert.equal(byId("u1").status, "active");
    assert.equal(opsOf("tx.update").length, 0);
  });

  test("a PI retrieve failure refuses with capture_failed", async () => {
    seedGroup();
    const s = makeStripe({ log });
    s.paymentIntents.retrieve = async () => { throw new Error("stripe down"); };
    setStripe(s);

    await assert.rejects(() => forfeitParticipant(GID, "u1", "self_quit"), /capture_failed/);
    assert.equal(byId("u1").status, "active");
  });

  test("a PI-less legacy participant forfeits with no capture — the one documented exception", async () => {
    seedGroup({ participants: [P("u1", "active", { paymentIntentId: undefined }), P("u2")] });

    const res = await forfeitParticipant(GID, "u1", "self_quit");

    assert.deepEqual(res, { success: true });
    assert.equal(stripeCalls().length, 0);
    assert.equal(byId("u1").status, "failed");
  });
});

// ─────────────────────────────────────────────────────────────────────────────
describe("fence and terminal-state guards — checked BEFORE any Stripe call", () => {
  test("a settlement holding the fence refuses with settlement_in_progress", async () => {
    seedGroup({ settleLockAt: Date.now() });

    await assert.rejects(() => forfeitParticipant(GID, "u1", "self_quit"), /settlement_in_progress/);
    assert.equal(stripeCalls().length, 0, "must not capture under a live settlement");
    assert.equal(byId("u1").status, "active");
  });

  test("a stale fence stamp does not block a forfeit (TTL-bounded)", async () => {
    seedGroup({ settleLockAt: Date.now() - 20 * 60 * 1000 });
    setStripe(makeStripe({ log, status: { pi_u1: "succeeded" } }));

    const res = await forfeitParticipant(GID, "u1", "self_quit");

    assert.deepEqual(res, { success: true });
    assert.equal(byId("u1").status, "failed");
  });

  test("a completed challenge refuses with challenge_settled", async () => {
    seedGroup({ status: "completed" });

    await assert.rejects(() => forfeitParticipant(GID, "u1", "self_quit"), /challenge_settled/);
    assert.equal(stripeCalls().length, 0);
  });

  test("a cancelled challenge refuses with challenge_settled", async () => {
    seedGroup({ status: "cancelled" });

    await assert.rejects(() => forfeitParticipant(GID, "u1", "self_quit"), /challenge_settled/);
    assert.equal(stripeCalls().length, 0);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
describe("idempotency and the transactional merge", () => {
  test("an already-failed participant returns alreadyFailed without touching Stripe", async () => {
    seedGroup({ participants: [P("u1", "failed"), P("u2")] });

    const res = await forfeitParticipant(GID, "u1", "self_quit");

    assert.deepEqual(res, { success: true, alreadyFailed: true });
    assert.equal(stripeCalls().length, 0, "the stake was collected on the first run");
  });

  test("an unknown participant is rejected", async () => {
    seedGroup();
    await assert.rejects(() => forfeitParticipant(GID, "nobody", "self_quit"), /not a participant/i);
  });

  test("a missing group document is rejected", async () => {
    await assert.rejects(() => forfeitParticipant("nope", "u1", "self_quit"), /not found/i);
  });

  test("the merge is transactional and touches only the forfeiting participant", async () => {
    seedGroup({ participants: [P("u1"), P("u2"), P("u3")] });
    setStripe(makeStripe({ log, status: { pi_u1: "succeeded" } }));

    await forfeitParticipant(GID, "u1", "self_quit");

    const read = log.find((e) => e.op === "tx.get");
    const write = log.find((e) => e.op === "tx.update");
    assert.ok(read && write, "read and write must share one transaction");
    assert.ok(log.indexOf(read) < log.indexOf(write));
    assert.equal(byId("u2").status, "active", "other participants are untouched");
    assert.equal(byId("u3").status, "active");
  });

  test("a participant who flipped to a settled status mid-capture is NOT overwritten", async () => {
    // Fail-vs-settle photo finish from the forfeit side: the stake was captured, but
    // settlement already recorded an outcome. The existing record wins and the conflict
    // is logged for manual review rather than silently overwritten.
    seedGroup();
    setStripe(makeStripe({ log, status: { pi_u1: "succeeded" } }));
    db.onTransaction = (n, d) => {
      if (n !== 1) return;
      const gc = d.read(PATH);
      d.seed(PATH, {
        ...gc,
        participants: gc.participants.map((p) =>
          p.userId === "u1" ? { ...p, status: "success", payoutStatus: "completed" } : p
        ),
      });
    };

    const res = await forfeitParticipant(GID, "u1", "self_quit");

    assert.deepEqual(res, { success: true });
    assert.equal(byId("u1").status, "success", "the settled record is kept");
  });
});

// ─────────────────────────────────────────────────────────────────────────────
describe("the reason parameter", () => {
  test("is diagnostic only — it is NOT persisted on the participant entry", async () => {
    // Pins the Step-5 contract deliberately: the extraction added the parameter for Step 7
    // (permission_violation) but changed nothing about what is written. The merge still
    // writes only status + failedAt. If Step 7 decides a server forfeit should be
    // auditable, persisting it is a deliberate change that must flip this assertion.
    seedGroup();
    setStripe(makeStripe({ log, status: { pi_u1: "succeeded" } }));

    await forfeitParticipant(GID, "u1", "permission_violation");

    const p = byId("u1");
    assert.equal(p.status, "failed");
    assert.equal(p.failReason, undefined, "no failReason is written today");
    assert.deepEqual(Object.keys(p).sort(), [
      "amountCents", "displayName", "failedAt", "paymentIntentId", "status", "userId",
    ]);
  });

  test("does not change the money path — any reason forfeits identically", async () => {
    seedGroup();
    setStripe(makeStripe({ log, status: { pi_u1: "succeeded" } }));
    const res = await forfeitParticipant(GID, "u1", "anything_at_all");
    assert.deepEqual(res, { success: true });
    assert.equal(byId("u1").status, "failed");
  });
});
