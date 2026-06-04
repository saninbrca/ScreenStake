import * as functions from "firebase-functions/v1";
import * as admin from "firebase-admin";
import Stripe from "stripe";

admin.initializeApp();

const REGION = "us-central1";
const MILLIS_PER_DAY = 86_400_000;

/**
 * Returns 23:59:59.999 of the day that is durationDays days after startMs.
 * durationDays - 1 because startMs already counts as day 1. Mirrors the client
 * DateUtils.endOfDayMillis — closes the "Last Day Loophole".
 */
function endOfDayMillis(startMs: number, durationDays: number): number {
  const d = new Date(startMs);
  d.setDate(d.getDate() + (durationDays - 1));
  d.setHours(23, 59, 59, 999);
  return d.getTime();
}

/** Normalises a stored value (epoch-millis number OR Firestore Timestamp) to millis. */
function tsToMillis(v: unknown): number {
  if (typeof v === "number") return v;
  if (v && typeof (v as { toMillis?: () => number }).toMillis === "function") {
    return (v as { toMillis: () => number }).toMillis();
  }
  return 0;
}

// ── Stripe ─────────────────────────────────────────────────────────────────────

let _stripe: Stripe | null = null;

function getStripe(): Stripe {
  if (!_stripe) {
    const key = process.env.STRIPE_SECRET_KEY;
    if (!key) throw new Error("STRIPE_SECRET_KEY is not configured.");
    _stripe = new Stripe(key, { apiVersion: "2023-10-16" });
  }
  return _stripe;
}

// ── Auth helper ────────────────────────────────────────────────────────────────

class HttpError extends Error {
  constructor(public readonly status: number, message: string) {
    super(message);
  }
}

async function requireAuth(req: functions.https.Request): Promise<string> {
  const authHeader = req.headers.authorization;
  if (!authHeader?.startsWith("Bearer ")) {
    throw new HttpError(401, "Missing Authorization: Bearer <token>");
  }
  try {
    const decoded = await admin.auth().verifyIdToken(authHeader.slice(7));
    return decoded.uid;
  } catch (e) {
    if (e instanceof HttpError) throw e;
    throw new HttpError(401, "Invalid or expired ID token.");
  }
}

function handleError(tag: string, e: unknown, res: functions.Response): void {
  if (e instanceof HttpError) {
    res.status(e.status).json({ error: e.message });
  } else {
    functions.logger.error(`${tag} error`, e);
    res.status(500).json({ error: "Internal server error." });
  }
}

// ── CORS helper ──────────────────────────────────────────────────────────────
// The admin dashboard is opened from a local file:// (Origin: "null") and other
// origins, so the browser sends a CORS preflight before any POST that carries an
// Authorization header / application/json body. onRequest functions don't get
// automatic CORS, so without this the preflight hits requireAdmin → 401 with no
// Access-Control-Allow-Origin → the browser blocks it ("Failed to fetch").
//
// We send permissive headers (no cookies are used — the Authorization bearer token
// is not a credential in the CORS sense, and the dashboard does not set
// credentials:"include", so "*" is safe) and short-circuit the OPTIONS preflight.
// Native Android callers are unaffected (no preflight; extra response headers are
// harmless). Returns true when the request was a preflight and is now fully handled.
function applyCors(req: functions.https.Request, res: functions.Response): boolean {
  res.set("Access-Control-Allow-Origin", "*");
  res.set("Access-Control-Allow-Methods", "POST, OPTIONS");
  res.set("Access-Control-Allow-Headers", "Authorization, Content-Type");
  res.set("Access-Control-Max-Age", "3600");
  if (req.method === "OPTIONS") {
    res.status(204).send("");
    return true;
  }
  return false;
}

// ── Counters (best-effort dashboard statistics) ─────────────────────────────────
// Atomically bumps fields on counters/global via FieldValue.increment (never
// read-then-write). BEST EFFORT: any failure is logged and swallowed — a counter
// update must NEVER block or fail a payment / challenge operation.
//
// Scope note: challenge active/completed/failed counters track HARD MODE solo
// challenges only — those are the events with an authoritative server-side money
// step (createPaymentIntent / capturePayment / cancelOrRefundPayment / permission
// capture), so the counts stay balanced. Soft Mode challenges have no reliable
// server-side completion signal and are intentionally excluded. totalRevenueCents
// also includes the 10% Group Challenge fee.
const ADMIN_EMAIL = "sanin.brica@gmail.com";

async function bumpCounters(deltas: Record<string, number>): Promise<void> {
  try {
    const update: Record<string, unknown> = { updatedAt: Date.now() };
    for (const [key, value] of Object.entries(deltas)) {
      update[key] = admin.firestore.FieldValue.increment(value);
    }
    await admin.firestore().collection("counters").doc("global").set(update, { merge: true });
  } catch (e) {
    functions.logger.error("bumpCounters failed (non-fatal)", { deltas, error: e });
  }
}

// ── Participant type ───────────────────────────────────────────────────────────

interface Participant {
  userId: string;
  paymentIntentId: string;
  status?: string;
  displayName?: string;
  amountCents?: number;
  payoutStatus?: string;
}

// ── createPaymentIntent ────────────────────────────────────────────────────────

export const createPaymentIntent = functions.region(REGION).https.onRequest(async (req, res) => {
  try {
    const userId = await requireAuth(req);
    const { amountCents, durationDays, challengeId, isGroupChallenge } = req.body as {
      amountCents: number;
      durationDays: number;
      challengeId: string;
      isGroupChallenge?: boolean;
    };

    if (!amountCents || amountCents < 500) throw new HttpError(400, "Minimum amount is €5.00.");
    if (!durationDays || durationDays < 1) throw new HttpError(400, "Duration must be at least 1 day.");

    // Group Challenges always use manual capture (5-day auth window).
    // Hard Mode keeps the durationDays > 7 → automatic logic unchanged.
    const isImmediateCapture = isGroupChallenge ? false : durationDays > 7;
    const customerId = await getOrCreateStripeCustomer(userId);

    const paymentIntent = await getStripe().paymentIntents.create({
      amount: amountCents,
      currency: "eur",
      customer: customerId,
      capture_method: isImmediateCapture ? "automatic" : "manual",
      metadata: { userId, challengeId, durationDays: durationDays.toString() },
      description: `Detox Hard Mode challenge — ${challengeId}`,
    });

    // NOTE: the challenge doc is intentionally NOT written here. The client's saveChallenge()
    // performs a single full CREATE under this same challengeId after the PaymentSheet confirms.
    // Firestore rules allow all fields on create but block status/endDate/amountCents/
    // stripePaymentIntentId on a client UPDATE — so pre-writing the doc here turned that create into
    // a rejected update and left a gutted doc, which made server-side win validation fail forever.
    // stripeCustomerId still lives on the user doc (getOrCreateStripeCustomer) and nothing reads it
    // from the challenge doc; metadata.challengeId (above) keeps the PI bound to the same cid.

    // Counter: a solo Hard Mode challenge is being started. Group buy-ins are tracked
    // separately and excluded here. (Best-effort; PaymentSheet cancel may slightly over-count.)
    if (!isGroupChallenge) {
      await bumpCounters({ totalActiveChallenges: 1 });
    }

    res.json({ paymentIntentId: paymentIntent.id, clientSecret: paymentIntent.client_secret, isImmediateCapture });
  } catch (e) { handleError("createPaymentIntent", e, res); }
});

// ── capturePayment ─────────────────────────────────────────────────────────────

export const capturePayment = functions.region(REGION).https.onRequest(async (req, res) => {
  try {
    const userId = await requireAuth(req);
    const { paymentIntentId } = req.body as { paymentIntentId: string };
    if (!paymentIntentId) throw new HttpError(400, "paymentIntentId is required.");

    // Ownership check (IDOR guard): the PaymentIntent must belong to the caller.
    // createPaymentIntent stamps metadata.userId on every PI; reject any attempt to
    // capture a PaymentIntent that carries a different owner. (PIs with no userId
    // metadata are legacy/test-only and fall through unchanged.)
    const owned = await getStripe().paymentIntents.retrieve(paymentIntentId);
    if (owned.metadata?.userId && owned.metadata.userId !== userId) {
      throw new HttpError(403, "PaymentIntent does not belong to caller.");
    }

    // Idempotent capture — branch on the status of the PI we already fetched (no extra Stripe call).
    // CRITICAL: a `success` response ALWAYS means "the stake is captured"; callers gate FAILED on it.
    //  - succeeded         → money is ALREADY gone (>7-day auto-capture, or a racing/duplicate caller).
    //                        Do NOT re-capture, re-bump counters, or re-write paymentCaptures.
    //  - requires_capture  → ≤7-day manual-capture stake → capture now, record + bump counters once.
    //  - anything else     → not capturable (canceled / requires_payment_method / …) → surface a real
    //                        error so the caller leaves the challenge ACTIVE.
    if (owned.status === "succeeded") {
      // TODO(counter-gap): auto-captured (>7d) losses never bump failed/revenue counters — Stripe
      // captured at challenge creation with no CF involvement, so this branch cannot tell a genuine
      // first-time >7d loss from a benign re-capture race. Bumping here would double-count manual
      // captures; a dedicated server-side fail-accounting source is needed. Out of scope here.
      res.json({ success: true, alreadyCaptured: true });
      return;
    }
    if (owned.status !== "requires_capture") {
      throw new HttpError(409, `PaymentIntent is not capturable (status: ${owned.status}).`);
    }

    const paymentIntent = await getStripe().paymentIntents.capture(paymentIntentId);

    await admin.firestore()
      .collection("users").doc(userId)
      .collection("paymentCaptures").add({
        paymentIntentId,
        amountCaptured: paymentIntent.amount_received,
        capturedAt: admin.firestore.FieldValue.serverTimestamp(),
      });

    // Counter: Hard Mode fail (worker fail path / abandon / emergency unlock). The full captured
    // stake is service revenue. Bumped ONLY on a fresh capture (never the succeeded branch above),
    // so an auto-captured / racing re-capture never double-counts.
    await bumpCounters({
      totalActiveChallenges: -1,
      totalFailedChallenges: 1,
      totalRevenueCents: paymentIntent.amount_received,
    });

    res.json({ success: true, alreadyCaptured: false });
  } catch (e) { handleError("capturePayment", e, res); }
});

// ── cancelOrRefundPayment ──────────────────────────────────────────────────────

export const cancelOrRefundPayment = functions.region(REGION).https.onRequest(async (req, res) => {
  try {
    const verifiedUserId = await requireAuth(req);
    const { paymentIntentId, challengeId, amountCents, partialRefundCents } = req.body as {
      paymentIntentId: string;
      challengeId?: string;
      amountCents?: number;
      partialRefundCents?: number;
    };
    if (!paymentIntentId) throw new HttpError(400, "paymentIntentId is required.");

    const pi = await getStripe().paymentIntents.retrieve(paymentIntentId);
    const fullAmount = pi.amount;
    let refundedAmount: number;

    // Server-side validation for solo Hard Mode completion refunds (non-redemption path).
    // The redemption/partial-refund branch is validated by its own stored refundAmountCents
    // and uses the original PaymentIntent, so it is left intact below.
    let serverAmountCents = amountCents;
    if (!(partialRefundCents && partialRefundCents > 0)) {
      if (!challengeId) throw new HttpError(400, "challengeId is required.");

      const challengeSnap = await admin.firestore()
        .collection("users").doc(verifiedUserId)
        .collection("challenges").doc(challengeId).get();
      if (!challengeSnap.exists) throw new HttpError(404, "Challenge not found.");
      const challenge = challengeSnap.data()!;

      // 1. endDate must have passed — server clock only, never trust client time
      const endDate: number = typeof challenge["endDate"] === "number"
        ? challenge["endDate"]
        : (challenge["endDate"] as admin.firestore.Timestamp)?.toMillis?.() ?? 0;
      if (endDate <= 0 || Date.now() < endDate) {
        throw new HttpError(400, "Challenge has not reached its end date.");
      }

      // 2. Idempotency — refuse to refund a challenge that was already paid out
      if (challenge["payoutStatus"] === "refunded") {
        throw new HttpError(409, "Challenge already refunded.");
      }

      // 3. The PaymentIntent must match the one stored on the challenge
      if (challenge["stripePaymentIntentId"] !== paymentIntentId) {
        throw new HttpError(400, "paymentIntentId does not match challenge.");
      }

      // 4. Win-gate (FAIL-OPEN): deny the win-refund only if the server can POSITIVELY
      //    see a limit-exceeded day in the challenge's daily logs. A limit-exceeded day
      //    means the stake should have been captured, not refunded — so this catches a
      //    cheater who edits local Room state to fake a win (their real limitExceeded=true
      //    has already synced to Firestore). Absence of logs NEVER denies (sync is
      //    best-effort), so a legitimate winner is never wrongly refused.
      //    NOTE: the daily logs are still client-writable per Firestore rules, so a
      //    sophisticated cheater who also rewrites/deletes the Firestore logs can bypass
      //    this. Closing that requires rules hardening (see security report) — this gate
      //    only raises the bar for the Room-only tamper path.
      const logsSnap = await admin.firestore()
        .collection("users").doc(verifiedUserId)
        .collection("challenges").doc(challengeId)
        .collection("dailyLogs").get();
      const anyDayExceeded = logsSnap.docs.some((d) => d.data()["limitExceeded"] === true);
      if (anyDayExceeded) {
        throw new HttpError(400, "Challenge had limit-exceeded days — not eligible for win refund.");
      }

      // 5. Recompute the 80% refund from the stored stake — ignore client-supplied amountCents
      const storedStake: number = typeof challenge["amountCents"] === "number" ? challenge["amountCents"] : 0;
      serverAmountCents = Math.floor(storedStake * 0.80);
    } else {
      // ── Redemption refund path (partialRefundCents > 0) — server-validated ──────────
      // Previously this branch refunded the client-supplied amount with NO validation.
      // Now: re-fetch the redemption challenge, verify it, and recompute the 60% refund
      // from the ORIGINAL challenge's stored stake. The client's partialRefundCents is
      // never trusted.
      if (!challengeId) throw new HttpError(400, "challengeId is required.");

      const redSnap = await admin.firestore()
        .collection("users").doc(verifiedUserId)
        .collection("challenges").doc(challengeId).get();
      if (!redSnap.exists) throw new HttpError(404, "Redemption challenge not found.");
      const red = redSnap.data()!;

      // 1. Must actually be a redemption challenge
      if (red["isRedemption"] !== true) {
        throw new HttpError(400, "Not a redemption challenge.");
      }

      // 2. Idempotency — never refund the same redemption twice
      if (red["payoutStatus"] === "refunded") {
        throw new HttpError(409, "Redemption already refunded.");
      }

      // 3. PI binding — the supplied PI must match the stored original PaymentIntent
      if (red["originalPaymentIntentId"] !== paymentIntentId) {
        throw new HttpError(400, "paymentIntentId does not match the redemption's original PaymentIntent.");
      }

      // 4. endDate must have passed — server clock only
      const redEndDate: number = typeof red["endDate"] === "number"
        ? red["endDate"]
        : (red["endDate"] as admin.firestore.Timestamp)?.toMillis?.() ?? 0;
      if (redEndDate <= 0 || Date.now() < redEndDate) {
        throw new HttpError(400, "Redemption challenge has not reached its end date.");
      }

      // 5. Recompute 60% from the ORIGINAL challenge's stored stake (amountCents is
      //    update-protected by Firestore rules, so it is trustworthy). The redemption
      //    challenge itself has amountCents = 0 (no new payment).
      const originalChallengeId = red["originalChallengeId"] as string | undefined;
      if (!originalChallengeId) throw new HttpError(400, "Redemption is missing originalChallengeId.");
      const origSnap = await admin.firestore()
        .collection("users").doc(verifiedUserId)
        .collection("challenges").doc(originalChallengeId).get();
      const originalStake: number = origSnap.exists && typeof origSnap.data()!["amountCents"] === "number"
        ? origSnap.data()!["amountCents"]
        : 0;
      serverAmountCents = Math.floor(originalStake * 0.60);
      if (!serverAmountCents || serverAmountCents <= 0) {
        throw new HttpError(400, "Computed redemption refund is zero — refusing.");
      }
    }

    if (partialRefundCents && partialRefundCents > 0) {
      // Redemption Challenge win: PI already captured. Refund the SERVER-recomputed 60%
      // (serverAmountCents, validated above) — the client's partialRefundCents is ignored.
      await getStripe().refunds.create({ payment_intent: paymentIntentId, amount: serverAmountCents });
      refundedAmount = serverAmountCents!;
    } else if (pi.status === "requires_capture" && serverAmountCents && serverAmountCents < fullAmount) {
      // Hard Mode win with 20% app fee: capture full pre-auth, then refund 80%
      await getStripe().paymentIntents.capture(paymentIntentId);
      await getStripe().refunds.create({ payment_intent: paymentIntentId, amount: serverAmountCents });
      refundedAmount = serverAmountCents;
    } else if (pi.status === "requires_capture") {
      // Full cancel — no fee (e.g. nobody-failed group challenge fallback)
      await getStripe().paymentIntents.cancel(paymentIntentId);
      refundedAmount = fullAmount;
    } else {
      // Already captured: partial or full refund
      if (serverAmountCents && serverAmountCents < fullAmount) {
        await getStripe().refunds.create({ payment_intent: paymentIntentId, amount: serverAmountCents });
        refundedAmount = serverAmountCents;
      } else {
        await getStripe().refunds.create({ payment_intent: paymentIntentId });
        refundedAmount = fullAmount;
      }
    }

    // Update Firestore challenge doc with payout tracking fields
    if (challengeId && verifiedUserId) {
      const db = admin.firestore();
      await db.collection("users").doc(verifiedUserId)
        .collection("challenges").doc(challengeId)
        .set({
          payoutStatus: "refunded",
          payoutAmount: refundedAmount,
          appFeeAmount: fullAmount - refundedAmount,
          payoutDate: Date.now(),
        }, { merge: true });

      // Counter: a solo Hard Mode (or Redemption) challenge was won. The retained app
      // fee (stake minus refund) is service revenue.
      await bumpCounters({
        totalActiveChallenges: -1,
        totalCompletedChallenges: 1,
        totalRevenueCents: fullAmount - refundedAmount,
      });
    }

    res.json({ success: true });
  } catch (e) { handleError("cancelOrRefundPayment", e, res); }
});

// ── createGroupChallenge ───────────────────────────────────────────────────────
// Step 2 of creator join flow: called only after PaymentSheetResult.Completed.
// Accepts the pre-authorized paymentIntentId — does NOT create a new PaymentIntent.

export const createGroupChallenge = functions.region(REGION).https.onRequest(async (req, res) => {
  try {
    const userId = await requireAuth(req);
    const { groupId, code, groupData, paymentIntentId } = req.body as {
      groupId: string;
      code: string;
      groupData: Record<string, unknown>;
      paymentIntentId: string;
    };

    if (!groupId || !code || !groupData || !paymentIntentId) {
      throw new HttpError(400, "groupId, code, groupData, and paymentIntentId are required.");
    }
    if (groupData["creatorUserId"] !== userId) throw new HttpError(403, "creatorUserId must match the authenticated user.");

    const db = admin.firestore();

    let finalCode = code.toUpperCase();
    const existing = await db.collection("groupChallenges").where("code", "==", finalCode).limit(1).get();
    if (!existing.empty) {
      const chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
      finalCode = Array.from({ length: 6 }, () => chars[Math.floor(Math.random() * chars.length)]).join("");
    }

    const buyInCents: number = (groupData["buyInCents"] as number) ?? 500;
    const creatorDisplayName: string = (groupData["creatorDisplayName"] as string) ?? "Anonymous";

    const docData = {
      ...groupData,
      code: finalCode,
      groupId,
      status: "waiting",
      authorizationExpiresAt: Date.now() + 5 * MILLIS_PER_DAY,
      participants: [{
        userId,
        displayName: creatorDisplayName,
        paymentIntentId,
        amountCents: buyInCents,
        status: "active",
        opensToday: 0,
        timeUsedMinutes: 0,
        joinedAt: Date.now(),
      }],
      participantUserIds: [userId],
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
    };

    try {
      await db.collection("groupChallenges").doc(groupId).set(docData);
      functions.logger.info("createGroupChallenge: created", { groupId, code: finalCode, userId });
    } catch (e) {
      functions.logger.error("createGroupChallenge: Firestore write failed", { groupId, error: e });
      throw new HttpError(500, "Failed to save group challenge.");
    }

    res.json({ code: finalCode, paymentIntentId });
  } catch (e) { handleError("createGroupChallenge", e, res); }
});

// ── joinGroupChallenge ─────────────────────────────────────────────────────────

export const joinGroupChallenge = functions.region(REGION).https.onRequest(async (req, res) => {
  try {
    const userId = await requireAuth(req);
    const { groupId, displayName } = req.body as { groupId: string; displayName: string };
    if (!groupId) throw new HttpError(400, "groupId is required.");

    const db = admin.firestore();
    const docRef = db.collection("groupChallenges").doc(groupId);
    const doc = await docRef.get();
    if (!doc.exists) throw new HttpError(404, "Group challenge not found.");

    const gc = doc.data()!;
    if (gc["status"] !== "waiting") throw new HttpError(412, "This challenge is no longer accepting players.");

    const participantIds: string[] = gc["participantUserIds"] ?? [];
    if (participantIds.includes(userId)) throw new HttpError(409, "You have already joined this challenge.");
    if (participantIds.length >= (gc["maxParticipants"] ?? 5)) throw new HttpError(429, "This challenge is full.");

    const startDate: number = typeof gc["startDate"] === "number"
      ? gc["startDate"]
      : (gc["startDate"] as admin.firestore.Timestamp)?.toMillis?.() ?? 0;
    if (startDate && startDate <= Date.now()) throw new HttpError(412, "The join window for this challenge has closed.");

    const buyInCents: number = gc["buyInCents"] ?? 500;

    const customerId = await getOrCreateStripeCustomer(userId);
    const paymentIntent = await getStripe().paymentIntents.create({
      amount: buyInCents,
      currency: "eur",
      customer: customerId,
      capture_method: "manual",
      metadata: { userId, groupId, displayName: displayName ?? "Anonymous", type: "group_challenge_buy_in" },
      description: `Detox Group Challenge buy-in — ${groupId}`,
    });

    functions.logger.info("joinGroupChallenge: payment intent created", { groupId, userId });
    res.json({ paymentIntentId: paymentIntent.id, clientSecret: paymentIntent.client_secret, isImmediateCapture: false });
  } catch (e) { handleError("joinGroupChallenge", e, res); }
});

// ── confirmGroupJoin ───────────────────────────────────────────────────────────
// Called after PaymentSheetResult.Completed — adds user to participants only
// after Stripe confirms the payment intent is valid.

export const confirmGroupJoin = functions.region(REGION).https.onRequest(async (req, res) => {
  try {
    const userId = await requireAuth(req);
    const { groupId, paymentIntentId, deviceId } = req.body as {
      groupId: string;
      paymentIntentId: string;
      deviceId?: string;
    };
    if (!groupId) throw new HttpError(400, "groupId is required.");
    if (!paymentIntentId) throw new HttpError(400, "paymentIntentId is required.");

    const db = admin.firestore();
    const docRef = db.collection("groupChallenges").doc(groupId);
    const doc = await docRef.get();
    if (!doc.exists) throw new HttpError(404, "Group challenge not found.");

    const gc = doc.data()!;

    // Idempotency: user already in — payment was confirmed, just return success.
    const participantIds: string[] = gc["participantUserIds"] ?? [];
    if (participantIds.includes(userId)) {
      functions.logger.info("confirmGroupJoin: already joined (idempotent)", { groupId, userId });
      res.json({ success: true, alreadyJoined: true });
      return;
    }

    if (gc["status"] !== "waiting") throw new HttpError(412, "This challenge is no longer accepting players.");
    if (participantIds.length >= (gc["maxParticipants"] ?? 5)) throw new HttpError(429, "This challenge is full.");

    // Verify the PaymentIntent belongs to this user and group and is paid.
    const paymentIntent = await getStripe().paymentIntents.retrieve(paymentIntentId);
    if (paymentIntent.metadata.userId !== userId) throw new HttpError(403, "Payment intent does not belong to this user.");
    if (paymentIntent.metadata.groupId !== groupId) throw new HttpError(403, "Payment intent does not belong to this group.");

    const validStatuses = ["requires_capture", "succeeded", "processing"];
    if (!validStatuses.includes(paymentIntent.status)) {
      throw new HttpError(402, `Payment not completed. Status: ${paymentIntent.status}`);
    }

    const buyInCents: number = gc["buyInCents"] ?? 500;
    const displayName: string = paymentIntent.metadata.displayName ?? "Anonymous";

    await docRef.update({
      participants: admin.firestore.FieldValue.arrayUnion({
        userId,
        displayName,
        paymentIntentId,
        amountCents: buyInCents,
        status: "active",
        opensToday: 0,
        timeUsedMinutes: 0,
        joinedAt: Date.now(),
        // Anti-cheat: deviceId for multi-account detection (empty string if client omitted it).
        deviceId: deviceId ?? "",
      }),
      participantUserIds: admin.firestore.FieldValue.arrayUnion(userId),
    });

    functions.logger.info("confirmGroupJoin: participant added", { groupId, userId });
    res.json({ success: true });
  } catch (e) { handleError("confirmGroupJoin", e, res); }
});

// ── startGroupChallenge ────────────────────────────────────────────────────────

export const startGroupChallenge = functions.region(REGION).https.onRequest(async (req, res) => {
  try {
    const verifiedUserId = await requireAuth(req);
    const { groupId } = req.body as { groupId: string };
    if (!groupId) throw new HttpError(400, "groupId is required.");

    const db = admin.firestore();
    const docRef = db.collection("groupChallenges").doc(groupId);
    const doc = await docRef.get();
    if (!doc.exists) throw new HttpError(404, "Group challenge not found.");

    const gc = doc.data()!;
    if (gc["creatorUserId"] !== verifiedUserId) {
      throw new HttpError(403, "Only the creator can start this challenge.");
    }
    if (gc["status"] !== "waiting") {
      functions.logger.info("startGroupChallenge: already status=" + gc["status"], { groupId });
      res.json({ status: gc["status"] });
      return;
    }

    const participants = parseParticipants<{ userId: string; displayName: string; paymentIntentId: string }>(gc["participants"]);

    if (participants.length < 2) {
      for (const p of participants) {
        try {
          const pi = await getStripe().paymentIntents.retrieve(p.paymentIntentId);
          if (pi.status === "requires_capture") {
            await getStripe().paymentIntents.cancel(p.paymentIntentId);
          }
        } catch (e) {
          functions.logger.error("startGroupChallenge: cancel failed for <2 participants", { groupId, userId: p.userId, error: e });
        }
      }
      await docRef.update({ status: "cancelled" });
      functions.logger.info("startGroupChallenge: cancelled (< 2 participants)", { groupId, participants: participants.length });
      res.json({ status: "cancelled" });
      return;
    }

    // Pre-flight: all PIs must be requires_capture before we capture any.
    for (const p of participants) {
      const pi = await getStripe().paymentIntents.retrieve(p.paymentIntentId);
      if (pi.status !== "requires_capture") {
        functions.logger.warn("startGroupChallenge: payment not ready", { groupId, userId: p.userId, piStatus: pi.status });
        res.status(400).json({
          error: "payment_not_ready",
          message: `Payment for ${p.displayName} is not ready. Their authorization may have expired.`,
        });
        return;
      }
    }

    // Capture all PIs — money is now charged.
    const captured: string[] = [];
    for (const p of participants) {
      try {
        await getStripe().paymentIntents.capture(p.paymentIntentId);
        captured.push(p.paymentIntentId);
        functions.logger.info("startGroupChallenge: captured PI", { groupId, userId: p.userId });
      } catch (e) {
        functions.logger.error("startGroupChallenge: capture failed mid-loop — rolling back", { groupId, userId: p.userId, error: e });
        for (const piId of captured) {
          try { await getStripe().refunds.create({ payment_intent: piId }); } catch (re) {
            functions.logger.error("startGroupChallenge: rollback refund failed", { piId, error: re });
          }
        }
        res.status(500).json({ error: "capture_failed", message: "Failed to capture a payment. All captured payments have been refunded." });
        return;
      }
    }

    const startDate = Date.now();
    const durationDays: number = (gc["durationDays"] as number) ?? 7;
    const endDate = endOfDayMillis(startDate, durationDays);

    functions.logger.info("startGroupChallenge: setting startDate + endDate", {
      groupId,
      startDate,
      endDate,
      durationDays,
      participants: participants.length,
    });

    await docRef.update({ status: "active", startDate, endDate });
    functions.logger.info("startGroupChallenge: activated", { groupId, participants: participants.length, startDate, endDate });
    res.json({ status: "active" });
  } catch (e) { handleError("startGroupChallenge", e, res); }
});

// ── cancelGroupChallenge ───────────────────────────────────────────────────────

export const cancelGroupChallenge = functions.region(REGION).https.onRequest(async (req, res) => {
  try {
    const userId = await requireAuth(req);
    const { groupId } = req.body as { groupId: string };
    if (!groupId) throw new HttpError(400, "groupId is required.");

    const db = admin.firestore();
    const docRef = db.collection("groupChallenges").doc(groupId);
    const doc = await docRef.get();
    if (!doc.exists) throw new HttpError(404, "Group challenge not found.");

    const gc = doc.data()!;
    if (gc["status"] === "cancelled") {
      functions.logger.info("cancelGroupChallenge: already cancelled", { groupId });
      res.json({ status: "cancelled" });
      return;
    }
    if (gc["status"] !== "waiting") {
      throw new HttpError(412, `Cannot cancel a challenge in status '${gc["status"]}'.`);
    }
    if (gc["creatorUserId"] !== userId) {
      throw new HttpError(403, "Only the creator can cancel a group challenge.");
    }

    const participants = parseParticipants<Participant>(gc["participants"]);

    const updatedParticipants = [...participants];
    for (let i = 0; i < updatedParticipants.length; i++) {
      const p = updatedParticipants[i];
      if (!p.paymentIntentId) continue;
      try {
        const pi = await getStripe().paymentIntents.retrieve(p.paymentIntentId);
        if (pi.status === "requires_capture") {
          await getStripe().paymentIntents.cancel(p.paymentIntentId);
          functions.logger.info("cancelGroupChallenge: PI cancelled", { groupId, userId: p.userId });
        } else if (pi.status === "canceled") {
          functions.logger.info("cancelGroupChallenge: PI already cancelled (idempotent)", { groupId, userId: p.userId });
        } else {
          functions.logger.warn("cancelGroupChallenge: unexpected PI status", { groupId, userId: p.userId, piStatus: pi.status });
        }
        updatedParticipants[i] = { ...p, status: "refunded" };
      } catch (e) {
        functions.logger.error("cancelGroupChallenge: cancel failed", { groupId, userId: p.userId, error: e });
      }
    }

    await docRef.update({ status: "cancelled", participants: updatedParticipants });
    functions.logger.info("cancelGroupChallenge: cancelled", { groupId, participants: participants.length });
    res.json({ status: "cancelled" });
  } catch (e) { handleError("cancelGroupChallenge", e, res); }
});

// ── failParticipant ────────────────────────────────────────────────────────────

export const failParticipant = functions.region(REGION).https.onRequest(async (req, res) => {
  try {
    const verifiedUserId = await requireAuth(req);
    const { groupId, userId: failedUserId } = req.body as { groupId: string; userId: string };
    if (!groupId || !failedUserId) throw new HttpError(400, "groupId and userId are required.");
    if (verifiedUserId !== failedUserId) throw new HttpError(403, "You can only fail yourself.");

    const db = admin.firestore();
    const docRef = db.collection("groupChallenges").doc(groupId);
    const doc = await docRef.get();
    if (!doc.exists) throw new HttpError(404, "Group challenge not found.");

    const gc = doc.data()!;
    const participants = parseParticipants(gc["participants"]);

    const updatedParticipants = participants.map((p) =>
      p["userId"] === failedUserId ? { ...p, status: "failed", failedAt: Date.now() } : p
    );

    const failedParticipant = participants.find((p) => p["userId"] === failedUserId);
    if (failedParticipant?.["paymentIntentId"]) {
      try {
        const pi = await getStripe().paymentIntents.retrieve(failedParticipant["paymentIntentId"] as string);
        if (pi.status === "requires_capture") {
          await getStripe().paymentIntents.capture(pi.id);
          functions.logger.info("failParticipant: payment captured", { groupId, userId: failedUserId });
        } else if (pi.status === "succeeded") {
          // PI was already captured when creator started the challenge — no action needed.
          functions.logger.info("failParticipant: PI already captured at challenge start, skipping", { groupId, userId: failedUserId });
        } else {
          functions.logger.warn("failParticipant: unexpected PI status", { groupId, userId: failedUserId, piStatus: pi.status });
        }
      } catch (e) {
        functions.logger.error("failParticipant: capture failed", { groupId, userId: failedUserId, error: e });
      }
    }

    await docRef.update({ participants: updatedParticipants });
    functions.logger.info("failParticipant: participant failed", { groupId, userId: failedUserId });
    res.json({ success: true });
  } catch (e) { handleError("failParticipant", e, res); }
});

// ── expireGroupChallenge ───────────────────────────────────────────────────────
// Called by DailyEvaluationWorker when authorizationExpiresAt has passed.
// Any authenticated user can trigger this — no creator check.
// Idempotent: already-cancelled challenges are silently accepted.

export const expireGroupChallenge = functions.region(REGION).https.onRequest(async (req, res) => {
  try {
    await requireAuth(req);
    const { groupId } = req.body as { groupId: string };
    if (!groupId) throw new HttpError(400, "groupId is required.");

    const db = admin.firestore();
    const docRef = db.collection("groupChallenges").doc(groupId);
    const doc = await docRef.get();
    if (!doc.exists) throw new HttpError(404, "Group challenge not found.");

    const gc = doc.data()!;

    if (gc["status"] === "cancelled") {
      functions.logger.info("expireGroupChallenge: already cancelled (idempotent)", { groupId });
      res.json({ status: "cancelled" });
      return;
    }
    if (gc["status"] !== "waiting") {
      functions.logger.info("expireGroupChallenge: not in waiting status — skipping", { groupId, status: gc["status"] });
      res.json({ status: gc["status"] });
      return;
    }

    const authorizationExpiresAt: number = (gc["authorizationExpiresAt"] as number) ?? 0;
    if (authorizationExpiresAt > 0 && Date.now() < authorizationExpiresAt) {
      functions.logger.warn("expireGroupChallenge: authorization not yet expired", { groupId, authorizationExpiresAt, now: Date.now() });
      res.status(412).json({ error: "not_expired", message: "Authorization window has not yet expired." });
      return;
    }

    const participants = parseParticipants<Participant>(gc["participants"]);

    const updatedParticipants = [...participants];
    for (let i = 0; i < updatedParticipants.length; i++) {
      const p = updatedParticipants[i];
      if (!p.paymentIntentId) continue;
      try {
        const pi = await getStripe().paymentIntents.retrieve(p.paymentIntentId);
        if (pi.status === "requires_capture") {
          await getStripe().paymentIntents.cancel(p.paymentIntentId);
          functions.logger.info("expireGroupChallenge: PI cancelled", { groupId, userId: p.userId });
        } else if (pi.status === "canceled") {
          functions.logger.info("expireGroupChallenge: PI already cancelled (idempotent)", { groupId, userId: p.userId });
        } else {
          functions.logger.warn("expireGroupChallenge: unexpected PI status", { groupId, userId: p.userId, piStatus: pi.status });
        }
        updatedParticipants[i] = { ...p, status: "refunded" };
      } catch (e) {
        functions.logger.error("expireGroupChallenge: PI cancel failed", { groupId, userId: p.userId, error: e });
      }
    }

    await docRef.update({ status: "cancelled", participants: updatedParticipants });
    functions.logger.info("expireGroupChallenge: expired and cancelled", { groupId, participants: participants.length });
    res.json({ status: "cancelled" });
  } catch (e) { handleError("expireGroupChallenge", e, res); }
});

// ── leaveGroupChallenge ────────────────────────────────────────────────────────
// Regular participant (non-creator) leaves a WAITING challenge.
// Stripe PI is cancelled → 100% refund.
// If remaining participants < 2 → status set to "cancelled".

export const leaveGroupChallenge = functions.region(REGION).https.onRequest(async (req, res) => {
  try {
    const verifiedUserId = await requireAuth(req);
    const { groupId } = req.body as { groupId: string };
    if (!groupId) throw new HttpError(400, "groupId is required.");

    const db = admin.firestore();
    const docRef = db.collection("groupChallenges").doc(groupId);
    const doc = await docRef.get();
    if (!doc.exists) throw new HttpError(404, "Group challenge not found.");

    const gc = doc.data()!;

    // 2. Verify status == "waiting"
    if (gc["status"] !== "waiting") {
      throw new HttpError(400, "Du kannst eine aktive Challenge nicht verlassen.");
    }

    const participants = parseParticipants<Participant>(gc["participants"]);

    // 3. Verify user is a participant
    const participant = participants.find((p) => p.userId === verifiedUserId);
    if (!participant) {
      throw new HttpError(403, "Du bist kein Teilnehmer dieser Challenge.");
    }

    // 4. Verify user is NOT the creator
    if (gc["creatorUserId"] === verifiedUserId) {
      throw new HttpError(400, "Als Ersteller kannst du die Challenge nicht verlassen. Lösche die Challenge stattdessen.");
    }

    // 5+6. Find and verify PaymentIntent
    const paymentIntentId = participant.paymentIntentId;
    if (!paymentIntentId) throw new HttpError(500, "Kein Payment gefunden.");

    const pi = await getStripe().paymentIntents.retrieve(paymentIntentId);
    if (pi.status !== "requires_capture") {
      throw new HttpError(400, `Zahlung kann nicht storniert werden. Status: ${pi.status}`);
    }

    // 7. Cancel Stripe PI — FIRST before any Firestore write
    await getStripe().paymentIntents.cancel(paymentIntentId);
    functions.logger.info("leaveGroupChallenge: PI cancelled", { groupId, userId: verifiedUserId });

    // 8. Only after Stripe cancel → update Firestore
    const remainingParticipants = participants.filter((p) => p.userId !== verifiedUserId);
    const newStatus = remainingParticipants.length < 2 ? "cancelled" : (gc["status"] as string);

    const updateData: Record<string, unknown> = {
      participants: remainingParticipants,
      participantUserIds: admin.firestore.FieldValue.arrayRemove(verifiedUserId),
    };
    if (newStatus === "cancelled") {
      updateData["status"] = "cancelled";
    }
    await docRef.update(updateData);

    functions.logger.info("leaveGroupChallenge: participant removed", {
      groupId,
      userId: verifiedUserId,
      remaining: remainingParticipants.length,
      newStatus,
    });

    const amountCents: number = (participant.amountCents ?? (gc["buyInCents"] as number)) ?? 0;
    res.json({ success: true, amountCents });
  } catch (e) { handleError("leaveGroupChallenge", e, res); }
});

// ── deleteGroupChallenge ───────────────────────────────────────────────────────
// Creator deletes a WAITING challenge → cancels ALL participant PIs → 100% refund each.

export const deleteGroupChallenge = functions.region(REGION).https.onRequest(async (req, res) => {
  try {
    const verifiedUserId = await requireAuth(req);
    const { groupId } = req.body as { groupId: string };
    if (!groupId) throw new HttpError(400, "groupId is required.");

    const db = admin.firestore();
    const docRef = db.collection("groupChallenges").doc(groupId);
    const doc = await docRef.get();
    if (!doc.exists) throw new HttpError(404, "Group challenge not found.");

    const gc = doc.data()!;

    // 2. Verify status == "waiting"
    if (gc["status"] !== "waiting") {
      throw new HttpError(400, "Du kannst nur eine wartende Challenge löschen.");
    }

    // 3. Verify caller is the creator
    if (gc["creatorUserId"] !== verifiedUserId) {
      throw new HttpError(403, "Nur der Ersteller kann die Challenge löschen.");
    }

    const participants = parseParticipants<Participant>(gc["participants"]);

    // 4. For each participant: cancel their PI — all Stripe work BEFORE Firestore write
    const updatedParticipants = [...participants];
    for (let i = 0; i < updatedParticipants.length; i++) {
      const p = updatedParticipants[i];
      if (!p.paymentIntentId) continue;
      try {
        const pi = await getStripe().paymentIntents.retrieve(p.paymentIntentId);
        if (pi.status === "requires_capture") {
          await getStripe().paymentIntents.cancel(p.paymentIntentId);
          functions.logger.info("deleteGroupChallenge: PI cancelled", { groupId, userId: p.userId });
        } else if (pi.status === "canceled") {
          functions.logger.info("deleteGroupChallenge: PI already cancelled (idempotent)", { groupId, userId: p.userId });
        } else {
          functions.logger.warn("deleteGroupChallenge: unexpected PI status — skipping", { groupId, userId: p.userId, piStatus: pi.status });
        }
        updatedParticipants[i] = { ...p, status: "refunded" };
      } catch (e) {
        functions.logger.error("deleteGroupChallenge: PI cancel failed", { groupId, userId: p.userId, error: e });
      }
    }

    // 5. Only after ALL Stripe cancels → update Firestore
    await docRef.update({ status: "cancelled", participants: updatedParticipants });

    functions.logger.info("deleteGroupChallenge: challenge deleted by creator", {
      groupId,
      creatorId: verifiedUserId,
      participantCount: participants.length,
    });

    res.json({ success: true });
  } catch (e) { handleError("deleteGroupChallenge", e, res); }
});

// ── completeGroupChallenge ─────────────────────────────────────────────────────

export const completeGroupChallenge = functions.region(REGION).https.onRequest(async (req, res) => {
  try {
    await requireAuth(req);
    const { groupId } = req.body as { groupId: string };
    if (!groupId) throw new HttpError(400, "groupId is required.");

    const db = admin.firestore();
    const docRef = db.collection("groupChallenges").doc(groupId);
    const doc = await docRef.get();
    if (!doc.exists) throw new HttpError(404, "Group challenge not found.");

    const gc = doc.data()!;

    if (gc["status"] === "completed") {
      functions.logger.info("completeGroupChallenge: already completed", { groupId });
      res.json({ success: true, reason: "already_completed" });
      return;
    }

    const participants = parseParticipants(gc["participants"]);
    const buyInCents: number = (gc["buyInCents"] as number) ?? 0;

    const endDate: number = typeof gc["endDate"] === "number"
      ? gc["endDate"]
      : (gc["endDate"] as admin.firestore.Timestamp)?.toMillis?.() ?? 0;
    const now = Date.now();
    const expired = endDate > 0 && endDate <= now;
    const allFailed = participants.length > 0 && participants.every((p) => p["status"] === "failed");

    functions.logger.info(`Group challenge check: endDate=${endDate} now=${now} expired=${expired}`, { groupId, allFailed });

    if (!expired && !allFailed) {
      functions.logger.info("completeGroupChallenge: endDate not yet reached and not all participants failed — skipping", { groupId, endDate, now });
      res.json({ success: false, reason: "not_expired" });
      return;
    }

    // "active" = still in the challenge; "completed" = CF already marked them on a prior run.
    // Both statuses mean the participant did NOT fail — include both when counting winners.
    const failedParticipants = participants.filter((p) => p["status"] === "failed");
    const successParticipants = participants.filter(
      (p) => p["status"] === "active" || p["status"] === "completed"
    );

    // Per docs/09_payout_and_fees.md: nobodyFailed iff every participant is active OR completed.
    // Stricter than failedParticipants.length === 0 — a stale "success" status (from a partial
    // prior run of the someone-failed path) correctly returns false here instead of true.
    const nobodyFailed = participants.every(
      (p) => p["status"] === "active" || p["status"] === "completed"
    );

    functions.logger.info("completeGroupChallenge: participant statuses", {
      groupId,
      total: participants.length,
      active: participants.filter((p) => p["status"] === "active").length,
      failed: failedParticipants.length,
      completed: participants.filter((p) => p["status"] === "completed").length,
      other: participants.filter(
        (p) => p["status"] !== "active" && p["status"] !== "failed" && p["status"] !== "completed"
      ).map((p) => ({ userId: p["userId"], status: p["status"] })),
      nobodyFailed,
    });

    // ── Nobody-failed case: 100% refund for all, no app fee ─────────────────
    if (nobodyFailed) {
      functions.logger.info("completeGroupChallenge: nobodyFailed=true — full refund path", { groupId });
      const updatedParticipants = await Promise.all(participants.map(async (p) => {
        const userId = p["userId"] as string;
        const pid = p["paymentIntentId"] as string;
        if (pid) {
          try {
            const pi = await getStripe().paymentIntents.retrieve(pid);
            functions.logger.info("completeGroupChallenge: nobody-failed PI status", { groupId, userId, pid, piStatus: pi.status });
            if (pi.status === "requires_capture") {
              await getStripe().paymentIntents.cancel(pid);
              functions.logger.info("completeGroupChallenge: nobody-failed PI cancelled (full cancel)", { groupId, userId, pid });
            } else if (pi.status === "succeeded") {
              await getStripe().refunds.create({ payment_intent: pid });
              functions.logger.info("completeGroupChallenge: nobody-failed PI full-refunded", { groupId, userId, pid });
            } else {
              functions.logger.warn("completeGroupChallenge: nobody-failed PI in unexpected status — skipping Stripe op", { groupId, userId, pid, piStatus: pi.status });
            }
          } catch (e) {
            functions.logger.error("completeGroupChallenge: full refund failed", { groupId, userId, error: e });
          }
        }
        return { ...p, status: "completed", payoutStatus: "completed", finalPayout: p["amountCents"] ?? buyInCents };
      }));
      await docRef.update({
        status: "completed",
        completedAt: Date.now(),
        prizePool: 0,
        appFee: 0,
        prizePerWinner: 0,
        nobodyFailed: true,
        participants: updatedParticipants,
      });
      functions.logger.info("completeGroupChallenge: completed (nobody failed)", { groupId });
      res.json({ success: true, nobodyFailed: true });
      return;
    }

    // ── Someone-failed path: winners get 80% of own stake + prize share ─────
    functions.logger.info("completeGroupChallenge: nobodyFailed=false — someone-failed path", {
      groupId,
      winners: successParticipants.length,
      losers: failedParticipants.length,
    });

    // ── Pot calculation ───────────────────────────────────────────────────────
    const failedPot = failedParticipants.reduce((sum, p) => sum + ((p["amountCents"] as number) ?? buyInCents), 0);
    const appFee = Math.floor(failedPot * 0.10);
    const distributablePot = failedPot - appFee;
    const perWinnerBonus = successParticipants.length > 0
      ? Math.floor(distributablePot / successParticipants.length)
      : 0;

    functions.logger.info("completeGroupChallenge: pot calculation", {
      groupId,
      totalPot: participants.length * buyInCents,
      failedPot,
      appFee,
      distributablePot,
      perWinnerBonus,
      winners: successParticipants.length,
      losers: failedParticipants.length,
    });

    // ── Process winners: 80% stake refund + prize share ──────────────────────
    const updatedParticipants = await Promise.all(participants.map(async (p) => {
      if (p["status"] === "failed") {
        return { ...p, status: "failed", payoutStatus: "lost", finalPayout: 0 };
      }

      const userId = p["userId"] as string;
      const pid = p["paymentIntentId"] as string;
      const participantStake: number = (p["amountCents"] as number) ?? buyInCents;
      const stakeRefund = Math.floor(participantStake * 0.80);

      // Refund 80% of own stake — capture first if PI is still pre-authorized
      functions.logger.info("completeGroupChallenge: processing winner stake refund (80%)", {
        groupId, userId, participantStake, stakeRefund,
      });
      if (pid) {
        try {
          const pi = await getStripe().paymentIntents.retrieve(pid);
          functions.logger.info("completeGroupChallenge: winner PI status", { groupId, userId, pid, piStatus: pi.status });
          if (pi.status === "requires_capture") {
            await getStripe().paymentIntents.capture(pid);
            await getStripe().refunds.create({ payment_intent: pid, amount: stakeRefund });
            functions.logger.info("completeGroupChallenge: winner PI captured-then-partial-refunded", { groupId, userId, stakeRefund });
          } else {
            await getStripe().refunds.create({ payment_intent: pid, amount: stakeRefund });
            functions.logger.info("completeGroupChallenge: winner PI partial-refunded (already captured)", { groupId, userId, stakeRefund });
          }
        } catch (e) {
          functions.logger.error("completeGroupChallenge: stake refund failed", { groupId, userId, error: e });
        }
      }

      const displayName = (p["displayName"] as string) ?? "";

      // No bonus pot to distribute — stake refund only
      if (perWinnerBonus <= 0) {
        return { ...p, status: "success", payoutStatus: "completed", finalPayout: stakeRefund };
      }

      // Look up connected account
      let connectedAccountId: string | undefined;
      try {
        const userDoc = await db.collection("users").doc(userId).get();
        connectedAccountId = userDoc.data()?.stripeConnectedAccountId as string | undefined;
      } catch (e) {
        functions.logger.error("completeGroupChallenge: user lookup failed", { groupId, userId, error: e });
      }

      const writePendingPayout = async () => {
        await db.collection("users").doc(userId)
          .collection("pendingPayouts").add({
            amount: perWinnerBonus,
            stakeRefundCents: stakeRefund,
            currency: "eur",
            groupId,
            displayName,
            status: "pending_account_setup",
            createdAt: admin.firestore.FieldValue.serverTimestamp(),
          });
      };

      if (!connectedAccountId) {
        try { await writePendingPayout(); } catch (e) {
          functions.logger.error("completeGroupChallenge: pendingPayout write failed", { groupId, userId, error: e });
        }
        return { ...p, status: "success", payoutStatus: "pending_payout", finalPayout: stakeRefund + perWinnerBonus };
      }

      // Verify payouts are enabled before transferring
      try {
        const account = await getStripe().accounts.retrieve(connectedAccountId);
        if (!account.payouts_enabled) {
          await writePendingPayout();
          return { ...p, status: "success", payoutStatus: "pending_payout", finalPayout: stakeRefund + perWinnerBonus };
        }

        await getStripe().transfers.create({
          amount: perWinnerBonus,
          currency: "eur",
          destination: connectedAccountId,
          description: `Prize for group challenge ${groupId}`,
        });
        functions.logger.info(`Transfer sent to ${userId}: ${perWinnerBonus}`);

        // Record successful transfer on user doc
        await db.collection("users").doc(userId).set({
          [`pendingPayouts_completed.${groupId}`]: {
            amount: perWinnerBonus,
            stakeRefundCents: stakeRefund,
            status: "transferred",
            groupId,
            transferredAt: Date.now(),
          }
        }, { merge: true });

        return { ...p, status: "success", payoutStatus: "completed", finalPayout: stakeRefund + perWinnerBonus };
      } catch (e) {
        functions.logger.error("completeGroupChallenge: transfer failed", { groupId, userId, error: e });
        // Fall back to pending so money isn't lost
        try { await writePendingPayout(); } catch (_) { /* best effort */ }
        return { ...p, status: "success", payoutStatus: "pending_payout", finalPayout: stakeRefund + perWinnerBonus };
      }
    }));

    await docRef.update({
      status: "completed",
      completedAt: Date.now(),
      prizePool: distributablePot,
      appFee,
      prizePerWinner: perWinnerBonus,
      nobodyFailed: false,
      participants: updatedParticipants,
    });

    // Counter: the 10% Group Challenge fee on the failed-participants pot is service revenue.
    await bumpCounters({ totalRevenueCents: appFee });

    functions.logger.info("completeGroupChallenge: completed", { groupId });
    res.json({ success: true });
  } catch (e) { handleError("completeGroupChallenge", e, res); }
});

// ── claimPendingPayouts ───────────────────────────────────────────────────────

export const claimPendingPayouts = functions.region(REGION).https.onRequest(async (req, res) => {
  try {
    const userId = await requireAuth(req);
    const db = admin.firestore();

    const userDoc = await db.collection("users").doc(userId).get();
    const accountId = userDoc.data()?.stripeConnectedAccountId as string | undefined;

    if (!accountId) {
      res.json({ transferred: 0, skipped: 0, reason: "no_account" });
      return;
    }

    const account = await getStripe().accounts.retrieve(accountId);
    if (!account.payouts_enabled) {
      res.json({ transferred: 0, skipped: 0, reason: "onboarding_incomplete" });
      return;
    }

    const pendingSnap = await db.collection("users").doc(userId)
      .collection("pendingPayouts").get();

    if (pendingSnap.empty) {
      res.json({ transferred: 0, skipped: 0, reason: "none_pending" });
      return;
    }

    let transferred = 0;
    let skipped = 0;

    for (const doc of pendingSnap.docs) {
      const payout = doc.data();
      const amount = payout.amount as number;
      const groupId = payout.groupId as string;
      try {
        await getStripe().transfers.create({
          amount,
          currency: "eur",
          destination: accountId,
          description: `Detox Group Challenge winnings - ${groupId}`,
        });
        await doc.ref.delete();
        transferred += amount;
        functions.logger.info(`Transfer sent to ${userId}: ${amount}`);
      } catch (e) {
        functions.logger.error("claimPendingPayouts: transfer failed", { userId, amount, error: e });
        skipped += amount;
      }
    }

    res.json({ transferred, skipped });
  } catch (e) { handleError("claimPendingPayouts", e, res); }
});

// ── createConnectedAccount ────────────────────────────────────────────────────

export const createConnectedAccount = functions.region(REGION).https.onRequest(async (req, res) => {
  try {
    const verifiedUserId = await requireAuth(req);
    const { iban, accountHolderName } = req.body as {
      iban: string;
      accountHolderName: string;
    };
    if (!iban || !accountHolderName) {
      throw new HttpError(400, "iban and accountHolderName are required.");
    }

    const db = admin.firestore();

    const account = await getStripe().accounts.create({
      type: "custom",
      country: "AT",
      capabilities: {
        transfers: { requested: true },
      },
      external_account: {
        object: "bank_account",
        country: "AT",
        currency: "eur",
        account_holder_name: accountHolderName,
        account_holder_type: "individual",
        account_number: iban,
      } as any,
      tos_acceptance: {
        date: Math.floor(Date.now() / 1000),
        ip: req.ip ?? "0.0.0.0",
      },
    });

    await db.collection("users").doc(verifiedUserId).set({
      stripeConnectedAccountId: account.id,
      payoutIban: iban,
      payoutName: accountHolderName,
      payoutSetupAt: Date.now(),
    }, { merge: true });

    functions.logger.info(`Custom connected account created: ${account.id} for user ${verifiedUserId}`);
    res.json({ success: true, accountId: account.id });
  } catch (e) { handleError("createConnectedAccount", e, res); }
});

// ── getConnectedAccountStatus ─────────────────────────────────────────────────

export const getConnectedAccountStatus = functions.region(REGION).https.onRequest(async (req, res) => {
  try {
    const userId = await requireAuth(req);
    const userDoc = await admin.firestore().collection("users").doc(userId).get();
    const accountId = userDoc.data()?.stripeConnectedAccountId as string | undefined;

    if (!accountId) {
      res.json({ hasAccount: false, chargesEnabled: false, payoutsEnabled: false });
      return;
    }

    const account = await getStripe().accounts.retrieve(accountId);
    res.json({
      hasAccount: true,
      chargesEnabled: account.charges_enabled,
      payoutsEnabled: account.payouts_enabled,
    });
  } catch (e) { handleError("getConnectedAccountStatus", e, res); }
});

// ── checkPermissionViolations ─────────────────────────────────────────────────
// Finds users whose overlay/accessibility permission has been missing for > 24h
// and captures their Hard Mode Stripe payments server-side.
// Called hourly by scheduledPermissionCheck, and as fallback from DailyEvaluationWorker.

async function runPermissionViolationCheck(): Promise<number> {
  const now = Date.now();
  const twentyFourHours = MILLIS_PER_DAY;
  const db = admin.firestore();
  let processed = 0;

  let permissionDocs: admin.firestore.QueryDocumentSnapshot[] = [];
  try {
    const snapshot = await db
      .collectionGroup("permissionStatus")
      .where("permissionLostAt", "!=", null)
      .get();
    permissionDocs = snapshot.docs;
  } catch (e) {
    // A failed query (e.g. missing collection-group index) must NOT abort the
    // whole run — log and skip this pass so the usage pass still executes.
    functions.logger.error("checkPermissionViolations: permissionLostAt collection-group query failed", e);
  }

  for (const doc of permissionDocs) {
    const data = doc.data();
    const lostAt: number = data["permissionLostAt"];

    if (!lostAt || (now - lostAt) < twentyFourHours) continue;
    if (data["capturedAt"]) continue;
    if (data["permissionRestoredAt"]) continue;

    const userId = doc.ref.parent.parent!.id;

    // Solo Hard Mode challenges
    const challenges = await db.collection("users").doc(userId)
      .collection("challenges")
      .where("status", "==", "active")
      .get();

    for (const challenge of challenges.docs) {
      const cd = challenge.data();
      if (cd["mode"] !== "hard") continue;
      const paymentIntentId: string | undefined = cd["stripePaymentIntentId"] ?? cd["paymentIntentId"];
      if (!paymentIntentId) continue;

      try {
        const capturedPi = await getStripe().paymentIntents.capture(paymentIntentId);
        await challenge.ref.update({
          status: "failed",
          failReason: "permission_violation",
          failedAt: now,
          payoutStatus: "captured",
        });
        await bumpCounters({
          totalActiveChallenges: -1,
          totalFailedChallenges: 1,
          totalRevenueCents: capturedPi.amount_received,
        });
        functions.logger.info(`checkPermissionViolations: captured solo challenge ${challenge.id} user ${userId}`);
      } catch (e) {
        functions.logger.error(`checkPermissionViolations: capture failed for ${challenge.id}`, e);
      }
    }

    // Group Challenge participants
    let groupDocs: admin.firestore.QueryDocumentSnapshot[] = [];
    try {
      const groups = await db.collectionGroup("participants")
        .where("userId", "==", userId)
        .where("status", "==", "active")
        .get();
      groupDocs = groups.docs;
    } catch (e) {
      // A failed participants query must not abort the run — log and skip group
      // captures for this user; the solo capture above already ran.
      functions.logger.error(`checkPermissionViolations: participants query failed for user ${userId}`, e);
    }

    for (const participant of groupDocs) {
      const pd = participant.data();
      if (!pd["paymentIntentId"]) continue;
      try {
        await getStripe().paymentIntents.capture(pd["paymentIntentId"]);
        await participant.ref.update({
          status: "failed",
          failReason: "permission_violation",
          failedAt: now,
        });
        functions.logger.info(`checkPermissionViolations: captured group participant user ${userId}`);
      } catch (e) {
        functions.logger.error(`checkPermissionViolations: group capture failed`, e);
      }
    }

    // Mark as captured — Admin SDK only field, enforced by Firestore rules on client
    await doc.ref.update({ capturedAt: now, captureReason: "permission_loss_24h" });
    processed++;
  }

  // ── Usage violation check (accessibility disabled + blocked app used > 1h ago) ─
  const oneHour = 60 * 60 * 1000;
  let usageDocs: admin.firestore.QueryDocumentSnapshot[] = [];
  try {
    const usageSnapshot = await db
      .collectionGroup("permissionStatus")
      .where("usageViolationDetectedAt", "!=", null)
      .get();
    usageDocs = usageSnapshot.docs;
  } catch (e) {
    // A failed query (e.g. missing collection-group index) must NOT abort the
    // whole run — log and skip this pass.
    functions.logger.error("checkPermissionViolations: usageViolationDetectedAt collection-group query failed", e);
  }

  for (const doc of usageDocs) {
    const data = doc.data();
    const violatedAt: number = data["usageViolationDetectedAt"];

    if (!violatedAt || (now - violatedAt) < oneHour) continue;
    if (data["usageCapturedAt"]) continue;

    const userId = doc.ref.parent.parent!.id;

    const challenges = await db.collection("users").doc(userId)
      .collection("challenges")
      .where("status", "==", "active")
      .get();

    for (const challenge of challenges.docs) {
      const cd = challenge.data();
      if (cd["mode"] !== "hard") continue;
      const paymentIntentId: string | undefined = cd["stripePaymentIntentId"] ?? cd["paymentIntentId"];
      if (!paymentIntentId) continue;

      try {
        const capturedPi = await getStripe().paymentIntents.capture(paymentIntentId);
        await challenge.ref.update({
          status: "failed",
          failReason: "usage_violation",
          failedAt: now,
          payoutStatus: "captured",
        });
        await bumpCounters({
          totalActiveChallenges: -1,
          totalFailedChallenges: 1,
          totalRevenueCents: capturedPi.amount_received,
        });
        functions.logger.info(`checkPermissionViolations: usage violation captured solo challenge ${challenge.id} user ${userId}`);
      } catch (e) {
        functions.logger.error(`checkPermissionViolations: usage violation capture failed for ${challenge.id}`, e);
      }
    }

    await doc.ref.update({ usageCapturedAt: now, captureReason: "usage_violation_1h" });
    processed++;
  }

  return processed;
}

export const checkPermissionViolations = functions.region(REGION).https.onRequest(async (req, res) => {
  try {
    // Accept authenticated user calls (DailyEvaluationWorker fallback via Bearer token)
    // OR internal scheduler calls via x-internal-secret header
    const internalSecret = req.headers["x-internal-secret"];
    const isInternalCall = !!process.env.INTERNAL_SECRET && internalSecret === process.env.INTERNAL_SECRET;
    if (!isInternalCall) {
      await requireAuth(req);
    }

    const processed = await runPermissionViolationCheck();
    functions.logger.info(`checkPermissionViolations: processed=${processed}`);
    res.json({ processed });
  } catch (e) { handleError("checkPermissionViolations", e, res); }
});

export const scheduledPermissionCheck = functions.region(REGION)
  .pubsub.schedule("every 1 hours")
  .onRun(async () => {
    const processed = await runPermissionViolationCheck();
    functions.logger.info(`scheduledPermissionCheck: processed=${processed}`);
  });

// ── Helpers ────────────────────────────────────────────────────────────────────

// Firestore can return the participants field as an Array OR a Map/Object when
// arrayRemove+arrayUnion partial snapshots occur. This normalises both cases.
function parseParticipants<T = Record<string, unknown>>(raw: unknown): T[] {
  if (Array.isArray(raw)) return raw as T[];
  if (raw !== null && typeof raw === "object") return Object.values(raw) as T[];
  return [];
}

async function getOrCreateStripeCustomer(userId: string): Promise<string> {
  const userDoc = await admin.firestore().collection("users").doc(userId).get();
  const existingCustomerId = userDoc.data()?.stripeCustomerId as string | undefined;
  if (existingCustomerId) return existingCustomerId;

  const firebaseUser = await admin.auth().getUser(userId);
  const customer = await getStripe().customers.create({
    email: firebaseUser.email,
    name: firebaseUser.displayName,
    metadata: { firebaseUid: userId },
  });

  await admin.firestore()
    .collection("users").doc(userId)
    .set({ stripeCustomerId: customer.id }, { merge: true });

  return customer.id;
}

// ── Admin auth helper ──────────────────────────────────────────────────────────
// Verifies the caller's ID token AND that the token email matches the admin account.
async function requireAdmin(req: functions.https.Request): Promise<void> {
  const authHeader = req.headers.authorization;
  if (!authHeader?.startsWith("Bearer ")) {
    throw new HttpError(401, "Missing Authorization: Bearer <token>");
  }
  let decoded: admin.auth.DecodedIdToken;
  try {
    decoded = await admin.auth().verifyIdToken(authHeader.slice(7));
  } catch (e) {
    if (e instanceof HttpError) throw e;
    throw new HttpError(401, "Invalid or expired ID token.");
  }
  if (decoded.email !== ADMIN_EMAIL) {
    throw new HttpError(403, "Admin privileges required.");
  }
}

// ── totalUsers counter trigger ──────────────────────────────────────────────────
// Fires once when a users/{userId} document is first created (registration).
export const onUserCreated = functions.region(REGION).firestore
  .document("users/{userId}")
  .onCreate(async () => {
    await bumpCounters({ totalUsers: 1 });
  });

// ── onChallengeDeleted — cascade-delete a challenge's daily logs ──────────────────
// Firestore does NOT delete sub-collections when a parent document is deleted, so a
// per-challenge delete used to orphan its dailyLogs. This trigger clears them via the
// Admin SDK (bypasses security rules). It is also what allows the dailyLogs rules to
// block ALL client deletes — required so a cheater cannot delete an exceeded-day log to
// dodge the limitExceeded win-gate in cancelOrRefundPayment.
export const onChallengeDeleted = functions.region(REGION).firestore
  .document("users/{userId}/challenges/{challengeId}")
  .onDelete(async (_snap, context) => {
    const { userId, challengeId } = context.params as { userId: string; challengeId: string };
    const logsRef = admin.firestore()
      .collection("users").doc(userId)
      .collection("challenges").doc(challengeId)
      .collection("dailyLogs");
    try {
      // Delete in batches of 400 (under the 500-write batch limit).
      for (;;) {
        const snap = await logsRef.limit(400).get();
        if (snap.empty) break;
        const batch = admin.firestore().batch();
        snap.docs.forEach((d) => batch.delete(d.ref));
        await batch.commit();
        if (snap.size < 400) break;
      }
      functions.logger.info(`onChallengeDeleted: cleared dailyLogs for ${userId}/${challengeId}`);
    } catch (e) {
      functions.logger.error(`onChallengeDeleted: failed clearing dailyLogs for ${userId}/${challengeId}`, e);
    }
  });

// ── setUserBanStatus ────────────────────────────────────────────────────────────
// Admin-only. Bans/unbans a user on BOTH layers:
//   1) Firebase Auth `disabled` flag (hard — blocks token refresh / new sign-in)
//   2) Firestore users/{uid} flag (instant — enforced at app startup)
// A banned user with an active Hard Mode challenge keeps the existing capture rules;
// this never refunds a stake.
export const setUserBanStatus = functions.region(REGION).https.onRequest(async (req, res) => {
  try {
    if (applyCors(req, res)) return;
    await requireAdmin(req);
    const { uid, banned, reason } = req.body as {
      uid?: string;
      banned?: boolean;
      reason?: string;
    };
    if (!uid) throw new HttpError(400, "uid is required.");
    if (typeof banned !== "boolean") throw new HttpError(400, "banned (boolean) is required.");

    // Layer 2 — Firebase Auth hard disable.
    await admin.auth().updateUser(uid, { disabled: banned });

    // Layer 1 — Firestore flag for instant in-app enforcement.
    await admin.firestore().collection("users").doc(uid).set({
      disabled: banned,
      disabledReason: banned ? (reason ?? "") : null,
      disabledAt: banned ? Date.now() : null,
    }, { merge: true });

    functions.logger.info(`setUserBanStatus: uid=${uid} banned=${banned}`);
    res.json({ success: true, uid, banned });
  } catch (e) { handleError("setUserBanStatus", e, res); }
});

// ── backfillCounters ─────────────────────────────────────────────────────────────
// Admin-only, one-time (run sparingly — scans ALL users + ALL challenges). Recomputes
// counters/global from scratch and OVERWRITES the doc (not increment). Use to seed the
// counter after introducing the live increments, or to repair drift.
//
// Scope matches bumpCounters: active/completed/failed track solo HARD MODE challenges
// only (mode === "hard" && no groupChallengeId). totalRevenueCents = retained app fees
// on wins (appFeeAmount, fallback floor(20%)) + full captured stakes on fails/abandons
// + 10% Group Challenge fees from completed groups.
//
// COST NOTE: this is an unbounded collectionGroup + collection scan. It is intentionally
// admin-gated and meant to be triggered manually, not on a schedule.
export const backfillCounters = functions.region(REGION).https.onRequest(async (req, res) => {
  try {
    if (applyCors(req, res)) return;
    await requireAdmin(req);
    const db = admin.firestore();

    // 1. Total users.
    const usersSnap = await db.collection("users").get();
    const totalUsers = usersSnap.size;

    // 2. All challenges (collection-group scan).
    let totalActiveChallenges = 0;
    let totalCompletedChallenges = 0;
    let totalFailedChallenges = 0;
    let totalRevenueCents = 0;

    const challengesSnap = await db.collectionGroup("challenges").get();
    challengesSnap.forEach((doc) => {
      const c = doc.data();
      // Counter scope: solo Hard Mode only (matches the live bumpCounters increments).
      const isSoloHard = c.mode === "hard" && !c.groupChallengeId;
      if (!isSoloHard) return;

      const status = c.status as string | undefined;
      const payoutStatus = c.payoutStatus as string | undefined;
      const amountCents = typeof c.amountCents === "number" ? c.amountCents : 0;
      const appFeeAmount = typeof c.appFeeAmount === "number" ? c.appFeeAmount : 0;

      if (payoutStatus === "refunded" || status === "completed") {
        // Win: app keeps the recorded fee (or floor(20%) if not stored).
        totalCompletedChallenges++;
        totalRevenueCents += appFeeAmount > 0 ? appFeeAmount : Math.floor(amountCents * 0.20);
      } else if (payoutStatus === "captured" || status === "failed") {
        // Fail / abandon / permission capture: the full stake is service revenue.
        totalFailedChallenges++;
        totalRevenueCents += amountCents;
      } else if (status === "active") {
        totalActiveChallenges++;
      }
    });

    // 3. Group Challenge 10% fees (completed groups only).
    const groupsSnap = await db.collection("groupChallenges").where("status", "==", "completed").get();
    groupsSnap.forEach((doc) => {
      const g = doc.data();
      if (typeof g.appFee === "number") totalRevenueCents += g.appFee;
    });

    const counters = {
      totalUsers,
      totalActiveChallenges,
      totalCompletedChallenges,
      totalFailedChallenges,
      totalRevenueCents,
      updatedAt: Date.now(),
    };

    // OVERWRITE (not increment) — this is the authoritative recompute.
    await db.collection("counters").doc("global").set(counters);

    functions.logger.info("backfillCounters complete", counters);
    res.json({ success: true, counters });
  } catch (e) { handleError("backfillCounters", e, res); }
});

// ── detectSuspiciousUsers ─────────────────────────────────────────────────────────
// Admin-only, READ-ONLY anti-cheat analysis. Computes an ADDITIVE risk score per user
// from 5 signals and returns the flagged users (sorted by riskScore desc). It is a
// FLAGGING system only — it NEVER bans, modifies, or deletes any user data. A human
// admin reviews each flag and decides (clear / ban) in the dashboard.
//
// Signals (points):
//   1. Shared IBAN     (40) — 2+ users share the same payoutIban.
//   2. Shared deviceId (40) — 2+ users share the same Android deviceId (solo challenges
//                              + group participants).
//   3. Rooted device   (25) — any Hard Mode challenge created with isRooted === true.
//   4. Perfect win     (20) — completed solo Hard Mode with >= 3 daily logs, ALL with
//                              consciousOpens == 0 AND totalMinutes == 0.
//   5. Instant win     (15) — completed solo Hard Mode in < 1 day of elapsed time.
//
// COST NOTE: unbounded scans (all users + collectionGroup challenges + all groups +
// per-completed-challenge dailyLogs). Admin-gated + manual trigger only (the dashboard
// "Analyse starten" button shows a confirmation modal). Move to a scheduled/cached job
// if it grows expensive.
export const detectSuspiciousUsers = functions.region(REGION).https.onRequest(async (req, res) => {
  try {
    if (applyCors(req, res)) return;
    await requireAdmin(req);
    const db = admin.firestore();

    const POINTS = {
      sharedIban: 40,
      sharedDevice: 40,
      rooted: 25,
      perfectWin: 20,
      instantWin: 15,
    };

    // ── Load all users ──────────────────────────────────────────────────────────
    interface UserInfo { userId: string; username: string; email: string; payoutIban: string | null }
    const users: Record<string, UserInfo> = {};
    const usersSnap = await db.collection("users").get();
    usersSnap.forEach((doc) => {
      const u = doc.data();
      const ibanRaw = typeof u.payoutIban === "string" ? u.payoutIban.trim() : "";
      users[doc.id] = {
        userId: doc.id,
        username: u.username || u.displayName || "",
        email: u.email || "",
        payoutIban: ibanRaw.length > 0 ? ibanRaw : null,
      };
    });

    // Per-user accumulator: triggered signals + the set of accounts they are linked to.
    interface Signal { type: string; description: string; points: number }
    interface Flag { signals: Signal[]; sharedWith: Set<string> }
    const flags: Record<string, Flag> = {};
    function ensureFlag(uid: string): Flag {
      let f = flags[uid];
      if (!f) { f = { signals: [], sharedWith: new Set<string>() }; flags[uid] = f; }
      return f;
    }
    function pushUnique(uid: string, signal: Signal): void {
      const f = ensureFlag(uid);
      if (!f.signals.some((s) => s.type === signal.type)) f.signals.push(signal);
    }
    function plural(n: number, singular: string, plural: string): string {
      return n === 1 ? singular : plural;
    }

    // ── Signal 1 — Shared IBAN (40) ───────────────────────────────────────────────
    const ibanToUsers: Record<string, string[]> = {};
    for (const u of Object.values(users)) {
      if (u.payoutIban) {
        const list = ibanToUsers[u.payoutIban] || (ibanToUsers[u.payoutIban] = []);
        list.push(u.userId);
      }
    }
    for (const uids of Object.values(ibanToUsers)) {
      if (uids.length < 2) continue;
      for (const uid of uids) {
        const others = uids.length - 1;
        pushUnique(uid, {
          type: "shared_iban",
          description: `Gleiche IBAN wie ${others} ${plural(others, "anderes Konto", "andere Konten")}.`,
          points: POINTS.sharedIban,
        });
        const f = ensureFlag(uid);
        uids.forEach((o) => { if (o !== uid) f.sharedWith.add(o); });
      }
    }

    // ── Scan challenges: deviceIds + rooted + collect completed solo Hard Mode ─────
    const deviceToUsers: Record<string, Set<string>> = {};
    function addDevice(deviceId: unknown, uid: string): void {
      if (typeof deviceId === "string" && deviceId.trim().length > 0 && uid) {
        const key = deviceId.trim();
        const set = deviceToUsers[key] || (deviceToUsers[key] = new Set<string>());
        set.add(uid);
      }
    }

    interface CompletedChallenge {
      uid: string;
      ref: admin.firestore.DocumentReference;
      startDate: number;
      endDate: number;
      payoutDate: number | null;
    }
    const completedHard: CompletedChallenge[] = [];

    const challengesSnap = await db.collectionGroup("challenges").get();
    challengesSnap.forEach((doc) => {
      const c = doc.data();
      const uid = doc.ref.parent.parent ? doc.ref.parent.parent.id : "";
      if (!uid) return;

      addDevice(c.deviceId, uid);

      // Signal 3 — Rooted device.
      if (c.isRooted === true) {
        pushUnique(uid, {
          type: "rooted",
          description: "Hard Mode auf einem gerooteten Gerät erstellt.",
          points: POINTS.rooted,
        });
      }

      // Collect completed solo Hard Mode wins for signals 4 + 5 (exclude group + redemption).
      const isSoloHard = c.mode === "hard" && !c.groupChallengeId && c.isRedemption !== true;
      const won = c.status === "completed" || c.payoutStatus === "refunded";
      if (isSoloHard && won) {
        completedHard.push({
          uid,
          ref: doc.ref,
          startDate: tsToMillis(c.startDate),
          endDate: tsToMillis(c.endDate),
          payoutDate: c.payoutDate != null ? tsToMillis(c.payoutDate) : null,
        });
      }
    });

    // Group participants also carry a deviceId (written by confirmGroupJoin).
    const groupsSnap = await db.collection("groupChallenges").get();
    groupsSnap.forEach((doc) => {
      const participants = (doc.data().participants || []) as Array<Record<string, unknown>>;
      participants.forEach((p) => addDevice(p.deviceId, typeof p.userId === "string" ? p.userId : ""));
    });

    // ── Signal 2 — Shared deviceId (40) ───────────────────────────────────────────
    for (const set of Object.values(deviceToUsers)) {
      if (set.size < 2) continue;
      const uids = Array.from(set);
      for (const uid of uids) {
        const others = uids.length - 1;
        pushUnique(uid, {
          type: "shared_device",
          description: `Gleiche Geräte-ID wie ${others} ${plural(others, "anderes Konto", "andere Konten")}.`,
          points: POINTS.sharedDevice,
        });
        const f = ensureFlag(uid);
        uids.forEach((o) => { if (o !== uid) f.sharedWith.add(o); });
      }
    }

    // ── Signals 4 + 5 — per completed solo Hard Mode challenge ────────────────────
    for (const ch of completedHard) {
      // Signal 5 — Instant win: completed in < 1 day of actual elapsed time.
      const completionTs = ch.payoutDate != null ? ch.payoutDate : ch.endDate;
      const elapsed = completionTs - ch.startDate;
      if (ch.startDate > 0 && elapsed >= 0 && elapsed < MILLIS_PER_DAY) {
        pushUnique(ch.uid, {
          type: "instant_win",
          description: "Challenge in unter 24 Stunden abgeschlossen (zu schnell gewonnen).",
          points: POINTS.instantWin,
        });
      }

      // Signal 4 — Perfect win: >= 3 daily logs, ALL with 0 opens AND 0 minutes.
      const logsSnap = await ch.ref.collection("dailyLogs").get();
      if (logsSnap.size >= 3) {
        const allZero = logsSnap.docs.every((d) => {
          const l = d.data();
          const opens = typeof l.consciousOpens === "number" ? l.consciousOpens : 0;
          const minutes = typeof l.totalMinutes === "number" ? l.totalMinutes : 0;
          return opens === 0 && minutes === 0;
        });
        if (allZero) {
          pushUnique(ch.uid, {
            type: "perfect_win",
            description: `Perfekter Gewinn: 0 Öffnungen und 0 Minuten an ${logsSnap.size} Tagen (echte Nutzer öffnen Apps gelegentlich).`,
            points: POINTS.perfectWin,
          });
        }
      }
    }

    // ── Attach review decisions (false positives + prior bans) ────────────────────
    const reviews: Record<string, { decision?: string; reviewedAt?: number; note?: string }> = {};
    const reviewsSnap = await db.collection("antiCheatReviews").get();
    reviewsSnap.forEach((doc) => { reviews[doc.id] = doc.data(); });

    // ── Build sorted response ─────────────────────────────────────────────────────
    const flagged = Object.keys(flags)
      .map((uid) => {
        const f = flags[uid];
        const u = users[uid];
        const riskScore = f.signals.reduce((sum, s) => sum + s.points, 0);
        const r = reviews[uid];
        return {
          userId: uid,
          username: u ? u.username : "",
          email: u ? u.email : "",
          riskScore,
          signals: f.signals,
          sharedWith: Array.from(f.sharedWith),
          reviewed: r
            ? { decision: r.decision || null, reviewedAt: r.reviewedAt || null, note: r.note || null }
            : null,
        };
      })
      .filter((x) => x.signals.length > 0)
      .sort((a, b) => b.riskScore - a.riskScore);

    functions.logger.info("detectSuspiciousUsers complete", {
      scannedUsers: usersSnap.size,
      scannedChallenges: challengesSnap.size,
      scannedGroups: groupsSnap.size,
      flagged: flagged.length,
    });
    res.json({ success: true, flaggedCount: flagged.length, flagged });
  } catch (e) { handleError("detectSuspiciousUsers", e, res); }
});
