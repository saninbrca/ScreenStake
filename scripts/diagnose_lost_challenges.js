#!/usr/bin/env node
/* eslint-disable no-console */
/**
 * READ-ONLY diagnostic — "dormant account wrongly-lost Hard-Mode challenges" incident.
 *
 * Reads Firestore (Firebase Admin SDK) + Stripe (read-only list/retrieve only) and prints one
 * row per challenge (surviving doc OR Stripe-reconstructed) with a VERDICT. It NEVER writes,
 * deletes, captures, refunds, or mutates anything. There is not a single mutating SDK call in
 * this file by design — grep it: no `.set(`, `.update(`, `.delete(`, `.create(`, `.capture(`,
 * `.cancel(`, `refunds.create`.
 *
 * WHY Stripe reconstruction is needed:
 *   ChallengeRepositoryImpl.updateChallengeStatus(FAILED) DELETES the Firestore challenge doc
 *   (kt:193-195), and the onChallengeDeleted trigger cascade-deletes its dailyLogs. So a
 *   DEVICE-settled loss leaves NO surviving doc and possibly NO limitExceeded evidence — it
 *   survives only as a Stripe PaymentIntent (metadata.challengeId / metadata.userId). A
 *   SERVER-reconciliation loss, by contrast, keeps its doc (status=failed, failReason set).
 *
 * VERDICT rules (as specified):
 *   - limitExceeded === true anywhere ........................ PROVEN LOSS (legit)
 *   - else, failReason ∈ {permission_violation, usage_violation}
 *     OR an account-level permission/usage capture marker .... PERMISSION LOSS (by-design, harsh)
 *   - else, captured/failed with NO limitExceeded AND NO
 *     permission/usage evidence ............................. WRONGLY LOST (BUG) — refund candidate
 *   - refunded / not-captured / still-active ................. (reported, not a loss)
 *
 * CAVEAT printed at the end: a device-deleted loss destroys its own limitExceeded evidence, so a
 * Stripe-only "captured, no doc, no logs, no marker" row is AMBIGUOUS — it is flagged for HUMAN
 * review, not auto-classified as a definite bug.
 *
 * Usage:
 *   GOOGLE_APPLICATION_CREDENTIALS=/abs/path/serviceAccount.json \
 *   STRIPE_SECRET_KEY=sk_test_...  (or sk_live_... for a real-money incident — still read-only) \
 *   node scripts/diagnose_lost_challenges.js <uid-or-email>
 *
 * If STRIPE_SECRET_KEY is unset it falls back to functions/.env (test key). Firebase project is
 * read from .firebaserc.
 */

const path = require("path");
const fs = require("fs");

const ROOT = path.join(__dirname, "..");
const FN_MODULES = path.join(ROOT, "functions", "node_modules");

// Reuse the already-installed SDKs from functions/node_modules.
let admin;
let Stripe;
try {
  admin = require(path.join(FN_MODULES, "firebase-admin"));
  Stripe = require(path.join(FN_MODULES, "stripe"));
} catch (e) {
  console.error("FATAL: could not load firebase-admin / stripe from functions/node_modules.");
  console.error("Run `npm install` inside functions/ first. Detail:", e.message);
  process.exit(1);
}

// ── Prerequisite gate (no network call happens before this passes) ───────────────
const account = process.argv[2];
if (!account) {
  console.error("Usage: node scripts/diagnose_lost_challenges.js <uid-or-email>");
  console.error("(the ACCOUNT was left as a placeholder in the task — supply the real uid/email)");
  process.exit(2);
}

function readFirebaserc() {
  try {
    const j = JSON.parse(fs.readFileSync(path.join(ROOT, ".firebaserc"), "utf8"));
    return j.projects && j.projects.default;
  } catch { return undefined; }
}

function stripeKeyFromEnvFile() {
  try {
    const env = fs.readFileSync(path.join(ROOT, "functions", ".env"), "utf8");
    const m = env.match(/^\s*STRIPE_SECRET_KEY\s*=\s*(.+)\s*$/m);
    return m ? m[1].trim().replace(/^["']|["']$/g, "") : undefined;
  } catch { return undefined; }
}

const projectId = readFirebaserc();
const stripeKey = process.env.STRIPE_SECRET_KEY || stripeKeyFromEnvFile();

if (!stripeKey) {
  console.error("FATAL: no STRIPE_SECRET_KEY (env or functions/.env).");
  process.exit(2);
}
if (!process.env.GOOGLE_APPLICATION_CREDENTIALS) {
  console.warn(
    "WARN: GOOGLE_APPLICATION_CREDENTIALS is not set. The Admin SDK will try Application " +
    "Default Credentials and will FAIL if none are configured. Provide a service-account JSON."
  );
}
console.log(`Stripe key mode: ${stripeKey.startsWith("sk_live_") ? "LIVE" : "TEST"}  (read-only)`);

// ── Init SDKs ────────────────────────────────────────────────────────────────────
try {
  admin.initializeApp({ projectId, credential: admin.credential.applicationDefault() });
} catch (e) {
  console.error("FATAL: Firebase Admin init failed (missing/invalid credentials).");
  console.error("Set GOOGLE_APPLICATION_CREDENTIALS to a service-account JSON for", projectId, "—", e.message);
  process.exit(1);
}
const db = admin.firestore();
const stripe = new Stripe(stripeKey, { apiVersion: "2023-10-16" });

const PERMISSION_FAIL_REASONS = new Set(["permission_violation", "usage_violation"]);

async function resolveUid(acct) {
  if (!acct.includes("@")) return acct;
  const u = await admin.auth().getUserByEmail(acct);
  return u.uid;
}

// Scan a challenge's dailyLogs subcollection for any limitExceeded === true.
async function anyDayExceeded(uid, cid) {
  try {
    const snap = await db.collection("users").doc(uid)
      .collection("challenges").doc(cid).collection("dailyLogs").get();
    return { present: !snap.empty, exceeded: snap.docs.some((d) => d.data().limitExceeded === true) };
  } catch (e) {
    return { present: false, exceeded: false, error: e.message };
  }
}

async function stripeRefundedCents(piId) {
  try {
    const r = await stripe.refunds.list({ payment_intent: piId, limit: 20 });
    return r.data.reduce((s, x) => s + (x.amount || 0), 0);
  } catch { return 0; }
}

async function main() {
  const uid = await resolveUid(account);
  const now = Date.now();
  console.log(`\nAccount: ${account}  →  uid=${uid}  project=${projectId}\n`);

  // permissionStatus/current
  let perm = {};
  try {
    const ps = await db.collection("users").doc(uid)
      .collection("permissionStatus").doc("current").get();
    perm = ps.exists ? ps.data() : {};
  } catch (e) { console.warn("permissionStatus read failed:", e.message); }
  const permCaptured = !!(perm.permissionLostAt && perm.capturedAt);
  const usageCaptured = !!(perm.usageViolationDetectedAt && perm.usageCapturedAt);
  const accountLevelMarker = permCaptured || usageCaptured;

  // rows keyed by challengeId
  const rows = new Map();

  // 1) Surviving challenge docs
  const chSnap = await db.collection("users").doc(uid).collection("challenges").get();
  for (const d of chSnap.docs) {
    const c = d.data();
    if ((c.mode || "").toLowerCase() !== "hard") continue; // Hard Mode only
    const logs = await anyDayExceeded(uid, d.id);
    rows.set(d.id, {
      challengeId: d.id,
      docPresent: true,
      status: c.status || "",
      failReason: c.failReason || "",
      payoutStatus: c.payoutStatus || "",
      failedAt: c.failedAt || "",
      endDate: c.endDate || "",
      amountCents: typeof c.amountCents === "number" ? c.amountCents : "",
      pi: c.stripePaymentIntentId || c.paymentIntentId || "",
      limitExceeded: logs.exceeded,
      stripeCaptured: null,
      stripeRefunded: null,
    });
  }

  // 2) Stripe reconstruction — list this user's challenge PaymentIntents
  let customerId;
  try {
    const u = await db.collection("users").doc(uid).get();
    customerId = u.exists ? u.data().stripeCustomerId : undefined;
  } catch { /* ignore */ }

  const pis = [];
  if (customerId) {
    let startingAfter;
    // paginate the customer's PaymentIntents (read-only list)
    // eslint-disable-next-line no-constant-condition
    while (true) {
      const page = await stripe.paymentIntents.list({ customer: customerId, limit: 100, starting_after: startingAfter });
      pis.push(...page.data);
      if (!page.has_more) break;
      startingAfter = page.data[page.data.length - 1].id;
    }
  } else {
    console.warn("No stripeCustomerId on the user doc — Stripe reconstruction skipped.");
  }

  for (const pi of pis) {
    const cid = pi.metadata && pi.metadata.challengeId;
    // Only solo Hard-Mode challenge PIs carry challengeId; group buy-ins carry groupId/type.
    if (!cid) continue;
    if (pi.metadata.userId && pi.metadata.userId !== uid) continue;
    const captured = pi.status === "succeeded" || (pi.amount_received || 0) > 0;
    const refunded = await stripeRefundedCents(pi.id);

    let row = rows.get(cid);
    if (!row) {
      // No surviving doc → device-deleted (or reconciliation-pending). Probe orphaned logs.
      const logs = await anyDayExceeded(uid, cid);
      row = {
        challengeId: cid,
        docPresent: false,
        status: "(no doc)",
        failReason: "",
        payoutStatus: "",
        failedAt: "",
        endDate: pi.metadata.durationDays ? `(durationDays=${pi.metadata.durationDays})` : "",
        amountCents: pi.amount,
        pi: pi.id,
        limitExceeded: logs.exceeded,
        orphanedLogsPresent: logs.present,
      };
      rows.set(cid, row);
    }
    row.stripeCaptured = captured;
    row.stripeRefunded = refunded;
    row.stripeStatus = pi.status;
    if (!row.amountCents && pi.amount) row.amountCents = pi.amount;
  }

  // 3) Verdicts
  function verdict(r) {
    const wasRefunded = r.payoutStatus === "refunded" || (r.stripeRefunded || 0) > 0;
    const captured = r.stripeCaptured === true;
    const endMs = typeof r.endDate === "number" ? r.endDate : NaN;
    const endPassed = !Number.isNaN(endMs) && endMs <= now;
    // A challenge is "lost" ONLY if it was actually settled as a loss: status=failed, or the
    // doc was device-deleted (FAILED deletes the doc). Stripe `captured` does NOT imply a loss —
    // Hard Mode challenges >7 days capture the stake UPFRONT (capture_method=automatic) and refund
    // 80% on win, so an active >7d challenge is legitimately captured-but-running.
    const settledLost = r.status === "failed" || r.docPresent === false;

    if (r.limitExceeded === true) return "PROVEN LOSS";
    if (wasRefunded) return "WON (refunded)";

    if (settledLost) {
      if (PERMISSION_FAIL_REASONS.has(r.failReason) || accountLevelMarker) return "PERMISSION LOSS (by-design)";
      return r.docPresent === false
        ? "WRONGLY LOST? (BUG — device-deleted, evidence gone, REVIEW)"
        : "WRONGLY LOST (BUG)";
    }

    // Still active in Firestore → NOT lost.
    if (r.status === "active") {
      if (endPassed && captured) return "WIN PENDING REFUND (past endDate, upfront-captured, unrefunded)";
      return captured ? "ACTIVE (upfront-captured, in progress)" : "ACTIVE";
    }
    return "UNCLEAR";
  }

  const out = [];
  for (const r of rows.values()) {
    out.push({
      challengeId: r.challengeId,
      docPresent: r.docPresent,
      status: r.status,
      failReason: r.failReason,
      payoutStatus: r.payoutStatus,
      failedAt: r.failedAt,
      endDate: r.endDate,
      limitExceeded: r.limitExceeded,
      permMarker: accountLevelMarker ? (permCaptured ? "perm" : "usage") : "",
      stripe: `${r.stripeStatus || "?"}/cap=${r.stripeCaptured}/refund=${r.stripeRefunded ?? "?"}`,
      amountCents: r.amountCents,
      VERDICT: verdict(r),
    });
  }

  console.log("=== Per-challenge report ===");
  console.table(out);

  // Summary
  const counts = {};
  for (const r of out) counts[r.VERDICT] = (counts[r.VERDICT] || 0) + 1;
  console.log("\n=== Summary (counts per verdict) ===");
  console.table(counts);

  const wrongly = out.filter((r) => r.VERDICT.startsWith("WRONGLY LOST"));
  console.log("\n=== WRONGLY-LOST refund candidates (manual review) ===");
  if (wrongly.length === 0) {
    console.log("None.");
  } else {
    for (const r of wrongly) {
      console.log(`  ${r.challengeId}  amountCents=${r.amountCents}  (${r.VERDICT})  stripe=${r.stripe}`);
    }
    const total = wrongly.reduce((s, r) => s + (typeof r.amountCents === "number" ? r.amountCents : 0), 0);
    console.log(`  → total at stake: ${total} cents (€${(total / 100).toFixed(2)})`);
  }

  console.log(
    "\nNOTE: rows marked 'device-deleted, evidence gone, REVIEW' are AMBIGUOUS — the device " +
    "deleted the doc + dailyLogs on a local FAILED, so a genuine limit-loss is indistinguishable " +
    "from a bug by data alone. Decide refunds manually. READ-ONLY: nothing was modified."
  );
}

main()
  .then(() => process.exit(0))
  .catch((e) => { console.error("ERROR:", e && e.stack ? e.stack : e); process.exit(1); });
