"use strict";

/**
 * Tests for `runDueGroupChallengeSettlement` — the scheduler body behind
 * scheduledGroupChallengeSettlement.
 *
 * Two things matter most here and both are asserted against the boundary doubles rather
 * than inferred: which groups get SELECTED as due, and that the non-LIVE states are
 * provably side-effect-free (zero writes, zero Stripe calls).
 */

const { test, describe, beforeEach } = require("node:test");
const assert = require("node:assert/strict");

const { runDueGroupChallengeSettlement, setDb } = require("./helpers/load");
const { FakeFirestore } = require("./helpers/fake-firestore");
const { makeStripe, setStripe } = require("./helpers/fake-stripe");

const PAST = Date.now() - 86_400_000;
const FUTURE = Date.now() + 86_400_000;

let db;
let log;

function P(userId, status = "active") {
  return {
    userId,
    displayName: userId.toUpperCase(),
    paymentIntentId: `pi_${userId}`,
    amountCents: 1000,
    status,
  };
}

function group(groupId, overrides = {}) {
  const participants = overrides.participants ?? [P(`${groupId}a`), P(`${groupId}b`)];
  db.seed(`groupChallenges/${groupId}`, {
    status: "active",
    creatorUserId: `${groupId}a`,
    participantUserIds: participants.map((p) => p.userId),
    buyInCents: 1000,
    endDate: PAST,
    ...overrides,
    participants,
  });
}

/** LIVE requires BOTH flags; dryRun defaults true, so only an explicit false disarms it. */
function config(cfg) {
  if (cfg !== null) db.seed("config/app", cfg);
}

function stripeCalls() {
  return log.filter((e) => String(e.op).startsWith("stripe."));
}
function writes() {
  return log.filter((e) => ["set", "update", "create", "tx.update", "tx.set"].includes(e.op));
}
function dryRunTargets() {
  // The DRY-RUN log line is the deliverable of that mode — assert on it directly.
  const { fnLogs } = require("./helpers/load");
  return fnLogs
    .filter((l) => l[0] === "info" && l[1] === "groupSettlement: DRY-RUN — would settle")
    .map((l) => l[2]);
}

beforeEach(() => {
  db = new FakeFirestore();
  log = [];
  db.log = log;
  setDb(db);
  setStripe(makeStripe({ log }));
  require("./helpers/load").fnLogs.length = 0;
});

// ─────────────────────────────────────────────────────────────────────────────
describe("arm state", () => {
  test("no config document at all → DISABLED, and it does not even query", async () => {
    group("g1");

    const tally = await runDueGroupChallengeSettlement();

    assert.equal(tally.enabled, false);
    assert.equal(tally.dryRun, true);
    assert.equal(tally.active, 0);
    assert.equal(log.filter((e) => e.op === "query").length, 0, "must return before querying");
    assert.equal(writes().length, 0);
    assert.equal(stripeCalls().length, 0);
  });

  test("enabled flag absent → DISABLED (the default is not consent)", async () => {
    config({ groupSettlementDryRun: false }); // disarmed dry-run, but never enabled
    group("g1");

    const tally = await runDueGroupChallengeSettlement();

    assert.equal(tally.enabled, false);
    assert.equal(stripeCalls().length, 0);
  });

  test("a config read failure → DISABLED + DRY-RUN, never fail-open", async () => {
    // Money paths invert the user-facing fail-OPEN AppConfig contract (invariant #24).
    db.failReads = true;
    const original = db.collection.bind(db);
    db.collection = (name) => {
      if (name === "config") throw new Error("simulated config read failure");
      return original(name);
    };
    group("g1");

    const tally = await runDueGroupChallengeSettlement();

    assert.equal(tally.enabled, false);
    assert.equal(tally.dryRun, true);
    assert.equal(stripeCalls().length, 0);
    assert.equal(writes().length, 0);
  });

  test("enabled without an explicit dryRun:false stays in DRY-RUN", async () => {
    config({ groupSettlementEnabled: true });
    group("g1");

    const tally = await runDueGroupChallengeSettlement();

    assert.equal(tally.enabled, true);
    assert.equal(tally.dryRun, true);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
describe("due selection", () => {
  beforeEach(() => config({ groupSettlementEnabled: true })); // DRY-RUN — selection only

  test("an expired group is selected", async () => {
    group("g1", { endDate: PAST });

    const tally = await runDueGroupChallengeSettlement();

    assert.equal(tally.due, 1);
    assert.equal(tally.skipped, 0);
    assert.equal(dryRunTargets()[0].reason, "expired");
  });

  test("an allFailed group that has NOT yet expired is still selected", async () => {
    // THE correctness case that ruled out a `where("endDate","<=",now)` range filter:
    // everyone forfeited early, their stakes are captured, and nothing would release them
    // until endDate if due-ness were an index range instead of a per-doc computation.
    group("g1", {
      endDate: FUTURE,
      participants: [P("u1", "failed"), P("u2", "failed")],
    });

    const tally = await runDueGroupChallengeSettlement();

    assert.equal(tally.due, 1, "an early total wipeout must settle immediately");
    assert.equal(dryRunTargets()[0].reason, "all_failed");
  });

  test("a live group that is neither expired nor allFailed is skipped", async () => {
    group("g1", { endDate: FUTURE, participants: [P("u1"), P("u2", "failed")] });

    const tally = await runDueGroupChallengeSettlement();

    assert.equal(tally.due, 0);
    assert.equal(tally.skipped, 1);
  });

  test("only status==active groups are scanned — waiting/completed/cancelled are invisible", async () => {
    group("live", { endDate: PAST });
    group("waiting", { status: "waiting", endDate: PAST }); // holds are UNCAPTURED
    group("done", { status: "completed", endDate: PAST });
    group("gone", { status: "cancelled", endDate: PAST });

    const tally = await runDueGroupChallengeSettlement();

    assert.equal(tally.active, 1, "the query must not reach non-active groups");
    assert.equal(tally.due, 1);
    assert.deepEqual(dryRunTargets().map((t) => t.groupId), ["live"]);
  });

  test("a group with no participants and a past endDate is expired, not allFailed", async () => {
    group("g1", { endDate: PAST, participants: [] });

    const tally = await runDueGroupChallengeSettlement();

    assert.equal(tally.due, 1);
    assert.equal(dryRunTargets()[0].reason, "expired");
  });

  test("endDate stored as a Firestore Timestamp is normalised, not treated as 0", async () => {
    group("g1", { endDate: { toMillis: () => PAST } });

    const tally = await runDueGroupChallengeSettlement();

    assert.equal(tally.due, 1);
  });

  test("a mixed population selects exactly the due ones", async () => {
    group("expired", { endDate: PAST });
    group("wiped", { endDate: FUTURE, participants: [P("x", "failed")] });
    group("running", { endDate: FUTURE });
    group("running2", { endDate: FUTURE, participants: [P("y"), P("z", "failed")] });

    const tally = await runDueGroupChallengeSettlement();

    assert.equal(tally.active, 4);
    assert.equal(tally.due, 2);
    assert.equal(tally.skipped, 2);
    assert.deepEqual(dryRunTargets().map((t) => t.groupId).sort(), ["expired", "wiped"]);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
describe("DRY-RUN is side-effect-free", () => {
  test("selects due groups but performs zero writes and zero Stripe calls", async () => {
    config({ groupSettlementEnabled: true, groupSettlementDryRun: true });
    group("g1", { endDate: PAST });
    group("g2", { endDate: FUTURE, participants: [P("u1", "failed")] });

    const tally = await runDueGroupChallengeSettlement();

    assert.equal(tally.due, 2);
    assert.equal(tally.settled, 0);
    assert.equal(stripeCalls().length, 0, "DRY-RUN must not touch Stripe");
    assert.equal(writes().length, 0, "DRY-RUN must not write anything");
    // Specifically: no fence stamp and no status transition.
    assert.equal(db.read("groupChallenges/g1").settleLockAt, undefined);
    assert.equal(db.read("groupChallenges/g1").status, "active");
  });

  test("the dry-run log carries enough to judge a later live flip", async () => {
    config({ groupSettlementEnabled: true });
    group("g1", { endDate: PAST });

    await runDueGroupChallengeSettlement();

    const t = dryRunTargets()[0];
    assert.equal(t.groupId, "g1");
    assert.equal(t.reason, "expired");
    assert.equal(t.participants, 2);
    assert.equal(typeof t.endDate, "number");
  });
});

// ─────────────────────────────────────────────────────────────────────────────
describe("LIVE", () => {
  beforeEach(() => config({ groupSettlementEnabled: true, groupSettlementDryRun: false }));

  test("settles a due group through the existing body", async () => {
    group("g1", { endDate: PAST, participants: [P("u1"), P("u2", "failed")] });
    setStripe(makeStripe({ log, status: { pi_u1: "succeeded", pi_u2: "succeeded" } }));

    const tally = await runDueGroupChallengeSettlement();

    assert.equal(tally.settled, 1);
    assert.equal(tally.failed, 0);
    assert.equal(db.read("groupChallenges/g1").status, "completed");
    assert.ok(stripeCalls().some((c) => c.op === "stripe.refund.create"));
  });

  test("a group already fenced by another settlement run is deferred, not double-paid", async () => {
    // The device path holds the fence right now. The scheduler must back off before any
    // Stripe call — the body returns settlement_in_progress and this counts as deferred.
    group("g1", { endDate: PAST, settleLockAt: Date.now(), participants: [P("u1"), P("u2", "failed")] });
    setStripe(makeStripe({ log, status: { pi_u1: "succeeded", pi_u2: "succeeded" } }));

    const tally = await runDueGroupChallengeSettlement();

    assert.equal(tally.due, 1);
    assert.equal(tally.settled, 0);
    assert.equal(tally.deferred, 1);
    assert.equal(stripeCalls().length, 0, "must not move money behind a live fence");
    assert.equal(db.read("groupChallenges/g1").status, "active");
  });

  test("one group throwing does not abort the rest of the run", async () => {
    group("bad", { endDate: PAST, participants: [P("u1"), P("u2", "failed")] });
    group("good", { endDate: PAST, participants: [P("v1"), P("v2", "failed")] });
    // Make only the bad group's Stripe work explode at the retrieve step.
    const s = makeStripe({ log, status: { pi_v1: "succeeded", pi_v2: "succeeded" } });
    const originalRetrieve = s.paymentIntents.retrieve;
    s.paymentIntents.retrieve = async (id) => {
      if (id === "pi_u1") throw new Error("stripe exploded");
      return originalRetrieve(id);
    };
    setStripe(s);

    const tally = await runDueGroupChallengeSettlement();

    assert.equal(tally.due, 2);
    assert.equal(tally.settled + tally.failed + tally.deferred, 2);
    assert.equal(db.read("groupChallenges/good").status, "completed", "the healthy group still settles");
  });
});
