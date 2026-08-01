"use strict";

/**
 * Tests for `runGroupPermissionForfeit` — the B5 fix.
 *
 * The block this replaces never fired: it queried collectionGroup("participants") on
 * userId + status, but those fields do not exist on the documents it reached (that
 * sub-collection holds client-written leaderboard stats). A group participant could revoke
 * permissions, stop being enforced, and still settle as a winner.
 *
 * The load-bearing case here is the POISON-PILL one: the old pass stamps `capturedAt` on
 * every marker it processes, including users for whom nothing was captured — which is every
 * group-only participant the dead block "handled". Those markers exist in production
 * already. This pass must still reach them.
 */

const { test, describe, beforeEach } = require("node:test");
const assert = require("node:assert/strict");

const { runGroupPermissionForfeit, setDb } = require("./helpers/load");
const { FakeFirestore } = require("./helpers/fake-firestore");
const { makeStripe, setStripe } = require("./helpers/fake-stripe");

const DAY = 86_400_000;
const LONG_AGO = Date.now() - 2 * DAY;
const RECENT = Date.now() - 3_600_000;

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

/** The marker the client writes at users/{uid}/permissionStatus/current. */
function marker(userId, fields = {}) {
  db.seed(`users/${userId}/permissionStatus/current`, {
    permissionLostAt: LONG_AGO,
    permissionType: "accessibility",
    ...fields,
  });
}

function group(groupId, participants, overrides = {}) {
  db.seed(`groupChallenges/${groupId}`, {
    status: "active",
    creatorUserId: participants[0].userId,
    participantUserIds: participants.map((p) => p.userId),
    buyInCents: 1000,
    ...overrides,
    participants,
  });
}

function enable() {
  db.seed("config/app", { groupChallengeEnabled: true });
}

function participant(groupId, userId) {
  return db.read(`groupChallenges/${groupId}`).participants.find((p) => p.userId === userId);
}
function markerOf(userId) {
  return db.read(`users/${userId}/permissionStatus/current`);
}
function stripeCalls() {
  return log.filter((e) => String(e.op).startsWith("stripe."));
}

beforeEach(() => {
  db = new FakeFirestore();
  log = [];
  db.log = log;
  setDb(db);
  // Group buy-ins are captured at start, so a real forfeit always meets "succeeded".
  setStripe(makeStripe({ log, status: {} }));
});

// ─────────────────────────────────────────────────────────────────────────────
describe("the flag gate", () => {
  test("no config → DISABLED, and it does not even query", async () => {
    marker("u1");
    group("g1", [P("u1"), P("u2")]);

    const tally = await runGroupPermissionForfeit();

    assert.equal(tally.enabled, false);
    assert.equal(tally.candidates, 0);
    assert.equal(log.filter((e) => e.op === "collectionGroup").length, 0);
    assert.equal(stripeCalls().length, 0);
    assert.equal(participant("g1", "u1").status, "active");
  });

  test("groupChallengeEnabled false → DISABLED", async () => {
    db.seed("config/app", { groupChallengeEnabled: false });
    marker("u1");
    group("g1", [P("u1"), P("u2")]);

    const tally = await runGroupPermissionForfeit();

    assert.equal(tally.enabled, false);
    assert.equal(participant("g1", "u1").status, "active");
  });

  test("a config read failure → DISABLED, never fail-open", async () => {
    const original = db.collection.bind(db);
    db.collection = (name) => {
      if (name === "config") throw new Error("simulated config read failure");
      return original(name);
    };
    marker("u1");
    group("g1", [P("u1"), P("u2")]);

    const tally = await runGroupPermissionForfeit();

    assert.equal(tally.enabled, false);
    assert.equal(stripeCalls().length, 0);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
describe("forfeiting a group participant", () => {
  beforeEach(enable);

  test("a 24h-permission-less participant is forfeited with reason permission_violation", async () => {
    marker("u1");
    group("g1", [P("u1"), P("u2")]);
    setStripe(makeStripe({ log, status: { pi_u1: "succeeded" } }));

    const tally = await runGroupPermissionForfeit();

    assert.equal(tally.candidates, 1);
    assert.equal(tally.forfeited, 1);
    const p = participant("g1", "u1");
    assert.equal(p.status, "failed");
    assert.equal(p.failReason, "permission_violation");
    assert.equal(typeof p.failedAt, "number");
    assert.equal(participant("g1", "u2").status, "active", "other participants untouched");
  });

  test("the buy-in is already captured, so nothing new is charged — it forfeits to the pot", async () => {
    marker("u1");
    group("g1", [P("u1"), P("u2")]);
    setStripe(makeStripe({ log, status: { pi_u1: "succeeded" } }));

    await runGroupPermissionForfeit();

    assert.equal(log.filter((e) => e.op === "stripe.pi.capture").length, 0, "no double-capture");
    assert.equal(participant("g1", "u1").status, "failed");
  });

  test("a still-uncaptured hold IS captured before the participant is marked failed", async () => {
    marker("u1");
    group("g1", [P("u1"), P("u2")]);
    setStripe(makeStripe({ log, status: { pi_u1: "requires_capture" } }));

    await runGroupPermissionForfeit();

    const cap = log.findIndex((e) => e.op === "stripe.pi.capture" && e.id === "pi_u1");
    const write = log.findIndex((e) => e.op === "tx.update");
    assert.ok(cap >= 0 && cap < write, "capture must precede the status write (invariant #1)");
    assert.equal(participant("g1", "u1").status, "failed");
  });

  test("a user in several active groups is forfeited from all of them", async () => {
    marker("u1");
    group("g1", [P("u1"), P("x")]);
    group("g2", [P("u1"), P("y")]);
    setStripe(makeStripe({ log, status: { pi_u1: "succeeded" } }));

    const tally = await runGroupPermissionForfeit();

    assert.equal(tally.forfeited, 2);
    assert.equal(participant("g1", "u1").status, "failed");
    assert.equal(participant("g2", "u1").status, "failed");
  });

  test("only ACTIVE groups are touched — waiting/completed/cancelled are left alone", async () => {
    marker("u1");
    group("live", [P("u1"), P("x")]);
    group("waiting", [P("u1"), P("y")], { status: "waiting" }); // holds still UNCAPTURED
    group("done", [P("u1"), P("z")], { status: "completed" });
    setStripe(makeStripe({ log, status: { pi_u1: "succeeded" } }));

    const tally = await runGroupPermissionForfeit();

    assert.equal(tally.forfeited, 1);
    assert.equal(participant("live", "u1").status, "failed");
    assert.equal(participant("waiting", "u1").status, "active");
    assert.equal(participant("done", "u1").status, "active");
  });
});

// ─────────────────────────────────────────────────────────────────────────────
describe("the poison-pill sidestep", () => {
  beforeEach(enable);

  test("a user ALREADY stamped capturedAt by the dead run is STILL forfeited", async () => {
    // This is the whole reason the pass owns a separate handled-stamp. The old loop stamps
    // capturedAt on every marker it processes — including group-only users for whom the
    // dead block captured nothing — so those markers are already poisoned in production.
    // Keying on capturedAt would skip exactly the people this exists to reach.
    marker("u1", { capturedAt: Date.now() - DAY, captureReason: "permission_loss_24h" });
    group("g1", [P("u1"), P("u2")]);
    setStripe(makeStripe({ log, status: { pi_u1: "succeeded" } }));

    const tally = await runGroupPermissionForfeit();

    assert.equal(tally.candidates, 1, "a poisoned marker must still be a candidate");
    assert.equal(tally.forfeited, 1);
    assert.equal(participant("g1", "u1").status, "failed");
  });

  test("its own handled-stamp is written, and the solo capturedAt is left untouched", async () => {
    const captured = Date.now() - DAY;
    marker("u1", { capturedAt: captured, captureReason: "permission_loss_24h" });
    group("g1", [P("u1"), P("u2")]);
    setStripe(makeStripe({ log, status: { pi_u1: "succeeded" } }));

    await runGroupPermissionForfeit();

    const m = markerOf("u1");
    assert.equal(typeof m.groupForfeitAt, "number");
    assert.equal(m.groupForfeitReason, "permission_loss_24h");
    assert.equal(m.capturedAt, captured, "the solo marker must not be rewritten");
    assert.equal(m.captureReason, "permission_loss_24h");
  });

  test("a stamped groupForfeitAt stops it re-firing", async () => {
    marker("u1", { groupForfeitAt: Date.now() - 3_600_000 });
    group("g1", [P("u1"), P("u2")]);

    const tally = await runGroupPermissionForfeit();

    assert.equal(tally.candidates, 0);
    assert.equal(tally.skipped, 1);
    assert.equal(participant("g1", "u1").status, "active");
    assert.equal(stripeCalls().length, 0);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
describe("who is NOT forfeited", () => {
  beforeEach(enable);

  test("permission lost less than 24h ago is skipped", async () => {
    marker("u1", { permissionLostAt: RECENT });
    group("g1", [P("u1"), P("u2")]);

    const tally = await runGroupPermissionForfeit();

    assert.equal(tally.candidates, 0);
    assert.equal(participant("g1", "u1").status, "active");
  });

  test("permission already restored is skipped", async () => {
    marker("u1", { permissionRestoredAt: Date.now() });
    group("g1", [P("u1"), P("u2")]);

    const tally = await runGroupPermissionForfeit();

    assert.equal(tally.candidates, 0);
    assert.equal(participant("g1", "u1").status, "active");
  });

  test("a user with a marker but no groups is a candidate that forfeits nothing", async () => {
    marker("u1");

    const tally = await runGroupPermissionForfeit();

    assert.equal(tally.candidates, 1);
    assert.equal(tally.forfeited, 0);
    assert.equal(tally.stamped, 0, "left unstamped so a later join is still caught");
    assert.equal(markerOf("u1").groupForfeitAt, undefined);
  });

  test("a user with no permission marker at all is never looked at", async () => {
    group("g1", [P("u1"), P("u2")]);

    const tally = await runGroupPermissionForfeit();

    assert.equal(tally.candidates, 0);
    assert.equal(participant("g1", "u1").status, "active");
  });
});

// ─────────────────────────────────────────────────────────────────────────────
describe("money safety on refusal", () => {
  beforeEach(enable);

  test("a settlement holding the fence defers the forfeit and leaves the marker unstamped", async () => {
    marker("u1");
    group("g1", [P("u1"), P("u2")], { settleLockAt: Date.now() });
    setStripe(makeStripe({ log, status: { pi_u1: "succeeded" } }));

    const tally = await runGroupPermissionForfeit();

    assert.equal(tally.refused, 1);
    assert.equal(tally.forfeited, 0);
    assert.equal(stripeCalls().length, 0, "no capture under a live settlement (invariant #28)");
    assert.equal(participant("g1", "u1").status, "active");
    assert.equal(markerOf("u1").groupForfeitAt, undefined, "must retry next run");
  });

  test("an uncollectable stake refuses — no forfeit for money we do not hold", async () => {
    marker("u1");
    group("g1", [P("u1"), P("u2")]);
    setStripe(makeStripe({ log, status: { pi_u1: "canceled" } }));

    const tally = await runGroupPermissionForfeit();

    assert.equal(tally.refused, 1);
    assert.equal(participant("g1", "u1").status, "active", "capture gate holds");
    assert.equal(markerOf("u1").groupForfeitAt, undefined);
  });

  test("one refusal among several groups leaves the marker unstamped so all are retried", async () => {
    marker("u1");
    group("ok", [P("u1"), P("x")]);
    group("locked", [P("u1"), P("y")], { settleLockAt: Date.now() });
    setStripe(makeStripe({ log, status: { pi_u1: "succeeded" } }));

    const tally = await runGroupPermissionForfeit();

    assert.equal(tally.forfeited, 1);
    assert.equal(tally.refused, 1);
    assert.equal(tally.stamped, 0);
    assert.equal(participant("ok", "u1").status, "failed");
    assert.equal(participant("locked", "u1").status, "active");
    assert.equal(markerOf("u1").groupForfeitAt, undefined);
  });

  test("an already-failed participant is idempotent and stamps cleanly", async () => {
    marker("u1");
    group("g1", [P("u1", "failed"), P("u2")]);

    const tally = await runGroupPermissionForfeit();

    assert.equal(tally.forfeited, 1);
    assert.equal(tally.refused, 0);
    assert.equal(stripeCalls().length, 0);
    assert.equal(typeof markerOf("u1").groupForfeitAt, "number");
  });
});

// ─────────────────────────────────────────────────────────────────────────────
describe("isolation from the solo path", () => {
  beforeEach(enable);

  test("solo challenge documents are never read or written by this pass", async () => {
    marker("u1");
    db.seed("users/u1/challenges/solo1", {
      mode: "hard", status: "active", stripePaymentIntentId: "pi_solo",
    });
    group("g1", [P("u1"), P("u2")]);
    setStripe(makeStripe({ log, status: { pi_u1: "succeeded" } }));

    await runGroupPermissionForfeit();

    assert.equal(db.read("users/u1/challenges/solo1").status, "active", "solo is not this pass's job");
    assert.equal(log.filter((e) => e.op === "stripe.pi.capture" && e.id === "pi_solo").length, 0);
  });

  test("several users are processed independently", async () => {
    marker("u1");
    marker("u2", { permissionLostAt: RECENT }); // too recent
    group("g1", [P("u1"), P("other")]);
    group("g2", [P("u2"), P("other2")]);
    setStripe(makeStripe({ log, status: { pi_u1: "succeeded" } }));

    const tally = await runGroupPermissionForfeit();

    assert.equal(tally.candidates, 1);
    assert.equal(participant("g1", "u1").status, "failed");
    assert.equal(participant("g2", "u2").status, "active");
  });
});
